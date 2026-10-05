package ru.example.parentwatch.debug

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ru.example.parentwatch.R
import ru.example.parentwatch.service.AssistantRecoveryAccess

/** Owner chooses the system assistant; the application never assigns its own role. */
class AssistantPilotControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }
    override fun onResume() {
        super.onResume()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
            setBackgroundColor(getColor(R.color.cw_color_background))
        }
        fun text(value: String, size: Float = 18f) { content.addView(TextView(this).apply {
            text = value; textSize = size; setPadding(0, dp(10), 0, dp(10))
            setTextColor(getColor(R.color.cw_color_on_background))
        }) }
        fun button(value: String, action: () -> Unit) { content.addView(Button(this).apply {
            text = value; isAllCaps = false; setOnClickListener { action() }
        }) }
        text(getString(R.string.assistant_recovery_title), 26f)
        text(getString(R.string.assistant_recovery_explanation))
        text(getString(R.string.assistant_recovery_privacy))
        text(getString(R.string.assistant_recovery_limit), 16f)
        text(getString(when {
            AssistantRecoveryAccess.isSelected(this) -> R.string.assistant_recovery_selected
            AssistantRecoveryAccess.isEnabled(this) -> R.string.assistant_recovery_pending
            else -> R.string.assistant_recovery_off
        }))
        if (!ru.example.parentwatch.service.MonitoringRecovery.isDesired(this)) text(getString(R.string.assistant_recovery_monitoring_off))
        text(getString(R.string.assistant_recovery_steps), 16f)
        button(getString(R.string.assistant_recovery_choose)) {
            getSharedPreferences("assistant_recovery_pilot", MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
            candidates(PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            // Some Android 11 role controllers return immediately without a chooser.
            // The system's assistant settings expose the observed, working selection UI.
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }
        button(getString(R.string.assistant_recovery_restore)) { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        button(getString(R.string.assistant_recovery_disable)) {
            if (FamilyAssistantService.active(this)) {
                text(getString(R.string.assistant_recovery_restore_first))
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } else {
                getSharedPreferences("assistant_recovery_pilot", MODE_PRIVATE).edit().putBoolean("enabled", false).commit()
                candidates(PackageManager.COMPONENT_ENABLED_STATE_DISABLED); recreate()
            }
        }
        button(getString(R.string.assistant_recovery_close)) { finish() }
        setContentView(ScrollView(this).apply { addView(content) })
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun candidates(state: Int) {
        for (type in listOf(FamilyAssistantService::class.java, FamilyAssistantSessionService::class.java,
            FamilyRecognitionService::class.java)) {
            packageManager.setComponentEnabledSetting(ComponentName(this, type), state, PackageManager.DONT_KILL_APP)
        }
    }
}
