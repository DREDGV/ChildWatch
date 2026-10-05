package ru.example.parentwatch.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R
import ru.example.parentwatch.service.MonitoringRecovery
import ru.example.parentwatch.session.ChildEffectiveContextResolver

/** Phone pilot setup. Disabled by default and enabled only on the owner's spare handset. */
class HomePilotControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }

    override fun onResume() {
        super.onResume()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(24))
            setBackgroundColor(getColor(R.color.cw_color_background))
        }
        fun text(label: Int, size: Float = 16f) {
            content.addView(TextView(this).apply {
                setText(label); textSize = size
                setTextColor(getColor(R.color.cw_color_on_background))
                setPadding(0, dp(10), 0, dp(10))
            })
        }
        fun button(label: Int, action: () -> Unit) {
            content.addView(Button(this).apply {
                setText(label); isAllCaps = false; minHeight = dp(56)
                setTextColor(getColor(R.color.cw_color_primary))
                setOnClickListener {
                    runCatching(action).onFailure {
                        Toast.makeText(this@HomePilotControlActivity, R.string.home_probe_unavailable, Toast.LENGTH_LONG).show()
                    }
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        text(R.string.home_pilot_title, 28f)
        text(R.string.home_pilot_explanation)
        text(if (HomePilotAccess.isCurrentHome(this)) R.string.home_pilot_selected else R.string.home_pilot_not_selected)
        val session = ChildEffectiveContextResolver(this).resolveEffectiveContext()
        text(if (session == null || session.serverUrl.isBlank() || session.ownChildDeviceId.isBlank())
            R.string.home_pilot_setup_needed else R.string.home_pilot_session_present)
        text(if (MonitoringRecovery.isDesired(this)) R.string.home_probe_monitoring else R.string.home_probe_disabled)
        button(R.string.home_pilot_choose) {
            HomePilotAccess.enableCandidate(this)
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        button(R.string.home_probe_family) { startActivity(Intent(this, MainActivity::class.java)) }
        button(R.string.home_pilot_restore) { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        button(R.string.home_pilot_disable) {
            if (HomePilotAccess.disableCandidate(this)) {
                recreate()
            } else {
                Toast.makeText(this, R.string.home_pilot_restore_first, Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            }
        }
        text(R.string.home_pilot_unlock)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
