package ru.childwatch.shared.chat

/** Limits shared by explicit chat recording and its private attachment upload. */
object VoiceRecordingPolicy {
    const val MAX_DURATION_MS = 180_000L
    const val MAX_BYTES = 10L * 1024 * 1024
    const val START_FREE_BYTES = 2L * 1024 * 1024
    const val STOP_FREE_BYTES = 512L * 1024

    fun usable(durationMs: Long, bytes: Long): Boolean =
        durationMs in 1..MAX_DURATION_MS && bytes in 1..MAX_BYTES

    fun elapsed(startMonotonicMs: Long, nowMonotonicMs: Long): Long =
        (nowMonotonicMs - startMonotonicMs).coerceIn(0, MAX_DURATION_MS)

    fun mayStart(microphoneGranted: Boolean, freeBytes: Long, hasUnsentDraft: Boolean): Boolean =
        microphoneGranted && freeBytes >= START_FREE_BYTES && !hasUnsentDraft
}
