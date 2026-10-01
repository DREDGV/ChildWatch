package ru.example.parentwatch.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject
import ru.example.parentwatch.session.ChildEffectiveContextResolver

/** Reads capability/permission only. Never opens the camera for a diagnostic check. */
object CameraDiagnostics {
    private fun key(server: String, own: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest((server.trimEnd('/') + "\n" + own).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun recordOutcome(context: Context, server: String, own: String, error: String?) {
        if (server.isBlank() || own.isBlank()) return
        context.getSharedPreferences("camera_diagnostics", Context.MODE_PRIVATE).edit()
            .putString(key(server, own), JSONObject().put("error", error ?: JSONObject.NULL)
                .put("timestamp", System.currentTimeMillis()).toString()).apply()
    }

    fun snapshot(context: Context): JSONObject = JSONObject().apply {
        put("collectedAt", System.currentTimeMillis())
        put("permissionGranted", ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        // Android exposes toggle support, but not a public synchronous query for its state.
        // Permission/camera capability alone must never claim the privacy toggle is off.
        put("sensorBlocked", if (Build.VERSION.SDK_INT >= 31) JSONObject.NULL else false)
        val facings = runCatching {
            val manager = context.getSystemService(CameraManager::class.java) ?: return@runCatching null
            manager.cameraIdList.map { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }
        }.getOrNull()
        put("hasBack", facings?.contains(CameraCharacteristics.LENS_FACING_BACK) ?: JSONObject.NULL)
        put("hasFront", facings?.contains(CameraCharacteristics.LENS_FACING_FRONT) ?: JSONObject.NULL)
        val resolver = ChildEffectiveContextResolver(context)
        val stored = context.getSharedPreferences("camera_diagnostics", Context.MODE_PRIVATE)
            .getString(key(resolver.resolveServerUrl(), resolver.resolveChildDeviceId()), null)
        val outcome = stored?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (outcome != null) put("lastOutcome", outcome)
        // Permission does not prove Android will allow foreground service camera use from the background.
        put("backgroundCaptureVerified", false)
    }
}
