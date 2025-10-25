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
import ru.example.parentwatch.chat.ChatMessage
import java.util.UUID

/**
 * Broadcast receiver for handling quick reply from chat notifications
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationReply"
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val ACTION_REPLY = "ru.example.parentwatch.ACTION_REPLY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) {
            return
        }

        // Get the reply text from RemoteInput
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        if (remoteInput != null) {
            val replyText = remoteInput.getCharSequence(KEY_TEXT_REPLY)?.toString()

            if (!replyText.isNullOrBlank()) {
                Log.d(TAG, "Quick reply received: $replyText")

                // Send the message
                sendQuickReply(context, replyText)

                // Cancel the notification
                ru.example.parentwatch.utils.NotificationManager.cancelChatNotification(context)
            }
        }
    }

    private fun sendQuickReply(context: Context, messageText: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Create message with SENT status (optimistic sending)
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = messageText,
                    sender = "parent",
                    timestamp = System.currentTimeMillis(),
                    status = ChatMessage.MessageStatus.SENT
                )

                // Save to local storage
                val chatManager = ChatManager(context)
                chatManager.saveMessage(message)

                Log.d(TAG, "Quick reply saved: $messageText")

                // TODO: Send to server via WebSocket when WebSocket manager is available
                // For now, message is saved locally and will be sent when chat is opened

            } catch (e: Exception) {
                Log.e(TAG, "Error sending quick reply", e)
            }
        }
    }
}
