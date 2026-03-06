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
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R
import ru.example.parentwatch.audio.AudioStreamRecorder
import ru.example.parentwatch.network.NetworkHelper
import ru.example.parentwatch.utils.RemoteLogger

/**
 * Foreground service that captures microphone audio and streams it over WebSocket.
 */
class AudioStreamingService : Service() {

    companion object {
        private const val TAG = "AUDIO"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "audio_streaming_channel"

        const val ACTION_START_STREAMING = "ru.example.parentwatch.START_STREAMING"
        const val ACTION_STOP_STREAMING = "ru.example.parentwatch.STOP_STREAMING"

        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_RECORDING_MODE = "recording_mode"
        const val EXTRA_SAMPLE_RATE = "sample_rate"

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
    }

    private var audioStreamRecorder: AudioStreamRecorder? = null
    private var networkHelper: NetworkHelper? = null
    private var isStreaming = false
    private var currentDeviceId: String? = null
    private var currentServerUrl: String? = null
    private var currentRecordingMode: Boolean = false
    private var currentSampleRate: Int = 24_000
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
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
        }

        return START_STICKY
    }

    private fun normalizeSampleRate(rate: Int): Int {
        return 24_000
    }

    private fun startStreaming(deviceId: String, serverUrl: String, recordingMode: Boolean, sampleRate: Int) {
        val normalizedSampleRate = normalizeSampleRate(sampleRate)
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
                Log.d(TAG, "Already streaming with same config")
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
}
