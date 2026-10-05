package ru.example.parentwatch.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import ru.example.parentwatch.BuildConfig
import ru.example.parentwatch.management.ManagedDeviceAccess
import ru.example.parentwatch.service.CameraService
import ru.example.parentwatch.service.LocationService
import ru.example.parentwatch.service.MonitoringRecovery
import ru.example.parentwatch.session.ChildActiveSessionStore
import ru.example.parentwatch.utils.AppVisibilityTracker
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs in the ordinary app process after Home disappears; no instrumentation or shell capture. */
internal object HomeBackgroundCaptureProbe {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null
    fun isIsolated(context: Context): Boolean = BuildConfig.DEBUG &&
        (Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") &&
        !ManagedDeviceAccess.isDeviceOwner(context) &&
        ChildActiveSessionStore(context).resolveCurrentChildId() == "isolated-home-boot-probe" &&
        ChildActiveSessionStore(context).resolveCurrentServerUrl() == "http://127.0.0.1:9"

    fun cancelPending() { pending?.cancel(); pending = null }
    fun schedule(context: Context, waitMs: Long = 6000) {
        if (!isIsolated(context)) return
        cancelPending()
        pending = scope.launch {
            delay(waitMs)
            if (AppVisibilityTracker.isVisible()) return@launch
            val result = JSONObject().put("uptimeMs", SystemClock.elapsedRealtime())
                .put("visibleBefore", false).put("deviceOwner", false)
                .put("monitoringActive", LocationService.isMonitoringActive)
            try {
                check(LocationService.isMonitoringActive) { "Monitoring did not recover" }
                // Exercise the production microphone FGS promotion, with no real server/network session.
                LocationService.requestAudioStart(context, false)
                delay(1500)
                val min = AudioRecord.getMinBufferSize(24_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(min > 0)
                val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 24_000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 3840))
                try {
                    check(recorder.state == AudioRecord.STATE_INITIALIZED)
                    recorder.startRecording()
                    check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    val count = recorder.read(ByteArray(960), 0, 960, AudioRecord.READ_BLOCKING)
                    val silenced = recorder.activeRecordingConfiguration?.isClientSilenced
                    result.put("audioBytes", count).put("audioSilenced", silenced ?: JSONObject.NULL)
                    check(count > 0 && silenced == false) { "No confirmed unsilenced microphone frames" }
                } finally {
                    runCatching { recorder.stop() }; recorder.release()
                }
                LocationService.pauseAudioCaptureForPhoto(context)
                val camera = CameraService(context)
                val delivered = CountDownLatch(1)
                var photo: File? = null
                try {
                    camera.capturePhoto(CameraService.CameraFacing.BACK) { photo = it; delivered.countDown() }
                    check(delivered.await(20, TimeUnit.SECONDS)) { "Camera timeout" }
                    check(photo != null && photo!!.length() > 0) { "Camera: ${camera.consumeLastFailureReason()}" }
                    photo!!.copyTo(File(context.filesDir, "home-probe-camera.jpg"), overwrite = true)
                    result.put("jpegBytes", photo!!.length())
                } finally { camera.release(); LocationService.resumeAudioCaptureAfterPhoto(context) }
                check(!AppVisibilityTracker.isVisible()) { "App became visible during capture" }
                result.put("visibleAfter", false).put("pass", true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                result.put("pass", false).put("error", error.toString())
                Log.e("HomeBootProbe", "Background capture failed", error)
            } finally { LocationService.requestAudioStop(context) }
            File(context.filesDir, "home-probe-result.json").writeText(result.toString(2))
            Log.i("HomeBootProbe", result.toString())
        }
    }
}

/** Disabled by default and emulator guarded; seeds only a fake local session before reboot. */
class HomeProbeSetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != "ru.example.parentwatch.HOME_PROBE_SETUP" ||
            (Build.HARDWARE != "ranchu" && Build.HARDWARE != "goldfish") ||
            ManagedDeviceAccess.isDeviceOwner(context)) return
        val sessions = ChildActiveSessionStore(context)
        sessions.applySession(sessions.buildSession(name = "Проверочный телефон",
            serverUrl = "http://127.0.0.1:9", ownChildDeviceId = "isolated-home-boot-probe", linkedParentDeviceId = ""))
        MonitoringRecovery.enable(context)
        context.getSharedPreferences("assistant_lab", Context.MODE_PRIVATE).edit()
            .putBoolean("captureEnabled", intent.getBooleanExtra("assistant_capture_lab", false)).commit()
        if (intent.getBooleanExtra("assistant_capture_lab", false)) {
            File(context.filesDir, "assistant-ready.json").delete()
            File(context.filesDir, "assistant-probe-result.json").delete()
        }
        Log.i("HomeBootProbe", "Isolated fixture prepared; reboot before testing")
    }
}

/** Baseline capture after a real boot, without any Home/Activity/instrumentation opening. */
class HomeProbeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            HomeBackgroundCaptureProbe.schedule(context.applicationContext, waitMs = 30_000)
        }
    }
}
