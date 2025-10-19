package ru.example.parentwatch.network

import android.content.Context
import android.util.Log

/**
 * Singleton WebSocket Manager for ParentWatch
 * Manages a single WebSocket connection shared across the app
 */
object WebSocketManager {
    private const val TAG = "WebSocketManager"

    private var webSocketClient: WebSocketClient? = null
    private var isInitialized = false
    private val chatMessageCallbacks = mutableSetOf<(String, String, String, Long) -> Unit>()
    private val commandCallbacks = mutableSetOf<(String, org.json.JSONObject?) -> Unit>()

    /**
     * Initialize WebSocket client
     */
    fun initialize(context: Context, serverUrl: String, deviceId: String) {
        if (isInitialized && webSocketClient != null) {
            Log.d(TAG, "WebSocket already initialized")
            return
        }

        Log.d(TAG, "Initializing WebSocket: $serverUrl with deviceId: $deviceId")
        webSocketClient = WebSocketClient(serverUrl, deviceId)
        webSocketClient?.setChatMessageCallback(::dispatchChatMessage)
        webSocketClient?.setCommandCallback(::dispatchCommand)
        isInitialized = true
    }

    /**
     * Connect to WebSocket server
     */
    fun connect(onConnected: () -> Unit = {}, onError: (String) -> Unit = {}) {
        if (!isInitialized) {
            Log.e(TAG, "WebSocket not initialized. Call initialize() first")
            onError("WebSocket not initialized")
            return
        }

        webSocketClient?.setChatMessageCallback(::dispatchChatMessage)
        webSocketClient?.setCommandCallback(::dispatchCommand)
        webSocketClient?.connect(onConnected, onError)
    }

    /**
     * Disconnect from WebSocket server
     */
    fun disconnect() {
        webSocketClient?.disconnect()
    }

    /**
     * Send chat message
     */

    private fun dispatchChatMessage(messageId: String, text: String, sender: String, timestamp: Long) {
        chatMessageCallbacks.forEach { listener ->
            listener(messageId, text, sender, timestamp)
        }
    }

    fun addChatMessageListener(callback: (messageId: String, text: String, sender: String, timestamp: Long) -> Unit) {
        chatMessageCallbacks.add(callback)
        webSocketClient?.setChatMessageCallback(::dispatchChatMessage)
    }

    fun removeChatMessageListener(callback: (messageId: String, text: String, sender: String, timestamp: Long) -> Unit) {
        chatMessageCallbacks.remove(callback)
    }

    fun clearChatMessageCallback() {
        chatMessageCallbacks.clear()
    }

    fun sendChatMessage(
        messageId: String,
        text: String,
        sender: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        webSocketClient?.sendChatMessage(messageId, text, sender, onSuccess, onError)
    }

    /**
     * Set chat message callback
     */
    fun setChatMessageCallback(callback: (messageId: String, text: String, sender: String, timestamp: Long) -> Unit) {
        chatMessageCallbacks.clear()
        chatMessageCallbacks.add(callback)
        webSocketClient?.setChatMessageCallback(::dispatchChatMessage)
    }
    /**
     * Check if connected
     */
    fun isConnected(): Boolean {
        return webSocketClient?.isConnected() ?: false
    }

    /**
     * Command handling
     */
    private fun dispatchCommand(command: String, data: org.json.JSONObject?) {
        commandCallbacks.forEach { listener ->
            listener(command, data)
        }
    }

    fun addCommandListener(callback: (command: String, data: org.json.JSONObject?) -> Unit) {
        commandCallbacks.add(callback)
        webSocketClient?.setCommandCallback(::dispatchCommand)
    }

    fun removeCommandListener(callback: (command: String, data: org.json.JSONObject?) -> Unit) {
        commandCallbacks.remove(callback)
    }

    fun clearCommandCallbacks() {
        commandCallbacks.clear()
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        webSocketClient?.cleanup()
        webSocketClient = null
        chatMessageCallbacks.clear()
        commandCallbacks.clear()
        isInitialized = false
    }
}
