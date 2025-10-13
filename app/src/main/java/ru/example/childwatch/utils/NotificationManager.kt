package ru.example.childwatch.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.example.childwatch.MainActivity
import ru.example.childwatch.R

/**
 * Notification Manager for ChildWatch (Parent App)
 * Handles chat notifications and other app notifications
 */
object NotificationManager {

    private const val CHAT_CHANNEL_ID = "chat_notifications"
    private const val CHAT_CHANNEL_NAME = "Сообщения чата"
    private const val CHAT_CHANNEL_DESCRIPTION = "Уведомления о новых сообщениях от ребенка"

    private const val CHAT_NOTIFICATION_ID = 4001
    private const val CHAT_GROUP_KEY = "ru.example.childwatch.CHAT_GROUP"

    // Counter for unread messages
    private var unreadMessageCount = 0

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
     * Show chat notification for new message
     */
    fun showChatNotification(
        context: Context,
        senderName: String = "Ребенок",
        messageText: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        // Increment unread counter
        unreadMessageCount++

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

        // Build notification with grouping
        val notification = NotificationCompat.Builder(context, CHAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("💬 Новое сообщение от $senderName")
            .setContentText(messageText)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(messageText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setWhen(timestamp)
            .setShowWhen(true)
            .setGroup(CHAT_GROUP_KEY)
            .setNumber(unreadMessageCount) // Show badge with count
            .setOnlyAlertOnce(false) // Alert for each message
            .build()

        // Show notification
        try {
            with(NotificationManagerCompat.from(context)) {
                notify(CHAT_NOTIFICATION_ID, notification)
            }
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
    }

    /**
     * Reset unread message counter (call when user opens chat)
     */
    fun resetUnreadCount() {
        unreadMessageCount = 0
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
