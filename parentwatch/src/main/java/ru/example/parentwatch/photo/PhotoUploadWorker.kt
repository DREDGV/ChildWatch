package ru.example.parentwatch.photo

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.example.parentwatch.network.NetworkClient
import ru.example.parentwatch.session.ChildEffectiveContextResolver
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Transfers an already captured image. Never opens a camera or repeats a capture. */
class PhotoUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val server = inputData.getString("server") ?: return@withContext Result.failure()
        val own = inputData.getString("own") ?: return@withContext Result.failure()
        val family = inputData.getString("family").orEmpty()
        val request = inputData.getString("request") ?: return@withContext Result.failure()
        val captured = inputData.getLong("captured", 0L)
        val file = File(directory(applicationContext), key(server, family, own, request) + ".jpg")
        if (!file.isFile) return@withContext Result.success()
        val identity = ChildEffectiveContextResolver(applicationContext)
        fun matches() = identity.resolveServerUrl().trimEnd('/') == server.trimEnd('/') &&
            identity.resolveChildDeviceId() == own && identity.resolveFamilyId().orEmpty() == family
        val age = System.currentTimeMillis() - captured
        if (captured <= 0 || age > RETENTION_MS || age < -60_000) {
            if (matches()) NetworkClient(applicationContext).reportPhotoFailure(server, own, request, "photo_upload_expired")
            file.delete()
            return@withContext Result.failure()
        }
        // Another active connection must not inherit this phone's queued images.
        if (!matches()) return@withContext Result.retry()
        val network = NetworkClient(applicationContext)
        try {
            val outcome = network.uploadPhotoOutcome(server, file, request, captured, own)
            if (!outcome.uploaded && !outcome.retryable) {
                if (matches()) network.reportPhotoFailure(server, own, request, outcome.error ?: "photo_upload_rejected")
                file.delete()
                return@withContext Result.failure()
            }
            if (!outcome.uploaded) {
                if (matches()) network.reportPhotoFailure(server, own, request, "photo_upload_queued")
                return@withContext Result.retry()
            }
            if (matches()) {
                ru.example.parentwatch.utils.CameraDiagnostics.recordOutcome(applicationContext, server, own, null)
            }
            file.delete() // Only the durable server acknowledgement releases the private copy.
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.w("PhotoUpload", "Captured image remains queued", error)
            Result.retry()
        }
    }

    companion object {
        private const val RETENTION_MS = 24 * 60 * 60 * 1000L
        private val enqueueLock = Any()
        private fun directory(context: Context) = File(context.noBackupFilesDir, "photo_upload_queue").apply { mkdirs() }
        private fun key(server: String, family: String, own: String, request: String): String =
            MessageDigest.getInstance("SHA-256").digest(org.json.JSONArray(listOf(server.trimEnd('/'), family, own, request))
                .toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun hasQueued(context: Context, server: String, family: String, own: String, request: String): Boolean =
            File(directory(context), key(server, family, own, request) + ".jpg").isFile

        /** Called on IO. Commit the private image and persistent work before removing the camera file. */
        fun enqueue(context: Context, server: String, family: String, own: String, request: String,
                    source: File, captured: Long) = synchronized(enqueueLock) {
            require(server.isNotBlank() && own.isNotBlank() && request.matches(Regex("[A-Za-z0-9_-]{1,100}")))
            check(source.isFile && source.length() > 0L) { "photo_file_missing" }
            check(source.length() <= 10L * 1024 * 1024) { "photo_upload_too_large" }
            val dir = directory(context)
            val now = System.currentTimeMillis()
            dir.listFiles()?.filter { now - it.lastModified() > RETENTION_MS }?.forEach { it.delete() }
            val id = key(server, family, own, request)
            val destination = File(dir, "$id.jpg")
            if (!destination.exists()) {
                val images = dir.listFiles()?.filter { it.extension == "jpg" }.orEmpty()
                check(images.size < 10 && images.sumOf { it.length() } + source.length() <= 60L * 1024 * 1024) { "photo_upload_queue_full" }
                val temporary = File(dir, "$id.tmp")
                try {
                    source.inputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
                    check(temporary.renameTo(destination)) { "photo_upload_queue_failed" }
                    destination.setLastModified(captured)
                } finally { temporary.delete() }
            }
            val work = OneTimeWorkRequestBuilder<PhotoUploadWorker>()
                .setInputData(Data.Builder().putString("server", server).putString("family", family)
                    .putString("own", own).putString("request", request).putLong("captured", captured).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("photo-upload-$id", ExistingWorkPolicy.KEEP, work)
                .result.get(15, TimeUnit.SECONDS)
        }

    }
}
