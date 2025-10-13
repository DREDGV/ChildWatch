package ru.example.childwatch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import ru.example.childwatch.MainActivity
import ru.example.childwatch.R
import ru.example.childwatch.chat.ChatManager
import ru.example.childwatch.chat.ChatMessage
import ru.example.childwatch.network.WebSocketManager

/**
 * Background Foreground Service for receiving chat messages
 * Keeps WebSocket connection alive to receive messages even when app is in background
 */
class ChatBackgroundService : LifecycleService() {

    companion object {
        private const val TAG = "ChatBackgroundService"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "chat_background_service"

        const val ACTION_START_SERVICE = "ru.example.childwatch.START_CHAT_SERVICE"
        const val ACTION_STOP_SERVICE = "ru.example.childwatch.STOP_CHAT_SERVICE"

        var isRunning = false
            private set

        fun start(context: Context, serverUrl: String, childDeviceId: String) {
            val intent = Intent(context, ChatBackgroundService::class.java).apply {
                action = ACTION_START_SERVICE
                putExtra("server_url", serverUrl)
                putExtra("child_device_id", childDeviceId)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ChatBackgroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }

    private lateinit var chatManager: ChatManager

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ChatBackgroundService created")

        chatManager = ChatManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START_SERVICE -> {
                val serverUrl = intent.getStringExtra("server_url")
                val childDeviceId = intent.getStringExtra("child_device_id")

                if (serverUrl.isNullOrEmpty() || childDeviceId.isNullOrEmpty()) {
                    Log.e(TAG, "Missing required parameters")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundService(serverUrl, childDeviceId)
            }
            ACTION_STOP_SERVICE -> {
                stopForegroundService()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    private fun startForegroundService(serverUrl: String, childDeviceId: String) {
        if (isRunning) {
            Log.w(TAG, "Service already running")
            return
        }

        Log.d(TAG, "Starting chat background service")

        // Start foreground with notification
        startForeground(NOTIFICATION_ID, createNotification("Подключение..."))

        // Initialize WebSocket if not connected
        if (!WebSocketManager.isConnected()) {
            WebSocketManager.initialize(this, serverUrl, childDeviceId)
        }

        // Set up message callback
        WebSocketManager.setChatMessageCallback { messageId, text, sender, timestamp ->
            handleIncomingMessage(messageId, text, sender, timestamp)
        }

        // Connect WebSocket
        WebSocketManager.connect(
            onConnected = {
                Log.d(TAG, "WebSocket connected")
                updateNotification("Чат активен")
            },
            onError = { error ->
                Log.e(TAG, "WebSocket error: $error")
                updateNotification("Ошибка подключения")
            }
        )

        isRunning = true
        updateNotification("Чат активен")
    }

    private fun stopForegroundService() {
        Log.d(TAG, "Stopping chat background service")

        // Clear callback
        WebSocketManager.clearChatMessageCallback()

        // Stop foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        isRunning = false
        stopSelf()
    }

    private fun handleIncomingMessage(messageId: String, text: String, sender: String, timestamp: Long) {
        Log.d(TAG, "Received message: $text from $sender")

        // Save message to local storage
        val message = ChatMessage(
            id = messageId,
            text = text,
            sender = sender,
            timestamp = timestamp,
            isRead = false // Mark as unread
        )
        chatManager.saveMessage(message)

        // Show notification
        val senderName = if (sender == "child") "Ребенок" else "Родитель"
        ru.example.childwatch.utils.NotificationManager.showChatNotification(
            context = this,
            senderName = senderName,
            messageText = text,
            timestamp = timestamp
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фоновый чат",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Держит соединение для получения сообщений"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_chat", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ChildWatch - Чат")
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

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ChatBackgroundService destroyed")
        WebSocketManager.clearChatMessageCallback()
        isRunning = false
    }
}
