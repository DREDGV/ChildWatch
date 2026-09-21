package ru.example.parentwatch.profile

import android.content.Context
import android.net.Uri
import android.util.Log
import ru.example.parentwatch.network.NetworkClient
import ru.example.parentwatch.utils.ServerUrlResolver
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Uploads this person's own photograph and stores the returned address value.
 *
 * The picture is checked here, on the phone, before a byte leaves it: a file
 * that is obviously too big should be refused with a sentence the person can
 * read instead of a failed request. Everything else — that the file really is a
 * picture, and that it is one of the accepted formats — is checked again by the
 * server, from the bytes themselves, because a stored picture is later served
 * back to every member of the family.
 *
 * The value the server returns is kept exactly as it came: it is a path, not an
 * address, so the server can move without breaking anybody's profile.
 */
object AvatarPhotoUpload {

    private const val TAG = "AvatarPhotoUpload"

    /** The same ceiling the server enforces; larger files are refused before sending. */
    const val MAX_AVATAR_BYTES = 5 * 1024 * 1024

    /** One byte more than the limit, so a file that is too big is detected as such. */
    private const val READ_LIMIT = MAX_AVATAR_BYTES + 1

    /**
     * What went wrong, as a reason the caller turns into a message.
     *
     * A code rather than a sentence, because the text belongs in a string
     * resource and this object has no context to read one from.
     */
    enum class Failure {
        /** The picked file is larger than the server accepts. */
        TOO_LARGE,

        /** The picked file is not a JPEG, PNG or WebP picture. */
        UNSUPPORTED_FORMAT,

        /** The file could not be read, or the upload did not succeed. */
        UPLOAD_FAILED,

        /** The server address is unknown, so there is nowhere to upload to. */
        NO_SERVER
    }

    /** The outcome of an upload: the value to store, or a reason it did not happen. */
    data class Result(val avatarValue: String? = null, val failure: Failure? = null) {
        val isSuccess: Boolean get() = !avatarValue.isNullOrBlank()
    }

    /**
     * Reads the picked picture and sends it to the server.
     *
     * Suspending: it reads a file and performs an upload, so it is called from a
     * coroutine that is off the main thread. It is given the picture rather than
     * picking one itself, so choosing from the phone stays with the screen that
     * owns the picker.
     */
    suspend fun upload(context: Context, serverUrl: String, picture: Uri): Result {
        val prepared = readPicture(context, picture)
        if (prepared.failure != null) return Result(failure = prepared.failure)

        val bytes = prepared.bytes ?: return Result(failure = Failure.UPLOAD_FAILED)
        val contentType = prepared.contentType ?: return Result(failure = Failure.UNSUPPORTED_FORMAT)

        val value = runCatching {
            NetworkClient(context).uploadOwnAvatar(
                serverUrl = serverUrl,
                picture = bytes,
                contentType = contentType
            )
        }.getOrNull()

        return if (value.isNullOrBlank()) Result(failure = Failure.UPLOAD_FAILED)
        else Result(avatarValue = value)
    }

    /**
     * Removes a picture this device uploaded earlier, after the person switched
     * back to a built-in avatar.
     *
     * The result is deliberately not reported to the user: the profile has
     * already changed, and a leftover file on the server is not something they
     * can see or act on.
     */
    suspend fun deleteUploadedPhoto(context: Context, serverUrl: String, avatarValue: String?) {
        val value = avatarValue?.trim().orEmpty()
        if (!value.startsWith("/avatars/")) return
        runCatching {
            NetworkClient(context).deleteUploadedAvatar(serverUrl, value)
        }.onFailure { Log.d(TAG, "The old picture was not removed: ${it.message}") }
    }

    /** The server address this device is configured with. */
    fun serverUrl(context: Context): String? =
        ServerUrlResolver.getServerUrl(context)?.takeIf { it.isNotBlank() }

    /**
     * Reads the picked file into memory, refusing anything that cannot be stored.
     *
     * A ContentResolver often cannot say a file's size, so the limit is applied
     * to what is actually read: the stream is abandoned one byte past the
     * ceiling rather than after loading an arbitrarily large file.
     */
    private fun readPicture(context: Context, picture: Uri): Prepared {
        val bytes = try {
            context.contentResolver.openInputStream(picture)?.use { stream ->
                readUpTo(stream, READ_LIMIT)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the picked picture", error)
            null
        } ?: return Prepared(failure = Failure.UPLOAD_FAILED)

        if (bytes.size > MAX_AVATAR_BYTES) return Prepared(failure = Failure.TOO_LARGE)
        if (bytes.isEmpty()) return Prepared(failure = Failure.UPLOAD_FAILED)

        // The declared type is a claim; the first bytes are the file itself.
        val contentType = detectImageType(bytes)
            ?: return Prepared(failure = Failure.UNSUPPORTED_FORMAT)
        return Prepared(bytes = bytes, contentType = contentType)
    }

    /**
     * A picture ready to send. Kept separate from [Result] so the public result
     * stays what a screen needs: a value to store, or a reason there is none.
     */
    private data class Prepared(
        val bytes: ByteArray? = null,
        val contentType: String? = null,
        val failure: Failure? = null
    )

    /** Reads at most [limit] bytes, so an oversized file is not loaded whole. */
    private fun readUpTo(stream: InputStream, limit: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (buffer.size() < limit) {
            val remaining = limit - buffer.size()
            val read = stream.read(chunk, 0, minOf(chunk.size, remaining))
            if (read <= 0) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /**
     * Recognises the picture from its own first bytes.
     *
     * The same three formats the server accepts, and no others: SVG is a
     * document format that can carry script and is deliberately not allowed
     * anywhere in this path.
     */
    private fun detectImageType(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        if (png.indices.all { bytes[it] == png[it] }) return "image/png"
        val riff = String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF"
        val webp = String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        if (riff && webp) return "image/webp"
        return null
    }
}
