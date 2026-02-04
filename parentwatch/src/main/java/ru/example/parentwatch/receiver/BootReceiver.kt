package ru.example.parentwatch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import ru.example.parentwatch.service.LocationService
import ru.example.parentwatch.service.ChatBackgroundService
import ru.example.parentwatch.service.PhotoCaptureService
import ru.example.parentwatch.utils.ServerUrlResolver

/**
 * Boot receiver to auto-start location service
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_REBOOT,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "Boot-related action received: ${intent.action}")

                val prefs = context.getSharedPreferences("parentwatch_prefs", Context.MODE_PRIVATE)
                val wasRunning = prefs.getBoolean("service_running", false)
                val autoStart = prefs.getBoolean("auto_start_on_boot", true)
                var deviceId = prefs.getString("device_id", null)
                val childDeviceId = prefs.getString("child_device_id", null)

                if (deviceId.isNullOrEmpty() && !childDeviceId.isNullOrEmpty()) {
                    deviceId = childDeviceId
                    prefs.edit()
                        .putString("device_id", childDeviceId)
                        .putBoolean("device_id_permanent", true)
                        .apply()
                }

                val serverUrl = ServerUrlResolver.getServerUrl(context)

                val shouldStart = (wasRunning || autoStart) && !deviceId.isNullOrEmpty() && !serverUrl.isNullOrBlank()
                if (!shouldStart) {
                    Log.w(TAG, "Skipping auto-start: wasRunning=$wasRunning autoStart=$autoStart deviceId=$deviceId serverUrl=$serverUrl")
                    return
                }

                Log.d(TAG, "Restarting LocationService after boot")
                val serviceIntent = Intent(context, LocationService::class.java).apply {
                    action = LocationService.ACTION_START
                    putExtra("server_url", serverUrl)
                    putExtra("device_id", deviceId)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                ChatBackgroundService.start(context, serverUrl, deviceId!!)
                PhotoCaptureService.start(context, serverUrl, deviceId)
                Log.d(TAG, "Background services restarted after boot")
            }
        }
    }
}
