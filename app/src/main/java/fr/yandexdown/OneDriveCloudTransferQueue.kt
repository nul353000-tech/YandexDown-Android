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
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

internal const val ONEDRIVE_UPLOAD_CHUNK_SIZE = 10 * 1024 * 1024
private const val ONEDRIVE_UPLOAD_SESSION_BASE = "https://graph.microsoft.com/v1.0/me/drive/root:/"

private val oneDriveQueueHttp = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .writeTimeout(90, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

internal fun enqueuePersistentOneDriveTransfer(
    context: Context,
    publicKey: String,
    item: DiskItem
): UUID {
    require(publicKey.isNotBlank()) { "Lien public Yandex absent." }
    require(item.type == "file" && item.size > 0L) {
        "Sélectionne un fichier non vide pour le transfert OneDrive."
    }

    val request = OneTimeWorkRequestBuilder<OneDriveCloudTransferWorker>()
        .setInitialDelay(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getInt("start_delay_seconds", 0).coerceIn(0, 300).toLong(),
            TimeUnit.SECONDS
        )
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
        .putString("name_${request.id}", "OneDrive · ${item.name}")
        .commit()
    WorkManager.getInstance(context).enqueue(request)
    return request.id
}

internal fun parseOneDriveNextExpectedOffset(ranges: List<String>): Long? {
    val firstRange = ranges.firstOrNull() ?: return null
    val offset = firstRange.substringBefore('-').toLongOrNull() ?: return null
    return offset.takeIf { it >= 0L }
}

private class ExpiredOneDriveSessionException(message: String) : IOException(message)
private class RestartOneDriveSessionException(message: String) : IOException(message)

class OneDriveCloudTransferWorker(
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
        val sessionKey = "onedrive_upload_url_$id"
        val offsetKey = "onedrive_offset_$id"

        try {
            require(publicKey.isNotBlank() && path.isNotBlank() && name.isNotBlank() && expectedSize > 0L) {
                "Informations du transfert OneDrive incomplètes."
            }

            val token = refreshOneDriveAccessToken(applicationContext)
            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim().take(180).ifBlank { "yandex-file" }
            var uploadUrl = TokenVault.read(applicationContext, sessionKey)
            var offset = prefs.getLong(offsetKey, 0L)

            if (uploadUrl.isBlank()) {
                uploadUrl = createOneDriveUploadSession(token, safeName)
                TokenVault.write(applicationContext, sessionKey, uploadUrl)
                offset = 0L
                prefs.edit().putLong(offsetKey, offset).commit()
            } else {
                val serverOffset = try {
                    queryOneDriveUploadOffset(uploadUrl)
                } catch (expired: ExpiredOneDriveSessionException) {
                    TokenVault.clear(applicationContext, sessionKey)
                    prefs.edit().remove(offsetKey).commit()
                    uploadUrl = createOneDriveUploadSession(token, safeName)
                    TokenVault.write(applicationContext, sessionKey, uploadUrl)
                    0L
                }
                offset = serverOffset
                require(offset <= expectedSize) {
                    "La progression OneDrive dépasse la taille annoncée du fichier."
                }
                prefs.edit().putLong(offsetKey, offset).commit()
            }

            val sourceUrl = getDownloadUrl(publicKey, path)
            val sourceRequest = Request.Builder().url(sourceUrl).get().build()
            val result = oneDriveQueueHttp.newCall(sourceRequest).execute().use { sourceResponse ->
                if (!sourceResponse.isSuccessful) {
                    val message = "Source Yandex HTTP ${sourceResponse.code}."
                    if (sourceResponse.code == 429 || sourceResponse.code >= 500) throw IOException(message)
                    throw IllegalStateException(message)
                }

                val input = sourceResponse.body?.byteStream()
                    ?: throw IllegalStateException("Flux source Yandex vide.")
                val md5 = MessageDigest.getInstance("MD5")
                val sha1 = MessageDigest.getInstance("SHA-1")
                val sha256 = MessageDigest.getInstance("SHA-256")
                var bytesRead = 0L
                val skipBuffer = ByteArray(64 * 1024)

                while (bytesRead < offset) {
                    val wanted = minOf(skipBuffer.size.toLong(), offset - bytesRead).toInt()
                    val count = input.read(skipBuffer, 0, wanted)
                    if (count < 0) {
                        TokenVault.clear(applicationContext, sessionKey)
                        prefs.edit().remove(offsetKey).commit()
                        throw RestartOneDriveSessionException("La source Yandex est tronquée ; une nouvelle session OneDrive sera créée.")
                    }
                    if (count == 0) continue
                    md5.update(skipBuffer, 0, count)
                    sha1.update(skipBuffer, 0, count)
                    sha256.update(skipBuffer, 0, count)
                    bytesRead += count
                }

                setProgress(
                    workDataOf(
                        "downloaded_bytes" to bytesRead,
                        "total_bytes" to expectedSize
                    )
                )

                val buffer = ByteArray(ONEDRIVE_UPLOAD_CHUNK_SIZE)
                var remote = JSONObject()
                while (bytesRead < expectedSize) {
                    if (isStopped) throw CancellationException("Transfert OneDrive annulé.")
                    val remaining = expectedSize - bytesRead
                    val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                    val count = readOneDriveChunk(input, buffer, wanted)
                    require(count > 0) {
                        "Flux Yandex interrompu à l’octet $bytesRead sur $expectedSize."
                    }

                    md5.update(buffer, 0, count)
                    sha1.update(buffer, 0, count)
                    sha256.update(buffer, 0, count)

                    val end = bytesRead + count - 1L
                    val putRequest = Request.Builder()
                        .url(uploadUrl)
                        .header("Content-Length", count.toString())
                        .header("Content-Range", "bytes $bytesRead-$end/$expectedSize")
                        .put(buffer.copyOf(count).toRequestBody("application/octet-stream".toMediaType()))
                        .build()

                    oneDriveQueueHttp.newCall(putRequest).execute().use { response ->
                        val raw = response.body?.string().orEmpty()
                        when (response.code) {
                            202 -> {
                                val nextOffset = parseOneDriveResponseOffset(raw)
                                    ?: queryOneDriveUploadOffset(uploadUrl)
                                require(nextOffset > bytesRead && nextOffset <= expectedSize) {
                                    "OneDrive n'a pas confirmé une progression valide."
                                }
                                offset = nextOffset
                                prefs.edit().putLong(offsetKey, offset).commit()
                            }
                            200, 201 -> {
                                bytesRead += count
                                require(bytesRead == expectedSize) {
                                    "OneDrive a finalisé avant la fin des ${expectedSize} octets attendus."
                                }
                                remote = JSONObject(raw)
                            }
                            404, 410 -> {
                                TokenVault.clear(applicationContext, sessionKey)
                                prefs.edit().remove(offsetKey).commit()
                                throw RestartOneDriveSessionException("La session OneDrive a expiré ; nouvelle session requise.")
                            }
                            416 -> {
                                val serverOffset = runCatching { queryOneDriveUploadOffset(uploadUrl) }.getOrNull()
                                if (serverOffset == null) {
                                    TokenVault.clear(applicationContext, sessionKey)
                                    prefs.edit().remove(offsetKey).commit()
                                } else {
                                    prefs.edit().putLong(offsetKey, serverOffset).commit()
                                }
                                throw RestartOneDriveSessionException("OneDrive a demandé une resynchronisation de l'offset.")
                            }
                            else -> {
                                val message = "OneDrive upload HTTP ${response.code}: ${raw.take(180)}"
                                if (response.code == 429 || response.code >= 500) throw IOException(message)
                                throw IllegalStateException(message)
                            }
                        }
                    }

                    if (remote.length() == 0) {
                        bytesRead = offset
                        setProgress(
                            workDataOf(
                                "downloaded_bytes" to bytesRead,
                                "total_bytes" to expectedSize
                            )
                        )
                    }
                }

                require(bytesRead == expectedSize) {
                    "Taille source incorrecte : $bytesRead octets reçus sur $expectedSize annoncés."
                }
                require(input.read() == -1) {
                    "La source Yandex contient davantage de données que la taille annoncée."
                }

                val actualMd5 = md5.digest().toHex()
                require(expectedMd5.isBlank() || expectedMd5.equals(actualMd5, ignoreCase = true)) {
                    "Le MD5 de la source Yandex ne correspond pas aux métadonnées."
                }
                val actualSha1 = sha1.digest().toHex()
                val actualSha256 = sha256.digest().toHex()
                val remoteSize = remote.optLong("size", -1L)
                require(remoteSize == expectedSize) {
                    "Taille distante OneDrive incorrecte : $remoteSize au lieu de $expectedSize octets."
                }
                val remoteSha1 = remote.optJSONObject("file")
                    ?.optJSONObject("hashes")
                    ?.optString("sha1Hash")
                    .orEmpty()
                val hashVerified = remoteSha1.isNotBlank() &&
                    remoteSha1.equals(actualSha1, ignoreCase = true)
                require(remoteSha1.isBlank() || hashVerified) {
                    "Le SHA-1 renvoyé par OneDrive ne correspond pas au fichier envoyé."
                }

                TokenVault.clear(applicationContext, sessionKey)
                prefs.edit().remove(offsetKey).commit()
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
        } catch (restart: RestartOneDriveSessionException) {
            Result.retry()
        } catch (expired: ExpiredOneDriveSessionException) {
            Result.retry()
        } catch (network: IOException) {
            Result.retry()
        } catch (error: Exception) {
            Result.failure(workDataOf("error" to (error.message ?: "Transfert OneDrive échoué.")))
        }
    }
}

private fun refreshOneDriveAccessToken(context: Context): String {
    val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val current = TokenVault.read(context, "onedrive_access_token")
    if (current.isNotBlank() &&
        System.currentTimeMillis() < settings.getLong("onedrive_expiry", 0L) - 60_000L
    ) return current

    val clientId = settings.getString("onedrive_client_id", "").orEmpty()
    val refreshToken = TokenVault.read(context, "onedrive_refresh_token")
    require(clientId.isNotBlank() && refreshToken.isNotBlank()) {
        "Reconnecte OneDrive : identifiant d'application ou jeton de renouvellement absent."
    }

    val body = FormBody.Builder()
        .add("client_id", clientId)
        .add("grant_type", "refresh_token")
        .add("refresh_token", refreshToken)
        .add("scope", "offline_access Files.ReadWrite User.Read")
        .build()
    val request = Request.Builder()
        .url("https://login.microsoftonline.com/common/oauth2/v2.0/token")
        .post(body)
        .build()

    return oneDriveQueueHttp.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val message = "Renouvellement OneDrive HTTP ${response.code}."
            if (response.code == 429 || response.code >= 500) throw IOException(message)
            throw IllegalStateException(message)
        }
        val json = JSONObject(raw)
        val token = json.optString("access_token")
        require(token.isNotBlank()) { "Jeton OneDrive renouvelé absent." }
        TokenVault.write(context, "onedrive_access_token", token)
        json.optString("refresh_token").takeIf { it.isNotBlank() }
            ?.let { TokenVault.write(context, "onedrive_refresh_token", it) }
        settings.edit()
            .putLong("onedrive_expiry", System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L)
            .commit()
        token
    }
}

private fun createOneDriveUploadSession(token: String, safeName: String): String {
    val encodedName = URLEncoder.encode(safeName, "UTF-8").replace("+", "%20")
    val metadata = JSONObject()
        .put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "rename"))
    val request = Request.Builder()
        .url("${ONEDRIVE_UPLOAD_SESSION_BASE}${encodedName}:/createUploadSession")
        .header("Authorization", "Bearer $token")
        .header("Content-Type", "application/json")
        .post(metadata.toString().toRequestBody("application/json".toMediaType()))
        .build()

    return oneDriveQueueHttp.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val message = "Création de session OneDrive HTTP ${response.code}."
            if (response.code == 429 || response.code >= 500) throw IOException(message)
            throw IllegalStateException(message)
        }
        JSONObject(raw).optString("uploadUrl")
            .takeIf { it.startsWith("https://") }
            ?: throw IllegalStateException("URL de session OneDrive absente ou invalide.")
    }
}

private fun queryOneDriveUploadOffset(uploadUrl: String): Long {
    val request = Request.Builder().url(uploadUrl).get().build()
    return oneDriveQueueHttp.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (response.code == 404 || response.code == 410) {
            throw ExpiredOneDriveSessionException("La session OneDrive a expiré.")
        }
        if (!response.isSuccessful) {
            val message = "Vérification de session OneDrive HTTP ${response.code}."
            if (response.code == 429 || response.code >= 500) throw IOException(message)
            throw IllegalStateException(message)
        }
        parseOneDriveResponseOffset(raw)
            ?: throw IOException("OneDrive n'a pas renvoyé nextExpectedRanges.")
    }
}

private fun parseOneDriveResponseOffset(raw: String): Long? {
    val ranges = JSONObject(raw).optJSONArray("nextExpectedRanges") ?: return null
    val values = (0 until ranges.length()).mapNotNull { index ->
        ranges.optString(index).takeIf { it.isNotBlank() }
    }
    return parseOneDriveNextExpectedOffset(values)
}

private fun readOneDriveChunk(input: InputStream, buffer: ByteArray, limit: Int): Int {
    var count = 0
    while (count < limit) {
        val read = input.read(buffer, count, limit - count)
        if (read < 0) break
        if (read == 0) continue
        count += read
    }
    return count
}

private fun ByteArray.toHex(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
