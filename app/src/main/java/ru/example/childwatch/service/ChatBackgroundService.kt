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
import ru.example.childwatch.utils.SecureSettingsManager
import java.util.Locale

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
        @Volatile private var lastServerUrl: String? = null
        @Volatile private var lastDeviceId: String? = null

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
    private var chatMessageSentListener: ((String, Boolean, Long) -> Unit)? = null
    private var photoReceivedListener: ((String, String, Long) -> Unit)? = null
    private var photoErrorListener: ((String, String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ChatBackgroundService created")

        chatManager = ChatManager(this)
        createNotificationChannel()
        ru.example.childwatch.utils.NotificationManager.createNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        // Service may be relaunched by the system with null intent; recover configuration from prefs
        if (intent == null) {
            val prefs = getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
            val serverUrl = SecureSettingsManager(this).getServerUrl().trim()
            val legacyPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val childDeviceId = listOf(
                prefs.getString("child_device_id", null),
                prefs.getString("selected_device_id", null),
                legacyPrefs.getString("child_device_id", null),
                legacyPrefs.getString("selected_device_id", null)
            )
                .mapNotNull { it?.trim() }
                .firstOrNull { it.isNotBlank() }
            if (serverUrl.isNotBlank() && !childDeviceId.isNullOrEmpty()) {
                Log.d(TAG, "Restarting ChatBackgroundService after kill (from prefs)")
                startForegroundService(serverUrl, childDeviceId)
                return START_STICKY
            }
            Log.w(TAG, "Cannot restart ChatBackgroundService after kill: missing prefs (serverUrl=$serverUrl, childDeviceId=$childDeviceId)")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START_SERVICE -> {
                val serverUrl = intent.getStringExtra("server_url")
                val childDeviceId = intent.getStringExtra("child_device_id")

                if (serverUrl.isNullOrEmpty() || childDeviceId.isNullOrEmpty()) {
                    Log.e(TAG, "Missing required parameters")
                    stopSelf()
                    return START_NOT_STICKY
                }

                SecureSettingsManager(this).setServerUrl(serverUrl)
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

        if (isRunning &&
            serverUrl == lastServerUrl &&
            childDeviceId == lastDeviceId &&
            WebSocketManager.getClient() != null
        ) {
            WebSocketManager.ensureConnected(
                onReady = { updateNotification("Чат активен") },
                onError = { updateNotification("Ошибка подключения") }
            )
            return
        }
        lastServerUrl = serverUrl
        lastDeviceId = childDeviceId

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

                                if (msg.sender == "child" && !msg.isRead && msg.id.isNotEmpty()) {
                                    chatManagerAdapter?.updateMessageStatus(msg.id, ChatMessage.MessageStatus.DELIVERED)
                                    chatManager.updateMessageStatus(msg.id, ChatMessage.MessageStatus.DELIVERED)
                                    WebSocketManager.sendChatStatus(msg.id, "delivered", "parent")
                                }
                            }

                            // Notifications only for incoming child messages when chat screen is closed.
                            missed.forEach { msg ->
                                if (!shouldShowIncomingChatNotification(msg.sender)) {
                                    return@forEach
                                }
                                val senderName = if (msg.sender == "child") "Child" else "Parent"
                                ru.example.childwatch.utils.NotificationManager.showChatNotification(
                                    context = this@ChatBackgroundService,
                                    senderName = senderName,
                                    messageText = msg.text,
                                    timestamp = msg.timestamp
                                )
                            }
                        }
                    )

                    WebSocketManager.setChatStatusCallback { messageId, status, _ ->
                        handleStatusUpdate(messageId, status)
                    }
                    if (chatMessageSentListener == null) {
                        chatMessageSentListener = { messageId, delivered, _ ->
                            handleMessageSentAck(messageId, delivered)
                        }
                        WebSocketManager.addChatMessageSentListener(chatMessageSentListener!!)
                    }

                    // Set up message listener (do not override others)
                    if (serviceChatListener == null) {
                        serviceChatListener = { messageId, text, sender, timestamp ->
                            handleIncomingMessage(messageId, text, sender, timestamp)
                        }
                        WebSocketManager.addChatMessageListener(serviceChatListener!!)
                    }

                    // Подписка на получение фото и ошибок фото (не перезаписываем другие слушатели)
                    if (photoReceivedListener == null) {
                        photoReceivedListener = { photoBase64, requestId, timestamp ->
                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    val uri = saveBase64PhotoToGallery(photoBase64, timestamp)
                                    Log.d(TAG, "✅ Photo saved: $uri")
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        ru.example.childwatch.utils.NotificationManager.showChatNotification(
                                            context = this@ChatBackgroundService,
                                            senderName = "Удалённая камера",
                                            messageText = "Получено фото (ID: $requestId)",
                                            timestamp = timestamp
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ Error saving received photo", e)
                                }
                            }
                        }
                        WebSocketManager.addPhotoReceivedListener(photoReceivedListener!!)
                    }

                    if (photoErrorListener == null) {
                        photoErrorListener = { requestId, error ->
                            Log.e(TAG, "❌ Photo error (request=$requestId): $error")
                            ru.example.childwatch.utils.NotificationManager.showChatNotification(
                                context = this@ChatBackgroundService,
                                senderName = "Ошибка фото",
                                messageText = "$error (req $requestId)",
                                timestamp = System.currentTimeMillis()
                            )
                        }
                        WebSocketManager.addPhotoErrorListener(photoErrorListener!!)
                    }

                    // Connect WebSocket
                    WebSocketManager.connect(
                        onConnected = {
                            Log.d(TAG, "✅ WebSocket connected successfully on attempt $attempt")
                            updateNotification("Чат активен")
                            // Запускаем отправку локации родителя (если разрешено)
                            maybeStartParentLocationService()
                            // Запускаем heartbeat для стабильности
                            try { WebSocketManager.getClient()?.startHeartbeat() } catch (_: Exception) {}
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
        chatMessageSentListener?.let { WebSocketManager.removeChatMessageSentListener(it) }
        chatMessageSentListener = null

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
        stopSelf()
    }

    private fun handleIncomingMessage(messageId: String, text: String, sender: String, timestamp: Long) {
        Log.d(TAG, "Received message: $text from $sender")

        // Проверяем активен ли UI чата - если да, то сервис не должен обрабатывать сообщения
        // Это предотвращает дублирование обработки между Activity и Service
        if (ru.example.childwatch.ChatActivity.isChatUiVisible) {
            Log.d(TAG, "Chat UI is visible, skipping message processing in service")
            return
        }

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

        chatManager.saveMessage(message)
        chatManagerAdapter?.updateMessageStatus(messageId, ChatMessage.MessageStatus.DELIVERED)
        chatManager.updateMessageStatus(messageId, ChatMessage.MessageStatus.DELIVERED)

        if (sender == "child" && messageId.isNotEmpty()) {
            WebSocketManager.sendChatStatus(messageId, "delivered", "parent")
        }

        if (shouldShowIncomingChatNotification(sender)) {
            // Show notification only for incoming child messages while chat screen is closed.
            val senderName = if (sender == "child") "Child" else "Parent"
            ru.example.childwatch.utils.NotificationManager.showChatNotification(
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

        chatManagerAdapter?.updateMessageStatus(messageId, mapped)
        try {
            chatManager.updateMessageStatus(messageId, mapped)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating legacy chat status", e)
        }
    }

    private fun handleMessageSentAck(messageId: String, delivered: Boolean) {
        if (messageId.isBlank()) return
        val status = if (delivered) {
            ChatMessage.MessageStatus.DELIVERED
        } else {
            ChatMessage.MessageStatus.SENT
        }
        if (!shouldApplyStatus(messageId, status)) return

        chatManagerAdapter?.updateMessageStatus(messageId, status)
        try {
            chatManager.updateMessageStatus(messageId, status)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating legacy sent ack status", e)
        }
    }

    private fun shouldApplyStatus(
        messageId: String,
        newStatus: ChatMessage.MessageStatus
    ): Boolean {
        val currentStatus = findCurrentStatus(messageId) ?: return true
        if (newStatus == currentStatus) return false
        if (newStatus == ChatMessage.MessageStatus.FAILED) {
            return currentStatus == ChatMessage.MessageStatus.SENDING ||
                currentStatus == ChatMessage.MessageStatus.SENT
        }
        val rank = mapOf(
            ChatMessage.MessageStatus.FAILED to -1,
            ChatMessage.MessageStatus.SENDING to 0,
            ChatMessage.MessageStatus.SENT to 1,
            ChatMessage.MessageStatus.DELIVERED to 2,
            ChatMessage.MessageStatus.READ to 3
        )
        return (rank[newStatus] ?: 0) >= (rank[currentStatus] ?: 0)
    }

    private fun findCurrentStatus(messageId: String): ChatMessage.MessageStatus? {
        return try {
            chatManagerAdapter?.getAllMessages()
                ?.firstOrNull { it.id == messageId }
                ?.status
                ?: chatManager.getAllMessages().firstOrNull { it.id == messageId }?.status
        } catch (e: Exception) {
            Log.e(TAG, "Error reading current message status", e)
            null
        }
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

    private fun maybeStartParentLocationService() {
        try {
            val prefs = getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
            val share = prefs.getBoolean("share_parent_location", true)
            if (share) {
                ParentLocationService.start(this)
                Log.d(TAG, "ParentLocationService started (share_parent_location=$share)")
            } else {
                Log.d(TAG, "Parent location sharing disabled in prefs")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ParentLocationService", e)
        }
    }

    private fun shouldShowIncomingChatNotification(sender: String): Boolean {
        if (sender != "child") return false
        val prefs = getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
        return !prefs.getBoolean("chat_open", false)
    }

    private fun saveBase64PhotoToGallery(base64: String, timestamp: Long): android.net.Uri? {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        val resolver = contentResolver
        val name = "childwatch_photo_${timestamp}.jpg"
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ChildWatch")
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
        }
        return uri
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
        chatMessageSentListener?.let { WebSocketManager.removeChatMessageSentListener(it) }
        chatMessageSentListener = null
        photoReceivedListener?.let { WebSocketManager.removePhotoReceivedListener(it) }
        photoReceivedListener = null
        photoErrorListener?.let { WebSocketManager.removePhotoErrorListener(it) }
        photoErrorListener = null
        isRunning = false
    }
}
