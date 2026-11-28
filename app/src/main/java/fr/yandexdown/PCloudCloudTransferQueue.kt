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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val pCloudQueueHttp = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .writeTimeout(90, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

internal fun isSupportedPCloudApiHost(host: String): Boolean =
    host.lowercase() == "api.pcloud.com" || host.lowercase() == "eapi.pcloud.com"

internal fun pCloudChecksumMatches(
    remoteHashes: Map<String, String>,
    actualMd5: String,
    actualSha1: String,
    actualSha256: String
): Boolean? {
    val checks = listOf(
        remoteHashes["md5"].orEmpty() to actualMd5,
        remoteHashes["sha1"].orEmpty() to actualSha1,
        remoteHashes["sha256"].orEmpty() to actualSha256
    ).filter { it.first.isNotBlank() }
    if (checks.isEmpty()) return null
    return checks.all { (remoteHash, localHash) -> remoteHash.equals(localHash, ignoreCase = true) }
}

internal fun enqueuePersistentPCloudTransfer(
    context: Context,
    publicKey: String,
    item: DiskItem
): UUID {
    require(publicKey.isNotBlank()) { "Lien public Yandex absent." }
    require(item.type == "file" && item.size > 0L) {
        "Sélectionne un fichier non vide pour le transfert pCloud."
    }

    val request = OneTimeWorkRequestBuilder<PCloudCloudTransferWorker>()
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
        .putString("name_${request.id}", "pCloud · ${item.name}")
        .commit()
    WorkManager.getInstance(context).enqueue(request)
    return request.id
}

class PCloudCloudTransferWorker(
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
        val progressKey = "pcloud_progress_hash_$id"

        try {
            require(publicKey.isNotBlank() && path.isNotBlank() && name.isNotBlank() && expectedSize > 0L) {
                "Informations du transfert pCloud incomplètes."
            }
            val token = TokenVault.read(applicationContext, "pcloud_access_token")
            require(token.isNotBlank()) { "Reconnecte pCloud avant de lancer le transfert." }
            val settings = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val host = settings.getString("pcloud_host", "eapi.pcloud.com").orEmpty()
            require(isSupportedPCloudApiHost(host)) { "Hôte API pCloud non autorisé." }

            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim().take(180).ifBlank { "yandex-file" }
            val progressHash = UUID.randomUUID().toString()
            prefs.edit().putString(progressKey, progressHash).commit()

            val sourceUrl = getDownloadUrl(publicKey, path)
            val sourceRequest = Request.Builder().url(sourceUrl).get().build()
            val result = pCloudQueueHttp.newCall(sourceRequest).execute().use { sourceResponse ->
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
                val bytesSent = AtomicLong(0L)
                val streamBody = PCloudStreamingRequestBody(
                    input = input,
                    expectedSize = expectedSize,
                    md5 = md5,
                    sha1 = sha1,
                    sha256 = sha256,
                    bytesSent = bytesSent,
                    shouldStop = { isStopped }
                )
                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", safeName, streamBody)
                    .build()

                val encodedToken = URLEncoder.encode(token, "UTF-8")
                val encodedProgress = URLEncoder.encode(progressHash, "UTF-8")
                val uploadUrl = "https://$host/uploadfile?access_token=$encodedToken&folderid=0&nopartial=1&renameifexists=1&progresshash=$encodedProgress"
                val uploadRequest = Request.Builder()
                    .url(uploadUrl)
                    .post(multipart)
                    .build()

                val raw = supervisorScope {
                    val upload = async(Dispatchers.IO) {
                        pCloudQueueHttp.newCall(uploadRequest).execute().use { response ->
                            val body = response.body?.string().orEmpty()
                            if (!response.isSuccessful) {
                                val message = "Upload pCloud HTTP ${response.code}."
                                if (response.code == 429 || response.code >= 500) throw IOException(message)
                                throw IllegalStateException(message)
                            }
                            body
                        }
                    }
                    while (!upload.isCompleted) {
                        delay(1200)
                        val localProgress = bytesSent.get()
                        val remoteProgress = try {
                            withContext(Dispatchers.IO) {
                                queryPCloudUploadProgress(host, token, progressHash)
                            }
                        } catch (_: IOException) {
                            null
                        }
                        val uploaded = remoteProgress?.optLong("currentfileuploaded", -1L)
                            ?.takeIf { it >= 0L } ?: localProgress
                        setProgress(
                            workDataOf(
                                "downloaded_bytes" to uploaded.coerceAtMost(expectedSize),
                                "total_bytes" to expectedSize
                            )
                        )
                    }
                    upload.await()
                }

                require(bytesSent.get() == expectedSize) {
                    "Taille source incorrecte : ${bytesSent.get()} octets envoyés sur $expectedSize annoncés."
                }
                val uploadJson = JSONObject(raw)
                require(uploadJson.optInt("result", -1) == 0) {
                    "Erreur pCloud ${uploadJson.optInt("result", -1)} : ${uploadJson.optString("error", "upload échoué")}."
                }
                val metadata = uploadJson.optJSONArray("metadata")
                    ?.optJSONObject(0)
                    ?: throw IllegalStateException("pCloud n'a pas renvoyé les métadonnées du fichier.")
                val remoteSize = metadata.optLong("size", -1L)
                require(remoteSize == expectedSize) {
                    "Taille distante pCloud incorrecte : $remoteSize au lieu de $expectedSize octets."
                }

                val actualMd5 = md5.digest().toHex()
                require(expectedMd5.isBlank() || expectedMd5.equals(actualMd5, ignoreCase = true)) {
                    "Le MD5 de la source Yandex ne correspond pas aux métadonnées."
                }
                val actualSha1 = sha1.digest().toHex()
                val actualSha256 = sha256.digest().toHex()
                val fileId = metadata.optLong("fileid", -1L)
                require(fileId > 0L) { "Identifiant du fichier pCloud absent." }

                val checksum = queryPCloudChecksum(host, token, fileId)
                val remoteHashes = mapOf(
                    "md5" to checksum.optString("md5"),
                    "sha1" to checksum.optString("sha1"),
                    "sha256" to checksum.optString("sha256")
                )
                val hashVerified = pCloudChecksumMatches(remoteHashes, actualMd5, actualSha1, actualSha256)
                require(hashVerified != false) {
                    "Les empreintes renvoyées par pCloud ne correspondent pas au fichier envoyé."
                }

                prefs.edit().remove(progressKey).commit()
                Result.success(
                    workDataOf(
                        "sha256" to actualSha256,
                        "md5_verified" to expectedMd5.isNotBlank(),
                        "destination_size_verified" to true,
                        "destination_hash_verified" to (hashVerified == true),
                        "destination_name" to metadata.optString("name", safeName)
                    )
                )
            }
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (network: IOException) {
            Result.retry()
        } catch (error: Exception) {
            prefs.edit().remove(progressKey).commit()
            Result.failure(workDataOf("error" to (error.message ?: "Transfert pCloud échoué.")))
        }
    }
}

private class PCloudStreamingRequestBody(
    private val input: InputStream,
    private val expectedSize: Long,
    private val md5: MessageDigest,
    private val sha1: MessageDigest,
    private val sha256: MessageDigest,
    private val bytesSent: AtomicLong,
    private val shouldStop: () -> Boolean
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength(): Long = -1L

    override fun writeTo(sink: BufferedSink) {
        input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (shouldStop()) throw CancellationException("Transfert pCloud annulé.")
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (bytesSent.get() + count > expectedSize) {
                    throw IOException("La source Yandex dépasse la taille annoncée.")
                }
                sink.write(buffer, 0, count)
                md5.update(buffer, 0, count)
                sha1.update(buffer, 0, count)
                sha256.update(buffer, 0, count)
                bytesSent.addAndGet(count.toLong())
            }
        }
        if (bytesSent.get() != expectedSize) {
            throw IOException("Flux Yandex tronqué : ${bytesSent.get()} octets sur $expectedSize.")
        }
    }
}

private fun queryPCloudUploadProgress(host: String, token: String, progressHash: String): JSONObject? {
    val url = "https://$host/uploadprogress?access_token=${URLEncoder.encode(token, "UTF-8")}&progresshash=${URLEncoder.encode(progressHash, "UTF-8")}"
    val request = Request.Builder().url(url).get().build()
    return pCloudQueueHttp.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            if (response.code == 429 || response.code >= 500) throw IOException("Progression pCloud HTTP ${response.code}.")
            return@use null
        }
        val json = JSONObject(raw)
        if (json.optInt("result", -1) == 0) json else null
    }
}

private fun queryPCloudChecksum(host: String, token: String, fileId: Long): JSONObject {
    val url = "https://$host/checksumfile?access_token=${URLEncoder.encode(token, "UTF-8")}&fileid=$fileId"
    val request = Request.Builder().url(url).get().build()
    return pCloudQueueHttp.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val message = "Vérification pCloud HTTP ${response.code}."
            if (response.code == 429 || response.code >= 500) throw IOException(message)
            throw IllegalStateException(message)
        }
        val json = JSONObject(raw)
        require(json.optInt("result", -1) == 0) {
            "Erreur pCloud pendant la vérification du fichier."
        }
        json
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
