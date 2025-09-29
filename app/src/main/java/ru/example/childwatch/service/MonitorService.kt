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
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.utils.PermissionHelper
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
    private lateinit var networkClient: NetworkClient
    private lateinit var prefs: SharedPreferences
    
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
        
        // Initialize components
        locationManager = LocationManager(this)
        audioRecorder = AudioRecorder(this)
        networkClient = NetworkClient()
        
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
        val text = if (isRecording) {
            getString(R.string.notification_recording_audio)
        } else {
            getString(R.string.notification_monitoring_text)
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification) // TODO: Add proper icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
        isRunning = true
        
        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Start location updates
        startLocationUpdates()
    }
    
    private fun stopMonitoring() {
        if (!isRunning) {
            Log.d(TAG, "Monitoring already stopped")
            return
        }
        
        Log.d(TAG, "Stopping monitoring")
        isRunning = false
        
        // Cancel all jobs
        locationUpdateJob?.cancel()
        audioRecordingJob?.cancel()
        
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
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in location update", e)
                }
                
                // Wait for next update
                delay(locationIntervalSeconds * 1000L)
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
        
        // NOTE: Android 14+ restricts background microphone access
        // This will only work in foreground service with proper notification
        Log.d(TAG, "Starting audio capture for $durationSeconds seconds")
        
        audioRecordingJob?.cancel()
        audioRecordingJob = serviceScope.launch {
            try {
                isAudioRecording.set(true)
                
                // Update notification to show recording
                startForeground(NOTIFICATION_ID, createNotification(isRecording = true))
                
                // Start recording
                val audioFile = audioRecorder.startRecording(durationSeconds)
                
                // Wait for recording to complete
                delay(durationSeconds * 1000L)
                
                // Stop recording
                audioRecorder.stopRecording()
                
                if (audioFile != null && audioFile.exists()) {
                    Log.d(TAG, "Audio recorded: ${audioFile.absolutePath}")
                    
                    // Upload audio to server
                    networkClient.uploadAudio(serverUrl, audioFile)
                    
                    // Delete local file after upload
                    audioFile.delete()
                } else {
                    Log.e(TAG, "Audio recording failed")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio recording", e)
            } finally {
                isAudioRecording.set(false)
                
                // Update notification back to normal
                if (isRunning) {
                    startForeground(NOTIFICATION_ID, createNotification(isRecording = false))
                }
            }
        }
    }
    
    private fun takePhoto() {
        // TODO: Implement photo capture
        Log.d(TAG, "Photo capture requested (not implemented yet)")
    }
    
    private fun loadConfiguration() {
        locationIntervalSeconds = prefs.getInt("location_interval", 30)
        defaultAudioDurationSeconds = prefs.getInt("audio_duration", 20)
        serverUrl = prefs.getString("server_url", "https://your-server.com") ?: "https://your-server.com"
        
        Log.d(TAG, "Configuration loaded: interval=${locationIntervalSeconds}s, audio=${defaultAudioDurationSeconds}s, url=$serverUrl")
    }
}
