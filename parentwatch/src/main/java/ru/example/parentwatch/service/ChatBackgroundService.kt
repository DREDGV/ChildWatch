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
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R
import ru.example.parentwatch.chat.ChatManager
import ru.example.parentwatch.chat.ChatMessage
import ru.example.parentwatch.network.WebSocketManager

/**
 * Background Foreground Service for receiving chat messages
 * Keeps WebSocket connection alive to receive messages even when app is in background
 */
class ChatBackgroundService : LifecycleService() {

    companion object {
        private const val TAG = "ChatBackgroundService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "chat_background_service"

        const val ACTION_START_SERVICE = "ru.example.parentwatch.START_CHAT_SERVICE"
        const val ACTION_STOP_SERVICE = "ru.example.parentwatch.STOP_CHAT_SERVICE"

        var isRunning = false
            private set

        fun start(context: Context, serverUrl: String, deviceId: String) {
            val intent = Intent(context, ChatBackgroundService::class.java).apply {
                action = ACTION_START_SERVICE
                putExtra("server_url", serverUrl)
                putExtra("device_id", deviceId)
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
    private val backgroundListener: (String, String, String, Long) -> Unit = { messageId, text, sender, timestamp ->
        handleIncomingMessage(messageId, text, sender, timestamp)
    }


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
                val deviceId = intent.getStringExtra("device_id")

                if (serverUrl.isNullOrEmpty() || deviceId.isNullOrEmpty()) {
                    Log.e(TAG, "Missing required parameters")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundService(serverUrl, deviceId)
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

    private fun startForegroundService(serverUrl: String, deviceId: String) {
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
                    WebSocketManager.initialize(this@ChatBackgroundService, serverUrl, deviceId)
                    WebSocketManager.addChatMessageListener(backgroundListener)

                    // Регистрируем callback запроса фото ДО connect
                    WebSocketManager.setPhotoRequestCallback { requestId, targetDevice ->
                        Log.d(TAG, "📸 Photo request received (req=$requestId, target=$targetDevice)")
                        // Запускаем сервис захвата фото если не запущен
                        ensurePhotoCaptureService(serverUrl, deviceId)
                        // Сервис сам обработает через PhotoCaptureService.setPhotoRequestCallback
                    }
                    
                    // Setup parent location listener to save to DB
                    WebSocketManager.setParentLocationCallback { parentId, lat, lon, accuracy, timestamp, speed, bearing ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val database = ru.example.parentwatch.database.ParentWatchDatabase.getInstance(this@ChatBackgroundService)
                                val location = ru.example.parentwatch.database.entity.ParentLocation(
                                    parentId = parentId,
                                    latitude = lat,
                                    longitude = lon,
                                    accuracy = accuracy,
                                    timestamp = timestamp,
                                    provider = "websocket",
                                    speed = speed,
                                    bearing = bearing
                                )
                                database.parentLocationDao().insertLocation(location)
                                Log.d(TAG, "✅ Parent location saved to DB: $lat, $lon")
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Error saving parent location to DB", e)
                            }
                        }
                    }

                    // Connect WebSocket
                    WebSocketManager.connect(
                        onConnected = {
                            Log.d(TAG, "✅ WebSocket connected successfully on attempt $attempt")
                            updateNotification("Чат активен")
                            // Heartbeat для устойчивости
                            try { WebSocketManager.getClient()?.startHeartbeat() } catch (_: Exception) {}
                            // Автостарт сервиса фото (если разрешено)
                            ensurePhotoCaptureService(serverUrl, deviceId)
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

        // Clear callback
        WebSocketManager.removeChatMessageListener(backgroundListener)

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
        val senderName = if (sender == "parent") "Родители" else "Ребенок"
        ru.example.parentwatch.utils.NotificationManager.showChatNotification(
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
            .setContentTitle("ParentWatch - Чат")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
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

    private fun ensurePhotoCaptureService(serverUrl: String, deviceId: String) {
        try {
            val prefs = getSharedPreferences("parentwatch_prefs", Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("allow_remote_photo", true)
            if (!enabled) {
                Log.d(TAG, "Remote photo disabled (allow_remote_photo=false)")
                return
            }
            // Простой признак: запущена ли нотификация сервиса (можно отслеживать глобальный static в PhotoCaptureService при расширении)
            PhotoCaptureService.start(this, serverUrl, deviceId)
            Log.d(TAG, "PhotoCaptureService ensure start invoked")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PhotoCaptureService", e)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ChatBackgroundService destroyed")
        WebSocketManager.removeChatMessageListener(backgroundListener)
        isRunning = false
    }
}
