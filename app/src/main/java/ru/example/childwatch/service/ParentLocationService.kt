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
        private const val LOCATION_UPDATE_INTERVAL_IDLE = 30_000L
        private const val LOCATION_FASTEST_INTERVAL_IDLE = 15_000L
        private const val LOCATION_UPDATE_INTERVAL_MOVING = 5_000L
        private const val LOCATION_FASTEST_INTERVAL_MOVING = 3_000L
        private const val LOCATION_UPLOAD_INTERVAL_IDLE = 60_000L
        private const val LOCATION_UPLOAD_INTERVAL_MOVING = 5_000L
        private const val LOCATION_UPLOAD_DISTANCE_IDLE_METERS = 35f
        private const val LOCATION_UPLOAD_DISTANCE_MOVING_METERS = 10f
        private const val MOVING_SPEED_THRESHOLD_MPS = 0.7f
        private const val MOVEMENT_DISTANCE_THRESHOLD_METERS = 20f
        private const val MOVEMENT_TIME_WINDOW_MS = 45_000L
        private const val TRACKING_MODE_STICKINESS_MS = 45_000L
        
        /**
         * Starts tracking, tolerating a refusal by the system.
         *
         * On Android 12+ the platform rejects a foreground service start made
         * while the app is in the background and throws
         * ForegroundServiceStartNotAllowedException. This is called from places
         * such as switching the selected child, so an unguarded call could take
         * the whole application down instead of merely not tracking.
         */
        fun start(context: Context) {
            if (!hasLocationPermission(context)) {
                Log.i(TAG, "Location sharing awaits user permission")
                return
            }
            val intent = Intent(context, ParentLocationService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { error ->
                Log.w(TAG, "Location service start refused by the system", error)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ParentLocationService::class.java))
            }.onFailure { error ->
                Log.w(TAG, "Location service stop failed", error)
            }
        }

        private fun hasLocationPermission(context: Context): Boolean =
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentTrackingMode = TrackingMode.IDLE
    private var lastTrackingModeChangeAt = 0L
    private var lastObservedLocation: Location? = null
    @Volatile private var lastUploadedLocation: Location? = null
    @Volatile private var lastUploadAt: Long = 0L
    @Volatile private var uploadInFlight = false
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ParentLocationService created")
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (!hasLocationPermission(this)) { stopSelf(); return }
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (refused: SecurityException) {
            Log.w(TAG, "Location foreground access is unavailable", refused)
            stopSelf()
            return
        }
        
        setupLocationUpdates()
    }
    
    private val locationOutbox by lazy { ru.example.childwatch.designsystem.LocationOutbox(this) }
    private fun outboxScope(): String? {
        val resolver = ParentEffectiveContextResolver(this)
        return ru.example.childwatch.designsystem.LocationOutbox.scope(resolver.resolveServerUrl(), resolver.resolveFamilyId(), resolver.resolveOwnParentId())
    }

    private fun setupLocationUpdates() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    if (!ru.example.childwatch.designsystem.LocationQuality.usable(location)) return
                    updateTrackingMode(location)
                    val scope = outboxScope()
                    val moving = currentTrackingMode == TrackingMode.MOVING
                    serviceScope.launch(Dispatchers.IO) {
                        try { locationOutbox.enqueue(scope, location, moving) }
                        catch (error: Exception) { Log.w(TAG, "Cannot retain measured location", error) }
                        if (scope == outboxScope() && shouldUploadLocation(location)) sendLocationToChild(location)
                    }
                }
            }
        }
        
        val locationRequest = buildLocationRequest()
        
        if (hasLocationPermission(this)) {
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
    
    @Synchronized private fun sendLocationToChild(location: Location) {
        val queuedScope = outboxScope()
        if (uploadInFlight) return
        uploadInFlight = true
        serviceScope.launch {
            try {
                val parentId = resolveParentDeviceId()
                if (parentId.isNullOrBlank()) {
                    Log.w(TAG, "Parent device ID is missing, skipping location upload")
                    return@launch
                }
                if (queuedScope != outboxScope()) return@launch
                serviceScope.launch { ru.example.childwatch.remote.ParentDeviceStatusReporter.report(this@ParentLocationService) }
                if (queuedScope != outboxScope()) return@launch
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
                    put("timestamp", location.time)
                    ru.example.childwatch.designsystem.LocationMotion.put(this, location, this@ParentLocationService)
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
                var accepted = false
                try {
                    if (serverUrl.isNotBlank()) {
                        accepted = ru.example.childwatch.network.NetworkClient(this@ParentLocationService)
                            .uploadParentLocation(
                                parentId = parentId,
                                latitude = location.latitude,
                                longitude = location.longitude,
                                accuracy = location.accuracy,
                                timestamp = location.time,
                                speed = ru.example.childwatch.designsystem.LocationMotion.speed(location),
                                speedAccuracyMps = ru.example.childwatch.designsystem.LocationMotion.accuracy(location),
                                measurementElapsedRealtimeNanos = ru.example.childwatch.designsystem.LocationMotion.measurementElapsedRealtimeNanos(location),
                                bootSessionId = ru.example.childwatch.designsystem.LocationMotion.bootSessionId(this@ParentLocationService),
                                bearing = location.bearing.takeIf { it > 0 } ?: 0f,
                                batteryLevel = null
                            )
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) {
                    Log.w(TAG, "Failed to upload parent location via REST", e)
                }

                if (accepted) {
                    lastUploadedLocation = Location(location)
                    lastUploadAt = android.os.SystemClock.elapsedRealtime()
                    withContext(Dispatchers.IO) {
                        if (queuedScope == outboxScope()) {
                            locationOutbox.acknowledge(queuedScope, location.time)
                            val family = ParentEffectiveContextResolver(this@ParentLocationService).resolveFamilyId()
                            if (!family.isNullOrBlank()) repeat(3) {
                                if (queuedScope != outboxScope()) return@withContext
                                val batch = locationOutbox.batch(queuedScope, location.time)
                                if (batch.length() == 0) return@withContext
                                if (!ru.example.childwatch.network.NetworkClient(this@ParentLocationService).uploadLocationHistory(serverUrl, family, parentId, batch)) return@withContext
                                locationOutbox.acknowledge(queuedScope, batch)
                            }
                        }
                    }
                }
                
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                Log.e(TAG, "Error sending parent location", e)
            } finally { uploadInFlight = false }
        }
    }

    private fun buildLocationRequest(): LocationRequest {
        val (priority, interval, fastest, maxDelay) = when (currentTrackingMode) {
            TrackingMode.MOVING -> Quadruple(
                Priority.PRIORITY_HIGH_ACCURACY,
                LOCATION_UPDATE_INTERVAL_MOVING,
                LOCATION_FASTEST_INTERVAL_MOVING,
                0L
            )
            TrackingMode.IDLE -> Quadruple(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                LOCATION_UPDATE_INTERVAL_IDLE,
                LOCATION_FASTEST_INTERVAL_IDLE,
                30_000L
            )
        }

        return LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(fastest)
            .setMaxUpdateDelayMillis(maxDelay)
            .setMinUpdateDistanceMeters(0f) // Allow stationary heartbeat fixes.
            .build()
    }

    private fun shouldUploadLocation(location: Location): Boolean {
        if (uploadInFlight || !ru.example.childwatch.designsystem.LocationQuality.usable(location)) return false
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

        val elapsed = android.os.SystemClock.elapsedRealtime() - lastUploadAt
        if (elapsed >= minInterval) {
            return true
        }

        if (elapsed >= 3_000L && location.distanceTo(previous) >= maxOf(minDistance, location.accuracy + previous.accuracy)) {
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
        return if (ru.example.childwatch.designsystem.LocationQuality.moving(location, previous, MOVING_SPEED_THRESHOLD_MPS))
            TrackingMode.MOVING else TrackingMode.IDLE
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
        if (!::locationCallback.isInitialized || !hasLocationPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
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
