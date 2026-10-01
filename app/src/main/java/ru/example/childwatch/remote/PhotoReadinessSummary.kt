package ru.example.childwatch.remote

import android.content.Context
import org.json.JSONObject
import ru.example.childwatch.R
import ru.example.childwatch.network.DeviceStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A snapshot of prerequisites, never a promise of successful background capture. */
data class PhotoReadinessUi(val summary: String, val details: String, val blocked: Boolean)

object PhotoReadinessSummary {
    private const val FRESH_MS = 120_000L
    private fun JSONObject.bool(key: String): Boolean? = opt(key) as? Boolean
    private fun time(value: Long) = SimpleDateFormat("dd.MM · HH:mm", Locale.getDefault()).format(Date(value))

    fun fromStatus(context: Context, status: DeviceStatus): PhotoReadinessUi {
        val camera = status.raw?.get("camera") as? Map<*, *>
        val json = camera?.let { JSONObject(it.entries.associate { entry -> entry.key.toString() to entry.value }) }
        return describe(context, JSONObject().put("camera", json ?: JSONObject.NULL))
    }

    fun describe(context: Context, state: JSONObject?, facing: String = "back"): PhotoReadinessUi {
        fun text(id: Int) = context.getString(id)
        val camera = state?.optJSONObject("camera")
        val collected = camera?.optLong("collectedAt", 0L) ?: 0L
        val age = System.currentTimeMillis() - collected
        val fresh = collected > 0 && age in -30_000L..FRESH_MS
        val permission = camera?.bool("permissionGranted")
        val sensor = camera?.bool("sensorBlocked")
        val available = camera?.bool(if (facing == "front") "hasFront" else "hasBack")
        val denied = state?.optBoolean("permissionDenied") == true
        val offline = state?.bool("online") == false
        val busy = state?.bool("busy") == true
        val reason = when {
            denied -> R.string.remote_photo_family_denied_hint
            offline -> R.string.photo_camera_offline
            busy -> R.string.photo_camera_busy
            fresh && permission == false -> R.string.photo_camera_permission_missing
            fresh && sensor == true -> R.string.photo_camera_sensor_blocked
            fresh && available == false -> R.string.photo_camera_absent
            !fresh && camera != null -> R.string.photo_camera_stale
            permission == null || !fresh -> R.string.photo_camera_unknown
            else -> R.string.photo_camera_permission_ok
        }
        val help = when (reason) {
            R.string.remote_photo_family_denied_hint -> R.string.photo_camera_family_permission_help
            R.string.photo_camera_busy -> R.string.photo_camera_busy_help
            R.string.photo_camera_permission_missing -> R.string.photo_camera_permission_help
            R.string.photo_camera_sensor_blocked -> R.string.photo_camera_sensor_help
            R.string.photo_camera_offline -> R.string.photo_camera_offline_help
            R.string.photo_camera_absent -> R.string.photo_camera_absent_help
            R.string.photo_camera_unknown, R.string.photo_camera_stale -> R.string.photo_camera_unknown_help
            else -> R.string.photo_camera_background_note
        }
        val lines = mutableListOf(text(reason), text(help))
        if (collected > 0) lines.add(context.getString(R.string.photo_camera_checked_at, time(collected)))
        if (camera != null) {
            fun hardware(key: String) = when (camera.bool(key)) {
                true -> text(R.string.photo_camera_present)
                false -> text(R.string.photo_camera_missing)
                null -> text(R.string.device_info_unknown)
            }
            lines.add(context.getString(R.string.photo_camera_hardware, hardware("hasBack"), hardware("hasFront")))
            val outcome = camera.optJSONObject("lastOutcome")
            val at = outcome?.optLong("timestamp", 0L) ?: 0L
            if (at > 0) {
                val error = outcome?.optString("error")?.takeUnless { it.isBlank() || it == "null" }
                if (error == null) lines.add(context.getString(R.string.photo_camera_last_success, time(at)))
                else {
                    val known = listOf("camera_background_restricted", "camera_permission_denied", "camera_in_use",
                        "photo_upload_failed", "photo_capture_timeout", "photo_service_start_failed", "camera_not_available")
                    val message = if (known.any { error.contains(it, ignoreCase = true) })
                        RemotePhotoErrorMessages.resolve(context, error).message else text(R.string.photo_camera_failure_unknown)
                    lines.add(context.getString(R.string.photo_camera_last_failed, time(at), message))
                }
            }
        }
        return PhotoReadinessUi(text(reason), lines.joinToString("\n\n"),
            denied || offline || busy || (fresh && (permission == false || sensor == true || available == false)))
    }
}
