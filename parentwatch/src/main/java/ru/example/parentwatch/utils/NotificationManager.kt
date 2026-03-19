package ru.example.parentwatch.utils

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R
import ru.example.parentwatch.receiver.NotificationReplyReceiver
import java.util.Calendar
import android.app.NotificationManager as AndroidNotificationManager

/**
 * Notification manager for ChildWatch chat notifications.
 */
object NotificationManager {

    private const val TAG = "NotificationManager"
    private const val CHAT_CHANNEL_ID = "chat_notifications"
    private const val CHAT_NOTIFICATION_ID = 1001
    private const val CHAT_PREVIEW_NOTIFICATION_ID = 1002
    private const val CHAT_GROUP_KEY = "ru.example.parentwatch.CHAT_GROUP"
    private const val MAX_HISTORY_SIZE = 10
    private const val DEFAULT_QUIET_HOURS_START = "22:00"
    private const val DEFAULT_QUIET_HOURS_END = "07:00"

    private var unreadMessageCount = 0
    private val messageHistory = mutableListOf<NotificationMessage>()

    data class NotificationMessage(
        val senderName: String,
        val text: String,
        val timestamp: Long
    )

    data class ChatNotificationSettings(
        val durationMs: Int,
        val priority: Int,
        val size: String,
        val enableSound: Boolean,
        val enableVibration: Boolean,
        val showBadge: Boolean,
        val previewMode: String,
        val quietHoursEnabled: Boolean = false,
        val quietHoursStart: String = DEFAULT_QUIET_HOURS_START,
        val quietHoursEnd: String = DEFAULT_QUIET_HOURS_END
    )

    fun createNotificationChannels(
        context: Context,
        overrideSettings: ChatNotificationSettings? = null
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        ensureChatChannel(
            context,
            resolveEffectiveSettings(overrideSettings ?: readChatSettings(context))
        )
    }

    fun showChatNotification(
        context: Context,
        senderName: String = context.getString(R.string.notification_preview_sender),
        messageText: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        unreadMessageCount++

        messageHistory.add(NotificationMessage(senderName, messageText, timestamp))
        if (messageHistory.size > MAX_HISTORY_SIZE) {
            messageHistory.removeAt(0)
        }

        val settings = resolveEffectiveSettings(readChatSettings(context))
        val builder = buildChatNotification(
            context = context,
            senderName = senderName,
            messageText = messageText,
            timestamp = timestamp,
            settings = settings,
            unreadCount = unreadMessageCount,
            displayHistory = messageHistory.toList(),
            includeReplyAction = true
        )

        notifySafely(context, CHAT_NOTIFICATION_ID, builder.build())
    }

    fun showPreviewNotification(
        context: Context,
        overrideSettings: ChatNotificationSettings? = null
    ) {
        val settings = resolveEffectiveSettings(overrideSettings ?: readChatSettings(context))
        createNotificationChannels(context, settings)

        val preview = NotificationMessage(
            senderName = context.getString(R.string.notification_preview_sender),
            text = context.getString(R.string.notification_preview_message),
            timestamp = System.currentTimeMillis()
        )

        val builder = buildChatNotification(
            context = context,
            senderName = preview.senderName,
            messageText = preview.text,
            timestamp = preview.timestamp,
            settings = settings,
            unreadCount = 1,
            displayHistory = listOf(preview),
            includeReplyAction = false
        )

        notifySafely(context, CHAT_PREVIEW_NOTIFICATION_ID, builder.build())
    }

    fun cancelChatNotification(context: Context) {
        with(NotificationManagerCompat.from(context)) {
            cancel(CHAT_NOTIFICATION_ID)
            cancel(CHAT_PREVIEW_NOTIFICATION_ID)
        }
        unreadMessageCount = 0
        messageHistory.clear()
    }

    fun resetUnreadCount() {
        unreadMessageCount = 0
        messageHistory.clear()
    }

    fun getUnreadCount(): Int = unreadMessageCount

    fun areNotificationsEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun readChatSettings(context: Context): ChatNotificationSettings {
        val prefs = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
        return ChatNotificationSettings(
            durationMs = prefs.getInt("notification_duration", 10000),
            priority = prefs.getInt("notification_priority", 2),
            size = prefs.getString("notification_size", "expanded") ?: "expanded",
            enableSound = prefs.getBoolean("notification_sound", true),
            enableVibration = prefs.getBoolean("notification_vibration", true),
            showBadge = prefs.getBoolean("notification_badge", true),
            previewMode = prefs.getString("notification_preview", "public") ?: "public",
            quietHoursEnabled = prefs.getBoolean("notification_quiet_hours_enabled", false),
            quietHoursStart = prefs.getString(
                "notification_quiet_hours_start",
                DEFAULT_QUIET_HOURS_START
            ) ?: DEFAULT_QUIET_HOURS_START,
            quietHoursEnd = prefs.getString(
                "notification_quiet_hours_end",
                DEFAULT_QUIET_HOURS_END
            ) ?: DEFAULT_QUIET_HOURS_END
        )
    }

    private fun resolveEffectiveSettings(settings: ChatNotificationSettings): ChatNotificationSettings {
        if (!isQuietHoursActive(settings)) {
            return settings
        }

        return settings.copy(
            enableSound = false,
            enableVibration = false,
            priority = settings.priority.coerceAtMost(1)
        )
    }

    private fun isQuietHoursActive(settings: ChatNotificationSettings): Boolean {
        if (!settings.quietHoursEnabled) {
            return false
        }

        val startMinutes = parseTimeToMinutes(settings.quietHoursStart, DEFAULT_QUIET_HOURS_START)
        val endMinutes = parseTimeToMinutes(settings.quietHoursEnd, DEFAULT_QUIET_HOURS_END)
        if (startMinutes == endMinutes) {
            return false
        }

        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (startMinutes < endMinutes) {
            currentMinutes in startMinutes until endMinutes
        } else {
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    private fun parseTimeToMinutes(value: String, fallback: String): Int {
        val normalized = value.takeIf { it.contains(':') } ?: fallback
        val parts = normalized.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 0
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return hour * 60 + minute
    }

    private fun resolveChatChannelId(settings: ChatNotificationSettings): String {
        return buildString {
            append(CHAT_CHANNEL_ID)
            append("_p").append(settings.priority)
            append("_b").append(if (settings.showBadge) 1 else 0)
            append("_s").append(if (settings.enableSound) 1 else 0)
            append("_v").append(if (settings.enableVibration) 1 else 0)
            append("_l").append(if (settings.previewMode == "private") 0 else 1)
        }
    }

    private fun ensureChatChannel(
        context: Context,
        settings: ChatNotificationSettings
    ): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return CHAT_CHANNEL_ID
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
        val channelId = resolveChatChannelId(settings)
        if (notificationManager.getNotificationChannel(channelId) != null) {
            return channelId
        }

        val channel = NotificationChannel(
            channelId,
            context.getString(R.string.notification_channel_chat_name),
            mapChannelImportance(settings.priority)
        ).apply {
            description = context.getString(R.string.notification_channel_chat_description)
            enableLights(true)
            setShowBadge(settings.showBadge)
            enableVibration(settings.enableVibration)
            vibrationPattern = if (settings.enableVibration) {
                longArrayOf(0, 500, 200, 500)
            } else {
                null
            }
            lockscreenVisibility = if (settings.previewMode == "private") {
                Notification.VISIBILITY_PRIVATE
            } else {
                Notification.VISIBILITY_PUBLIC
            }

            if (settings.enableSound) {
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)
            } else {
                setSound(null, null)
            }
        }

        notificationManager.createNotificationChannel(channel)
        return channelId
    }

    private fun buildChatNotification(
        context: Context,
        senderName: String,
        messageText: String,
        timestamp: Long,
        settings: ChatNotificationSettings,
        unreadCount: Int,
        displayHistory: List<NotificationMessage>,
        includeReplyAction: Boolean
    ): NotificationCompat.Builder {
        val channelId = ensureChatChannel(context, settings)
        val pendingIntent = buildOpenChatPendingIntent(context)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_content_title))
            .setContentText(messageText)
            .setPriority(mapCompatPriority(settings.priority))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setWhen(timestamp)
            .setShowWhen(true)
            .setGroup(CHAT_GROUP_KEY)
            .setNumber(if (settings.showBadge) unreadCount else 0)
            .setOnlyAlertOnce(false)
            .setTimeoutAfter(settings.durationMs.toLong())
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(
                if (settings.previewMode == "private") {
                    NotificationCompat.VISIBILITY_PRIVATE
                } else {
                    NotificationCompat.VISIBILITY_PUBLIC
                }
            )

        if (settings.size == "expanded") {
            val messagingStyle =
                NotificationCompat.MessagingStyle(context.getString(R.string.notification_user_self))
                    .setConversationTitle(context.getString(R.string.notification_conversation_title))
            displayHistory.forEach { msg ->
                messagingStyle.addMessage(
                    NotificationCompat.MessagingStyle.Message(
                        msg.text,
                        msg.timestamp,
                        msg.senderName
                    )
                )
            }
            builder.setStyle(messagingStyle)
        } else {
            builder.setSubText(senderName)
        }

        if (includeReplyAction) {
            builder.addAction(buildReplyAction(context))
        }

        if (settings.previewMode == "private") {
            builder.setPublicVersion(buildPublicVersion(context, channelId, pendingIntent, timestamp))
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (settings.enableSound) {
                builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
            }
            if (settings.enableVibration) {
                builder.setVibrate(longArrayOf(0, 500, 200, 500))
            }
        }

        Log.d(
            TAG,
            "Notification settings: duration=${settings.durationMs}ms, priority=${settings.priority}, " +
                "size=${settings.size}, sound=${settings.enableSound}, vibration=${settings.enableVibration}, " +
                "badge=${settings.showBadge}, preview=${settings.previewMode}"
        )

        return builder
    }

    private fun buildReplyAction(context: Context): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(NotificationReplyReceiver.KEY_TEXT_REPLY)
            .setLabel(context.getString(R.string.notification_reply_hint))
            .build()

        val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_REPLY
        }

        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            context.getString(R.string.notification_action_reply),
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun buildPublicVersion(
        context: Context,
        channelId: String,
        pendingIntent: PendingIntent,
        timestamp: Long
    ): Notification {
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_content_title))
            .setContentText(context.getString(R.string.notification_hidden_content))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setWhen(timestamp)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun buildOpenChatPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_chat", true)
        }

        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notifySafely(context: Context, notificationId: Int, notification: Notification) {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        if (!permissionGranted) {
            Log.w(TAG, "Skipping notification $notificationId: POST_NOTIFICATIONS not granted")
            return
        }

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show notification: ${e.message}")
        }
    }

    private fun mapChannelImportance(priority: Int): Int {
        return when (priority) {
            0 -> AndroidNotificationManager.IMPORTANCE_LOW
            1 -> AndroidNotificationManager.IMPORTANCE_DEFAULT
            else -> AndroidNotificationManager.IMPORTANCE_HIGH
        }
    }

    private fun mapCompatPriority(priority: Int): Int {
        return when (priority) {
            0 -> NotificationCompat.PRIORITY_LOW
            1 -> NotificationCompat.PRIORITY_DEFAULT
            else -> NotificationCompat.PRIORITY_MAX
        }
    }
}
