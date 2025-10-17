package ru.example.parentwatch.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R

/**
 * Notification Manager for ParentWatch
 * Handles chat notifications and other app notifications
 */
object NotificationManager {

    private const val CHAT_CHANNEL_ID = "chat_notifications"
    private const val CHAT_CHANNEL_NAME = "Сообщения чата"
    private const val CHAT_CHANNEL_DESCRIPTION = "Уведомления о новых сообщениях от родителей"

    private const val CHAT_NOTIFICATION_ID = 1001
    private const val CHAT_GROUP_KEY = "ru.example.parentwatch.CHAT_GROUP"

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
        senderName: String = "Родители",
        messageText: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        // Increment unread counter
        unreadMessageCount++

        // Get notification duration from preferences (default: 10 seconds / 10000ms)
        val prefs = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
        val durationMs = prefs.getInt("notification_duration", 10000)
        val enableSound = prefs.getBoolean("notification_sound", true)
        val enableVibration = prefs.getBoolean("notification_vibration", true)

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

        // Build notification with BIG style for better visibility
        val builder = NotificationCompat.Builder(context, CHAT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("💬 Новое сообщение от $senderName")
            .setContentText(messageText)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(messageText)
                .setBigContentTitle("💬 Новое сообщение от $senderName"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
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

            android.util.Log.d("NotificationManager", "Chat notification shown: duration=${durationMs}ms, sound=$enableSound, vibration=$enableVibration")
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
