package ru.example.parentwatch.network

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URISyntaxException
import ru.example.parentwatch.utils.RemoteLogger

/**
 * WebSocketClient for ParentWatch (Child Device)
 * Handles real-time audio chunk transmission via Socket.IO
 */
class WebSocketClient(
    private val serverUrl: String,
    private val deviceId: String
) {
    private var socket: Socket? = null
    private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null

    companion object {
        private const val TAG = "WebSocketClient"
        private const val CONNECTION_TIMEOUT = 30000L // 30 seconds - increased for Railway
        private const val RECONNECTION_DELAY = 1000L // 1 second
        private const val RECONNECTION_DELAY_MAX = 5000L // 5 seconds
        private const val HEARTBEAT_INTERVAL = 25000L // 25 seconds
        private const val RECONNECTION_ATTEMPTS = Int.MAX_VALUE // Infinite reconnection attempts
    }

    // Connection event handlers
    private val onConnect = Emitter.Listener {
        Log.d(TAG, "WebSocket connected")
        scope.launch {
            isConnected = true
            RemoteLogger.info(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "WebSocket connected"
            )
            registerAsChild()
            onConnectedCallback?.invoke()
        }
    }

    private val onDisconnect = Emitter.Listener { args ->
        val reason = args.getOrNull(0)
        Log.d(TAG, "WebSocket disconnected. Reason: $reason")
        RemoteLogger.warn(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "WebSocket disconnected",
            meta = mapOf("reason" to (reason?.toString() ?: "unknown"))
        )
        isConnected = false
        stopHeartbeat()
        onDisconnectedCallback?.invoke()
    }

    private val onConnectError = Emitter.Listener { args ->
        val error = args.getOrNull(0)
        Log.e(TAG, "WebSocket connection error: $error")
        RemoteLogger.error(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "WebSocket connection error: $error"
        )
        isConnected = false
        scope.launch {
            onErrorCallback?.invoke(error?.toString() ?: "Connection error")
        }
    }

    private val onRegistered = Emitter.Listener { args ->
        val data = args.getOrNull(0) as? JSONObject
        val success = data?.optBoolean("success") ?: false

        if (success) {
            Log.d(TAG, "Child registered: $deviceId")
            RemoteLogger.info(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "Child registered via WebSocket"
            )
        } else {
            Log.e(TAG, "Child registration failed: $deviceId")
            RemoteLogger.error(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "Child registration failed via WebSocket"
            )
        }
    }

    private val onParentConnected = Emitter.Listener {
        Log.d(TAG, "Parent connected to stream")
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "Parent connected to stream"
        )
        scope.launch {
            onParentConnectedCallback?.invoke()
        }
    }

    private val onParentDisconnected = Emitter.Listener {
        Log.d(TAG, "Parent disconnected from stream")
        RemoteLogger.warn(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "Parent disconnected from stream"
        )
        scope.launch {
            onParentDisconnectedCallback?.invoke()
        }
    }

    private val onPong = Emitter.Listener {
        Log.d(TAG, "рџЏ“ Pong received")
    }

    private val onChatMessage = Emitter.Listener { args ->
        try {
            val messageData = args.getOrNull(0) as? JSONObject
            if (messageData != null) {
                val messageId = messageData.optString("id", "")
                val text = messageData.optString("text", "")
                val sender = messageData.optString("sender", "")
                val timestamp = messageData.optLong("timestamp", System.currentTimeMillis())

                Log.d(TAG, "💬 Chat message received: from=$sender, text=$text")
                RemoteLogger.info(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    source = TAG,
                    message = "Chat message received",
                    meta = mapOf("sender" to sender, "messageId" to messageId)
                )

                scope.launch {
                    if (onChatMessageCallback != null) {
                        Log.d(TAG, "✅ Invoking chat message callback")
                        onChatMessageCallback?.invoke(messageId, text, sender, timestamp)
                        Log.d(TAG, "✅ Chat message callback invoked successfully")
                    } else {
                        Log.e(TAG, "❌ Chat message callback is NULL - message will not be delivered!")
                        RemoteLogger.error(
                            serverUrl = serverUrl,
                            deviceId = deviceId,
                            source = TAG,
                            message = "Chat callback is NULL - cannot deliver message"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling chat message", e)
        }
    }

    private val onChatMessageSent = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            val messageId = data.optString("id")
            if (messageId.isNullOrEmpty()) return@Listener

            val timestamp = data.optLong("timestamp", System.currentTimeMillis())
            val delivered = data.optBoolean("delivered", false)

            Log.d(TAG, "✅ Chat message sent confirmation: id=$messageId delivered=$delivered")
            onChatMessageSentCallback?.invoke(messageId, delivered, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling chat message sent confirmation", e)
        }
    }

    private val onChatMessageStatus = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            val messageId = data.optString("id")
            val status = data.optString("status")
            if (messageId.isNullOrEmpty() || status.isNullOrEmpty()) return@Listener
            val timestamp = data.optLong("timestamp", System.currentTimeMillis())
            Log.d(TAG, "📬 Chat status update: id=$messageId status=$status")
            onChatStatusCallback?.invoke(messageId, status, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling chat status update", e)
        }
    }

    private val onCommand = Emitter.Listener { args ->
        try {
            val commandData = args.getOrNull(0) as? JSONObject
            val commandType = commandData?.optString("type")

            Log.d(TAG, "рџ“Ґ Command received: $commandType")

            when (commandType) {
                "start_audio_stream" -> {
                    Log.d(TAG, "рџЋ™пёЏ START AUDIO STREAM command received!")
                    scope.launch {
                        onCommandCallback?.invoke("start_audio_stream", commandData)
                    }
                }
                "stop_audio_stream" -> {
                    Log.d(TAG, "рџ›‘ STOP AUDIO STREAM command received!")
                    scope.launch {
                        onCommandCallback?.invoke("stop_audio_stream", commandData)
                    }
                }
                else -> {
                    Log.w(TAG, "вљ пёЏ Unknown command type: $commandType")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "вќЊ Error handling command", e)
        }
    }

    // Callbacks
    private var onConnectedCallback: (() -> Unit)? = null
    private var onDisconnectedCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onParentConnectedCallback: (() -> Unit)? = null
    private var onParentDisconnectedCallback: (() -> Unit)? = null
    private var onChatMessageCallback: ((messageId: String, text: String, sender: String, timestamp: Long) -> Unit)? = null
    private var onChatMessageSentCallback: ((String, Boolean, Long) -> Unit)? = null
    private var onChatStatusCallback: ((String, String, Long) -> Unit)? = null
    private var onCommandCallback: ((String, JSONObject?) -> Unit)? = null

    /**
     * Connect to WebSocket server
     */
    fun connect(onConnected: () -> Unit = {}, onError: (String) -> Unit = {}) {
        try {
            Log.d(TAG, "Connecting to WebSocket: $serverUrl")

            val opts = IO.Options().apply {
                transports = arrayOf("websocket", "polling") // WebSocket preferred
                reconnection = true
                reconnectionAttempts = RECONNECTION_ATTEMPTS
                reconnectionDelay = RECONNECTION_DELAY
                reconnectionDelayMax = RECONNECTION_DELAY_MAX
                timeout = CONNECTION_TIMEOUT
                forceNew = true // Create new connection
            }

            socket = IO.socket(serverUrl, opts)

            // Save callbacks
            onConnectedCallback = onConnected
            onErrorCallback = onError

            // Connection event handlers
            socket?.on(Socket.EVENT_CONNECT, onConnect)
            socket?.on(Socket.EVENT_DISCONNECT, onDisconnect)
            socket?.on(Socket.EVENT_CONNECT_ERROR, onConnectError)
            socket?.on("registered", onRegistered)
            socket?.on("parent_connected", onParentConnected)
            socket?.on("parent_disconnected", onParentDisconnected)
            socket?.on("pong", onPong)
            socket?.on("chat_message", onChatMessage)
            socket?.on("chat_message_sent", onChatMessageSent)
            socket?.on("chat_message_status", onChatMessageStatus)
            socket?.on("command", onCommand) // в†ђ CRITICAL: Listen for server commands!

            socket?.connect()

        } catch (e: URISyntaxException) {
            Log.e(TAG, "Invalid server URL: $serverUrl", e)
            onError("Invalid server URL: ${e.message}")
        } catch (e: Exception) {
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
            socket?.disconnect()
            socket?.off()
            socket = null
            isConnected = false
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }

    /**
     * Register as child device
     */
    private fun registerAsChild() {
        try {
            val registrationData = JSONObject().apply {
                put("deviceId", deviceId)
            }
            
            socket?.emit("register_child", registrationData)
            Log.d(TAG, "рџ“¤ Child registration sent: $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering as child", e)
        }
    }

    /**
     * Send audio chunk via WebSocket
     */
    fun sendAudioChunk(
        sequence: Int,
        audioData: ByteArray,
        recording: Boolean = false,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        try {
            Log.d(TAG, "рџ“¤ Attempting to send chunk #$sequence (${audioData.size} bytes)...")
            
            if (!isConnected) {
                Log.w(TAG, "вќЊ Not connected - cannot send audio chunk #$sequence")
                onError("Not connected to server")
                return
            }

            if (socket == null) {
                Log.e(TAG, "вќЊ Socket is null - cannot send chunk #$sequence")
                onError("Socket not initialized")
                return
            }

            // Create JSON metadata
            val metadata = JSONObject().apply {
                put("deviceId", deviceId)
                put("sequence", sequence)
                put("timestamp", System.currentTimeMillis())
                put("recording", recording)
            }

            // Send both metadata (JSON) and binary data
            socket?.emit("audio_chunk", metadata, audioData)
            
            Log.d(TAG, "вњ… Audio chunk #$sequence sent successfully")
            onSuccess()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk #$sequence", e)
            onError("Failed to send chunk: ${e.message}")
        }
    }

    /**
     * Start heartbeat to keep connection alive
     */
    fun startHeartbeat() {
        stopHeartbeat() // Cancel any existing heartbeat
        
        heartbeatJob = scope.launch {
            while (isActive && isConnected) {
                try {
                    sendPing()
                    delay(HEARTBEAT_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in heartbeat", e)
                    break
                }
            }
        }
    }

    /**
     * Stop heartbeat
     */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Send heartbeat/ping
     */
    private fun sendPing() {
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
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        try {
            if (!isConnected) {
                onError("Not connected to server")
                return
            }

            val messageData = JSONObject().apply {
                put("id", messageId)
                put("text", text)
                put("sender", sender)
                put("deviceId", deviceId)
                put("timestamp", System.currentTimeMillis())
            }

            socket?.emit("chat_message", messageData)
            onSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending chat message", e)
            onError("Failed to send message: ${e.message}")
        }
    }

    /**
     * Set chat message callback
     */
    fun setChatMessageCallback(callback: (messageId: String, text: String, sender: String, timestamp: Long) -> Unit) {
        onChatMessageCallback = callback
    }

    fun setChatMessageSentCallback(callback: (messageId: String, delivered: Boolean, timestamp: Long) -> Unit) {
        onChatMessageSentCallback = callback
    }

    fun setChatStatusCallback(callback: (messageId: String, status: String, timestamp: Long) -> Unit) {
        onChatStatusCallback = callback
    }

    fun sendChatStatus(messageId: String, status: String, actor: String) {
        try {
            if (!isConnected) return
            val payload = JSONObject().apply {
                put("id", messageId)
                put("status", status)
                put("deviceId", deviceId)
                put("actor", actor)
                put("timestamp", System.currentTimeMillis())
            }
            socket?.emit("chat_message_status", payload)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send chat status", e)
        }
    }

    /**
     * Set parent connection callbacks
     */
    fun setParentConnectedCallback(callback: () -> Unit) {
        onParentConnectedCallback = callback
    }

    fun setParentDisconnectedCallback(callback: () -> Unit) {
        onParentDisconnectedCallback = callback
    }

    /**
     * Set command callback
     */
    fun setCommandCallback(callback: (commandType: String, data: JSONObject?) -> Unit) {
        onCommandCallback = callback
        Log.d(TAG, "вњ… Command callback registered")
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Cleanup resources
     */
    fun cleanup() {
        disconnect()
        scope.cancel()
        onConnectedCallback = null
        onDisconnectedCallback = null
        onErrorCallback = null
        onParentConnectedCallback = null
        onParentDisconnectedCallback = null
        onChatMessageCallback = null
        onChatMessageSentCallback = null
        onChatStatusCallback = null
    }
}
