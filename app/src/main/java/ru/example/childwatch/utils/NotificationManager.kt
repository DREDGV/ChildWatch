package ru.example.childwatch.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import ru.example.childwatch.MainActivity
import ru.example.childwatch.R
import ru.example.childwatch.receiver.NotificationReplyReceiver

/**
 * Notification Manager for ChildWatch (Parent App)
 * Handles chat notifications and other app notifications
 */
object NotificationManager {

    private const val TAG = "NotificationManager"
    private const val CHAT_CHANNEL_ID = "chat_notifications"
    private const val CHAT_CHANNEL_NAME = "Сообщения чата"
    private const val CHAT_CHANNEL_DESCRIPTION = "Уведомления о новых сообщениях от ребенка"

    private const val CHAT_NOTIFICATION_ID = 4001
    private const val CHAT_GROUP_KEY = "ru.example.childwatch.CHAT_GROUP"

    // Counter for unread messages
    private var unreadMessageCount = 0

    // Message history for MessagingStyle (last 10 messages)
    private val messageHistory = mutableListOf<NotificationMessage>()
    private const val MAX_HISTORY_SIZE = 10

    data class NotificationMessage(
        val senderName: String,
        val text: String,
        val timestamp: Long
    )

    /**
     * Create notification channels for Android O and above
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Chat notifications channel
            val chatChannel = NotificationChannel(
                CHAT_CHANNEL_ID,
                CHAT_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHAT_CHANNEL_DESCRIPTION
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(chatChannel)
        }
    }

    /**
     * Show chat notification for new message with MessagingStyle
     */
    fun showChatNotification(
        context: Context,
        senderName: String = "Ребенок",
        messageText: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        // Increment unread counter
        unreadMessageCount++

        // Add message to history
        val message = NotificationMessage(senderName, messageText, timestamp)
        messageHistory.add(message)
        if (messageHistory.size > MAX_HISTORY_SIZE) {
            messageHistory.removeAt(0) // Remove oldest message
        }

        // Get notification preferences
        val prefs = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
        val durationMs = prefs.getInt("notification_duration", 10000)
        val notificationPriority = prefs.getInt("notification_priority", 2)
        val enableSound = prefs.getBoolean("notification_sound", true)
        val enableVibration = prefs.getBoolean("notification_vibration", true)

        Log.d(TAG, "Notification settings: duration=${durationMs}ms, priority=$notificationPriority, sound=$enableSound, vibration=$enableVibration")

        // Create intent to open chat when notification is tapped
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_chat", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Map priority value to NotificationCompat constant
        val priority = when (notificationPriority) {
            0 -> NotificationCompat.PRIORITY_LOW
            1 -> NotificationCompat.PRIORITY_DEFAULT
            2 -> NotificationCompat.PRIORITY_MAX
            else -> NotificationCompat.PRIORITY_MAX
        }

        // Create MessagingStyle
        val messagingStyle = NotificationCompat.MessagingStyle("Вы")
            .setConversationTitle("Чат с ребёнком")

        // Add all messages from history to MessagingStyle
        messageHistory.forEach { msg ->
            messagingStyle.addMessage(
                NotificationCompat.MessagingStyle.Message(
                    msg.text,
                    msg.timestamp,
                    msg.senderName
                )
            )
        }

        // Create RemoteInput for quick reply
        val remoteInput = RemoteInput.Builder(NotificationReplyReceiver.KEY_TEXT_REPLY)
            .setLabel("Ответить...")
            .build()

        // Create reply action intent
        val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_REPLY
        }

        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE // Must be mutable for RemoteInput
        )

        // Create reply action
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Ответить",
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        // Build notification
        val builder = NotificationCompat.Builder(context, CHAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("💬 Чат с ребёнком")
            .setContentText(messageText)
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setWhen(timestamp)
            .setShowWhen(true)
            .setGroup(CHAT_GROUP_KEY)
            .setNumber(unreadMessageCount)
            .setOnlyAlertOnce(false)
            .setTimeoutAfter(durationMs.toLong())
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show on lockscreen
            .setStyle(messagingStyle)
            .addAction(replyAction) // Add quick reply action

        // Configure sound based on preferences
        if (enableSound) {
            builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
        }

        // Configure vibration based on preferences
        if (enableVibration) {
            builder.setVibrate(longArrayOf(0, 500, 200, 500)) // Pattern: wait, vibrate, wait, vibrate
        }

        // Show notification
        try {
            with(NotificationManagerCompat.from(context)) {
                notify(CHAT_NOTIFICATION_ID, builder.build())
            }

            android.util.Log.d("NotificationManager", "Chat notification shown with MessagingStyle: duration=${durationMs}ms, priority=$notificationPriority, sound=$enableSound, vibration=$enableVibration, history=${messageHistory.size} messages")
        } catch (e: SecurityException) {
            android.util.Log.e("NotificationManager", "Failed to show notification: ${e.message}")
        }
    }

    /**
     * Cancel chat notification and reset counter
     */
    fun cancelChatNotification(context: Context) {
        with(NotificationManagerCompat.from(context)) {
            cancel(CHAT_NOTIFICATION_ID)
        }
        unreadMessageCount = 0 // Reset counter when notifications dismissed
        messageHistory.clear() // Clear message history
    }

    /**
     * Reset unread message counter (call when user opens chat)
     */
    fun resetUnreadCount() {
        unreadMessageCount = 0
        messageHistory.clear() // Also clear history when chat is opened
    }

    /**
     * Get current unread message count
     */
    fun getUnreadCount(): Int {
        return unreadMessageCount
    }

    /**
     * Check if notifications are enabled
     */
    fun areNotificationsEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
