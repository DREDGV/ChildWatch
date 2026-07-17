package ru.example.childwatch.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.example.childwatch.profile.ParentEffectiveContextProvider
import ru.example.childwatch.R
import ru.example.childwatch.network.WebSocketManager
import ru.example.childwatch.utils.SecureSettingsManager

/**
 * ParentLocationService - отправляет локацию родителя ребёнку через WebSocket
 * 
 * Работает в фоне и периодически отправляет координаты родителя,
 * если включена настройка "Делиться моей локацией"
 */
class ParentLocationService : Service() {

    private enum class TrackingMode {
        IDLE,
        MOVING
    }
    
    companion object {
        private const val TAG = "ParentLocationService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "parent_location_channel"
        private const val LOCATION_UPDATE_INTERVAL_IDLE = 60_000L
        private const val LOCATION_FASTEST_INTERVAL_IDLE = 30_000L
        private const val LOCATION_UPDATE_INTERVAL_MOVING = 15_000L
        private const val LOCATION_FASTEST_INTERVAL_MOVING = 7_000L
        private const val LOCATION_UPLOAD_INTERVAL_IDLE = 90_000L
        private const val LOCATION_UPLOAD_INTERVAL_MOVING = 18_000L
        private const val LOCATION_UPLOAD_DISTANCE_IDLE_METERS = 35f
        private const val LOCATION_UPLOAD_DISTANCE_MOVING_METERS = 10f
        private const val MOVING_SPEED_THRESHOLD_MPS = 1.4f
        private const val MOVEMENT_DISTANCE_THRESHOLD_METERS = 20f
        private const val MOVEMENT_TIME_WINDOW_MS = 45_000L
        private const val TRACKING_MODE_STICKINESS_MS = 45_000L
        
        fun start(context: Context) {
            val intent = Intent(context, ParentLocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, ParentLocationService::class.java))
        }
    }
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentTrackingMode = TrackingMode.IDLE
    private var lastTrackingModeChangeAt = 0L
    private var lastObservedLocation: Location? = null
    private var lastUploadedLocation: Location? = null
    private var lastUploadAt: Long = 0L
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ParentLocationService created")
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        setupLocationUpdates()
    }
    
    private fun setupLocationUpdates() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    updateTrackingMode(location)
                    if (shouldUploadLocation(location)) {
                        sendLocationToChild(location)
                    }
                }
            }
        }
        
        val locationRequest = buildLocationRequest()
        
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                null
            )
            Log.d(TAG, "Location updates started")
        } else {
            Log.w(TAG, "Location permission not granted")
        }
    }
    
    private fun sendLocationToChild(location: Location) {
        serviceScope.launch {
            try {
                val parentId = resolveParentDeviceId()
                if (parentId.isNullOrBlank()) {
                    Log.w(TAG, "Parent device ID is missing, skipping location upload")
                    return@launch
                }
                val targetDeviceId = resolveTargetDeviceId()
                val serverUrl = ParentEffectiveContextProvider.get(this@ParentLocationService)
                    .featureContext("location")?.serverUrl
                    ?.takeIf { it.isNotBlank() }
                    ?: SecureSettingsManager(this@ParentLocationService).getServerUrl().trim()
                
                val locationData = org.json.JSONObject().apply {
                    put("parentId", parentId)
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("accuracy", location.accuracy)
                    put("timestamp", System.currentTimeMillis())
                    put("speed", location.speed.takeIf { it > 0 } ?: 0f)
                    put("bearing", location.bearing.takeIf { it > 0 } ?: 0f)
                    if (!targetDeviceId.isNullOrBlank()) {
                        put("targetDevice", targetDeviceId)
                    }
                }
                
                // Отправить через WebSocket
                if (WebSocketManager.isConnected()) {
                    WebSocketManager.getClient()?.emit("parent_location", locationData)
                    Log.d(TAG, "Parent location sent: ${location.latitude}, ${location.longitude}")
                } else {
                    Log.w(TAG, "WebSocket not connected, skipping location send")
                }

                // Дополнительно отправим на сервер REST для fallback карты
                try {
                    if (serverUrl.isNotBlank()) {
                        ru.example.childwatch.network.NetworkClient(this@ParentLocationService)
                            .uploadParentLocation(
                                parentId = parentId,
                                latitude = location.latitude,
                                longitude = location.longitude,
                                accuracy = location.accuracy,
                                timestamp = System.currentTimeMillis(),
                                speed = location.speed.takeIf { it > 0 } ?: 0f,
                                bearing = location.bearing.takeIf { it > 0 } ?: 0f,
                                batteryLevel = null
                            )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to upload parent location via REST", e)
                }

                lastUploadedLocation = Location(location)
                lastUploadAt = System.currentTimeMillis()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending parent location", e)
            }
        }
    }

    private fun buildLocationRequest(): LocationRequest {
        val (priority, interval, fastest, maxDelay) = when (currentTrackingMode) {
            TrackingMode.MOVING -> Quadruple(
                Priority.PRIORITY_HIGH_ACCURACY,
                LOCATION_UPDATE_INTERVAL_MOVING,
                LOCATION_FASTEST_INTERVAL_MOVING,
                LOCATION_UPDATE_INTERVAL_MOVING * 2
            )
            TrackingMode.IDLE -> Quadruple(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                LOCATION_UPDATE_INTERVAL_IDLE,
                LOCATION_FASTEST_INTERVAL_IDLE,
                LOCATION_UPDATE_INTERVAL_IDLE * 2
            )
        }

        return LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(fastest)
            .setMaxUpdateDelayMillis(maxDelay)
            .setMinUpdateDistanceMeters(
                if (currentTrackingMode == TrackingMode.MOVING) {
                    LOCATION_UPLOAD_DISTANCE_MOVING_METERS
                } else {
                    LOCATION_UPLOAD_DISTANCE_IDLE_METERS
                }
            )
            .build()
    }

    private fun shouldUploadLocation(location: Location): Boolean {
        val previous = lastUploadedLocation ?: return true
        val minInterval = if (currentTrackingMode == TrackingMode.MOVING) {
            LOCATION_UPLOAD_INTERVAL_MOVING
        } else {
            LOCATION_UPLOAD_INTERVAL_IDLE
        }
        val minDistance = if (currentTrackingMode == TrackingMode.MOVING) {
            LOCATION_UPLOAD_DISTANCE_MOVING_METERS
        } else {
            LOCATION_UPLOAD_DISTANCE_IDLE_METERS
        }

        val elapsed = System.currentTimeMillis() - lastUploadAt
        if (elapsed >= minInterval) {
            return true
        }

        if (location.distanceTo(previous) >= minDistance) {
            return true
        }

        val currentAccuracy = location.accuracy.takeIf { it > 0f } ?: Float.MAX_VALUE
        val previousAccuracy = previous.accuracy.takeIf { it > 0f } ?: Float.MAX_VALUE
        return elapsed >= (minInterval / 2) && currentAccuracy + 15f < previousAccuracy
    }

    private fun updateTrackingMode(location: Location) {
        val previous = lastObservedLocation?.let { Location(it) }
        lastObservedLocation = Location(location)

        val candidateMode = determineTrackingMode(location, previous)
        if (candidateMode == currentTrackingMode) {
            return
        }

        val now = System.currentTimeMillis()
        val shouldPromote = trackingRank(candidateMode) > trackingRank(currentTrackingMode)
        val canDowngrade = now - lastTrackingModeChangeAt >= TRACKING_MODE_STICKINESS_MS
        if (!shouldPromote && !canDowngrade) {
            return
        }

        currentTrackingMode = candidateMode
        lastTrackingModeChangeAt = now
        restartLocationUpdatesForCurrentMode()
        Log.d(TAG, "Parent location tracking mode changed to $candidateMode")
    }

    private fun determineTrackingMode(location: Location, previous: Location?): TrackingMode {
        val measuredSpeed = location.speed.takeIf { it > 0f }
        if (measuredSpeed != null) {
            return if (measuredSpeed >= MOVING_SPEED_THRESHOLD_MPS) {
                TrackingMode.MOVING
            } else {
                TrackingMode.IDLE
            }
        }

        if (previous != null) {
            val elapsed = (location.time - previous.time).takeIf { it > 0L }
                ?: TRACKING_MODE_STICKINESS_MS
            if (elapsed <= MOVEMENT_TIME_WINDOW_MS &&
                location.distanceTo(previous) >= MOVEMENT_DISTANCE_THRESHOLD_METERS
            ) {
                return TrackingMode.MOVING
            }
        }

        return TrackingMode.IDLE
    }

    private fun trackingRank(mode: TrackingMode): Int = when (mode) {
        TrackingMode.IDLE -> 0
        TrackingMode.MOVING -> 1
    }

    private fun restartLocationUpdatesForCurrentMode() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        runCatching {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            fusedLocationClient.requestLocationUpdates(
                buildLocationRequest(),
                locationCallback,
                null
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to restart parent location updates", error)
        }
    }

    private fun resolveParentDeviceId(): String? {
        val legacyPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val secure = SecureSettingsManager(this)
        val resolved = ParentEffectiveContextResolver(this)
            .resolveOwnParentCandidates(legacyPrefs.getString("device_id", null))
            .firstOrNull()
        if (!resolved.isNullOrBlank() && secure.getDeviceId().isNullOrBlank()) {
            secure.setDeviceId(resolved)
        }
        return resolved
    }

    private fun resolveTargetDeviceId(): String? {
        return ParentEffectiveContextProvider.get(this).featureContext("location")?.targetDeviceId
            ?.takeIf { it.isNotBlank() }
            ?: ParentEffectiveContextResolver(this).resolveTargetDeviceId().takeIf { it.isNotBlank() }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Передача локации родителя",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление о работе службы передачи локации"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, ru.example.childwatch.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Передача локации")
            .setContentText("Ваша локация передаётся ребёнку")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        Log.d(TAG, "ParentLocationService destroyed")
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}
