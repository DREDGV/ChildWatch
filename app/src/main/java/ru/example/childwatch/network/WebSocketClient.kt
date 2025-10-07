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
    private var onAudioChunkReceived: ((ByteArray, Int, Long) -> Unit)? = null
    private var onChildDisconnected: (() -> Unit)? = null
    private var onConnectedCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "WebSocketClient"
        private const val CONNECTION_TIMEOUT = 20000L // 20 seconds
        private const val RECONNECTION_DELAY = 1000L // 1 second
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
                timeout = CONNECTION_TIMEOUT
            }

            socket = IO.socket(serverUrl, opts)

            // Connection event handlers
            socket?.on(Socket.EVENT_CONNECT, onConnect)
            socket?.on(Socket.EVENT_DISCONNECT, onDisconnect)
            socket?.on(Socket.EVENT_CONNECT_ERROR, onConnectError)
            socket?.on("registered", onRegistered)
            socket?.on("audio_chunk", onAudioChunk)
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

    private val onDisconnect = Emitter.Listener {
        isConnected = false
        Log.w(TAG, "⚠️ WebSocket disconnected")
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
