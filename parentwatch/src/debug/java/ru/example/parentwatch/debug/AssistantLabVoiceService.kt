package ru.example.parentwatch.debug

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import ru.example.parentwatch.utils.AppVisibilityTracker
import java.io.File

/** Isolated finite probe; never packaged in a release or used on physical phones. */
class AssistantLabVoiceService : FamilyAssistantService() {
    private val handler = Handler(Looper.getMainLooper())
    override fun onSystemReady() {
        if (!HomeBackgroundCaptureProbe.isIsolated(this) || !selected(this)) return
        File(filesDir, "assistant-ready.json").writeText(JSONObject()
            .put("uptimeMs", SystemClock.elapsedRealtime()).put("systemSelected", true)
            .put("appVisible", AppVisibilityTracker.isVisible()).toString(2))
        if (!getSharedPreferences("assistant_lab", MODE_PRIVATE).getBoolean("captureEnabled", false)) return
        handler.postDelayed({
            if (selected(this) && HomeBackgroundCaptureProbe.isIsolated(this)) {
                runCatching { startForegroundService(Intent(this, AssistantCaptureProbeService::class.java)) }
                    .onFailure { File(filesDir, "assistant-probe-result.json").writeText(JSONObject()
                        .put("pass", false).put("phase", "service_request").put("error", it.toString()).toString(2)) }
            }
        }, 25_000)
    }
    override fun onShutdown() { handler.removeCallbacksAndMessages(null); super.onShutdown() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    companion object {
        fun selected(context: Context): Boolean = isActiveService(context, ComponentName(context, AssistantLabVoiceService::class.java))
    }
}
