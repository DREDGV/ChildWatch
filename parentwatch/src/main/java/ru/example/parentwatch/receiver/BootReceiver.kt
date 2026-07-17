package ru.example.parentwatch.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import ru.example.parentwatch.R
import ru.example.parentwatch.session.ChildActiveSessionStore
import ru.example.parentwatch.session.ChildEffectiveContextResolver
import ru.example.parentwatch.service.LocationService
import ru.example.parentwatch.service.ChatBackgroundService
import ru.example.parentwatch.utils.ServerUrlResolver

/**
 * Boot receiver to auto-start location service
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val ACTION_RETRY_MONITORING = "ru.example.parentwatch.RETRY_MONITORING_START"
        private const val EXTRA_RETRY_COUNT = "retry_count"
        private const val MAX_RETRY_COUNT = 2
        private const val RETRY_REQUEST_CODE_BASE = 7310
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_REBOOT,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_RETRY_MONITORING -> {
                Log.d(TAG, "Boot-related action received: ${intent.action}")

                val retryCount = intent.getIntExtra(EXTRA_RETRY_COUNT, 0)

                val prefs = context.getSharedPreferences("parentwatch_prefs", Context.MODE_PRIVATE)
                val sessionStore = ChildActiveSessionStore(context)
                val effectiveContext = ChildEffectiveContextResolver(context).resolveEffectiveContext()
                val wasRunning = prefs.getBoolean("service_running", false)
                val autoStart = prefs.getBoolean("auto_start_on_boot", true)
                var deviceId = effectiveContext?.ownChildDeviceId?.takeIf { it.isNotBlank() }
                    ?: sessionStore.resolveCurrentChildId().takeIf { it.isNotBlank() }
                val childDeviceId = prefs.getString("child_device_id", null)

                if (deviceId.isNullOrEmpty() && !childDeviceId.isNullOrEmpty()) {
                    deviceId = childDeviceId
                    prefs.edit()
                        .putString("device_id", childDeviceId)
                        .putBoolean("device_id_permanent", true)
                        .apply()
                }

                val serverUrl = effectiveContext?.serverUrl?.takeIf { it.isNotBlank() }
                    ?: sessionStore.resolveCurrentServerUrl().takeIf { it.isNotBlank() }
                    ?: ServerUrlResolver.getServerUrl(context)

                val shouldStart = (wasRunning || autoStart) && !deviceId.isNullOrEmpty() && !serverUrl.isNullOrBlank()
                if (!shouldStart) {
                    Log.w(TAG, "Skipping auto-start: wasRunning=$wasRunning autoStart=$autoStart deviceId=$deviceId serverUrl=$serverUrl")
                    scheduleRetry(context, retryCount)
                    return
                }

                sessionStore.applySession(
                    sessionStore.buildSession(
                        name = sessionStore.getActiveSession()?.name?.takeIf { it.isNotBlank() }
                            ?: context.getString(R.string.profile_switch_current_name),
                        serverUrl = serverUrl!!,
                        ownChildDeviceId = deviceId!!,
                        linkedParentDeviceId = effectiveContext?.linkedParentDeviceId
                            ?.takeIf { it.isNotBlank() }
                            ?: sessionStore.resolveCurrentParentId().takeIf { it.isNotBlank() }
                            ?: sessionStore.resolveCurrentParentId()
                    )
                )

                Log.d(TAG, "Restarting LocationService after boot")
                val serviceIntent = Intent(context, LocationService::class.java).apply {
                    action = LocationService.ACTION_START
                    putExtra("server_url", serverUrl)
                    putExtra("device_id", deviceId)
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d(TAG, "LocationService restart requested after boot/update")
                } catch (error: Exception) {
                    Log.e(TAG, "Unable to restart LocationService after boot/update", error)
                }

                try {
                    ChatBackgroundService.start(context, serverUrl, deviceId!!)
                    Log.d(TAG, "ChatBackgroundService restart requested after boot/update")
                } catch (error: Exception) {
                    // LocationService also starts and supervises chat after it reaches
                    // foreground, so a platform restriction here is recoverable.
                    Log.e(TAG, "Direct chat restart was rejected; LocationService will retry", error)
                }

                // A boot broadcast can arrive before user storage, networking, or the
                // platform foreground-service allowance is fully ready.  Retrying is
                // harmless: LocationService ignores ACTION_START when already tracking.
                scheduleRetry(context, retryCount)
            }
        }
    }

    private fun scheduleRetry(context: Context, retryCount: Int) {
        if (retryCount >= MAX_RETRY_COUNT) return

        val nextRetry = retryCount + 1
        val delayMs = if (nextRetry == 1) 30_000L else 90_000L
        val retryIntent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_RETRY_MONITORING
            putExtra(EXTRA_RETRY_COUNT, nextRetry)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            RETRY_REQUEST_CODE_BASE + nextRetry,
            retryIntent,
            flags
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = android.os.SystemClock.elapsedRealtime() + delayMs

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
        } else {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }
        Log.d(TAG, "Scheduled monitoring recovery retry #$nextRetry in ${delayMs / 1000}s")
    }
}
