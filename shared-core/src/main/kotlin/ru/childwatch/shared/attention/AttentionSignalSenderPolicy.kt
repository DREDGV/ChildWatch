package ru.childwatch.shared.attention

data class AttentionSignalSenderState(
    val requestId: String,
    val targetDeviceId: String,
    val sentAt: Long,
    val durationMs: Long,
    val safeUntil: Long,
    val status: AttentionSignalStatus? = null,
    val lastStatusAt: Long = 0,
    val lastEvidenceAt: Long = sentAt,
    val stopRequestedAt: Long? = null,
    val stoppedWaiting: Boolean = false,
    val isUnknown: Boolean = false,
    val lastPolledAt: Long = 0
) {
    val isTerminal: Boolean get() = status?.isTerminal == true
    val waiting: Boolean get() = !isTerminal && !stoppedWaiting && !isUnknown
    val stopPending: Boolean get() = stopRequestedAt != null && !isTerminal
}

/** Local waiting has a deadline; only matching server evidence can declare an outcome. */
object AttentionSignalSenderPolicy {
    const val SILENCE_MS = 15_000L
    const val STOP_WAIT_MS = 10_000L
    const val POLL_AFTER_MS = 10_000L
    const val POLL_INTERVAL_MS = 5_000L
    const val GRACE_MS = 5_000L

    fun start(requestId: String, targetDeviceId: String, sentAt: Long, durationMs: Long): AttentionSignalSenderState {
        require(requestId.isNotBlank() && targetDeviceId.isNotBlank() && sentAt > 0)
        val duration = AttentionSignalContract.clampDuration(durationMs)
        val horizon = AttentionSignalContract.DEFAULT_TTL_MS + duration + GRACE_MS
        require(sentAt <= Long.MAX_VALUE - horizon)
        return AttentionSignalSenderState(requestId, targetDeviceId, sentAt, duration, sentAt + horizon)
    }

    fun onStatus(state: AttentionSignalSenderState, event: AttentionSignalStatusEvent, now: Long): AttentionSignalSenderState =
        onStatus(state, event.requestId, event.targetDeviceId, event.status, now, event.timestamp)

    fun onStatus(state: AttentionSignalSenderState, requestId: String, targetDeviceId: String,
                 status: AttentionSignalStatus, now: Long, timestamp: Long = now): AttentionSignalSenderState {
        if (requestId != state.requestId || targetDeviceId != state.targetDeviceId || state.isTerminal ||
            timestamp <= 0 || timestamp < state.lastStatusAt || now < state.sentAt ||
            (timestamp > now && timestamp - now > AttentionSignalContract.MAX_CLOCK_SKEW_MS)) return state
        if (rank(status) < rank(state.status)) return state
        // Duplicates are not fresh progress: repeated QUEUED cannot keep the spinner alive forever.
        if (status == state.status) return state
        return state.copy(status = status, lastStatusAt = timestamp, lastEvidenceAt = now, isUnknown = false)
    }

    fun requestStop(state: AttentionSignalSenderState, now: Long): AttentionSignalSenderState =
        if (state.isTerminal || state.stopRequestedAt != null || now < state.sentAt) state
        else state.copy(stopRequestedAt = now)

    /** Stops waiting locally, without pretending that the child stopped playing. */
    fun stopWaiting(state: AttentionSignalSenderState): AttentionSignalSenderState =
        if (state.isTerminal) state else state.copy(stoppedWaiting = true, isUnknown = true)

    /** UNKNOWN from read-only recovery has no terminal meaning and never frees the send guard. */
    fun markUnknown(state: AttentionSignalSenderState, now: Long): AttentionSignalSenderState =
        if (state.isTerminal || now < state.sentAt) state else state.copy(isUnknown = true)

    fun tick(state: AttentionSignalSenderState, now: Long): AttentionSignalSenderState {
        if (state.isTerminal || state.isUnknown || now < state.sentAt) return state
        val stopAt = state.stopRequestedAt
        val evidenceWaitMs = if (state.status == AttentionSignalStatus.STARTED) state.durationMs + GRACE_MS else SILENCE_MS
        val timedOut = now >= state.safeUntil || now - state.lastEvidenceAt >= evidenceWaitMs ||
            (stopAt != null && now >= stopAt && now - stopAt >= STOP_WAIT_MS)
        return if (timedOut) state.copy(isUnknown = true) else state
    }

    /** Never sends a second sound while the first could still start/play after its request TTL. */
    fun canSend(state: AttentionSignalSenderState, now: Long): Boolean = state.isTerminal || now >= state.safeUntil

    fun canStop(state: AttentionSignalSenderState, now: Long): Boolean =
        !state.isTerminal && state.stopRequestedAt == null && now >= state.sentAt && now < state.safeUntil

    /** Read-only recovery only. A poll never renews the command TTL or progress deadline. */
    fun shouldPoll(state: AttentionSignalSenderState, now: Long): Boolean {
        if (state.isTerminal || state.stoppedWaiting || now < state.sentAt || now >= state.safeUntil) return false
        val needed = state.stopRequestedAt != null || now - state.lastEvidenceAt >= POLL_AFTER_MS
        return needed && (state.lastPolledAt == 0L || now - state.lastPolledAt >= POLL_INTERVAL_MS)
    }

    fun markPolled(state: AttentionSignalSenderState, now: Long): AttentionSignalSenderState =
        if (now < state.sentAt) state else state.copy(lastPolledAt = now)

    private fun rank(status: AttentionSignalStatus?): Int = when (status) {
        null -> -1
        AttentionSignalStatus.QUEUED -> 0
        AttentionSignalStatus.DELIVERED -> 1
        AttentionSignalStatus.STARTED -> 2
        else -> 3
    }
}
