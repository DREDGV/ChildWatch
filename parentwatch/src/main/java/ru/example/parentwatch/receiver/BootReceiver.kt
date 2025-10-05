package ru.example.parentwatch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import ru.example.parentwatch.service.LocationService

/**
 * Boot receiver to auto-start location service
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed")

            val prefs = context.getSharedPreferences("parentwatch_prefs", Context.MODE_PRIVATE)
            val wasRunning = prefs.getBoolean("service_running", false)

            if (wasRunning) {
                Log.d(TAG, "Restarting location service")

                val serviceIntent = Intent(context, LocationService::class.java).apply {
                    action = LocationService.ACTION_START
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
