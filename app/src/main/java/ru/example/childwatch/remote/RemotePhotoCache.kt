package ru.example.childwatch.remote

import android.content.Context
import android.util.Base64
import android.util.Base64InputStream
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

object RemotePhotoCache {
    private const val TAG = "RemotePhotoCache"
    private const val MAX_CACHE_FILES = 20
    private const val BUFFER_SIZE = 8 * 1024

    fun saveBase64PhotoToCache(context: Context, base64: String, timestamp: Long): File? {
        return try {
            val payload = sanitizeBase64Payload(base64)
            if (payload.isEmpty()) {
                Log.e(TAG, "Remote photo payload is empty")
                return null
            }

            val cacheDir = File(context.cacheDir, "remote_photo_preview")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            pruneOldFiles(cacheDir)

            val file = File(cacheDir, "remote_photo_${timestamp}.jpg")
            var totalWritten = 0L

            ByteArrayInputStream(payload.toByteArray(Charsets.US_ASCII)).use { source ->
                Base64InputStream(source, Base64.DEFAULT).use { decoded ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = decoded.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            totalWritten += read
                        }
                    }
                }
            }

            if (totalWritten <= 0L || !file.exists() || file.length() == 0L) {
                runCatching { file.delete() }
                Log.e(TAG, "Decoded photo file is empty")
                null
            } else {
                file
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache remote photo", e)
            null
        }
    }

    private fun sanitizeBase64Payload(base64: String): String {
        val trimmed = base64.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.substringAfter(',', trimmed)
    }

    private fun pruneOldFiles(cacheDir: File) {
        val files = cacheDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        files.drop(MAX_CACHE_FILES).forEach { file ->
            runCatching { file.delete() }
        }
    }
}
