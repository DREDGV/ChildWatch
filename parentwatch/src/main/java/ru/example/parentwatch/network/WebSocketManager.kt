package ru.example.parentwatch.network

import android.content.Context
import android.util.Log
import org.json.JSONObject
import ru.childwatch.shared.attention.AttentionSignalContract
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
    private val chatStatusAckListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(String, String, Long) -> Unit>()
    )
    private var chatMessageSentCallback: ((String, Boolean, Long) -> Unit)? = null
    private var chatStatusCallback: ((String, String, Long) -> Unit)? = null
    private var chatStatusAckCallback: ((String, String, Long) -> Unit)? = null
    private var parentConnectedCallback: (() -> Unit)? = null
    private var parentDisconnectedCallback: (() -> Unit)? = null
    private var photoRequestCallback: ((String, String, String) -> Unit)? = null
    private val photoRequestListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(String, String, String) -> Unit>()
    )
    private val attentionStartListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(JSONObject) -> Unit>()
    )
    private val attentionStopListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(JSONObject) -> Unit>()
    )
    private val attentionStatusListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(JSONObject) -> Unit>()
    )
    private val pendingAttentionStatuses = mutableListOf<JSONObject>()
    private val chatV2MessageListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(JSONObject) -> Unit>()
    )
    private val chatV2ReceiptListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(JSONObject) -> Unit>()
    )
    private val chatV2ErrorListeners = java.util.Collections.synchronizedSet(
        mutableSetOf<(JSONObject) -> Unit>()
    )
    private val chatV2Subscriptions = java.util.Collections.synchronizedMap(
        mutableMapOf<String, Int>()
    )

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
        webSocketClient = WebSocketClient(
            serverUrl,
            childDeviceId,
            onMissedMessages = missedMessagesCallback,
            context = context
        )
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
        // Always dispatch incoming commands to all registered listeners.
        webSocketClient?.setCommandCallback { command, data ->
            dispatchCommand(command, data)
        }
        webSocketClient?.onRequestPhotoCallback = { requestId, targetDevice, cameraFacing ->
            dispatchPhotoRequest(requestId, targetDevice, cameraFacing)
        }
        parentConnectedCallback?.let { webSocketClient?.setParentConnectedCallback(it) }
        parentDisconnectedCallback?.let { webSocketClient?.setParentDisconnectedCallback(it) }
        configureAttentionCallbacks()
        configureChatV2Callbacks()
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
            onRequestPhotoCallback = { requestId, targetDevice, cameraFacing ->
                dispatchPhotoRequest(requestId, targetDevice, cameraFacing)
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
            parentConnectedCallback?.let { setParentConnectedCallback(it) }
            parentDisconnectedCallback?.let { setParentDisconnectedCallback(it) }
            configureAttentionCallbacks()
            configureChatV2Callbacks()
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
        var delivered = false
        webSocketClient?.setRegisteredCallback {
            if (delivered) return@setRegisteredCallback
            delivered = true
            onReady()
        }
        if (!isConnected()) {
            connect(onConnected = {}, onError = onError)
        } else {
            webSocketClient?.requestRegistration()
        }
    }

    /**
     * Rebuild the socket after HTTP registration or token rotation so the next
     * Socket.IO handshake always uses the latest persisted credential.
     */
    fun reconnectWithCurrentAuth(
        onReady: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (!isInitialized || webSocketClient == null) {
            Log.d(TAG, "WebSocket not initialized yet; the first connection will use current auth")
            return
        }

        var delivered = false
        webSocketClient?.setRegisteredCallback {
            if (delivered) return@setRegisteredCallback
            delivered = true
            onReady()
        }
        webSocketClient?.disconnect()
        connect(onConnected = {}, onError = onError)
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
        authorDeviceId: String? = null,
        authorDisplayName: String? = null,
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
        client.sendChatMessage(
            messageId,
            text,
            sender,
            authorDeviceId,
            authorDisplayName,
            onSuccess,
            onError
        )
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
    fun cleanup(preserveChatV2: Boolean = false) {
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
        photoRequestCallback = null
        chatMessageSentListeners.clear()
        chatStatusListeners.clear()
        chatMessageListeners.clear()
        commandListeners.clear()
        photoRequestListeners.clear()
        if (!preserveChatV2) {
            chatV2MessageListeners.clear()
            chatV2ReceiptListeners.clear()
            chatV2ErrorListeners.clear()
            chatV2Subscriptions.clear()
        }
    }

    private fun configureChatV2Callbacks() {
        webSocketClient?.setChatV2Callbacks(
            onMessage = { dispatchChatV2(chatV2MessageListeners, it, "message") },
            onReceiptUpdated = { dispatchChatV2(chatV2ReceiptListeners, it, "receipt") },
            onError = { dispatchChatV2(chatV2ErrorListeners, it, "error") },
            onTransportReady = ::restoreChatV2Subscriptions
        )
    }

    private fun dispatchChatV2(
        listeners: MutableSet<(JSONObject) -> Unit>,
        payload: JSONObject,
        event: String
    ) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { listener ->
            runCatching { listener(payload) }
                .onFailure { Log.e(TAG, "Chat v2 $event listener failed", it) }
        }
    }

    fun addChatV2MessageListener(listener: (JSONObject) -> Unit) {
        chatV2MessageListeners.add(listener)
        configureChatV2Callbacks()
    }

    fun removeChatV2MessageListener(listener: (JSONObject) -> Unit) {
        chatV2MessageListeners.remove(listener)
    }

    fun addChatV2ReceiptListener(listener: (JSONObject) -> Unit) {
        chatV2ReceiptListeners.add(listener)
        configureChatV2Callbacks()
    }

    fun removeChatV2ReceiptListener(listener: (JSONObject) -> Unit) {
        chatV2ReceiptListeners.remove(listener)
    }

    fun addChatV2ErrorListener(listener: (JSONObject) -> Unit) {
        chatV2ErrorListeners.add(listener)
        configureChatV2Callbacks()
    }

    fun removeChatV2ErrorListener(listener: (JSONObject) -> Unit) {
        chatV2ErrorListeners.remove(listener)
    }

    fun subscribeChatV2(conversationId: String): Boolean {
        val normalized = conversationId.trim()
        if (normalized.isEmpty()) return false
        val shouldEmit = synchronized(chatV2Subscriptions) {
            val count = chatV2Subscriptions[normalized] ?: 0
            chatV2Subscriptions[normalized] = count + 1
            count == 0
        }
        return !shouldEmit || emitChatV2("chat_v2:subscribe", normalized)
    }

    fun unsubscribeChatV2(conversationId: String): Boolean {
        val normalized = conversationId.trim()
        if (normalized.isEmpty()) return false
        val shouldEmit = synchronized(chatV2Subscriptions) {
            val count = chatV2Subscriptions[normalized] ?: return@synchronized false
            if (count <= 1) chatV2Subscriptions.remove(normalized)
            else chatV2Subscriptions[normalized] = count - 1
            count <= 1
        }
        return !shouldEmit || emitChatV2("chat_v2:unsubscribe", normalized)
    }

    private fun restoreChatV2Subscriptions() {
        val snapshot = synchronized(chatV2Subscriptions) { chatV2Subscriptions.keys.toList() }
        snapshot.forEach { emitChatV2("chat_v2:subscribe", it) }
    }

    private fun emitChatV2(event: String, conversationId: String): Boolean {
        val client = webSocketClient?.takeIf { it.isReady() } ?: return false
        client.emit(event, JSONObject().put("conversationId", conversationId))
        return true
    }

    fun sendChatV2Message(
        conversationId: String,
        clientMessageId: String,
        text: String,
        clientSentAt: Long,
        callback: (JSONObject) -> Unit
    ): Boolean {
        val client = webSocketClient?.takeIf { it.isReady() } ?: return false
        val payload = JSONObject().apply {
            put("conversationId", conversationId)
            put("clientMessageId", clientMessageId)
            put("text", text)
            put("clientSentAt", clientSentAt)
        }
        return client.emitWithAck("chat_v2:send", payload, callback)
    }

    private fun configureAttentionCallbacks() {
        webSocketClient?.setAttentionCallbacks(
            onStart = { dispatchAttention(attentionStartListeners, it, "start") },
            onStop = { dispatchAttention(attentionStopListeners, it, "stop") },
            onStatus = { dispatchAttention(attentionStatusListeners, it, "status") },
            onTransportReady = ::flushPendingAttentionStatuses
        )
    }

    private fun dispatchAttention(
        listeners: MutableSet<(JSONObject) -> Unit>,
        payload: JSONObject,
        event: String
    ) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { listener ->
            runCatching { listener(payload) }
                .onFailure { Log.e(TAG, "Attention $event listener failed", it) }
        }
    }

    fun addAttentionStartListener(listener: (JSONObject) -> Unit) {
        attentionStartListeners.add(listener)
        configureAttentionCallbacks()
    }

    fun removeAttentionStartListener(listener: (JSONObject) -> Unit) {
        attentionStartListeners.remove(listener)
    }

    fun addAttentionStopListener(listener: (JSONObject) -> Unit) {
        attentionStopListeners.add(listener)
        configureAttentionCallbacks()
    }

    fun removeAttentionStopListener(listener: (JSONObject) -> Unit) {
        attentionStopListeners.remove(listener)
    }

    fun addAttentionStatusListener(listener: (JSONObject) -> Unit) {
        attentionStatusListeners.add(listener)
        configureAttentionCallbacks()
    }

    fun removeAttentionStatusListener(listener: (JSONObject) -> Unit) {
        attentionStatusListeners.remove(listener)
    }

    fun sendAttentionRequest(payload: JSONObject): Boolean =
        emitAttention(AttentionSignalContract.EVENT_REQUEST, payload)

    fun sendAttentionStopRequest(payload: JSONObject): Boolean =
        emitAttention(AttentionSignalContract.EVENT_STOP_REQUEST, payload)

    fun sendAttentionStatus(payload: JSONObject): Boolean {
        if (emitAttention(AttentionSignalContract.EVENT_STATUS, payload)) return true
        synchronized(pendingAttentionStatuses) {
            if (pendingAttentionStatuses.size >= 32) pendingAttentionStatuses.removeAt(0)
            pendingAttentionStatuses.add(JSONObject(payload.toString()))
        }
        return false
    }

    private fun flushPendingAttentionStatuses() {
        val client = webSocketClient?.takeIf { it.isReady() } ?: return
        val now = System.currentTimeMillis()
        val pending = synchronized(pendingAttentionStatuses) {
            pendingAttentionStatuses.filter {
                now - it.optLong("timestamp", now) <= AttentionSignalContract.MAX_TTL_MS
            }.also { pendingAttentionStatuses.clear() }
        }
        pending.forEach { client.emit(AttentionSignalContract.EVENT_STATUS, it) }
    }

    private fun emitAttention(event: String, payload: JSONObject): Boolean {
        val client = webSocketClient ?: return false
        if (!client.isReady()) return false
        client.emit(event, payload)
        return true
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
        photoRequestCallback = callback
        webSocketClient?.onRequestPhotoCallback = { requestId, targetDevice, cameraFacing ->
            dispatchPhotoRequest(requestId, targetDevice, cameraFacing)
        }
    }

    fun addPhotoRequestListener(callback: (requestId: String, targetDevice: String, cameraFacing: String) -> Unit) {
        photoRequestListeners.add(callback)
        webSocketClient?.onRequestPhotoCallback = { requestId, targetDevice, cameraFacing ->
            dispatchPhotoRequest(requestId, targetDevice, cameraFacing)
        }
    }

    fun removePhotoRequestListener(callback: ((requestId: String, targetDevice: String, cameraFacing: String) -> Unit)? = null) {
        if (callback == null) {
            photoRequestListeners.clear()
        } else {
            photoRequestListeners.remove(callback)
        }
    }

    private fun dispatchPhotoRequest(requestId: String, targetDevice: String, cameraFacing: String) {
        val snapshot = synchronized(photoRequestListeners) { photoRequestListeners.toList() }
        snapshot.forEach { listener ->
            try {
                listener(requestId, targetDevice, cameraFacing)
            } catch (e: Exception) {
                Log.e(TAG, "Photo request listener failed", e)
            }
        }
        photoRequestCallback?.invoke(requestId, targetDevice, cameraFacing)
    }
}
