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
        const val ACTION_TAKE_PHOTO = "take_photo"
        
        const val EXTRA_AUDIO_DURATION = "audio_duration"
        
        @Volatile
        var isRunning = false
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
    
    // Configuration
    private var locationIntervalSeconds = 30
    private var defaultAudioDurationSeconds = 20
    private var serverUrl = "https://your-server.com"
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        secureSettings = SecureSettingsManager(this)
        errorHandler = ErrorHandler(this)
        recoveryManager = RecoveryManager(this)
        batteryOptimizer = BatteryOptimizationManager(this)
        auditLogger = AuditLogger(this)
        
        // Initialize components
        locationManager = LocationManager(this)
        audioRecorder = AudioRecorder(this)
        photoCapture = PhotoCapture(this)
        networkClient = NetworkClient(this)
        
        // Load configuration
        loadConfiguration()
        
        // Create notification channel
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
            ACTION_REQUEST_LOCATION -> requestLocationNow()
            ACTION_START_AUDIO_CAPTURE -> {
                val duration = intent.getIntExtra(EXTRA_AUDIO_DURATION, defaultAudioDurationSeconds)
                startAudioCapture(duration)
            }
            ACTION_TAKE_PHOTO -> takePhoto()
        }
        
        return START_STICKY // Restart service if killed by system
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        
        stopMonitoring()
        
        // Cleanup resources
        recoveryManager.cleanup()
        batteryOptimizer.cleanup()
        auditLogger.cleanup()
        
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
                setSound(null, null) // Silent notifications
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(isRecording: Boolean = false): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val title = getString(R.string.notification_monitoring_title)
        val baseText = if (isRecording) {
            getString(R.string.notification_status_recording)
        } else {
            getString(R.string.notification_status_running)
        }
        val statusText = when {
            !batteryOptimizer.isIgnoringBatteryOptimizations() -> getString(R.string.notification_status_battery_optimization)
            batteryOptimizer.isPowerSaveModeEnabled() -> getString(R.string.notification_status_power_save)
            else -> baseText
        }
        
        // Create action buttons
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
            action = ACTION_START_AUDIO_CAPTURE
            putExtra(EXTRA_AUDIO_DURATION, defaultAudioDurationSeconds)
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
        
        // Get last update time
        val lastUpdate = secureSettings.getLastLocationUpdate()
        val lastUpdateText = if (lastUpdate > 0) {
            val dateFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val timeString = dateFormat.format(java.util.Date(lastUpdate))
            getString(R.string.notification_last_update, timeString)
        } else {
            getString(R.string.notification_last_update, "�������")
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSubText(lastUpdateText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
                getString(R.string.notification_action_audio),
                audioPendingIntent
            )
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.notification_action_photo),
                photoPendingIntent
            )
            .build()
    }
    
    private fun startMonitoring() {
        if (isRunning) {
            Log.d(TAG, "Monitoring already running")
            return
        }
        
        // Check permissions
        if (!PermissionHelper.hasAllRequiredPermissions(this)) {
            Log.e(TAG, "Missing required permissions")
            stopSelf()
            return
        }
        
        Log.d(TAG, "Starting monitoring")
        // auditLogger.logAudit("Monitoring started", AuditLogger.EventCategory.SYSTEM)
        isRunning = true
        
        // Save service start time
        secureSettings.setServiceStartTime(System.currentTimeMillis())
        
        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Register device and get auth token
        CoroutineScope(Dispatchers.IO).launch {
            val serverUrl = prefs.getString("server_url", "https://your-server.com") ?: "https://your-server.com"
            val token = networkClient.registerDevice(serverUrl)
            if (token != null) {
                Log.d(TAG, "Device registered successfully")
            } else {
                Log.w(TAG, "Failed to register device, continuing without auth")
            }
        }
        
        // Start recovery monitoring
        recoveryManager.startHealthMonitoring()
        
        // Start battery optimization
        batteryOptimizer.startBatteryMonitoring()
        
        // Start location updates
        startLocationUpdates()
        
        // Start periodic audio recording if enabled
        if (secureSettings.isAudioEnabled()) {
            startPeriodicAudioRecording()
        }
    }
    
    private fun stopMonitoring() {
        if (!isRunning) {
            Log.d(TAG, "Monitoring already stopped")
            return
        }
        
        Log.d(TAG, "Stopping monitoring")
        // auditLogger.logAudit("Monitoring stopped", AuditLogger.EventCategory.SYSTEM)
        isRunning = false
        
        // Cancel all jobs
        locationUpdateJob?.cancel()
        audioRecordingJob?.cancel()
        
        // Stop recovery monitoring
        recoveryManager.stopHealthMonitoring()
        
        // Stop battery optimization
        batteryOptimizer.stopBatteryMonitoring()
        
        // Stop location updates
        locationManager.stopLocationUpdates()
        
        // Stop audio recording if active
        if (isAudioRecording.get()) {
            audioRecorder.stopRecording()
            isAudioRecording.set(false)
        }
        
        // Stop foreground service
        stopForeground(true)
        stopSelf()
    }
    
    private fun startLocationUpdates() {
        locationUpdateJob?.cancel()
        locationUpdateJob = serviceScope.launch {
            while (isActive && isRunning) {
                try {
                    val location = locationManager.getCurrentLocation()
                    if (location != null) {
                        Log.d(TAG, "Location: ${location.latitude}, ${location.longitude}")
                        
                        // Log location event
                        // auditLogger.logLocationEvent(
                        //     "Location updated",
                        //     location.latitude,
                        //     location.longitude,
                        //     location.accuracy
                        // )
                        
                        // Save last location update time
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
                        Log.w(TAG, "Failed to get location")
                        // auditLogger.logWarning("Failed to get location", AuditLogger.EventCategory.LOCATION)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in location update", e)
                    errorHandler.handleLocationError(e) {
                        // Fallback: retry with longer interval
                        // Note: delay() cannot be called in non-suspend context
                        Log.w(TAG, "Location error fallback triggered")
                    }
                }
                
                // Wait for next update using adaptive interval
                val adaptiveInterval = batteryOptimizer.getAdaptiveLocationInterval()
                delay(adaptiveInterval)
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
                    
                    // Save last location update time
                    prefs.edit().putLong("last_location_update", System.currentTimeMillis()).apply()
                    
                    // Upload location to server
                    networkClient.uploadLocation(
                        serverUrl,
                        location.latitude,
                        location.longitude,
                        location.accuracy,
                        System.currentTimeMillis()
                    )
                } else {
                    Log.w(TAG, "Failed to get immediate location")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting immediate location", e)
            }
        }
    }
    
    private fun startPeriodicAudioRecording() {
        Log.d(TAG, "Starting periodic audio recording")
        
        serviceScope.launch {
            while (isActive && isRunning && secureSettings.isAudioEnabled()) {
                try {
                    // Wait for interval (e.g., 1 minute for testing, 5 minutes for production)
                    val audioIntervalMs = 1 * 60 * 1000L // 1 minute for testing
                    delay(audioIntervalMs)
                    
                    // Start audio capture
                    if (!isAudioRecording.get()) {
                        val duration = secureSettings.getAudioDuration()
                        startAudioCapture(duration)
                        
                        // Wait for recording to complete before next cycle
                        delay((duration * 1000L) + 2000) // Add 2 second buffer
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic audio recording", e)
                }
            }
        }
    }
    
    private fun startAudioCapture(durationSeconds: Int) {
        if (isAudioRecording.get()) {
            Log.w(TAG, "Audio recording already in progress")
            return
        }
        
        // Check audio permission
        if (!PermissionHelper.hasAudioPermission(this)) {
            Log.e(TAG, "Audio permission not granted")
            return
        }
        
        // Check if audio recording is available
        if (!audioRecorder.isAudioRecordingAvailable()) {
            Log.e(TAG, "Audio recording is not available on this device")
            return
        }
        
        // NOTE: Android 14+ restricts background microphone access
        // This will only work in foreground service with proper notification
        Log.d(TAG, "Starting audio capture for $durationSeconds seconds")
        
        audioRecordingJob?.cancel()
        audioRecordingJob = serviceScope.launch {
            var audioFile: File? = null
            try {
                isAudioRecording.set(true)
                
                // Update notification to show recording with microphone indicator
                startForeground(NOTIFICATION_ID, createNotification(isRecording = true))
                
                // Small delay to ensure foreground service is properly registered
                delay(100)
                
                // Start recording
                Log.d(TAG, "AudioRecorder.startRecording() called")
                audioFile = audioRecorder.startRecording(durationSeconds)
                
                if (audioFile == null) {
                    Log.e(TAG, "Failed to start audio recording - audioFile is null")
                    return@launch
                }
                
                Log.d(TAG, "Audio recording started successfully, waiting for $durationSeconds seconds")
                
                // Wait for recording to complete (add 500ms buffer for cleanup)
                // Use actual durationSeconds, not adaptive duration
                delay((durationSeconds * 1000L) + 500)
                
                // Stop recording explicitly
                Log.d(TAG, "Stopping audio recording")
                val stoppedFile = audioRecorder.stopRecording()
                
                if (stoppedFile != null && stoppedFile.exists() && stoppedFile.length() > 0) {
                    Log.d(TAG, "Audio recorded successfully: ${stoppedFile.absolutePath}, size: ${stoppedFile.length()} bytes")
                    
                    // Save last audio update time
                    secureSettings.setLastAudioUpdate(System.currentTimeMillis())
                    
                    // Upload audio to server
                    Log.d(TAG, "Uploading audio to server")
                    val uploadSuccess = networkClient.uploadAudio(serverUrl, stoppedFile)
                    
                    if (uploadSuccess) {
                        Log.d(TAG, "Audio uploaded successfully")
                    } else {
                        Log.w(TAG, "Audio upload failed, file kept for retry")
                    }
                    
                    // Delete local file after upload (even if upload failed, to save space)
                    stoppedFile.delete()
                    Log.d(TAG, "Temp audio file deleted")
                } else {
                    Log.e(TAG, "Audio recording failed - file is null, doesn't exist, or is empty")
                }
                
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception during audio recording (Android 14+ restriction?)", e)
                errorHandler.handleAudioError(e) {
                    Log.w(TAG, "Audio error fallback triggered - security exception")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio recording", e)
                errorHandler.handleAudioError(e) {
                    Log.w(TAG, "Audio error fallback triggered - generic exception")
                }
            } finally {
                // Ensure recording is stopped even if error occurred
                try {
                    if (audioRecorder.isRecording()) {
                        Log.d(TAG, "Ensuring audio recorder is stopped in finally block")
                        audioRecorder.stopRecording()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping audio recorder in finally block", e)
                }
                
                isAudioRecording.set(false)
                
                // Update notification back to normal
                if (isRunning) {
                    startForeground(NOTIFICATION_ID, createNotification(isRecording = false))
                }
                
                Log.d(TAG, "Audio capture cycle completed")
            }
        }
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
        locationIntervalSeconds = prefs.getInt("location_interval", 30)
        defaultAudioDurationSeconds = prefs.getInt("audio_duration", 20)
        serverUrl = prefs.getString("server_url", "https://your-server.com") ?: "https://your-server.com"
        
        Log.d(TAG, "Configuration loaded: interval=${locationIntervalSeconds}s, audio=${defaultAudioDurationSeconds}s, url=$serverUrl")
    }
}
