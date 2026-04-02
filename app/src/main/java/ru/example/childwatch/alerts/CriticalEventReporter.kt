package ru.example.childwatch.alerts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.profile.ParentEffectiveContextResolver
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
        val effectiveContext = ParentEffectiveContextResolver(context).resolve()
        val deviceId = effectiveContext.ownParentDeviceId.ifBlank {
            secureSettings.getDeviceId()?.trim().orEmpty()
        }
        val serverUrl = effectiveContext.serverUrl.ifBlank { secureSettings.getServerUrl().trim() }

        if (deviceId.isBlank() || serverUrl.isBlank()) {
            Log.w(TAG, "Skipping critical event reporting: incomplete effective context")
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
