package ru.example.parentwatch.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import ru.example.parentwatch.database.ChildWatchDatabase
import ru.example.parentwatch.database.entity.ParentLocation
import ru.example.parentwatch.network.NetworkClient
import java.util.concurrent.TimeUnit

/**
 * Parent Location Tracker
 * 
 * Отслеживает локацию родителя для функции "Где родители?"
 * - Периодичность: каждые 60 секунд
 * - Priority: PRIORITY_BALANCED_POWER_ACCURACY (экономия батареи)
 * - Автоматическая загрузка на сервер и сохранение в БД
 */
class ParentLocationTracker(
    private val context: Context,
    private val parentId: String
) {
    private val TAG = "ParentLocationTracker"
    
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    
    private val database = ChildWatchDatabase.getInstance(context)
    private val networkClient = NetworkClient(context)
    
    private var locationCallback: LocationCallback? = null
    private var isTracking = false
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val UPDATE_INTERVAL = 60_000L // 60 секунд
        private const val FASTEST_INTERVAL = 30_000L // 30 секунд минимум
        private const val MAX_WAIT_TIME = 120_000L // 2 минуты максимум
    }
    
    /**
     * Начать отслеживание локации родителя
     */
    fun startTracking() {
        if (isTracking) {
            Log.w(TAG, "Tracking already started")
            return
        }
        
        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission not granted")
            return
        }
        
        Log.i(TAG, "Starting parent location tracking for parentId: $parentId")
        
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            UPDATE_INTERVAL
        ).apply {
            setMinUpdateIntervalMillis(FASTEST_INTERVAL)
            setMaxUpdateDelayMillis(MAX_WAIT_TIME)
            setWaitForAccurateLocation(false)
        }.build()
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    handleLocationUpdate(location)
                }
            }
            
            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "Location not available")
                }
            }
        }
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            isTracking = true
            Log.i(TAG, "Parent location tracking started successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting location tracking", e)
        }
    }
    
    /**
     * Остановить отслеживание локации
     */
    fun stopTracking() {
        if (!isTracking) {
            Log.w(TAG, "Tracking not active")
            return
        }
        
        Log.i(TAG, "Stopping parent location tracking")
        
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        
        locationCallback = null
        isTracking = false
        
        Log.i(TAG, "Parent location tracking stopped")
    }
    
    /**
     * Обработка обновления локации
     */
    private fun handleLocationUpdate(location: Location) {
        Log.d(TAG, "Location update: lat=${location.latitude}, lon=${location.longitude}, accuracy=${location.accuracy}")
        
        scope.launch {
            try {
                val batteryLevel = getBatteryLevel()
                
                val parentLocation = ParentLocation(
                    parentId = parentId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    timestamp = System.currentTimeMillis(),
                    provider = location.provider ?: "fused",
                    batteryLevel = batteryLevel,
                    speed = if (location.hasSpeed()) location.speed else null,
                    bearing = if (location.hasBearing()) location.bearing else null
                )
                
                // Сохранить в локальную БД
                database.parentLocationDao().insertLocation(parentLocation)
                Log.d(TAG, "Location saved to local database")
                
                // Загрузить на сервер
                uploadLocationToServer(parentLocation)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling location update", e)
            }
        }
    }
    
    /**
     * Загрузка локации на сервер
     */
    private suspend fun uploadLocationToServer(location: ParentLocation) {
        try {
            val success = networkClient.uploadParentLocation(
                parentId = location.parentId,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                timestamp = location.timestamp,
                speed = location.speed,
                bearing = location.bearing,
                batteryLevel = location.batteryLevel
            )
            
            if (success) {
                Log.d(TAG, "Location uploaded to server successfully")
            } else {
                Log.w(TAG, "Failed to upload location to server")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading location to server", e)
        }
    }
    
    /**
     * Получить уровень заряда батареи
     */
    private fun getBatteryLevel(): Int? {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            Log.w(TAG, "Could not get battery level", e)
            null
        }
    }
    
    /**
     * Проверка наличия разрешений на локацию
     */
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Проверить, активно ли отслеживание
     */
    fun isTracking(): Boolean = isTracking
    
    /**
     * Очистка ресурсов
     */
    fun cleanup() {
        stopTracking()
        scope.cancel()
        Log.i(TAG, "ParentLocationTracker cleanup complete")
    }
}

