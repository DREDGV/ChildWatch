package ru.example.parentwatch.utils

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager as AndroidNotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import ru.example.parentwatch.ChatActivity
import ru.example.parentwatch.R
import java.text.SimpleDateFormat
import java.util.*

/**
 * Priority Notification Manager for urgent parent messages
 *
 * Features:
 * - Full-screen overlay notifications that appear over apps/games
 * - Customizable duration, size, sound, vibration
 * - Auto-dismiss with countdown
 * - Requires "Display over other apps" permission
 */
object PriorityNotificationManager {

    private const val CHANNEL_ID_PRIORITY = "chat_priority_messages"
    private const val CHANNEL_NAME_PRIORITY = "Важные сообщения от родителей"
    private const val NOTIFICATION_ID_PRIORITY = 5000

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var dismissTimer: android.os.CountDownTimer? = null

    /**
     * Check if overlay permission is granted
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Request overlay permission
     */
    fun requestOverlayPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${activity.packageName}")
            )
            activity.startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
        }
    }

    const val REQUEST_OVERLAY_PERMISSION = 1234

    /**
     * Show priority message notification
     *
     * @param context Application context
     * @param messageText Message content
     * @param senderName Sender name (usually "Родитель")
     * @param durationSeconds How long to display (default 10 sec)
     * @param textSizeMultiplier Size multiplier (1.0 = normal, 2.0 = double, etc.)
     * @param useSound Play notification sound
     * @param useVibration Vibrate device
     */
    fun showPriorityMessage(
        context: Context,
        messageText: String,
        senderName: String = "Родитель",
        durationSeconds: Int = 10,
        textSizeMultiplier: Float = 1.5f,
        useSound: Boolean = true,
        useVibration: Boolean = true
    ) {
        // Create notification channel first
        createPriorityNotificationChannel(context, useSound)

        // Show standard notification
        showPriorityNotification(context, senderName, messageText)

        // Show overlay if permission granted
        if (hasOverlayPermission(context)) {
            showOverlayNotification(
                context,
                messageText,
                senderName,
                durationSeconds,
                textSizeMultiplier
            )
        }

        // Play sound
        if (useSound) {
            playNotificationSound(context)
        }

        // Vibrate
        if (useVibration) {
            vibrateDevice(context)
        }
    }

    /**
     * Create notification channel for priority messages
     */
    private fun createPriorityNotificationChannel(context: Context, useSound: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = AndroidNotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID_PRIORITY, CHANNEL_NAME_PRIORITY, importance).apply {
                description = "Критически важные сообщения от родителей"
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC

                if (useSound) {
                    val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                    setSound(soundUri, audioAttributes)
                }
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Show standard notification
     */
    private fun showPriorityNotification(context: Context, senderName: String, messageText: String) {
        val intent = Intent(context, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_PRIORITY)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // Use system icon
            .setContentTitle("⚠️ ВАЖНОЕ СООБЩЕНИЕ от $senderName")
            .setContentText(messageText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(Color.RED)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
        notificationManager.notify(NOTIFICATION_ID_PRIORITY, notification)
    }

    /**
     * Show overlay notification (appears over apps)
     */
    private fun showOverlayNotification(
        context: Context,
        messageText: String,
        senderName: String,
        durationSeconds: Int,
        textSizeMultiplier: Float
    ) {
        // Remove existing overlay if any
        dismissOverlay()

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutParams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
        }

        layoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        layoutParams.y = 100 // Offset from top

        // Inflate custom layout
        overlayView = LayoutInflater.from(context).inflate(R.layout.overlay_priority_message, null)

        // Set text
        val senderTextView = overlayView?.findViewById<TextView>(R.id.senderText)
        val messageTextView = overlayView?.findViewById<TextView>(R.id.messageText)
        val countdownTextView = overlayView?.findViewById<TextView>(R.id.countdownText)

        senderTextView?.text = "⚠️ $senderName"
        messageTextView?.text = messageText
        messageTextView?.textSize = 18f * textSizeMultiplier

        // Click to dismiss
        overlayView?.setOnClickListener {
            dismissOverlay()
        }

        // Add to window
        try {
            windowManager?.addView(overlayView, layoutParams)
        } catch (e: Exception) {
            android.util.Log.e("PriorityNotif", "Failed to show overlay", e)
            return
        }

        // Auto-dismiss timer
        dismissTimer = object : android.os.CountDownTimer((durationSeconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000).toInt()
                countdownTextView?.text = "Закроется через $secondsLeft сек (нажмите для закрытия)"
            }

            override fun onFinish() {
                dismissOverlay()
            }
        }.start()
    }

    /**
     * Dismiss overlay notification
     */
    fun dismissOverlay() {
        dismissTimer?.cancel()
        dismissTimer = null

        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // View already removed
            }
        }
        overlayView = null
        windowManager = null
    }

    /**
     * Play notification sound
     */
    private fun playNotificationSound(context: Context) {
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, notification)
            ringtone.play()
        } catch (e: Exception) {
            android.util.Log.e("PriorityNotif", "Failed to play sound", e)
        }
    }

    /**
     * Vibrate device
     */
    private fun vibrateDevice(context: Context) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pattern = longArrayOf(0, 500, 200, 500, 200, 500) // Long-Short-Long-Short-Long
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 500, 200, 500, 200, 500), -1)
            }
        } catch (e: Exception) {
            android.util.Log.e("PriorityNotif", "Failed to vibrate", e)
        }
    }

    /**
     * Check if message is priority (starts with !!! or marked as important)
     */
    fun isPriorityMessage(messageText: String): Boolean {
        return messageText.trim().startsWith("!!!") ||
                messageText.trim().startsWith("[ВАЖНО]") ||
                messageText.trim().startsWith("[URGENT]")
    }

    /**
     * Strip priority markers from message
     */
    fun stripPriorityMarkers(messageText: String): String {
        return messageText.trim()
            .removePrefix("!!!")
            .removePrefix("[ВАЖНО]")
            .removePrefix("[URGENT]")
            .trim()
    }
}
