package fr.yandexdown

import android.app.DownloadManager
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private var clipboardLink by mutableStateOf("")

    override fun onResume() {
        super.onResume()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val candidate = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        clipboardLink = candidate.takeIf { isYandexLink(it) }.orEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        setContent { YandexDownScreen(applicationContext, sharedText, clipboardLink) }
    }
}

private val http = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

private data class DiskItem(val name: String, val path: String, val type: String, val size: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YandexDownScreen(context: Context, initialLink: String = "", clipboardLink: String = "") {
    var link by remember { mutableStateOf(initialLink.ifBlank { clipboardLink }) }
    LaunchedEffect(initialLink, clipboardLink) {
        val suggested = initialLink.ifBlank { clipboardLink }
        if (suggested.isNotBlank() && (link.isBlank() || !isYandexLink(link))) link = suggested
    }
    var publicKey by remember { mutableStateOf("") }
    var currentPath by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("YandexDown") }
    var status by remember { mutableStateOf("Colle un lien public Yandex Disk pour commencer.") }
    var busy by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<DiskItem>>(emptyList()) }
    var selected by remember { mutableStateOf<DiskItem?>(null) }
    val scope = rememberCoroutineScope()

    fun runTask(message: String, task: suspend () -> Unit) {
        scope.launch {
            busy = true
            status = message
            try { task() } catch (e: Exception) {
                status = e.message?.take(300) ?: "Une erreur est survenue."
            } finally { busy = false }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    Icon(Icons.Default.CloudDownload, contentDescription = null,
                        modifier = Modifier.padding(start = 16.dp))
                }
            )
        }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (clipboardLink.isNotBlank() && clipboardLink != link) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Lien Yandex détecté dans le presse-papiers", style = MaterialTheme.typography.titleSmall)
                                Text(clipboardLink, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { link = clipboardLink }) { Text("Importer") }
                        }
                    }
                }
                OutlinedTextField(
                    value = link, onValueChange = { link = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Lien public Yandex Disk") },
                    leadingIcon = { Icon(Icons.Default.Link, null) },
                    singleLine = true
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val copied = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                                ?.coerceToText(context)?.toString()?.trim().orEmpty()
                            if (isYandexLink(copied)) { link = copied; status = "Lien importé depuis le presse-papiers." }
                            else status = "Aucun lien Yandex Disk valide dans le presse-papiers."
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Coller") }
                    Button(
                        onClick = {
                            runTask("Analyse du lien…") {
                            val parsed = withContext(Dispatchers.IO) { inspectPublicLink(link.trim()) }
                            publicKey = link.trim()
                            currentPath = ""
                            title = parsed.first
                            items = parsed.second
                            selected = null
                            status = if (items.isEmpty()) "Ressource reconnue. Aucun élément listé." else "${items.size} élément(s) trouvé(s)."
                        }
                    },
                    enabled = !busy && link.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Analyser / afficher le contenu")
                }
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

                if (selected != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sélection : ${selected!!.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { selected = null }) { Text("Fermer") }
                    }
                    Button(
                        onClick = {
                            val item = selected!!
                            runTask("Préparation du téléchargement…") {
                                val url = withContext(Dispatchers.IO) { getDownloadUrl(publicKey, item.path) }
                                val id = enqueueDownload(context, url, item.name)
                                status = "Téléchargement ajouté (ID $id) dans Téléchargements/YandexDown."
                                selected = null
                            }
                        },
                        enabled = !busy && selected!!.type == "file",
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Télécharger le fichier") }
                }

                if (currentPath.isNotEmpty()) {
                    TextButton(onClick = {
                        val parent = currentPath.substringBeforeLast("/", "")
                        runTask("Ouverture du dossier…") {
                            val data = withContext(Dispatchers.IO) { listPublicFolder(publicKey, parent) }
                            currentPath = parent
                            items = data
                            status = "${data.size} élément(s)."
                        }
                    }) { Text("← Dossier parent") }
                }

                if (items.isNotEmpty()) {
                    Text("Contenu", style = MaterialTheme.typography.titleMedium)
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = true),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(items, key = { it.path }) { item ->
                            ListItem(
                                headlineContent = { Text(item.name, maxLines = 2) },
                                supportingContent = {
                                    Text(if (item.type == "dir") "Dossier" else formatSize(item.size))
                                },
                                leadingContent = {
                                    Icon(if (item.type == "dir") Icons.Default.Folder else Icons.Default.InsertDriveFile, null)
                                },
                                modifier = Modifier.clickable {
                                    if (item.type == "dir") {
                                        runTask("Chargement du dossier…") {
                                            val data = withContext(Dispatchers.IO) { listPublicFolder(publicKey, item.path) }
                                            currentPath = item.path
                                            items = data
                                            status = "${data.size} élément(s) dans ${item.name}."
                                        }
                                    } else selected = item
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                    Text("Les liens publics et dossiers accessibles sont pris en charge. Les téléchargements sont enregistrés dans le dossier Téléchargements/YandexDown.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Limites actuelles : connexion cloud/OAuth, transfert cloud-à-cloud, résolveurs tiers et authentification privée restent à intégrer. Aucun CAPTCHA ni contrôle d’accès n’est contourné.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

private fun isYandexLink(value: String): Boolean =
    value.startsWith("https://") && (value.contains("disk.yandex") || value.contains("yadi.sk"))

private fun inspectPublicLink(url: String): Pair<String, List<DiskItem>> {
    require(url.startsWith("https://")) { "Utilise un lien HTTPS." }
    require(url.contains("disk.yandex") || url.contains("yadi.sk")) { "Ce lien ne semble pas être un lien Yandex Disk." }
    val json = apiGet("https://cloud-api.yandex.net/v1/disk/public/resources?public_key=${enc(url)}&limit=100")
    val name = json.optString("name", "YandexDown")
    val type = json.optString("type", "file")
    val entries = if (type == "dir") parseItems(json) else listOf(
        DiskItem(name, json.optString("path", "/"), "file", json.optLong("size"))
    )
    return name to entries
}

private fun listPublicFolder(key: String, path: String): List<DiskItem> {
    val url = "https://cloud-api.yandex.net/v1/disk/public/resources?public_key=${enc(key)}&path=${enc(path)}&limit=100"
    return parseItems(apiGet(url))
}

private fun parseItems(json: JSONObject): List<DiskItem> {
    val embedded = json.optJSONObject("_embedded") ?: return emptyList()
    val array = embedded.optJSONArray("items") ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        DiskItem(obj.optString("name"), obj.optString("path"), obj.optString("type"), obj.optLong("size"))
    }
}

private fun getDownloadUrl(key: String, path: String): String {
    val url = "https://cloud-api.yandex.net/v1/disk/public/resources/download?public_key=${enc(key)}&path=${enc(path)}"
    return apiGet(url).optString("href").takeIf { it.startsWith("https://") }
        ?: throw IllegalStateException("Yandex n’a pas fourni de lien de téléchargement.")
}

private fun apiGet(url: String): JSONObject {
    val request = Request.Builder().url(url).get().build()
    http.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val detail = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
            throw IllegalStateException("Erreur Yandex HTTP ${response.code}: ${detail.ifBlank { body.take(180) }}")
        }
        return JSONObject(body)
    }
}

private fun enqueueDownload(context: Context, url: String, name: String): Long {
    val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180).ifBlank { "yandex-file" }
    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle(safeName)
        .setDescription("Téléchargement depuis Yandex Disk")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "YandexDown/$safeName")
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(false)
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    return manager.enqueue(request)
}

private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

private fun formatSize(size: Long): String = when {
    size <= 0 -> "Fichier"
    size < 1024 -> "$size octets"
    size < 1024 * 1024 -> "${size / 1024} Ko"
    else -> String.format("%.1f Mo", size / (1024.0 * 1024.0))
}
