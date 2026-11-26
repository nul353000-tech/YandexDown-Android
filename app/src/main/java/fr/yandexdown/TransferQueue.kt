package fr.yandexdown

import android.Manifest
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

internal const val TRANSFER_QUEUE_TAG = "yandexdown.transfer"
private const val TRANSFER_PREFS = "transfer_queue"

internal fun enqueuePersistentDownload(
    context: Context,
    publicKey: String,
    item: DiskItem,
    isPrivate: Boolean
): UUID {
    val request = OneTimeWorkRequestBuilder<YandexDownloadWorker>()
        .setInputData(
            workDataOf(
                "public_key" to publicKey,
                "path" to item.path,
                "name" to item.name,
                "expected_size" to item.size,
                "expected_md5" to item.md5,
                "is_private" to isPrivate
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
    context.getSharedPreferences(TRANSFER_PREFS, Context.MODE_PRIVATE).edit()
        .putString("name_${request.id}", item.name)
        .apply()
    WorkManager.getInstance(context).enqueue(request)
    return request.id
}

internal fun persistentDownloadName(context: Context, workId: UUID): String =
    context.getSharedPreferences(TRANSFER_PREFS, Context.MODE_PRIVATE)
        .getString("name_$workId", null) ?: "Fichier"

internal fun cancelPersistentDownload(context: Context, workId: UUID) {
    val key = workId.toString()
    val prefs = context.getSharedPreferences(TRANSFER_PREFS, Context.MODE_PRIVATE)
    val downloadId = prefs.getLong(key, -1L)
    if (downloadId >= 0L) {
        runCatching {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(downloadId)
        }
        prefs.edit().remove(key).apply()
    }
    TokenVault.clear(context, "onedrive_upload_url_$key")
    prefs.edit()
        .remove("onedrive_offset_$key")
        .remove("dropbox_session_$key")
        .remove("dropbox_offset_$key")
        .apply()
    WorkManager.getInstance(context).cancelWorkById(workId)
}

internal fun isTransferIntegrityValid(
    actualSize: Long,
    expectedSize: Long,
    expectedMd5: String,
    actualMd5: String
): Boolean {
    val sizeMatches = expectedSize <= 0L || actualSize == expectedSize
    val checksumMatches = expectedMd5.isBlank() || expectedMd5.equals(actualMd5, ignoreCase = true)
    return sizeMatches && checksumMatches
}

class YandexDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private data class Snapshot(
        val status: Int,
        val reason: Int,
        val downloaded: Long,
        val total: Long
    )

    private data class Digests(
        val size: Long,
        val md5: String,
        val sha256: String
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val publicKey = inputData.getString("public_key").orEmpty()
        val path = inputData.getString("path").orEmpty()
        val name = inputData.getString("name").orEmpty()
        val expectedSize = inputData.getLong("expected_size", 0L)
        val expectedMd5 = inputData.getString("expected_md5").orEmpty()
        val isPrivate = inputData.getBoolean("is_private", false)

        if (path.isBlank() || name.isBlank() || (!isPrivate && publicKey.isBlank())) {
            return@withContext Result.failure(workDataOf("error" to "Informations de téléchargement incomplètes."))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            applicationContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Result.failure(workDataOf("error" to "Autorisation de stockage requise pour Android 8/9."))
        }

        val manager = applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val prefs = applicationContext.getSharedPreferences(TRANSFER_PREFS, Context.MODE_PRIVATE)
        val workKey = id.toString()
        var downloadId = prefs.getLong(workKey, -1L)

        if (downloadId >= 0L && querySnapshot(manager, downloadId) == null) {
            prefs.edit().remove(workKey).apply()
            downloadId = -1L
        }

        if (downloadId < 0L) {
            val sourceUrl = if (isPrivate) {
                val token = TokenVault.read(applicationContext, "yandex_token")
                if (token.isBlank()) {
                    return@withContext Result.failure(workDataOf("error" to "Reconnecte le compte Yandex privé."))
                }
                getPrivateDownloadUrl(token, path)
            } else {
                getDownloadUrl(publicKey, path)
            }
            require(Uri.parse(sourceUrl).scheme == "https") { "L’URL de téléchargement n’est pas HTTPS." }
            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180).ifBlank { "yandex-file" }
            val request = DownloadManager.Request(Uri.parse(sourceUrl))
                .setTitle(safeName)
                .setDescription("Téléchargement depuis Yandex Disk")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "YandexDown/$safeName")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            downloadId = manager.enqueue(request)
            prefs.edit().putLong(workKey, downloadId).apply()
        }

        while (!isStopped) {
            val snapshot = querySnapshot(manager, downloadId)
                ?: return@withContext Result.retry()

            when (snapshot.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val digests = runCatching { calculateDigests(manager, downloadId) }.getOrElse {
                        manager.remove(downloadId)
                        prefs.edit().remove(workKey).apply()
                        return@withContext Result.failure(
                            workDataOf("error" to "Lecture du fichier téléchargé impossible : ${it.message ?: "erreur inconnue"}")
                        )
                    }
                    if (!isTransferIntegrityValid(digests.size, expectedSize, expectedMd5, digests.md5)) {
                        manager.remove(downloadId)
                        prefs.edit().remove(workKey).apply()
                        return@withContext Result.failure(
                            workDataOf("error" to "Intégrité invalide : taille ou MD5 différent des métadonnées Yandex.")
                        )
                    }
                    prefs.edit().remove(workKey).apply()
                    setProgress(
                        workDataOf(
                            "downloaded_bytes" to digests.size,
                            "total_bytes" to digests.size
                        )
                    )
                    return@withContext Result.success(
                        workDataOf(
                            "sha256" to digests.sha256,
                            "md5_verified" to expectedMd5.isNotBlank()
                        )
                    )
                }
                DownloadManager.STATUS_FAILED -> {
                    val message = "DownloadManager a échoué (code ${snapshot.reason})."
                    manager.remove(downloadId)
                    prefs.edit().remove(workKey).apply()
                    return@withContext Result.failure(workDataOf("error" to message))
                }
                else -> {
                    val total = if (snapshot.total > 0L) snapshot.total else expectedSize
                    setProgress(
                        workDataOf(
                            "downloaded_bytes" to snapshot.downloaded.coerceAtLeast(0L),
                            "total_bytes" to total.coerceAtLeast(0L)
                        )
                    )
                    delay(1000)
                }
            }
        }
        Result.retry()
    }

    private fun querySnapshot(manager: DownloadManager, downloadId: Long): Snapshot? {
        return manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            Snapshot(
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            )
        }
    }

    private fun calculateDigests(manager: DownloadManager, downloadId: Long): Digests {
        val md5 = MessageDigest.getInstance("MD5")
        val sha256 = MessageDigest.getInstance("SHA-256")
        var count = 0L
        manager.openDownloadedFile(downloadId).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    md5.update(buffer, 0, read)
                    sha256.update(buffer, 0, read)
                    count += read
                }
            }
        }
        return Digests(count, md5.digest().toHex(), sha256.digest().toHex())
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
