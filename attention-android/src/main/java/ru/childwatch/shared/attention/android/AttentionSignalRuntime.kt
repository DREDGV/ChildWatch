package ru.childwatch.shared.attention.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import ru.childwatch.shared.attention.AttentionSignalRequest
import ru.childwatch.shared.attention.AttentionSignalReceiverCoordinator

class AttentionSignalRuntime(
    context: Context,
    private val ownDeviceId: () -> String,
    private val emitStatus: (JSONObject) -> Unit,
    private val serviceClass: Class<*>,
    private val mainActivityClass: Class<*>,
    private val stopAction: String,
    private val notificationIcon: Int,
    private val appName: String = "ChildWatch"
) : AttentionSignalController.Callback {
    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val controller = AttentionSignalController(appContext, this)
    private val coordinator = AttentionSignalReceiverCoordinator(
        ownDeviceId = ownDeviceId,
        emitStatus = { emitStatus(AttentionSignalJson.statusToJson(it)) },
        startPlayback = controller::start,
        stopPlayback = controller::stop
    )

    fun handleStart(payload: JSONObject?) {
        val request = runCatching { payload?.let(AttentionSignalJson::requestFromJson) }
            .getOrNull() ?: return
        coordinator.handleStart(request)
    }

    fun handleStop(payload: JSONObject?) {
        val requestId = payload?.optString("requestId").orEmpty().trim()
        coordinator.handleStop(requestId)
    }

    fun stopLocally(requestId: String? = null): Boolean =
        controller.stop(requestId, "LOCAL_USER")

    fun shutdown() {
        controller.stop(reason = "SERVICE_DESTROYED")
    }

    override fun onStarted(request: AttentionSignalRequest) {
        showNotification(request)
        coordinator.onStarted(request)
    }

    override fun onStopped(request: AttentionSignalRequest, reason: String) {
        cancelNotification(request.requestId)
        coordinator.onStopped(request, reason)
    }

    override fun onCompleted(request: AttentionSignalRequest) {
        cancelNotification(request.requestId)
        coordinator.onCompleted(request)
    }

    override fun onFailed(request: AttentionSignalRequest, code: String, error: Throwable?) {
        cancelNotification(request.requestId)
        Log.e(TAG, "Attention signal playback failed: $code", error)
        coordinator.onFailed(request, code, error?.message)
    }

    private fun showNotification(request: AttentionSignalRequest) {
        createNotificationChannel()
        val stopIntent = Intent(appContext, serviceClass).apply {
            action = stopAction
            putExtra(EXTRA_REQUEST_ID, request.requestId)
        }
        val stopPendingIntent = PendingIntent.getService(
            appContext,
            notificationId(request.requestId),
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = PendingIntent.getActivity(
            appContext,
            notificationId(request.requestId) xor 0x4000,
            Intent(appContext, mainActivityClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(notificationIcon)
            .setContentTitle("Сигнал внимания")
            .setContentText("${request.requesterDisplayName} просит обратить внимание")
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(notificationIcon, "Остановить", stopPendingIntent)
            .build()
        runCatching { notificationManager.notify(notificationId(request.requestId), notification) }
            .onFailure { Log.w(TAG, "Unable to show attention notification", it) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "$appName — сигналы внимания",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Входящие сигналы внимания от членов семьи"
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun cancelNotification(requestId: String) {
        notificationManager.cancel(notificationId(requestId))
    }

    private fun notificationId(requestId: String): Int =
        NOTIFICATION_ID_BASE + (requestId.hashCode() and 0x0fff)

    companion object {
        const val EXTRA_REQUEST_ID = "attention_request_id"
        private const val TAG = "AttentionSignalRuntime"
        private const val CHANNEL_ID = "attention_signal_v1"
        private const val NOTIFICATION_ID_BASE = 18_000
    }
}
