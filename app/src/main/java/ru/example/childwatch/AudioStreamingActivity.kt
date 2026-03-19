package ru.example.childwatch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.example.childwatch.audio.AudioEnhancer
import ru.example.childwatch.audio.FilterMode
import ru.example.childwatch.databinding.ActivityAudioStreamingBinding
import ru.example.childwatch.audio.AudioEnhancer.VolumeMode
import ru.example.childwatch.diagnostics.AudioStreamMetrics
import ru.example.childwatch.diagnostics.AudioStatus
import ru.example.childwatch.diagnostics.WsStatus
import ru.example.childwatch.diagnostics.NetworkType
import ru.example.childwatch.diagnostics.PingStatus
import android.graphics.Color
import ru.example.childwatch.network.DeviceStatus
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.recordings.RecordingsLibraryActivity
import ru.example.childwatch.service.AudioPlaybackService
import ru.example.childwatch.ui.AdvancedAudioVisualizer
import ru.example.childwatch.utils.SecureSettingsManager
import java.text.SimpleDateFormat
import java.util.*

/**
 * Enhanced Audio Streaming Activity with Quality Modes and Advanced Visualization
 *
 * Features:
 * - Multiple audio quality modes (Normal, Noise Reduction, Voice Enhanced, etc.)
 * - Advanced audio visualization (Waveform, Frequency Bars, Volume Meter, Circular)
 * - Real-time audio processing with customizable settings
 * - Service-based architecture (continues when screen is off)
 */
class AudioStreamingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AudioStreamingActivity"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_SERVER_URL = "server_url"
        private const val DEFAULT_SAMPLE_RATE = 24_000
        private const val CHILD_STATUS_FRESH_MS = 60_000L
        private const val STATUS_POLL_ACTIVE_MS = 10_000L
        private const val STATUS_POLL_IDLE_MS = 25_000L
    }

    private lateinit var binding: ActivityAudioStreamingBinding
    private lateinit var deviceId: String
    private lateinit var serverUrl: String
    private lateinit var audioPrefs: SharedPreferences
    private lateinit var secureSettings: SecureSettingsManager
    private val networkClient by lazy { NetworkClient(this) }
    private val gson by lazy { Gson() }

    // Audio Filter Management
    private var currentFilterMode = FilterMode.ORIGINAL

    // Volume Mode Management
    private var currentVolumeMode = VolumeMode.QUIET
    private val availableSampleRates = listOf(DEFAULT_SAMPLE_RATE)
    private var selectedSampleRate = DEFAULT_SAMPLE_RATE
    private var qualitySwitchInProgress = false

    // Child device status cache
    private var childBatteryLevel: Int? = null
    private var childCharging: Boolean? = null
    private var childStatusTimestamp: Long? = null
    private var statusPollingJob: Job? = null

    // Visualization
    private val currentVisualizationMode = AdvancedAudioVisualizer.VisualizationMode.WAVEFORM

    private var audioService: AudioPlaybackService? = null
    private var serviceBound = false
    private var streamingStartTime: Long = 0L
    private var metricsCollectorJob: Job? = null
    private var uiUpdateJob: Job? = null
    private var transitionResetJob: Job? = null
    private var streamingActionInProgress = false
    private var desiredRecordingEnabled = false

    private val recordingToggleListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        desiredRecordingEnabled = isChecked
        if (AudioPlaybackService.isPlaying) {
            toggleRecording(isChecked)
        }
    }

    private enum class HudMode { COMPACT, EXPANDED }
    private var hudMode = HudMode.EXPANDED

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioPlaybackService.LocalBinder
            audioService = binder.getService()
            syncFilterModeWithService()
            syncVolumeModeWithService()
            serviceBound = true
            desiredRecordingEnabled = audioService?.isRecordingEnabled() ?: desiredRecordingEnabled
            syncRecordingSwitch(desiredRecordingEnabled)
            Log.d(TAG, "Service connected")

            audioService?.setWaveformCallback { audioData ->
                runOnUiThread {
                    binding.advancedAudioVisualizer.updateVisualization(audioData)
                    updateSignalLevel(audioData)
                }
            }

            metricsCollectorJob?.cancel()
            metricsCollectorJob = lifecycleScope.launch {
                audioService?.metricsManager?.metrics?.collect { metrics ->
                    runOnUiThread {
                        updateHUD(metrics)
                    }
                }
            }

            finishStreamingTransition()
            updateUI()
            startUIUpdateLoop()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService?.setWaveformCallback(null)
            audioService = null
            serviceBound = false
            metricsCollectorJob?.cancel()
            finishStreamingTransition()
            updateUI()
            if (AudioPlaybackService.isSessionDesired(this@AudioStreamingActivity)) {
                lifecycleScope.launch {
                    delay(1200L)
                    AudioPlaybackService.restoreIfNeeded(this@AudioStreamingActivity)
                    bindPlaybackService()
                }
            }
            Log.d(TAG, "Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioStreamingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        secureSettings = SecureSettingsManager(this)
        audioPrefs = getSharedPreferences("audio_streaming", MODE_PRIVATE)

        deviceId = resolveTargetDeviceIdForStreaming()
            ?: run {
                Toast.makeText(
                    this,
                    getString(R.string.listen_error_missing_device),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return
            }

        val resolvedServerUrl = intent.getStringExtra(EXTRA_SERVER_URL)
            ?: secureSettings.getServerUrl().trim()
        if (resolvedServerUrl.isBlank()) {
            Toast.makeText(
                this,
                getString(R.string.listen_error_missing_server),
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }
        serverUrl = resolvedServerUrl
        secureSettings.setServerUrl(serverUrl)

        loadAudioSettings()
        loadCachedStatus()
        setupUI()

        // Check if service is already running
        if (AudioPlaybackService.isPlaying) {
            bindPlaybackService()
            streamingStartTime = AudioPlaybackService.streamingStartTime.takeIf { it > 0L } ?: System.currentTimeMillis()
        } else if (AudioPlaybackService.isSessionDesired(this)) {
            AudioPlaybackService.restoreIfNeeded(this)
            bindPlaybackService()
        }

        updateUI()
    }

    private fun resolveTargetDeviceIdForStreaming(): String? {
        val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        val legacyPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val myDeviceId = prefs.getString("device_id", null)?.trim().orEmpty()
        val excluded = listOf(
            myDeviceId,
            prefs.getString("parent_device_id", null),
            prefs.getString("linked_parent_device_id", null),
            legacyPrefs.getString("parent_device_id", null),
            legacyPrefs.getString("linked_parent_device_id", null)
        )
            .mapNotNull { it?.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        val fromIntent = intent.getStringExtra(EXTRA_DEVICE_ID)?.trim()
        if (!fromIntent.isNullOrBlank() && fromIntent !in excluded) {
            return fromIntent
        }

        return listOf(
            prefs.getString("child_device_id", null),
            secureSettings.getChildDeviceId(),
            legacyPrefs.getString("child_device_id", null),
            prefs.getString("selected_device_id", null),
            legacyPrefs.getString("selected_device_id", null)
        )
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() && it !in excluded }
    }

    private fun loadAudioSettings() {
        // Safe baseline for listening stability: no filter, no boosted gain, fixed 24 kHz.
        currentFilterMode = FilterMode.ORIGINAL
        currentVolumeMode = try {
            VolumeMode.valueOf(
                audioPrefs.getString("volume_mode", VolumeMode.QUIET.name) ?: VolumeMode.QUIET.name
            )
        } catch (_: IllegalArgumentException) {
            VolumeMode.QUIET
        }
        selectedSampleRate = sanitizeSampleRate(audioPrefs.getInt("audio_quality_hz", DEFAULT_SAMPLE_RATE))

        val savedHudMode = audioPrefs.getString("hud_mode", HudMode.EXPANDED.name)
        hudMode = try {
            HudMode.valueOf(savedHudMode ?: HudMode.EXPANDED.name)
        } catch (e: IllegalArgumentException) {
            HudMode.EXPANDED
        }
    }

    private fun loadCachedStatus() {
        try {
            val cachedStatus = secureSettings.getLastDeviceStatusForDevice(deviceId)
            if (!cachedStatus.isNullOrEmpty()) {
                val status = gson.fromJson(cachedStatus, DeviceStatus::class.java)
                childBatteryLevel = status?.batteryLevel
                childCharging = status?.isCharging
                childStatusTimestamp = normalizeStatusTimestamp(
                    status?.timestamp ?: secureSettings.getLastDeviceStatusTimestampForDevice(deviceId)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached device status", e)
        }
    }

    private fun saveAudioSettings() {
        audioPrefs.edit()
            .putString("filter_mode", currentFilterMode.name)
            .putString("volume_mode", currentVolumeMode.name)
            .putInt("audio_quality_hz", selectedSampleRate)
            .putString("hud_mode", hudMode.name)
            .apply()
    }

    private fun setupUI() {
        binding.deviceIdText.text = getString(R.string.listen_device_id, deviceId)
        binding.topAppBar.setNavigationOnClickListener { finish() }

        binding.filterCard.isVisible = false
        binding.advancedAudioVisualizer.setVisualizationMode(currentVisualizationMode)
        setupVolumeModeControls()
        setupHudModeControls()
        setupAudioQualitySelector()

        binding.toggleStreamingBtn.setOnClickListener {
            if (streamingActionInProgress) return@setOnClickListener

            Log.d(TAG, "Button clicked. isPlaying=${AudioPlaybackService.isPlaying}")
            beginStreamingTransition()
            if (AudioPlaybackService.isPlaying) {
                Log.d(TAG, "Calling stopStreaming()")
                stopStreaming()
            } else {
                Log.d(TAG, "Calling startStreaming()")
                startStreaming()
            }
        }

        binding.recordingSwitch.setOnCheckedChangeListener(recordingToggleListener)

        binding.volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val volume = (value / 100f).coerceIn(0f, 1f)
                audioService?.setVolume(volume)
                binding.volumeText.text = "${value.toInt()}%"
            }
        }

        binding.openRecordingsButton.setOnClickListener {
            startActivity(Intent(this, RecordingsLibraryActivity::class.java))
        }

        syncRecordingSwitch(desiredRecordingEnabled)
        updateBatteryHud()
        updateDiagnosticsSummary(AudioStreamMetrics())
    }

    private fun sanitizeSampleRate(candidate: Int): Int {
        return if (availableSampleRates.contains(candidate)) candidate else DEFAULT_SAMPLE_RATE
    }

    private fun setupAudioQualitySelector() {
        selectedSampleRate = DEFAULT_SAMPLE_RATE
        binding.audioQualityGroup.check(R.id.quality24Button)
        binding.qualityContainer.isVisible = false
    }

    private fun applyQualityChangeWhilePlaying(newRate: Int) {
        if (qualitySwitchInProgress) return

        qualitySwitchInProgress = true
        updateUI()
        Toast.makeText(
            this,
            getString(R.string.listen_quality_switching, newRate / 1000),
            Toast.LENGTH_SHORT
        ).show()

        // Use controlled restart for rate switch to avoid long-lived artifacts on some devices.
        stopStreaming()
        lifecycleScope.launch {
            delay(900)
            if (!isFinishing && !isDestroyed) {
                startStreaming()
            }
            delay(2600)
            if (!isFinishing && !isDestroyed) {
                val actualInputRate = AudioPlaybackService.inputStreamSampleRate
                if (actualInputRate == newRate) {
                    Toast.makeText(
                        this@AudioStreamingActivity,
                        getString(R.string.listen_quality_applied, actualInputRate / 1000),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@AudioStreamingActivity,
                        getString(R.string.listen_quality_fallback, actualInputRate / 1000),
                        Toast.LENGTH_LONG
                    ).show()
                    // Keep UI and persisted preference aligned with actual remote stream quality.
                    selectedSampleRate = sanitizeSampleRate(actualInputRate)
                    saveAudioSettings()
                    binding.audioQualityGroup.check(buttonIdForRate(selectedSampleRate))
                }
            }
            qualitySwitchInProgress = false
            updateUI()
        }
    }

    private fun buttonIdForRate(rate: Int): Int {
        return when (sanitizeSampleRate(rate)) {
            32_000 -> R.id.quality32Button
            48_000 -> R.id.quality48Button
            else -> R.id.quality24Button
        }
    }

    private fun restartStreamingWithNewQuality() {
        if (qualitySwitchInProgress) return

        qualitySwitchInProgress = true
        Toast.makeText(
            this,
            getString(R.string.listen_quality_switching, selectedSampleRate / 1000),
            Toast.LENGTH_SHORT
        ).show()

        stopStreaming()
        lifecycleScope.launch {
            delay(750)
            if (!isFinishing && !isDestroyed) {
                startStreaming()
            }
            qualitySwitchInProgress = false
            updateUI()
        }
    }

    private fun setupQualityModeChips() {
        // Setup filter mode RecyclerView with cards
        val filterItems = listOf(
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.ORIGINAL,
                "RAW",
                getString(R.string.audio_monitor_filter_original),
                getString(R.string.audio_monitor_filter_original_desc)
            ),
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.VOICE,
                "VOC",
                getString(R.string.audio_monitor_filter_voice),
                getString(R.string.audio_monitor_filter_voice_desc)
            ),
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.QUIET_SOUNDS,
                "LOW",
                getString(R.string.audio_monitor_filter_quiet),
                getString(R.string.audio_monitor_filter_quiet_desc)
            ),
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.MUSIC,
                "MUS",
                getString(R.string.audio_monitor_filter_music),
                getString(R.string.audio_monitor_filter_music_desc)
            ),
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.OUTDOOR,
                "OUT",
                getString(R.string.audio_monitor_filter_outdoor),
                getString(R.string.audio_monitor_filter_outdoor_desc)
            )
        )

        val filterAdapter = ru.example.childwatch.audio.AudioFilterAdapter(
            items = filterItems,
            selectedMode = currentFilterMode,
            onFilterSelected = { mode ->
                setFilterMode(mode)
                Toast.makeText(
                    this,
                    getString(R.string.audio_monitor_filter_applied, getFilterName(mode)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        binding.filterRecyclerView.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                this@AudioStreamingActivity,
                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                false
            )
            adapter = filterAdapter
            setHasFixedSize(true)
        }
    }

    private fun getFilterName(mode: FilterMode): String {
        return when (mode) {
            FilterMode.ORIGINAL -> getString(R.string.audio_monitor_filter_original)
            FilterMode.VOICE -> getString(R.string.audio_monitor_filter_voice)
            FilterMode.QUIET_SOUNDS -> getString(R.string.audio_monitor_filter_quiet)
            FilterMode.MUSIC -> getString(R.string.audio_monitor_filter_music)
            FilterMode.OUTDOOR -> getString(R.string.audio_monitor_filter_outdoor)
        }
    }

    private fun setupVolumeModeControls() {
        binding.gainX1Button.setOnClickListener { setVolumeMode(VolumeMode.QUIET) }
        binding.gainX2Button.setOnClickListener { setVolumeMode(VolumeMode.NORMAL) }
        binding.gainX3Button.setOnClickListener { setVolumeMode(VolumeMode.LOUD) }
        binding.gainX4Button.setOnClickListener { setVolumeMode(VolumeMode.BOOST) }
        binding.gainX5Button.setOnClickListener { setVolumeMode(VolumeMode.MAX) }
        updateVolumeModeControls()
    }

    private fun setupHudModeControls() {
        binding.hudCompactButton.setOnClickListener { setHudMode(HudMode.COMPACT) }
        binding.hudExpandedButton.setOnClickListener { setHudMode(HudMode.EXPANDED) }
        applyHudMode()
    }

    private fun setHudMode(mode: HudMode) {
        if (hudMode == mode) return
        hudMode = mode
        applyHudMode()
        saveAudioSettings()
    }

    private fun applyHudMode() {
        binding.hudRow2.isVisible = hudMode == HudMode.EXPANDED
        binding.hudModeGroup.check(
            if (hudMode == HudMode.EXPANDED) R.id.hudExpandedButton else R.id.hudCompactButton
        )
    }

    private fun syncRecordingSwitch(checked: Boolean) {
        binding.recordingSwitch.setOnCheckedChangeListener(null)
        binding.recordingSwitch.isChecked = checked
        binding.recordingSwitch.setOnCheckedChangeListener(recordingToggleListener)
    }

    private fun beginStreamingTransition() {
        streamingActionInProgress = true
        binding.toggleStreamingBtn.isEnabled = false
        transitionResetJob?.cancel()
        transitionResetJob = lifecycleScope.launch {
            delay(4_000)
            if (streamingActionInProgress) {
                finishStreamingTransition()
                updateUI()
            }
        }
    }

    private fun finishStreamingTransition() {
        streamingActionInProgress = false
        transitionResetJob?.cancel()
        transitionResetJob = null
    }

    private fun formatChildBatteryText(): String {
        val batteryDisplay = childBatteryLevel?.takeIf { it in 0..100 }
        val ageSuffix = statusAgeMs()
            ?.takeIf { it > CHILD_STATUS_FRESH_MS }
            ?.let { " · ${formatStatusAgeCompact(it)}" }
            .orEmpty()
        return when {
            batteryDisplay == null -> getString(R.string.listen_battery_waiting)
            childCharging == true -> getString(R.string.listen_battery_charging, batteryDisplay) + ageSuffix
            else -> getString(R.string.listen_battery_percent, batteryDisplay) + ageSuffix
        }
    }

    private fun buildConnectionHeadline(isPlaying: Boolean, initError: String?, lastChunkAgeMs: Long): String {
        return when {
            !initError.isNullOrBlank() -> getString(R.string.listen_connection_error)
            !isPlaying -> getString(R.string.listen_connection_idle)
            AudioPlaybackService.chunksReceived == 0 -> getString(R.string.listen_connection_waiting)
            lastChunkAgeMs in 0..1500 -> getString(R.string.listen_connection_stable)
            lastChunkAgeMs in 1501..6000 -> getString(R.string.listen_connection_delayed)
            else -> getString(R.string.listen_connection_reconnecting)
        }
    }

    private fun buildConnectionHint(isPlaying: Boolean, initError: String?, ageText: String): String {
        return when {
            !initError.isNullOrBlank() -> getString(R.string.listen_hint_error)
            !isPlaying -> getString(R.string.listen_hint_idle)
            AudioPlaybackService.chunksReceived == 0 -> getString(R.string.listen_hint_waiting)
            AudioPlaybackService.lastChunkTimestamp > 0L &&
                (System.currentTimeMillis() - AudioPlaybackService.lastChunkTimestamp) > 1500L ->
                getString(R.string.listen_hint_delayed, ageText)
            !isChildStatusFresh() && childStatusTimestamp != null ->
                getString(R.string.listen_hint_active_stale_status, formatStatusAgeLong(statusAgeMs()))
            else -> getString(R.string.listen_hint_active, ageText)
        }
    }

    private fun startChildStatusPolling() {
        if (statusPollingJob?.isActive == true) return
        statusPollingJob = lifecycleScope.launch {
            fetchChildStatus()
            while (isActive) {
                delay(if (AudioPlaybackService.isPlaying) STATUS_POLL_ACTIVE_MS else STATUS_POLL_IDLE_MS)
                fetchChildStatus()
            }
        }
    }

    private fun stopChildStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
    }

    private suspend fun fetchChildStatus() {
        try {
            if (serverUrl.isBlank()) {
                Log.w(TAG, "Skipping child status fetch: server URL not configured")
                return
            }
            val response = networkClient.getChildDeviceStatus(deviceId)
            if (response.isSuccessful) {
                val status = response.body()?.status
                if (status != null) {
                    applyChildStatus(status)
                } else {
                    syncChildStatusFromPlaybackService()
                }
            } else {
                Log.w(TAG, "Device status request failed: ${'$'}{response.code()}")
                syncChildStatusFromPlaybackService()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching device status", e)
            syncChildStatusFromPlaybackService()
        }
    }

    private fun setVolumeMode(mode: VolumeMode) {
        if (currentVolumeMode == mode) return
        currentVolumeMode = mode

        audioService?.let { service ->
            val currentConfig = service.getAudioEnhancerConfig()
            val newConfig = currentConfig.copy(volumeMode = currentVolumeMode)
            service.setAudioEnhancerConfig(newConfig)
        }

        updateVolumeModeControls()
        saveAudioSettings()

        Log.d(TAG, "Volume mode changed to: $currentVolumeMode")
    }

    private fun updateVolumeModeControls() {
        val checkedButtonId = when (currentVolumeMode) {
            VolumeMode.QUIET -> R.id.gainX1Button
            VolumeMode.NORMAL -> R.id.gainX2Button
            VolumeMode.LOUD -> R.id.gainX3Button
            VolumeMode.BOOST -> R.id.gainX4Button
            VolumeMode.MAX -> R.id.gainX5Button
        }
        val captionRes = when (currentVolumeMode) {
            VolumeMode.QUIET -> R.string.listen_gain_mode_quiet
            VolumeMode.NORMAL -> R.string.listen_gain_mode_normal
            VolumeMode.LOUD -> R.string.listen_gain_mode_loud
            VolumeMode.BOOST -> R.string.listen_gain_mode_boost
            VolumeMode.MAX -> R.string.listen_gain_mode_max
        }
        binding.gainModeGroup.check(checkedButtonId)
        binding.volumeModeCaptionText.text = getString(captionRes)
    }

    private fun setFilterMode(mode: FilterMode) {
        currentFilterMode = mode

        // Update service with new filter mode
        if (AudioPlaybackService.isPlaying) {
            audioService?.setFilterMode(mode)

            // Task 3: Send broadcast to ParentWatch to update SystemAudioEffects
            try {
                val intent = Intent("ru.example.childwatch.UPDATE_FILTER_MODE")
                intent.putExtra("filter_mode", mode.name)
                sendBroadcast(intent)
                Log.d(TAG, "Filter mode broadcast sent to ParentWatch: $mode")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending filter mode broadcast", e)
            }
        }

        // Update UI
        updateModeDescription()
        saveAudioSettings()

        Log.d(TAG, "Filter mode changed to: $mode")
    }

    private fun updateModeDescription() {
        val description = when (currentFilterMode) {
            FilterMode.ORIGINAL -> getString(R.string.audio_monitor_filter_original_desc)
            FilterMode.VOICE -> getString(R.string.audio_monitor_filter_voice_desc)
            FilterMode.QUIET_SOUNDS -> getString(R.string.audio_monitor_filter_quiet_desc)
            FilterMode.MUSIC -> getString(R.string.audio_monitor_filter_music_desc)
            FilterMode.OUTDOOR -> getString(R.string.audio_monitor_filter_outdoor_desc)
        }
        binding.modeDescriptionText?.text = description
    }

    private fun updateSignalLevel(audioData: ByteArray) {
        if (audioData.isEmpty()) {
            runOnUiThread {
                binding.signalLevelText.text = getString(R.string.listen_signal_no_data)
            }
            return
        }

        // Calculate signal level (simplified)
        var sum = 0f
        var count = 0

        for (i in audioData.indices step 2) {
            if (i + 1 < audioData.size) {
                val sample = ((audioData[i + 1].toInt() shl 8) or (audioData[i].toInt() and 0xFF)).toShort()
                sum += kotlin.math.abs(sample.toFloat() / Short.MAX_VALUE)
                count++
            }
        }

        val level = if (count > 0) sum / count else 0f
        val percentage = (level * 100).toInt()

        val levelText = when {
            percentage > 50 -> getString(R.string.listen_signal_strong)
            percentage > 20 -> getString(R.string.listen_signal_medium)
            percentage > 5 -> getString(R.string.listen_signal_weak)
            else -> getString(R.string.listen_signal_silence)
        }

        runOnUiThread {
            binding.signalLevelText.text = levelText
        }
    }

    private fun syncFilterModeWithService() {
        if (AudioPlaybackService.isPlaying) {
            audioService?.setFilterMode(currentFilterMode)
            Log.d(TAG, "Filter mode synced with service: $currentFilterMode")
        }
    }

    private fun syncVolumeModeWithService() {
        audioService?.let { service ->
            val currentConfig = service.getAudioEnhancerConfig()
            val newConfig = currentConfig.copy(volumeMode = currentVolumeMode)
            service.setAudioEnhancerConfig(newConfig)
            Log.d(TAG, "Volume mode synced with service: $currentVolumeMode")
        }
    }

    private fun applyChildStatus(status: DeviceStatus, updateUi: Boolean = true) {
        childBatteryLevel = status.batteryLevel?.takeIf { it in 0..100 }
        childCharging = status.isCharging

        val normalizedStatusTimestamp =
            normalizeStatusTimestamp(status.timestamp) ?: System.currentTimeMillis()
        val normalizedStatus = status.copy(
            batteryLevel = childBatteryLevel,
            timestamp = normalizedStatusTimestamp
        )
        val statusJson = gson.toJson(normalizedStatus)

        childStatusTimestamp = normalizedStatusTimestamp
        secureSettings.setLastDeviceStatus(statusJson)
        secureSettings.setLastDeviceStatusTimestamp(normalizedStatusTimestamp)
        secureSettings.setLastDeviceStatusForDevice(deviceId, statusJson)
        secureSettings.setLastDeviceStatusTimestampForDevice(deviceId, normalizedStatusTimestamp)

        if (updateUi) {
            runOnUiThread {
                updateBatteryHud()
                updateDiagnosticsSummary(audioService?.metricsManager?.metrics?.value ?: AudioStreamMetrics())
            }
        }
    }

    private fun syncChildStatusFromPlaybackService(updateUi: Boolean = true) {
        val remoteDeviceId = AudioPlaybackService.remoteChildBatteryDeviceId?.trim().orEmpty()
        if (remoteDeviceId.isBlank() || remoteDeviceId != deviceId.trim()) return

        val liveBattery = AudioPlaybackService.remoteChildBatteryLevel?.takeIf { it in 0..100 } ?: return
        val liveTimestamp = normalizeStatusTimestamp(AudioPlaybackService.remoteChildBatteryTimestamp) ?: return
        val currentTimestamp = childStatusTimestamp ?: 0L
        if (currentTimestamp > 0L && liveTimestamp <= currentTimestamp) return

        applyChildStatus(
            DeviceStatus(
                batteryLevel = liveBattery,
                isCharging = AudioPlaybackService.remoteChildCharging,
                chargingType = null,
                temperature = null,
                voltage = null,
                health = null,
                manufacturer = null,
                model = null,
                androidVersion = null,
                sdkVersion = null,
                currentAppName = null,
                currentAppPackage = null,
                timestamp = liveTimestamp,
                raw = null
            ),
            updateUi = updateUi
        )
    }

    private fun startStreaming() {
        Log.d(TAG, "Starting audio streaming...")

        // Improvement: Keep screen on during streaming to prevent system sleep
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "Screen lock enabled - preventing sleep during streaming")

        // Start visualizer
        binding.advancedAudioVisualizer.start()

        val intent = Intent(this, AudioPlaybackService::class.java).apply {
            action = AudioPlaybackService.ACTION_START_PLAYBACK
            putExtra(AudioPlaybackService.EXTRA_DEVICE_ID, deviceId)
            putExtra(AudioPlaybackService.EXTRA_SERVER_URL, serverUrl)
            putExtra(AudioPlaybackService.EXTRA_RECORDING, desiredRecordingEnabled)
            putExtra(AudioPlaybackService.EXTRA_SAMPLE_RATE, selectedSampleRate)
        }

        startForegroundService(intent)
        bindPlaybackService()

        streamingStartTime = System.currentTimeMillis()
        updateUI()
    }

    private fun stopStreaming() {
        Log.d(TAG, "Stopping audio streaming...")

        // Improvement: Allow screen to sleep when streaming stops
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "Screen lock disabled - allowing normal sleep")

        // Stop visualizer
        binding.advancedAudioVisualizer.stop()

        val intent = Intent(this, AudioPlaybackService::class.java).apply {
            action = AudioPlaybackService.ACTION_STOP_PLAYBACK
        }
        startForegroundService(intent)

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }

        syncRecordingSwitch(false)
        desiredRecordingEnabled = false
        updateUI()
    }

    private fun toggleRecording(recording: Boolean) {
        val intent = Intent(this, AudioPlaybackService::class.java).apply {
            action = AudioPlaybackService.ACTION_TOGGLE_RECORDING
            putExtra(AudioPlaybackService.EXTRA_RECORDING, recording)
        }
        startForegroundService(intent)

        val toastRes = if (recording) {
            R.string.listen_toast_recording_on
        } else {
            R.string.listen_toast_recording_off
        }
        Toast.makeText(this, getString(toastRes), Toast.LENGTH_SHORT).show()
    }

    private fun startUIUpdateLoop() {
        uiUpdateJob?.cancel()
        uiUpdateJob = lifecycleScope.launch {
            while (isActive) {
                updateUI()
                delay(1000)
            }
        }
    }

    private fun updateUI() {
        syncChildStatusFromPlaybackService(updateUi = false)

        val isPlaying = AudioPlaybackService.isPlaying
        val lastChunkAgeMs = if (AudioPlaybackService.lastChunkTimestamp > 0L) {
            (System.currentTimeMillis() - AudioPlaybackService.lastChunkTimestamp).coerceAtLeast(0L)
        } else {
            -1L
        }
        val ageText = if (lastChunkAgeMs >= 0L) formatStreamAge(lastChunkAgeMs) else getString(R.string.audio_monitor_placeholder_dash)
        val initError = AudioPlaybackService.audioTrackInitError

        binding.toggleStreamingBtn.text = getString(
            if (isPlaying) R.string.listen_toggle_stop else R.string.listen_toggle_start
        )
        binding.toggleStreamingBtn.isEnabled = !streamingActionInProgress

        binding.statusText.text = getString(
            if (isPlaying) R.string.listen_state_running else R.string.listen_state_stopped
        )
        binding.statusText.setTextColor(getColor(android.R.color.white))
        binding.statusBadgeCard.setCardBackgroundColor(
            if (isPlaying) Color.parseColor("#1FBBF7D0") else Color.parseColor("#1FFFFFFF")
        )

        binding.recordingSwitch.isEnabled = isPlaying
        binding.childBatteryText.text = formatChildBatteryText()
        binding.connectionQualityText.text = buildConnectionHeadline(isPlaying, initError, lastChunkAgeMs)
        binding.connectionHintText.text = buildConnectionHint(isPlaying, initError, ageText)

        val qualityEnabled = !qualitySwitchInProgress
        binding.audioQualityGroup.isEnabled = qualityEnabled
        binding.quality24Button.isEnabled = qualityEnabled
        binding.quality32Button.isEnabled = qualityEnabled
        binding.quality48Button.isEnabled = qualityEnabled
        updateBatteryHud()
        updateDiagnosticsSummary(audioService?.metricsManager?.metrics?.value ?: AudioStreamMetrics())

        if (isPlaying) {
            val serviceStart = AudioPlaybackService.streamingStartTime
            if (serviceStart > 0L) {
                streamingStartTime = serviceStart
            }

            if (streamingStartTime > 0L) {
                val currentTime = System.currentTimeMillis()
                val duration = currentTime - streamingStartTime

                if (duration >= 0) {
                    val startTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    binding.startTimeText.text = startTimeFormat.format(Date(streamingStartTime))

                    val durationFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    durationFormat.timeZone = TimeZone.getTimeZone("UTC")
                    binding.durationText.text = durationFormat.format(Date(duration))
                }
            }
            binding.chunksReceivedText.text = AudioPlaybackService.chunksReceived.toString()
        } else {
            binding.startTimeText.text = getString(R.string.audio_monitor_time_placeholder)
            binding.durationText.text = getString(R.string.audio_monitor_duration_placeholder)
            binding.chunksReceivedText.text = getString(R.string.audio_monitor_placeholder_zero)
            streamingStartTime = 0L
        }
    }

    override fun onResume() {
        super.onResume()
        if (AudioPlaybackService.isSessionDesired(this) && !AudioPlaybackService.isPlaying) {
            AudioPlaybackService.restoreIfNeeded(this)
            bindPlaybackService()
        }
        startChildStatusPolling()
    }

    override fun onPause() {
        super.onPause()
        stopChildStatusPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopChildStatusPolling()
        metricsCollectorJob?.cancel()
        uiUpdateJob?.cancel()
        transitionResetJob?.cancel()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        binding.advancedAudioVisualizer.stop()
    }

    private fun bindPlaybackService() {
        if (serviceBound) return
        Intent(this, AudioPlaybackService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    /**
     * Enhanced HUD with 2 rows of detailed metrics
     */
    private fun updateHUD(metrics: AudioStreamMetrics) {
        val dash = getString(R.string.audio_monitor_placeholder_dash)

        val wsLabel = when (metrics.wsStatus) {
            WsStatus.CONNECTED -> "Сокет онлайн"
            WsStatus.CONNECTING -> "Подключение"
            WsStatus.RETRYING -> "Повтор"
            WsStatus.ERROR -> "Ошибка"
            else -> "Оффлайн"
        }
        val duration = formatDuration(metrics.connectionDuration)
        binding.hudWsStatus.text = "$wsLabel $duration"

        val networkLabel = if (metrics.networkName.isNotEmpty() && metrics.networkName != "Wi-Fi") {
            metrics.networkName.take(5)
        } else {
            when (metrics.networkType) {
                NetworkType.WIFI -> "WiFi"
                NetworkType.MOBILE -> "LTE"
                NetworkType.ETHERNET -> "LAN"
                else -> dash
            }
        }
        binding.hudNetwork.text = networkLabel

        val dataRateKB = metrics.bytesPerSecond / 1024
        val rateText = if (dataRateKB > 0) getString(R.string.listen_stream_kbps, dataRateKB.toInt()) else dash
        binding.hudDataRate.text = rateText

        val latencyMs = resolveLatencyMs(metrics)
        val latencyText = if (latencyMs > 0) {
            getString(R.string.listen_ping_value, latencyMs.toInt())
        } else {
            dash
        }
        binding.hudPing.text = latencyText
        binding.hudPing.setTextColor(getPingColor(resolveLatencyStatus(metrics, latencyMs)))

        val batteryDisplay = childBatteryLevel?.takeIf { it in 0..100 }
        val charging = childCharging == true
        binding.hudBattery.text = if (batteryDisplay == null) {
            dash
        } else {
            val suffix = statusAgeMs()?.let { " • ${formatStatusAgeCompact(it)}" }.orEmpty()
            if (charging) {
                "${batteryDisplay}%⚡$suffix"
            } else {
                "${batteryDisplay}%$suffix"
            }
        }

        val audioLabel = when (metrics.audioStatus) {
            AudioStatus.PLAYING -> "Идет"
            AudioStatus.BUFFERING -> "Буфер"
            AudioStatus.RECORDING -> "Запись"
            AudioStatus.ERROR -> "Сбой"
            else -> "Стоп"
        }
        binding.hudAudioStatus.text = audioLabel

        val queueText = if (metrics.queueCapacity > 0) {
            val underrunSuffix = if (metrics.underrunCount > 0) " !${metrics.underrunCount}" else ""
            "${metrics.queueDepth}/${metrics.queueCapacity}$underrunSuffix"
        } else {
            dash
        }
        binding.hudQueue.text = queueText

        val totalBytes = metrics.framesTotal * metrics.frameSize
        val totalMB = totalBytes / (1024.0 * 1024.0)
        val totalText = when {
            totalMB >= 1.0 -> "%.1fM".format(totalMB)
            totalBytes > 0 -> "%.0fK".format(totalBytes / 1024.0)
            else -> dash
        }
        binding.hudTotalData.text = totalText

        val sampleRateKHz = metrics.sampleRate / 1000.0
        binding.hudSampleRate.text = "%.1f кГц".format(sampleRateKHz)
        binding.hudRow2.isVisible = hudMode == HudMode.EXPANDED
        updateDiagnosticsSummary(metrics)
    }

    private fun updateBatteryHud() {
        val batteryDisplay = childBatteryLevel?.takeIf { it in 0..100 }
        binding.childBatteryText.text = formatChildBatteryText()
        binding.childBatteryMetaText.text = buildBatteryMetaText()
        binding.childBatteryIcon.setImageResource(
            if (batteryDisplay == null) {
                android.R.drawable.ic_menu_help
            } else if (childCharging == true) {
                android.R.drawable.ic_lock_idle_charging
            } else {
                android.R.drawable.ic_lock_idle_low_battery
            }
        )
    }

    private fun updateDiagnosticsSummary(metrics: AudioStreamMetrics) {
        val dash = getString(R.string.listen_diag_no_data)
        binding.diagConnectionValue.text = buildConnectionHeadline(
            AudioPlaybackService.isPlaying,
            AudioPlaybackService.audioTrackInitError,
            if (AudioPlaybackService.lastChunkTimestamp > 0L) {
                (System.currentTimeMillis() - AudioPlaybackService.lastChunkTimestamp).coerceAtLeast(0L)
            } else {
                -1L
            }
        )
        binding.diagConnectionValue.setTextColor(
            when {
                AudioPlaybackService.audioTrackInitError != null -> Color.parseColor("#B3261E")
                !AudioPlaybackService.isPlaying -> Color.parseColor("#6B7280")
                else -> Color.parseColor("#0F766E")
            }
        )

        val latencyMs = resolveLatencyMs(metrics)
        binding.diagLatencyValue.text = if (latencyMs > 0) {
            getString(R.string.listen_ping_value, latencyMs.toInt())
        } else {
            dash
        }
        binding.diagLatencyValue.setTextColor(getPingColor(resolveLatencyStatus(metrics, latencyMs)))

        val rateKb = (metrics.bytesPerSecond / 1024).toInt()
        binding.diagStreamValue.text = when {
            rateKb > 0 -> getString(R.string.listen_stream_kbps, rateKb)
            AudioPlaybackService.isPlaying -> getString(
                R.string.listen_stream_sample_rate,
                AudioPlaybackService.inputStreamSampleRate / 1000
            )
            else -> dash
        }

        val freshnessAge = statusAgeMs()
        val batteryDisplay = childBatteryLevel?.takeIf { it in 0..100 }
        binding.diagFreshnessValue.text = when {
            freshnessAge == null && batteryDisplay == null -> dash
            freshnessAge == null && batteryDisplay != null ->
                if (childCharging == true) "${batteryDisplay}% ⚡" else "${batteryDisplay}%"
            freshnessAge != null && batteryDisplay != null -> {
                val batteryText = if (childCharging == true) "${batteryDisplay}% ⚡" else "${batteryDisplay}%"
                "$batteryText · ${formatStatusAgeCompact(freshnessAge)}"
            }
            else -> formatStatusAgeCompact(freshnessAge ?: 0L)
        }
        binding.diagFreshnessValue.setTextColor(
            when {
                freshnessAge == null -> Color.parseColor("#6B7280")
                freshnessAge > CHILD_STATUS_FRESH_MS -> Color.parseColor("#B3261E")
                else -> Color.parseColor("#0F766E")
            }
        )
    }

    private fun buildBatteryMetaText(): String {
        val age = statusAgeMs() ?: return getString(R.string.listen_battery_meta_waiting)
        val ageText = formatStatusAgeLong(age)
        return if (age > CHILD_STATUS_FRESH_MS) {
            getString(R.string.listen_battery_meta_stale, ageText)
        } else {
            getString(R.string.listen_battery_meta_updated, ageText)
        }
    }

    private fun normalizeStatusTimestamp(rawTimestamp: Long?): Long? {
        val value = rawTimestamp ?: return null
        if (value <= 0L) return null
        return if (value < 1_000_000_000_000L) value * 1000L else value
    }

    private fun statusAgeMs(now: Long = System.currentTimeMillis()): Long? {
        val timestamp = childStatusTimestamp
            ?: secureSettings.getLastDeviceStatusTimestampForDevice(deviceId).takeIf { it > 0L }
        return timestamp?.let { (now - it).coerceAtLeast(0L) }
    }

    private fun isChildStatusFresh(now: Long = System.currentTimeMillis()): Boolean {
        val age = statusAgeMs(now) ?: return false
        return age <= CHILD_STATUS_FRESH_MS
    }

    private fun formatStatusAgeCompact(ageMs: Long): String {
        val seconds = (ageMs / 1000).coerceAtLeast(0L)
        return when {
            seconds < 5 -> getString(R.string.listen_status_just_now)
            seconds < 60 -> getString(R.string.listen_status_seconds, seconds.toInt())
            seconds < 3600 -> getString(R.string.listen_status_minutes, (seconds / 60).toInt())
            else -> getString(R.string.listen_status_hours, (seconds / 3600).toInt())
        }
    }

    private fun formatStatusAgeLong(ageMs: Long?): String {
        return ageMs?.let { formatStatusAgeCompact(it) } ?: getString(R.string.listen_diag_no_data)
    }

    private fun formatStreamAge(ageMs: Long): String {
        return when {
            ageMs < 1000L -> "${ageMs} мс"
            ageMs < 60_000L -> getString(R.string.listen_status_seconds, (ageMs / 1000L).toInt())
            else -> getString(R.string.listen_status_minutes, (ageMs / 60_000L).toInt())
        }
    }

    private fun resolveLatencyMs(metrics: AudioStreamMetrics): Long {
        if (metrics.pingMs > 0) {
            return metrics.pingMs
        }
        return if (AudioPlaybackService.lastChunkTimestamp > 0L) {
            (System.currentTimeMillis() - AudioPlaybackService.lastChunkTimestamp).coerceAtLeast(0L)
        } else {
            -1L
        }
    }

    private fun resolveLatencyStatus(metrics: AudioStreamMetrics, latencyMs: Long): PingStatus {
        if (metrics.pingMs > 0) {
            return metrics.pingStatus
        }
        return when {
            latencyMs < 0L -> PingStatus.UNKNOWN
            latencyMs < 250L -> PingStatus.EXCELLENT
            latencyMs < 1000L -> PingStatus.GOOD
            latencyMs < 3000L -> PingStatus.FAIR
            else -> PingStatus.POOR
        }
    }


    /**
     * Format duration in milliseconds to human-readable string
     */
    private fun formatDuration(ms: Long): String {
        if (ms == 0L) return getString(R.string.audio_monitor_placeholder_dash)

        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}h${minutes % 60}m"
            minutes > 0 -> "${minutes}m${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    /**
     * Get color for ping status
     */
    private fun getPingColor(status: PingStatus): Int {
        return when (status) {
            PingStatus.EXCELLENT -> Color.parseColor("#00FF00") // Green
            PingStatus.GOOD -> Color.parseColor("#90EE90") // Light green
            PingStatus.FAIR -> Color.parseColor("#FFFF00") // Yellow
            PingStatus.POOR -> Color.parseColor("#FF0000") // Red
            else -> Color.parseColor("#888888") // Gray
        }
    }

    /**
     * Get current battery level percentage
     */
    private fun getLocalBatteryLevel(): Int {
        return try {
            val batteryStatus: Intent? = registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

            if (level >= 0 && scale > 0) {
                (level * 100 / scale.toFloat()).toInt()
            } else {
                0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting battery level", e)
            0
        }
    }
}
