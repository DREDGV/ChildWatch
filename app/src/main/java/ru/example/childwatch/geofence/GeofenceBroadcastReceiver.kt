package ru.example.childwatch.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.utils.NotificationManager

/**
 * BroadcastReceiver for geofence transitions
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "GeofenceBroadcastReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        
        if (geofencingEvent == null) {
            Log.e(TAG, "❌ GeofencingEvent is null")
            return
        }
        
        if (geofencingEvent.hasError()) {
            Log.e(TAG, "❌ Geofencing error: ${geofencingEvent.errorCode}")
            return
        }
        
        // Get the transition type
        val geofenceTransition = geofencingEvent.geofenceTransition
        
        // Get the geofences that were triggered
        val triggeringGeofences = geofencingEvent.triggeringGeofences
        
        if (triggeringGeofences.isNullOrEmpty()) {
            Log.w(TAG, "⚠️ No triggering geofences")
            return
        }
        
        // Handle each geofence
        triggeringGeofences.forEach { geofence ->
            handleGeofenceTransition(context, geofence, geofenceTransition)
        }
    }
    
    /**
     * Handle individual geofence transition
     */
    private fun handleGeofenceTransition(
        context: Context,
        geofence: Geofence,
        transitionType: Int
    ) {
        val geofenceId = geofence.requestId.toLongOrNull() ?: return
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = ChildWatchDatabase.getInstance(context)
                val geofenceEntity = database.geofenceDao().getGeofenceById(geofenceId)
                
                if (geofenceEntity == null) {
                    Log.w(TAG, "⚠️ Geofence not found in database: $geofenceId")
                    return@launch
                }
                
                val own = ru.example.childwatch.profile.ParentEffectiveContextResolver(context).resolveOwnParentId().removePrefix("device_")
                if (own.isBlank() || geofenceEntity.deviceId.removePrefix("device_") != own) return@launch
                when (transitionType) {
                    Geofence.GEOFENCE_TRANSITION_ENTER -> {
                        Log.d(TAG, "📍 Entered geofence: ${geofenceEntity.name}")
                        if (geofenceEntity.notificationOnEnter) {
                            showNotification(
                                context,
                                geofenceEntity.name,
                                "Вы вошли в место",
                                isExit = false
                            )
                        }
                    }
                    
                    Geofence.GEOFENCE_TRANSITION_EXIT -> {
                        Log.d(TAG, "📍 Exited geofence: ${geofenceEntity.name}")
                        if (geofenceEntity.notificationOnExit) {
                            showNotification(
                                context,
                                geofenceEntity.name,
                                "Вы покинули место",
                                isExit = true
                            )
                        }
                    }
                    
                    else -> {
                        Log.w(TAG, "⚠️ Unknown transition type: $transitionType")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error handling geofence transition", e)
            }
        }
    }
    
    /**
     * Show notification for geofence event
     */
    private fun showNotification(
        context: Context,
        zoneName: String,
        message: String,
        isExit: Boolean
    ) {
        NotificationManager.showGeofenceNotification(
            context = context,
            title = zoneName,
            message = message,
            isExit = isExit
        )
    }
}
