package ru.example.parentwatch.network

import android.content.Context
import android.util.Log
import android.util.Base64
import io.socket.client.IO
import io.socket.client.Ack
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import ru.childwatch.shared.attention.AttentionSignalContract
import ru.example.parentwatch.chat.ChatMessage
import ru.example.parentwatch.chat.ChatMessageRuntimeRegistry
import ru.example.parentwatch.session.ChildParticipantNameResolver
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocketClient for ChildWatch (Parent Device)
 * Handles real-time audio chunk reception via Socket.IO
 */
class WebSocketClient(
    private val serverUrl: String,
    private val childDeviceId: String,
    private val onMissedMessages: ((List<ChatMessage>) -> Unit)? = null,
    context: Context? = null
) {
    private var socket: Socket? = null
    private var isConnected = false
    private var isConnecting = false
    private var isRegistered = false
    private var registeredDeviceId: String = childDeviceId
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val appContext = context?.applicationContext
    private val participantNameResolver =
        appContext?.let { ChildParticipantNameResolver(it) }
    private val tokenManager = appContext?.let { TokenManager(it) }
    private val syncPrefs =
        appContext?.getSharedPreferences("chat_sync_state", Context.MODE_PRIVATE)

    // Callbacks
    data class CriticalAlertMessage(
        val id: Long,
        val eventType: String,
        val severity: String,
        val message: String,
        val metadata: String?,
        val createdAt: Long
    )

    private var onAudioChunkReceived: ((ByteArray, Int, Long) -> Unit)? = null
    private var onChildDisconnected: (() -> Unit)? = null
    private var onCriticalAlertCallback: ((CriticalAlertMessage) -> Unit)? = null
    private var onConnectedCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onChatMessageCallback: ((String, String, String, Long) -> Unit)? = null
    private var onChatMessageSentCallback: ((String, Boolean, Long) -> Unit)? = null
    private var onChatStatusCallback: ((String, String, Long) -> Unit)? = null
    private var onChatStatusAckCallback: ((String, String, Long) -> Unit)? = null
    private var onCommandCallback: ((String, JSONObject?) -> Unit)? = null
    private var onRegisteredCallback: (() -> Unit)? = null
    private var onTypingCallback: ((isTyping: Boolean) -> Unit)? = null
    private var onAttentionStartCallback: ((JSONObject) -> Unit)? = null
    private var onAttentionStopCallback: ((JSONObject) -> Unit)? = null
    private var onAttentionStatusCallback: ((JSONObject) -> Unit)? = null
    private var onAttentionTransportReadyCallback: (() -> Unit)? = null
    private var onChatV2MessageCallback: ((JSONObject) -> Unit)? = null
    private var onChatV2ReceiptCallback: ((JSONObject) -> Unit)? = null
    private var onChatV2ErrorCallback: ((JSONObject) -> Unit)? = null
    private var onChatV2TransportReadyCallback: (() -> Unit)? = null
    
    // Track last processed sequence to prevent duplicates
    private var lastProcessedSequence = -1
    private var audioPayloadTypeLogged = false
    private var heartbeatJob: Job? = null
    private var reregistrationJob: Job? = null
    private val pendingChatCallbacks = ConcurrentHashMap<String, PendingChatCallback>()
    private val recentChatIds =
        object : LinkedHashMap<String, Long>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 200
            }
        }
    @Volatile
    private var shouldRequestMissedMessagesAfterRegistration = true
    private var lastChatSyncTimestamp: Long
        get() = syncPrefs?.getLong(syncKey(), 0L) ?: 0L
        set(value) {
            syncPrefs?.edit()?.putLong(syncKey(), value)?.apply()
        }

    companion object {
        private const val TAG = "WebSocketClient"
        private const val CONNECTION_TIMEOUT = 15000L // 15 seconds (reduced from 20)
        private const val RECONNECTION_DELAY = 500L // 0.5 second (faster initial retry)
        private const val RECONNECTION_DELAY_MAX = 10000L // 10 seconds max (increased from 5)
        private const val PING_INTERVAL = 25000L // 25 seconds (heartbeat)
        private const val REREGISTRATION_INITIAL_DELAY_MS = 10_000L
        private const val REREGISTRATION_INTERVAL_MS = 60_000L
        private const val MAX_RECONNECT_ATTEMPTS_BEFORE_WARNING = 3 // Warn user after 3 failed attempts
        private const val CHAT_ACK_TIMEOUT = 10000L
    }

    private data class PendingChatCallback(
        val onSuccess: () -> Unit,
        val onError: (String) -> Unit,
        val createdAt: Long = System.currentTimeMillis()
    )

    private fun syncKey(): String = "last_chat_sync_${childDeviceId.trim()}"

    private fun normalizeChatEventId(
        primaryId: String?,
        timestamp: Long,
        sender: String,
        text: String
    ): String {
        val normalized = primaryId?.trim().orEmpty()
        return if (normalized.isNotEmpty()) normalized else "$sender|$timestamp|${text.hashCode()}"
    }

    @Synchronized
    private fun shouldAcceptChatEvent(messageId: String, timestamp: Long): Boolean {
        if (messageId.isBlank()) return false
        if (recentChatIds.containsKey(messageId)) return false
        recentChatIds[messageId] = timestamp
        if (timestamp > lastChatSyncTimestamp) {
            lastChatSyncTimestamp = timestamp
        }
        return true
    }

    @Synchronized
    private fun rememberChatEvent(messageId: String, timestamp: Long) {
        if (messageId.isNotBlank()) {
            recentChatIds[messageId] = timestamp
        }
        if (timestamp > lastChatSyncTimestamp) {
            lastChatSyncTimestamp = timestamp
        }
    }

    // Connection event handlers
    private val onConnect = Emitter.Listener {
        Log.d(TAG, "WebSocket connected")
        scope.launch {
            isConnecting = false
            isConnected = true
            isRegistered = false
            registeredDeviceId = childDeviceId
            lastProcessedSequence = -1
            audioPayloadTypeLogged = false
            shouldRequestMissedMessagesAfterRegistration = true
            registerAsParent()
            onConnectedCallback?.invoke()
        }
    }

    private val onDisconnect = Emitter.Listener { args ->
        Log.d(TAG, "WebSocket disconnected. Reason: ${args.getOrNull(0)}")
        isConnecting = false
        isConnected = false
        isRegistered = false
        registeredDeviceId = childDeviceId
        stopHeartbeat()
        lastProcessedSequence = -1
        shouldRequestMissedMessagesAfterRegistration = true
        failPendingChat("Disconnected")
        onChildDisconnected?.invoke()
    }

    private val onConnectError = Emitter.Listener { args ->
        val error = args.getOrNull(0)
        isConnecting = false
        Log.e(TAG, "WebSocket connection error: $error")
        isConnected = false
        isRegistered = false
        registeredDeviceId = childDeviceId
        shouldRequestMissedMessagesAfterRegistration = true
        failPendingChat(error?.toString() ?: "Connection error")
        scope.launch {
            onErrorCallback?.invoke(error?.toString() ?: "Connection error")
        }
    }

    private val onRegistered = Emitter.Listener { args ->
        val data = args.getOrNull(0) as? JSONObject
        val success = data?.optBoolean("success") ?: false
        val deviceId = data?.optString("deviceId")?.trim().orEmpty()
        val requestedDeviceId = data?.optString("requestedDeviceId")?.trim().orEmpty()

        if (success) {
            registeredDeviceId = when {
                deviceId.isNotEmpty() -> deviceId
                requestedDeviceId.isNotEmpty() -> requestedDeviceId
                else -> childDeviceId
            }
            if (registeredDeviceId != childDeviceId) {
                Log.w(
                    TAG,
                    "Child registered with remapped deviceId: requested=$childDeviceId registered=$registeredDeviceId"
                )
            } else {
                Log.d(TAG, "Child device registered: $registeredDeviceId")
            }
            isRegistered = true
            onAttentionTransportReadyCallback?.invoke()
            onChatV2TransportReadyCallback?.invoke()
            onRegisteredCallback?.invoke()
            if (shouldRequestMissedMessagesAfterRegistration) {
                shouldRequestMissedMessagesAfterRegistration = false
                requestMissedMessagesViaSocket()
            } else {
                Log.d(TAG, "Skipping missed messages request on periodic re-registration")
            }
        } else {
            Log.e(TAG, "Parent registration failed for device: $childDeviceId")
        }
    }



    private val onAudioChunk = Emitter.Listener { args ->
        try {
            val metadata = parseMetadata(args.getOrNull(0))
                ?: parseMetadata(args.getOrNull(1))
            val binaryData = parseBinaryChunk(args.getOrNull(1))
                ?: parseBinaryChunk(args.getOrNull(0))
            
            if (metadata != null && binaryData != null) {
                val sequence = metadata.optInt("sequence", -1)
                val timestamp = metadata.optLong("timestamp", System.currentTimeMillis())
                val deviceId = metadata.optString("deviceId")
                
                // Only process chunks for our target device and prevent duplicates
                if (isTargetDevice(deviceId)) {
                    if (sequence < 0) {
                        Log.w(TAG, "Ignoring chunk with invalid sequence: $sequence")
                        return@Listener
                    }

                    if (sequence < lastProcessedSequence) {
                        Log.d(TAG, "Detected sequence reset ($sequence < $lastProcessedSequence). Accepting new stream.")
                    } else if (sequence == lastProcessedSequence) {
                        Log.d(TAG, "Skipped duplicate chunk #$sequence")
                        return@Listener
                    }

                    lastProcessedSequence = sequence
                    Log.d(TAG, "Received audio chunk #$sequence (${binaryData.size} bytes)")
                    scope.launch {
                        onAudioChunkReceived?.invoke(binaryData, sequence, timestamp)
                    }
                }
            } else {
                if (!audioPayloadTypeLogged) {
                    audioPayloadTypeLogged = true
                    val arg0 = args.getOrNull(0)?.javaClass?.name ?: "null"
                    val arg1 = args.getOrNull(1)?.javaClass?.name ?: "null"
                    Log.w(TAG, "Invalid audio chunk payload (arg0=$arg0, arg1=$arg1)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio chunk", e)
        }
    }

    private val onCriticalAlert = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            if (data != null) {
                val alert = CriticalAlertMessage(
                    id = data.optLong("id"),
                    eventType = data.optString("eventType"),
                    severity = data.optString("severity"),
                    message = data.optString("message"),
                    metadata = data.optString("metadata"),
                    createdAt = data.optLong("createdAt")
                )
                
                scope.launch {
                    onCriticalAlertCallback?.invoke(alert)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing critical alert", e)
        }
    }

    private val onParentConnectedEvent = Emitter.Listener { args ->
        val data = args.getOrNull(0) as? JSONObject
        val deviceId = data?.optString("deviceId", childDeviceId) ?: childDeviceId
        if (!isTargetDevice(deviceId)) return@Listener
        Log.d(TAG, "Parent connected")
        scope.launch {
            parentConnectedCallback?.invoke()
        }
    }

    private val onParentDisconnectedEvent = Emitter.Listener { args ->
        val data = args.getOrNull(0) as? JSONObject
        val deviceId = data?.optString("deviceId", childDeviceId) ?: childDeviceId
        if (!isTargetDevice(deviceId)) return@Listener
        Log.d(TAG, "Parent disconnected")
        lastProcessedSequence = -1
        scope.launch {
            parentDisconnectedCallback?.invoke()
        }
    }

    private val onChildDisconnectedEvent = Emitter.Listener {
        Log.d(TAG, "Child device disconnected")
        lastProcessedSequence = -1
        scope.launch {
            onChildDisconnected?.invoke()
        }
    }

    private val onMissedMessagesEvent = Emitter.Listener { args ->
        try {
            val payload = args.getOrNull(0) as? JSONObject ?: return@Listener
            if (!payload.optBoolean("success", false)) return@Listener
            val items = payload.optJSONArray("messages") ?: return@Listener
            val restored = mutableListOf<ChatMessage>()
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                mapJsonToChatMessage(obj)
                    ?.takeIf { shouldAcceptChatEvent(it.id, it.timestamp) }
                    ?.let {
                        ChatMessageRuntimeRegistry.remember(it)
                        restored.add(it)
                    }
            }
            if (restored.isNotEmpty()) {
                scope.launch {
                    onMissedMessages?.invoke(restored)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling missed_messages event", e)
        }
    }

    private fun mapJsonToChatMessage(obj: JSONObject): ChatMessage? {
        val textValue = obj.optString("message", obj.optString("text", ""))
        if (textValue.isEmpty()) return null
        val sender = obj.optString("senderRole", obj.optString("sender", ""))
        val authorDeviceId = obj.optString("senderDeviceId").takeIf { it.isNotBlank() }
        val authorDisplayName = obj.optString("senderDisplayName").takeIf { it.isNotBlank() }
        val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
        val messageId = normalizeChatEventId(
            obj.optString("clientMessageId", obj.optString("client_id", obj.optString("id", ""))),
            timestamp,
            sender,
            textValue
        )
        val isRead = obj.optBoolean("isRead", false)
        val isIncoming = when (sender) {
            "parent" -> true
            "child" -> false
            else -> true
        }
        val status = when {
            isRead -> ChatMessage.MessageStatus.READ
            isIncoming -> ChatMessage.MessageStatus.DELIVERED
            else -> ChatMessage.MessageStatus.SENT
        }
        return ChatMessage(
            id = messageId,
            text = textValue,
            sender = sender,
            authorDeviceId = authorDeviceId,
            authorDisplayName = authorDisplayName,
            timestamp = timestamp,
            isRead = isRead,
            status = status
        )
    }

    private fun requestMissedMessagesViaSocket() {
        try {
            val sinceTimestamp = lastChatSyncTimestamp
            val payload = JSONObject().apply {
                put("deviceId", registeredDeviceId.ifBlank { childDeviceId })
                put("sinceTimestamp", sinceTimestamp)
            }
            socket?.emit("get_missed_messages", payload)
            Log.d(TAG, "Requested missed messages via WebSocket since=$sinceTimestamp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request missed messages", e)
        }
    }

    private val onPong = Emitter.Listener {
        Log.d(TAG, "Pong received")
    }

    private val onCommand = Emitter.Listener { args ->
        try {
            val commandData = args.getOrNull(0) as? JSONObject
            if (commandData != null) {
                val type = commandData.optString("type", "")
                val data = commandData.optJSONObject("data")
                val timestamp = commandData.optLong("timestamp", System.currentTimeMillis())

                Log.d(TAG, "Command received: type=$type, timestamp=$timestamp")

                scope.launch {
                    onCommandCallback?.invoke(type, data)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling command", e)
        }
    }

    private val onChatMessage = Emitter.Listener { args ->
        try {
            val messageData = args.getOrNull(0) as? JSONObject
            if (messageData != null) {
                val message = mapJsonToChatMessage(messageData)
                if (message == null) {
                    return@Listener
                }
                val text = message.text
                val sender = message.sender
                val timestamp = message.timestamp
                val messageId = message.id

                if (!shouldAcceptChatEvent(messageId, timestamp)) {
                    Log.d(TAG, "Skipped duplicate chat event id=$messageId")
                    return@Listener
                }

                ChatMessageRuntimeRegistry.remember(message)
                Log.d(TAG, "Chat message received: from=$sender, text=$text")

                scope.launch {
                    onChatMessageCallback?.invoke(messageId, text, sender, timestamp)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling chat message", e)
        }
    }

    private val onChatMessageSent = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            val timestamp = data.optLong("timestamp", System.currentTimeMillis())
            val messageId = normalizeChatEventId(
                data.optString("clientMessageId", data.optString("id", "")),
                timestamp,
                "child",
                ""
            )
            if (messageId.isEmpty()) return@Listener
            val delivered = data.optBoolean("delivered", false)

            rememberChatEvent(messageId, timestamp)
            Log.d(TAG, "Chat message sent confirmation: id=$messageId, delivered=$delivered")
            pendingChatCallbacks.remove(messageId)?.onSuccess?.invoke()
            onChatMessageSentCallback?.invoke(messageId, delivered, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling chat message sent confirmation", e)
        }
    }

    private val onChatMessageStatus = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            val messageId = data.optString("id")
            val status = data.optString("status")
            if (messageId.isNullOrEmpty() || status.isNullOrEmpty()) return@Listener
            val timestamp = data.optLong("timestamp", System.currentTimeMillis())
            Log.d(TAG, "Chat status update: id=$messageId status=$status")
            onChatStatusCallback?.invoke(messageId, status, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling chat status update", e)
        }
    }

    private val onChatMessageStatusAck = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            val messageId = data.optString("id")
            val status = data.optString("status")
            if (messageId.isNullOrEmpty() || status.isNullOrEmpty()) return@Listener
            val timestamp = data.optLong("timestamp", System.currentTimeMillis())
            Log.d(TAG, "Chat status ack: id=$messageId status=$status")
            onChatStatusAckCallback?.invoke(messageId, status, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling chat status ack", e)
        }
    }

    private val onChatMessageError = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            val error = data?.optString("error") ?: "Unknown error"

            Log.e(TAG, "Chat message error: $error")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling chat message error", e)
        }
    }

    private val onRequestPhoto = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            val requestId = data?.optString("requestId") ?: ""
            val targetDevice = data?.optString("targetDevice") ?: ""
            val cameraFacing = data?.optString("camera", "back") ?: "back"

            Log.d(TAG, "Received photo request: requestId=$requestId, targetDevice=$targetDevice")

            // Forward to PhotoIntegration via callback
            onRequestPhotoCallback?.invoke(requestId, targetDevice, cameraFacing)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling photo request", e)
        }
    }

    // Callback for photo request
    var onRequestPhotoCallback: ((requestId: String, targetDevice: String, cameraFacing: String) -> Unit)? = null

    private val onTypingStart = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            val deviceId = data?.optString("deviceId") ?: ""
            
            // Only process if it's NOT from this child device (it's from parent)
            if (deviceId != childDeviceId) {
                Log.d(TAG, "Parent started typing")
                onTypingCallback?.invoke(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling typing_start", e)
        }
    }

    private val onTypingStop = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            val deviceId = data?.optString("deviceId") ?: ""
            
            // Only process if it's NOT from this child device (it's from parent)
            if (deviceId != childDeviceId) {
                Log.d(TAG, "Parent stopped typing")
                onTypingCallback?.invoke(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling typing_stop", e)
        }
    }

    private val onAttentionStart = Emitter.Listener { args ->
        (args.getOrNull(0) as? JSONObject)?.let { payload ->
            runCatching { onAttentionStartCallback?.invoke(payload) }
                .onFailure { Log.e(TAG, "Error handling attention signal start", it) }
        }
    }

    private val onAttentionStop = Emitter.Listener { args ->
        (args.getOrNull(0) as? JSONObject)?.let { payload ->
            runCatching { onAttentionStopCallback?.invoke(payload) }
                .onFailure { Log.e(TAG, "Error handling attention signal stop", it) }
        }
    }

    private val onAttentionStatus = Emitter.Listener { args ->
        (args.getOrNull(0) as? JSONObject)?.let { payload ->
            runCatching { onAttentionStatusCallback?.invoke(payload) }
                .onFailure { Log.e(TAG, "Error handling attention signal status", it) }
        }
    }

    private val onChatV2Message = Emitter.Listener { args ->
        (args.getOrNull(0) as? JSONObject)?.let { payload ->
            runCatching { onChatV2MessageCallback?.invoke(payload) }
                .onFailure { Log.e(TAG, "Error handling chat_v2:message", it) }
        }
    }

    private val onChatV2ReceiptUpdated = Emitter.Listener { args ->
        (args.getOrNull(0) as? JSONObject)?.let { payload ->
            runCatching { onChatV2ReceiptCallback?.invoke(payload) }
                .onFailure { Log.e(TAG, "Error handling chat_v2:receipt_updated", it) }
        }
    }

    private val onChatV2Error = Emitter.Listener { args ->
        (args.getOrNull(0) as? JSONObject)?.let { payload ->
            runCatching { onChatV2ErrorCallback?.invoke(payload) }
                .onFailure { Log.e(TAG, "Error handling chat_v2:error", it) }
        }
    }

    private val onParentLocation = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            val parentId = data.optString("parentId")
            val latitude = data.optDouble("latitude")
            val longitude = data.optDouble("longitude")
            val accuracy = data.optDouble("accuracy", 0.0).toFloat()
            val timestamp = data.optLong("timestamp", System.currentTimeMillis())
            val speed = data.optDouble("speed", 0.0).toFloat()
            val bearing = data.optDouble("bearing", 0.0).toFloat()
            
            Log.d(TAG, "Received parent location: $latitude, $longitude")
            
            // Save to local database
            onParentLocationCallback?.invoke(parentId, latitude, longitude, accuracy, timestamp, speed, bearing)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling parent_location", e)
        }
    }

    // Callback for parent location
    var onParentLocationCallback: ((parentId: String, lat: Double, lon: Double, accuracy: Float, timestamp: Long, speed: Float, bearing: Float) -> Unit)? = null

    /**
     * Connect to WebSocket server
     */
    fun connect(
        onConnected: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        // Save callbacks
        onConnectedCallback = onConnected
        onErrorCallback = onError

        try {
            if (socket?.connected() == true && isConnected) {
                Log.d(TAG, "WebSocket already connected")
                onConnected()
                if (!isRegistered) {
                    requestRegistration()
                }
                return
            }
            if (isConnecting) {
                Log.d(TAG, "WebSocket connection already in progress")
                return
            }
            socket?.let { existingSocket ->
                Log.d(TAG, "Disposing stale socket before reconnect")
                runCatching {
                    existingSocket.off()
                    existingSocket.disconnect()
                    existingSocket.close()
                }.onFailure {
                    Log.w(TAG, "Failed to dispose stale socket cleanly: ${it.message}")
                }
                socket = null
            }

            Log.d(TAG, "Connecting to WebSocket: $serverUrl")

            val opts = IO.Options().apply {
                transports = arrayOf("websocket", "polling") // WebSocket preferred
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = RECONNECTION_DELAY
                reconnectionDelayMax = RECONNECTION_DELAY_MAX
                timeout = CONNECTION_TIMEOUT
                tokenManager?.getAuthToken()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { authToken -> auth = mapOf("token" to authToken) }
                // Socket.IO will handle ping/pong automatically
            }

            socket = IO.socket(serverUrl, opts)
            isConnecting = true

            // Connection event handlers
            socket?.on(Socket.EVENT_CONNECT, onConnect)
            socket?.on(Socket.EVENT_DISCONNECT, onDisconnect)
            socket?.on(Socket.EVENT_CONNECT_ERROR, onConnectError)
            socket?.on("registered", onRegistered)
            socket?.on("audio_chunk", onAudioChunk)
            socket?.on("critical_alert", onCriticalAlert)
            socket?.on("parent_connected", onParentConnectedEvent)
            socket?.on("parent_disconnected", onParentDisconnectedEvent)
            socket?.on("child_disconnected", onChildDisconnectedEvent)
            socket?.on("pong", onPong)
            socket?.on("command", onCommand)
            socket?.on("chat_message", onChatMessage)
            socket?.on("chat_message_sent", onChatMessageSent)
            socket?.on("chat_message_status", onChatMessageStatus)
            socket?.on("chat_message_status_ack", onChatMessageStatusAck)
            socket?.on("chat_message_error", onChatMessageError)
            socket?.on("request_photo", onRequestPhoto)
            socket?.on("typing_start", onTypingStart)
            socket?.on("typing_stop", onTypingStop)
            socket?.on("missed_messages", onMissedMessagesEvent)
            socket?.on("parent_location", onParentLocation)
            socket?.on(AttentionSignalContract.EVENT_START, onAttentionStart)
            socket?.on(AttentionSignalContract.EVENT_STOP, onAttentionStop)
            socket?.on(AttentionSignalContract.EVENT_STATUS, onAttentionStatus)
            socket?.on("chat_v2:message", onChatV2Message)
            socket?.on("chat_v2:receipt_updated", onChatV2ReceiptUpdated)
            socket?.on("chat_v2:error", onChatV2Error)

            socket?.connect()

        } catch (e: Exception) {
            isConnecting = false
            Log.e(TAG, "Connection error", e)
            onError("Connection error: ${e.message}")
        }
    }

    /**
     * Disconnect from WebSocket server
     */
    fun disconnect() {
        try {
            Log.d(TAG, "Disconnecting from WebSocket")
            stopHeartbeat()
            isConnecting = false
            socket?.disconnect()
            socket?.off("critical_alert")
            socket?.off()
            runCatching { socket?.close() }
            socket = null
            isConnected = false
            isRegistered = false
            registeredDeviceId = childDeviceId
            failPendingChat("Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }

    /**
     * Set callback for receiving audio chunks
     */
    fun setAudioChunkCallback(callback: (ByteArray, Int, Long) -> Unit) {
        onAudioChunkReceived = callback
    }

    /**
     * Set callback for child disconnect event
     */
    fun setChildDisconnectedCallback(callback: () -> Unit) {
        onChildDisconnected = callback
    }
    
    fun setCriticalAlertCallback(callback: (CriticalAlertMessage) -> Unit) {
        onCriticalAlertCallback = callback
    }

    /**
     * Register as parent device for specific child
     */
    private fun registerAsParent() {
        try {
            val childDisplayName = participantNameResolver?.resolveChildDisplayName().orEmpty()
            val registrationData = JSONObject().apply {
                put("deviceId", childDeviceId) // This IS our device ID (ParentWatch's own ID)
                if (childDisplayName.isNotBlank()) {
                    put("childDisplayName", childDisplayName)
                }
            }

            socket?.emit("register_child", registrationData) // ParentWatch IS the child device!
            Log.d(TAG, "Child device registration sent: $childDeviceId, socketId: ${socket?.id()}")

            // Retry registration after 2 seconds to ensure it's received
            scope.launch {
                delay(2000)
                if (isConnected && socket != null) {
                    socket?.emit("register_child", registrationData)
                    Log.d(TAG, "Child device registration retry sent: $childDeviceId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering as child device", e)
        }
    }

    /**
     * Send heartbeat/ping
     */
    fun sendPing() {
        try {
            socket?.emit("ping")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ping", e)
        }
    }

    /**
     * Send chat message
     */
    fun sendChatMessage(
        messageId: String,
        text: String,
        sender: String,
        authorDeviceId: String? = null,
        authorDisplayName: String? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        try {
            if (!isConnected) {
                onError("Not connected to server")
                return
            }
            if (!isRegistered) {
                onError("Not registered on server")
                return
            }

            val messageData = JSONObject().apply {
                put("id", messageId)
                put("text", text)
                put("sender", sender)
                put("deviceId", registeredDeviceId.ifBlank { childDeviceId })
                put("timestamp", System.currentTimeMillis())
                authorDeviceId?.takeIf { it.isNotBlank() }?.let {
                    put("authorDeviceId", it)
                }
                authorDisplayName?.takeIf { it.isNotBlank() }?.let {
                    put("authorDisplayName", it)
                }
            }

            pendingChatCallbacks[messageId] = PendingChatCallback(onSuccess, onError)
            socket?.emit("chat_message", messageData)

            scope.launch {
                delay(CHAT_ACK_TIMEOUT)
                pendingChatCallbacks.remove(messageId)?.let { pending ->
                    pending.onError("No server ACK for message $messageId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending chat message", e)
            onError("Failed to send message: ${e.message}")
        }
    }

    /**
     * Send command to child device
     */
    fun sendCommand(
        commandType: String,
        data: JSONObject? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        try {
            if (!isConnected) {
                onError("Not connected to server")
                return
            }

            val commandData = JSONObject().apply {
                put("type", commandType)
                put("deviceId", registeredDeviceId.ifBlank { childDeviceId })
                put("timestamp", System.currentTimeMillis())
                if (data != null) {
                    put("data", data)
                }
            }

            socket?.emit("command", commandData)
            Log.d(TAG, "Command sent: $commandType to device: ${registeredDeviceId.ifBlank { childDeviceId }}")
            onSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending command", e)
            onError("Failed to send command: ${e.message}")
        }
    }

    /**
     * Set callback for chat messages
     */
    fun setChatMessageCallback(callback: (messageId: String, text: String, sender: String, timestamp: Long) -> Unit) {
        onChatMessageCallback = callback
        Log.d(TAG, "Chat message callback registered")
    }

    fun setChatMessageSentCallback(callback: (messageId: String, delivered: Boolean, timestamp: Long) -> Unit) {
        onChatMessageSentCallback = callback
    }

    fun setChatStatusCallback(callback: (messageId: String, status: String, timestamp: Long) -> Unit) {
        onChatStatusCallback = callback
    }

    fun setChatStatusAckCallback(callback: (messageId: String, status: String, timestamp: Long) -> Unit) {
        onChatStatusAckCallback = callback
    }

    /**
     * Set callback for typing indicator
     */
    fun setTypingCallback(callback: (isTyping: Boolean) -> Unit) {
        onTypingCallback = callback
        Log.d(TAG, "Typing indicator callback registered")
    }

    /**
     * Send typing start/stop event
     */
    fun sendTypingStatus(isTyping: Boolean) {
        try {
            if (!isConnected) {
                Log.w(TAG, "Cannot send typing status - not connected")
                return
            }
            val event = if (isTyping) "typing_start" else "typing_stop"
            val payload = JSONObject().apply {
                put("deviceId", registeredDeviceId.ifBlank { childDeviceId })
                put("timestamp", System.currentTimeMillis())
            }
            socket?.emit(event, payload)
            Log.d(TAG, "Sent $event event")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send typing status", e)
        }
    }

    fun sendChatStatus(messageId: String, status: String, actor: String): Boolean {
        try {
            if (!isReady()) return false
            val payload = JSONObject().apply {
                put("id", messageId)
                put("status", status)
                put("deviceId", registeredDeviceId.ifBlank { childDeviceId })
                put("actor", actor)
                put("timestamp", System.currentTimeMillis())
            }
            socket?.emit("chat_message_status", payload)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send chat status", e)
            return false
        }
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected

    fun isReady(): Boolean = isConnected && isRegistered

    private fun parseMetadata(raw: Any?): JSONObject? {
        return when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            is Map<*, *> -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        }
    }

    private fun parseBinaryChunk(raw: Any?): ByteArray? {
        return when (raw) {
            is ByteArray -> raw
            is ByteBuffer -> {
                val copy = raw.slice()
                ByteArray(copy.remaining()).also { copy.get(it) }
            }
            is JSONArray -> {
                val out = ByteArray(raw.length())
                for (i in 0 until raw.length()) {
                    out[i] = raw.optInt(i, 0).toByte()
                }
                out
            }
            is IntArray -> raw.map { it.toByte() }.toByteArray()
            is String -> runCatching { Base64.decode(raw, Base64.DEFAULT) }.getOrNull()
            is Array<*> -> {
                val out = ByteArray(raw.size)
                for (i in raw.indices) {
                    val value = raw[i]
                    out[i] = when (value) {
                        is Number -> value.toInt().toByte()
                        else -> return null
                    }
                }
                out
            }
            else -> null
        }
    }

    private fun isTargetDevice(candidate: String?): Boolean {
        val normalizedCandidate = candidate?.trim().orEmpty()
        if (normalizedCandidate.isEmpty()) return false
        val requested = childDeviceId.trim()
        val registered = registeredDeviceId.trim()
        return normalizedCandidate == requested || normalizedCandidate == registered
    }

    fun setRegisteredCallback(callback: () -> Unit) {
        onRegisteredCallback = callback
    }

    fun requestRegistration() {
        if (isConnected) {
            registerAsParent()
        }
    }

    /**
     * Start heartbeat job to keep connection alive
     */
    fun startHeartbeat(intervalMs: Long = PING_INTERVAL) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isConnected) {
                try {
                    sendPing()
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat ping failed", e)
                    break
                }
                delay(intervalMs)
            }
        }

        // Start periodic re-registration to ensure server knows we're a parent
        startPeriodicReregistration()
    }

    /**
     * Periodically re-register as parent to refresh server-side presence.
     */
    private fun startPeriodicReregistration() {
        reregistrationJob?.cancel()
        reregistrationJob = scope.launch {
            delay(REREGISTRATION_INITIAL_DELAY_MS)
            while (isActive && isConnected) {
                try {
                    registerAsParent()
                    Log.d(TAG, "Periodic parent re-registration triggered")
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic re-registration failed", e)
                }
                delay(REREGISTRATION_INTERVAL_MS)
            }
        }
    }

    fun stopHeartbeat() {
        reregistrationJob?.cancel()
        reregistrationJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // Legacy API compatibility methods for audio streaming
    private var commandCallback: ((String, JSONObject?) -> Unit)? = null
    private var parentConnectedCallback: (() -> Unit)? = null
    private var parentDisconnectedCallback: (() -> Unit)? = null
    
    fun setCommandCallback(callback: (commandType: String, data: JSONObject?) -> Unit) {
        commandCallback = callback
        onCommandCallback = callback // Link to event handler
        Log.d(TAG, "Command callback set")
    }
    
    fun setParentConnectedCallback(callback: () -> Unit) {
        parentConnectedCallback = callback
        Log.d(TAG, "Parent connected callback set")
    }
    
    fun setParentDisconnectedCallback(callback: () -> Unit) {
        parentDisconnectedCallback = callback
        Log.d(TAG, "Parent disconnected callback set")
    }
    
    fun sendAudioChunk(
        sequence: Int,
        audioData: ByteArray,
        recording: Boolean = true,
        sampleRate: Int = 24_000,
        batteryLevel: Int? = null,
        isCharging: Boolean? = null,
        deviceStatusTimestamp: Long? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (!isReady()) {
            Log.w(TAG, "Cannot send audio chunk - socket not ready")
            onError("Socket not ready")
            return
        }
        
        try {
            val timestamp = System.currentTimeMillis()
            val metadata = JSONObject().apply {
                put("deviceId", registeredDeviceId.ifBlank { childDeviceId }) // Our own device ID
                put("sequence", sequence)
                put("timestamp", timestamp)
                put("recording", recording)
                put("sampleRate", sampleRate)
                put("channels", 1)
                if (batteryLevel != null) {
                    put("batteryLevel", batteryLevel)
                }
                if (isCharging != null) {
                    put("isCharging", isCharging)
                }
                if (deviceStatusTimestamp != null && deviceStatusTimestamp > 0L) {
                    put("deviceStatusTimestamp", deviceStatusTimestamp)
                }
            }
            // Send metadata and binary data separately (matching server expectations)
            socket?.emit("audio_chunk", metadata, audioData)
            Log.d(TAG, "Sent audio chunk #$sequence (${audioData.size} bytes)")
            onSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk", e)
            onError(e.message ?: "Unknown error")
        }
    }

    /**
     * Public emit method for external modules to send WebSocket events
     */
    fun emit(event: String, data: JSONObject) {
        socket?.emit(event, data)
    }

    fun emitWithAck(event: String, data: JSONObject, callback: (JSONObject) -> Unit): Boolean {
        if (!isReady()) return false
        val activeSocket = socket ?: return false
        activeSocket.emit(event, data, Ack { args ->
            val payload = when (val raw = args.getOrNull(0)) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrNull()
                else -> null
            } ?: JSONObject().put("success", false).put("code", "INVALID_ACK")
            callback(payload)
        })
        return true
    }

    fun setAttentionCallbacks(
        onStart: ((JSONObject) -> Unit)?,
        onStop: ((JSONObject) -> Unit)?,
        onStatus: ((JSONObject) -> Unit)?,
        onTransportReady: (() -> Unit)? = null
    ) {
        onAttentionStartCallback = onStart
        onAttentionStopCallback = onStop
        onAttentionStatusCallback = onStatus
        onAttentionTransportReadyCallback = onTransportReady
    }

    fun setChatV2Callbacks(
        onMessage: ((JSONObject) -> Unit)?,
        onReceiptUpdated: ((JSONObject) -> Unit)?,
        onError: ((JSONObject) -> Unit)?,
        onTransportReady: (() -> Unit)? = null
    ) {
        onChatV2MessageCallback = onMessage
        onChatV2ReceiptCallback = onReceiptUpdated
        onChatV2ErrorCallback = onError
        onChatV2TransportReadyCallback = onTransportReady
    }

    /**
     * Public emit method with byte array (for binary data like photos)
     */
    fun emit(event: String, metadata: JSONObject, binaryData: ByteArray) {
        socket?.emit(event, metadata, binaryData)
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        failPendingChat("Client cleanup")
        stopHeartbeat()
        disconnect()
        scope.cancel()
        onAudioChunkReceived = null
        onChildDisconnected = null
        onConnectedCallback = null
        onErrorCallback = null
        onRegisteredCallback = null
        onCriticalAlertCallback = null
        onChatMessageCallback = null
        onChatMessageSentCallback = null
        onChatStatusCallback = null
        commandCallback = null
        parentConnectedCallback = null
        parentDisconnectedCallback = null
        onAttentionStartCallback = null
        onAttentionStopCallback = null
        onAttentionStatusCallback = null
        onAttentionTransportReadyCallback = null
        onChatV2MessageCallback = null
        onChatV2ReceiptCallback = null
        onChatV2ErrorCallback = null
        onChatV2TransportReadyCallback = null
    }

    private fun failPendingChat(reason: String) {
        if (pendingChatCallbacks.isEmpty()) return
        val pending = pendingChatCallbacks.entries.toList()
        pendingChatCallbacks.clear()
        pending.forEach { entry ->
            try {
                entry.value.onError(reason)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify pending chat error", e)
            }
        }
    }
}
