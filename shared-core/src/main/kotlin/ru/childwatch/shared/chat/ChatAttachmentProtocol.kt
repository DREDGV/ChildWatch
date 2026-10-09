package ru.childwatch.shared.chat

/** Private conversation attachment; no public URL or local URI enters a message DTO. */
data class ChatV2AttachmentDto(
    val attachmentId: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val type: String,
    val durationMs: Long? = null
)
data class ChatV2CapabilitiesResponse(
    val success: Boolean = false,
    val attachments: Boolean = false,
    val maxFileBytes: Long = 25L * 1024 * 1024,
    val maxImageBytes: Long = 10L * 1024 * 1024,
    val attachmentTypes: List<String> = emptyList()
)
data class ChatV2AttachmentResponse(val success: Boolean = false, val attachment: ChatV2AttachmentDto? = null)
data class ChatV2AttachmentCancelResponse(val success: Boolean = false)

object ChatAttachmentPolicy {
    const val FILE_LIMIT = 25L * 1024 * 1024
    const val MEDIA_LIMIT = 10L * 1024 * 1024
    val TYPES = setOf("IMAGE", "FILE", "GIF", "VOICE")
    fun maxBytes(type: String): Long = if (type == "FILE") FILE_LIMIT else MEDIA_LIMIT
    fun validate(type: String, size: Long, mime: String, durationMs: Long? = null) {
        require(type in TYPES) { "ATTACHMENT_TYPE_UNSUPPORTED" }
        require(size in 1..maxBytes(type)) { "ATTACHMENT_SIZE_LIMIT" }
        require(mime.isNotBlank() && !mime.contains('\n') && !mime.contains('\r')) { "ATTACHMENT_MIME_INVALID" }
        if (type == "IMAGE") require(mime in setOf("image/jpeg", "image/png", "image/webp")) { "IMAGE_FORMAT_UNSUPPORTED" }
        if (type == "GIF") require(mime == "image/gif") { "GIF_FORMAT_INVALID" }
        if (type == "VOICE") {
            require(mime in setOf("audio/mp4", "audio/x-m4a", "audio/m4a")) { "VOICE_FORMAT_INVALID" }
            require(durationMs != null && durationMs in 1..180_000) { "VOICE_DURATION_LIMIT" }
        }
    }
    fun filename(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
        .filter { it >= ' ' && it != '\u007f' }.take(160).ifBlank { "attachment" }
    fun verifyMetadata(expectedType: String, size: Long, hash: String, attachment: ChatV2AttachmentDto): Boolean =
        attachment.attachmentId.isNotBlank() && attachment.type == expectedType &&
            attachment.sizeBytes == size && attachment.sha256.equals(hash, true) &&
            hash.matches(Regex("[0-9a-fA-F]{64}"))
}

/** Cache contains server feature metadata only and is never authority for membership or upload. */
object ChatMediaCapabilityPolicy {
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    fun canUseCached(capturedAt: Long, now: Long): Boolean = capturedAt > 0 && now >= capturedAt && now-capturedAt <= MAX_AGE_MS
}
