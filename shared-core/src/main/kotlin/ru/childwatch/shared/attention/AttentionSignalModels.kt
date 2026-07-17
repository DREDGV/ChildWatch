package ru.childwatch.shared.attention

import java.util.UUID

object AttentionSignalContract {
    const val EVENT_REQUEST = "attention_signal_request"
    const val EVENT_START = "attention_signal_start"
    const val EVENT_STOP_REQUEST = "attention_signal_stop_request"
    const val EVENT_STOP = "attention_signal_stop"
    const val EVENT_STATUS = "attention_signal_status"

    const val MIN_DURATION_MS = 2_000L
    const val MAX_DURATION_MS = 60_000L
    const val DEFAULT_DURATION_MS = 15_000L
    const val DEFAULT_TTL_MS = 30_000L
    const val MAX_TTL_MS = 120_000L
    const val MAX_CLOCK_SKEW_MS = 5_000L

    val selectableDurationsMs = listOf(2_000L, 3_000L, 5_000L, 10_000L, 15_000L, 30_000L, 60_000L)

    fun clampDuration(value: Long): Long = value.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

    fun clampVolume(value: Int): Int = value.coerceIn(0, 100)
}

enum class AttentionTone {
    ATTENTION,
    RINGTONE,
    ALARM,
    SIREN;

    companion object {
        fun fromWire(value: String?): AttentionTone =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: ATTENTION
    }
}

enum class AttentionVibrationPattern {
    OFF,
    PULSE,
    URGENT,
    SOS;

    companion object {
        fun fromWire(value: String?): AttentionVibrationPattern =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: PULSE
    }
}

enum class AttentionSignalStatus {
    QUEUED,
    DELIVERED,
    STARTED,
    COMPLETED,
    STOPPED,
    REJECTED,
    FAILED,
    EXPIRED;

    val isTerminal: Boolean
        get() = this in setOf(COMPLETED, STOPPED, REJECTED, FAILED, EXPIRED)

    companion object {
        fun fromWire(value: String?): AttentionSignalStatus? =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() }
    }
}

data class AttentionSignalRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val familyId: String? = null,
    val targetMemberId: String? = null,
    val targetDeviceId: String,
    val requesterMemberId: String? = null,
    val requesterDeviceId: String,
    val requesterDisplayName: String,
    val tone: AttentionTone = AttentionTone.ATTENTION,
    val durationMs: Long = AttentionSignalContract.DEFAULT_DURATION_MS,
    val volumePercent: Int = 100,
    val vibrate: Boolean = true,
    val vibrationPattern: AttentionVibrationPattern = AttentionVibrationPattern.PULSE,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = createdAt + AttentionSignalContract.DEFAULT_TTL_MS
) {
    fun normalized(now: Long = System.currentTimeMillis()): AttentionSignalRequest {
        val normalizedCreatedAt = createdAt.coerceAtMost(now + AttentionSignalContract.MAX_CLOCK_SKEW_MS)
        val normalizedExpiresAt = expiresAt.coerceIn(
            normalizedCreatedAt + 1_000L,
            normalizedCreatedAt + AttentionSignalContract.MAX_TTL_MS
        )
        return copy(
            requestId = requestId.trim(),
            familyId = familyId.normalizedOrNull(),
            targetMemberId = targetMemberId.normalizedOrNull(),
            targetDeviceId = targetDeviceId.trim(),
            requesterMemberId = requesterMemberId.normalizedOrNull(),
            requesterDeviceId = requesterDeviceId.trim(),
            requesterDisplayName = requesterDisplayName.trim().take(100),
            durationMs = AttentionSignalContract.clampDuration(durationMs),
            volumePercent = AttentionSignalContract.clampVolume(volumePercent),
            vibrationPattern = if (vibrate) vibrationPattern else AttentionVibrationPattern.OFF,
            createdAt = normalizedCreatedAt,
            expiresAt = normalizedExpiresAt
        )
    }

    fun validationError(now: Long = System.currentTimeMillis()): String? {
        val value = normalized(now)
        return when {
            value.requestId.length < 8 -> "INVALID_REQUEST_ID"
            value.targetDeviceId.isBlank() -> "MISSING_TARGET_DEVICE"
            value.requesterDeviceId.isBlank() -> "MISSING_REQUESTER_DEVICE"
            value.targetDeviceId == value.requesterDeviceId -> "TARGET_EQUALS_REQUESTER"
            value.requesterDisplayName.isBlank() -> "MISSING_REQUESTER_NAME"
            value.expiresAt <= now -> "REQUEST_EXPIRED"
            else -> null
        }
    }
}

data class AttentionSignalStatusEvent(
    val requestId: String,
    val targetDeviceId: String,
    val status: AttentionSignalStatus,
    val reason: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
