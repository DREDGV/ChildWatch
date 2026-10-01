package ru.example.childwatch.profile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.example.childwatch.network.NetworkClient

/**
 * The result of offering a chosen picture to the server.
 *
 * Failures are values rather than exceptions because every one of them has to
 * become a sentence for the person, and none of them is a fault in this app: a
 * picture can be too large, in a format the server does not store, or the phone
 * can simply be offline.
 */
sealed interface ProfilePhotoUpload {
    data class Stored(val avatarValue: String) : ProfilePhotoUpload
    data object FileTooLarge : ProfilePhotoUpload
    data object UnsupportedFormat : ProfilePhotoUpload
    data object Unreadable : ProfilePhotoUpload
    data object Rejected : ProfilePhotoUpload
}

/**
 * Turns a picture chosen from the phone into a value the profile can store.
 *
 * The value is the path the server answers with, such as `/avatars/ab12….jpg`.
 * Nothing absolute is ever built here: the address of the server is added when
 * the picture is displayed, so a stored profile stays valid if the server moves.
 *
 * Two things are deliberately checked before anything is sent, because both make
 * the upload fail anyway and a refusal the app explains is better than a generic
 * "could not save":
 *  - the size, so a large picture is never read into memory at all;
 *  - the real format, sniffed from the first bytes, because the server judges the
 *    file by its content and a stored file is later served to the whole family.
 */
class ProfilePhotoUploader(
    private val context: Context,
    private val networkClient: NetworkClient = NetworkClient(context.applicationContext)
) {

    /**
     * The most the server stores. Kept here as well so an oversized picture is
     * refused on the phone, before its bytes are read.
     */
    private val maxBytes = 5L * 1024L * 1024L

    /**
     * Reads the chosen picture and stores it on the server.
     *
     * Always call this from the main dispatcher: it touches the stored server
     * address, and the reading, the sniffing and the upload happen on the IO
     * dispatcher because a picture is not something to read on the UI thread.
     */
    suspend fun upload(uri: Uri): ProfilePhotoUpload {
        val size = runCatching { sizeOf(uri) }.getOrNull()
        if (size != null && size > maxBytes) return ProfilePhotoUpload.FileTooLarge

        val declaredType = runCatching { context.contentResolver.getType(uri) }.getOrNull()

        return withContext(Dispatchers.IO) {
            val bytes = runCatching { readBytes(uri) }.getOrNull()
                ?: return@withContext ProfilePhotoUpload.Unreadable
            if (bytes.isEmpty()) return@withContext ProfilePhotoUpload.Unreadable
            // The size is checked again after reading, for a document provider
            // that did not report one.
            if (bytes.size.toLong() > maxBytes) return@withContext ProfilePhotoUpload.FileTooLarge

            val contentType = chooseContentType(declaredType, bytes)
                ?: return@withContext ProfilePhotoUpload.UnsupportedFormat

            try {
                val response = networkClient.uploadAvatar(
                    fileName = fileNameFor(uri, contentType),
                    contentType = contentType,
                    openBytes = { bytes }
                )
                val body = response.body()
                val value = body?.avatarValue?.trim().orEmpty()
                if (response.isSuccessful && body?.success == true && value.isNotBlank()) {
                    ProfilePhotoUpload.Stored(value)
                } else {
                    Log.w(
                        TAG,
                        "The server refused the picture: ${response.code()} ${body?.code.orEmpty()}"
                    )
                    ProfilePhotoUpload.Rejected
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.e(TAG, "Storing the chosen picture failed", error)
                ProfilePhotoUpload.Rejected
            }
        }
    }

    /**
     * Removes a picture this device uploaded earlier.
     *
     * Only a value shaped like an uploaded picture is sent, so a built-in avatar
     * name — or anything else a profile happened to hold — never reaches the server
     * as a deletion request. The answer is a plain boolean because the caller only
     * logs it.
     */
    suspend fun remove(avatarValue: String): Boolean {
        val path = avatarValue.trim()
        if (!UPLOADED_AVATAR_PATTERN.matches(path)) return false
        return try {
            val response = networkClient.deleteUploadedAvatar(path)
            if (!response.isSuccessful) {
                Log.w(TAG, "The stored picture could not be removed: ${response.code()}")
            }
            response.isSuccessful
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.w(TAG, "Removing the stored picture failed", error)
            false
        }
    }

    /** The size the provider reports, which spares reading a large file to find out. */
    private fun sizeOf(uri: Uri): Long? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) {
                    return cursor.getLong(index)
                }
            }
        return null
    }

    private fun readBytes(uri: Uri): ByteArray? {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var remaining = maxBytes.toInt() + 1
            while (remaining > 0) {
                val count = stream.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.toByteArray()
        }
    }

    /**
     * What the file really is, preferring the declared type.
     *
     * `contentResolver.getType` is the cheapest answer but it is only a claim, and
     * some providers answer `application/octet-stream` or nothing at all. The type
     * is then taken from the first bytes, and a file that claims to be an image but
     * is not is refused here instead of being sent to be rejected.
     */
    private fun chooseContentType(declaredType: String?, bytes: ByteArray): String? {
        val declared = declaredType?.trim()?.lowercase()
        if (declared in SUPPORTED_CONTENT_TYPES) return declared
        return detectContentType(bytes)
    }

    /** Recognises the formats the server stores, from the file's own first bytes. */
    private fun detectContentType(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        if (
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        if (PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }) return "image/png"
        if (
            bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
        ) {
            return "image/webp"
        }
        return null
    }

    /**
     * A name for the uploaded part.
     *
     * The server looks at the bytes, so only the extension matters here: it has to
     * agree with the type that was detected, or a screenshot saved as an unhelpful
     * name would be described to the server as something it is not.
     */
    private fun fileNameFor(uri: Uri, contentType: String): String {
        val extension = when (contentType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val providerName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }.getOrNull()
        val base = providerName
            ?.substringBeforeLast('.')
            ?.replace(UNSAFE_FILE_NAME_CHARACTERS, "_")
            ?.take(40)
            ?.takeIf { it.isNotBlank() }
            ?: "avatar"
        return "$base.$extension"
    }

    companion object {
        private const val TAG = "ProfilePhotoUploader"

        /** The formats the server stores. Everything else is refused on the phone. */
        private val SUPPORTED_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        private val PNG_SIGNATURE =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        private val UNSAFE_FILE_NAME_CHARACTERS = Regex("[^A-Za-z0-9_-]")

        /**
         * The shape of a value this server returns for an uploaded picture and the
         * only shape it deletes. Kept in step with the server's own rule.
         */
        private val UPLOADED_AVATAR_PATTERN = Regex("^/avatars/[a-f0-9]{32}\\.(jpg|png|webp)$")
    }
}
