package ru.example.parentwatch.chat.v2

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.childwatch.shared.chat.ChatV2UiRegistry
import ru.childwatch.shared.chat.ChatV2MessageDto
import ru.example.parentwatch.network.WebSocketManager
import ru.example.parentwatch.utils.NotificationManager

/** Keeps v2 conversations subscribed while the foreground connection service is alive. */
class ChatV2BackgroundCoordinator(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private var repository: ChatV2Repository? = null
    private var refreshJob: Job? = null
    private val gson = Gson()
    private val subscriptions = linkedSetOf<String>()
    private val messageListener: (JSONObject) -> Unit = { payload ->
        scope.launch { handleMessage(payload) }
    }

    fun start(serverUrl: String, legacyChildDeviceId: String?) {
        stop()
        repository = ChatV2Repository.create(appContext, serverUrl)
        WebSocketManager.addChatV2MessageListener(messageListener)
        refreshJob = scope.launch {
            while (isActive) {
                runCatching { refreshSubscriptions(legacyChildDeviceId) }
                    .onFailure { Log.w(TAG, "Conversation subscription refresh failed", it) }
                delay(SUBSCRIPTION_REFRESH_MS)
            }
        }
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
        WebSocketManager.removeChatV2MessageListener(messageListener)
        subscriptions.toList().forEach(WebSocketManager::unsubscribeChatV2)
        subscriptions.clear()
        repository = null
    }

    private suspend fun refreshSubscriptions(legacyChildDeviceId: String?) {
        val items = repository?.refreshConversations(legacyChildDeviceId) ?: return
        val wanted = items.mapTo(linkedSetOf()) { it.conversationId }
        (subscriptions - wanted).forEach(WebSocketManager::unsubscribeChatV2)
        (wanted - subscriptions).forEach(WebSocketManager::subscribeChatV2)
        subscriptions.clear()
        subscriptions.addAll(wanted)
    }

    private suspend fun handleMessage(payload: JSONObject) {
        val repo = repository ?: return
        val conversationId = payload.optString("conversationId").trim()
        val message = payload.optJSONObject("message") ?: return
        val messageId = message.optString("messageId").trim()
        val senderMemberId = message.optString("senderMemberId").trim()
        val sequence = message.optLong("serverSequence", 0L)
        if (conversationId.isEmpty() || messageId.isEmpty()) return

        val dto = runCatching {
            gson.fromJson(message.toString(), ChatV2MessageDto::class.java)
        }.getOrNull()
        val cached = dto != null && runCatching { repo.cacheRealtimeMessage(dto) }
            .onFailure { Log.w(TAG, "Incoming realtime chat cache failed", it) }
            .isSuccess
        if (!cached) {
            runCatching { repo.syncMessagesPage(conversationId, limit = 50) }
                .onFailure { Log.w(TAG, "Incoming chat sync failed", it) }
                .getOrNull() ?: return
        }
        if (sequence > 0) runCatching { repo.markDeliveredThrough(conversationId, sequence) }

        val conversation = repo.getCachedConversations()
            .firstOrNull { it.conversationId == conversationId } ?: return
        if (senderMemberId.isNotEmpty() && senderMemberId == conversation.localMemberId) return
        if (ChatV2UiRegistry.isActive(conversationId)) return

        NotificationManager.showChatNotification(
            context = appContext,
            senderName = message.optString("senderDisplayName").ifBlank { "Участник семьи" },
            messageText = message.optString("text"),
            timestamp = message.optLong("serverCreatedAt", System.currentTimeMillis()),
            messageId = messageId,
            conversationId = conversationId,
            conversationTitle = conversation.title
        )
    }

    private companion object {
        const val TAG = "ChatV2Background"
        const val SUBSCRIPTION_REFRESH_MS = 60_000L
    }
}
