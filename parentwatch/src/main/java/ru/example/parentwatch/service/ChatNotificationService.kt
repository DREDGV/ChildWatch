package ru.example.parentwatch.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import ru.example.parentwatch.chat.ChatMessage
import ru.example.parentwatch.chat.ChatMessageRuntimeRegistry
import ru.example.parentwatch.network.WebSocketManager
import ru.example.parentwatch.utils.NotificationManager

/**
 * Background service for receiving chat messages and showing notifications
 * Runs when app is not in foreground to ensure messages are received
 */
class ChatNotificationService : Service() {

    companion object {
        private const val TAG = "ChatNotificationService"
        private const val NOTIFICATION_ID = 2001
    }

    private val notificationListener: (String, String, String, Long) -> Unit = { messageId, text, sender, timestamp ->
        Log.d(TAG, "Received message in background: $text from $sender")
        val message = ChatMessageRuntimeRegistry.find(messageId) ?: ChatMessage(
            id = messageId,
            text = text,
            sender = sender,
            timestamp = timestamp
        )
        NotificationManager.showChatNotification(
            context = this,
            senderName = message.getSenderName(),
            messageText = text,
            timestamp = timestamp,
            messageId = messageId
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ChatNotificationService created")
        
        // Set up message callback for notifications
        WebSocketManager.addChatMessageListener(notificationListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ChatNotificationService started")
        
        // Return START_STICKY to keep service running
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ChatNotificationService destroyed")
        WebSocketManager.removeChatMessageListener(notificationListener)
    }
}
