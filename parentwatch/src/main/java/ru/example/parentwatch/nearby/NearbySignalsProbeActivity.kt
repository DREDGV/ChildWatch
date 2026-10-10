package ru.example.parentwatch.nearby

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.childwatch.shared.nearby.*
import ru.example.parentwatch.R
import ru.example.parentwatch.databinding.ActivityNearbySignalsProbeBinding

/** Owner-started local radio experiment, reached through PIN-protected child settings. */
class NearbySignalsProbeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNearbySignalsProbeBinding
    private lateinit var model: NearbyProbeViewModel
    private var rendered: NearbySignalsSnapshot? = null
    private val ageLabels = mutableListOf<Triple<TextView, NearbySignal, Long>>()
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Permission approval alone never starts a scan.
        refreshControls()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[NearbyProbeViewModel::class.java]
        if (!model.current()) { finish(); return }
        binding = ActivityNearbySignalsProbeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.probeAllowed.isChecked = model.allowed()
        binding.probeAllowed.setOnCheckedChangeListener { _, value ->
            model.allow(value)
            if (!value) { rendered = null; ageLabels.clear(); binding.probeResults.removeAllViews() }
            refreshControls()
        }
        binding.probePermissions.setOnClickListener {
            if (model.allowed()) permissions.launch(NearbySignalsScanner.requiredPermissions())
        }
        binding.probeScan.setOnClickListener {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && model.allowed())
                model.start(binding.probeActiveWifi.isChecked)
        }
        binding.probeCancel.setOnClickListener { model.cancel() }
        binding.probeClose.setOnClickListener { model.cancel(); finish() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch { model.state.collect { state ->
                    if (!model.current()) { model.cancel(); finish(); return@collect }
                    if (state.snapshot !== rendered) {
                        rendered = state.snapshot
                        renderSnapshot(state.snapshot)
                    }
                    refreshControls()
                } }
                launch { while (isActive) {
                    if (!model.current()) { model.cancel(); finish(); return@launch }
                    refreshAges(); refreshControls(); delay(1_000)
                } }
            }
        }
    }
    override fun onResume() { super.onResume(); if (::binding.isInitialized) refreshControls() }
    override fun onDestroy() {
        if (isFinishing && ::model.isInitialized) model.cancel()
        ageLabels.clear()
        super.onDestroy()
    }
    private fun refreshControls() {
        if (!::binding.isInitialized) return
        val allowed = model.allowed()
        val state = model.state.value
        val remaining = (state.nextScanElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        binding.probePermissions.isEnabled = allowed && !state.running
        binding.probeActiveWifi.isEnabled = allowed && !state.running
        binding.probeScan.isEnabled = allowed && !state.running && remaining == 0L
        binding.probeCooldown.visibility = if (allowed && !state.running && remaining > 0) View.VISIBLE else View.GONE
        binding.probeCooldown.text = getString(R.string.nearby_probe_cooldown, (remaining + 999) / 1000)
        binding.probeCancel.visibility = if (state.running) View.VISIBLE else View.GONE
        binding.probeProgress.visibility = if (state.running) View.VISIBLE else View.GONE
        val missing = NearbySignalsScanner.requiredPermissions().any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        binding.probeStatus.text = getString(when {
            !allowed -> R.string.nearby_probe_off
            state.running -> R.string.nearby_probe_scanning
            state.failed -> R.string.nearby_probe_failed
            state.cancelled -> R.string.nearby_probe_cancelled
            state.snapshot != null -> R.string.nearby_probe_done
            missing -> R.string.nearby_probe_permission_hint
            else -> R.string.nearby_probe_ready
        })
    }
    private fun text(parent: LinearLayout, value: String, title: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = if (title) 18f else 14f
        setTextColor(getColor(if (title) R.color.cw_color_on_surface else R.color.cw_color_on_surface_variant))
        if (title) setTypeface(null, android.graphics.Typeface.BOLD)
        val pad = (8 * resources.displayMetrics.density).toInt()
        setPadding(0, pad, 0, pad)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun renderSnapshot(snapshot: NearbySignalsSnapshot?) {
        ageLabels.clear(); binding.probeResults.removeAllViews()
        if (snapshot == null) return
        if (snapshot.screenOffDuringScan) text(binding.probeResults, getString(R.string.nearby_probe_screen_off))
        for (report in snapshot.reports) {
            val source = getString(when (report.source) {
                NearbySignalSource.WIFI -> R.string.nearby_source_wifi
                NearbySignalSource.BLUETOOTH_CLASSIC -> R.string.nearby_source_classic
                NearbySignalSource.BLE -> R.string.nearby_source_ble
            })
            text(binding.probeResults, source, true)
            text(binding.probeResults, getString(when (report.state) {
                NearbySourceState.DISABLED -> R.string.nearby_probe_off
                NearbySourceState.COOLDOWN -> R.string.nearby_state_cooldown
                NearbySourceState.PERMISSION_REQUIRED -> R.string.nearby_state_permission
                NearbySourceState.RADIO_OFF -> R.string.nearby_state_radio_off
                NearbySourceState.LOCATION_OFF -> R.string.nearby_state_location_off
                NearbySourceState.UNSUPPORTED -> R.string.nearby_state_unsupported
                NearbySourceState.SCAN_NOT_STARTED -> when (report.reason) {
                    "BLUETOOTH_CONNECTION_IN_USE", "DISCOVERY_ALREADY_RUNNING" -> R.string.nearby_state_bt_busy
                    "BLUETOOTH_CONNECTION_STATE_UNKNOWN" -> R.string.nearby_state_bt_unknown
                    else -> R.string.nearby_state_not_started
                }
                NearbySourceState.SCANNING -> R.string.nearby_probe_scanning
                NearbySourceState.READY -> R.string.nearby_state_ready
                NearbySourceState.EMPTY -> R.string.nearby_state_empty
                NearbySourceState.STALE -> R.string.nearby_state_stale
                NearbySourceState.FAILED -> R.string.nearby_probe_failed
                NearbySourceState.CANCELLED -> R.string.nearby_probe_cancelled
            }))
            for (signal in report.signals) {
                text(binding.probeResults, signal.name ?: getString(R.string.nearby_signal_unknown), true)
                val age = text(binding.probeResults, "")
                ageLabels.add(Triple(age, signal, snapshot.capturedElapsedMs))
            }
        }
        refreshAges()
    }
    private fun refreshAges() {
        val now = SystemClock.elapsedRealtime()
        for ((label, signal, captured) in ageLabels) {
            val age = signal.observedAgeMs?.takeIf { now >= captured }?.plus(now - captured)
            val freshness = when {
                age == null -> getString(R.string.nearby_age_unknown)
                age > NearbySignalsPolicy.FRESHNESS_MS -> getString(R.string.nearby_age_stale, (age + 999) / 1000)
                else -> getString(R.string.nearby_age_seconds, (age + 999) / 1000)
            }
            // A cached or old observation never becomes proof that the device is present now.
            label.text = getString(R.string.nearby_signal_detail, signal.rssiDbm, freshness)
        }
    }
}
