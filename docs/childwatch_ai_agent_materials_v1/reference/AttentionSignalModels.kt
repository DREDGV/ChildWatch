package ru.childwatch.shared.attention

import org.json.JSONObject
import java.util.UUID

object AttentionSignalContract {
    const val EVENT_REQUEST = "attention_signal_request"
    const val EVENT_START = "attention_signal_start"
    const val EVENT_STOP_REQUEST = "attention_signal_stop_request"
    const val EVENT_STOP = "attention_signal_stop"
    const val EVENT_STATUS = "attention_signal_status"

    const val MIN_DURATION_MS = 5_000L
    const val MAX_DURATION_MS = 60_000L
    const val DEFAULT_DURATION_MS = 15_000L
    const val DEFAULT_TTL_MS = 30_000L
    const val MAX_TTL_MS = 120_000L

    fun clampDuration(value: Long): Long =
        value.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

    fun clampVolume(value: Int): Int = value.coerceIn(0, 100)
}

enum class AttentionTone {
    ATTENTION, RINGTONE, ALARM, SIREN;

    companion object {
        fun fromWire(value: String?): AttentionTone =
            entries.firstOrNull {
                it.name == value?.trim()?.uppercase()
            } ?: ATTENTION
    }
}

enum class AttentionVibrationPattern {
    OFF, PULSE, URGENT, SOS;

    companion object {
        fun fromWire(value: String?): AttentionVibrationPattern =
            entries.firstOrNull {
                it.name == value?.trim()?.uppercase()
            } ?: PULSE
    }
}

enum class AttentionSignalStatus {
    QUEUED, DELIVERED, STARTED, COMPLETED,
    STOPPED, REJECTED, FAILED, EXPIRED
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
    val vibrationPattern: AttentionVibrationPattern =
        AttentionVibrationPattern.PULSE,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long =
        createdAt + AttentionSignalContract.DEFAULT_TTL_MS
) {
    fun normalized(
        now: Long = System.currentTimeMillis()
    ): AttentionSignalRequest {
        val normalizedCreatedAt = createdAt.coerceAtMost(now + 5_000L)
        val normalizedExpiresAt = expiresAt.coerceIn(
            normalizedCreatedAt + 1_000L,
            normalizedCreatedAt + AttentionSignalContract.MAX_TTL_MS
        )
        return copy(
            requestId = requestId.trim(),
            familyId = familyId?.trim()?.takeIf(String::isNotEmpty),
            targetMemberId =
                targetMemberId?.trim()?.takeIf(String::isNotEmpty),
            targetDeviceId = targetDeviceId.trim(),
            requesterMemberId =
                requesterMemberId?.trim()?.takeIf(String::isNotEmpty),
            requesterDeviceId = requesterDeviceId.trim(),
            requesterDisplayName =
                requesterDisplayName.trim().take(100),
            durationMs =
                AttentionSignalContract.clampDuration(durationMs),
            volumePercent =
                AttentionSignalContract.clampVolume(volumePercent),
            vibrationPattern =
                if (vibrate) vibrationPattern
                else AttentionVibrationPattern.OFF,
            createdAt = normalizedCreatedAt,
            expiresAt = normalizedExpiresAt
        )
    }

    fun validationError(
        now: Long = System.currentTimeMillis()
    ): String? {
        val value = normalized(now)
        return when {
            value.requestId.length < 8 ->
                "INVALID_REQUEST_ID"
            value.targetDeviceId.isBlank() ->
                "MISSING_TARGET_DEVICE"
            value.requesterDeviceId.isBlank() ->
                "MISSING_REQUESTER_DEVICE"
            value.targetDeviceId == value.requesterDeviceId ->
                "TARGET_EQUALS_REQUESTER"
            value.requesterDisplayName.isBlank() ->
                "MISSING_REQUESTER_NAME"
            value.expiresAt <= now ->
                "REQUEST_EXPIRED"
            else -> null
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("requestId", requestId)
        put("familyId", familyId ?: JSONObject.NULL)
        put("targetMemberId", targetMemberId ?: JSONObject.NULL)
        put("targetDeviceId", targetDeviceId)
        put("requesterMemberId", requesterMemberId ?: JSONObject.NULL)
        put("requesterDeviceId", requesterDeviceId)
        put("requesterDisplayName", requesterDisplayName)
        put("tone", tone.name)
        put("durationMs", durationMs)
        put("volumePercent", volumePercent)
        put("vibrate", vibrate)
        put("vibrationPattern", vibrationPattern.name)
        put("createdAt", createdAt)
        put("expiresAt", expiresAt)
    }

    companion object {
        fun fromJson(json: JSONObject): AttentionSignalRequest =
            AttentionSignalRequest(
                requestId = json.optString("requestId"),
                familyId = json.optString("familyId")
                    .takeIf { it.isNotBlank() && it != "null" },
                targetMemberId = json.optString("targetMemberId")
                    .takeIf { it.isNotBlank() && it != "null" },
                targetDeviceId =
                    json.optString("targetDeviceId"),
                requesterMemberId =
                    json.optString("requesterMemberId")
                        .takeIf {
                            it.isNotBlank() && it != "null"
                        },
                requesterDeviceId =
                    json.optString("requesterDeviceId"),
                requesterDisplayName =
                    json.optString("requesterDisplayName"),
                tone = AttentionTone.fromWire(
                    json.optString("tone")
                ),
                durationMs = json.optLong(
                    "durationMs",
                    AttentionSignalContract.DEFAULT_DURATION_MS
                ),
                volumePercent =
                    json.optInt("volumePercent", 100),
                vibrate =
                    json.optBoolean("vibrate", true),
                vibrationPattern =
                    AttentionVibrationPattern.fromWire(
                        json.optString("vibrationPattern")
                    ),
                createdAt = json.optLong(
                    "createdAt",
                    System.currentTimeMillis()
                ),
                expiresAt = json.optLong(
                    "expiresAt",
                    System.currentTimeMillis() +
                        AttentionSignalContract.DEFAULT_TTL_MS
                )
            ).normalized()
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
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("requestId", requestId)
        put("targetDeviceId", targetDeviceId)
        put("status", status.name)
        put("reason", reason ?: JSONObject.NULL)
        put("errorCode", errorCode ?: JSONObject.NULL)
        put("message", message ?: JSONObject.NULL)
        put("timestamp", timestamp)
    }
}
