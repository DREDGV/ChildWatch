package ru.example.parentwatch.debug

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import ru.example.parentwatch.service.CameraService
import ru.example.parentwatch.utils.AppVisibilityTracker
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One finite OS-capability experiment in the ordinary app process, emulator only. */
class AssistantCaptureProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!HomeBackgroundCaptureProbe.isIsolated(this) || !AssistantLabVoiceService.selected(this) ||
            !getSharedPreferences("assistant_lab", MODE_PRIVATE).getBoolean("captureEnabled", false)) {
            stopSelf(); return START_NOT_STICKY
        }
        val result = JSONObject().put("uptimeMs", SystemClock.elapsedRealtime())
            .put("assistantActive", true).put("deviceOwner", false)
            .put("visibleBefore", AppVisibilityTracker.isVisible())
        try {
            check(!AppVisibilityTracker.isVisible()) { "Activity visible: invalid background experiment" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("assistant_lab_capture", "Проверка помощника", NotificationManager.IMPORTANCE_LOW))
            val notification = NotificationCompat.Builder(this, "assistant_lab_capture")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Проверка семейного помощника")
                .setContentText("Короткий тест микрофона и камеры на эмуляторе").setOngoing(true).build()
            startForeground(784, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } catch (error: Exception) {
            result.put("pass", false).put("phase", "foreground").put("error", error.toString())
            writeResult(result); stopSelf(); return START_NOT_STICKY
        }
        scope.launch {
            val wake = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "ChildWatch:assistantLab")
            try {
                wake.acquire(45_000)
                delay(1500)
                val min = AudioRecord.getMinBufferSize(24_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(min > 0)
                val audio = AudioRecord(MediaRecorder.AudioSource.MIC, 24_000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 3840))
                try {
                    check(audio.state == AudioRecord.STATE_INITIALIZED)
                    audio.startRecording()
                    val bytes = audio.read(ByteArray(960), 0, 960, AudioRecord.READ_BLOCKING)
                    val silenced = audio.activeRecordingConfiguration?.isClientSilenced
                    result.put("audioBytes", bytes).put("audioSilenced", silenced ?: JSONObject.NULL)
                    check(bytes > 0 && silenced == false) { "No confirmed unsilenced microphone frames" }
                } finally { runCatching { audio.stop() }; audio.release() }
                val camera = CameraService(this@AssistantCaptureProbeService)
                val completed = CountDownLatch(1)
                var photo: File? = null
                try {
                    camera.capturePhoto(CameraService.CameraFacing.BACK) { photo = it; completed.countDown() }
                    check(completed.await(20, TimeUnit.SECONDS)) { "Camera timeout" }
                    check(photo != null && photo!!.length() > 0) { "Camera: ${camera.consumeLastFailureReason()}" }
                    photo!!.copyTo(File(filesDir, "assistant-probe-camera.jpg"), overwrite = true)
                    result.put("jpegBytes", photo!!.length())
                } finally { camera.release() }
                check(!AppVisibilityTracker.isVisible())
                result.put("visibleAfter", false).put("pass", true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { result.put("pass", false).put("phase", "capture").put("error", error.toString()) }
            finally {
                if (wake.isHeld) wake.release()
                writeResult(result); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun writeResult(result: JSONObject) {
        File(filesDir, "assistant-probe-result.json").writeText(result.toString(2))
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
