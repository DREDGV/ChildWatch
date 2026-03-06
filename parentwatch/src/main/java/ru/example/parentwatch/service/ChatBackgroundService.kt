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
import org.json.JSONObject
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R
import ru.example.parentwatch.chat.ChatManager
import ru.example.parentwatch.chat.ChatMessage
import ru.example.parentwatch.network.WebSocketManager
import ru.example.parentwatch.utils.RemoteLogger
import ru.example.parentwatch.utils.ServerUrlResolver
import java.util.Locale

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
        @Volatile private var lastServerUrl: String? = null
        @Volatile private var lastDeviceId: String? = null

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

    private lateinit var chatManagerAdapter: ru.example.parentwatch.chat.ChatManagerAdapter
    private val backgroundListener: (String, String, String, Long) -> Unit = { messageId, text, sender, timestamp ->
        handleIncomingMessage(messageId, text, sender, timestamp)
    }
    private var backgroundListenerRegistered = false
    @Volatile private var lastStartStreamAtMs: Long = 0L
    @Volatile private var lastStartStreamRate: Int = 24_000
    @Volatile private var lastStartStreamRecording: Boolean = false
    private val commandListener: (String, JSONObject?) -> Unit = { command, data ->
        when (command) {
            "start_audio_stream" -> {
                val url = lastServerUrl
                val id = lastDeviceId
                if (!url.isNullOrBlank() && !id.isNullOrBlank()) {
                    val recording = data?.optBoolean("recording", false) ?: false
                    val sampleRate = data?.optInt("sampleRate", 24_000) ?: 24_000
                    RemoteLogger.info(
                        serverUrl = url,
                        deviceId = id,
                        source = TAG,
                        message = "start_audio_stream received in ChatBackgroundService",
                        meta = mapOf(
                            "recording" to recording,
                            "sampleRate" to sampleRate
                        )
                    )
                    val now = System.currentTimeMillis()
                    val duplicateStart =
                        (now - lastStartStreamAtMs) < 1500L &&
                            sampleRate == lastStartStreamRate &&
                            recording == lastStartStreamRecording
                    if (duplicateStart) {
                        Log.d(TAG, "Ignoring duplicate start_audio_stream within cooldown window")
                        RemoteLogger.warn(
                            serverUrl = url,
                            deviceId = id,
                            source = TAG,
                            message = "Duplicate start_audio_stream ignored",
                            meta = mapOf(
                                "recording" to recording,
                                "sampleRate" to sampleRate
                            )
                        )
                    } else {
                        lastStartStreamAtMs = now
                        lastStartStreamRate = sampleRate
                        lastStartStreamRecording = recording
                        Log.d(
                            TAG,
                            "Received start_audio_stream (recording=$recording, sampleRate=$sampleRate), starting AudioStreamingService"
                        )
                        runCatching {
                            AudioStreamingService.startStreaming(this, id, url, recording, sampleRate)
                        }.onFailure { error ->
                            Log.e(TAG, "Failed to start AudioStreamingService", error)
                            RemoteLogger.error(
                                serverUrl = url,
                                deviceId = id,
                                source = TAG,
                                message = "Failed to start AudioStreamingService",
                                throwable = error
                            )
                        }
                    }
                } else {
                    Log.w(TAG, "Ignoring start_audio_stream: missing server/device context")
                }
            }
            "stop_audio_stream" -> {
                Log.d(TAG, "Received stop_audio_stream, stopping AudioStreamingService")
                AudioStreamingService.stopStreaming(this)
            }
        }
    }


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ChatBackgroundService created")

        // Initialize ChatManagerAdapter - will be fully initialized in onStartCommand with deviceId
        createNotificationChannel()
        ru.example.parentwatch.utils.NotificationManager.createNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        // Service may be relaunched by the system with null intent; recover configuration from prefs
        if (intent == null) {
            val prefs = getSharedPreferences("parentwatch_prefs", Context.MODE_PRIVATE)
            val serverUrl = ServerUrlResolver.getServerUrl(this)
            val deviceId = listOf(
                prefs.getString("device_id", null),
                prefs.getString("child_device_id", null)
            )
                .mapNotNull { it?.trim() }
                .firstOrNull { it.isNotBlank() }

            if (!serverUrl.isNullOrBlank() && !deviceId.isNullOrBlank()) {
                if (!::chatManagerAdapter.isInitialized) {
                    chatManagerAdapter = ru.example.parentwatch.chat.ChatManagerAdapter(this, deviceId)
                    Log.d(TAG, "ChatManagerAdapter recovered with deviceId: $deviceId")
                }
                Log.d(TAG, "Restarting ChatBackgroundService after kill (from prefs)")
                startForegroundService(serverUrl, deviceId)
                return START_STICKY
            }

            Log.w(TAG, "Cannot restart ChatBackgroundService after kill: missing prefs (serverUrl=$serverUrl, deviceId=$deviceId)")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START_SERVICE -> {
                val serverUrl = intent.getStringExtra("server_url")
                val deviceId = intent.getStringExtra("device_id")

                if (serverUrl.isNullOrEmpty() || deviceId.isNullOrEmpty()) {
                    Log.e(TAG, "Missing required parameters")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Initialize ChatManagerAdapter with current deviceId
                if (!::chatManagerAdapter.isInitialized || lastDeviceId != deviceId) {
                    chatManagerAdapter = ru.example.parentwatch.chat.ChatManagerAdapter(this, deviceId)
                    Log.d(TAG, "ChatManagerAdapter initialized with deviceId: $deviceId")
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

        if (isRunning &&
            serverUrl == lastServerUrl &&
            deviceId == lastDeviceId &&
            WebSocketManager.getClient() != null
        ) {
            WebSocketManager.ensureConnected(
                onReady = { updateNotification("Чат активен") },
                onError = { updateNotification("Ошибка подключения") }
            )
            return
        }
        lastServerUrl = serverUrl
        lastDeviceId = deviceId

        // Always cleanup and reinitialize WebSocket to ensure fresh connection
        if (WebSocketManager.isConnected()) {
            Log.d(TAG, "Disconnecting existing WebSocket before reconnecting")
            WebSocketManager.disconnect()
        }

        // Cleanup and reinitialize WebSocket
        WebSocketManager.cleanup()
        backgroundListenerRegistered = false

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
                        deviceId,
                        onMissedMessages = { missed ->
                            missed.forEach { msg ->
                                if (::chatManagerAdapter.isInitialized) {
                                    chatManagerAdapter.saveMessage(msg)
                                }
                                if (msg.sender == "parent" && !msg.isRead && msg.id.isNotEmpty()) {
                                    chatManagerAdapter.updateMessageStatus(msg.id, ChatMessage.MessageStatus.DELIVERED)
                                    WebSocketManager.sendChatStatus(msg.id, "delivered", "child")
                                }
                                if (shouldShowIncomingChatNotification(msg.sender)) {
                                    val senderName = if (msg.sender == "parent") "Parent" else "Child"
                                    ru.example.parentwatch.utils.NotificationManager.showChatNotification(
                                        context = this@ChatBackgroundService,
                                        senderName = senderName,
                                        messageText = msg.text,
                                        timestamp = msg.timestamp
                                    )
                                }
                            }
                        }
                    )
                    WebSocketManager.setChatStatusCallback { messageId, status, _ ->
                        handleStatusUpdate(messageId, status)
                    }
                    if (!backgroundListenerRegistered) {
                        WebSocketManager.addChatMessageListener(backgroundListener)
                        WebSocketManager.addCommandListener(commandListener)
                        backgroundListenerRegistered = true
                    }

                    // Регистрируем callback запроса фото ДО connect
                    WebSocketManager.setPhotoRequestCallback { requestId, targetDevice, cameraFacing ->
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
        WebSocketManager.removeCommandListener(commandListener)

        // Stop foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        isRunning = false
        lastServerUrl = null
        lastDeviceId = null
        backgroundListenerRegistered = false
        stopSelf()
    }

    private fun handleIncomingMessage(messageId: String, text: String, sender: String, timestamp: Long) {
        Log.d(TAG, "Received message: $text from $sender")

        // Save message to Room Database
        val message = ChatMessage(
            id = messageId,
            text = text,
            sender = sender,
            timestamp = timestamp,
            isRead = false // Mark as unread
        )
        if (::chatManagerAdapter.isInitialized) {
            chatManagerAdapter.saveMessage(message)
            chatManagerAdapter.updateMessageStatus(messageId, ChatMessage.MessageStatus.DELIVERED)
        } else {
            Log.e(TAG, "ChatManagerAdapter not initialized, message not saved")
        }

        if (sender == "parent" && messageId.isNotEmpty()) {
            WebSocketManager.sendChatStatus(messageId, "delivered", "child")
        }

        if (shouldShowIncomingChatNotification(sender)) {
            val senderName = if (sender == "parent") "Parent" else "Child"
            ru.example.parentwatch.utils.NotificationManager.showChatNotification(
                context = this,
                senderName = senderName,
                messageText = text,
                timestamp = timestamp
            )
        }
    }

    private fun handleStatusUpdate(messageId: String, status: String) {
        if (messageId.isEmpty()) return
        val mapped = when (status.lowercase(Locale.ROOT)) {
            "delivered" -> ChatMessage.MessageStatus.DELIVERED
            "read" -> ChatMessage.MessageStatus.READ
            "sent" -> ChatMessage.MessageStatus.SENT
            else -> null
        } ?: return

        if (::chatManagerAdapter.isInitialized) {
            chatManagerAdapter.updateMessageStatus(messageId, mapped)
        }
    }

    private fun shouldShowIncomingChatNotification(sender: String): Boolean {
        if (sender != "parent") return false
        val prefs = getSharedPreferences("parentwatch_prefs", Context.MODE_PRIVATE)
        return !prefs.getBoolean("chat_open", false)
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
        WebSocketManager.removeCommandListener(commandListener)
        isRunning = false
    }
}
