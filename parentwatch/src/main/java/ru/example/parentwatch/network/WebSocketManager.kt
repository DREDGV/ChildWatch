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
    private var currentServerUrl: String? = null
    private var currentDeviceId: String? = null
    // Legacy single callback for backward compatibility
    private var chatMessageCallback: ((String, String, String, Long) -> Unit)? = null
    // Multiple listeners support (service + activities) - синхронизация с app/
    private val chatMessageListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(String, String, String, Long) -> Unit>()
    )
    private val chatMessageSentListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(String, Boolean, Long) -> Unit>()
    )
    private val chatStatusListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(String, String, Long) -> Unit>()
    )
    private var chatMessageSentCallback: ((String, Boolean, Long) -> Unit)? = null
    private var chatStatusCallback: ((String, String, Long) -> Unit)? = null
    private var parentConnectedCallback: (() -> Unit)? = null
    private var parentDisconnectedCallback: (() -> Unit)? = null

    /**
     * Initialize WebSocket client
     */
    private var missedMessagesCallback: ((List<ChatMessage>) -> Unit)? = null

    fun initialize(context: Context, serverUrl: String, childDeviceId: String, onMissedMessages: ((List<ChatMessage>) -> Unit)? = null) {
        if (isInitialized && webSocketClient != null) {
            if (currentServerUrl == serverUrl && currentDeviceId == childDeviceId) {
                Log.d(TAG, "WebSocket already initialized")
                return
            }
            Log.d(TAG, "Reinitializing WebSocket for new target")
            cleanup()
        }

        Log.d(TAG, "Initializing WebSocket: $serverUrl with childDeviceId: $childDeviceId")
        missedMessagesCallback = onMissedMessages
        webSocketClient = WebSocketClient(serverUrl, childDeviceId, onMissedMessages = missedMessagesCallback)
        // Always set dispatching callback to propagate to all listeners and legacy single
        webSocketClient?.setChatMessageCallback { id, text, sender, ts ->
            dispatchChatMessage(id, text, sender, ts)
        }
        webSocketClient?.setChatMessageSentCallback { messageId, delivered, timestamp ->
            dispatchChatMessageSent(messageId, delivered, timestamp)
        }
        webSocketClient?.setChatStatusCallback { messageId, status, timestamp ->
            dispatchChatStatus(messageId, status, timestamp)
        }
        // Always dispatch incoming commands to all registered listeners.
        webSocketClient?.setCommandCallback { command, data ->
            dispatchCommand(command, data)
        }
        parentConnectedCallback?.let { webSocketClient?.setParentConnectedCallback(it) }
        parentDisconnectedCallback?.let { webSocketClient?.setParentDisconnectedCallback(it) }
        isInitialized = true
        currentServerUrl = serverUrl
        currentDeviceId = childDeviceId
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
            setCommandCallback { command, data ->
                dispatchCommand(command, data)
            }
            setChatMessageSentCallback { messageId, delivered, timestamp ->
                dispatchChatMessageSent(messageId, delivered, timestamp)
            }
            setChatStatusCallback { messageId, status, timestamp ->
                dispatchChatStatus(messageId, status, timestamp)
            }
            parentConnectedCallback?.let { setParentConnectedCallback(it) }
            parentDisconnectedCallback?.let { setParentDisconnectedCallback(it) }
            connect(onConnected, onError)
        }
    }

    fun ensureConnected(onReady: () -> Unit = {}, onError: (String) -> Unit = {}) {
        if (!isInitialized) {
            Log.e(TAG, "WebSocket not initialized. Call initialize() first")
            onError("WebSocket not initialized")
            return
        }
        if (isReady()) {
            onReady()
            return
        }
        webSocketClient?.setRegisteredCallback { onReady() }
        if (!isConnected()) {
            connect(onConnected = {}, onError = onError)
        } else {
            webSocketClient?.requestRegistration()
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
        val client = webSocketClient
        if (client == null) {
            onError("WebSocket not initialized")
            return
        }
        if (!client.isReady()) {
            onError("WebSocket not ready")
            return
        }
        client.sendChatMessage(messageId, text, sender, onSuccess, onError)
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

    private fun dispatchChatMessageSent(messageId: String, delivered: Boolean, timestamp: Long) {
        try {
            val snapshot = synchronized(chatMessageSentListeners) { chatMessageSentListeners.toList() }
            snapshot.forEach { listener ->
                try {
                    listener(messageId, delivered, timestamp)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in chat sent listener", e)
                }
            }
            chatMessageSentCallback?.invoke(messageId, delivered, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "dispatchChatMessageSent failed", e)
        }
    }

    private fun dispatchChatStatus(messageId: String, status: String, timestamp: Long) {
        try {
            val snapshot = synchronized(chatStatusListeners) { chatStatusListeners.toList() }
            snapshot.forEach { listener ->
                try {
                    listener(messageId, status, timestamp)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in chat status listener", e)
                }
            }
            chatStatusCallback?.invoke(messageId, status, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "dispatchChatStatus failed", e)
        }
    }

    fun setChatMessageSentCallback(callback: (messageId: String, delivered: Boolean, timestamp: Long) -> Unit) {
        chatMessageSentCallback = callback
        webSocketClient?.setChatMessageSentCallback { messageId, delivered, timestamp ->
            dispatchChatMessageSent(messageId, delivered, timestamp)
        }
    }

    fun clearChatMessageSentCallback() {
        chatMessageSentCallback = null
        webSocketClient?.setChatMessageSentCallback { messageId, delivered, timestamp ->
            dispatchChatMessageSent(messageId, delivered, timestamp)
        }
    }

    fun setChatStatusCallback(callback: (messageId: String, status: String, timestamp: Long) -> Unit) {
        chatStatusCallback = callback
        webSocketClient?.setChatStatusCallback { messageId, status, timestamp ->
            dispatchChatStatus(messageId, status, timestamp)
        }
    }

    fun clearChatStatusCallback() {
        chatStatusCallback = null
        webSocketClient?.setChatStatusCallback { messageId, status, timestamp ->
            dispatchChatStatus(messageId, status, timestamp)
        }
    }

    fun addChatMessageSentListener(listener: (messageId: String, delivered: Boolean, timestamp: Long) -> Unit) {
        chatMessageSentListeners.add(listener)
        webSocketClient?.setChatMessageSentCallback { messageId, delivered, timestamp ->
            dispatchChatMessageSent(messageId, delivered, timestamp)
        }
    }

    fun removeChatMessageSentListener(listener: (messageId: String, delivered: Boolean, timestamp: Long) -> Unit) {
        chatMessageSentListeners.remove(listener)
    }

    fun addChatStatusListener(listener: (messageId: String, status: String, timestamp: Long) -> Unit) {
        chatStatusListeners.add(listener)
        webSocketClient?.setChatStatusCallback { messageId, status, timestamp ->
            dispatchChatStatus(messageId, status, timestamp)
        }
    }

    fun removeChatStatusListener(listener: (messageId: String, status: String, timestamp: Long) -> Unit) {
        chatStatusListeners.remove(listener)
    }

    fun sendChatStatus(messageId: String, status: String, actor: String) {
        webSocketClient?.sendChatStatus(messageId, status, actor)
    }

    fun setParentConnectedCallback(callback: () -> Unit) {
        parentConnectedCallback = callback
        webSocketClient?.setParentConnectedCallback(callback)
    }

    fun setParentDisconnectedCallback(callback: () -> Unit) {
        parentDisconnectedCallback = callback
        webSocketClient?.setParentDisconnectedCallback(callback)
    }

    fun clearParentConnectedCallback() {
        parentConnectedCallback = null
        webSocketClient?.setParentConnectedCallback { }
    }

    fun clearParentDisconnectedCallback() {
        parentDisconnectedCallback = null
        webSocketClient?.setParentDisconnectedCallback { }
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

    fun isReady(): Boolean {
        return webSocketClient?.isReady() ?: false
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
        currentServerUrl = null
        currentDeviceId = null
        chatMessageCallback = null
        chatMessageSentCallback = null
        chatStatusCallback = null
        parentConnectedCallback = null
        parentDisconnectedCallback = null
        chatMessageSentListeners.clear()
        chatStatusListeners.clear()
        chatMessageListeners.clear()
        commandListeners.clear()
    }
    
    private val commandListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(String, org.json.JSONObject?) -> Unit>()
    )

    private fun dispatchCommand(command: String, data: org.json.JSONObject?) {
        val snapshot = synchronized(commandListeners) { commandListeners.toList() }
        snapshot.forEach { listener ->
            try {
                listener(command, data)
            } catch (e: Exception) {
                Log.e(TAG, "Command listener failed", e)
            }
        }
    }

    fun addCommandListener(callback: (command: String, data: org.json.JSONObject?) -> Unit) {
        commandListeners.add(callback)
        webSocketClient?.setCommandCallback { command, data ->
            dispatchCommand(command, data)
        }
        Log.d(TAG, "Command listener added. Total listeners: ${commandListeners.size}")
    }

    fun removeCommandListener(callback: ((command: String, data: org.json.JSONObject?) -> Unit)? = null) {
        if (callback == null) {
            commandListeners.clear()
        } else {
            commandListeners.remove(callback)
        }
        Log.d(TAG, "Command listener removed. Total listeners: ${commandListeners.size}")
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
    fun setPhotoRequestCallback(callback: (requestId: String, targetDevice: String, cameraFacing: String) -> Unit) {
        webSocketClient?.onRequestPhotoCallback = callback
    }
}

