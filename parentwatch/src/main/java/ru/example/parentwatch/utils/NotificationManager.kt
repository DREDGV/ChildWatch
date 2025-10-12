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
        
        // Build notification
        val notification = NotificationCompat.Builder(context, CHAT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
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
     * Cancel chat notification
     */
    fun cancelChatNotification(context: Context) {
        with(NotificationManagerCompat.from(context)) {
            cancel(CHAT_NOTIFICATION_ID)
        }
    }
    
    /**
     * Check if notifications are enabled
     */
    fun areNotificationsEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
