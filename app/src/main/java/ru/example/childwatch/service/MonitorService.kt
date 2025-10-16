package ru.example.childwatch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import ru.example.childwatch.MainActivity
import ru.example.childwatch.R
import ru.example.childwatch.location.LocationManager
import ru.example.childwatch.audio.AudioRecorder
import ru.example.childwatch.photo.PhotoCapture
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.utils.PermissionHelper
import ru.example.childwatch.utils.SecureSettingsManager
import ru.example.childwatch.utils.ErrorHandler
import ru.example.childwatch.utils.RecoveryManager
import ru.example.childwatch.utils.BatteryOptimizationManager
import ru.example.childwatch.utils.AuditLogger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service for continuous monitoring
 * 
 * Features:
 * - Runs as foreground service with persistent notification
 * - Periodic location updates
 * - Audio recording on command
 * - Network upload of data
 * - Handles Android 14+ restrictions for background microphone access
 */
class MonitorService : Service() {
    
    companion object {
        private const val TAG = "MonitorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "childwatch_monitoring"
        
        const val ACTION_START_MONITORING = "start_monitoring"
        const val ACTION_STOP_MONITORING = "stop_monitoring"
        const val ACTION_REQUEST_LOCATION = "request_location"
        const val ACTION_START_AUDIO_CAPTURE = "start_audio_capture"
        const val ACTION_STOP_AUDIO_CAPTURE = "stop_audio_capture"
        const val ACTION_TAKE_PHOTO = "take_photo"
        
        const val EXTRA_AUDIO_DURATION = "audio_duration"
        
        @Volatile
        var isRunning = false
            private set
        
        @Volatile
        var isRecording = false
            private set
        
        @Volatile
        var lastError: String? = null
            private set
        
        @Volatile
        var lastCommand: String? = null
            private set
        
        @Volatile
        var lastStartedAt: Long = 0L
            private set
    }
    
    private lateinit var notificationManager: NotificationManager
    private lateinit var locationManager: LocationManager
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var photoCapture: PhotoCapture
    private lateinit var networkClient: NetworkClient
    private lateinit var prefs: SharedPreferences
    private lateinit var secureSettings: SecureSettingsManager
    private lateinit var errorHandler: ErrorHandler
    private lateinit var recoveryManager: RecoveryManager
    private lateinit var batteryOptimizer: BatteryOptimizationManager
    private lateinit var auditLogger: AuditLogger
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var locationUpdateJob: Job? = null
    private var audioRecordingJob: Job? = null
    
    private val isAudioRecording = AtomicBoolean(false)
    private val isForegroundActive = AtomicBoolean(false)
    
    // Configuration
    private var locationIntervalSeconds = 30
    private var defaultAudioDurationSeconds = 20
    private var serverUrl = "https://your-server.com"
    
    private fun setRecordingState(active: Boolean) {
        isRecording = active
    }
    
    private fun setLastErrorMessage(message: String?) {
        lastError = message
    }
    
    private fun setLastCommandValue(command: String?) {
        lastCommand = command
    }
    
    private fun setLastStartedTimestamp(timestamp: Long) {
        lastStartedAt = timestamp
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        val appContext = applicationContext
        
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        prefs = appContext.getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        secureSettings = SecureSettingsManager(appContext)
        errorHandler = ErrorHandler(appContext)
        recoveryManager = RecoveryManager(appContext)
        batteryOptimizer = BatteryOptimizationManager(appContext)
        auditLogger = AuditLogger(appContext)
        
        // Initialize components
        locationManager = LocationManager(appContext)
        audioRecorder = AudioRecorder(appContext)
        photoCapture = PhotoCapture(appContext)
        networkClient = NetworkClient(appContext)
        
        // Load configuration
        loadConfiguration()
        
        // Create notification channel
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand: action=$action, startId=$startId, flags=$flags")
        
        if (action == null) {
            handleNullStartCommand()
            return START_STICKY
        }
        
        setLastCommandValue(action)
        
        when (action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
            ACTION_REQUEST_LOCATION -> requestLocationNow()
            ACTION_START_AUDIO_CAPTURE -> {
                val duration = intent.getIntExtra(EXTRA_AUDIO_DURATION, defaultAudioDurationSeconds)
                startAudioCapture(duration)
            }
            ACTION_STOP_AUDIO_CAPTURE -> stopAudioCapture("command")
            ACTION_TAKE_PHOTO -> takePhoto()
            else -> Log.w(TAG, "Unknown action received: $action")
        }
        
        return START_STICKY // Restart service if killed by system
    }
    
    private fun handleNullStartCommand() {
        Log.w(TAG, "onStartCommand invoked with null intent/action, recovering state")
        setLastCommandValue(null)
        
        if (!isRunning && secureSettings.isMonitoringEnabled()) {
            Log.i(TAG, "Monitoring was enabled previously, restarting service after sticky restart")
            startMonitoring()
        } else if (isRunning || isAudioRecording.get()) {
            Log.d(TAG, "Service already running, ensuring foreground notification is active")
            ensureForeground(isRecording = isAudioRecording.get())
        } else {
            Log.d(TAG, "Service not running and monitoring disabled, no action required")
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        
        stopMonitoring()
        
        // Cleanup resources
        try {
            recoveryManager.cleanup()
        } catch (e: Exception) {
            Log.w(TAG, "RecoveryManager cleanup failed", e)
        }
        
        try {
            batteryOptimizer.stopBatteryMonitoring()
        } catch (e: Exception) {
            Log.w(TAG, "BatteryOptimizer cleanup failed", e)
        }
        
        try {
            auditLogger.cleanup()
        } catch (e: Exception) {
            Log.w(TAG, "AuditLogger cleanup failed", e)
        }
        
        serviceScope.cancel()
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
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(
        isRecording: Boolean = false,
        statusOverride: String? = null
    ): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = getString(R.string.notification_monitoring_title)
        val baseStatus = if (isRecording) {
            getString(R.string.notification_status_recording)
        } else {
            getString(R.string.notification_status_running)
        }
        val statusText = statusOverride ?: when {
            !batteryOptimizer.isIgnoringBatteryOptimizations() -> getString(R.string.notification_status_battery_optimization)
            batteryOptimizer.isPowerSaveEnabled() -> getString(R.string.notification_status_power_save)
            else -> baseStatus
        }

        val stopIntent = Intent(this, MonitorService::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val locationIntent = Intent(this, MonitorService::class.java).apply {
            action = ACTION_REQUEST_LOCATION
        }
        val locationPendingIntent = PendingIntent.getService(
            this, 2, locationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val audioIntent = Intent(this, MonitorService::class.java).apply {
            action = if (isRecording) {
                ACTION_STOP_AUDIO_CAPTURE
            } else {
                ACTION_START_AUDIO_CAPTURE
            }

            if (action == ACTION_START_AUDIO_CAPTURE) {
                putExtra(EXTRA_AUDIO_DURATION, secureSettings.getAudioDuration())
            }
        }
        val audioPendingIntent = PendingIntent.getService(
            this, 3, audioIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val photoIntent = Intent(this, MonitorService::class.java).apply {
            action = ACTION_TAKE_PHOTO
        }
        val photoPendingIntent = PendingIntent.getService(
            this, 4, photoIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val lastUpdate = secureSettings.getLastLocationUpdate()
        val lastUpdateText = if (lastUpdate > 0) {
            val dateFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val timeString = dateFormat.format(java.util.Date(lastUpdate))
            getString(R.string.notification_last_update, timeString)
        } else {
            getString(R.string.notification_last_update, getString(R.string.notification_status_stopped))
        }

        val audioActionText = if (isRecording) {
            getString(R.string.notification_action_stop)
        } else {
            getString(R.string.notification_action_audio)
        }

        val notificationStyle = NotificationCompat.BigTextStyle().bigText(statusText)
        lastError?.takeIf { !it.isNullOrBlank() }?.let { notificationStyle.setSummaryText(it) }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSubText(lastUpdateText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(notificationStyle)
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.notification_action_location),
                locationPendingIntent
            )
            .addAction(
                R.drawable.ic_notification,
                audioActionText,
                audioPendingIntent
            )
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.notification_action_photo),
                photoPendingIntent
            )

        lastError?.takeIf { !it.isNullOrBlank() }?.let { builder.setTicker(it) }

        return builder.build()
    }

    private fun ensureForeground(isRecording: Boolean = isAudioRecording.get(), statusOverride: String? = null) {
        val notification = createNotification(isRecording, statusOverride)
        startForeground(NOTIFICATION_ID, notification)
        isForegroundActive.set(true)
    }

    private fun stopForegroundService(removeNotification: Boolean = true) {
        if (isForegroundActive.compareAndSet(true, false)) {
            stopForeground(removeNotification)
        }
    }

    private fun startMonitoring() {
        if (isRunning) {
            Log.d(TAG, "Monitoring already running")
            ensureForeground(isRecording = isAudioRecording.get())
            return
        }
        
        loadConfiguration()
        
        if (!PermissionHelper.hasAllRequiredPermissions(this)) {
            val message = "Missing required permissions for monitoring"
            Log.e(TAG, message)
            setLastErrorMessage(message)
            ensureForeground(isRecording = false, statusOverride = message)
            stopForegroundService(true)
            stopSelf()
            return
        }
        
        Log.i(
            TAG,
            "Starting monitoring (locationInterval=${locationIntervalSeconds}s, audioDuration=${defaultAudioDurationSeconds}s, server=$serverUrl)"
        )
        isRunning = true
        isAudioRecording.set(false)
        setRecordingState(false)
        val startedAt = System.currentTimeMillis()
        setLastStartedTimestamp(startedAt)
        setLastErrorMessage(null)
        
        secureSettings.setServiceStartTime(startedAt)
        secureSettings.setMonitoringEnabled(true)
        prefs.edit().putBoolean("was_monitoring", true).apply()
        
        ensureForeground(isRecording = false)

        // Start ChatBackgroundService for real-time chat
        startChatBackgroundService()

        serviceScope.launch(Dispatchers.IO) {
            try {
                val token = networkClient.registerDevice(serverUrl)
                if (token != null) {
                    Log.d(TAG, "Device registered successfully")
                } else {
                    Log.w(TAG, "Failed to register device, continuing without auth")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Device registration failed", e)
                setLastErrorMessage("Device registration failed: ${e.message}")
            }
        }

        recoveryManager.startHealthMonitoring()
        
        try {
            batteryOptimizer.startBatteryMonitoring()
        } catch (e: Exception) {
            Log.w(TAG, "BatteryOptimizer start failed", e)
        }
        
        startLocationUpdates()
        
        if (secureSettings.isAudioEnabled()) {
            startPeriodicAudioRecording()
        }
    }
    
    private fun startChatBackgroundService() {
        try {
            val childDeviceId = prefs.getString("child_device_id", "") ?: ""
            if (childDeviceId.isNotEmpty()) {
                ChatBackgroundService.start(this, serverUrl, childDeviceId)
                Log.d(TAG, "ChatBackgroundService started for child device: $childDeviceId")
            } else {
                Log.w(TAG, "Cannot start ChatBackgroundService: child_device_id not set")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ChatBackgroundService", e)
        }
    }

    private fun stopMonitoring() {
        if (!isRunning && !isAudioRecording.get()) {
            Log.d(TAG, "Monitoring already stopped")
            return
        }

        Log.i(TAG, "Stopping monitoring")
        isRunning = false
        secureSettings.setMonitoringEnabled(false)
        prefs.edit().putBoolean("was_monitoring", false).apply()
        setLastStartedTimestamp(0L)

        // Stop ChatBackgroundService
        try {
            ChatBackgroundService.stop(this)
            Log.d(TAG, "ChatBackgroundService stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop ChatBackgroundService", e)
        }

        locationUpdateJob?.cancel()
        locationUpdateJob = null

        recoveryManager.stopHealthMonitoring()

        try {
            batteryOptimizer.stopBatteryMonitoring()
        } catch (e: Exception) {
            Log.w(TAG, "BatteryOptimizer stop failed", e)
        }

        locationManager.stopLocationUpdates()

        if (isAudioRecording.get()) {
            stopAudioCapture("monitoring stop")
        }
        audioRecordingJob = null
        setRecordingState(false)

        stopForegroundService(true)
        stopSelf()
    }
    
    private fun startLocationUpdates() {
        locationUpdateJob?.cancel()
        val fallbackInterval = locationIntervalSeconds.coerceAtLeast(5) * 1000L
        locationUpdateJob = serviceScope.launch {
            while (isActive && isRunning) {
                val intervalMs = try {
                    batteryOptimizer.getAdaptiveLocationInterval().coerceAtLeast(fallbackInterval)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get adaptive location interval, using fallback", e)
                    fallbackInterval
                }
                
                try {
                    val location = locationManager.getCurrentLocation()
                    if (location != null) {
                        Log.d(TAG, "Location: ${location.latitude}, ${location.longitude} (accuracy=${location.accuracy})")
                        
                        secureSettings.setLastLocationUpdate(System.currentTimeMillis())
                        
                        networkClient.uploadLocation(
                            serverUrl,
                            location.latitude,
                            location.longitude,
                            location.accuracy,
                            System.currentTimeMillis()
                        )
                    } else {
                        val message = "Failed to get location"
                        Log.w(TAG, message)
                        setLastErrorMessage(message)
                    }
                } catch (e: Exception) {
                    val message = "Error in location update: ${e.message}"
                    Log.e(TAG, message, e)
                    setLastErrorMessage(message)
                    errorHandler.handleLocationError(e) {
                        Log.w(TAG, "Location error fallback triggered")
                    }
                }
                
                delay(intervalMs.coerceAtLeast(5_000L))
            }
        }
    }
    
    private fun requestLocationNow() {
        Log.d(TAG, "Location requested immediately")
        serviceScope.launch {
            try {
                val location = locationManager.getCurrentLocation()
                if (location != null) {
                    Log.d(TAG, "Immediate location: ${location.latitude}, ${location.longitude}")
                    
                    secureSettings.setLastLocationUpdate(System.currentTimeMillis())
                    
                    // Upload location to server
                    networkClient.uploadLocation(
                        serverUrl,
                        location.latitude,
                        location.longitude,
                        location.accuracy,
                        System.currentTimeMillis()
                    )
                } else {
                    val message = "Failed to get immediate location"
                    Log.w(TAG, message)
                    setLastErrorMessage(message)
                }
            } catch (e: Exception) {
                val message = "Error getting immediate location: ${e.message}"
                Log.e(TAG, message, e)
                setLastErrorMessage(message)
            }
        }
    }
    
    private fun startPeriodicAudioRecording() {
        Log.d(TAG, "Starting periodic audio recording")
        
        serviceScope.launch {
            while (isActive && isRunning && secureSettings.isAudioEnabled()) {
                try {
                    val duration = secureSettings.getAudioDuration().coerceAtLeast(5)
                    val intervalMs = (duration * 1000L).coerceAtLeast(60_000L)
                    delay(intervalMs)
                    
                    if (!isAudioRecording.get()) {
                        startAudioCapture(duration)
                        delay((duration * 1000L) + 2_000L)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = "Error in periodic audio recording: ${e.message}"
                    Log.e(TAG, message, e)
                    setLastErrorMessage(message)
                }
            }
        }
    }
    
    private fun startAudioCapture(durationSeconds: Int) {
        val safeDuration = durationSeconds.coerceAtLeast(5)
        
        if (!PermissionHelper.hasAudioPermission(this)) {
            val message = "Audio permission not granted"
            Log.e(TAG, message)
            setLastErrorMessage(message)
            return
        }
        
        if (!audioRecorder.isAudioRecordingAvailable()) {
            val message = "Audio recording is not available on this device"
            Log.e(TAG, message)
            setLastErrorMessage(message)
            return
        }
        
        if (!isAudioRecording.compareAndSet(false, true)) {
            Log.w(TAG, "Audio recording already in progress")
            return
        }
        
        Log.d(TAG, "Starting audio capture for $safeDuration seconds")
        setRecordingState(true)
        ensureForeground(isRecording = true)
        
        audioRecordingJob?.cancel()
        audioRecordingJob = serviceScope.launch {
            try {
                delay(100)
                val audioFile = audioRecorder.startRecording(safeDuration)
                if (audioFile == null) {
                    val message = "Failed to start audio recording"
                    Log.e(TAG, message)
                    setLastErrorMessage(message)
                    return@launch
                }
                
                Log.d(TAG, "Audio recording started successfully, waiting for $safeDuration seconds")
                val waitTime = (safeDuration * 1000L).coerceAtLeast(5_000L) + 500
                delay(waitTime)
                
                val stoppedFile = audioRecorder.stopRecording()
                
                if (stoppedFile != null && stoppedFile.exists() && stoppedFile.length() > 0) {
                    Log.d(TAG, "Audio recorded successfully: ${stoppedFile.absolutePath}, size: ${stoppedFile.length()} bytes")
                    secureSettings.setLastAudioUpdate(System.currentTimeMillis())
                    
                    val uploadSuccess = networkClient.uploadAudio(serverUrl, stoppedFile)
                    if (uploadSuccess) {
                        Log.d(TAG, "Audio uploaded successfully")
                    } else {
                        Log.w(TAG, "Audio upload failed, file kept for retry")
                    }
                    
                    stoppedFile.delete()
                    Log.d(TAG, "Temp audio file deleted")
                    setLastErrorMessage(null)
                } else {
                    val message = "Audio recording failed - empty file"
                    Log.e(TAG, message)
                    setLastErrorMessage(message)
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Audio recording cancelled: ${e.message}")
            } catch (e: SecurityException) {
                val message = "Security exception during audio recording: ${e.message}"
                Log.e(TAG, message, e)
                setLastErrorMessage(message)
                errorHandler.handleAudioError(e) {
                    Log.w(TAG, "Audio error fallback triggered - security exception")
                }
            } catch (e: Exception) {
                val message = "Error in audio recording: ${e.message}"
                Log.e(TAG, message, e)
                setLastErrorMessage(message)
                errorHandler.handleAudioError(e) {
                    Log.w(TAG, "Audio error fallback triggered - generic exception")
                }
            } finally {
                try {
                    if (audioRecorder.isRecording()) {
                        Log.d(TAG, "Ensuring audio recorder is stopped in finally block")
                        audioRecorder.stopRecording()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping audio recorder in finally block", e)
                }
                
                isAudioRecording.set(false)
                setRecordingState(false)
                audioRecordingJob = null
                
                ensureForeground(isRecording = false)
                
                Log.d(TAG, "Audio capture cycle completed")
            }
        }
    }
    
    private fun stopAudioCapture(reason: String? = null) {
        if (!isAudioRecording.get()) {
            Log.d(TAG, "Stop audio capture requested but no recording in progress")
            return
        }
        
        val reasonText = reason?.let { " ($it)" } ?: ""
        Log.i(TAG, "Stopping audio capture$reasonText")
        audioRecordingJob?.cancel(CancellationException("stopAudioCapture$reasonText"))
    }
    
    private fun takePhoto() {
        Log.d(TAG, "Photo capture requested")
        
        // Check camera permission
        if (!PermissionHelper.hasCameraPermission(this)) {
            Log.e(TAG, "Camera permission not granted")
            return
        }
        
        // Check if camera is available
        if (!photoCapture.isCameraAvailable()) {
            Log.e(TAG, "Camera not available on this device")
            return
        }
        
        // Start photo capture in background
        serviceScope.launch {
            try {
                Log.d(TAG, "Starting photo capture")
                
                // Take photo
                val photoFile = photoCapture.takePhoto()
                
                if (photoFile != null && photoFile.exists() && photoFile.length() > 0) {
                    Log.d(TAG, "Photo captured successfully: ${photoFile.absolutePath}, size: ${photoFile.length()} bytes")
                    
                    // Save last photo update time
                    secureSettings.setLastPhotoUpdate(System.currentTimeMillis())
                    
                    // Upload photo to server
                    Log.d(TAG, "Uploading photo to server")
                    val uploadSuccess = networkClient.uploadPhoto(serverUrl, photoFile)
                    
                    if (uploadSuccess) {
                        Log.d(TAG, "Photo uploaded successfully")
                    } else {
                        Log.w(TAG, "Photo upload failed, file kept for retry")
                    }
                    
                    // Delete local file after upload (even if upload failed, to save space)
                    photoFile.delete()
                    Log.d(TAG, "Temp photo file deleted")
                } else {
                    Log.e(TAG, "Photo capture failed - file is null, doesn't exist, or is empty")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in photo capture", e)
                // Simple error logging for now
            }
        }
    }
    
    private fun loadConfiguration() {
        locationIntervalSeconds = (secureSettings.getLocationInterval() / 1000L).toInt().coerceAtLeast(5)
        defaultAudioDurationSeconds = secureSettings.getAudioDuration().coerceAtLeast(5)
        serverUrl = secureSettings.getServerUrl().ifBlank { "https://childwatch-production.up.railway.app" }
        
        Log.d(
            TAG,
            "Configuration loaded: interval=${locationIntervalSeconds}s, audio=${defaultAudioDurationSeconds}s, url=$serverUrl"
        )
    }
}
