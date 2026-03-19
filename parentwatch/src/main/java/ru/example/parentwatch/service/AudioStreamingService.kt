package ru.example.parentwatch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R
import ru.example.parentwatch.audio.AudioStreamRecorder
import ru.example.parentwatch.network.NetworkHelper
import ru.example.parentwatch.utils.DeviceInfoCollector
import ru.example.parentwatch.utils.RemoteLogger

/**
 * Foreground service that captures microphone audio and streams it over WebSocket.
 */
class AudioStreamingService : Service() {

    companion object {
        private const val TAG = "AUDIO"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "audio_streaming_channel"
        private const val PREFS_NAME = "parentwatch_prefs"
        private const val PREF_AUDIO_DESIRED = "audio_stream_desired"
        private const val PREF_AUDIO_DEVICE_ID = "audio_stream_device_id"
        private const val PREF_AUDIO_SERVER_URL = "audio_stream_server_url"
        private const val PREF_AUDIO_RECORDING = "audio_stream_recording"
        private const val PREF_AUDIO_SAMPLE_RATE = "audio_stream_sample_rate"

        const val ACTION_START_STREAMING = "ru.example.parentwatch.START_STREAMING"
        const val ACTION_STOP_STREAMING = "ru.example.parentwatch.STOP_STREAMING"
        const val ACTION_PAUSE_CAPTURE = "ru.example.parentwatch.PAUSE_STREAM_CAPTURE"
        const val ACTION_RESUME_CAPTURE = "ru.example.parentwatch.RESUME_STREAM_CAPTURE"

        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_RECORDING_MODE = "recording_mode"
        const val EXTRA_SAMPLE_RATE = "sample_rate"

        @Volatile
        var isServiceAlive = false
            private set

        fun startStreaming(
            context: Context,
            deviceId: String,
            serverUrl: String,
            recordingMode: Boolean = false,
            sampleRate: Int = 24_000
        ) {
            val intent = Intent(context, AudioStreamingService::class.java).apply {
                action = ACTION_START_STREAMING
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_RECORDING_MODE, recordingMode)
                putExtra(EXTRA_SAMPLE_RATE, sampleRate)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopStreaming(context: Context) {
            val intent = Intent(context, AudioStreamingService::class.java).apply {
                action = ACTION_STOP_STREAMING
            }
            context.startService(intent)
        }

        fun pauseCaptureForPhoto(context: Context) {
            val intent = Intent(context, AudioStreamingService::class.java).apply {
                action = ACTION_PAUSE_CAPTURE
            }
            context.startService(intent)
        }

        fun resumeIfDesired(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_AUDIO_DESIRED, false)) return false

            val deviceId = prefs.getString(PREF_AUDIO_DEVICE_ID, null)?.trim().orEmpty()
            val serverUrl = prefs.getString(PREF_AUDIO_SERVER_URL, null)?.trim().orEmpty()
            if (deviceId.isBlank() || serverUrl.isBlank()) return false

            val recording = prefs.getBoolean(PREF_AUDIO_RECORDING, false)
            val sampleRate = prefs.getInt(PREF_AUDIO_SAMPLE_RATE, 24_000)
            val action = if (isServiceAlive) ACTION_RESUME_CAPTURE else ACTION_START_STREAMING
            val intent = Intent(context, AudioStreamingService::class.java).apply {
                this.action = action
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_RECORDING_MODE, recording)
                putExtra(EXTRA_SAMPLE_RATE, sampleRate)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        fun isStreamingDesired(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_AUDIO_DESIRED, false)
        }
    }

    private var audioStreamRecorder: AudioStreamRecorder? = null
    private var networkHelper: NetworkHelper? = null
    private var isStreaming = false
    private var currentDeviceId: String? = null
    private var currentServerUrl: String? = null
    private var currentRecordingMode: Boolean = false
    private var currentSampleRate: Int = 24_000
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var deviceStatusSyncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        isServiceAlive = true
        Log.d(TAG, "AudioStreamingService created")
        RemoteLogger.info(
            serverUrl = currentServerUrl,
            deviceId = currentDeviceId,
            source = TAG,
            message = "AudioStreamingService created"
        )

        networkHelper = NetworkHelper(this)
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ParentWatch::AudioStreamingWakeLock"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == null) {
            if (resumeIfDesired(this)) {
                Log.d(TAG, "AudioStreamingService restored after sticky restart")
                return START_STICKY
            }
            Log.w(TAG, "AudioStreamingService restart skipped: no desired session")
            stopSelf()
            return START_NOT_STICKY
        }

        RemoteLogger.info(
            serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL),
            deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID),
            source = TAG,
            message = "AudioStreamingService onStartCommand",
            meta = mapOf(
                "action" to (intent?.action ?: "null"),
                "startId" to startId
            )
        )
        when (intent?.action) {
            ACTION_START_STREAMING -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return START_NOT_STICKY
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: return START_NOT_STICKY
                val recordingMode = intent.getBooleanExtra(EXTRA_RECORDING_MODE, false)
                val sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 24_000)
                startStreaming(deviceId, serverUrl, recordingMode, sampleRate)
            }

            ACTION_STOP_STREAMING -> stopStreaming()
            ACTION_PAUSE_CAPTURE -> pauseCapture()
            ACTION_RESUME_CAPTURE -> resumeCapture()
        }

        return START_STICKY
    }

    private fun normalizeSampleRate(rate: Int): Int {
        return 24_000
    }

    private fun startStreaming(deviceId: String, serverUrl: String, recordingMode: Boolean, sampleRate: Int) {
        val normalizedSampleRate = normalizeSampleRate(sampleRate)
        persistDesiredSession(deviceId, serverUrl, recordingMode, normalizedSampleRate)
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "AudioStreamingService startStreaming invoked",
            meta = mapOf(
                "recordingMode" to recordingMode,
                "requestedSampleRate" to sampleRate,
                "normalizedSampleRate" to normalizedSampleRate,
                "alreadyStreaming" to isStreaming
            )
        )

        if (isStreaming) {
            val sameConfig =
                currentDeviceId == deviceId &&
                    currentServerUrl == serverUrl &&
                    currentRecordingMode == recordingMode &&
                    currentSampleRate == normalizedSampleRate

            if (sameConfig) {
                val recorder = audioStreamRecorder
                if (recorder == null) {
                    Log.w(TAG, "Streaming flag set but recorder missing - recreating stream")
                    networkHelper?.let { helper ->
                        audioStreamRecorder = AudioStreamRecorder(this, helper).also {
                            it.startStreaming(deviceId, serverUrl, recordingMode, normalizedSampleRate)
                        }
                    }
                } else if (recorder.isActive()) {
                    recorder.updateStreamConfig(recordingMode, normalizedSampleRate)
                    recorder.ensureCaptureRunning()
                    startDeviceStatusSyncLoop(serverUrl)
                    Log.d(TAG, "Already streaming with same config, capture resume requested")
                } else {
                    Log.w(TAG, "Streaming flag set but recorder inactive - rebootstrap requested")
                    recorder.startStreaming(deviceId, serverUrl, recordingMode, normalizedSampleRate)
                    startDeviceStatusSyncLoop(serverUrl)
                }
                updateNotification("Audio stream active (${normalizedSampleRate / 1000} kHz)")
                return
            }

            Log.i(
                TAG,
                "Reconfiguring stream: rate ${currentSampleRate} -> $normalizedSampleRate, recording $currentRecordingMode -> $recordingMode"
            )
            RemoteLogger.info(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "AudioStreamingService reconfiguring stream",
                meta = mapOf(
                    "oldRate" to currentSampleRate,
                    "newRate" to normalizedSampleRate,
                    "oldRecordingMode" to currentRecordingMode,
                    "newRecordingMode" to recordingMode
                )
            )
            audioStreamRecorder?.updateStreamConfig(recordingMode, normalizedSampleRate)
            currentRecordingMode = recordingMode
            currentSampleRate = normalizedSampleRate
            startDeviceStatusSyncLoop(serverUrl)
            updateNotification("Audio stream active (${normalizedSampleRate / 1000} kHz)")
            return
        }

        startForeground(NOTIFICATION_ID, createNotification("Audio stream active"))

        try {
            wakeLock?.acquire(2 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
            RemoteLogger.error(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "Failed to acquire audio WakeLock",
                throwable = e
            )
        }

        networkHelper?.let { helper ->
            audioStreamRecorder = AudioStreamRecorder(this, helper)
            audioStreamRecorder?.startStreaming(deviceId, serverUrl, recordingMode, normalizedSampleRate)
            isStreaming = true
            currentDeviceId = deviceId
            currentServerUrl = serverUrl
            currentRecordingMode = recordingMode
            currentSampleRate = normalizedSampleRate
            startDeviceStatusSyncLoop(serverUrl)
            updateNotification("Audio stream active (${normalizedSampleRate / 1000} kHz)")
            RemoteLogger.info(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "AudioStreamingService delegated start to AudioStreamRecorder",
                meta = mapOf(
                    "sampleRate" to normalizedSampleRate,
                    "recordingMode" to recordingMode
                )
            )
        }
    }

    private fun stopStreaming() {
        clearDesiredSession()
        stopDeviceStatusSyncLoop()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }

        audioStreamRecorder?.stopStreaming()
        audioStreamRecorder = null
        isStreaming = false
        currentDeviceId = null
        currentServerUrl = null
        currentRecordingMode = false
        currentSampleRate = 24_000

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun pauseCapture() {
        audioStreamRecorder?.pauseCapture()
        updateNotification("Audio stream paused for photo")
    }

    private fun resumeCapture() {
        audioStreamRecorder?.ensureCaptureRunning()
        currentServerUrl?.let { startDeviceStatusSyncLoop(it) }
        updateNotification("Audio stream active (${currentSampleRate / 1000} kHz)")
    }

    private fun startDeviceStatusSyncLoop(serverUrl: String) {
        deviceStatusSyncJob?.cancel()
        deviceStatusSyncJob = serviceScope.launch {
            uploadDeviceStatusSnapshot(serverUrl)
            while (isActive && isStreaming) {
                delay(15_000L)
                uploadDeviceStatusSnapshot(serverUrl)
            }
        }
    }

    private fun stopDeviceStatusSyncLoop() {
        deviceStatusSyncJob?.cancel()
        deviceStatusSyncJob = null
    }

    private suspend fun uploadDeviceStatusSnapshot(serverUrl: String) {
        val helper = networkHelper ?: return
        runCatching {
            helper.uploadDeviceStatus(serverUrl, DeviceInfoCollector.getDeviceInfo(this))
        }.onFailure { error ->
            Log.w(TAG, "Device status sync failed during listening", error)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for outgoing audio stream"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ParentWatch")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isServiceAlive = false
        stopDeviceStatusSyncLoop()
        serviceScope.cancel()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock in onDestroy", e)
        }

        audioStreamRecorder?.stopStreaming()
        audioStreamRecorder = null
        isStreaming = false
        currentDeviceId = null
        currentServerUrl = null
        currentRecordingMode = false
        currentSampleRate = 24_000
    }

    private fun persistDesiredSession(
        deviceId: String,
        serverUrl: String,
        recordingMode: Boolean,
        sampleRate: Int
    ) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_AUDIO_DESIRED, true)
            .putString(PREF_AUDIO_DEVICE_ID, deviceId)
            .putString(PREF_AUDIO_SERVER_URL, serverUrl)
            .putBoolean(PREF_AUDIO_RECORDING, recordingMode)
            .putInt(PREF_AUDIO_SAMPLE_RATE, sampleRate)
            .apply()
    }

    private fun clearDesiredSession() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_AUDIO_DESIRED, false)
            .remove(PREF_AUDIO_DEVICE_ID)
            .remove(PREF_AUDIO_SERVER_URL)
            .remove(PREF_AUDIO_RECORDING)
            .remove(PREF_AUDIO_SAMPLE_RATE)
            .apply()
    }
}
