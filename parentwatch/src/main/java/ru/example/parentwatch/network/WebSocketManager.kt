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
    private val chatMessageSentCallbacks = mutableSetOf<(String, Boolean, Long) -> Unit>()
    private val chatStatusCallbacks = mutableSetOf<(String, String, Long) -> Unit>()
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
        webSocketClient = WebSocketClient(serverUrl, deviceId).also { client ->
            client.setChatMessageCallback(::dispatchChatMessage)
            client.setChatMessageSentCallback(::dispatchChatMessageSent)
            client.setChatStatusCallback(::dispatchChatStatus)
            client.setCommandCallback(::dispatchCommand)
        }
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

        webSocketClient?.apply {
            setChatMessageCallback(::dispatchChatMessage)
            setChatMessageSentCallback(::dispatchChatMessageSent)
            setChatStatusCallback(::dispatchChatStatus)
            setCommandCallback(::dispatchCommand)
            connect(onConnected, onError)
        }
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

    private fun dispatchChatMessageSent(messageId: String, delivered: Boolean, timestamp: Long) {
        chatMessageSentCallbacks.forEach { listener ->
            listener(messageId, delivered, timestamp)
        }
    }

    private fun dispatchChatStatus(messageId: String, status: String, timestamp: Long) {
        chatStatusCallbacks.forEach { listener ->
            listener(messageId, status, timestamp)
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

    fun addChatMessageSentListener(callback: (String, Boolean, Long) -> Unit) {
        chatMessageSentCallbacks.add(callback)
        webSocketClient?.setChatMessageSentCallback(::dispatchChatMessageSent)
    }

    fun removeChatMessageSentListener(callback: (String, Boolean, Long) -> Unit) {
        chatMessageSentCallbacks.remove(callback)
    }

    fun clearChatMessageSentCallbacks() {
        chatMessageSentCallbacks.clear()
    }

    fun addChatStatusListener(callback: (String, String, Long) -> Unit) {
        chatStatusCallbacks.add(callback)
        webSocketClient?.setChatStatusCallback(::dispatchChatStatus)
    }

    fun removeChatStatusListener(callback: (String, String, Long) -> Unit) {
        chatStatusCallbacks.remove(callback)
    }

    fun clearChatStatusListeners() {
        chatStatusCallbacks.clear()
    }

    fun sendChatStatus(messageId: String, status: String, actor: String) {
        webSocketClient?.sendChatStatus(messageId, status, actor)
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
        chatMessageSentCallbacks.clear()
        chatStatusCallbacks.clear()
        commandCallbacks.clear()
        isInitialized = false
    }
}
