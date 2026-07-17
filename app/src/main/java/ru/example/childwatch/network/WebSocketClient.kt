package ru.example.childwatch.network

import android.content.Context
import android.util.Log
import android.util.Base64
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import ru.childwatch.shared.attention.AttentionSignalContract
import ru.example.childwatch.chat.ChatMessage
import ru.example.childwatch.chat.ChatMessageRuntimeRegistry
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.example.childwatch.profile.ParentParticipantNameResolver
import ru.example.childwatch.utils.SecureSettingsManager
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocketClient for ChildWatch (Parent Device)
 * Handles real-time audio chunk reception via Socket.IO
 */
class WebSocketClient(
    private val serverUrl: String,
    private val childDeviceId: String,
    private val onMissedMessages: ((List<ChatMessage>) -> Unit)? = null,
    context: Context? = null
) {
    private var socket: Socket? = null
    private var isConnected = false
    private var isConnecting = false
    private var isRegistered = false
    private var registeredDeviceId: String = childDeviceId
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val appContext = context?.applicationContext
    private val effectiveContextResolver =
        appContext?.let { ParentEffectiveContextResolver(it) }
    private val participantNameResolver =
        appContext?.let { ParentParticipantNameResolver(it) }
    private val tokenManager = appContext?.let { TokenManager(it) }
    private val syncPrefs =
        appContext?.getSharedPreferences("chat_sync_state", Context.MODE_PRIVATE)

    // Callbacks
    data class CriticalAlertMessage(
        val id: Long,
        val eventType: String,
        val severity: String,
        val message: String,
        val metadata: String?,
        val createdAt: Long
    )

    private var onAudioChunkReceived: ((ByteArray, Int, Long, Int, Int, Int?, Boolean?, Long?) -> Unit)? = null
    private var onAudioCaptureErrorCallback: ((String, String, String, Long) -> Unit)? = null
    private var onChildConnected: (() -> Unit)? = null
    private var onChildDisconnected: (() -> Unit)? = null
    private var onCriticalAlertCallback: ((CriticalAlertMessage) -> Unit)? = null
    private var onConnectedCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onChatMessageCallback: ((String, String, String, Long) -> Unit)? = null
    private var onChatMessageSentCallback: ((String, Boolean, Long) -> Unit)? = null
    private var onChatStatusCallback: ((String, String, Long) -> Unit)? = null
    private var onChatStatusAckCallback: ((String, String, Long) -> Unit)? = null
    private var onRegisteredCallback: (() -> Unit)? = null
    var onPhotoQueuedCallback: ((requestId: String, deviceId: String, camera: String, timestamp: Long) -> Unit)? = null
    var onPhotoReceived: ((photoBase64: String, requestId: String, timestamp: Long) -> Unit)? = null
    var onPhotoError: ((requestId: String, error: String) -> Unit)? = null
    var onPhotoBusy: ((requestId: String, deviceId: String, ownerParentId: String, ownerDisplayName: String, timestamp: Long) -> Unit)? = null
    var onStreamTakeoverRequested: ((deviceId: String, requesterParentId: String, requesterDisplayName: String, timestamp: Long) -> Unit)? = null
    var onStreamForceReleased: ((deviceId: String, ownerParentId: String, ownerDisplayName: String, releasedByType: String, releasedByDisplayName: String, timestamp: Long) -> Unit)? = null
    private var onTypingCallback: ((isTyping: Boolean) -> Unit)? = null
    private var onAudioStreamResetCallback: (() -> Unit)? = null
    private var onAttentionStartCallback: ((JSONObject) -> Unit)? = null
    private var onAttentionStopCallback: ((JSONObject) -> Unit)? = null
    private var onAttentionStatusCallback: ((JSONObject) -> Unit)? = null
    private var onAttentionTransportReadyCallback: (() -> Unit)? = null
    
    // Track last processed sequence to prevent duplicates
    private var lastProcessedSequence = -1
    private var lastAudioChunkWallclock = 0L
    private var audioDeviceMismatchLogged = false
    private var audioPayloadTypeLogged = false
    private var heartbeatJob: Job? = null
    private var reregistrationJob: Job? = null
    private val pendingChatCallbacks = ConcurrentHashMap<String, PendingChatCallback>()
    private val recentChatIds =
        object : LinkedHashMap<String, Long>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 200
            }
        }
    @Volatile
    private var shouldRequestMissedMessagesAfterRegistration = true
    private var lastChatSyncTimestamp: Long
        get() = syncPrefs?.getLong(syncKey(), 0L) ?: 0L
        set(value) {
            syncPrefs?.edit()?.putLong(syncKey(), value)?.apply()
        }

    companion object {
        private const val TAG = "WebSocketClient"
        private const val CONNECTION_TIMEOUT = 15000L // 15 seconds (reduced from 20)
        private const val RECONNECTION_DELAY = 500L // 0.5 second (faster initial retry)
        private const val RECONNECTION_DELAY_MAX = 10000L // 10 seconds max (increased from 5)
        private const val PING_INTERVAL = 25000L // 25 seconds (heartbeat)
        private const val MAX_RECONNECT_ATTEMPTS_BEFORE_WARNING = 3 // Warn user after 3 failed attempts
        private const val CHAT_ACK_TIMEOUT = 10000L
    }

    private data class PendingChatCallback(
        val onSuccess: () -> Unit,
        val onError: (String) -> Unit,
        val createdAt: Long = System.currentTimeMillis()
    )

    private fun syncKey(): String = "last_chat_sync_${childDeviceId.trim()}"

    private fun normalizeChatEventId(
        primaryId: String?,
        timestamp: Long,
        sender: String,
        text: String
    ): String {
        val normalized = primaryId?.trim().orEmpty()
        return if (normalized.isNotEmpty()) normalized else "$sender|$timestamp|${text.hashCode()}"
    }

    private fun formatShortId(rawId: String): String {
        val normalized = rawId.trim()
        if (normalized.isEmpty()) return normalized
        return if (normalized.length <= 16) normalized else "${normalized.take(8)}...${normalized.takeLast(4)}"
    }

    private fun resolveStableOwnParentId(): String {
        effectiveContextResolver
            ?.resolveOwnParentCandidates()
            ?.firstOrNull { it.isNotBlank() }
            ?.let { return it }

        val context = appContext ?: return ""
        val secureSettings = SecureSettingsManager(context)
        val tokenPrefs = context.getSharedPreferences("childwatch_tokens", Context.MODE_PRIVATE)
        val legacyPrefs = context.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)

        val persistedId = listOf(
            secureSettings.getDeviceId(),
            tokenPrefs.getString("device_id", null),
            legacyPrefs.getString("device_id", null),
            legacyPrefs.getString("parent_device_id", null)
        )
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() }

        if (!persistedId.isNullOrBlank()) {
            if (secureSettings.getDeviceId().isNullOrBlank()) {
                secureSettings.setDeviceId(persistedId)
            }
            if (tokenPrefs.getString("device_id", null).isNullOrBlank()) {
                tokenPrefs.edit().putString("device_id", persistedId).apply()
            }
            return persistedId
        }

        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )?.trim().orEmpty()
        if (androidId.isBlank()) {
            return ""
        }

        val generated = "device_$androidId"
        secureSettings.setDeviceId(generated)
        tokenPrefs.edit().putString("device_id", generated).apply()
        return generated
    }

    @Synchronized
    private fun shouldAcceptChatEvent(messageId: String, timestamp: Long): Boolean {
        if (messageId.isBlank()) return false
        if (recentChatIds.containsKey(messageId)) return false
        recentChatIds[messageId] = timestamp
        if (timestamp > lastChatSyncTimestamp) {
            lastChatSyncTimestamp = timestamp
        }
        return true
    }

    @Synchronized
    private fun rememberChatEvent(messageId: String, timestamp: Long) {
        if (messageId.isNotBlank()) {
            recentChatIds[messageId] = timestamp
        }
        if (timestamp > lastChatSyncTimestamp) {
            lastChatSyncTimestamp = timestamp
        }
    }

    // Connection event handlers
    private val onConnect = Emitter.Listener {
        Log.d(TAG, "WebSocket connected")
        scope.launch {
            isConnecting = false
            isConnected = true
            isRegistered = false
            registeredDeviceId = childDeviceId
            lastProcessedSequence = -1
            lastAudioChunkWallclock = 0L
            audioPayloadTypeLogged = false
            shouldRequestMissedMessagesAfterRegistration = true
            registerAsParent()
            onConnectedCallback?.invoke()
        }
    }

    private val onDisconnect = Emitter.Listener { args ->
        Log.d(TAG, "WebSocket disconnected. Reason: ${args.getOrNull(0)}")
        isConnecting = false
        isConnected = false
        isRegistered = false
        registeredDeviceId = childDeviceId
        stopHeartbeat()
        lastProcessedSequence = -1
        lastAudioChunkWallclock = 0L
        shouldRequestMissedMessagesAfterRegistration = true
        failPendingChat("Disconnected")
        onChildDisconnected?.invoke()
    }

    private val onConnectError = Emitter.Listener { args ->
        val error = args.getOrNull(0)
        Log.e(TAG, "вќЊ WebSocket connection error: $error")
        isConnecting = false
        isConnected = false
        isRegistered = false
        registeredDeviceId = childDeviceId
        lastProcessedSequence = -1
        lastAudioChunkWallclock = 0L
        shouldRequestMissedMessagesAfterRegistration = true
        failPendingChat(error?.toString() ?: "Connection error")
        scope.launch {
            onErrorCallback?.invoke(error?.toString() ?: "Connection error")
        }
    }

    private val onRegistered = Emitter.Listener { args ->
        val data = args.getOrNull(0) as? JSONObject
        val success = data?.optBoolean("success") ?: false
        val deviceId = data?.optString("deviceId")?.trim().orEmpty()
        val requestedDeviceId = data?.optString("requestedDeviceId")?.trim().orEmpty()

        if (success) {
            registeredDeviceId = when {
                deviceId.isNotEmpty() -> deviceId
                requestedDeviceId.isNotEmpty() -> requestedDeviceId
                else -> childDeviceId
            }
            if (registeredDeviceId != childDeviceId) {
                Log.w(
                    TAG,
                    "Parent registered with remapped deviceId: requested=$childDeviceId registered=$registeredDeviceId"
                )
            } else {
                Log.d(TAG, "Parent registered for device: $registeredDeviceId")
            }
            isRegistered = true
            audioDeviceMismatchLogged = false
            onAttentionTransportReadyCallback?.invoke()
            onRegisteredCallback?.invoke()
            if (shouldRequestMissedMessagesAfterRegistration) {
                shouldRequestMissedMessagesAfterRegistration = false
                requestMissedMessagesViaSocket()
            } else {
                Log.d(TAG, "Skipping missed messages request on periodic re-registration")
            }
        } else {
            Log.e(TAG, "вќЊ Parent registration failed for device: $childDeviceId")
        }
    }



    private val onAudioChunk = Emitter.Listener { args ->
        try {
            val metadata = parseMetadata(args.getOrNull(0))
                ?: parseMetadata(args.getOrNull(1))
            val binaryData = parseBinaryChunk(args.getOrNull(1))
                ?: parseBinaryChunk(args.getOrNull(0))

            if (metadata != null && binaryData != null) {
                val sequence = metadata.optInt("sequence", -1)
                val timestamp = metadata.optLong("timestamp", System.currentTimeMillis())
                val sourceDeviceId = metadata.optString("deviceId").trim()
                val sampleRate = metadata.optInt("sampleRate", 24_000)
                val channels = metadata.optInt("channels", 1)
                val batteryLevel = when {
                    metadata.has("batteryLevel") && !metadata.isNull("batteryLevel") ->
                        metadata.optInt("batteryLevel")
                    metadata.has("battery") && !metadata.isNull("battery") ->
                        metadata.optInt("battery")
                    else -> null
                }?.takeIf { it in 0..100 }
                val isCharging = if (metadata.has("isCharging") && !metadata.isNull("isCharging")) {
                    metadata.optBoolean("isCharging")
                } else {
                    null
                }
                val deviceStatusTimestamp = when {
                    metadata.has("deviceStatusTimestamp") && !metadata.isNull("deviceStatusTimestamp") ->
                        metadata.optLong("deviceStatusTimestamp")
                    batteryLevel != null -> timestamp
                    else -> null
                }

                // Chunks are routed to this socket by server mapping, so keep stream alive even if
                // local target ID drifted after contact migration.
                if (sourceDeviceId.isNotEmpty() &&
                    sourceDeviceId != childDeviceId &&
                    !audioDeviceMismatchLogged
                ) {
                    audioDeviceMismatchLogged = true
                    Log.w(
                        TAG,
                        "Audio chunk deviceId mismatch: expected=$childDeviceId actual=$sourceDeviceId; accepting stream"
                    )
                }

                if (sequence < 0) {
                    Log.w(TAG, "Ignoring chunk with invalid sequence: $sequence")
                    return@Listener
                }

                val looksLikeFreshStreamReset =
                    sequence in 0..3 && lastProcessedSequence > 100

                if (sequence < lastProcessedSequence) {
                    if (looksLikeFreshStreamReset) {
                        Log.i(
                            TAG,
                            "Detected audio stream reset ($sequence < $lastProcessedSequence). Clearing stale state."
                        )
                        lastProcessedSequence = -1
                        lastAudioChunkWallclock = 0L
                        onAudioStreamResetCallback?.invoke()
                    } else {
                        Log.d(TAG, "Dropped stale/out-of-order chunk #$sequence (last=$lastProcessedSequence)")
                        return@Listener
                    }
                } else if (sequence == lastProcessedSequence) {
                    Log.d(TAG, "Skipped duplicate chunk #$sequence")
                    return@Listener
                }

                lastProcessedSequence = sequence
                lastAudioChunkWallclock = System.currentTimeMillis()
                Log.d(TAG, "Received audio chunk #$sequence (${binaryData.size} bytes)")
                onAudioChunkReceived?.invoke(
                    binaryData,
                    sequence,
                    timestamp,
                    sampleRate,
                    channels,
                    batteryLevel,
                    isCharging,
                    deviceStatusTimestamp
                )
            } else {
                if (!audioPayloadTypeLogged) {
                    audioPayloadTypeLogged = true
                    val arg0 = args.getOrNull(0)?.javaClass?.name ?: "null"
                    val arg1 = args.getOrNull(1)?.javaClass?.name ?: "null"
                    Log.w(TAG, "Invalid audio chunk payload (arg0=$arg0, arg1=$arg1)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio chunk", e)
        }
    }

    private val onAudioCaptureErrorEvent = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            val deviceId = data.optString("deviceId")
            val reason = data.optString("reason", "capture_failed")
            val message = data.optString("message")
            val timestamp = data.optLong("timestamp", System.currentTimeMillis())
            if (deviceId.isBlank() || isTargetDevice(deviceId)) {
                onAudioCaptureErrorCallback?.invoke(deviceId, reason, message, timestamp)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Error handling audio_capture_error", error)
        }
    }
    private val onCriticalAlert = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            if (data != null) {
                val alert = CriticalAlertMessage(
                    id = data.optLong("id"),
                    eventType = data.optString("eventType"),
                    severity = data.optString("severity"),
                    message = data.optString("message"),
                    metadata = data.optString("metadata"),
                    createdAt = data.optLong("createdAt")
                )
                
                scope.launch {
                    onCriticalAlertCallback?.invoke(alert)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing critical alert", e)
        }
    }

    private val onChildConnectedEvent = Emitter.Listener { args ->
        val data = args.getOrNull(0) as? JSONObject
        val deviceId = data?.optString("deviceId", childDeviceId) ?: childDeviceId
        if (!isTargetDevice(deviceId)) return@Listener
        Log.d(TAG, "Child device connected")
        scope.launch {
            onChildConnected?.invoke()
        }
    }

    private val onChildDisconnectedEvent = Emitter.Listener { args ->
        val data = args.getOrNull(0) as? JSONObject
        val deviceId = data?.optString("deviceId", childDeviceId) ?: childDeviceId
        if (!isTargetDevice(deviceId)) return@Listener
        Log.d(TAG, "Child device disconnected")
        lastProcessedSequence = -1
        scope.launch {
            onChildDisconnected?.invoke()
        }
    }

    private val onMissedMessagesEvent = Emitter.Listener { args ->
        try {
            val payload = args.getOrNull(0) as? JSONObject ?: return@Listener
            if (!payload.optBoolean("success", false)) return@Listener
            val items = payload.optJSONArray("messages") ?: return@Listener
            val restored = mutableListOf<ChatMessage>()
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                mapJsonToChatMessage(obj)
                    ?.takeIf { shouldAcceptChatEvent(it.id, it.timestamp) }
                    ?.let {
                        ChatMessageRuntimeRegistry.remember(it)
                        restored.add(it)
                    }
            }
            if (restored.isNotEmpty()) {
                scope.launch {
                    onMissedMessages?.invoke(restored)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "? Error handling missed_messages event", e)
        }
    }

    private fun mapJsonToChatMessage(obj: JSONObject): ChatMessage? {
        val text = obj.optString("message", obj.optString("text", ""))
        if (text.isEmpty()) return null
        val sender = obj.optString("senderRole", obj.optString("sender", ""))
        val authorDeviceId = obj.optString("senderDeviceId").takeIf { it.isNotBlank() }
        val ownParentId = effectiveContextResolver?.resolveOwnParentId()?.trim().orEmpty()
        val authorDisplayName = obj.optString("senderDisplayName").takeIf { it.isNotBlank() }
            ?: when (sender) {
                "child" -> participantNameResolver
                    ?.resolveFocusedChildDisplayName(registeredDeviceId.ifBlank { childDeviceId })
                    ?.takeIf { it.isNotBlank() }
                "parent" -> when {
                    !authorDeviceId.isNullOrBlank() && authorDeviceId == ownParentId ->
                        participantNameResolver?.resolveOwnParentDisplayName()?.takeIf { it.isNotBlank() }
                    !authorDeviceId.isNullOrBlank() ->
                        formatShortId(authorDeviceId)
                    else -> null
                }
                else -> null
            }
        val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
        val messageId = normalizeChatEventId(
            obj.optString("clientMessageId", obj.optString("client_id", obj.optString("id", ""))),
            timestamp,
            sender,
            text
        )
        val isRead = obj.optBoolean("isRead", false)
        val isIncoming = when (sender) {
            "child" -> true
            "parent" -> authorDeviceId.isNullOrBlank() || authorDeviceId != ownParentId
            else -> true
        }
        val status = when {
            isRead -> ChatMessage.MessageStatus.READ
            isIncoming -> ChatMessage.MessageStatus.DELIVERED
            else -> ChatMessage.MessageStatus.SENT
        }
        return ChatMessage(
            id = messageId,
            text = text,
            sender = sender,
            authorDeviceId = authorDeviceId,
            authorDisplayName = authorDisplayName,
            timestamp = timestamp,
            isRead = isRead,
            status = status
        )
    }

    private fun requestMissedMessagesViaSocket() {
        try {
            val sinceTimestamp = lastChatSyncTimestamp
            val payload = JSONObject().apply {
                put("deviceId", registeredDeviceId.ifBlank { childDeviceId })
                put("sinceTimestamp", sinceTimestamp)
            }
            socket?.emit("get_missed_messages", payload)
            Log.d(TAG, "Requested missed messages via WebSocket since=$sinceTimestamp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request missed messages", e)
        }
    }

    private val onPong = Emitter.Listener {
        Log.d(TAG, "Pong received")
    }

    private val onChatMessage = Emitter.Listener { args ->
        try {
            val messageData = args.getOrNull(0) as? JSONObject
            if (messageData != null) {
                val message = mapJsonToChatMessage(messageData)
                if (message == null) {
                    return@Listener
                }
                val messageId = message.id
                val sender = message.sender
                val text = message.text
                val timestamp = message.timestamp

                if (!shouldAcceptChatEvent(messageId, timestamp)) {
                    Log.d(TAG, "Skipped duplicate chat event id=$messageId")
                    return@Listener
                }

                ChatMessageRuntimeRegistry.remember(message)
                Log.d(TAG, "Chat message received: from=$sender, text=$text")

                scope.launch {
                    onChatMessageCallback?.invoke(messageId, text, sender, timestamp)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling chat message", e)
        }
    }

    private val onChatMessageSent = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            val timestamp = data.optLong("timestamp", System.currentTimeMillis())
            val messageId = normalizeChatEventId(
                data.optString("clientMessageId", data.optString("id", "")),
                timestamp,
                "parent",
                ""
            )
            if (messageId.isEmpty()) return@Listener
            val delivered = data.optBoolean("delivered", false)

            rememberChatEvent(messageId, timestamp)
            Log.d(TAG, "вњ… Chat message sent confirmation: id=$messageId, delivered=$delivered")
            pendingChatCallbacks.remove(messageId)?.onSuccess?.invoke()
            onChatMessageSentCallback?.invoke(messageId, delivered, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "вќЊ Error handling chat message sent confirmation", e)
        }
    }

    private val onChatMessageStatus = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            val messageId = data.optString("id")
            val status = data.optString("status")
            if (messageId.isNullOrEmpty() || status.isNullOrEmpty()) return@Listener
            val timestamp = data.optLong("timestamp", System.currentTimeMillis())
            Log.d(TAG, "Chat status update: id=$messageId status=$status")
            onChatStatusCallback?.invoke(messageId, status, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling chat status update", e)
        }
    }

    private val onChatMessageStatusAck = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject ?: return@Listener
            val messageId = data.optString("id")
            val status = data.optString("status")
            if (messageId.isNullOrEmpty() || status.isNullOrEmpty()) return@Listener
            val timestamp = data.optLong("timestamp", System.currentTimeMillis())
            Log.d(TAG, "✅ Chat status ack: id=$messageId status=$status")
            onChatStatusAckCallback?.invoke(messageId, status, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling chat status ack", e)
        }
    }

    private val onChatMessageError = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            val error = data?.optString("error") ?: "Unknown error"

            Log.e(TAG, "Chat message error: $error")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling chat message error", e)
        }
    }

    private val onPhoto = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            val photoBase64 = data?.optString("photo") ?: ""
            val requestId = data?.optString("requestId") ?: ""
            val timestamp = data?.optLong("timestamp") ?: System.currentTimeMillis()

            Log.d(TAG, "Photo received: requestId=$requestId, size=${photoBase64.length} bytes")
            
            if (photoBase64.isNotEmpty()) {
                onPhotoReceived?.invoke(photoBase64, requestId, timestamp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling photo", e)
        }
    }

    private val onPhotoRequestQueued = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            val requestId = data?.optString("requestId") ?: ""
            val deviceId = data?.optString("deviceId") ?: ""
            val camera = data?.optString("camera", "back") ?: "back"
            val timestamp = data?.optLong("timestamp") ?: System.currentTimeMillis()

            if (requestId.isNotBlank()) {
                Log.d(TAG, "Photo request queued: requestId=$requestId device=$deviceId camera=$camera")
                onPhotoQueuedCallback?.invoke(requestId, deviceId, camera, timestamp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling photo_request_queued", e)
        }
    }

    private val onPhotoErrorEvent = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            val requestId = data?.optString("requestId") ?: ""
            val error = data?.optString("error") ?: "Unknown error"

            Log.e(TAG, "вќЊ Photo error: requestId=$requestId, error=$error")
            onPhotoError?.invoke(requestId, error)
        } catch (e: Exception) {
            Log.e(TAG, "вќЊ Error handling photo error", e)
        }
    }

    private val onPhotoBusyEvent = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            val requestId = data?.optString("requestId") ?: ""
            val deviceId = data?.optString("deviceId") ?: ""
            val ownerParentId = data?.optString("ownerParentId") ?: ""
            val ownerDisplayName = data?.optString("ownerDisplayName") ?: ownerParentId
            val timestamp = data?.optLong("timestamp") ?: System.currentTimeMillis()

            Log.w(
                TAG,
                "Photo busy: requestId=$requestId device=$deviceId owner=$ownerDisplayName"
            )
            onPhotoBusy?.invoke(requestId, deviceId, ownerParentId, ownerDisplayName, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "вќЊ Error handling photo busy", e)
        }
    }

    private val onStreamTakeoverRequestedEvent = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            val deviceId = data?.optString("deviceId") ?: ""
            val requesterParentId = data?.optString("requesterParentId") ?: ""
            val requesterDisplayName = data?.optString("requesterDisplayName")
                ?.takeIf { it.isNotBlank() }
                ?: requesterParentId
            val timestamp = data?.optLong("timestamp") ?: System.currentTimeMillis()

            if (deviceId.isNotBlank()) {
                onStreamTakeoverRequested?.invoke(
                    deviceId,
                    requesterParentId,
                    requesterDisplayName,
                    timestamp
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling stream_takeover_requested", e)
        }
    }

    private val onStreamForceReleasedEvent = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            val deviceId = data?.optString("deviceId") ?: ""
            val ownerParentId = data?.optString("ownerParentId") ?: ""
            val ownerDisplayName = data?.optString("ownerDisplayName")
                ?.takeIf { it.isNotBlank() }
                ?: ownerParentId
            val releasedByType = data?.optString("releasedByType") ?: "child"
            val releasedByDisplayName = data?.optString("releasedByDisplayName")
                ?.takeIf { it.isNotBlank() }
                ?: releasedByType
            val timestamp = data?.optLong("timestamp") ?: System.currentTimeMillis()

            if (deviceId.isNotBlank()) {
                Log.w(
                    TAG,
                    "Stream force released: device=$deviceId owner=$ownerDisplayName by=$releasedByDisplayName"
                )
                onStreamForceReleased?.invoke(
                    deviceId,
                    ownerParentId,
                    ownerDisplayName,
                    releasedByType,
                    releasedByDisplayName,
                    timestamp
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling stream_force_released", e)
        }
    }

    private val onTypingStart = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            val deviceId = data?.optString("deviceId") ?: ""
            
            // Only process if it's from the child device
            if (isTargetDevice(deviceId)) {
                Log.d(TAG, "Child started typing")
                onTypingCallback?.invoke(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling typing_start", e)
        }
    }

    private val onTypingStop = Emitter.Listener { args ->
        try {
            val data = args.getOrNull(0) as? JSONObject
            val deviceId = data?.optString("deviceId") ?: ""
            
            // Only process if it's from the child device
            if (isTargetDevice(deviceId)) {
                Log.d(TAG, "Child stopped typing")
                onTypingCallback?.invoke(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling typing_stop", e)
        }
    }

    private val onAttentionStart = Emitter.Listener { args ->
        (args.getOrNull(0) as? JSONObject)?.let { payload ->
            runCatching { onAttentionStartCallback?.invoke(payload) }
                .onFailure { Log.e(TAG, "Error handling attention signal start", it) }
        }
    }

    private val onAttentionStop = Emitter.Listener { args ->
        (args.getOrNull(0) as? JSONObject)?.let { payload ->
            runCatching { onAttentionStopCallback?.invoke(payload) }
                .onFailure { Log.e(TAG, "Error handling attention signal stop", it) }
        }
    }

    private val onAttentionStatus = Emitter.Listener { args ->
        (args.getOrNull(0) as? JSONObject)?.let { payload ->
            runCatching { onAttentionStatusCallback?.invoke(payload) }
                .onFailure { Log.e(TAG, "Error handling attention signal status", it) }
        }
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
            if (socket?.connected() == true && isConnected) {
                Log.d(TAG, "WebSocket already connected")
                onConnected()
                if (!isRegistered) {
                    requestRegistration()
                }
                return
            }
            if (isConnecting) {
                Log.d(TAG, "WebSocket connection already in progress")
                return
            }
            socket?.let { existingSocket ->
                Log.d(TAG, "Disposing stale socket before reconnect")
                runCatching {
                    existingSocket.off()
                    existingSocket.disconnect()
                    existingSocket.close()
                }.onFailure {
                    Log.w(TAG, "Failed to dispose stale socket cleanly: ${it.message}")
                }
                socket = null
            }

            Log.d(TAG, "Connecting to WebSocket: $serverUrl")

            val opts = IO.Options().apply {
                transports = arrayOf("websocket", "polling") // WebSocket preferred
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = RECONNECTION_DELAY
                reconnectionDelayMax = RECONNECTION_DELAY_MAX
                timeout = CONNECTION_TIMEOUT
                tokenManager?.getAuthToken()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { authToken -> auth = mapOf("token" to authToken) }
                // Socket.IO will handle ping/pong automatically
            }

            socket = IO.socket(serverUrl, opts)
            isConnecting = true

            // Connection event handlers
            socket?.on(Socket.EVENT_CONNECT, onConnect)
            socket?.on(Socket.EVENT_DISCONNECT, onDisconnect)
            socket?.on(Socket.EVENT_CONNECT_ERROR, onConnectError)
            socket?.on("registered", onRegistered)
            socket?.on("audio_chunk", onAudioChunk)
            socket?.on("audio_capture_error", onAudioCaptureErrorEvent)
            socket?.on("critical_alert", onCriticalAlert)
            socket?.on("child_connected", onChildConnectedEvent)
            socket?.on("child_disconnected", onChildDisconnectedEvent)
            socket?.on("pong", onPong)
            socket?.on("chat_message", onChatMessage)
            socket?.on("chat_message_sent", onChatMessageSent)
            socket?.on("chat_message_status", onChatMessageStatus)
            socket?.on("chat_message_status_ack", onChatMessageStatusAck)
            socket?.on("chat_message_error", onChatMessageError)
            socket?.on("photo_request_queued", onPhotoRequestQueued)
            socket?.on("photo", onPhoto)
            socket?.on("photo_error", onPhotoErrorEvent)
            socket?.on("photo_busy", onPhotoBusyEvent)
            socket?.on("stream_takeover_requested", onStreamTakeoverRequestedEvent)
            socket?.on("stream_force_released", onStreamForceReleasedEvent)
            socket?.on("missed_messages", onMissedMessagesEvent)
            socket?.on("typing_start", onTypingStart)
            socket?.on("typing_stop", onTypingStop)
            socket?.on(AttentionSignalContract.EVENT_START, onAttentionStart)
            socket?.on(AttentionSignalContract.EVENT_STOP, onAttentionStop)
            socket?.on(AttentionSignalContract.EVENT_STATUS, onAttentionStatus)

            socket?.connect()

        } catch (e: Exception) {
            isConnecting = false
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
            stopHeartbeat()
            isConnecting = false
            socket?.disconnect()
            socket?.off("critical_alert")
            socket?.off()
            runCatching { socket?.close() }
            socket = null
            isConnected = false
            isRegistered = false
            registeredDeviceId = childDeviceId
            lastProcessedSequence = -1
            lastAudioChunkWallclock = 0L
            failPendingChat("Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }

    /**
     * Set callback for receiving audio chunks
     */
    fun setAudioChunkCallback(callback: (ByteArray, Int, Long, Int, Int, Int?, Boolean?, Long?) -> Unit) {
        onAudioChunkReceived = callback
    }

    fun setAudioCaptureErrorCallback(callback: ((String, String, String, Long) -> Unit)?) {
        onAudioCaptureErrorCallback = callback
    }

    fun setAudioStreamResetCallback(callback: (() -> Unit)?) {
        onAudioStreamResetCallback = callback
    }

    /**
     * Set callback for child connect event
     */
    fun setChildConnectedCallback(callback: () -> Unit) {
        onChildConnected = callback
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
     * Register as parent device for specific child
     */
    private fun registerAsParent() {
        try {
            val ownParentId = resolveStableOwnParentId()
            if (ownParentId.isBlank()) {
                Log.e(TAG, "Cannot register parent: stable ownParentId is missing")
                return
            }
            val parentDisplayName = participantNameResolver?.resolveOwnParentDisplayName().orEmpty()
            val registrationData = JSONObject().apply {
                put("deviceId", childDeviceId)
                put("parentId", ownParentId)
                if (parentDisplayName.isNotBlank()) {
                    put("parentDisplayName", parentDisplayName)
                }
            }

            socket?.emit("register_parent", registrationData)
            Log.d(TAG, "Parent registration sent for device: $childDeviceId, socketId: ${socket?.id()}")

            // Retry registration after 2 seconds to ensure it's received
            scope.launch {
                delay(2000)
                if (isConnected && socket != null) {
                    socket?.emit("register_parent", registrationData)
                    Log.d(TAG, "Parent registration retry sent for device: $childDeviceId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering as parent", e)
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
        try {
            if (!isConnected) {
                onError("Not connected to server")
                return
            }
            if (!isRegistered) {
                onError("Not registered on server")
                return
            }

            val messageData = JSONObject().apply {
                put("id", messageId)
                put("text", text)
                put("sender", sender)
                put("deviceId", registeredDeviceId.ifBlank { childDeviceId })
                put("timestamp", System.currentTimeMillis())
                authorDeviceId?.takeIf { it.isNotBlank() }?.let {
                    put("authorDeviceId", it)
                    put("parentId", it)
                }
                authorDisplayName?.takeIf { it.isNotBlank() }?.let {
                    put("authorDisplayName", it)
                    put("parentDisplayName", it)
                }
            }

            pendingChatCallbacks[messageId] = PendingChatCallback(onSuccess, onError)
            socket?.emit("chat_message", messageData)

            scope.launch {
                delay(CHAT_ACK_TIMEOUT)
                pendingChatCallbacks.remove(messageId)?.let { pending ->
                    pending.onError("No server ACK for message $messageId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending chat message", e)
            onError("Failed to send message: ${e.message}")
        }
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
        try {
            if (!isConnected) {
                onError("Not connected to server")
                return
            }

            val commandData = JSONObject().apply {
                put("type", commandType)
                put("deviceId", registeredDeviceId.ifBlank { childDeviceId })
                val ownParentId = effectiveContextResolver?.resolveOwnParentId()?.trim().orEmpty()
                if (ownParentId.isNotBlank()) {
                    put("parentId", ownParentId)
                }
                put("timestamp", System.currentTimeMillis())
                if (data != null) {
                    put("data", data)
                }
            }

            socket?.emit("command", commandData)
            Log.d(TAG, "Command sent: $commandType to device: $childDeviceId")
            onSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending command", e)
            onError("Failed to send command: ${e.message}")
        }
    }

    /**
     * Set callback for chat messages
     */
    fun setChatMessageCallback(callback: (messageId: String, text: String, sender: String, timestamp: Long) -> Unit) {
        onChatMessageCallback = callback
        Log.d(TAG, "вњ… Chat message callback registered")
    }

    fun setChatMessageSentCallback(callback: (messageId: String, delivered: Boolean, timestamp: Long) -> Unit) {
        onChatMessageSentCallback = callback
    }

    fun setChatStatusCallback(callback: (messageId: String, status: String, timestamp: Long) -> Unit) {
        onChatStatusCallback = callback
    }

    fun setChatStatusAckCallback(callback: (messageId: String, status: String, timestamp: Long) -> Unit) {
        onChatStatusAckCallback = callback
    }

    /**
     * Set callback for typing indicator
     */
    fun setTypingCallback(callback: (isTyping: Boolean) -> Unit) {
        onTypingCallback = callback
        Log.d(TAG, "вњ… Typing indicator callback registered")
    }

    fun setStreamTakeoverRequestedCallback(callback: ((String, String, String, Long) -> Unit)?) {
        onStreamTakeoverRequested = callback
    }

    fun setStreamForceReleasedCallback(
        callback: ((String, String, String, String, String, Long) -> Unit)?
    ) {
        onStreamForceReleased = callback
    }

    /**
     * Send typing start/stop event
     */
    fun sendTypingStatus(isTyping: Boolean) {
        try {
            if (!isConnected) {
                Log.w(TAG, "Cannot send typing status - not connected")
                return
            }
            val event = if (isTyping) "typing_start" else "typing_stop"
            val payload = JSONObject().apply {
                put("deviceId", registeredDeviceId.ifBlank { childDeviceId })
                put("timestamp", System.currentTimeMillis())
            }
            socket?.emit(event, payload)
            Log.d(TAG, "Sent $event event")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send typing status", e)
        }
    }

    fun sendChatStatus(messageId: String, status: String, actor: String): Boolean {
        try {
            if (!isReady()) return false
            val payload = JSONObject().apply {
                put("id", messageId)
                put("status", status)
                put("deviceId", registeredDeviceId.ifBlank { childDeviceId })
                put("actor", actor)
                put("timestamp", System.currentTimeMillis())
            }
            socket?.emit("chat_message_status", payload)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "вќЊ Failed to send chat status", e)
            return false
        }
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected

    fun isReady(): Boolean = isConnected && isRegistered

    private fun parseMetadata(raw: Any?): JSONObject? {
        return when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            is Map<*, *> -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        }
    }

    private fun parseBinaryChunk(raw: Any?): ByteArray? {
        return when (raw) {
            is ByteArray -> raw
            is ByteBuffer -> {
                val copy = raw.slice()
                ByteArray(copy.remaining()).also { copy.get(it) }
            }
            is JSONArray -> {
                val out = ByteArray(raw.length())
                for (i in 0 until raw.length()) {
                    out[i] = raw.optInt(i, 0).toByte()
                }
                out
            }
            is IntArray -> raw.map { it.toByte() }.toByteArray()
            is String -> runCatching { Base64.decode(raw, Base64.DEFAULT) }.getOrNull()
            is Array<*> -> {
                val out = ByteArray(raw.size)
                for (i in raw.indices) {
                    val value = raw[i]
                    out[i] = when (value) {
                        is Number -> value.toInt().toByte()
                        else -> return null
                    }
                }
                out
            }
            else -> null
        }
    }

    private fun isTargetDevice(candidate: String?): Boolean {
        val normalizedCandidate = candidate?.trim().orEmpty()
        if (normalizedCandidate.isEmpty()) return false
        val requested = childDeviceId.trim()
        val registered = registeredDeviceId.trim()
        return normalizedCandidate == requested || normalizedCandidate == registered
    }

    fun setRegisteredCallback(callback: () -> Unit) {
        onRegisteredCallback = callback
    }

    fun requestRegistration() {
        if (isConnected) {
            registerAsParent()
        }
    }

    /**
     * Start heartbeat job to keep connection alive
     */
    fun startHeartbeat(intervalMs: Long = PING_INTERVAL) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isConnected) {
                try {
                    sendPing()
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat ping failed", e)
                    break
                }
                delay(intervalMs)
            }
        }

        // Start periodic re-registration to ensure server knows we're a parent
        startPeriodicReregistration()
    }

    /**
     * Periodically re-register as parent every 30 seconds
     */
    private fun startPeriodicReregistration() {
        reregistrationJob?.cancel()
        reregistrationJob = scope.launch {
            delay(5000) // Wait 5 seconds before first re-registration
            while (isActive && isConnected) {
                try {
                    registerAsParent()
                    Log.d(TAG, "Periodic parent re-registration triggered")
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic re-registration failed", e)
                }
                delay(30000) // Re-register every 30 seconds
            }
        }
    }

    fun stopHeartbeat() {
        reregistrationJob?.cancel()
        reregistrationJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Emit WebSocket event (for external use)
     */
    fun emit(event: String, data: JSONObject) {
        socket?.emit(event, data)
    }

    fun setAttentionCallbacks(
        onStart: ((JSONObject) -> Unit)?,
        onStop: ((JSONObject) -> Unit)?,
        onStatus: ((JSONObject) -> Unit)?,
        onTransportReady: (() -> Unit)? = null
    ) {
        onAttentionStartCallback = onStart
        onAttentionStopCallback = onStop
        onAttentionStatusCallback = onStatus
        onAttentionTransportReadyCallback = onTransportReady
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        failPendingChat("Client cleanup")
        stopHeartbeat()
        disconnect()
        scope.cancel()
        onAudioChunkReceived = null
        onAudioCaptureErrorCallback = null
        onAudioStreamResetCallback = null
        onChildDisconnected = null
        onConnectedCallback = null
        onErrorCallback = null
        onRegisteredCallback = null
        onCriticalAlertCallback = null
        onChatMessageCallback = null
        onChatMessageSentCallback = null
        onChatStatusCallback = null
        onPhotoReceived = null
        onPhotoError = null
        onPhotoBusy = null
        onStreamTakeoverRequested = null
        onStreamForceReleased = null
        onAttentionStartCallback = null
        onAttentionStopCallback = null
        onAttentionStatusCallback = null
        onAttentionTransportReadyCallback = null
    }

    private fun failPendingChat(reason: String) {
        if (pendingChatCallbacks.isEmpty()) return
        val pending = pendingChatCallbacks.entries.toList()
        pendingChatCallbacks.clear()
        pending.forEach { entry ->
            try {
                entry.value.onError(reason)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify pending chat error", e)
            }
        }
    }
}
