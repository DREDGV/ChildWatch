package ru.example.childwatch.alerts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.utils.SecureSettingsManager

object CriticalEventReporter {

    private const val TAG = "CriticalEventReporter"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun report(
        context: Context,
        eventType: String,
        severity: String,
        message: String,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        val secureSettings = SecureSettingsManager(context)
        val deviceId = secureSettings.getDeviceId()
        val serverUrl = secureSettings.getServerUrl()

        if (deviceId.isNullOrBlank()) {
            Log.w(TAG, "Skipping critical event reporting: deviceId is missing")
            return
        }

        scope.launch {
            try {
                val client = NetworkClient(context)
                val success = client.sendCriticalEvent(
                    serverUrl,
                    deviceId,
                    eventType,
                    severity,
                    message,
                    metadata
                )

                if (!success) {
                    Log.w(TAG, "Failed to send critical event: $eventType - $severity")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending critical event", e)
            }
        }
    }
}
