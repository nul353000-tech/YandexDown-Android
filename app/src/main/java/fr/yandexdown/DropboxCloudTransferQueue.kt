package fr.yandexdown

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val DROPBOX_CHUNK_SIZE = 8 * 1024 * 1024
private const val DROPBOX_SESSION_START = "https://content.dropboxapi.com/2/files/upload_session/start"
private const val DROPBOX_SESSION_APPEND = "https://content.dropboxapi.com/2/files/upload_session/append_v2"
private const val DROPBOX_SESSION_FINISH = "https://content.dropboxapi.com/2/files/upload_session/finish"

private val dropboxQueueHttp = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .writeTimeout(90, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

internal fun enqueuePersistentDropboxTransfer(
    context: Context,
    publicKey: String,
    item: DiskItem
): UUID {
    require(publicKey.isNotBlank()) { "Lien public Yandex absent." }
    require(item.type == "file") { "Sélectionne un fichier pour le transfert Dropbox." }

    val request = OneTimeWorkRequestBuilder<DropboxCloudTransferWorker>()
        .setInputData(
            workDataOf(
                "public_key" to publicKey,
                "path" to item.path,
                "name" to item.name,
                "expected_size" to item.size,
                "expected_md5" to item.md5
            )
        )
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .addTag(TRANSFER_QUEUE_TAG)
        .build()

    context.getSharedPreferences("transfer_queue", Context.MODE_PRIVATE).edit()
        .putString("name_${request.id}", "Dropbox · ${item.name}")
        .commit()
    WorkManager.getInstance(context).enqueue(request)
    return request.id
}

private class RestartDropboxSessionException(message: String) : IOException(message)

class DropboxCloudTransferWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val publicKey = inputData.getString("public_key").orEmpty()
        val path = inputData.getString("path").orEmpty()
        val name = inputData.getString("name").orEmpty()
        val expectedSize = inputData.getLong("expected_size", 0L)
        val expectedMd5 = inputData.getString("expected_md5").orEmpty()
        val prefs = applicationContext.getSharedPreferences("transfer_queue", Context.MODE_PRIVATE)
        val sessionKey = "dropbox_session_$id"
        val offsetKey = "dropbox_offset_$id"

        try {
            require(publicKey.isNotBlank() && path.isNotBlank() && name.isNotBlank()) {
                "Informations du transfert Dropbox incomplètes."
            }

            val token = refreshDropboxAccessToken(applicationContext)
            var sessionId = prefs.getString(sessionKey, null).orEmpty()
            var offset = prefs.getLong(offsetKey, 0L)
            if (sessionId.isBlank() || offset < 0L || (expectedSize > 0L && offset > expectedSize)) {
                sessionId = startDropboxUploadSession(token)
                offset = 0L
                check(prefs.edit().putString(sessionKey, sessionId).putLong(offsetKey, offset).commit()) {
                    "Impossible d'enregistrer la session Dropbox."
                }
            }

            val sourceUrl = getDownloadUrl(publicKey, path)
            val sourceRequest = Request.Builder().url(sourceUrl).get().build()
            val result = dropboxQueueHttp.newCall(sourceRequest).execute().use { sourceResponse ->
                if (!sourceResponse.isSuccessful) {
                    val message = "Source Yandex HTTP ${sourceResponse.code}."
                    if (sourceResponse.code == 429 || sourceResponse.code >= 500) throw IOException(message)
                    throw IllegalStateException(message)
                }

                val input = sourceResponse.body?.byteStream()
                    ?: throw IllegalStateException("Flux source Yandex vide.")
                val md5 = MessageDigest.getInstance("MD5")
                val sha256 = MessageDigest.getInstance("SHA-256")
                val dropboxHash = DropboxContentHash()
                var bytesRead = 0L
                val skipBuffer = ByteArray(64 * 1024)

                while (bytesRead < offset) {
                    val wanted = minOf(skipBuffer.size.toLong(), offset - bytesRead).toInt()
                    val count = input.read(skipBuffer, 0, wanted)
                    if (count < 0) {
                        prefs.edit().remove(sessionKey).putLong(offsetKey, 0L).commit()
                        throw RestartDropboxSessionException("La source Yandex a changé ou est tronquée ; reprise depuis zéro.")
                    }
                    if (count == 0) continue
                    md5.update(skipBuffer, 0, count)
                    sha256.update(skipBuffer, 0, count)
                    dropboxHash.update(skipBuffer, 0, count)
                    bytesRead += count
                }

                setProgress(
                    workDataOf(
                        "downloaded_bytes" to bytesRead,
                        "total_bytes" to expectedSize.coerceAtLeast(0L)
                    )
                )

                val buffer = ByteArray(DROPBOX_CHUNK_SIZE)
                while (true) {
                    if (isStopped) throw CancellationException("Transfert Dropbox annulé.")
                    val count = readChunk(input, buffer)
                    if (count == 0) break

                    md5.update(buffer, 0, count)
                    sha256.update(buffer, 0, count)
                    dropboxHash.update(buffer, 0, count)

                    val bytes = buffer.copyOf(count)
                    val appendArg = JSONObject()
                        .put("cursor", JSONObject().put("session_id", sessionId).put("offset", offset))
                        .put("close", false)
                        .toDropboxHeaderValue()
                    val appendRequest = Request.Builder()
                        .url(DROPBOX_SESSION_APPEND)
                        .header("Authorization", "Bearer $token")
                        .header("Dropbox-API-Arg", appendArg)
                        .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
                        .build()

                    dropboxQueueHttp.newCall(appendRequest).execute().use { response ->
                        if (!response.isSuccessful) {
                            val message = "Dropbox append HTTP ${response.code} à l’octet $offset."
                            if (response.code == 409) {
                                prefs.edit().remove(sessionKey).putLong(offsetKey, 0L).commit()
                                throw RestartDropboxSessionException("$message Nouvelle session requise.")
                            }
                            if (response.code == 429 || response.code >= 500) throw IOException(message)
                            throw IllegalStateException(message)
                        }
                    }

                    offset += count
                    bytesRead += count
                    check(prefs.edit().putLong(offsetKey, offset).commit()) {
                        "Impossible d'enregistrer la progression Dropbox."
                    }
                    setProgress(
                        workDataOf(
                            "downloaded_bytes" to bytesRead,
                            "total_bytes" to expectedSize.coerceAtLeast(0L)
                        )
                    )
                }

                require(expectedSize <= 0L || bytesRead == expectedSize) {
                    "Taille source incorrecte : $bytesRead octets reçus sur $expectedSize annoncés."
                }
                val actualMd5 = md5.digest().toHex()
                require(expectedMd5.isBlank() || expectedMd5.equals(actualMd5, ignoreCase = true)) {
                    "Le MD5 de la source Yandex ne correspond pas aux métadonnées."
                }
                val actualSha256 = sha256.digest().toHex()
                val actualDropboxHash = dropboxHash.finish()

                val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .trim().take(180).ifBlank { "yandex-file" }
                val commit = JSONObject()
                    .put("path", "/$safeName")
                    .put("mode", "add")
                    .put("autorename", true)
                    .put("mute", false)
                    .put("strict_conflict", false)
                val finishArg = JSONObject()
                    .put("cursor", JSONObject().put("session_id", sessionId).put("offset", offset))
                    .put("commit", commit)
                    .toDropboxHeaderValue()
                val finishRequest = Request.Builder()
                    .url(DROPBOX_SESSION_FINISH)
                    .header("Authorization", "Bearer $token")
                    .header("Dropbox-API-Arg", finishArg)
                    .post(ByteArray(0).toRequestBody("application/octet-stream".toMediaType()))
                    .build()

                val remote = dropboxQueueHttp.newCall(finishRequest).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val message = "Dropbox finalisation HTTP ${response.code}: ${raw.take(180)}"
                        if (response.code == 409) {
                            prefs.edit().remove(sessionKey).putLong(offsetKey, 0L).commit()
                            throw RestartDropboxSessionException("$message Nouvelle session requise.")
                        }
                        if (response.code == 429 || response.code >= 500) throw IOException(message)
                        throw IllegalStateException(message)
                    }
                    JSONObject(raw)
                }

                val remoteSize = remote.optLong("size", -1L)
                require(remoteSize == bytesRead) {
                    "Taille distante Dropbox incorrecte : $remoteSize au lieu de $bytesRead octets."
                }
                val remoteHash = remote.optString("content_hash")
                val hashVerified = remoteHash.isNotBlank() &&
                    remoteHash.equals(actualDropboxHash, ignoreCase = true)
                require(remoteHash.isBlank() || hashVerified) {
                    "Le content_hash renvoyé par Dropbox ne correspond pas au fichier envoyé."
                }

                prefs.edit().remove(sessionKey).remove(offsetKey).commit()
                Result.success(
                    workDataOf(
                        "sha256" to actualSha256,
                        "md5_verified" to expectedMd5.isNotBlank(),
                        "destination_size_verified" to true,
                        "destination_hash_verified" to hashVerified,
                        "destination_name" to remote.optString("name", safeName)
                    )
                )
            }
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (restart: RestartDropboxSessionException) {
            Result.retry()
        } catch (network: IOException) {
            Result.retry()
        } catch (error: Exception) {
            Result.failure(workDataOf("error" to (error.message ?: "Transfert Dropbox échoué.")))
        }
    }
}

private fun refreshDropboxAccessToken(context: Context): String {
    val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val current = TokenVault.read(context, "dropbox_access_token")
    if (current.isNotBlank() &&
        System.currentTimeMillis() < settings.getLong("dropbox_expiry", 0L) - 60_000L
    ) return current

    val clientId = settings.getString("dropbox_client_id", "").orEmpty()
    val refreshToken = TokenVault.read(context, "dropbox_refresh_token")
    require(clientId.isNotBlank() && refreshToken.isNotBlank()) {
        "Reconnecte Dropbox : identifiant d'application ou jeton de renouvellement absent."
    }

    val body = FormBody.Builder()
        .add("client_id", clientId)
        .add("grant_type", "refresh_token")
        .add("refresh_token", refreshToken)
        .build()
    val request = Request.Builder()
        .url("https://api.dropboxapi.com/oauth2/token")
        .post(body)
        .build()

    dropboxQueueHttp.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val message = "Renouvellement Dropbox HTTP ${response.code}."
            if (response.code == 429 || response.code >= 500) throw IOException(message)
            throw IllegalStateException(message)
        }
        val json = JSONObject(raw)
        val token = json.optString("access_token")
        require(token.isNotBlank()) { "Jeton Dropbox renouvelé absent." }
        TokenVault.write(context, "dropbox_access_token", token)
        settings.edit()
            .putLong("dropbox_expiry", System.currentTimeMillis() + json.optLong("expires_in", 14_400L) * 1000L)
            .commit()
        token
    }
}

private fun startDropboxUploadSession(token: String): String {
    val request = Request.Builder()
        .url(DROPBOX_SESSION_START)
        .header("Authorization", "Bearer $token")
        .header("Dropbox-API-Arg", """{"close":false}""")
        .post(ByteArray(0).toRequestBody("application/octet-stream".toMediaType()))
        .build()
    dropboxQueueHttp.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val message = "Création de session Dropbox HTTP ${response.code}."
            if (response.code == 429 || response.code >= 500) throw IOException(message)
            throw IllegalStateException(message)
        }
        JSONObject(raw).optString("session_id").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Dropbox n'a pas renvoyé d'identifiant de session.")
    }
}

private fun readChunk(input: InputStream, buffer: ByteArray): Int {
    var count = 0
    while (count < buffer.size) {
        val read = input.read(buffer, count, buffer.size - count)
        if (read < 0) break
        if (read == 0) continue
        count += read
    }
    return count
}

private fun JSONObject.toDropboxHeaderValue(): String = buildString {
    toString().forEach { character ->
        if (character.code in 0x7f..0xffff) {
            append("\\u")
            append(character.code.toString(16).padStart(4, '0'))
        } else {
            append(character)
        }
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
