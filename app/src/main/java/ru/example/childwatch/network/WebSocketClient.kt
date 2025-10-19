package ru.example.childwatch.network

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * WebSocketClient for ChildWatch (Parent Device)
 * Handles real-time audio chunk reception via Socket.IO
 */
class WebSocketClient(
    private val serverUrl: String,
    private val childDeviceId: String
) {
    private var socket: Socket? = null
    private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
    
    // Track last processed sequence to prevent duplicates
    private var lastProcessedSequence = -1
    private var heartbeatJob: Job? = null
    private var reregistrationJob: Job? = null

    companion object {
        private const val TAG = "WebSocketClient"
        private const val CONNECTION_TIMEOUT = 20000L // 20 seconds
        private const val RECONNECTION_DELAY = 1000L // 1 second
        private const val RECONNECTION_DELAY_MAX = 5000L // 5 seconds max
        private const val PING_INTERVAL = 25000L // 25 seconds (heartbeat)
    }

    // Connection event handlers
    private val onConnect = Emitter.Listener {
        Log.d(TAG, "🟢 WebSocket connected")
        scope.launch {
            isConnected = true
            lastProcessedSequence = -1
            registerAsParent()
            onConnectedCallback?.invoke()
        }
    }

    private val onDisconnect = Emitter.Listener { args ->
        Log.d(TAG, "🔴 WebSocket disconnected. Reason: ${args.getOrNull(0)}")
        isConnected = false
        stopHeartbeat()
        lastProcessedSequence = -1
        onChildDisconnected?.invoke()
    }

    private val onConnectError = Emitter.Listener { args ->
        val error = args.getOrNull(0)
        Log.e(TAG, "❌ WebSocket connection error: $error")
        isConnected = false
        scope.launch {
            onErrorCallback?.invoke(error?.toString() ?: "Connection error")
        }
    }

    private val onRegistered = Emitter.Listener { args ->
        val data = args.getOrNull(0) as? JSONObject
        val success = data?.optBoolean("success") ?: false
        val deviceId = data?.optString("deviceId")
        
        if (success && deviceId == childDeviceId) {
            Log.d(TAG, "✅ Parent registered for device: $childDeviceId")
        } else {
            Log.e(TAG, "❌ Parent registration failed for device: $childDeviceId")
        }
    }

    private val onAudioChunk = Emitter.Listener { args ->
        try {
            // First argument should be metadata JSON
            val metadata = args.getOrNull(0) as? JSONObject
            // Second argument should be binary data
            val binaryData = args.getOrNull(1) as? ByteArray
            
            if (metadata != null && binaryData != null) {
                val sequence = metadata.optInt("sequence", -1)
                val timestamp = metadata.optLong("timestamp", System.currentTimeMillis())
                val deviceId = metadata.optString("deviceId")
                
                // Only process chunks for our device and prevent duplicates
                if (deviceId == childDeviceId) {
                    if (sequence < 0) {
                        Log.w(TAG, "Ignoring chunk with invalid sequence: $sequence")
                        return@Listener
                    }

                    if (sequence < lastProcessedSequence) {
                        Log.d(TAG, "Detected sequence reset ($sequence < $lastProcessedSequence). Accepting new stream.")
                    } else if (sequence == lastProcessedSequence) {
                        Log.d(TAG, "⏭️ Skipped duplicate chunk #$sequence")
                        return@Listener
                    }

                    lastProcessedSequence = sequence
                    Log.d(TAG, "🎧 Received audio chunk #$sequence (${binaryData.size} bytes)")
                    scope.launch {
                        onAudioChunkReceived?.invoke(binaryData, sequence, timestamp)
                    }
                }
            } else {
                Log.w(TAG, "Invalid audio chunk data received")
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

    private val onChildDisconnectedEvent = Emitter.Listener {
        Log.d(TAG, "👶 Child device disconnected")
        lastProcessedSequence = -1
        scope.launch {
            onChildDisconnected?.invoke()
        }
    }

    private val onPong = Emitter.Listener {
        Log.d(TAG, "🏓 Pong received")
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

                scope.launch {
                    onChatMessageCallback?.invoke(messageId, text, sender, timestamp)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling chat message", e)
        }
    }

    private val onChatMessageSent = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            val messageId = data?.optString("id") ?: ""
            val timestamp = data?.optLong("timestamp") ?: System.currentTimeMillis()
            val delivered = data?.optBoolean("delivered") ?: false

            Log.d(TAG, "✅ Chat message sent confirmation: id=$messageId, delivered=$delivered")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling chat message sent confirmation", e)
        }
    }

    private val onChatMessageError = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            val error = data?.optString("error") ?: "Unknown error"

            Log.e(TAG, "❌ Chat message error: $error")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling chat message error", e)
        }
    }

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
            Log.d(TAG, "Connecting to WebSocket: $serverUrl")

            val opts = IO.Options().apply {
                transports = arrayOf("websocket", "polling") // WebSocket preferred
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = RECONNECTION_DELAY
                reconnectionDelayMax = RECONNECTION_DELAY_MAX
                timeout = CONNECTION_TIMEOUT
                // Socket.IO will handle ping/pong automatically
            }

            socket = IO.socket(serverUrl, opts)

            // Connection event handlers
            socket?.on(Socket.EVENT_CONNECT, onConnect)
            socket?.on(Socket.EVENT_DISCONNECT, onDisconnect)
            socket?.on(Socket.EVENT_CONNECT_ERROR, onConnectError)
            socket?.on("registered", onRegistered)
            socket?.on("audio_chunk", onAudioChunk)
            socket?.on("critical_alert", onCriticalAlert)
            socket?.on("child_disconnected", onChildDisconnectedEvent)
            socket?.on("pong", onPong)
            socket?.on("chat_message", onChatMessage)
            socket?.on("chat_message_sent", onChatMessageSent)
            socket?.on("chat_message_error", onChatMessageError)

            socket?.connect()

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
            socket?.off("critical_alert")
            socket?.off()
            socket = null
            isConnected = false
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
            val registrationData = JSONObject().apply {
                put("deviceId", childDeviceId)
                put("parentId", "parent_${System.currentTimeMillis()}")
            }

            socket?.emit("register_parent", registrationData)
            Log.d(TAG, "📤 Parent registration sent for device: $childDeviceId, socketId: ${socket?.id()}")

            // Retry registration after 2 seconds to ensure it's received
            scope.launch {
                delay(2000)
                if (isConnected && socket != null) {
                    socket?.emit("register_parent", registrationData)
                    Log.d(TAG, "📤 Parent registration RETRY sent for device: $childDeviceId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error registering as parent", e)
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
                put("deviceId", childDeviceId)
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
     * Set callback for chat messages
     */
    fun setChatMessageCallback(callback: (messageId: String, text: String, sender: String, timestamp: Long) -> Unit) {
        onChatMessageCallback = callback
        Log.d(TAG, "✅ Chat message callback registered")
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected

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
     * Periodically re-register as parent every 30 seconds
     */
    private fun startPeriodicReregistration() {
        reregistrationJob?.cancel()
        reregistrationJob = scope.launch {
            delay(5000) // Wait 5 seconds before first re-registration
            while (isActive && isConnected) {
                try {
                    registerAsParent()
                    Log.d(TAG, "🔄 Periodic parent re-registration triggered")
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic re-registration failed", e)
                }
                delay(30000) // Re-register every 30 seconds
            }
        }
    }

    fun stopHeartbeat() {
        reregistrationJob?.cancel()
        reregistrationJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        stopHeartbeat()
        disconnect()
        scope.cancel()
        onAudioChunkReceived = null
        onChildDisconnected = null
        onConnectedCallback = null
        onErrorCallback = null
        onCriticalAlertCallback = null
    }
}
