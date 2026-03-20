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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R
import ru.example.parentwatch.chat.ChatManager
import ru.example.parentwatch.chat.ChatMessage
import ru.example.parentwatch.chat.withStatus
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
        private const val PREFS_NAME = "parentwatch_prefs"
        private const val PREF_CHAT_DESIRED = "chat_service_desired"
        private const val CONNECTION_HEALTH_INTERVAL_HEALTHY_MS = 45_000L
        private const val CONNECTION_HEALTH_INTERVAL_DEGRADED_MS = 15_000L

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
    private var backgroundMessageSentListener: ((String, Boolean, Long) -> Unit)? = null
    private var backgroundListenerRegistered = false
    private var connectionHealthJob: kotlinx.coroutines.Job? = null
    @Volatile private var reconnectInProgress = false
    private var stopRequested = false
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
                            "Received start_audio_stream (recording=$recording, sampleRate=$sampleRate), delegating to LocationService"
                        )
                        runCatching {
                            LocationService.requestAudioStart(this, recording, sampleRate)
                        }.onFailure { error ->
                            Log.e(TAG, "Failed to delegate audio start to LocationService, falling back", error)
                            runCatching {
                                AudioStreamingService.startStreaming(this, id, url, recording, sampleRate)
                            }.onFailure { fallbackError ->
                                Log.e(TAG, "Fallback AudioStreamingService start failed", fallbackError)
                            }
                            RemoteLogger.error(
                                serverUrl = url,
                                deviceId = id,
                                source = TAG,
                                message = "Failed to delegate audio start to LocationService",
                                throwable = error
                            )
                        }
                    }
                } else {
                    Log.w(TAG, "Ignoring start_audio_stream: missing server/device context")
                }
            }
            "stop_audio_stream" -> {
                Log.d(TAG, "Received stop_audio_stream, delegating stop to LocationService")
                runCatching { LocationService.requestAudioStop(this) }
                AudioStreamingService.stopStreaming(this)
            }
            "take_photo" -> {
                val url = lastServerUrl
                val id = lastDeviceId
                if (!url.isNullOrBlank() && !id.isNullOrBlank()) {
                    val cameraFacing = data?.optString("camera", "front").orEmpty().ifBlank { "front" }
                    val requestId = data?.optString("requestId", "").orEmpty()
                        .ifBlank { "legacy-photo-${System.currentTimeMillis()}" }
                    val targetDevice = data?.optString("targetDevice", "").orEmpty().ifBlank { id }
                    Log.d(TAG, "Dispatching legacy take_photo via on-demand PhotoCaptureService")
                    PhotoCaptureService.dispatchPhotoRequest(
                        this,
                        url,
                        id,
                        requestId,
                        targetDevice,
                        cameraFacing
                    )
                } else {
                    Log.w(TAG, "Ignoring take_photo: missing server/device context")
                }
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

                stopRequested = false
                markChatDesired(true)
                startForegroundService(serverUrl, deviceId)
            }
            ACTION_STOP_SERVICE -> {
                stopRequested = true
                markChatDesired(false)
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
        if (reconnectInProgress) {
            Log.d(TAG, "Chat reconnect already in progress, skipping duplicate start")
            return
        }
        Log.d(TAG, "Starting chat background service (isRunning=$isRunning)")

        // Start foreground with notification
        if (!isRunning) {
            startForeground(NOTIFICATION_ID, createNotification(getString(R.string.chat_service_connecting)))
        }

        if (isRunning &&
            serverUrl == lastServerUrl &&
            deviceId == lastDeviceId &&
            WebSocketManager.getClient() != null &&
            WebSocketManager.isConnected() &&
            WebSocketManager.isReady()
        ) {
            WebSocketManager.ensureConnected(
                onReady = { updateNotification(getString(R.string.chat_service_active)) },
                onError = { updateNotification(getString(R.string.chat_service_error)) }
            )
            startConnectionHealthLoop(serverUrl, deviceId)
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
        reconnectInProgress = true
        lifecycleScope.launch {
            var attempt = 0
            val maxAttempts = 3
            var connected = false

            try {
                while (attempt < maxAttempts && !connected) {
                    attempt++
                    Log.d(TAG, "WebSocket connection attempt $attempt/$maxAttempts")

                    val delayMs = if (attempt == 1) 2000L else 1000L
                    delay(delayMs)

                    try {
                        WebSocketManager.initialize(
                            this@ChatBackgroundService,
                            serverUrl,
                            deviceId,
                            onMissedMessages = { missed ->
                                missed.forEach { msg ->
                                    val persistedMessage =
                                        if (msg.sender == "parent" && !msg.isRead && msg.id.isNotEmpty()) {
                                            msg.withStatus(ChatMessage.MessageStatus.DELIVERED)
                                        } else {
                                            msg
                                        }
                                    if (::chatManagerAdapter.isInitialized) {
                                        chatManagerAdapter.saveMessage(persistedMessage)
                                    }
                                    if (msg.sender == "parent" && !msg.isRead && msg.id.isNotEmpty()) {
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
                        if (backgroundMessageSentListener == null) {
                            backgroundMessageSentListener = { messageId, delivered, _ ->
                                handleMessageSentAck(messageId, delivered)
                            }
                            WebSocketManager.addChatMessageSentListener(backgroundMessageSentListener!!)
                        }
                        if (!backgroundListenerRegistered) {
                            WebSocketManager.addChatMessageListener(backgroundListener)
                            WebSocketManager.addCommandListener(commandListener)
                            backgroundListenerRegistered = true
                        }

                        WebSocketManager.setPhotoRequestCallback { requestId, targetDevice, cameraFacing ->
                            Log.d(TAG, "Photo request received (req=$requestId, target=$targetDevice)")
                            PhotoCaptureService.dispatchPhotoRequest(
                                this@ChatBackgroundService,
                                serverUrl,
                                deviceId,
                                requestId,
                                targetDevice,
                                cameraFacing
                            )
                        }

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
                                    Log.d(TAG, "Parent location saved to DB: $lat, $lon")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error saving parent location to DB", e)
                                }
                            }
                        }

                        WebSocketManager.connect(
                            onConnected = {
                                Log.d(TAG, "WebSocket connected successfully on attempt $attempt")
                                updateNotification(getString(R.string.chat_service_active))
                                try { WebSocketManager.getClient()?.startHeartbeat() } catch (_: Exception) {}
                                connected = true
                            },
                            onError = { error ->
                                Log.e(TAG, "WebSocket connection error on attempt $attempt: $error")
                                if (attempt >= maxAttempts) {
                                    updateNotification(getString(R.string.chat_service_error))
                                }
                            }
                        )

                        delay(1500)

                        if (WebSocketManager.isConnected()) {
                            connected = true
                            Log.d(TAG, "WebSocket connection verified on attempt $attempt")
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Exception during connection attempt $attempt", e)
                        if (attempt >= maxAttempts) {
                            updateNotification(getString(R.string.chat_service_error))
                        }
                    }
                }
            } finally {
                reconnectInProgress = false
            }

            if (!connected) {
                Log.e(TAG, "Failed to connect WebSocket after $maxAttempts attempts")
                updateNotification(getString(R.string.chat_service_unavailable))
            }
        }

        isRunning = true
        startConnectionHealthLoop(serverUrl, deviceId)
    }

    private fun stopForegroundService() {
        Log.d(TAG, "Stopping chat background service")

        // Clear callback
        WebSocketManager.removeChatMessageListener(backgroundListener)
        backgroundMessageSentListener?.let { WebSocketManager.removeChatMessageSentListener(it) }
        backgroundMessageSentListener = null
        WebSocketManager.removeCommandListener(commandListener)
        connectionHealthJob?.cancel()
        connectionHealthJob = null

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

        // Persist incoming message even when the chat UI is open
        val message = ChatMessage(
            id = messageId,
            text = text,
            sender = sender,
            timestamp = timestamp,
            isRead = false,
            status = if (sender == "parent") {
                ChatMessage.MessageStatus.DELIVERED
            } else {
                ChatMessage.MessageStatus.SENT
            }
        )
        if (::chatManagerAdapter.isInitialized) {
            chatManagerAdapter.saveMessage(message)
        } else {
            Log.e(TAG, "ChatManagerAdapter not initialized, message not saved")
        }

        // Always acknowledge delivery for parent-originated messages
        if (sender == "parent" && messageId.isNotEmpty()) {
            WebSocketManager.sendChatStatus(messageId, "delivered", "child")
            Log.d(TAG, "Sent delivered status for message: $messageId")
        }

        // Skip notifications while the chat screen is already visible
        if (ru.example.parentwatch.ChatActivity.isChatUiVisible) {
            Log.d(TAG, "Chat UI is visible, skipping notification")
            return
        }

        // Show notifications only when the chat is not open
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

    private fun handleMessageSentAck(messageId: String, delivered: Boolean) {
        if (messageId.isBlank() || !::chatManagerAdapter.isInitialized) return
        val status = if (delivered) {
            ChatMessage.MessageStatus.DELIVERED
        } else {
            ChatMessage.MessageStatus.SENT
        }
        if (!shouldApplyStatus(messageId, status)) return
        chatManagerAdapter.updateMessageStatus(messageId, status)
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
            chatManagerAdapter.getAllMessages()
                .firstOrNull { it.id == messageId }
                ?.status
        } catch (e: Exception) {
            Log.e(TAG, "Error reading current message status", e)
            null
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
                getString(R.string.chat_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.chat_service_channel_description)
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
            .setContentTitle(getString(R.string.chat_service_title))
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

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ChatBackgroundService destroyed")
        WebSocketManager.removeChatMessageListener(backgroundListener)
        backgroundMessageSentListener?.let { WebSocketManager.removeChatMessageSentListener(it) }
        backgroundMessageSentListener = null
        WebSocketManager.removeCommandListener(commandListener)
        connectionHealthJob?.cancel()
        connectionHealthJob = null
        isRunning = false
    }

    private fun startConnectionHealthLoop(serverUrl: String, deviceId: String) {
        connectionHealthJob?.cancel()
        connectionHealthJob = lifecycleScope.launch {
            while (isActive) {
                delay(resolveConnectionHealthInterval())
                if (!isChatDesired() || stopRequested) continue

                val degraded = !WebSocketManager.isConnected() || !WebSocketManager.isReady()
                if (degraded && !reconnectInProgress) {
                    Log.w(TAG, "Chat health check detected degraded socket, forcing reconnect")
                    startForegroundService(serverUrl, deviceId)
                }
            }
        }
    }

    private fun resolveConnectionHealthInterval(): Long {
        return if (WebSocketManager.isConnected() && WebSocketManager.isReady()) {
            CONNECTION_HEALTH_INTERVAL_HEALTHY_MS
        } else {
            CONNECTION_HEALTH_INTERVAL_DEGRADED_MS
        }
    }

    private fun markChatDesired(desired: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_CHAT_DESIRED, desired)
            .apply()
    }

    private fun isChatDesired(): Boolean {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_CHAT_DESIRED, false)
    }
}

