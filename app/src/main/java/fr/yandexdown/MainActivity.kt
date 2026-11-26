package fr.yandexdown

import android.app.DownloadManager
import android.app.Activity
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
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
import okhttp3.FormBody
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.security.SecureRandom
import java.security.MessageDigest
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.SecretKey
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException

class MainActivity : ComponentActivity() {
    private var clipboardLink by mutableStateOf("")
    private var sharedLink by mutableStateOf("")
    private var oauthCallback by mutableStateOf("")

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val data = intent.data
        if (data?.scheme == "yandexdown" && data.host == "oauth") oauthCallback = data.toString()
        else sharedLink = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
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
        oauthCallback = intent?.data?.takeIf { it.scheme == "yandexdown" && it.host == "oauth" }?.toString().orEmpty()
        setContent { YandexDownScreen(this, sharedLink, clipboardLink, oauthCallback) }
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
fun YandexDownScreen(context: Context, initialLink: String = "", clipboardLink: String = "", oauthCallback: String = "") {
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
    var googleDriveToken by remember { mutableStateOf(TokenVault.read(context, "google_drive_token")) }
    var dropboxClientId by remember { mutableStateOf(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("dropbox_client_id", "") ?: "") }
    var dropboxToken by remember { mutableStateOf(TokenVault.read(context, "dropbox_access_token")) }
    var oneDriveClientId by remember { mutableStateOf(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("onedrive_client_id", "") ?: "") }
    var oneDriveToken by remember { mutableStateOf(TokenVault.read(context, "onedrive_access_token")) }
    var pCloudClientId by remember { mutableStateOf(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("pcloud_client_id", "") ?: "") }
    var pCloudToken by remember { mutableStateOf(TokenVault.read(context, "pcloud_access_token")) }
    var pCloudHost by remember { mutableStateOf(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("pcloud_host", "eapi.pcloud.com") ?: "eapi.pcloud.com") }
    var tokenEntry by remember { mutableStateOf("") }
    var showAccountSettings by remember { mutableStateOf(false) }
    var showLinkTools by remember { mutableStateOf(true) }
    var resolverInput by remember { mutableStateOf("") }
    var resolverOutput by remember { mutableStateOf("") }
    var bypassCityStatus by remember { mutableStateOf("Non vérifié") }
    var bypassCityCatalog by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val googleAuthorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val authResult = Identity.getAuthorizationClient(context)
                    .getAuthorizationResultFromIntent(result.data)
                val token = authResult.accessToken.orEmpty()
                if (token.isNotBlank()) {
                    TokenVault.write(context, "google_drive_token", token)
                    googleDriveToken = token
                    status = "Google Drive autorisé. Le jeton est chiffré sur cet appareil."
                } else status = "Google n’a pas renvoyé de jeton d’accès."
            } catch (e: ApiException) {
                status = "Autorisation Google échouée : ${e.statusCode}."
            }
        } else status = "Autorisation Google annulée."
    }

    fun connectGoogleDrive() {
        val activity = context as? Activity
        if (activity == null) {
            status = "Impossible d’ouvrir l’autorisation Google depuis ce contexte."
            return
        }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file")))
            .build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { authorization ->
                if (authorization.hasResolution()) {
                    val pending = authorization.pendingIntent
                    if (pending != null) {
                        googleAuthorizationLauncher.launch(
                            IntentSenderRequest.Builder(pending.intentSender).build()
                        )
                    } else status = "Google demande une autorisation supplémentaire non disponible."
                } else {
                    val token = authorization.accessToken.orEmpty()
                    if (token.isNotBlank()) {
                        TokenVault.write(context, "google_drive_token", token)
                        googleDriveToken = token
                        status = "Google Drive connecté."
                    } else status = "Aucun jeton Google reçu."
                }
            }
            .addOnFailureListener { error ->
                status = "Connexion Google impossible : ${error.message ?: "erreur inconnue"}"
            }
    }

    fun saveProviderSetting(key: String, value: String) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(key, value.trim()).apply()
    }
    fun launchOAuth(provider: String, clientId: String, authBase: String, scopes: String = "") {
        if (clientId.isBlank()) { status = "Renseigne d’abord l’identifiant de l’application $provider."; return }
        val stateBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val stateValue = Base64.encodeToString(stateBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val verifierBytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.encodeToString(verifierBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        context.getSharedPreferences("oauth", Context.MODE_PRIVATE).edit()
            .putString("${provider}_state", stateValue).putString("${provider}_verifier", verifier).apply()
        val redirect = "yandexdown://oauth/$provider"
        val url = when (provider) {
            "dropbox" -> "https://www.dropbox.com/oauth2/authorize?client_id=${enc(clientId)}&response_type=code&redirect_uri=${enc(redirect)}&token_access_type=offline&code_challenge=${enc(challenge)}&code_challenge_method=S256&state=${enc(stateValue)}"
            "onedrive" -> "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=${enc(clientId)}&response_type=code&redirect_uri=${enc(redirect)}&response_mode=query&scope=${enc(scopes)}&code_challenge=${enc(challenge)}&code_challenge_method=S256&state=${enc(stateValue)}"
            else -> "https://my.pcloud.com/oauth2/authorize?client_id=${enc(clientId)}&response_type=token&redirect_uri=${enc(redirect)}&state=${enc(stateValue)}"
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun connectDropbox() {
        saveProviderSetting("dropbox_client_id", dropboxClientId)
        launchOAuth("dropbox", dropboxClientId, "https://www.dropbox.com/oauth2/authorize")
    }
    fun connectOneDrive() {
        saveProviderSetting("onedrive_client_id", oneDriveClientId)
        launchOAuth("onedrive", oneDriveClientId, "https://login.microsoftonline.com", "offline_access Files.ReadWrite User.Read")
    }
    fun connectPCloud() {
        saveProviderSetting("pcloud_client_id", pCloudClientId)
        launchOAuth("pcloud", pCloudClientId, "https://my.pcloud.com/oauth2/authorize")
    }

    fun runTask(message: String, task: suspend () -> Unit) {
        scope.launch {
            busy = true
            status = message
            try { task() } catch (e: Exception) {
                status = e.message?.take(300) ?: "Une erreur est survenue."
            } finally { busy = false }
        }
    }

    LaunchedEffect(oauthCallback) {
        if (oauthCallback.isNotBlank()) {
            runTask("Finalisation de la connexion cloud…") {
                val callback = Uri.parse(oauthCallback)
                val provider = callback.lastPathSegment.orEmpty()
                val prefs = context.getSharedPreferences("oauth", Context.MODE_PRIVATE)
                val state = callback.getQueryParameter("state")
                    ?: callback.fragment?.let { Uri.parse("https://callback.invalid/?$it").getQueryParameter("state") }
                val expected = prefs.getString("${provider}_state", null)
                require(!expected.isNullOrBlank() && state == expected) { "Vérification OAuth échouée (state invalide). Recommence la connexion." }
                when (provider) {
                    "dropbox" -> {
                        val code = callback.getQueryParameter("code") ?: error("Code Dropbox absent.")
                        val verifier = prefs.getString("dropbox_verifier", null) ?: error("Code PKCE Dropbox absent.")
                        val clientId = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("dropbox_client_id", "").orEmpty()
                        val token = withContext(Dispatchers.IO) { exchangeOAuthCode("https://api.dropboxapi.com/oauth2/token", clientId, "yandexdown://oauth/dropbox", code, verifier) }
                        TokenVault.write(context, "dropbox_access_token", token.optString("access_token"))
                        TokenVault.write(context, "dropbox_refresh_token", token.optString("refresh_token"))
                        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                            .putLong("dropbox_expiry", System.currentTimeMillis() + token.optLong("expires_in", 14400L) * 1000L).apply()
                        dropboxToken = token.optString("access_token")
                    }
                    "onedrive" -> {
                        val code = callback.getQueryParameter("code") ?: error("Code Microsoft absent.")
                        val verifier = prefs.getString("onedrive_verifier", null) ?: error("Code PKCE Microsoft absent.")
                        val clientId = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("onedrive_client_id", "").orEmpty()
                        val token = withContext(Dispatchers.IO) { exchangeOAuthCode("https://login.microsoftonline.com/common/oauth2/v2.0/token", clientId, "yandexdown://oauth/onedrive", code, verifier, "offline_access Files.ReadWrite User.Read") }
                        TokenVault.write(context, "onedrive_access_token", token.optString("access_token"))
                        TokenVault.write(context, "onedrive_refresh_token", token.optString("refresh_token"))
                        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                            .putLong("onedrive_expiry", System.currentTimeMillis() + token.optLong("expires_in", 3600L) * 1000L).apply()
                        oneDriveToken = token.optString("access_token")
                    }
                    "pcloud" -> {
                        val fragment = callback.fragment ?: error("Réponse pCloud absente.")
                        val params = Uri.parse("https://callback.invalid/?$fragment")
                        val token = params.getQueryParameter("access_token") ?: error("Jeton pCloud absent.")
                        val hostname = params.getQueryParameter("hostname")?.lowercase()
                            ?.takeIf { it == "api.pcloud.com" || it == "eapi.pcloud.com" }
                            ?: "eapi.pcloud.com"
                        TokenVault.write(context, "pcloud_access_token", token)
                        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("pcloud_host", hostname).apply()
                        pCloudToken = token
                        pCloudHost = hostname
                    }
                    else -> error("Fournisseur OAuth inconnu.")
                }
                prefs.edit().remove("${provider}_state").remove("${provider}_verifier").apply()
                status = "Connexion $provider terminée. Le jeton est chiffré sur cet appareil."
            }
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
                OutlinedButton(onClick = { showLinkTools = !showLinkTools }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showLinkTools) "Masquer les outils de liens" else "Outils de liens intégrés")
                }
                if (showLinkTools) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Résolveur intégré", style = MaterialTheme.typography.titleMedium)
                            Text("Interface native : colle un lien, identifie le service et suis les redirections HTTP simples sans ouvrir un site dans le navigateur.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = resolverInput,
                                onValueChange = { resolverInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Lien à analyser") },
                                singleLine = true
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        resolverInput = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                                            ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Coller") }
                                Button(
                                    enabled = resolverInput.isNotBlank() && !busy,
                                    onClick = {
                                        runTask("Analyse et résolution de la redirection…") {
                                            val result = withContext(Dispatchers.IO) { resolveOrdinaryRedirect(resolverInput.trim()) }
                                            resolverOutput = result
                                            status = "Redirection HTTP résolue. Vérifie l’adresse finale avant de l’utiliser."
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Résoudre") }
                            }
                            if (resolverOutput.isNotBlank()) {
                                Text("Résultat", style = MaterialTheme.typography.labelLarge)
                                SelectionContainer { Text(resolverOutput, style = MaterialTheme.typography.bodySmall) }
                                TextButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Lien résolu", resolverOutput))
                                    status = "Lien résolu copié."
                                }) { Text("Copier le résultat") }
                            }
                            HorizontalDivider()
                            Text("Services de résolution", style = MaterialTheme.typography.titleSmall)
                            Text("Bypass.city · Linkvertise · Lootlinks · Work.ink · Cuty.io · Boost.ink · Adfoc.us · Rekonise", style = MaterialTheme.typography.bodySmall)
                            Text("Bypass.city API : $bypassCityStatus", style = MaterialTheme.typography.bodySmall)
                            Text("Confidentialité : le lien saisi est transmis au service Bypass.city pour traitement. N’envoie pas de lien privé contenant un jeton ou un code d’accès.", style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = resolverInput.startsWith("https://") && !busy,
                                    onClick = {
                                        runTask("Résolution via l’API officielle Bypass.city…") {
                                            val result = withContext(Dispatchers.IO) { resolveWithBypassCity(resolverInput.trim()) }
                                            resolverOutput = result
                                            status = "Réponse Bypass.city reçue. Vérifie le lien final avant de l’ouvrir."
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Résoudre avec Bypass.city") }
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = {
                                        runTask("Vérification de l’API Bypass.city…") {
                                            val result = withContext(Dispatchers.IO) { checkBypassCityApi() }
                                            bypassCityStatus = result.first
                                            bypassCityCatalog = result.second
                                            status = "Vérification Bypass.city terminée."
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Vérifier l’API") }
                                OutlinedButton(
                                    enabled = bypassCityCatalog.isNotBlank(),
                                    onClick = { resolverOutput = bypassCityCatalog },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Voir les sites pris en charge") }
                            }
                            Text("Les autres services nécessitant une clé API, une validation humaine ou un protocole non documenté restent désactivés jusqu’à une intégration officielle vérifiée. Aucun CAPTCHA n’est contourné.", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                OutlinedButton(onClick = { showAccountSettings = !showAccountSettings }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showAccountSettings) "Masquer les connexions" else "Comptes cloud et connexions")
                }
                if (showAccountSettings) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Google Drive — destination cloud par défaut", style = MaterialTheme.typography.titleMedium)
                            Text(if (googleDriveToken.isBlank()) "Non connecté" else "Connecté sur cet appareil", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { connectGoogleDrive() },
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (googleDriveToken.isBlank()) "Connecter Google Drive" else "Renouveler l’accès") }
                                if (googleDriveToken.isNotBlank()) {
                                    OutlinedButton(onClick = {
                                        TokenVault.clear(context, "google_drive_token")
                                        googleDriveToken = ""
                                        status = "Google Drive déconnecté."
                                    }) { Text("Déconnecter") }
                                }
                            }
                            HorizontalDivider()
                            Text("Dropbox", style = MaterialTheme.typography.titleMedium)
                            Text(if (dropboxToken.isBlank()) "Non connecté" else "Connecté · transfert cloud activé", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = dropboxClientId, onValueChange = { dropboxClientId = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("Dropbox App key") }, singleLine = true)
                            Button(onClick = { connectDropbox() }, enabled = dropboxClientId.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()) { Text("Connecter Dropbox (OAuth PKCE)") }
                            HorizontalDivider()
                            Text("OneDrive", style = MaterialTheme.typography.titleMedium)
                            Text(if (oneDriveToken.isBlank()) "Non connecté" else "Connecté · transfert Graph activé", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = oneDriveClientId, onValueChange = { oneDriveClientId = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("Microsoft Application (client) ID") }, singleLine = true)
                            Button(onClick = { connectOneDrive() }, enabled = oneDriveClientId.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()) { Text("Connecter OneDrive (OAuth PKCE)") }
                            HorizontalDivider()
                            Text("pCloud", style = MaterialTheme.typography.titleMedium)
                            Text(if (pCloudToken.isBlank()) "Non connecté" else "Connecté · serveur $pCloudHost", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = pCloudClientId, onValueChange = { pCloudClientId = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("pCloud App ID") }, singleLine = true)
                            Button(onClick = { connectPCloud() }, enabled = pCloudClientId.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()) { Text("Connecter pCloud") }
                            HorizontalDivider()
                            Text("MEGA", style = MaterialTheme.typography.titleMedium)
                            Text("Le SDK officiel MEGA est natif C++/JNI. Il doit être compilé et lié comme module Android dédié avant d'activer un transfert ; aucun faux bouton de connexion n'est affiché.", style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
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
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
                    if (publicKey.isNotBlank() && dropboxToken.isNotBlank() && selected!!.type == "file") {
                        Button(enabled = !busy, onClick = {
                            val item = selected!!
                            runTask("Transfert direct vers Dropbox…") {
                                val clientId = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("dropbox_client_id", "").orEmpty()
                                val access = withContext(Dispatchers.IO) { refreshDropboxToken(context, clientId) }
                                val result = withContext(Dispatchers.IO) { transferPublicToDropbox(publicKey, item, access) }
                                dropboxToken = TokenVault.read(context, "dropbox_access_token")
                                status = "Transfert Dropbox terminé : $result. Aucun fichier temporaire permanent sur le téléphone."
                                selected = null
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Envoyer directement vers Dropbox") }
                    }
                    if (publicKey.isNotBlank() && oneDriveToken.isNotBlank() && selected!!.type == "file") {
                        Button(enabled = !busy, onClick = {
                            val item = selected!!
                            runTask("Transfert direct vers OneDrive…") {
                                val clientId = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("onedrive_client_id", "").orEmpty()
                                val access = withContext(Dispatchers.IO) { refreshOneDriveToken(context, clientId) }
                                val result = withContext(Dispatchers.IO) { transferPublicToOneDrive(publicKey, item, access) }
                                oneDriveToken = TokenVault.read(context, "onedrive_access_token")
                                status = "Transfert OneDrive terminé : $result. Aucun fichier temporaire permanent sur le téléphone."
                                selected = null
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Envoyer directement vers OneDrive") }
                    }
                    if (publicKey.isNotBlank() && pCloudToken.isNotBlank() && selected!!.type == "file") {
                        Button(enabled = !busy, onClick = {
                            val item = selected!!
                            runTask("Transfert direct vers pCloud…") {
                                val result = withContext(Dispatchers.IO) { transferPublicToPCloud(publicKey, item, pCloudToken, pCloudHost) }
                                status = "Transfert pCloud terminé : $result. Aucun fichier temporaire permanent sur le téléphone."
                                selected = null
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Envoyer directement vers pCloud") }
                    }
                    if (publicKey.isNotBlank() && googleDriveToken.isNotBlank() && selected!!.type == "file") {
                        Button(
                            enabled = !busy,
                            onClick = {
                                val item = selected!!
                                runTask("Transfert direct vers Google Drive…") {
                                    val result = withContext(Dispatchers.IO) {
                                        transferPublicToGoogleDrive(publicKey, item, googleDriveToken)
                                    }
                                    status = "Transfert Google Drive terminé : $result. Aucun fichier temporaire sur le téléphone."
                                    selected = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Envoyer directement vers Google Drive") }
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
                                                    val id = enqueueDownload(context, url, "${item.name}.zip")
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




private fun transferPublicToGoogleDrive(publicKey: String, item: DiskItem, accessToken: String): String {
    require(item.type == "file") { "Sélectionne un fichier pour le transfert Google Drive." }
    val metadata = JSONObject().put("name", item.name)
    val init = Request.Builder()
        .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")
        .header("Authorization", "Bearer $accessToken")
        .header("Content-Type", "application/json; charset=UTF-8")
        .header("X-Upload-Content-Type", "application/octet-stream")
        .apply { if (item.size > 0) header("X-Upload-Content-Length", item.size.toString()) }
        .post(metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
    val sessionUrl = http.newCall(init).execute().use { response ->
        if (!response.isSuccessful) {
            val body = response.body?.string().orEmpty()
            throw IllegalStateException("Google Drive HTTP ${response.code}: ${body.take(220)}")
        }
        response.header("Location")?.takeIf { it.startsWith("https://") }
            ?: throw IllegalStateException("Google Drive n’a pas retourné d’URL de session.")
    }
    val sourceUrl = getDownloadUrl(publicKey, item.path)
    http.newCall(Request.Builder().url(sourceUrl).get().build()).execute().use { source ->
        if (!source.isSuccessful) throw IllegalStateException("Téléchargement Yandex HTTP ${source.code}.")
        val sourceBody = source.body ?: throw IllegalStateException("Réponse Yandex vide.")
        val uploadBody = object : okhttp3.RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = sourceBody.contentLength()
            override fun writeTo(sink: okio.BufferedSink) {
                sourceBody.byteStream().use { input -> input.copyTo(sink.outputStream()) }
            }
        }
        val upload = Request.Builder().url(sessionUrl).put(uploadBody)
            .apply { if (item.size > 0) header("Content-Length", item.size.toString()) }
            .build()
        http.newCall(upload).execute().use { response ->
            if (response.code != 200 && response.code != 201) {
                val body = response.body?.string().orEmpty()
                throw IllegalStateException("Envoi Google Drive HTTP ${response.code}: ${body.take(220)}")
            }
            val result = JSONObject(response.body?.string().orEmpty())
            return result.optString("name", item.name)
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


private fun exchangeOAuthCode(endpoint: String, clientId: String, redirectUri: String, code: String, verifier: String, scope: String? = null): JSONObject {
    val builder = FormBody.Builder()
        .add("client_id", clientId)
        .add("grant_type", "authorization_code")
        .add("code", code)
        .add("redirect_uri", redirectUri)
        .add("code_verifier", verifier)
    if (!scope.isNullOrBlank()) builder.add("scope", scope)
    val body = builder.build()
    val request = Request.Builder().url(endpoint).post(body).build()
    http.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IllegalStateException("Échange OAuth HTTP ${response.code}: ${raw.take(250)}")
        return JSONObject(raw).also {
            require(it.optString("access_token").isNotBlank()) { "Le fournisseur n’a pas renvoyé de jeton." }
        }
    }
}

private fun refreshDropboxToken(context: Context, clientId: String): String {
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val current = TokenVault.read(context, "dropbox_access_token")
    if (current.isNotBlank() && System.currentTimeMillis() < prefs.getLong("dropbox_expiry", 0L) - 60000L) return current
    val refresh = TokenVault.read(context, "dropbox_refresh_token")
    require(refresh.isNotBlank()) { "Reconnecte Dropbox : jeton expiré ou absent." }
    val body = FormBody.Builder().add("client_id", clientId)
        .add("grant_type", "refresh_token").add("refresh_token", refresh).build()
    val request = Request.Builder().url("https://api.dropboxapi.com/oauth2/token").post(body).build()
    http.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IllegalStateException("Renouvellement Dropbox HTTP ${response.code}.")
        val json = JSONObject(raw)
        val token = json.optString("access_token")
        require(token.isNotBlank()) { "Jeton Dropbox renouvelé absent." }
        TokenVault.write(context, "dropbox_access_token", token)
        prefs.edit().putLong("dropbox_expiry", System.currentTimeMillis() + json.optLong("expires_in", 14400L) * 1000L).apply()
        return token
    }
}

private fun transferPublicToDropbox(publicKey: String, item: DiskItem, token: String): String {
    require(item.type == "file") { "Sélectionne un fichier." }
    val sourceUrl = getDownloadUrl(publicKey, item.path)
    http.newCall(Request.Builder().url(sourceUrl).get().build()).execute().use { source ->
        if (!source.isSuccessful) throw IllegalStateException("Téléchargement Yandex HTTP ${source.code}.")
        val sourceBody = source.body ?: error("Réponse Yandex vide.")
        val input = sourceBody.byteStream()
        val start = Request.Builder().url("https://content.dropboxapi.com/2/files/upload_session/start")
            .header("Authorization", "Bearer $token")
            .header("Dropbox-API-Arg", JSONObject().put("close", false).toString())
            .post(ByteArray(0).toRequestBody("application/octet-stream".toMediaType())).build()
        val sessionId = http.newCall(start).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Dropbox session HTTP ${response.code}: ${raw.take(200)}")
            JSONObject(raw).optString("session_id").takeIf { it.isNotBlank() } ?: error("Session Dropbox absente.")
        }
        val chunkSize = 8 * 1024 * 1024
        var offset = 0L
        val buffer = ByteArray(chunkSize)
        while (true) {
            var count = 0
            while (count < buffer.size) {
                val n = input.read(buffer, count, buffer.size - count)
                if (n < 0) break
                count += n
            }
            if (count == 0) break
            val bytes = buffer.copyOf(count)
            val appendArg = JSONObject().put("cursor", JSONObject().put("session_id", sessionId).put("offset", offset)).put("close", false)
            val append = Request.Builder().url("https://content.dropboxapi.com/2/files/upload_session/append_v2")
                .header("Authorization", "Bearer $token").header("Dropbox-API-Arg", appendArg.toString())
                .post(bytes.toRequestBody("application/octet-stream".toMediaType())).build()
            http.newCall(append).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Dropbox envoi HTTP ${response.code} à l’octet $offset.")
            }
            offset += count
            if (count < buffer.size) break
        }
        require(offset == item.size || item.size <= 0L) { "Taille transférée ($offset) différente de la taille annoncée (${item.size})." }
        val commit = JSONObject().put("path", "/${item.name}")
            .put("mode", "add").put("autorename", true).put("mute", false).put("strict_conflict", false)
        val finishArg = JSONObject()
            .put("cursor", JSONObject().put("session_id", sessionId).put("offset", offset))
            .put("commit", commit)
        val finish = Request.Builder().url("https://content.dropboxapi.com/2/files/upload_session/finish")
            .header("Authorization", "Bearer $token").header("Dropbox-API-Arg", finishArg.toString())
            .post(ByteArray(0).toRequestBody("application/octet-stream".toMediaType())).build()
        http.newCall(finish).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Dropbox finalisation HTTP ${response.code}: ${raw.take(220)}")
            return JSONObject(raw).optString("name", item.name)
        }
    }
}

private fun refreshOneDriveToken(context: Context, clientId: String): String {
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val current = TokenVault.read(context, "onedrive_access_token")
    if (current.isNotBlank() && System.currentTimeMillis() < prefs.getLong("onedrive_expiry", 0L) - 60000L) return current
    val refresh = TokenVault.read(context, "onedrive_refresh_token")
    require(refresh.isNotBlank()) { "Reconnecte OneDrive : jeton expiré ou absent." }
    val body = FormBody.Builder().add("client_id", clientId)
        .add("grant_type", "refresh_token").add("refresh_token", refresh)
        .add("scope", "offline_access Files.ReadWrite User.Read").build()
    val request = Request.Builder().url("https://login.microsoftonline.com/common/oauth2/v2.0/token").post(body).build()
    http.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IllegalStateException("Renouvellement OneDrive HTTP ${response.code}.")
        val json = JSONObject(raw)
        val token = json.optString("access_token")
        require(token.isNotBlank()) { "Jeton OneDrive renouvelé absent." }
        TokenVault.write(context, "onedrive_access_token", token)
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { TokenVault.write(context, "onedrive_refresh_token", it) }
        prefs.edit().putLong("onedrive_expiry", System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L).apply()
        return token
    }
}

private fun transferPublicToOneDrive(publicKey: String, item: DiskItem, token: String): String {
    require(item.type == "file" && item.size > 0L) { "Sélectionne un fichier non vide." }
    val safeName = item.name.replace("/", "_").replace("\\", "_")
    val metadata = JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "rename"))
    val create = Request.Builder()
        .url("https://graph.microsoft.com/v1.0/me/drive/root:/${enc(safeName)}:/createUploadSession")
        .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
        .post(metadata.toString().toRequestBody("application/json".toMediaType())).build()
    val uploadUrl = http.newCall(create).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IllegalStateException("OneDrive session HTTP ${response.code}: ${raw.take(220)}")
        JSONObject(raw).optString("uploadUrl").takeIf { it.startsWith("https://") } ?: error("URL de session OneDrive absente.")
    }
    val sourceUrl = getDownloadUrl(publicKey, item.path)
    http.newCall(Request.Builder().url(sourceUrl).get().build()).execute().use { source ->
        if (!source.isSuccessful) throw IllegalStateException("Yandex HTTP ${source.code}.")
        val input = source.body?.byteStream() ?: error("Réponse Yandex vide.")
        val chunkSize = 10 * 1024 * 1024
        val buffer = ByteArray(chunkSize)
        var offset = 0L
        while (offset < item.size) {
            var count = 0
            val wanted = minOf(buffer.size.toLong(), item.size - offset).toInt()
            while (count < wanted) {
                val n = input.read(buffer, count, wanted - count)
                if (n < 0) break
                count += n
            }
            require(count > 0) { "Flux Yandex interrompu à l’octet $offset." }
            val bytes = buffer.copyOf(count)
            val end = offset + count - 1
            val put = Request.Builder().url(uploadUrl)
                .header("Content-Length", count.toString())
                .header("Content-Range", "bytes $offset-$end/${item.size}")
                .put(bytes.toRequestBody("application/octet-stream".toMediaType())).build()
            http.newCall(put).execute().use { response ->
                if (response.code != 202 && response.code != 200 && response.code != 201) {
                    throw IllegalStateException("OneDrive envoi HTTP ${response.code} à l’octet $offset.")
                }
            }
            offset += count
        }
        return item.name
    }
}

private fun transferPublicToPCloud(publicKey: String, item: DiskItem, token: String, host: String): String {
    require(item.type == "file") { "Sélectionne un fichier." }
    val sourceUrl = getDownloadUrl(publicKey, item.path)
    http.newCall(Request.Builder().url(sourceUrl).get().build()).execute().use { source ->
        if (!source.isSuccessful) throw IllegalStateException("Yandex HTTP ${source.code}.")
        val sourceBody = source.body ?: error("Réponse Yandex vide.")
        val streamedBody = object : okhttp3.RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = -1L
            override fun writeTo(sink: okio.BufferedSink) {
                sourceBody.byteStream().use { it.copyTo(sink.outputStream()) }
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", item.name, streamedBody).build()
        val url = "https://$host/uploadfile?access_token=${enc(token)}&folderid=0&nopartial=1&renameifexists=1"
        val request = Request.Builder().url(url).post(multipart).build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("pCloud HTTP ${response.code}: ${raw.take(200)}")
            val json = JSONObject(raw)
            if (json.optInt("result", -1) != 0) throw IllegalStateException("Erreur pCloud ${json.optInt("result")}.")
            return json.optJSONArray("metadata")?.optJSONObject(0)?.optString("name") ?: item.name
        }
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



@Volatile private var lastBypassCityRequestAt = 0L

private fun resolveWithBypassCity(input: String): String {
    synchronized(Unit) {
        val now = System.currentTimeMillis()
        val waitMs = 6500L - (now - lastBypassCityRequestAt)
        require(waitMs <= 0L) { "Limite de Bypass.city : réessaie dans ${(waitMs / 1000L) + 1} seconde(s)." }
        lastBypassCityRequestAt = now
    }
    val parsedInput = Uri.parse(input)
    val inputHost = parsedInput.host?.lowercase().orEmpty()
    require(parsedInput.scheme == "https" && isKnownResolverHost(inputHost)) {
        "Ce domaine n’est pas dans la liste des services de liens pris en charge."
    }
    val specResponse = http.newCall(
        Request.Builder().url("https://api2.adbypass.org/api-json").header("Accept", "application/json").get().build()
    ).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("Documentation API Bypass.city indisponible (HTTP ${response.code}).")
        JSONObject(response.body?.string().orEmpty())
    }
    val bypassOperation = specResponse.optJSONObject("paths")?.optJSONObject("/bypass")?.optJSONObject("post")
        ?: throw IllegalStateException("L’API Bypass.city ne documente plus l’opération /bypass.")
    val requestBody = bypassOperation.optJSONObject("requestBody")
        ?: throw IllegalStateException("Le schéma de requête Bypass.city est absent.")
    val content = requestBody.optJSONObject("content")
        ?: throw IllegalStateException("Le format de requête Bypass.city est absent.")
    val jsonSchema = content.optJSONObject("application/json")?.optJSONObject("schema")
        ?: throw IllegalStateException("Le schéma JSON de Bypass.city est absent.")
    val ref = jsonSchema.optString("\$ref")
    val schema = if (ref.startsWith("#/components/schemas/")) {
        specResponse.optJSONObject("components")?.optJSONObject("schemas")
            ?.optJSONObject(ref.substringAfterLast("/"))
            ?: throw IllegalStateException("Le schéma de données Bypass.city est introuvable.")
    } else jsonSchema
    val properties = schema.optJSONObject("properties")
        ?: throw IllegalStateException("Le schéma Bypass.city ne décrit aucun champ.")
    val urlField = listOf("url", "link", "bypass").firstOrNull { properties.has(it) }
        ?: throw IllegalStateException("Le champ URL attendu n’est pas documenté par Bypass.city.")
    val required = schema.optJSONArray("required")
    val missingRequired = (0 until (required?.length() ?: 0))
        .mapNotNull { required?.optString(it) }
        .filter { it != urlField }
    require(missingRequired.isEmpty()) {
        "L’API Bypass.city exige aussi : ${missingRequired.joinToString()}. Configuration supplémentaire nécessaire."
    }
    val body = JSONObject().put(urlField, input)
    val server = specResponse.optJSONArray("servers")?.optJSONObject(0)?.optString("url")
        ?.takeIf { it.startsWith("https://") } ?: "https://api2.adbypass.org/api"
    val endpoint = server.trimEnd('/') + "/bypass"
    val request = Request.Builder().url(endpoint)
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
    http.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val message = runCatching { JSONObject(raw).optString("message") }.getOrNull().orEmpty()
            throw IllegalStateException("Bypass.city HTTP ${response.code}: ${message.ifBlank { raw.take(220) }}")
        }
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw IllegalStateException("Réponse Bypass.city illisible.")
        val resultUrl = findResolvedHttpsUrl(json)
        if (resultUrl != null) return resultUrl
        throw IllegalStateException("Bypass.city n’a pas renvoyé de lien final exploitable : ${raw.take(300)}")
    }
}

private fun findResolvedHttpsUrl(value: Any?): String? {
    if (value is JSONObject) {
        val preferred = listOf("bypassData", "result", "url", "targetUrl", "redirectUrl", "destination", "link")
        for (key in preferred) {
            val candidate = value.opt(key)
            if (candidate is String && candidate.startsWith("https://")) {
                val host = Uri.parse(candidate).host?.lowercase().orEmpty()
                if (host.isNotBlank() && host != "bypass.city" && !host.endsWith(".bypass.city") &&
                    host != "adbypass.org" && !host.endsWith(".adbypass.org")) return candidate
            }
        }
        val keys = value.keys()
        while (keys.hasNext()) {
            val found = findResolvedHttpsUrl(value.opt(keys.next()))
            if (found != null) return found
        }
    } else if (value is org.json.JSONArray) {
        for (i in 0 until value.length()) {
            val found = findResolvedHttpsUrl(value.opt(i))
            if (found != null) return found
        }
    } else if (value is String && value.startsWith("https://")) {
        val host = Uri.parse(value).host?.lowercase().orEmpty()
        if (host.isNotBlank() && host != "bypass.city" && host != "adbypass.org") return value
    }
    return null
}

private fun resolveOrdinaryRedirect(input: String): String {
    val uri = Uri.parse(input)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "Entre une adresse HTTPS valide." }
    val host = uri.host!!.lowercase()
    require(isKnownResolverHost(host)) {
        "Ce domaine n’est pas dans la liste des raccourcisseurs pris en charge."
    }
    val request = Request.Builder().url(input).header("User-Agent", "YandexDown-Android/1.0").get().build()
    http.newCall(request).execute().use { response ->
        require(response.isSuccessful) { "Le service a répondu HTTP ${response.code}. Les pages nécessitant une validation restent à confirmer sur leur service." }
        val finalUrl = response.request.url.toString()
        val finalUri = Uri.parse(finalUrl)
        require(finalUri.scheme == "https" && !finalUri.host.isNullOrBlank()) { "La redirection finale n’est pas une adresse HTTPS sûre." }
        return finalUrl
    }
}

private fun checkBypassCityApi(): Pair<String, String> {
    fun get(path: String): String {
        val request = Request.Builder().url("https://api2.adbypass.org/api/$path")
            .header("Accept", "application/json").get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Bypass.city HTTP ${response.code}.")
            return body
        }
    }
    val available = get("available")
    val catalog = get("list")
    return "API accessible" to catalog.take(5000).ifBlank { available.take(5000) }
}

private val supportedResolverDomains = setOf(
    "bit.ly", "tinyurl.com", "rebrand.ly", "cutt.ly", "t.ly", "is.gd", "shorturl.at", "rb.gy",
    "linkvertise.com", "linkvertise.net", "link-to.net", "link-center.net", "link-target.net",
    "loot-link.com", "loot-links.com", "lootlink.org", "lootlinks.co", "lootdest.org", "lootdest.info",
    "work.ink", "cuty.io", "boost.ink", "adfoc.us", "rekonise.com", "paster.so", "paster.gg"
)

private fun isKnownResolverHost(host: String): Boolean =
    supportedResolverDomains.any { host == it || host.endsWith(".$it") }

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
    require(uri.host?.lowercase()?.let { it in supportedShorteners } == true) { "Ce raccourcisseur n’est pas pris en charge." }
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
    val entries = if (type == "dir") listPublicFolder(resolvedUrl, "") else listOf(
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
