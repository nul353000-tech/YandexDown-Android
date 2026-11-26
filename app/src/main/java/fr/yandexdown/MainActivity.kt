package fr.yandexdown

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { YandexDownScreen() }
    }
}

private val http = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YandexDownScreen() {
    var link by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Colle un lien public Yandex Disk pour commencer.") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = lightColorScheme()) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("YandexDown") }, navigationIcon = {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.padding(start = 16.dp))
            })
        }) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Téléchargement et gestion de liens", style = MaterialTheme.typography.headlineSmall)
                Text("Version initiale : résolution des liens publics Yandex Disk via l’API officielle.")
                OutlinedTextField(
                    value = link, onValueChange = { link = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Lien Yandex Disk") },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                    singleLine = false
                )
                Button(
                    onClick = {
                        scope.launch {
                            busy = true; result = ""; status = "Analyse du lien…"
                            try {
                                val info = withContext(Dispatchers.IO) { resolveYandexPublicLink(link.trim()) }
                                result = info
                                status = "Lien analysé."
                            } catch (e: Exception) {
                                status = "Échec : ${e.message ?: "erreur inconnue"}"
                            } finally { busy = false }
                        }
                    },
                    enabled = !busy && link.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Analyser le lien")
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
                if (result.isNotBlank()) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Informations reçues", style = MaterialTheme.typography.titleMedium)
                            Text(result)
                        }
                    }
                }
                HorizontalDivider()
                Text("Fonctions prévues", style = MaterialTheme.typography.titleMedium)
                FeatureLine("Liens et dossiers publics Yandex Disk")
                FeatureLine("Accès privé via OAuth Yandex autorisé")
                FeatureLine("Mots de passe de partage fournis par l’utilisateur, si l’API les accepte")
                FeatureLine("Transfert cloud-à-cloud et stockage distant")
                FeatureLine("Résolution de liens avec moteurs externes compatibles")
                FeatureLine("Historique, reprise et récupération des médias accessibles")
                Text(
                    "Les transferts cloud, OAuth, mots de passe de partage et résolveurs externes ne sont pas encore implémentés dans cette première version. Aucun CAPTCHA ni contrôle d’accès ne sera contourné.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun FeatureLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•")
        Text(text)
    }
}

private fun resolveYandexPublicLink(publicUrl: String): String {
    require(publicUrl.startsWith("https://") || publicUrl.startsWith("http://")) { "URL invalide" }
    require("disk.yandex" in publicUrl || "yadi.sk" in publicUrl || "disk.yandex.ru" in publicUrl) {
        "Ce lien ne semble pas être un lien Yandex Disk"
    }
    val encoded = URLEncoder.encode(publicUrl, Charsets.UTF_8.name())
    val request = Request.Builder()
        .url("https://cloud-api.yandex.net/v1/disk/public/resources?public_key=$encoded")
        .get().build()
    http.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IllegalStateException("Yandex API HTTP ${response.code}: ${body.take(240)}")
        val json = JSONObject(body)
        val name = json.optString("name", "Sans nom")
        val type = json.optString("type", "inconnu")
        val size = if (json.has("size")) "\nTaille : ${json.optLong("size")} octets" else ""
        val path = json.optString("path", "")
        return "Nom : $name\nType : $type\nChemin : $path$size"
    }
}
