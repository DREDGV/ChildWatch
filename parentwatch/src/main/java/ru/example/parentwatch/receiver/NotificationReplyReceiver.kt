package ru.example.parentwatch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.example.parentwatch.chat.ChatManager
import ru.example.parentwatch.chat.ChatManagerAdapter
import ru.example.parentwatch.chat.ChatMessage
import ru.example.parentwatch.chat.ChatMessageRuntimeRegistry
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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return

        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = remoteInput.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim().orEmpty()
        if (replyText.isBlank()) return

        Log.d(TAG, "Quick reply received: $replyText")
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

            val message = ChatMessage(
                id = createLocalMessageId(ownChildDeviceId),
                text = messageText,
                sender = "child",
                authorDeviceId = ownChildDeviceId,
                authorDisplayName = participantNameResolver.resolveChildDisplayName(),
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
