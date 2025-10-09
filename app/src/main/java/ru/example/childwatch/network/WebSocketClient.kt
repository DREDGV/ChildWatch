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
    
    // Track last processed sequence to prevent duplicates
    private var lastProcessedSequence = -1

    companion object {
        private const val TAG = "WebSocketClient"
        private const val CONNECTION_TIMEOUT = 20000L // 20 seconds
        private const val RECONNECTION_DELAY = 1000L // 1 second
        private const val RECONNECTION_DELAY_MAX = 5000L // 5 seconds max
        private const val PING_INTERVAL = 25000L // 25 seconds (heartbeat)
        private const val PING_TIMEOUT = 60000L // 60 seconds
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
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected

    // Event Handlers

    private val onConnect = Emitter.Listener {
        Log.d(TAG, "✅ WebSocket connected")

        // Register as parent device
        val registerData = JSONObject().apply {
            put("childDeviceId", childDeviceId)
        }
        socket?.emit("register_parent", registerData)
    }

    private val onDisconnect = Emitter.Listener { args ->
        isConnected = false
        val reason = args.getOrNull(0)?.toString() ?: "unknown"
        Log.w(TAG, "⚠️ WebSocket disconnected - reason: $reason")

        // Log different disconnect reasons
        when (reason) {
            "io server disconnect" -> Log.w(TAG, "  → Server closed the connection")
            "io client disconnect" -> Log.w(TAG, "  → Client closed the connection")
            "ping timeout" -> Log.w(TAG, "  → Ping timeout (connection lost)")
            "transport close" -> Log.w(TAG, "  → Transport layer closed (network issue)")
            "transport error" -> Log.w(TAG, "  → Transport error occurred")
            else -> Log.w(TAG, "  → Unknown disconnect reason: $reason")
        }
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
                val monitoringId = data.optString("childDeviceId", "")

                isConnected = true
                Log.d(TAG, "✅ Registered as $role device: monitoring $monitoringId")

                // Call onConnected callback after successful registration
                onConnectedCallback?.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing registration response", e)
            onErrorCallback?.invoke("Registration error: ${e.message}")
        }
    }

    private val onAudioChunk = Emitter.Listener { args ->
        try {
            if (args.size < 2) {
                Log.w(TAG, "Audio chunk missing arguments (expected 2, got ${args.size})")
                return@Listener
            }

            // args[0] = metadata (JSONObject), args[1] = binary data (ByteArray)
            val metadata = args[0] as? JSONObject
            val binaryData = args[1] as? ByteArray

            if (metadata == null || binaryData == null) {
                Log.w(TAG, "Invalid audio chunk format: metadata=${metadata != null}, binary=${binaryData != null}")
                return@Listener
            }

            val deviceId = metadata.optString("deviceId", "")
            val sequence = metadata.optInt("sequence", 0)
            val timestamp = metadata.optLong("timestamp", 0)

            // Prevent duplicate chunks
            if (sequence <= lastProcessedSequence) {
                Log.w(TAG, "⚠️ Duplicate chunk #$sequence detected (last: $lastProcessedSequence) - ignoring")
                return@Listener
            }

            lastProcessedSequence = sequence

            // Log every 10th chunk to reduce spam
            if (sequence % 10 == 0) {
                Log.d(TAG, "🎧 Received audio chunk #$sequence from $deviceId (${binaryData.size} bytes)")
            }

            // Forward to callback
            onAudioChunkReceived?.invoke(binaryData, sequence, timestamp)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling audio chunk", e)
        }
    }

    private val onChildDisconnectedEvent = Emitter.Listener { args ->
        try {
            Log.w(TAG, "📱 Child device disconnected")
            onChildDisconnected?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling child_disconnected", e)
        }
    }

    private val onCriticalAlert = Emitter.Listener { args ->
        try {
            if (args.isNotEmpty()) {
                val data = args[0] as JSONObject
                val alert = CriticalAlertMessage(
                    id = data.optLong("id", 0),
                    eventType = data.optString("eventType", ""),
                    severity = data.optString("severity", ""),
                    message = data.optString("message", ""),
                    metadata = data.optString("metadata", null),
                    createdAt = data.optLong("createdAt", System.currentTimeMillis())
                )
                Log.d(TAG, "🚨 Critical alert received: ${alert.eventType} - ${alert.severity}")
                onCriticalAlertCallback?.invoke(alert)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling critical alert", e)
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

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next() as String
            map[key] = obj.get(key)
        }
        return map
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
     * Cleanup
     */
    fun cleanup() {
        stopHeartbeat()
        disconnect()
        scope.cancel()
    }
}
