package ru.childwatch.shared.attention.android

import org.json.JSONObject
import ru.childwatch.shared.attention.AttentionSignalRequest
import ru.childwatch.shared.attention.AttentionSignalStatus
import ru.childwatch.shared.attention.AttentionSignalStatusEvent
import ru.childwatch.shared.attention.AttentionTone
import ru.childwatch.shared.attention.AttentionVibrationPattern

object AttentionSignalJson {
    fun requestFromJson(json: JSONObject, now: Long = System.currentTimeMillis()): AttentionSignalRequest =
        AttentionSignalRequest(
            requestId = json.optString("requestId"),
            familyId = json.optionalString("familyId"),
            targetMemberId = json.optionalString("targetMemberId"),
            targetDeviceId = json.optString("targetDeviceId"),
            requesterMemberId = json.optionalString("requesterMemberId"),
            requesterDeviceId = json.optString("requesterDeviceId"),
            requesterDisplayName = json.optString("requesterDisplayName"),
            tone = AttentionTone.fromWire(json.optString("tone")),
            durationMs = json.optLong("durationMs", 15_000L),
            volumePercent = json.optInt("volumePercent", 100),
            vibrate = json.optBoolean("vibrate", true),
            vibrationPattern = AttentionVibrationPattern.fromWire(json.optString("vibrationPattern")),
            createdAt = json.optLong("createdAt", now),
            expiresAt = json.optLong("expiresAt", now + 30_000L)
        ).normalized(now)

    fun requestToJson(request: AttentionSignalRequest): JSONObject = JSONObject().apply {
        put("requestId", request.requestId)
        putNullable("familyId", request.familyId)
        putNullable("targetMemberId", request.targetMemberId)
        put("targetDeviceId", request.targetDeviceId)
        putNullable("requesterMemberId", request.requesterMemberId)
        put("requesterDeviceId", request.requesterDeviceId)
        put("requesterDisplayName", request.requesterDisplayName)
        put("tone", request.tone.name)
        put("durationMs", request.durationMs)
        put("volumePercent", request.volumePercent)
        put("vibrate", request.vibrate)
        put("vibrationPattern", request.vibrationPattern.name)
        put("createdAt", request.createdAt)
        put("expiresAt", request.expiresAt)
    }

    fun statusFromJson(json: JSONObject): AttentionSignalStatusEvent? {
        val requestId = json.optString("requestId").trim()
        val targetDeviceId = json.optString("targetDeviceId").trim()
        val status = AttentionSignalStatus.fromWire(json.optString("status")) ?: return null
        if (requestId.isBlank()) return null
        return AttentionSignalStatusEvent(
            requestId = requestId,
            targetDeviceId = targetDeviceId,
            status = status,
            reason = json.optionalString("reason"),
            errorCode = json.optionalString("errorCode"),
            message = json.optionalString("message"),
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )
    }

    fun statusToJson(event: AttentionSignalStatusEvent): JSONObject = JSONObject().apply {
        put("requestId", event.requestId)
        put("targetDeviceId", event.targetDeviceId)
        put("status", event.status.name)
        putNullable("reason", event.reason)
        putNullable("errorCode", event.errorCode)
        putNullable("message", event.message)
        put("timestamp", event.timestamp)
    }

    private fun JSONObject.optionalString(key: String): String? =
        optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun JSONObject.putNullable(key: String, value: String?) {
        put(key, value ?: JSONObject.NULL)
    }
}
