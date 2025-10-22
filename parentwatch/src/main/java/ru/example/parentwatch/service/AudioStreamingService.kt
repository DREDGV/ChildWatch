package ru.example.parentwatch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R
import ru.example.parentwatch.audio.AudioStreamRecorder
import ru.example.parentwatch.audio.FilterMode
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

    // Task 6: WakeLock to prevent CPU sleep during streaming
    private var wakeLock: PowerManager.WakeLock? = null

    // Task 3: BroadcastReceiver for filter mode updates from ChildWatch
    private val filterModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ru.example.childwatch.UPDATE_FILTER_MODE") {
                val modeStr = intent.getStringExtra("filter_mode")
                Log.d(TAG, "FX broadcast received: filter_mode=$modeStr")

                modeStr?.let {
                    try {
                        val mode = FilterMode.valueOf(it)
                        audioStreamRecorder?.setFilterMode(mode)
                        Log.d(TAG, "FX filter mode updated to: $mode")
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "FX invalid filter mode: $modeStr", e)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AudioStreamingService created")

        networkHelper = NetworkHelper(this)
        createNotificationChannel()

        // Task 6: Initialize WakeLock to prevent CPU sleep
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ParentWatch::AudioStreamingWakeLock"
        )
        Log.d(TAG, "🔋 WakeLock initialized")

        // Task 3: Register broadcast receiver for filter mode updates
        val filter = IntentFilter("ru.example.childwatch.UPDATE_FILTER_MODE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(filterModeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(filterModeReceiver, filter)
        }
        Log.d(TAG, "FX filter mode broadcast receiver registered")
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

        // Task 6: Acquire WakeLock to prevent CPU sleep (2 hours timeout)
        try {
            wakeLock?.acquire(2 * 60 * 60 * 1000L /* 2 hours */)
            Log.d(TAG, "🔋 WakeLock acquired (2 hours)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }

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

        // Task 6: Release WakeLock
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "✅ WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }

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

        // Task 6: Release WakeLock
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "✅ WakeLock released in onDestroy")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock in onDestroy", e)
        }

        // Task 3: Unregister broadcast receiver
        try {
            unregisterReceiver(filterModeReceiver)
            Log.d(TAG, "FX filter mode broadcast receiver unregistered")
        } catch (e: IllegalArgumentException) {
            // Receiver not registered, ignore
            Log.w(TAG, "FX receiver was not registered", e)
        }

        audioStreamRecorder?.stopStreaming()
        audioStreamRecorder = null
        isStreaming = false
    }
}
