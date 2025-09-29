package ru.example.childwatch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ru.example.childwatch.service.MonitorService

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
            "android.intent.action.QUICKBOOT_POWERON" -> {
                handleBootCompleted(context)
            }
        }
    }
    
    private fun handleBootCompleted(context: Context) {
        val prefs = context.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
        val hasConsent = prefs.getBoolean("consent_given", false)
        val wasMonitoring = prefs.getBoolean("was_monitoring", false)
        
        Log.d(TAG, "Boot completed - consent: $hasConsent, was monitoring: $wasMonitoring")
        
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
    }
}
