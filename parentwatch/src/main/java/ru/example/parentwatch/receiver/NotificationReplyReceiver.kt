package ru.example.parentwatch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.childwatch.shared.chat.ConversationMemberRole
import ru.childwatch.shared.chat.ConversationType
import ru.example.parentwatch.chat.ChatManager
import ru.example.parentwatch.chat.ChatManagerAdapter
import ru.example.parentwatch.chat.ChatMessage
import ru.example.parentwatch.chat.ChatMessageRuntimeRegistry
import ru.example.parentwatch.chat.v2.ChatV2Repository
import ru.example.parentwatch.ChatConversationV2Activity
import ru.example.parentwatch.network.WebSocketManager
import ru.example.parentwatch.service.ChatBackgroundService
import ru.example.parentwatch.session.ChildActiveSessionStore
import ru.example.parentwatch.session.ChildEffectiveContextResolver
import ru.example.parentwatch.session.ChildParticipantNameResolver
import ru.example.parentwatch.utils.ServerUrlResolver
import java.util.UUID

/**
 * Broadcast receiver for handling quick reply from chat notifications.
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationReply"
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val ACTION_REPLY = "ru.example.parentwatch.ACTION_REPLY"
    }

    /**
     * Conversation the notification belonged to, when the notification carried
     * one. Without it the reply goes to the family conversation.
     */
    private var pendingConversationId: String? = null

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return

        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = remoteInput.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim().orEmpty()
        if (replyText.isBlank()) return

        Log.d(TAG, "Quick reply received: $replyText")
        pendingConversationId = intent.getStringExtra(ChatConversationV2Activity.EXTRA_CONVERSATION_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val pendingResult = goAsync()
        sendQuickReply(context.applicationContext, replyText) {
            pendingResult.finish()
        }
        ru.example.parentwatch.utils.NotificationManager.cancelChatNotification(context)
    }

    private fun sendQuickReply(
        context: Context,
        messageText: String,
        onFinished: () -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val effectiveContextResolver = ChildEffectiveContextResolver(context)
            val activeSessionStore = ChildActiveSessionStore(context)
            val participantNameResolver = ChildParticipantNameResolver(context)

            val serverUrl = effectiveContextResolver.resolveEffectiveContext()?.serverUrl
                .orEmpty()
                .ifBlank { activeSessionStore.resolveCurrentServerUrl() }
                .ifBlank { ServerUrlResolver.getServerUrl(context).orEmpty() }
            val ownChildDeviceId = effectiveContextResolver.resolveChildDeviceId()
                .ifBlank { activeSessionStore.resolveCurrentChildId() }

            if (serverUrl.isBlank() || ownChildDeviceId.isBlank()) {
                Log.e(TAG, "Quick reply aborted: missing server or child context")
                onFinished()
                return@launch
            }

            // Conversation chat is the storage the user actually sees, so a quick
            // reply goes through the durable conversation outbox first. Delivery
            // survives a missing network, keeps retrying and reports real
            // delivery states instead of a single optimistic "sent".
            val ownDisplayName = participantNameResolver.resolveChildDisplayName()
            if (sendQuickReplyThroughConversation(context, serverUrl, ownChildDeviceId, messageText, ownDisplayName)) {
                onFinished()
                return@launch
            }

            // No conversation storage available (not upgraded yet, or nothing
            // cached): keep the previous behaviour exactly as it was so a reply
            // is never lost.
            Log.i(TAG, "Conversation chat unavailable; using the legacy send path")
            sendQuickReplyThroughLegacy(
                context = context,
                serverUrl = serverUrl,
                ownChildDeviceId = ownChildDeviceId,
                ownDisplayName = ownDisplayName,
                messageText = messageText,
                onFinished = onFinished
            )
        }
    }

    /**
     * Persists the reply in conversation storage and starts delivery.
     *
     * Returns false when conversation chat cannot be used, which tells the
     * caller to fall back to the legacy path.
     */
    private suspend fun sendQuickReplyThroughConversation(
        context: Context,
        serverUrl: String,
        ownChildDeviceId: String,
        messageText: String,
        ownDisplayName: String
    ): Boolean {
        return try {
            val repository = ChatV2Repository.create(context, serverUrl)
            val conversations = repository.getCachedConversations()
            if (conversations.isEmpty()) {
                return false
            }

            val target = conversations.firstOrNull { it.conversationId == pendingConversationId }
                ?: conversations.firstOrNull { it.type == ConversationType.FAMILY }
                ?: conversations.first()
            val senderMemberId = target.localMemberId

            repository.enqueueMessage(
                conversationId = target.conversationId,
                text = messageText,
                senderDisplayName = ownDisplayName,
                senderRole = ConversationMemberRole.CHILD,
                senderMemberId = senderMemberId
            )
            // The message is durable at this point. Delivery is attempted now and
            // is retried by the outbox and by the foreground service if it fails.
            val flush = runCatching { repository.flushOutbox(limit = 1) }.getOrNull()
            Log.i(
                TAG,
                "Quick reply queued in conversation ${target.conversationId}: " +
                    "sent=${flush?.sent ?: 0}, retryScheduled=${flush?.retryScheduled ?: 0}, " +
                    "permanentlyFailed=${flush?.permanentlyFailed ?: 0}"
            )

            ChatBackgroundService.start(context, serverUrl, ownChildDeviceId)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Quick reply through conversation chat failed; falling back", e)
            false
        }
    }

    /** Previous quick-reply behaviour, kept unchanged as the fallback path. */
    private fun sendQuickReplyThroughLegacy(
        context: Context,
        serverUrl: String,
        ownChildDeviceId: String,
        ownDisplayName: String,
        messageText: String,
        onFinished: () -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val message = ChatMessage(
                id = createLocalMessageId(ownChildDeviceId),
                text = messageText,
                sender = "child",
                authorDeviceId = ownChildDeviceId,
                authorDisplayName = ownDisplayName,
                timestamp = System.currentTimeMillis(),
                status = ChatMessage.MessageStatus.SENDING
            )

            val legacyManager = ChatManager(context)
            val roomManager = ChatManagerAdapter(context, ownChildDeviceId)
            ChatMessageRuntimeRegistry.remember(message)
            legacyManager.saveMessage(message)
            roomManager.saveMessage(message)

            ChatBackgroundService.start(context, serverUrl, ownChildDeviceId)
            WebSocketManager.initialize(context, serverUrl, ownChildDeviceId)
            WebSocketManager.ensureConnected(
                onReady = {
                    WebSocketManager.sendChatMessage(
                        messageId = message.id,
                        text = message.text,
                        sender = message.sender,
                        authorDeviceId = message.authorDeviceId,
                        authorDisplayName = message.authorDisplayName,
                        onSuccess = {
                            roomManager.updateMessageStatus(message.id, ChatMessage.MessageStatus.SENT)
                            legacyManager.updateMessageStatus(message.id, ChatMessage.MessageStatus.SENT)
                            onFinished()
                        },
                        onError = { error ->
                            Log.e(TAG, "Quick reply send failed: $error")
                            roomManager.updateMessageStatus(message.id, ChatMessage.MessageStatus.FAILED)
                            legacyManager.updateMessageStatus(message.id, ChatMessage.MessageStatus.FAILED)
                            onFinished()
                        }
                    )
                },
                onError = { error ->
                    Log.e(TAG, "Quick reply connection failed: $error")
                    roomManager.updateMessageStatus(message.id, ChatMessage.MessageStatus.FAILED)
                    legacyManager.updateMessageStatus(message.id, ChatMessage.MessageStatus.FAILED)
                    onFinished()
                }
            )
        }
    }

    private fun createLocalMessageId(ownChildDeviceId: String): String {
        val authorId = ownChildDeviceId.ifBlank { "child" }
        return "${authorId}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
    }
}
