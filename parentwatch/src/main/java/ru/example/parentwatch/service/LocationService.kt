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
        private const val LOCATION_UPDATE_INTERVAL = 30000L // 30 seconds
        private const val LOCATION_FASTEST_INTERVAL = 15000L // 15 seconds
        private const val COMMAND_CHECK_INTERVAL = 5000L // 5 seconds - quick response for audio streaming

        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_EMERGENCY_STOP = "emergency_stop"
        const val ACTION_START_AUDIO_STREAM = "start_audio_stream"
        const val ACTION_STOP_AUDIO_STREAM = "stop_audio_stream"
        const val EXTRA_AUDIO_RECORDING = "audio_recording"
        const val EXTRA_AUDIO_SAMPLE_RATE = "audio_sample_rate"

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
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var networkHelper: NetworkHelper
    private lateinit var audioRecorder: AudioStreamRecorder
    private lateinit var prefs: SharedPreferences
    private lateinit var appUsageTracker: AppUsageTracker

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isTracking = false
    private var isStreamingAudio = false
    private var deviceId: String? = null
    private var serverUrl: String? = null
    private var commandCheckJob: Job? = null
    private var registrationJob: Job? = null
    private var locationUpdatesStarted = false

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("ParentWatch", "LocationService onCreate called")
        Log.d(TAG, "Service created")

        prefs = getSharedPreferences("parentwatch_prefs", MODE_PRIVATE)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        networkHelper = NetworkHelper(this)
        audioRecorder = AudioStreamRecorder(this, networkHelper)
        appUsageTracker = AppUsageTracker(this)

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
                if (!isTracking) {
                    Log.w(TAG, "Ignoring audio start: monitoring service is not active")
                    return START_STICKY
                }
                val recording = intent.getBooleanExtra(EXTRA_AUDIO_RECORDING, false)
                val sampleRate = intent.getIntExtra(EXTRA_AUDIO_SAMPLE_RATE, 24_000)
                startAudioStreaming(recording = recording, sampleRate = sampleRate)
            }
            ACTION_STOP_AUDIO_STREAM -> {
                if (isStreamingAudio) {
                    stopAudioStreaming()
                }
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

        // Load settings (prefer intent extras to avoid async prefs race)
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
            deviceId = prefs.getString("device_id", null)
        }

        if (intentServerUrl != null) {
            serverUrl = intentServerUrl
            prefs.edit().putString("server_url", intentServerUrl).commit()
        } else {
            serverUrl = ServerUrlResolver.getServerUrl(this)
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
        ChatBackgroundService.start(this, serverUrl!!, deviceId!!)
        PhotoCaptureService.start(this, serverUrl!!, deviceId!!)
        // Register device with retries (network may be unavailable right after boot)
        startRegistrationLoop()
        prefs.edit().putBoolean("service_running", true).apply()

        // Audio listening starts only from an explicit start_audio_stream command.
        // Auto-start here races with the dedicated WebSocket audio path and can grab the mic too early.
    }

    private fun startRegistrationLoop() {
        registrationJob?.cancel()
        registrationJob = serviceScope.launch {
            var attempt = 0
            while (isActive && isTracking && !locationUpdatesStarted) {
                try {
                    val ok = networkHelper.registerDevice(serverUrl!!, deviceId!!)
                    if (ok) {
                        Log.d(TAG, "Device registered")
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

        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopForeground(true)
        stopSelf()

        isTracking = false
        locationUpdatesStarted = false
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

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL
        )
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL)
            .build()

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

        // Upload to server with device info
        serviceScope.launch {
            // Collect device info
            val deviceInfo = DeviceInfoCollector.getDeviceInfo(this@LocationService)

            val success = networkHelper.uploadLocationWithDeviceInfo(
                serverUrl!!,
                location.latitude,
                location.longitude,
                location.accuracy,
                deviceInfo
            )

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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
                delay(COMMAND_CHECK_INTERVAL)
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
                            "Polled start_audio_stream received while WS owner is active - using service backstop"
                        )
                        AudioStreamingService.startStreaming(
                            this,
                            deviceId,
                            serverUrl,
                            recording,
                            sampleRate
                        )
                    } else {
                        startAudioStreaming(recording = recording, sampleRate = sampleRate)
                    }
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

        audioRecorder.startStreaming(deviceId, serverUrl, recording, sampleRate)
        isStreamingAudio = true
        updateNotification(getString(R.string.location_service_notification_active_with_battery, DeviceInfoCollector.getBatteryStatus(this)))

        Log.d(TAG, "Audio streaming started (silent mode)")
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "Audio streaming started from LocationService",
            meta = mapOf("recording" to recording)
        )
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
        updateNotification(getString(R.string.location_service_notification_active))

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

        if (!ChatBackgroundService.isRunning) {
            Log.w(TAG, "ChatBackgroundService is down, restarting from LocationService")
            ChatBackgroundService.start(this, serverUrl, deviceId)
        }

        if (AudioStreamingService.isStreamingDesired(this)) {
            AudioStreamingService.resumeIfDesired(this)
        }

        val allowRemotePhoto = prefs.getBoolean("allow_remote_photo", true)
        if (allowRemotePhoto) {
            PhotoCaptureService.start(this, serverUrl, deviceId)
        }
    }

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
        super.onDestroy()
        commandCheckJob?.cancel()
        if (isStreamingAudio) {
            stopAudioStreaming()
        }
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }
}
