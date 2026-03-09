package ru.example.childwatch.network

import android.content.Context
import android.util.Log
import org.json.JSONObject
import ru.example.childwatch.chat.ChatMessage

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
    // Multiple listeners support (service + activities)
    private val chatMessageListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(String, String, String, Long) -> Unit>()
    )
    private val chatMessageSentListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(String, Boolean, Long) -> Unit>()
    )
    private val chatStatusListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(String, String, Long) -> Unit>()
    )
    private val chatStatusAckListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(String, String, Long) -> Unit>()
    )
    private var chatMessageSentCallback: ((String, Boolean, Long) -> Unit)? = null
    private var chatStatusCallback: ((String, String, Long) -> Unit)? = null
    private var chatStatusAckCallback: ((String, String, Long) -> Unit)? = null
    private var childConnectedCallback: (() -> Unit)? = null
    private var childDisconnectedCallback: (() -> Unit)? = null

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
        webSocketClient?.setChatStatusAckCallback { messageId, status, timestamp ->
            dispatchChatStatusAck(messageId, status, timestamp)
        }
        childConnectedCallback?.let { webSocketClient?.setChildConnectedCallback(it) }
        childDisconnectedCallback?.let { webSocketClient?.setChildDisconnectedCallback(it) }
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
            setChatMessageSentCallback { messageId, delivered, timestamp ->
                dispatchChatMessageSent(messageId, delivered, timestamp)
            }
            setChatStatusCallback { messageId, status, timestamp ->
                dispatchChatStatus(messageId, status, timestamp)
            }
            setChatStatusAckCallback { messageId, status, timestamp ->
                dispatchChatStatusAck(messageId, status, timestamp)
            }
            childConnectedCallback?.let { setChildConnectedCallback(it) }
            childDisconnectedCallback?.let { setChildDisconnectedCallback(it) }
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
     * Add/remove message listeners (preferred API)
     */
    fun addChatMessageListener(listener: (messageId: String, text: String, sender: String, timestamp: Long) -> Unit) {
        chatMessageListeners.add(listener)
        // Ensure client uses dispatching callback
        webSocketClient?.setChatMessageCallback { id, text, sender, ts ->
            dispatchChatMessage(id, text, sender, ts)
        }
    }

    fun removeChatMessageListener(listener: (messageId: String, text: String, sender: String, timestamp: Long) -> Unit) {
        chatMessageListeners.remove(listener)
    }

    fun clearChatMessageListeners() {
        chatMessageListeners.clear()
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

    private fun dispatchChatMessage(messageId: String, text: String, sender: String, timestamp: Long) {
        try {
            // Notify all registered listeners
            val snapshot = synchronized(chatMessageListeners) { chatMessageListeners.toList() }
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

    private fun dispatchChatStatusAck(messageId: String, status: String, timestamp: Long) {
        try {
            val snapshot = synchronized(chatStatusAckListeners) { chatStatusAckListeners.toList() }
            snapshot.forEach { listener ->
                try {
                    listener(messageId, status, timestamp)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in chat status ack listener", e)
                }
            }
            chatStatusAckCallback?.invoke(messageId, status, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "dispatchChatStatusAck failed", e)
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

    fun setChatStatusAckCallback(callback: (messageId: String, status: String, timestamp: Long) -> Unit) {
        chatStatusAckCallback = callback
        webSocketClient?.setChatStatusAckCallback { messageId, status, timestamp ->
            dispatchChatStatusAck(messageId, status, timestamp)
        }
    }

    fun clearChatStatusAckCallback() {
        chatStatusAckCallback = null
        webSocketClient?.setChatStatusAckCallback { messageId, status, timestamp ->
            dispatchChatStatusAck(messageId, status, timestamp)
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

    fun addChatStatusAckListener(listener: (messageId: String, status: String, timestamp: Long) -> Unit) {
        chatStatusAckListeners.add(listener)
        webSocketClient?.setChatStatusAckCallback { messageId, status, timestamp ->
            dispatchChatStatusAck(messageId, status, timestamp)
        }
    }

    fun removeChatStatusAckListener(listener: (messageId: String, status: String, timestamp: Long) -> Unit) {
        chatStatusAckListeners.remove(listener)
    }

    fun sendChatStatus(messageId: String, status: String, actor: String): Boolean {
        return webSocketClient?.sendChatStatus(messageId, status, actor) == true
    }

    fun setChildConnectedCallback(callback: () -> Unit) {
        childConnectedCallback = callback
        webSocketClient?.setChildConnectedCallback(callback)
    }

    fun setChildDisconnectedCallback(callback: () -> Unit) {
        childDisconnectedCallback = callback
        webSocketClient?.setChildDisconnectedCallback(callback)
    }

    fun clearChildConnectedCallback() {
        childConnectedCallback = null
        webSocketClient?.setChildConnectedCallback { }
    }

    fun clearChildDisconnectedCallback() {
        childDisconnectedCallback = null
        webSocketClient?.setChildDisconnectedCallback { }
    }

    /**
     * Send command to child device
     */
    fun sendCommand(
        commandType: String,
        data: JSONObject? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        webSocketClient?.sendCommand(commandType, data, onSuccess, onError)
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

    // Photo capture callbacks
    private var photoReceivedCallback: ((photoBase64: String, requestId: String, timestamp: Long) -> Unit)? = null
    private var photoErrorCallback: ((requestId: String, error: String) -> Unit)? = null
    private val photoReceivedListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(String, String, Long) -> Unit>()
    )
    private val photoErrorListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(String, String) -> Unit>()
    )

    /**
     * Request remote photo capture
     */
    fun requestPhoto(
        targetDevice: String,
        cameraFacing: String = "back",
        requestId: String = java.util.UUID.randomUUID().toString(),
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (!isReady()) {
            onError("WebSocket not ready")
            return
        }

        try {
            val data = JSONObject().apply {
                put("targetDevice", targetDevice)
                put("requestId", requestId)
                put("camera", cameraFacing)
            }

            webSocketClient?.emit("request_photo", data)
            onSuccess()
            Log.d(TAG, "Photo request sent: requestId=$requestId, target=$targetDevice")
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting photo", e)
            onError(e.message ?: "Unknown error")
        }
    }

    /**
     * Set photo received callback
     */
    fun setPhotoReceivedCallback(callback: (photoBase64: String, requestId: String, timestamp: Long) -> Unit) {
        photoReceivedCallback = callback
        webSocketClient?.onPhotoReceived = { photoBase64, requestId, timestamp ->
            dispatchPhotoReceived(photoBase64, requestId, timestamp)
        }
    }

    /**
     * Set photo error callback
     */
    fun setPhotoErrorCallback(callback: (requestId: String, error: String) -> Unit) {
        photoErrorCallback = callback
        webSocketClient?.onPhotoError = { requestId, error ->
            dispatchPhotoError(requestId, error)
        }
    }

    fun addPhotoReceivedListener(listener: (photoBase64: String, requestId: String, timestamp: Long) -> Unit) {
        photoReceivedListeners.add(listener)
        webSocketClient?.onPhotoReceived = { photoBase64, requestId, timestamp ->
            dispatchPhotoReceived(photoBase64, requestId, timestamp)
        }
    }

    fun removePhotoReceivedListener(listener: (photoBase64: String, requestId: String, timestamp: Long) -> Unit) {
        photoReceivedListeners.remove(listener)
    }

    fun addPhotoErrorListener(listener: (requestId: String, error: String) -> Unit) {
        photoErrorListeners.add(listener)
        webSocketClient?.onPhotoError = { requestId, error ->
            dispatchPhotoError(requestId, error)
        }
    }

    fun removePhotoErrorListener(listener: (requestId: String, error: String) -> Unit) {
        photoErrorListeners.remove(listener)
    }

    private fun dispatchPhotoReceived(photoBase64: String, requestId: String, timestamp: Long) {
        val snapshot = synchronized(photoReceivedListeners) { photoReceivedListeners.toList() }
        snapshot.forEach { listener ->
            try {
                listener(photoBase64, requestId, timestamp)
            } catch (e: Exception) {
                Log.e(TAG, "Error in photo listener", e)
            }
        }
        photoReceivedCallback?.invoke(photoBase64, requestId, timestamp)
    }

    private fun dispatchPhotoError(requestId: String, error: String) {
        val snapshot = synchronized(photoErrorListeners) { photoErrorListeners.toList() }
        snapshot.forEach { listener ->
            try {
                listener(requestId, error)
            } catch (e: Exception) {
                Log.e(TAG, "Error in photo error listener", e)
            }
        }
        photoErrorCallback?.invoke(requestId, error)
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
        childConnectedCallback = null
        childDisconnectedCallback = null
        chatMessageSentListeners.clear()
        chatStatusListeners.clear()
        photoReceivedCallback = null
        photoErrorCallback = null
        photoReceivedListeners.clear()
        photoErrorListeners.clear()
    }
}
