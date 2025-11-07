package ru.example.childwatch.geofence

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LiveData
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.entity.Geofence as GeofenceEntity

/**
 * Manager for handling geofences
 * Integrates with Android Geofencing API
 */
class GeofenceManager(private val context: Context) {
    
    private val database = ChildWatchDatabase.getInstance(context)
    private val geofenceDao = database.geofenceDao()
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)
    
    companion object {
        private const val TAG = "GeofenceManager"
        const val GEOFENCE_EXPIRATION_IN_MILLISECONDS = Geofence.NEVER_EXPIRE
    }
    
    /**
     * Get all geofences for a device
     */
    fun getAllGeofences(deviceId: String): LiveData<List<GeofenceEntity>> {
        return geofenceDao.getAllGeofences(deviceId)
    }
    
    /**
     * Get active geofences for a device
     */
    fun getActiveGeofences(deviceId: String): LiveData<List<GeofenceEntity>> {
        return geofenceDao.getActiveGeofences(deviceId)
    }
    
    /**
     * Add a new geofence
     */
    suspend fun addGeofence(geofence: GeofenceEntity): Result<Long> {
        return try {
            val id = geofenceDao.insert(geofence)
            
            // Register with Android Geofencing API
            if (geofence.isActive) {
                registerGeofenceWithSystem(geofence.copy(id = id))
            }
            
            Log.d(TAG, "✅ Geofence added: ${geofence.name} (ID: $id)")
            Result.success(id)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to add geofence", e)
            Result.failure(e)
        }
    }
    
    /**
     * Update existing geofence
     */
    suspend fun updateGeofence(geofence: GeofenceEntity): Result<Unit> {
        return try {
            geofenceDao.update(geofence)
            
            // Update system registration
            unregisterGeofenceFromSystem(geofence.id.toString())
            if (geofence.isActive) {
                registerGeofenceWithSystem(geofence)
            }
            
            Log.d(TAG, "✅ Geofence updated: ${geofence.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to update geofence", e)
            Result.failure(e)
        }
    }
    
    /**
     * Delete geofence
     */
    suspend fun deleteGeofence(geofence: GeofenceEntity): Result<Unit> {
        return try {
            unregisterGeofenceFromSystem(geofence.id.toString())
            geofenceDao.delete(geofence)
            
            Log.d(TAG, "✅ Geofence deleted: ${geofence.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to delete geofence", e)
            Result.failure(e)
        }
    }
    
    /**
     * Set geofence active/inactive
     */
    suspend fun setGeofenceActive(id: Long, isActive: Boolean): Result<Unit> {
        return try {
            geofenceDao.setActive(id, isActive)
            
            if (isActive) {
                val geofence = geofenceDao.getGeofenceById(id)
                geofence?.let { registerGeofenceWithSystem(it) }
            } else {
                unregisterGeofenceFromSystem(id.toString())
            }
            
            Log.d(TAG, "✅ Geofence ${if (isActive) "activated" else "deactivated"}: $id")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to set geofence active", e)
            Result.failure(e)
        }
    }
    
    /**
     * Register geofence with Android system
     */
    private fun registerGeofenceWithSystem(geofence: GeofenceEntity) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "⚠️ Location permission not granted")
            return
        }
        
        val androidGeofence = Geofence.Builder()
            .setRequestId(geofence.id.toString())
            .setCircularRegion(
                geofence.latitude,
                geofence.longitude,
                geofence.radius
            )
            .setExpirationDuration(GEOFENCE_EXPIRATION_IN_MILLISECONDS)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
            )
            .build()
        
        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(androidGeofence)
            .build()
        
        geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent)
            .addOnSuccessListener {
                Log.d(TAG, "✅ Geofence registered with system: ${geofence.name}")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Failed to register geofence with system", e)
            }
    }
    
    /**
     * Unregister geofence from Android system
     */
    private fun unregisterGeofenceFromSystem(requestId: String) {
        geofencingClient.removeGeofences(listOf(requestId))
            .addOnSuccessListener {
                Log.d(TAG, "✅ Geofence unregistered from system: $requestId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Failed to unregister geofence from system", e)
            }
    }
    
    /**
     * PendingIntent for geofence transitions
     */
    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
    
    /**
     * Re-register all active geofences with system
     * Call this after device reboot or permission grant
     */
    fun reregisterAllGeofences(deviceId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val geofences = geofenceDao.getActiveGeofences(deviceId).value ?: emptyList()
                geofences.forEach { geofence ->
                    registerGeofenceWithSystem(geofence)
                }
                Log.d(TAG, "✅ Re-registered ${geofences.size} geofences")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to re-register geofences", e)
            }
        }
    }
}
