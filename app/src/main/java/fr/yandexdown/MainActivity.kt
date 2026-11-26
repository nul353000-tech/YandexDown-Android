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
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.SecretKey

class MainActivity : ComponentActivity() {
    private var clipboardLink by mutableStateOf("")
    private var sharedLink by mutableStateOf("")

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedLink = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    }

    override fun onResume() {
        super.onResume()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val candidate = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        clipboardLink = candidate.takeIf { isYandexLink(it) }.orEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedLink = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        setContent { YandexDownScreen(applicationContext, sharedLink, clipboardLink) }
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
    var autoProcessedLink by remember { mutableStateOf("") }
    var yandexClientId by remember { mutableStateOf(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("yandex_client_id", "") ?: "") }
    var yandexToken by remember { mutableStateOf(TokenVault.read(context, "yandex_token")) }
    var tokenEntry by remember { mutableStateOf("") }
    var showAccountSettings by remember { mutableStateOf(false) }
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

    LaunchedEffect(initialLink, clipboardLink) {
        val candidate = when {
            isYandexLink(clipboardLink) && clipboardLink.trim() != autoProcessedLink -> clipboardLink.trim()
            isYandexLink(initialLink) && initialLink.trim() != autoProcessedLink -> initialLink.trim()
            else -> ""
        }
        if (candidate.isNotBlank()) {
            autoProcessedLink = candidate
            link = candidate
            runTask("Analyse automatique du lien détecté…") {
                val parsed = withContext(Dispatchers.IO) { inspectPublicLink(candidate) }
                publicKey = parsed.third
                link = parsed.third
                currentPath = ""
                title = parsed.first
                items = parsed.second
                selected = null
                status = if (items.isEmpty()) "Ressource reconnue. Aucun élément listé." else "${items.size} élément(s) trouvé(s)."
            }
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
                            publicKey = parsed.third
                            link = parsed.third
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
                OutlinedButton(onClick = { showAccountSettings = !showAccountSettings }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showAccountSettings) "Masquer les connexions" else "Comptes cloud et connexions")
                }
                if (showAccountSettings) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Yandex Disk privé", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = yandexClientId,
                                onValueChange = { yandexClientId = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Identifiant de l'application Yandex") },
                                singleLine = true
                            )
                            OutlinedButton(
                                enabled = yandexClientId.isNotBlank(),
                                onClick = {
                                    context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("yandex_client_id", yandexClientId.trim()).apply()
                                    val authUrl = "https://oauth.yandex.com/authorize?response_type=token&client_id=${enc(yandexClientId.trim())}&redirect_uri=${enc("https://oauth.yandex.com/verification_code")}"
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Autoriser Yandex dans le navigateur") }
                            OutlinedTextField(
                                value = tokenEntry,
                                onValueChange = { tokenEntry = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Jeton OAuth Yandex") },
                                placeholder = { Text("Colle le jeton reçu après autorisation") },
                                singleLine = true
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = tokenEntry.isNotBlank(),
                                    onClick = {
                                        TokenVault.write(context, "yandex_token", tokenEntry.trim())
                                        yandexToken = tokenEntry.trim()
                                        tokenEntry = ""
                                        status = "Jeton Yandex enregistré de façon chiffrée sur cet appareil."
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Enregistrer") }
                                OutlinedButton(
                                    enabled = yandexToken.isNotBlank() && !busy,
                                    onClick = {
                                        runTask("Chargement du Yandex Disk privé…") {
                                            val data = withContext(Dispatchers.IO) { listPrivateFolder(yandexToken, "/") }
                                            publicKey = ""
                                            currentPath = "/"
                                            title = "Mon Yandex Disk"
                                            items = data
                                            selected = null
                                            status = "${data.size} élément(s) dans ton espace privé."
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Ouvrir mon disque") }
                            }
                            if (yandexToken.isNotBlank()) {
                                TextButton(onClick = {
                                    TokenVault.clear(context, "yandex_token")
                                    yandexToken = ""
                                    status = "Connexion Yandex supprimée de cet appareil."
                                }) { Text("Déconnecter Yandex") }
                            }
                            Text("L'identifiant d'application doit être créé dans le portail développeur Yandex. L'autorisation est demandée directement à Yandex ; aucun mot de passe n'est demandé à l'application.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

                if (selected != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sélection : ${selected!!.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { selected = null }) { Text("Fermer") }
                    }
                    if (publicKey.isNotBlank() && yandexToken.isNotBlank()) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                val item = selected!!
                                runTask("Transfert direct vers ton Yandex Disk privé…") {
                                    val result = withContext(Dispatchers.IO) {
                                        transferPublicToPrivateYandex(publicKey, item, yandexToken)
                                    }
                                    status = "Transfert cloud terminé : $result. Aucun fichier temporaire enregistré sur le téléphone."
                                    selected = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Copier directement vers mon Yandex Disk privé") }
                    }
                    Button(
                        onClick = {
                            val item = selected!!
                            runTask("Préparation du téléchargement…") {
                                val privateDownload = publicKey.isBlank() && yandexToken.isNotBlank()
                                val url = withContext(Dispatchers.IO) {
                                    if (privateDownload) getPrivateDownloadUrl(yandexToken, item.path) else getDownloadUrl(publicKey, item.path)
                                }
                                val id = enqueueDownload(context, url, item.name, if (privateDownload) yandexToken else null)
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
                        val parent = currentPath.substringBeforeLast("/", "").ifBlank { if (publicKey.isBlank()) "/" else "" }
                        runTask("Ouverture du dossier…") {
                            val data = withContext(Dispatchers.IO) {
                            if (publicKey.isBlank() && yandexToken.isNotBlank()) listPrivateFolder(yandexToken, parent.ifBlank { "/" })
                            else listPublicFolder(publicKey, parent)
                        }
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
                                trailingContent = {
                                    if (item.type == "dir") {
                                        TextButton(
                                            enabled = !busy,
                                            onClick = {
                                                runTask("Préparation de l’archive du dossier…") {
                                                    val privateDownload = publicKey.isBlank() && yandexToken.isNotBlank()
                                                    val url = withContext(Dispatchers.IO) {
                                                        if (privateDownload) getPrivateDownloadUrl(yandexToken, item.path)
                                                        else getDownloadUrl(publicKey, item.path)
                                                    }
                                                    val id = enqueueDownload(context, url, "${item.name}.zip", if (privateDownload) yandexToken else null)
                                                    status = "Archive du dossier ajoutée aux téléchargements (ID $id)."
                                                }
                                            }
                                        ) { Text("ZIP") }
                                    }
                                },
                                modifier = Modifier.clickable {
                                    if (item.type == "dir") {
                                        runTask("Chargement du dossier…") {
                                            val data = withContext(Dispatchers.IO) {
                                                if (publicKey.isBlank() && yandexToken.isNotBlank()) listPrivateFolder(yandexToken, item.path)
                                                else listPublicFolder(publicKey, item.path)
                                            }
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
                Text("Yandex public et privé disponibles après autorisation. Transfert cloud direct vers Yandex privé disponible. Google Drive, Dropbox, OneDrive, MEGA, pCloud et les résolveurs tiers restent à intégrer. Aucun CAPTCHA ni contrôle d’accès n’est contourné.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}



private fun transferPublicToPrivateYandex(publicKey: String, item: DiskItem, token: String): String {
    val sourceUrl = getDownloadUrl(publicKey, item.path)
    val isFolder = item.type == "dir"
    val destinationName = (item.name + if (isFolder) ".zip" else "")
        .replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180)
    val destinationPath = "/YandexDown/$destinationName"
    val uploadInfo = apiGetAuthorized(
        "https://cloud-api.yandex.net/v1/disk/resources/upload?path=${enc(destinationPath)}&overwrite=true",
        token
    )
    val uploadUrl = uploadInfo.optString("href").takeIf { it.startsWith("https://") }
        ?: throw IllegalStateException("Yandex n’a pas fourni d’URL d’envoi.")
    val sourceRequest = Request.Builder().url(sourceUrl).get().build()
    http.newCall(sourceRequest).execute().use { source ->
        if (!source.isSuccessful) throw IllegalStateException("Téléchargement source HTTP ${source.code}.")
        val sourceBody = source.body ?: throw IllegalStateException("Réponse source vide.")
        val uploadBody = object : okhttp3.RequestBody() {
            override fun contentType() = sourceBody.contentType()
            override fun contentLength() = sourceBody.contentLength()
            override fun writeTo(sink: okio.BufferedSink) {
                sourceBody.byteStream().use { input -> input.copyTo(sink.outputStream()) }
            }
        }
        val uploadRequest = Request.Builder().url(uploadUrl).put(uploadBody).build()
        http.newCall(uploadRequest).execute().use { uploaded ->
            if (!uploaded.isSuccessful) throw IllegalStateException("Envoi Yandex HTTP ${uploaded.code}.")
        }
    }
    return destinationPath
}

private fun listPrivateFolder(token: String, path: String): List<DiskItem> {
    val all = mutableListOf<DiskItem>()
    var offset = 0
    var total = Int.MAX_VALUE
    while (offset < total && offset < 10000) {
        val url = "https://cloud-api.yandex.net/v1/disk/resources?path=${enc(path)}&limit=100&offset=$offset"
        val json = apiGetAuthorized(url, token)
        val embedded = json.optJSONObject("_embedded")
        val page = parseItems(json)
        if (embedded != null && embedded.has("total")) total = embedded.optInt("total", offset + page.size)
        all.addAll(page)
        if (page.isEmpty()) break
        offset += page.size
        if (page.size < 100) break
    }
    return all
}

private fun getPrivateDownloadUrl(token: String, path: String): String {
    val url = "https://cloud-api.yandex.net/v1/disk/resources/download?path=${enc(path)}"
    return apiGetAuthorized(url, token).optString("href").takeIf { it.startsWith("https://") }
        ?: throw IllegalStateException("Yandex n’a pas fourni de lien de téléchargement privé.")
}

private fun apiGetAuthorized(url: String, token: String): JSONObject {
    val request = Request.Builder().url(url).header("Authorization", "OAuth $token").get().build()
    http.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val detail = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
            throw IllegalStateException("Yandex privé HTTP ${response.code}: ${detail.ifBlank { body.take(180) }}")
        }
        return JSONObject(body)
    }
}

private object TokenVault {
    private const val alias = "yandexdown_master_key"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
            alias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
    fun write(context: Context, name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences("secure_tokens", Context.MODE_PRIVATE).edit()
            .putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }
    fun read(context: Context, name: String): String {
        return runCatching {
            val encoded = context.getSharedPreferences("secure_tokens", Context.MODE_PRIVATE).getString(name, null) ?: return ""
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }
    fun clear(context: Context, name: String) {
        context.getSharedPreferences("secure_tokens", Context.MODE_PRIVATE).edit().remove(name).apply()
    }
}

private val supportedShorteners = setOf(
    "bit.ly", "tinyurl.com", "rebrand.ly", "cutt.ly", "t.ly", "is.gd", "shorturl.at", "rb.gy"
)

private fun isYandexHost(host: String?): Boolean {
    val value = host?.lowercase().orEmpty()
    return value == "disk.yandex.com" || value.endsWith(".disk.yandex.com") ||
        value == "disk.yandex.ru" || value.endsWith(".disk.yandex.ru") ||
        value == "yadi.sk"
}

private fun isYandexLink(value: String): Boolean {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
    val host = uri.host?.lowercase().orEmpty()
    return uri.scheme == "https" && (isYandexHost(host) || host in supportedShorteners)
}

private fun resolveYandexSharedUrl(input: String): String {
    require(input.startsWith("https://")) { "Utilise un lien HTTPS." }
    val uri = Uri.parse(input)
    if (isYandexHost(uri.host)) return input
    require(uri.host?.lowercase() in supportedShorteners) { "Ce raccourcisseur n’est pas pris en charge." }
    http.newCall(Request.Builder().url(input).header("User-Agent", "YandexDown-Android/1.0").get().build()).execute().use { response ->
        val resolved = response.request.url.toString()
        require(response.isSuccessful && isYandexHost(Uri.parse(resolved).host)) {
            "Le lien ne redirige pas directement vers Yandex Disk. Les pages CAPTCHA, anti-bot ou JavaScript doivent être validées sur le site."
        }
        return resolved
    }
}

private fun inspectPublicLink(url: String): Triple<String, List<DiskItem>, String> {
    val resolvedUrl = resolveYandexSharedUrl(url)
    val json = apiGet("https://cloud-api.yandex.net/v1/disk/public/resources?public_key=${enc(resolvedUrl)}&limit=100")
    val name = json.optString("name", "YandexDown")
    val type = json.optString("type", "file")
    val entries = if (type == "dir") listPublicFolder(url, "") else listOf(
        DiskItem(name, json.optString("path", "/"), "file", json.optLong("size"))
    )
    return Triple(name, entries, resolvedUrl)
}

private fun listPublicFolder(key: String, path: String): List<DiskItem> {
    val all = mutableListOf<DiskItem>()
    var offset = 0
    var total = Int.MAX_VALUE
    while (offset < total && offset < 10000) {
        val pathPart = if (path.isBlank()) "" else "&path=${enc(path)}"
        val url = "https://cloud-api.yandex.net/v1/disk/public/resources?public_key=${enc(key)}$pathPart&limit=100&offset=$offset"
        val json = apiGet(url)
        val embedded = json.optJSONObject("_embedded")
        val page = parseItems(json)
        if (embedded != null && embedded.has("total")) total = embedded.optInt("total", offset + page.size)
        all.addAll(page)
        if (page.isEmpty()) break
        offset += page.size
        if (page.size < 100) break
    }
    return all
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

private fun enqueueDownload(context: Context, url: String, name: String, bearerToken: String? = null): Long {
    val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180).ifBlank { "yandex-file" }
    val request = DownloadManager.Request(Uri.parse(url))
        .apply { if (!bearerToken.isNullOrBlank()) addRequestHeader("Authorization", "OAuth $bearerToken") }
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
