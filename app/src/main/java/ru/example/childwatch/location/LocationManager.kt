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
        private const val LOCATION_REQUEST_TIMEOUT = 30000L // 30 seconds (increased from 15s)
        private const val LOCATION_FRESHNESS_THRESHOLD = 300000L // 5 minutes (increased from 2 min)
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
        var highAccuracyCompleted = false
        var timeoutTriggered = false

        // Try HIGH_ACCURACY first (GPS + network)
        val highAccuracyRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMaxUpdates(1)
            .setMaxUpdateDelayMillis(10000L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation
                if (location != null && !highAccuracyCompleted) {
                    highAccuracyCompleted = true
                    Log.d(TAG, "Fresh location (high accuracy): ${location.latitude}, ${location.longitude}, accuracy=${location.accuracy}m")
                    continuation.resume(location)
                    fusedLocationClient.removeLocationUpdates(this)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable && !highAccuracyCompleted) {
                    Log.w(TAG, "High accuracy location not available, trying balanced power mode...")
                    // Try fallback to network location
                    tryBalancedPowerLocation(continuation)
                    fusedLocationClient.removeLocationUpdates(this)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                highAccuracyRequest,
                callback,
                Looper.getMainLooper()
            )

            // Set timeout - increased to 30 seconds
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                if (!highAccuracyCompleted) {
                    timeoutTriggered = true
                    fusedLocationClient.removeLocationUpdates(callback)
                    Log.w(TAG, "High accuracy location request timed out, trying network fallback...")
                    tryBalancedPowerLocation(continuation)
                }
            }, LOCATION_REQUEST_TIMEOUT)

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception requesting fresh location", e)
            continuation.resume(null)
        }
    }

    /**
     * Fallback to BALANCED_POWER_ACCURACY (uses WiFi/cell towers)
     * Faster and works indoors better than GPS
     */
    @SuppressLint("MissingPermission")
    private fun tryBalancedPowerLocation(continuation: kotlin.coroutines.Continuation<Location?>) {
        var completed = false

        val balancedRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 1000L)
            .setMaxUpdates(1)
            .setMaxUpdateDelayMillis(5000L)
            .build()

        val fallbackCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation
                if (location != null && !completed) {
                    completed = true
                    Log.d(TAG, "Fallback location (balanced power): ${location.latitude}, ${location.longitude}, accuracy=${location.accuracy}m")
                    try {
                        continuation.resume(location)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Continuation already resumed")
                    }
                    fusedLocationClient.removeLocationUpdates(this)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                balancedRequest,
                fallbackCallback,
                Looper.getMainLooper()
            )

            // Set shorter timeout for fallback (10 seconds)
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                if (!completed) {
                    completed = true
                    fusedLocationClient.removeLocationUpdates(fallbackCallback)
                    Log.w(TAG, "Network fallback also timed out")
                    try {
                        continuation.resume(null)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Continuation already resumed")
                    }
                }
            }, 10000L)

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in fallback location", e)
            try {
                continuation.resume(null)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Continuation already resumed")
            }
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
     * Check if location is fresh (less than 5 minutes old)
     * Increased from 2 to 5 minutes to use cached location more often
     */
    private fun isLocationFresh(location: Location): Boolean {
        val locationAge = System.currentTimeMillis() - location.time
        return locationAge < LOCATION_FRESHNESS_THRESHOLD
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
