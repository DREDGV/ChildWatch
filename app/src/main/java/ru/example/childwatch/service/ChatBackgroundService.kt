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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private var chatManagerAdapter: ru.example.childwatch.chat.ChatManagerAdapter? = null
    private var serviceChatListener: ((String, String, String, Long) -> Unit)? = null

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
        Log.d(TAG, "Starting chat background service (isRunning=$isRunning)")

        // Start foreground with notification
        if (!isRunning) {
            startForeground(NOTIFICATION_ID, createNotification("Подключение..."))
        }

        // Always cleanup and reinitialize WebSocket to ensure fresh connection
        if (WebSocketManager.isConnected()) {
            Log.d(TAG, "Disconnecting existing WebSocket before reconnecting")
            WebSocketManager.disconnect()
        }

        // Cleanup and reinitialize WebSocket
        WebSocketManager.cleanup()

        // Prepare modern chat adapter (Room-based) bound to current child
        chatManagerAdapter = ru.example.childwatch.chat.ChatManagerAdapter(this, childDeviceId)

        // Use coroutine for delayed connection with retry logic
        lifecycleScope.launch {
            var attempt = 0
            val maxAttempts = 3
            var connected = false

            while (attempt < maxAttempts && !connected) {
                attempt++
                Log.d(TAG, "WebSocket connection attempt $attempt/$maxAttempts")

                // Add delay before connection (longer for first attempt)
                val delayMs = if (attempt == 1) 2000L else 1000L
                delay(delayMs)

                try {
                    WebSocketManager.initialize(
                        this@ChatBackgroundService,
                        serverUrl,
                        childDeviceId,
                        onMissedMessages = { missed ->
                            // Persist missed messages to both storages to keep badge/UI in sync
                            missed.forEach { msg ->
                                try {
                                    // Save to Room (new)
                                    chatManagerAdapter?.saveMessage(msg)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error saving missed message to DB", e)
                                }
                                try {
                                    // Save to legacy storage for badge compatibility
                                    chatManager.saveMessage(msg)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error saving missed message to legacy store", e)
                                }
                            }
                            // Optionally, show notifications for missed messages
                            missed.forEach { msg ->
                                val senderName = if (msg.sender == "child") "Ребенок" else "Родитель"
                                ru.example.childwatch.utils.NotificationManager.showChatNotification(
                                    context = this@ChatBackgroundService,
                                    senderName = senderName,
                                    messageText = msg.text,
                                    timestamp = msg.timestamp
                                )
                            }
                        }
                    )

                    // Set up message listener (do not override others)
                    if (serviceChatListener == null) {
                        serviceChatListener = { messageId, text, sender, timestamp ->
                            handleIncomingMessage(messageId, text, sender, timestamp)
                        }
                        WebSocketManager.addChatMessageListener(serviceChatListener!!)
                    }

                    // Connect WebSocket
                    WebSocketManager.connect(
                        onConnected = {
                            Log.d(TAG, "✅ WebSocket connected successfully on attempt $attempt")
                            updateNotification("Чат активен")
                            connected = true
                        },
                        onError = { error ->
                            Log.e(TAG, "❌ WebSocket connection error on attempt $attempt: $error")
                            if (attempt >= maxAttempts) {
                                updateNotification("Ошибка подключения")
                            }
                        }
                    )

                    // Wait a bit to see if connection succeeds
                    delay(1500)

                    if (WebSocketManager.isConnected()) {
                        connected = true
                        Log.d(TAG, "✅ WebSocket connection verified on attempt $attempt")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Exception during connection attempt $attempt", e)
                    if (attempt >= maxAttempts) {
                        updateNotification("Ошибка подключения")
                    }
                }
            }

            if (!connected) {
                Log.e(TAG, "❌ Failed to connect WebSocket after $maxAttempts attempts")
                updateNotification("Не удалось подключиться")
            }
        }

        isRunning = true
    }

    private fun stopForegroundService() {
        Log.d(TAG, "Stopping chat background service")

        // Remove service-scoped listener only
        serviceChatListener?.let { WebSocketManager.removeChatMessageListener(it) }
        serviceChatListener = null

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
        // Persist to Room (new storage)
        try {
            chatManagerAdapter?.saveMessage(message)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving message to DB", e)
        }

        // Persist to legacy storage (for badge compatibility in MainActivity)
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
        serviceChatListener?.let { WebSocketManager.removeChatMessageListener(it) }
        serviceChatListener = null
        isRunning = false
    }
}
