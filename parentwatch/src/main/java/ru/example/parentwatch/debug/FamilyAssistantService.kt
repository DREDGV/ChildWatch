package ru.example.parentwatch.debug

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ru.example.parentwatch.ChatConversationsActivity
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.utils.AppVisibilityTracker
import ru.example.parentwatch.service.AssistantRecoveryAccess
import ru.example.parentwatch.service.MonitoringRecovery
import ru.example.parentwatch.service.ChatBackgroundService
import ru.example.parentwatch.service.LocationService
import ru.example.parentwatch.session.ChildEffectiveContextResolver
import android.util.Log

/** Stable component name preserves the system choice across upgrades. No automatic capture or UI. */
open class FamilyAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        if (!isActiveService(this, ComponentName(this, javaClass))) return
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        Log.i("FamilyAssistant", "System assistant ready uptimeMs=${SystemClock.elapsedRealtime()}, visible=${AppVisibilityTracker.isVisible()}")
        if (AssistantRecoveryAccess.isEnabled(this) && MonitoringRecovery.isDesired(this)) {
            val session = ChildEffectiveContextResolver(this).resolveEffectiveContext()
            if (session != null && session.serverUrl.isNotBlank() && session.ownChildDeviceId.isNotBlank()) {
                runCatching {
                    ChatBackgroundService.start(this, session.serverUrl, session.ownChildDeviceId)
                    LocationService.startTrackingService(this, Intent(this, LocationService::class.java).apply {
                        action = LocationService.ACTION_START
                        putExtra("server_url", session.serverUrl)
                        putExtra("device_id", session.ownChildDeviceId)
                    }, rememberIntent = false)
                }.onFailure {
                    Log.w("FamilyAssistant", "Monitoring recovery deferred", it)
                    MonitoringRecovery.scheduleRetry(this)
                }
            } else MonitoringRecovery.scheduleRetry(this)
        }
        onSystemReady()
    }
    protected open fun onSystemReady() = Unit
    companion object {
        fun active(context: Context): Boolean = isActiveService(context,
            ComponentName(context, FamilyAssistantService::class.java))
    }
}

/** Real, explicitly invoked family shortcuts; no persistent UI and no automatic messages. */
class FamilyAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = FamilyAssistantSession(this)
}

private class FamilyAssistantSession(private val appContext: Context) : VoiceInteractionSession(appContext) {
    override fun onCreateContentView(): View {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(context.getColor(ru.example.parentwatch.R.color.cw_color_background))
        }
        content.addView(TextView(context).apply {
            text = context.getString(ru.example.parentwatch.R.string.assistant_family_title); textSize = 24f
            setTextColor(context.getColor(ru.example.parentwatch.R.color.cw_color_on_background))
        })
        fun action(label: String, onClick: () -> Unit) {
            content.addView(Button(context).apply {
                text = label; isAllCaps = false
                setOnClickListener { onClick() }
            })
        }
        fun open(type: Class<*>) {
            appContext.startActivity(Intent(appContext, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hide()
        }
        action(context.getString(ru.example.parentwatch.R.string.assistant_family_chat)) { open(ChatConversationsActivity::class.java) }
        action(context.getString(ru.example.parentwatch.R.string.assistant_family_app)) { open(MainActivity::class.java) }
        action(context.getString(ru.example.parentwatch.R.string.assistant_family_close)) { hide() }
        return content
    }
}
