package ru.childwatch.shared.attention

/**
 * Pure, platform-independent part of the Android receiver flow.
 *
 * Keeping target/TTL decisions and status ordering here makes them testable
 * without an Android device. Actual audio, vibration and notification work is
 * still performed by the Android controller.
 */
class AttentionSignalReceiverCoordinator(
    private val ownDeviceId: () -> String,
    private val emitStatus: (AttentionSignalStatusEvent) -> Unit,
    private val startPlayback: (AttentionSignalRequest) -> Unit,
    private val stopPlayback: (requestId: String?, reason: String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun handleStart(raw: AttentionSignalRequest) {
        val currentTime = now()
        val request = raw.normalized(currentTime)
        val decision = AttentionSignalReceiverPolicy.evaluate(
            request = request,
            ownDeviceId = ownDeviceId(),
            now = currentTime
        )
        if (decision != null) {
            send(
                request = request,
                status = decision.status,
                reason = decision.reason,
                errorCode = decision.errorCode
            )
            return
        }

        // This ordering is part of the wire contract: delivery is confirmed
        // before Android starts touching audio/vibration resources.
        send(request, AttentionSignalStatus.DELIVERED)
        startPlayback(request)
    }

    fun handleStop(requestId: String) {
        val normalizedRequestId = requestId.trim()
        if (normalizedRequestId.isBlank()) return
        if (!stopPlayback(normalizedRequestId, "REMOTE_REQUEST")) {
            emitStatus(
                AttentionSignalStatusEvent(
                    requestId = normalizedRequestId,
                    targetDeviceId = ownDeviceId().trim(),
                    status = AttentionSignalStatus.REJECTED,
                    reason = "NOT_ACTIVE",
                    errorCode = "SIGNAL_NOT_ACTIVE",
                    timestamp = now()
                )
            )
        }
    }

    fun onStarted(request: AttentionSignalRequest) {
        send(request, AttentionSignalStatus.STARTED)
    }

    fun onStopped(request: AttentionSignalRequest, reason: String) {
        send(request, AttentionSignalStatus.STOPPED, reason)
    }

    fun onCompleted(request: AttentionSignalRequest) {
        send(request, AttentionSignalStatus.COMPLETED, "TIMEOUT")
    }

    fun onFailed(request: AttentionSignalRequest, code: String, message: String? = null) {
        send(
            request = request,
            status = AttentionSignalStatus.FAILED,
            reason = "PLAYBACK_FAILED",
            errorCode = code,
            message = message
        )
    }

    private fun send(
        request: AttentionSignalRequest,
        status: AttentionSignalStatus,
        reason: String? = null,
        errorCode: String? = null,
        message: String? = null
    ) {
        emitStatus(
            AttentionSignalStatusEvent(
                requestId = request.requestId,
                targetDeviceId = request.targetDeviceId,
                status = status,
                reason = reason,
                errorCode = errorCode,
                message = message,
                timestamp = now()
            )
        )
    }
}

data class AttentionSignalReceiverDecision(
    val status: AttentionSignalStatus,
    val reason: String,
    val errorCode: String? = null
)

object AttentionSignalReceiverPolicy {
    fun evaluate(
        request: AttentionSignalRequest,
        ownDeviceId: String,
        now: Long = System.currentTimeMillis()
    ): AttentionSignalReceiverDecision? {
        val ownId = ownDeviceId.trim()
        return when {
            ownId.isBlank() -> AttentionSignalReceiverDecision(
                status = AttentionSignalStatus.REJECTED,
                reason = "NO_ACTIVE_CONTEXT",
                errorCode = "MISSING_DEVICE_ID"
            )

            request.targetDeviceId != ownId -> AttentionSignalReceiverDecision(
                status = AttentionSignalStatus.REJECTED,
                reason = "WRONG_TARGET",
                errorCode = "TARGET_DEVICE_MISMATCH"
            )

            request.expiresAt <= now -> AttentionSignalReceiverDecision(
                status = AttentionSignalStatus.EXPIRED,
                reason = "TTL_EXPIRED"
            )

            request.validationError(now) != null -> AttentionSignalReceiverDecision(
                status = AttentionSignalStatus.REJECTED,
                reason = "INVALID_REQUEST",
                errorCode = request.validationError(now)
            )

            else -> null
        }
    }
}
