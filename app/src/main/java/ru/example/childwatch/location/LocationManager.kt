package ru.example.childwatch.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.example.childwatch.utils.PermissionHelper
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * LocationManager wrapper for Google Play Services FusedLocationProvider
 * 
 * Features:
 * - High accuracy location updates
 * - Background location support
 * - Battery optimization
 * - Error handling for permission issues
 */
class LocationManager(private val context: Context) {
    
    companion object {
        private const val TAG = "LocationManager"
        private const val LOCATION_UPDATE_INTERVAL = 30000L // 30 seconds
        private const val LOCATION_FASTEST_INTERVAL = 15000L // 15 seconds
        private const val LOCATION_MAX_WAIT_TIME = 60000L // 1 minute
    }
    
    private val fusedLocationClient: FusedLocationProviderClient = 
        LocationServices.getFusedLocationProviderClient(context)
    
    private var locationCallback: LocationCallback? = null
    private var isRequestingUpdates = false
    
    /**
     * Get current location synchronously (for immediate requests)
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Location? = suspendCancellableCoroutine { continuation ->
        if (!PermissionHelper.hasLocationPermissions(context)) {
            Log.e(TAG, "Location permissions not granted")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        
        try {
            // Try to get last known location first (faster)
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null && isLocationFresh(location)) {
                        Log.d(TAG, "Using cached location: ${location.latitude}, ${location.longitude}")
                        continuation.resume(location)
                    } else {
                        // Request fresh location
                        requestFreshLocation(continuation)
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Failed to get last location", exception)
                    requestFreshLocation(continuation)
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting location", e)
            continuation.resume(null)
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun requestFreshLocation(continuation: kotlin.coroutines.Continuation<Location?>) {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMaxUpdates(1)
            .setMaxUpdateDelayMillis(10000L)
            .build()
        
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation
                if (location != null) {
                    Log.d(TAG, "Fresh location: ${location.latitude}, ${location.longitude}")
                    continuation.resume(location)
                } else {
                    Log.w(TAG, "Fresh location request returned null")
                    continuation.resume(null)
                }
                fusedLocationClient.removeLocationUpdates(this)
            }
            
            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "Location not available")
                    continuation.resume(null)
                    fusedLocationClient.removeLocationUpdates(this)
                }
            }
        }
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                callback,
                Looper.getMainLooper()
            )
            
            // Set timeout
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                fusedLocationClient.removeLocationUpdates(callback)
                try {
                    Log.w(TAG, "Location request timed out")
                    continuation.resume(null)
                } catch (e: IllegalStateException) {
                    // Continuation already completed
                }
            }, 15000L)
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception requesting fresh location", e)
            continuation.resume(null)
        }
    }
    
    /**
     * Start continuous location updates
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates(callback: (Location) -> Unit) {
        if (!PermissionHelper.hasLocationPermissions(context)) {
            Log.e(TAG, "Cannot start location updates: permissions not granted")
            return
        }
        
        if (isRequestingUpdates) {
            Log.d(TAG, "Location updates already started")
            return
        }
        
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL
        )
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL)
            .setMaxUpdateDelayMillis(LOCATION_MAX_WAIT_TIME)
            .build()
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    Log.d(TAG, "Location update: ${location.latitude}, ${location.longitude}")
                    callback(location)
                }
            }
            
            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.d(TAG, "Location availability: ${availability.isLocationAvailable}")
            }
        }
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            isRequestingUpdates = true
            Log.d(TAG, "Started location updates")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting location updates", e)
        }
    }
    
    /**
     * Stop continuous location updates
     */
    fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            fusedLocationClient.removeLocationUpdates(callback)
            locationCallback = null
            isRequestingUpdates = false
            Log.d(TAG, "Stopped location updates")
        }
    }
    
    /**
     * Check if location is fresh (less than 2 minutes old)
     */
    private fun isLocationFresh(location: Location): Boolean {
        val locationAge = System.currentTimeMillis() - location.time
        return locationAge < 120000L // 2 minutes
    }
    
    /**
     * Get location settings to check if location is enabled
     */
    fun checkLocationSettings(): Boolean {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL
        ).build()
        
        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
            .build()
        
        return try {
            val task = LocationServices.getSettingsClient(context)
                .checkLocationSettings(settingsRequest)
            
            val result = Tasks.await(task)
            result.locationSettingsStates?.isLocationUsable == true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking location settings", e)
            false
        }
    }
}
