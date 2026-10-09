package ru.childwatch.shared.chat

/** Our own server's private transcription job; recognized text is never sent as a message automatically. */
data class ChatV2TranscriptionJobDto(
    val jobId: String,
    val clientRequestId: String,
    val state: String,
    val text: String? = null,
    val errorCode: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null
)
data class ChatV2TranscriptionResponse(val success: Boolean = false, val job: ChatV2TranscriptionJobDto? = null)

object ChatTranscriptionPolicy {
    const val MAX_DURATION_MS = 180_000L
    const val MAX_BYTES = 10L * 1024 * 1024
    const val MAX_EDIT_BYTES = 16 * 1024
    val STATES = setOf("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED")
    fun validateSource(type: String, bytes: Long, mime: String, durationMs: Long?, sha256: String) {
        require(type == "VOICE") { "TRANSCRIPTION_SOURCE_NOT_VOICE" }
        ChatAttachmentPolicy.validate(type, bytes, mime, durationMs)
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "TRANSCRIPTION_SOURCE_HASH_INVALID" }
    }
    fun validJob(job: ChatV2TranscriptionJobDto, requestId: String): Boolean =
        !job.jobId.isNullOrBlank() && job.clientRequestId == requestId && job.state in STATES &&
            job.createdAt > 0 && job.updatedAt >= job.createdAt &&
            (job.state != "SUCCEEDED" || job.text != null) &&
            (job.completedAt == null || job.completedAt >= job.createdAt)
    fun terminal(state: String): Boolean = state in setOf("SUCCEEDED", "FAILED", "CANCELLED")
    fun validateEditedText(text: String) {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_EDIT_BYTES) { "TRANSCRIPTION_EDIT_LIMIT" }
    }
    /** A poll may never replace a correction made by the user. */
    fun editorText(edited: Boolean, editedText: String?, recognizedText: String?): String =
        if (edited) editedText.orEmpty() else recognizedText.orEmpty()
}
