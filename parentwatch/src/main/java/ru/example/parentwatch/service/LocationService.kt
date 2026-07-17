package ru.example.parentwatch.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R
import ru.example.parentwatch.network.NetworkHelper
import ru.example.parentwatch.audio.AudioStreamRecorder
import ru.example.parentwatch.session.ChildActiveSessionStore
import ru.example.parentwatch.session.ChildEffectiveContextResolver
import ru.example.parentwatch.utils.AppVisibilityTracker
import ru.example.parentwatch.utils.DeviceInfoCollector
import ru.example.parentwatch.utils.RemoteLogger
import ru.example.parentwatch.utils.ServerUrlResolver

/**
 * Foreground service for continuous location tracking and audio streaming
 */
class LocationService : Service() {

    companion object {
        private const val TAG = "LocationService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "location_tracking"
        private const val LOCATION_UPDATE_INTERVAL_BALANCED = 60_000L
        private const val LOCATION_FASTEST_INTERVAL_BALANCED = 30_000L
        private const val LOCATION_UPDATE_INTERVAL_TRANSIT = 15_000L
        private const val LOCATION_FASTEST_INTERVAL_TRANSIT = 7_000L
        private const val LOCATION_UPDATE_INTERVAL_ACTIVE = 30_000L
        private const val LOCATION_FASTEST_INTERVAL_ACTIVE = 15_000L
        private const val LOCATION_UPLOAD_INTERVAL_BALANCED = 90_000L
        private const val LOCATION_UPLOAD_INTERVAL_TRANSIT = 18_000L
        private const val LOCATION_UPLOAD_INTERVAL_ACTIVE = 25_000L
        private const val LOCATION_UPLOAD_DISTANCE_BALANCED_METERS = 35f
        private const val LOCATION_UPLOAD_DISTANCE_TRANSIT_METERS = 10f
        private const val LOCATION_UPLOAD_DISTANCE_ACTIVE_METERS = 10f
        private const val COMMAND_CHECK_INTERVAL_WS_HEALTHY = 60_000L
        private const val COMMAND_CHECK_INTERVAL_WS_DEGRADED = 10_000L
        private const val CHAT_SERVICE_RECOVERY_COOLDOWN_MS = 20_000L
        private const val REGISTRATION_RECOVERY_COOLDOWN_MS = 30_000L
        private const val MOVING_SPEED_THRESHOLD_MPS = 1.4f
        private const val FAST_TRANSIT_SPEED_THRESHOLD_MPS = 6.0f
        private const val MOVEMENT_DISTANCE_THRESHOLD_METERS = 20f
        private const val MOVEMENT_TIME_WINDOW_MS = 45_000L
        private const val TRACKING_MODE_STICKINESS_MS = 45_000L

        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_EMERGENCY_STOP = "emergency_stop"
        const val ACTION_START_AUDIO_STREAM = "start_audio_stream"
        const val ACTION_STOP_AUDIO_STREAM = "stop_audio_stream"
        const val ACTION_PAUSE_AUDIO_CAPTURE_FOR_PHOTO = "pause_audio_capture_for_photo"
        const val ACTION_RESUME_AUDIO_CAPTURE_AFTER_PHOTO = "resume_audio_capture_after_photo"
        const val ACTION_RETRY_AUDIO_AFTER_FOREGROUND = "retry_audio_after_foreground"
        const val EXTRA_AUDIO_RECORDING = "audio_recording"
        const val EXTRA_AUDIO_SAMPLE_RATE = "audio_sample_rate"

        @Volatile
        var isServiceAlive = false
            private set

        fun requestAudioStart(context: Context, recording: Boolean, sampleRate: Int = 24_000) {
            val intent = Intent(context, LocationService::class.java).apply {
                action = ACTION_START_AUDIO_STREAM
                putExtra(EXTRA_AUDIO_RECORDING, recording)
                putExtra(EXTRA_AUDIO_SAMPLE_RATE, sampleRate)
            }
            context.startService(intent)
        }

        fun requestAudioStop(context: Context) {
            val intent = Intent(context, LocationService::class.java).apply {
                action = ACTION_STOP_AUDIO_STREAM
            }
            context.startService(intent)
        }

        fun pauseAudioCaptureForPhoto(context: Context) {
            if (!isServiceAlive) return
            val intent = Intent(context, LocationService::class.java).apply {
                action = ACTION_PAUSE_AUDIO_CAPTURE_FOR_PHOTO
            }
            context.startService(intent)
        }

        fun resumeAudioCaptureAfterPhoto(context: Context) {
            if (!isServiceAlive) return
            val intent = Intent(context, LocationService::class.java).apply {
                action = ACTION_RESUME_AUDIO_CAPTURE_AFTER_PHOTO
            }
            context.startService(intent)
        }

        fun retryAudioAfterForeground(context: Context) {
            if (!isServiceAlive) return
            val intent = Intent(context, LocationService::class.java).apply {
                action = ACTION_RETRY_AUDIO_AFTER_FOREGROUND
            }
            context.startService(intent)
        }
    }

    private enum class TrackingMode {
        BALANCED,
        TRANSIT,
        CRITICAL
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var networkHelper: NetworkHelper
    private lateinit var audioRecorder: AudioStreamRecorder
    private lateinit var prefs: SharedPreferences
    private lateinit var effectiveContextResolver: ChildEffectiveContextResolver
    private lateinit var activeSessionStore: ChildActiveSessionStore

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isTracking = false
    private var isStreamingAudio = false
    /**
     * True only after this process successfully adds the CAMERA foreground-service type while an
     * activity is visible. Never persist this flag: Android revokes the while-in-use elevation
     * when the process is recreated (for example after a reboot).
     */
    private var cameraForegroundPrimed = false
    private var deviceId: String? = null
    private var serverUrl: String? = null
    private var commandCheckJob: Job? = null
    private var registrationJob: Job? = null
    private var registrationRecoveryJob: Job? = null
    private var locationUpdatesStarted = false
    private var currentTrackingMode = TrackingMode.BALANCED
    private var lastTrackingModeChangeAt = 0L
    private var lastObservedLocation: Location? = null
    private val locationUploadStateLock = Any()
    private var lastUploadedLocation: Location? = null
    private var lastLocationUploadAt: Long = 0L
    private var locationUploadInFlight = false
    @Volatile private var lastChatServiceRecoveryAt = 0L
    @Volatile private var lastRegistrationRecoveryAt = 0L
    private val photoRequestListener: (String, String, String) -> Unit = { requestId, targetDevice, cameraFacing ->
        val currentServerUrl = serverUrl
        val currentDeviceId = deviceId
        if (!currentServerUrl.isNullOrBlank() && !currentDeviceId.isNullOrBlank()) {
            Log.d(TAG, "LocationService relaying photo request: req=$requestId target=$targetDevice")
            PhotoCaptureService.dispatchPhotoRequest(
                this,
                currentServerUrl,
                currentDeviceId,
                requestId,
                targetDevice,
                cameraFacing
            )
        } else {
            Log.w(TAG, "Skipping photo relay: monitoring context is incomplete")
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceAlive = true
        android.util.Log.d("ParentWatch", "LocationService onCreate called")
        Log.d(TAG, "Service created")

        prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        effectiveContextResolver = ChildEffectiveContextResolver(this)
        activeSessionStore = ChildActiveSessionStore(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        networkHelper = NetworkHelper(this)
        audioRecorder = AudioStreamRecorder(this, networkHelper)
        ru.example.parentwatch.network.WebSocketManager.addPhotoRequestListener(photoRequestListener)

        createNotificationChannel()
        setupLocationCallback()
        android.util.Log.d("ParentWatch", "LocationService onCreate completed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("ParentWatch", "onStartCommand: ${intent?.action}")
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> startTracking(intent)
            ACTION_STOP -> stopTracking()
            ACTION_EMERGENCY_STOP -> emergencyStopAll()
            ACTION_START_AUDIO_STREAM -> {
                val recording = intent.getBooleanExtra(EXTRA_AUDIO_RECORDING, false)
                val sampleRate = intent.getIntExtra(EXTRA_AUDIO_SAMPLE_RATE, 24_000)
                if (!isTracking) {
                    Log.w(TAG, "Audio start requested while monitoring is inactive, attempting recovery")
                    startTracking(null)
                }
                if (!isTracking) {
                    val fallbackDeviceId = effectiveContextResolver.resolveChildDeviceId()
                        .takeIf { it.isNotBlank() }
                        ?: activeSessionStore.resolveCurrentChildId().takeIf { it.isNotBlank() }
                        ?: prefs.getString("child_device_id", null)?.takeIf { !it.isNullOrBlank() }
                        ?: prefs.getString("device_id", null)?.takeIf { !it.isNullOrBlank() }
                    val fallbackServerUrl = effectiveContextResolver.resolveServerUrl()
                        .takeIf { it.isNotBlank() }
                        ?: activeSessionStore.resolveCurrentServerUrl().takeIf { it.isNotBlank() }
                        ?: ServerUrlResolver.getServerUrl(this)
                    if (!fallbackDeviceId.isNullOrBlank() && !fallbackServerUrl.isNullOrBlank()) {
                        Log.w(TAG, "Monitoring service still inactive, falling back to AudioStreamingService")
                        AudioStreamingService.startStreaming(
                            this,
                            fallbackDeviceId,
                            fallbackServerUrl,
                            recording,
                            sampleRate
                        )
                    } else {
                        Log.w(TAG, "Cannot recover audio start: missing monitoring context")
                    }
                    return START_STICKY
                }
                startAudioStreaming(recording = recording, sampleRate = sampleRate)
            }
            ACTION_STOP_AUDIO_STREAM -> {
                AudioStreamingService.stopStreaming(this)
                if (isStreamingAudio) {
                    stopAudioStreaming()
                }
            }
            ACTION_PAUSE_AUDIO_CAPTURE_FOR_PHOTO -> {
                pauseAudioCaptureForPhoto()
            }
            ACTION_RESUME_AUDIO_CAPTURE_AFTER_PHOTO -> {
                resumeAudioCaptureAfterPhoto()
            }
            ACTION_RETRY_AUDIO_AFTER_FOREGROUND -> {
                retryAudioCaptureAfterForeground()
            }
            null -> {
                // Service restarted by system, resume tracking if it was running
                Log.d(TAG, "Service restarted by system, resuming tracking")
                if (!isTracking) {
                    startTracking(null)
                }
            }
        }

        return START_STICKY
    }

    private fun startTracking(startIntent: Intent?) {
        if (isTracking) {
            Log.d(TAG, "Already tracking")
            return
        }

        // Start foreground service FIRST to avoid crash
        promoteToForeground()
        primeCameraForegroundAccessIfVisible()

        // Load settings (prefer intent extras to avoid async prefs race)
        val effectiveContext = effectiveContextResolver.resolveEffectiveContext()
        val intentDeviceId = startIntent?.getStringExtra("device_id")?.takeIf { it.isNotBlank() }
        val intentServerUrl = startIntent?.getStringExtra("server_url")?.takeIf { it.isNotBlank() }

        if (intentDeviceId != null) {
            deviceId = intentDeviceId
            // Commit synchronously to ensure immediately readable by service/restarts
            prefs.edit()
                .putString("device_id", intentDeviceId)
                .putString("child_device_id", intentDeviceId)
                .putBoolean("device_id_permanent", true)
                .commit()
        } else {
            deviceId = effectiveContext?.ownChildDeviceId?.takeIf { it.isNotBlank() }
                ?: activeSessionStore.resolveCurrentChildId().takeIf { it.isNotBlank() }
                ?: prefs.getString("child_device_id", null)
                ?: prefs.getString("device_id", null)
        }

        if (intentServerUrl != null) {
            serverUrl = intentServerUrl
            prefs.edit().putString("server_url", intentServerUrl).commit()
        } else {
            serverUrl = effectiveContext?.serverUrl?.takeIf { it.isNotBlank() }
                ?: activeSessionStore.resolveCurrentServerUrl().takeIf { it.isNotBlank() }
                ?: ServerUrlResolver.getServerUrl(this)
        }

        if (!deviceId.isNullOrBlank() && !serverUrl.isNullOrBlank()) {
            val activeSession = activeSessionStore.getActiveSession()
            activeSessionStore.applySession(
                activeSessionStore.buildSession(
                    name = activeSession?.name?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.profile_switch_current_name),
                    serverUrl = serverUrl!!.trim(),
                    ownChildDeviceId = deviceId!!.trim(),
                    linkedParentDeviceId = effectiveContext?.linkedParentDeviceId
                        ?.takeIf { it.isNotBlank() }
                        ?: activeSession?.linkedParentDeviceId.orEmpty()
                        ?: activeSessionStore.resolveCurrentParentId()
                )
            )
        }

        if (deviceId == null) {
            Log.e(TAG, "Device ID not set")
            stopSelf()
            return
        }
        if (serverUrl.isNullOrBlank()) {
            Log.e(TAG, "Server URL not set")
            stopSelf()
            return
        }

        isTracking = true
        locationUpdatesStarted = false
        currentTrackingMode = TrackingMode.BALANCED
        lastTrackingModeChangeAt = System.currentTimeMillis()
        lastObservedLocation = null
        synchronized(locationUploadStateLock) {
            lastUploadedLocation = null
            lastLocationUploadAt = 0L
            locationUploadInFlight = false
        }
        ChatBackgroundService.start(this, serverUrl!!, deviceId!!)
        // Register device with retries (network may be unavailable right after boot)
        startRegistrationLoop()
        prefs.edit().putBoolean("service_running", true).apply()

        // Audio listening starts only from an explicit start_audio_stream command.
        // Auto-start here races with the dedicated WebSocket audio path and can grab the mic too early.
    }

    private fun startRegistrationLoop() {
        registrationJob?.cancel()
        registrationRecoveryJob?.cancel()
        registrationJob = serviceScope.launch {
            var attempt = 0
            while (isActive && isTracking && !locationUpdatesStarted) {
                try {
                    val ok = networkHelper.registerDevice(serverUrl!!, deviceId!!)
                    if (ok) {
                        Log.d(TAG, "Device registered")
                        ru.example.parentwatch.network.WebSocketManager.reconnectWithCurrentAuth(
                            onReady = { Log.i(TAG, "WebSocket reconnected with current authentication") },
                            onError = { error: String ->
                                Log.w(TAG, "WebSocket authentication reconnect failed: $error")
                            }
                        )
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@LocationService,
                                getString(R.string.location_service_monitoring_active),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        startLocationUpdates()
                        locationUpdatesStarted = true
                        return@launch
                    }
                    Log.e(TAG, "Failed to register device (attempt ${attempt + 1})")
                } catch (e: Exception) {
                    Log.e(TAG, "Registration error", e)
                }

                val delayMs = (1_000L * (1 shl attempt.coerceAtMost(5))).coerceAtMost(60_000L)
                delay(delayMs)
                attempt++
            }
        }
    }

    private fun stopTracking() {
        if (!isTracking) return

        registrationJob?.cancel()
        registrationJob = null
        registrationRecoveryJob?.cancel()
        registrationRecoveryJob = null

        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopForeground(true)
        stopSelf()

        isTracking = false
        locationUpdatesStarted = false
        synchronized(locationUploadStateLock) {
            lastUploadedLocation = null
            lastLocationUploadAt = 0L
            locationUploadInFlight = false
        }
        lastObservedLocation = null
        currentTrackingMode = TrackingMode.BALANCED
        lastTrackingModeChangeAt = 0L
        prefs.edit().putBoolean("service_running", false).apply()

        Log.d(TAG, "Tracking stopped")
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    handleLocationUpdate(location)
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permission not granted")
            stopSelf()
            return
        }

        val locationRequest = buildLocationRequest()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        // Start command checking
        startCommandChecking()

        Log.d(TAG, "Location updates started")
    }

    private fun handleLocationUpdate(location: Location) {
        Log.d(TAG, "Location update: ${location.latitude}, ${location.longitude}")
        updateTrackingModeFromLocation(location)

        if (!beginLocationUpload(location)) {
            return
        }

        // Upload to server with device info
        serviceScope.launch {
            // Collect device info
            val deviceInfo = DeviceInfoCollector.getDeviceInfo(
                this@LocationService,
                includeCurrentApp = DeviceInfoCollector.shouldIncludeAppUsageSnapshot()
            )

            val success = networkHelper.uploadLocationWithDeviceInfo(
                serverUrl!!,
                location.latitude,
                location.longitude,
                location.accuracy,
                deviceInfo
            )

            finishLocationUpload(location, success)

            if (success) {
                val batteryStatus = DeviceInfoCollector.getBatteryStatus(this@LocationService)
                updateNotification(
                    getString(R.string.location_service_notification_active_with_battery, batteryStatus)
                )
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setSound(null, null)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String? = null): Notification {
        val resolvedContentText = contentText ?: getString(R.string.location_service_notification_active)
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(resolvedContentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun promoteToForeground(contentText: String? = null) {
        val notification = createNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                composeForegroundServiceTypes(includeMicrophone = false)
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun promoteToForegroundForAudio(): Boolean {
        val notification = createNotification(
            getString(
                R.string.location_service_notification_active_with_battery,
                DeviceInfoCollector.getBatteryStatus(this)
            )
        )
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    composeForegroundServiceTypes(includeMicrophone = true)
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.onFailure { error ->
            Log.w(TAG, "Unable to promote LocationService for microphone capture", error)
            RemoteLogger.warn(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "Microphone foreground promotion failed",
                meta = mapOf("error" to (error.message ?: error.javaClass.simpleName))
            )
        }.getOrDefault(false)
    }

    /**
     * Android 11+ only grants background camera access when the camera FGS type is activated while
     * the app has a visible activity. LocationService is already persistent, so priming it avoids
     * a second permanent notification from PhotoCaptureService.
     */
    private fun primeCameraForegroundAccessIfVisible(): Boolean {
        val cameraPermissionGranted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!cameraPermissionGranted) {
            cameraForegroundPrimed = false
            Log.w(TAG, "Camera foreground access not primed: CAMERA permission missing")
            return false
        }
        if (cameraForegroundPrimed) return true
        if (!AppVisibilityTracker.isVisible()) {
            Log.d(TAG, "Camera foreground access not primed: no visible activity")
            return false
        }

        val notification = createNotification(
            getString(
                R.string.location_service_notification_active_with_battery,
                DeviceInfoCollector.getBatteryStatus(this)
            )
        )
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val microphoneRequested = isStreamingAudio ||
                    (this::audioRecorder.isInitialized && audioRecorder.isStreamingDesired())
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    composeForegroundServiceTypes(
                        includeMicrophone = microphoneRequested,
                        includeCamera = true
                    )
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to prime foreground camera access", error)
            RemoteLogger.warn(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "Camera foreground promotion failed",
                meta = mapOf("error" to (error.message ?: error.javaClass.simpleName))
            )
        }.isSuccess

        if (promoted) {
            cameraForegroundPrimed = true
            Log.i(TAG, "Foreground camera access primed while app is visible")
        } else {
            // Keep the existing location foreground service alive even if an OEM rejects CAMERA.
            promoteToForeground()
        }
        return promoted
    }

    private fun composeForegroundServiceTypes(
        includeMicrophone: Boolean,
        includeCamera: Boolean = cameraForegroundPrimed
    ): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (includeMicrophone &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (includeCamera &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return types
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    /**
     * Start periodic command checking
     */
    private fun startCommandChecking() {
        commandCheckJob?.cancel()
        commandCheckJob = serviceScope.launch {
            while (isTracking) {
                try {
                    checkStreamingCommands()
                    ensureBackgroundServicesHealthy()
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking commands", e)
                }
                delay(resolveCommandCheckInterval())
            }
        }
    }

    /**
     * Check for streaming commands from server
     */
    private suspend fun checkStreamingCommands() {
        val deviceId = this.deviceId
        val serverUrl = this.serverUrl

        if (deviceId.isNullOrBlank() || serverUrl.isNullOrBlank()) {
            RemoteLogger.error(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "Skipping command check due to missing identifiers"
            )
            return
        }

        Log.d(TAG, "Checking commands for device $deviceId at $serverUrl")
        val commands = networkHelper.getStreamingCommands(serverUrl, deviceId)
        Log.d(TAG, "Received ${'$'}{commands.size} commands from server")
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "Fetched streaming commands",
            meta = mapOf("count" to commands.size)
        )

        for (command in commands) {
            Log.d(TAG, "Processing command: ${'$'}{command.type}")
            RemoteLogger.info(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "Processing command",
                meta = mapOf(
                    "type" to command.type,
                    "timestamp" to command.timestamp
                )
            )

            val wsAudioOwnerActive = ChatBackgroundService.isRunning

            when (command.type) {
                "start_audio_stream" -> {
                    val sampleRate = command.data?.optInt("sampleRate", 24_000) ?: 24_000
                    val recording = command.data?.optBoolean("recording", false) ?: false
                    if (wsAudioOwnerActive) {
                        Log.w(
                            TAG,
                            "Polled start_audio_stream received while WS owner is active - keeping LocationService as single capture owner"
                        )
                    }
                    startAudioStreaming(recording = recording, sampleRate = sampleRate)
                }
                "stop_audio_stream" -> {
                    AudioStreamingService.stopStreaming(this)
                    if (!wsAudioOwnerActive || isStreamingAudio) {
                        stopAudioStreaming()
                    }
                }
                "start_recording" -> {
                    if (!wsAudioOwnerActive) {
                        audioRecorder.setRecordingMode(true)
                        Log.d(TAG, "Recording mode enabled (silent)")
                    }
                }
                "stop_recording" -> {
                    if (!wsAudioOwnerActive) {
                        audioRecorder.setRecordingMode(false)
                        Log.d(TAG, "Recording mode disabled (silent)")
                    }
                }
            }
        }
    }

    /**
     * Start audio streaming
     */
    /**
     * Start audio streaming
     */
    private fun startAudioStreaming(recording: Boolean, sampleRate: Int = 24_000) {
        stopLegacyAudioBackstopIfNeeded("location_service_start")

        if (isStreamingAudio) {
            if (!audioRecorder.isActive()) {
                Log.w(TAG, "Streaming flag set but recorder inactive - restarting")
                try {
                    audioRecorder.stopStreaming()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to stop stale audio recorder", e)
                }
                isStreamingAudio = false
            } else {
                // Service can remain "active" with alive WS but paused mic (e.g., after reconnect race).
                // Force recorder resume on repeated start command.
                promoteToForegroundForAudio()
                audioRecorder.ensureCaptureRunning()
                Log.w(TAG, "Already streaming audio, capture resume check performed")
                RemoteLogger.warn(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    source = TAG,
                    message = "startAudioStreaming called while already active; ensured capture running"
                )
                return
            }
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Audio permission not granted - streaming cannot start")
            RemoteLogger.error(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "Audio permission not granted - streaming cannot start"
            )
            return
        }

        val deviceId = this.deviceId ?: run {
            RemoteLogger.warn(
                serverUrl = serverUrl,
                deviceId = null,
                source = TAG,
                message = "Device ID missing - cannot start streaming"
            )
            return
        }
        val serverUrl = this.serverUrl ?: run {
            RemoteLogger.warn(
                serverUrl = null,
                deviceId = deviceId,
                source = TAG,
                message = "Server URL missing - cannot start streaming"
            )
            return
        }

        promoteToForegroundForAudio()
        audioRecorder.startStreaming(deviceId, serverUrl, recording, sampleRate)
        isStreamingAudio = true
        restartLocationUpdatesForCurrentMode()
        updateNotification(getString(R.string.location_service_notification_active_with_battery, DeviceInfoCollector.getBatteryStatus(this)))

        Log.d(TAG, "Audio streaming requested (silent mode)")
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "Audio streaming requested from LocationService",
            meta = mapOf("recording" to recording)
        )
    }

    private fun stopLegacyAudioBackstopIfNeeded(reason: String) {
        if (!AudioStreamingService.isStreamingDesired(this) && !AudioStreamingService.isServiceAlive) {
            return
        }
        Log.w(TAG, "Stopping legacy AudioStreamingService backstop ($reason)")
        runCatching {
            AudioStreamingService.stopStreaming(this)
        }.onFailure { error ->
            Log.w(TAG, "Failed to stop legacy AudioStreamingService backstop", error)
        }
    }

    private fun pauseAudioCaptureForPhoto() {
        if (!isStreamingAudio) {
            return
        }
        Log.d(TAG, "Pausing active audio capture for remote photo")
        runCatching {
            audioRecorder.pauseCapture()
        }.onFailure { error ->
            Log.w(TAG, "Failed to pause audio capture for remote photo", error)
        }
    }

    private fun resumeAudioCaptureAfterPhoto() {
        if (!isStreamingAudio) {
            return
        }
        Log.d(TAG, "Resuming active audio capture after remote photo")
        runCatching {
            promoteToForegroundForAudio()
            audioRecorder.resumeCapture()
        }.onFailure { error ->
            Log.w(TAG, "Failed to resume audio capture after remote photo", error)
        }
    }

    private fun retryAudioCaptureAfterForeground() {
        primeCameraForegroundAccessIfVisible()
        if (!isStreamingAudio && !audioRecorder.isStreamingDesired()) return

        Log.d(TAG, "Retrying requested audio capture while app is visible")
        promoteToForegroundForAudio()
        audioRecorder.retryCaptureAfterForeground()
    }

    /**
     * Stop audio streaming
     */
    private fun stopAudioStreaming() {
        if (!isStreamingAudio) {
            return
        }

        val deviceId = this.deviceId
        val serverUrl = this.serverUrl

        audioRecorder.stopStreaming()
        isStreamingAudio = false
        restartLocationUpdatesForCurrentMode()
        if (isServiceAlive) {
            promoteToForeground(getString(R.string.location_service_notification_active))
        }

        Log.d(TAG, "Audio streaming stopped (silent mode)")
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "Audio streaming stopped from LocationService"
        )
    }

    private fun ensureBackgroundServicesHealthy() {
        val deviceId = this.deviceId?.takeIf { it.isNotBlank() } ?: return
        val serverUrl = this.serverUrl?.takeIf { it.isNotBlank() } ?: return
        val socketDegraded =
            !ru.example.parentwatch.network.WebSocketManager.isConnected() ||
                !ru.example.parentwatch.network.WebSocketManager.isReady()

        if (!ChatBackgroundService.isRunning || shouldRestartChatService(socketDegraded)) {
            lastChatServiceRecoveryAt = System.currentTimeMillis()
            Log.w(
                TAG,
                if (!ChatBackgroundService.isRunning) {
                    "ChatBackgroundService is down, restarting from LocationService"
                } else {
                    "ChatBackgroundService socket degraded, forcing self-heal restart"
                }
            )
            ChatBackgroundService.start(this, serverUrl, deviceId)
        }

        if (!locationUpdatesStarted && registrationJob?.isActive != true) {
            Log.w(TAG, "Location registration loop is idle, restarting it")
            startRegistrationLoop()
        }

        if (socketDegraded) {
            maybeRecoverRegistration(serverUrl, deviceId)
        }

        if (isStreamingAudio) {
            stopLegacyAudioBackstopIfNeeded("health_check")
        }
    }

    private fun shouldRestartChatService(socketDegraded: Boolean): Boolean {
        if (!socketDegraded) return false
        val now = System.currentTimeMillis()
        return now - lastChatServiceRecoveryAt >= CHAT_SERVICE_RECOVERY_COOLDOWN_MS
    }

    private fun maybeRecoverRegistration(serverUrl: String, deviceId: String) {
        if (registrationRecoveryJob?.isActive == true) return

        val now = System.currentTimeMillis()
        if (now - lastRegistrationRecoveryAt < REGISTRATION_RECOVERY_COOLDOWN_MS) return

        lastRegistrationRecoveryAt = now
        registrationRecoveryJob = serviceScope.launch {
            runCatching {
                Log.w(TAG, "Attempting background registration recovery for degraded child connection")
                val recovered = networkHelper.registerDevice(serverUrl, deviceId)
                if (recovered) {
                    Log.i(TAG, "Background registration recovery succeeded")
                    ru.example.parentwatch.network.WebSocketManager.reconnectWithCurrentAuth(
                        onReady = { Log.i(TAG, "WebSocket re-registered after background recovery") },
                        onError = { error: String ->
                            Log.w(TAG, "WebSocket recovery request failed: $error")
                        }
                    )
                } else {
                    Log.w(TAG, "Background registration recovery failed")
                }
            }.onFailure { error ->
                Log.w(TAG, "Background registration recovery crashed", error)
            }
        }
    }

    private fun buildLocationRequest(): LocationRequest {
        val trackingMode = effectiveTrackingMode()
        val (priority, interval, fastest, minDistance) = when (trackingMode) {
            TrackingMode.CRITICAL -> {
                Quadruple(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    LOCATION_UPDATE_INTERVAL_ACTIVE,
                    LOCATION_FASTEST_INTERVAL_ACTIVE,
                    LOCATION_UPLOAD_DISTANCE_ACTIVE_METERS
                )
            }
            TrackingMode.TRANSIT -> {
                Quadruple(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    LOCATION_UPDATE_INTERVAL_TRANSIT,
                    LOCATION_FASTEST_INTERVAL_TRANSIT,
                    LOCATION_UPLOAD_DISTANCE_TRANSIT_METERS
                )
            }
            TrackingMode.BALANCED -> {
                Quadruple(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    LOCATION_UPDATE_INTERVAL_BALANCED,
                    LOCATION_FASTEST_INTERVAL_BALANCED,
                    LOCATION_UPLOAD_DISTANCE_BALANCED_METERS
                )
            }
        }
        return LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(fastest)
            .setMinUpdateDistanceMeters(minDistance)
            .build()
    }

    private fun resolveCommandCheckInterval(): Long {
        val socketHealthy = ChatBackgroundService.isRunning &&
            ru.example.parentwatch.network.WebSocketManager.isConnected() &&
            ru.example.parentwatch.network.WebSocketManager.isReady()
        return if (socketHealthy) {
            COMMAND_CHECK_INTERVAL_WS_HEALTHY
        } else {
            COMMAND_CHECK_INTERVAL_WS_DEGRADED
        }
    }

    private fun restartLocationUpdatesForCurrentMode() {
        if (!locationUpdatesStarted || !isTracking) return
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
                Looper.getMainLooper()
            )
            Log.d(TAG, "Location request updated for current power mode")
        }.onFailure { error ->
            Log.w(TAG, "Failed to restart location updates for power mode change", error)
        }
    }

    private fun beginLocationUpload(location: Location): Boolean {
        synchronized(locationUploadStateLock) {
            if (locationUploadInFlight) {
                return false
            }
            if (!shouldUploadLocationLocked(location)) {
                return false
            }
            locationUploadInFlight = true
            return true
        }
    }

    private fun shouldUploadLocationLocked(location: Location): Boolean {
        val lastLocation = lastUploadedLocation ?: return true
        val trackingMode = effectiveTrackingMode()
        val minInterval = when (trackingMode) {
            TrackingMode.CRITICAL -> LOCATION_UPLOAD_INTERVAL_ACTIVE
            TrackingMode.TRANSIT -> LOCATION_UPLOAD_INTERVAL_TRANSIT
            TrackingMode.BALANCED -> LOCATION_UPLOAD_INTERVAL_BALANCED
        }
        val minDistance = when (trackingMode) {
            TrackingMode.CRITICAL -> LOCATION_UPLOAD_DISTANCE_ACTIVE_METERS
            TrackingMode.TRANSIT -> LOCATION_UPLOAD_DISTANCE_TRANSIT_METERS
            TrackingMode.BALANCED -> LOCATION_UPLOAD_DISTANCE_BALANCED_METERS
        }

        val elapsed = System.currentTimeMillis() - lastLocationUploadAt
        if (elapsed >= minInterval) {
            return true
        }

        val distance = location.distanceTo(lastLocation)
        if (distance >= minDistance) {
            return true
        }

        val currentAccuracy = location.accuracy.takeIf { it > 0f } ?: Float.MAX_VALUE
        val previousAccuracy = lastLocation.accuracy.takeIf { it > 0f } ?: Float.MAX_VALUE
        return elapsed >= (minInterval / 2) && currentAccuracy + 15f < previousAccuracy
    }

    private fun finishLocationUpload(location: Location, success: Boolean) {
        synchronized(locationUploadStateLock) {
            locationUploadInFlight = false
            if (success) {
                lastUploadedLocation = Location(location)
                lastLocationUploadAt = System.currentTimeMillis()
            }
        }
    }

    private fun updateTrackingModeFromLocation(location: Location) {
        val previous = lastObservedLocation?.let { Location(it) }
        lastObservedLocation = Location(location)

        val candidateMode = determineTrackingMode(location, previous)
        val currentMode = currentTrackingMode
        if (candidateMode == currentMode) {
            return
        }

        val now = System.currentTimeMillis()
        val shouldPromote = trackingRank(candidateMode) > trackingRank(currentMode)
        val canDowngrade = now - lastTrackingModeChangeAt >= TRACKING_MODE_STICKINESS_MS
        if (!shouldPromote && !canDowngrade) {
            return
        }

        currentTrackingMode = candidateMode
        lastTrackingModeChangeAt = now
        Log.d(TAG, "Tracking mode changed: $currentMode -> $candidateMode")
        restartLocationUpdatesForCurrentMode()
    }

    private fun determineTrackingMode(location: Location, previous: Location?): TrackingMode {
        if (isStreamingAudio || AudioStreamingService.isStreamingDesired(this)) {
            return TrackingMode.CRITICAL
        }

        val measuredSpeed = location.speed.takeIf { it > 0f }
        if (measuredSpeed != null) {
            return when {
                measuredSpeed >= FAST_TRANSIT_SPEED_THRESHOLD_MPS -> TrackingMode.CRITICAL
                measuredSpeed >= MOVING_SPEED_THRESHOLD_MPS -> TrackingMode.TRANSIT
                else -> TrackingMode.BALANCED
            }
        }

        if (previous != null) {
            val elapsed = (location.time - previous.time).takeIf { it > 0L }
                ?: (System.currentTimeMillis() - lastTrackingModeChangeAt).coerceAtLeast(1L)
            val distance = location.distanceTo(previous)
            if (distance >= MOVEMENT_DISTANCE_THRESHOLD_METERS && elapsed <= MOVEMENT_TIME_WINDOW_MS) {
                val inferredSpeed = distance / (elapsed / 1000f)
                return when {
                    inferredSpeed >= FAST_TRANSIT_SPEED_THRESHOLD_MPS -> TrackingMode.CRITICAL
                    inferredSpeed >= MOVING_SPEED_THRESHOLD_MPS -> TrackingMode.TRANSIT
                    else -> TrackingMode.BALANCED
                }
            }
        }

        return TrackingMode.BALANCED
    }

    private fun effectiveTrackingMode(): TrackingMode {
        return if (isStreamingAudio || AudioStreamingService.isStreamingDesired(this)) {
            TrackingMode.CRITICAL
        } else {
            currentTrackingMode
        }
    }

    private fun trackingRank(mode: TrackingMode): Int = when (mode) {
        TrackingMode.BALANCED -> 0
        TrackingMode.TRANSIT -> 1
        TrackingMode.CRITICAL -> 2
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Emergency stop - stops ALL functions immediately
     * Used when user wants to ensure everything is stopped (audio streaming, location tracking, etc.)
     */
    private fun emergencyStopAll() {
        Log.w(TAG, "EMERGENCY STOP - Stopping all functions")

        // Stop audio streaming immediately
        if (isStreamingAudio) {
            stopAudioStreaming()
            Log.d(TAG, "Audio streaming stopped")
        }

        // Stop location tracking
        stopTracking()
        Log.d(TAG, "Location tracking stopped")

        // Cancel all coroutines
        commandCheckJob?.cancel()
        Log.d(TAG, "Command checking stopped")

        // Show notification
        Toast.makeText(this, getString(R.string.location_service_emergency_stop_done), Toast.LENGTH_LONG).show()

        Log.w(TAG, "EMERGENCY STOP COMPLETED")
    }

    override fun onDestroy() {
        isServiceAlive = false
        ru.example.parentwatch.network.WebSocketManager.removePhotoRequestListener(photoRequestListener)
        commandCheckJob?.cancel()
        if (isStreamingAudio) {
            stopAudioStreaming()
        }
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }
}
