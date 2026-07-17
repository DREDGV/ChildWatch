package ru.example.childwatch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.example.childwatch.utils.ParentMonitorProfileManager
import ru.example.childwatch.service.MonitorService
import ru.example.childwatch.service.ChatBackgroundService
import ru.example.childwatch.utils.SecureSettingsManager

/**
 * BootReceiver to restart monitoring after device reboot
 * 
 * Only restarts if user previously gave consent and enabled monitoring
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Boot receiver triggered: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                handleBootCompleted(context)
            }
        }
    }
    
    private fun handleBootCompleted(context: Context) {
        val prefs = context.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
        ParentMonitorProfileManager(context).reconcileCurrentState()
        val effectiveContext = ParentEffectiveContextResolver(context).resolve()
        val hasConsent = prefs.getBoolean("consent_given", false)
        val wasMonitoring = prefs.getBoolean("was_monitoring", false)
        val serverUrl = effectiveContext.serverUrl.ifBlank {
            SecureSettingsManager(context).getServerUrl().trim()
        }
        val childDeviceId = effectiveContext.linkedChildDeviceId
        
        Log.d(
            TAG,
            "Boot completed - consent=$hasConsent, wasMonitoring=$wasMonitoring, deviceId=${childDeviceId?.take(6)}..."
        )
        
        if (hasConsent && wasMonitoring) {
            // Restart monitoring service
            val serviceIntent = Intent(context, MonitorService::class.java).apply {
                action = MonitorService.ACTION_START_MONITORING
            }
            
            try {
                context.startForegroundService(serviceIntent)
                Log.d(TAG, "Restarted monitoring service after boot")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart monitoring service after boot", e)
            }
        }

        // Чат и связи должны подниматься даже если мониторинг не был активен
        if (hasConsent && childDeviceId.isNotBlank() && serverUrl.isNotBlank()) {
            try {
                ChatBackgroundService.start(context, serverUrl, childDeviceId)
                Log.d(TAG, "ChatBackgroundService restarted after boot with deviceId=$childDeviceId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart chat service after boot", e)
            }
        } else if (serverUrl.isBlank()) {
            Log.w(TAG, "Cannot restart chat service after boot - server URL missing")
        } else if (childDeviceId.isEmpty()) {
            Log.w(TAG, "Cannot restart chat service after boot - child_device_id missing")
        }
    }
}
