package ru.example.parentwatch.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ru.example.parentwatch.MainActivity
import ru.example.parentwatch.R
import ru.example.parentwatch.service.LocationService
import ru.example.parentwatch.service.MonitoringRecovery
import ru.example.parentwatch.service.ChatBackgroundService
import ru.example.parentwatch.service.PhotoCaptureService
import ru.example.parentwatch.session.ChildEffectiveContextResolver

/** A real, visible Home for an explicitly selected pilot. Disabled by default; debug only. */
class HomeRecoveryProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private lateinit var status: TextView
    private val recoverWhenVisible = Runnable { recoverServices() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!HomePilotAccess.isAllowed(applicationContext)) { finish(); return }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
            setBackgroundColor(getColor(R.color.cw_color_background))
        }
        fun heading(label: Int, size: Float) {
            content.addView(TextView(this).apply {
                setText(label); textSize = size
                setTextColor(getColor(R.color.cw_color_on_background))
                setPadding(0, dp(12), 0, dp(12))
            })
        }
        fun button(label: CharSequence, action: () -> Unit) {
            content.addView(Button(this).apply {
                text = label; isAllCaps = false; minHeight = dp(56)
                setTextColor(getColor(R.color.cw_color_primary))
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        heading(R.string.home_probe_title, 30f)
        status = TextView(this).apply {
            textSize = 16f; setTextColor(getColor(R.color.cw_color_on_background))
            setPadding(0, dp(12), 0, dp(12))
        }
        content.addView(status)
        updateStatus()
        button(getString(R.string.home_probe_family)) { open(Intent(this, MainActivity::class.java)) }
        // The way back is above the app list and remains reachable on a populated handset.
        button(getString(R.string.home_pilot_settings)) {
            open(if (HomePilotAccess.isEnabled(this)) Intent(this, HomePilotControlActivity::class.java)
                else Intent(Settings.ACTION_HOME_SETTINGS))
        }
        heading(R.string.home_probe_apps, 20f)
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
            .forEach { app ->
                button(app.loadLabel(packageManager)) {
                    open(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(app.activityInfo.packageName, app.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                }
            }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        HomeBackgroundCaptureProbe.cancelPending()
        recoverServices()
        // Service creation is asynchronous; retry only while the genuine Home is still visible.
        handler.postDelayed(recoverWhenVisible, 1_200)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && resumed) {
            handler.removeCallbacks(recoverWhenVisible)
            handler.postDelayed(recoverWhenVisible, 300)
        }
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(recoverWhenVisible)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Automatic test audio/photo must never run on an opted-in physical handset.
        if (HomeBackgroundCaptureProbe.isIsolated(applicationContext))
            HomeBackgroundCaptureProbe.schedule(applicationContext)
    }

    override fun onDestroy() {
        handler.removeCallbacks(recoverWhenVisible)
        super.onDestroy()
    }

    private fun recoverServices() {
        if (!resumed || !HomePilotAccess.isAllowed(this)) return
        updateStatus()
        if (!MonitoringRecovery.isDesired(this)) return
        val session = ChildEffectiveContextResolver(this).resolveEffectiveContext()
        if (session == null || session.serverUrl.isBlank() || session.ownChildDeviceId.isBlank()) {
            status.setText(R.string.home_pilot_setup_needed)
            return
        }
        // A visible Activity gives the normal Android allowance. Home itself grants no capture privilege.
        runCatching {
            LocationService.startTrackingService(this, Intent(this, LocationService::class.java).apply {
                action = LocationService.ACTION_START
                putExtra("server_url", session.serverUrl)
                putExtra("device_id", session.ownChildDeviceId)
            }, rememberIntent = false)
            LocationService.retryAudioAfterForeground(this)
            if (!HomeBackgroundCaptureProbe.isIsolated(this)) {
                // Same normal paths as the child app; no synthetic capture or test session on phones.
                if (!ChatBackgroundService.isRunning)
                    ChatBackgroundService.start(this, session.serverUrl, session.ownChildDeviceId)
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    PhotoCaptureService.start(this, session.serverUrl, session.ownChildDeviceId)
            }
        }.onFailure {
            Log.w("HomeRecoveryPilot", "Foreground recovery deferred", it)
            status.setText(R.string.home_pilot_recovery_failed)
        }
    }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        status.setText(when {
            !MonitoringRecovery.isDesired(this) -> R.string.home_probe_disabled
            LocationService.isMonitoringActive -> R.string.home_pilot_services_active
            else -> R.string.home_pilot_recovery_pending
        })
    }

    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.home_probe_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
