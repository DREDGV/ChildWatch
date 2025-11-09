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
    // Legacy single callback for backward compatibility
    private var chatMessageCallback: ((String, String, String, Long) -> Unit)? = null
    // Multiple listeners support (service + activities) - синхронизация с app/
    private val chatMessageListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(String, String, String, Long) -> Unit>()
    )
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
        // Always set dispatching callback to propagate to all listeners and legacy single
        webSocketClient?.setChatMessageCallback { id, text, sender, ts ->
            dispatchChatMessage(id, text, sender, ts)
        }
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
            // Ensure dispatching callback is set on each connect
            setChatMessageCallback { id, text, sender, ts ->
                dispatchChatMessage(id, text, sender, ts)
            }
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
        // Backward compatibility: override legacy single callback
        chatMessageCallback = callback
        // Make sure client keeps dispatching to all listeners + legacy
        webSocketClient?.setChatMessageCallback { id, text, sender, ts ->
            dispatchChatMessage(id, text, sender, ts)
        }
    }

    /**
     * Clear chat message callback
     */
    fun clearChatMessageCallback() {
        chatMessageCallback = null
        // Keep dispatching for registered listeners even if legacy cleared
        webSocketClient?.setChatMessageCallback { id, text, sender, ts ->
            dispatchChatMessage(id, text, sender, ts)
        }
    }

    /**
     * Add/remove message listeners (preferred API) - синхронизация с app/
     */
    fun addChatMessageListener(listener: (messageId: String, text: String, sender: String, timestamp: Long) -> Unit) {
        chatMessageListeners.add(listener)
        Log.d(TAG, "Chat message listener added. Total listeners: ${chatMessageListeners.size}")
        // Ensure client uses dispatching callback
        webSocketClient?.setChatMessageCallback { id, text, sender, ts ->
            dispatchChatMessage(id, text, sender, ts)
        }
    }

    fun removeChatMessageListener(listener: (messageId: String, text: String, sender: String, timestamp: Long) -> Unit) {
        chatMessageListeners.remove(listener)
        Log.d(TAG, "Chat message listener removed. Total listeners: ${chatMessageListeners.size}")
    }

    fun clearChatMessageListeners() {
        chatMessageListeners.clear()
        Log.d(TAG, "All chat message listeners cleared")
    }

    /**
     * Dispatch chat message to all registered listeners
     */
    private fun dispatchChatMessage(messageId: String, text: String, sender: String, timestamp: Long) {
        try {
            // Notify all registered listeners
            val snapshot = synchronized(chatMessageListeners) { chatMessageListeners.toList() }
            Log.d(TAG, "Dispatching chat message to ${snapshot.size} listeners: from=$sender, text=$text")
            snapshot.forEach { listener ->
                try {
                    listener(messageId, text, sender, timestamp)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in chat listener", e)
                }
            }
            // Also notify legacy single callback if present
            chatMessageCallback?.invoke(messageId, text, sender, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "dispatchChatMessage failed", e)
        }
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
        chatMessageListeners.clear()
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

