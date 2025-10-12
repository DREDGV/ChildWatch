package ru.example.parentwatch.network

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URISyntaxException

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

    companion object {
        private const val TAG = "WebSocketClient"
        private const val CONNECTION_TIMEOUT = 20000L // 20 seconds
        private const val RECONNECTION_DELAY = 1000L // 1 second
    }

    /**
     * Connect to WebSocket server
     */
    fun connect(onConnected: () -> Unit = {}, onError: (String) -> Unit = {}) {
        try {
            Log.d(TAG, "Connecting to WebSocket: $serverUrl")

            val opts = IO.Options().apply {
                transports = arrayOf("websocket", "polling") // WebSocket preferred
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = RECONNECTION_DELAY
                timeout = CONNECTION_TIMEOUT
            }

            socket = IO.socket(serverUrl, opts)

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
            socket?.disconnect()
            socket?.off()
            socket = null
            isConnected = false
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
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
            Log.d(TAG, "📤 Attempting to send chunk #$sequence (${audioData.size} bytes)...")
            
            if (!isConnected) {
                Log.w(TAG, "❌ Not connected - cannot send audio chunk #$sequence")
                onError("Not connected to server")
                return
            }

            if (socket == null) {
                Log.e(TAG, "❌ Socket is null - cannot send chunk #$sequence")
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

            Log.d(TAG, "📋 Sending metadata: $metadata")

            // Emit audio chunk with metadata and binary data separately
            // Socket.IO will encode binary data properly when sent as separate argument
            socket?.emit("audio_chunk", metadata, audioData)

            Log.d(TAG, "✅ Chunk #$sequence emitted via Socket.IO (${audioData.size} bytes)")
            onSuccess()

        } catch (e: Exception) {
            Log.e(TAG, "💥 Error sending audio chunk #$sequence", e)
            onError("Send error: ${e.message}")
        }
    }

    /**
     * Send heartbeat/ping
     */
    fun sendPing() {
        try {
            Log.d(TAG, "💓 Sending heartbeat ping...")
            socket?.emit("ping")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error sending ping", e)
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
            Log.d(TAG, "💬 Sending chat message: $text")

            if (!isConnected) {
                Log.w(TAG, "❌ Not connected - cannot send chat message")
                onError("Not connected to server")
                return
            }

            if (socket == null) {
                Log.e(TAG, "❌ Socket is null - cannot send chat message")
                onError("Socket not initialized")
                return
            }

            // Create message data
            val messageData = JSONObject().apply {
                put("id", messageId)
                put("text", text)
                put("sender", sender)
                put("timestamp", System.currentTimeMillis())
                put("deviceId", deviceId)
            }

            Log.d(TAG, "📋 Sending chat message: $messageData")

            // Emit chat message
            socket?.emit("chat_message", messageData)

            Log.d(TAG, "✅ Chat message emitted via Socket.IO")
            onSuccess()

        } catch (e: Exception) {
            Log.e(TAG, "💥 Error sending chat message", e)
            onError("Send error: ${e.message}")
        }
    }

    /**
     * Set callback for receiving chat messages
     */
    private var onChatMessageReceived: ((String, String, String, Long) -> Unit)? = null

    fun setChatMessageCallback(callback: (messageId: String, text: String, sender: String, timestamp: Long) -> Unit) {
        onChatMessageReceived = callback
    }

    /**
     * Start heartbeat to maintain connection
     */
    fun startHeartbeat() {
        Log.d(TAG, "💓 Starting heartbeat...")
        scope.launch {
            while (isConnected) {
                try {
                    sendPing()
                    delay(30000) // Send ping every 30 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "💥 Heartbeat error", e)
                    break
                }
            }
        }
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected


    // Event Handlers

    private val onConnect = Emitter.Listener {
        Log.d(TAG, "✅ WebSocket connected")

        // Register as child device (also on reconnect)
        val registerData = JSONObject().apply {
            put("deviceId", deviceId)
        }
        socket?.emit("register_child", registerData)

        Log.d(TAG, "📤 Sent registration request")
    }

    private val onDisconnect = Emitter.Listener {
        isConnected = false
        Log.w(TAG, "⚠️ WebSocket disconnected - will auto-reconnect")

        // Socket.IO will automatically try to reconnect
        // We just need to mark as disconnected
    }

    private val onConnectError = Emitter.Listener { args ->
        isConnected = false
        val error = if (args.isNotEmpty()) args[0].toString() else "Unknown error"
        Log.e(TAG, "❌ Connection error: $error")
    }

    private val onRegistered = Emitter.Listener { args ->
        try {
            if (args.isNotEmpty()) {
                val data = args[0] as JSONObject
                val role = data.optString("role", "unknown")
                val registeredDeviceId = data.optString("deviceId", "")

                isConnected = true
                Log.d(TAG, "✅ Registered as $role device: $registeredDeviceId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing registration response", e)
        }
    }

    private val onParentConnected = Emitter.Listener { args ->
        try {
            Log.d(TAG, "👨‍👩‍👧 Parent device connected - ready to stream")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling parent_connected", e)
        }
    }

    private val onParentDisconnected = Emitter.Listener { args ->
        try {
            Log.d(TAG, "👨‍👩‍👧 Parent device disconnected - no listener")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling parent_disconnected", e)
        }
    }

    private val onPong = Emitter.Listener { args ->
        try {
            if (args.isNotEmpty()) {
                val data = args[0] as JSONObject
                val timestamp = data.optLong("timestamp", 0)
                val latency = System.currentTimeMillis() - timestamp

                if (latency > 0) {
                    Log.d(TAG, "🏓 Pong received (latency: ${latency}ms)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing pong", e)
        }
    }

    private val onChatMessage = Emitter.Listener { args ->
        try {
            if (args.isNotEmpty()) {
                val data = args[0] as JSONObject
                val messageId = data.optString("id", "")
                val text = data.optString("text", "")
                val sender = data.optString("sender", "")
                val timestamp = data.optLong("timestamp", System.currentTimeMillis())

                Log.d(TAG, "💬 Chat message received from $sender: $text")

                // Forward to callback
                onChatMessageReceived?.invoke(messageId, text, sender, timestamp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing chat message", e)
        }
    }

    private val onChatMessageSent = Emitter.Listener { args ->
        try {
            if (args.isNotEmpty()) {
                val data = args[0] as JSONObject
                val messageId = data.optString("id", "")
                val timestamp = data.optLong("timestamp", 0)

                Log.d(TAG, "✅ Chat message sent confirmation: $messageId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing chat_message_sent", e)
        }
    }

    /**
     * Start heartbeat job (keeps connection alive)
     */
    private var heartbeatJob: Job? = null

    fun startHeartbeat(intervalMs: Long = 30000L) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isConnected) {
                sendPing()
                delay(intervalMs)
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        Log.d(TAG, "🧹 Cleaning up WebSocket client...")
        stopHeartbeat()
        disconnect()
        scope.cancel()
    }
}
