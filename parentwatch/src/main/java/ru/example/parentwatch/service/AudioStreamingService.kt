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
import android.util.Log
import androidx.core.app.NotificationCompat
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R
import ru.example.parentwatch.audio.AudioStreamRecorder
import ru.example.parentwatch.network.NetworkHelper

/**
 * Foreground Service for background audio streaming
 * Keeps audio recording active when app is minimized
 */
class AudioStreamingService : Service() {

    companion object {
        private const val TAG = "AUDIO" // Этап A: unified tag
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "audio_streaming_channel"

        const val ACTION_START_STREAMING = "ru.example.parentwatch.START_STREAMING"
        const val ACTION_STOP_STREAMING = "ru.example.parentwatch.STOP_STREAMING"

        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_RECORDING_MODE = "recording_mode"

        fun startStreaming(context: Context, deviceId: String, serverUrl: String, recordingMode: Boolean = false) {
            val intent = Intent(context, AudioStreamingService::class.java).apply {
                action = ACTION_START_STREAMING
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_RECORDING_MODE, recordingMode)
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

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AudioStreamingService created")

        networkHelper = NetworkHelper(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STREAMING -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return START_NOT_STICKY
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: return START_NOT_STICKY
                val recordingMode = intent.getBooleanExtra(EXTRA_RECORDING_MODE, false)

                startStreaming(deviceId, serverUrl, recordingMode)
            }
            ACTION_STOP_STREAMING -> {
                stopStreaming()
            }
        }

        return START_STICKY
    }

    private fun startStreaming(deviceId: String, serverUrl: String, recordingMode: Boolean) {
        if (isStreaming) {
            Log.w(TAG, "Already streaming")
            return
        }

        Log.d(TAG, "Starting audio streaming in foreground service")

        // Этап A: MUST call startForeground BEFORE initializing AudioRecord
        startForeground(NOTIFICATION_ID, createNotification("Отправка аудио активна"))
        Log.d(TAG, "AUDIO startForeground ok") // Этап A: Required log

        // Initialize audio recorder AFTER startForeground
        networkHelper?.let { helper ->
            audioStreamRecorder = AudioStreamRecorder(this, helper)
            audioStreamRecorder?.startStreaming(deviceId, serverUrl, recordingMode)
            isStreaming = true

            // Update notification
            updateNotification("Отправка аудио: активна")
        }
    }

    private fun stopStreaming() {
        Log.d(TAG, "🛑 Stopping audio streaming")

        audioStreamRecorder?.stopStreaming()
        audioStreamRecorder = null
        isStreaming = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Аудио стриминг",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления об отправке аудио"
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
        Log.d(TAG, "AudioStreamingService destroyed")

        audioStreamRecorder?.stopStreaming()
        audioStreamRecorder = null
        isStreaming = false
    }
}
