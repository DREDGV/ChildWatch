package ru.example.parentwatch.network

import android.content.Context
import android.util.Log
import org.json.JSONObject
import ru.example.parentwatch.chat.ChatMessage

/**
 * Singleton WebSocket Manager for ChildWatch
 * Manages a single WebSocket connection shared across the app
 */
object WebSocketManager {
    private const val TAG = "WebSocketManager"

    private var webSocketClient: WebSocketClient? = null
    private var isInitialized = false
    private var chatMessageCallback: ((String, String, String, Long) -> Unit)? = null
    private var chatMessageSentCallback: ((String, Boolean, Long) -> Unit)? = null
    private var chatStatusCallback: ((String, String, Long) -> Unit)? = null

    /**
     * Initialize WebSocket client
     */
    private var missedMessagesCallback: ((List<ChatMessage>) -> Unit)? = null

    fun initialize(context: Context, serverUrl: String, childDeviceId: String, onMissedMessages: ((List<ChatMessage>) -> Unit)? = null) {
        if (isInitialized && webSocketClient != null) {
            Log.d(TAG, "WebSocket already initialized")
            return
        }

        Log.d(TAG, "Initializing WebSocket: $serverUrl with childDeviceId: $childDeviceId")
        missedMessagesCallback = onMissedMessages
        webSocketClient = WebSocketClient(serverUrl, childDeviceId, onMissedMessages = missedMessagesCallback)
        chatMessageCallback?.let { webSocketClient?.setChatMessageCallback(it) }
        chatMessageSentCallback?.let { webSocketClient?.setChatMessageSentCallback(it) }
        chatStatusCallback?.let { webSocketClient?.setChatStatusCallback(it) }
        // Ensure previously registered command listener is also applied after initialization
        commandCallback?.let { webSocketClient?.setCommandCallback(it) }
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
            chatMessageCallback?.let { setChatMessageCallback(it) }
            chatMessageSentCallback?.let { setChatMessageSentCallback(it) }
            chatStatusCallback?.let { setChatStatusCallback(it) }
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
        chatMessageCallback = callback
        webSocketClient?.setChatMessageCallback(callback)
    }

    /**
     * Clear chat message callback
     */
    fun clearChatMessageCallback() {
        chatMessageCallback = null
        webSocketClient?.setChatMessageCallback { _, _, _, _ -> }
    }

    fun setChatMessageSentCallback(callback: (messageId: String, delivered: Boolean, timestamp: Long) -> Unit) {
        chatMessageSentCallback = callback
        webSocketClient?.setChatMessageSentCallback(callback)
    }

    fun clearChatMessageSentCallback() {
        chatMessageSentCallback = null
        webSocketClient?.setChatMessageSentCallback { _, _, _ -> }
    }

    fun setChatStatusCallback(callback: (messageId: String, status: String, timestamp: Long) -> Unit) {
        chatStatusCallback = callback
        webSocketClient?.setChatStatusCallback(callback)
    }

    fun clearChatStatusCallback() {
        chatStatusCallback = null
        webSocketClient?.setChatStatusCallback { _, _, _ -> }
    }

    fun sendChatStatus(messageId: String, status: String, actor: String) {
        webSocketClient?.sendChatStatus(messageId, status, actor)
    }

    /**
     * Set typing indicator callback
     */
    fun setTypingCallback(callback: (isTyping: Boolean) -> Unit) {
        webSocketClient?.setTypingCallback(callback)
    }

    /**
     * Send typing start/stop status
     */
    fun sendTypingStatus(isTyping: Boolean) {
        webSocketClient?.sendTypingStatus(isTyping)
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean {
        return webSocketClient?.isConnected() ?: false
    }

    /**
     * Get WebSocket client instance
     */
    fun getClient(): WebSocketClient? {
        return webSocketClient
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        webSocketClient?.cleanup()
        webSocketClient = null
        isInitialized = false
        chatMessageCallback = null
        chatMessageSentCallback = null
        chatStatusCallback = null
    }
    
    // Legacy API compatibility methods
    fun addChatMessageListener(callback: (messageId: String, text: String, sender: String, timestamp: Long) -> Unit) {
        setChatMessageCallback(callback)
    }
    
    fun removeChatMessageListener(callback: ((String, String, String, Long) -> Unit)? = null) {
        clearChatMessageCallback()
    }
    
    private var commandCallback: ((String, org.json.JSONObject?) -> Unit)? = null
    
    fun addCommandListener(callback: (command: String, data: org.json.JSONObject?) -> Unit) {
        commandCallback = callback
        webSocketClient?.setCommandCallback(callback)
        Log.d(TAG, "Command listener added")
    }
    
    fun removeCommandListener() {
        commandCallback = null
        Log.d(TAG, "Command listener removed")
    }
    
    /**
     * Set callback for parent location updates
     */
    fun setParentLocationCallback(callback: (parentId: String, lat: Double, lon: Double, accuracy: Float, timestamp: Long, speed: Float, bearing: Float) -> Unit) {
        webSocketClient?.onParentLocationCallback = callback
    }
    
    /**
     * Set callback for photo requests
     */
    fun setPhotoRequestCallback(callback: (requestId: String, targetDevice: String) -> Unit) {
        webSocketClient?.onRequestPhotoCallback = callback
    }
}

