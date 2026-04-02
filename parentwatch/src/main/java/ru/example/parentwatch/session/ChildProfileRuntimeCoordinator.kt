package ru.example.parentwatch.session

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import ru.example.parentwatch.network.NetworkClient
import ru.example.parentwatch.network.WebSocketManager
import ru.example.parentwatch.service.AudioStreamingService
import ru.example.parentwatch.service.ChatBackgroundService
import ru.example.parentwatch.service.LocationService
import ru.example.parentwatch.service.PhotoCaptureService
import ru.example.parentwatch.utils.ChildDeviceProfile
import ru.example.parentwatch.utils.ChildDeviceProfileManager

class ChildProfileRuntimeCoordinator(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("parentwatch_prefs", Context.MODE_PRIVATE)
    private val profileManager = ChildDeviceProfileManager(appContext)
    private val sessionStore = ChildActiveSessionStore(appContext)
    private val networkClient = NetworkClient(appContext)

    fun applyProfile(
        profile: ChildDeviceProfile,
        monitoringEnabled: Boolean
    ): ChildEffectiveContext? {
        profileManager.applyProfile(profile)
        return refreshRuntime(monitoringEnabled)
    }

    fun refreshRuntime(monitoringEnabled: Boolean): ChildEffectiveContext? {
        profileManager.reconcileCurrentState()
        val effectiveContext = sessionStore.resolveEffectiveContext()

        val childId = effectiveContext?.ownChildDeviceId.orEmpty()
        if (childId.isNotBlank()) {
            networkClient.replaceDeviceIdentity(childId)
        }

        WebSocketManager.cleanup()

        if (monitoringEnabled) {
            restartMonitoring(effectiveContext)
        } else {
            stopRealtimeServices()
            prefs.edit().putBoolean("service_running", false).apply()
        }

        return effectiveContext
    }

    private fun restartMonitoring(effectiveContext: ChildEffectiveContext?) {
        stopRealtimeServices()

        val serverUrl = effectiveContext?.serverUrl.orEmpty()
        val childId = effectiveContext?.ownChildDeviceId.orEmpty()
        if (serverUrl.isBlank() || childId.isBlank()) return

        val serviceIntent = Intent(appContext, LocationService::class.java).apply {
            action = LocationService.ACTION_START
            putExtra("server_url", serverUrl)
            putExtra("device_id", childId)
        }
        ContextCompat.startForegroundService(appContext, serviceIntent)
        ChatBackgroundService.start(appContext, serverUrl, childId)
        prefs.edit().putBoolean("service_running", true).apply()
    }

    private fun stopRealtimeServices() {
        val stopIntent = Intent(appContext, LocationService::class.java).apply {
            action = LocationService.ACTION_STOP
        }
        appContext.stopService(stopIntent)
        ChatBackgroundService.stop(appContext)
        PhotoCaptureService.stop(appContext)
        AudioStreamingService.stopStreaming(appContext)
    }
}
