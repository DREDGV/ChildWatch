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
            if (!isConnected) {
                Log.w(TAG, "Not connected - cannot send audio chunk")
                onError("Not connected to server")
                return
            }

            if (socket == null) {
                Log.e(TAG, "Socket is null")
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

            // Emit audio chunk with metadata and binary data separately
            // Socket.IO will encode binary data properly when sent as separate argument
            socket?.emit("audio_chunk", metadata, audioData)

            // Log every 10th chunk to reduce spam
            if (sequence % 10 == 0) {
                Log.d(TAG, "Sent audio chunk #$sequence (${audioData.size} bytes)")
            }

            onSuccess()

        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk", e)
            onError("Send error: ${e.message}")
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
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected

    // Event Handlers

    private val onConnect = Emitter.Listener {
        Log.d(TAG, "✅ WebSocket connected")

        // Register as child device
        val registerData = JSONObject().apply {
            put("deviceId", deviceId)
        }
        socket?.emit("register_child", registerData)
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
