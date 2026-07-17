package ru.example.childwatch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.example.childwatch.chat.ChatManager
import ru.example.childwatch.chat.ChatManagerAdapter
import ru.example.childwatch.chat.ChatMessage
import ru.example.childwatch.chat.ChatMessageRuntimeRegistry
import ru.example.childwatch.network.WebSocketManager
import ru.example.childwatch.profile.ParentActiveSessionStore
import ru.example.childwatch.profile.ParentEffectiveContextResolver
import ru.example.childwatch.profile.ParentParticipantNameResolver
import ru.example.childwatch.service.ChatBackgroundService
import ru.example.childwatch.utils.SecureSettingsManager
import java.util.UUID

/**
 * Broadcast receiver for handling quick reply from chat notifications.
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationReply"
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val ACTION_REPLY = "ru.example.childwatch.ACTION_REPLY"
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
        ru.example.childwatch.utils.NotificationManager.cancelChatNotification(context)
    }

    private fun sendQuickReply(
        context: Context,
        messageText: String,
        onFinished: () -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val effectiveContextResolver = ParentEffectiveContextResolver(context)
            val activeSessionStore = ParentActiveSessionStore(context)
            val participantNameResolver = ParentParticipantNameResolver(context)

            val serverUrl = effectiveContextResolver.resolveServerUrl()
                .ifBlank { activeSessionStore.getSession()?.serverUrl.orEmpty() }
                .ifBlank { SecureSettingsManager(context).getServerUrl().trim() }
            val childDeviceId = effectiveContextResolver.resolveFocusedChildId()
                .ifBlank { activeSessionStore.getSession()?.linkedChildDeviceId.orEmpty() }
            val ownParentDeviceId = effectiveContextResolver.resolveOwnParentId()
                .ifBlank { activeSessionStore.getSession()?.ownParentDeviceId.orEmpty() }

            if (serverUrl.isBlank() || childDeviceId.isBlank()) {
                Log.e(TAG, "Quick reply aborted: missing server or child context")
                onFinished()
                return@launch
            }

            val message = ChatMessage(
                id = createLocalMessageId(ownParentDeviceId),
                text = messageText,
                sender = "parent",
                authorDeviceId = ownParentDeviceId.ifBlank { null },
                authorDisplayName = participantNameResolver.resolveOwnParentDisplayName(),
                timestamp = System.currentTimeMillis(),
                status = ChatMessage.MessageStatus.SENDING
            )

            val legacyManager = ChatManager(context)
            val roomManager = ChatManagerAdapter(context, childDeviceId)
            ChatMessageRuntimeRegistry.remember(message)
            legacyManager.saveMessage(message)
            roomManager.saveMessage(message)

            ChatBackgroundService.start(context, serverUrl, childDeviceId)
            WebSocketManager.initialize(context, serverUrl, childDeviceId)
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

    private fun createLocalMessageId(ownParentDeviceId: String): String {
        val authorId = ownParentDeviceId.ifBlank { "parent" }
        return "${authorId}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
    }
}
