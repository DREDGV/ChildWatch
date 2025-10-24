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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
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
    private var currentVolumeMode = VolumeMode.NORMAL

    // Child device status cache
    private var childBatteryLevel: Int? = null
    private var childCharging: Boolean? = null
    private var statusPollingJob: Job? = null

    // Visualization
    private var currentVisualizationMode = AdvancedAudioVisualizer.VisualizationMode.FREQUENCY_BARS
    private val visualizationModes = listOf(
        AdvancedAudioVisualizer.VisualizationMode.FREQUENCY_BARS,
        AdvancedAudioVisualizer.VisualizationMode.WAVEFORM,
        AdvancedAudioVisualizer.VisualizationMode.VOLUME_METER,
        AdvancedAudioVisualizer.VisualizationMode.CIRCULAR
    )
    private var visualizationModeIndex = 0

    private var audioService: AudioPlaybackService? = null
    private var serviceBound = false
    private var streamingStartTime: Long = 0L
    private lateinit var hudModeButton: MaterialButton

    private enum class HudMode { COMPACT, EXPANDED }
    private var hudMode = HudMode.EXPANDED

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioPlaybackService.LocalBinder
            audioService = binder.getService()
            syncFilterModeWithService()
            syncVolumeModeWithService()
            serviceBound = true
            Log.d(TAG, "Service connected")

            // Set waveform callback for advanced visualizer
            audioService?.setWaveformCallback { audioData ->
                runOnUiThread {
                    binding.advancedAudioVisualizer.updateVisualization(audioData)
                    updateSignalLevel(audioData)
                }
            }

            // ╨н╤В╨░╨┐ D: Subscribe to metrics updates
            lifecycleScope.launch {
                audioService?.metricsManager?.metrics?.collect { metrics ->
                    runOnUiThread {
                        updateHUD(metrics)
                    }
                }
            }

            // Wait for service to actually start, then update UI
            runOnUiThread {
                binding.toggleStreamingBtn.postDelayed({
                    binding.toggleStreamingBtn.isEnabled = true
                    updateUI()
                    Log.d(TAG, "тЬЕ Button enabled. isPlaying=${AudioPlaybackService.isPlaying}")
                }, 1000)
            }

            // Start UI update loop
            startUIUpdateLoop()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService?.setWaveformCallback(null)
            audioService = null
            serviceBound = false
            binding.toggleStreamingBtn.isEnabled = true
            Log.d(TAG, "Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioStreamingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        secureSettings = SecureSettingsManager(this)
        audioPrefs = getSharedPreferences("audio_streaming", MODE_PRIVATE)

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
            ?: secureSettings.getChildDeviceId()
            ?: run {
                Toast.makeText(
                    this,
                    getString(R.string.audio_monitor_error_missing_device),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return
            }

        serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
            ?: secureSettings.getServerUrl().also {
                Toast.makeText(
                    this,
                    getString(R.string.audio_monitor_error_missing_server),
                    Toast.LENGTH_SHORT
                ).show()
            }

        loadAudioSettings()
        loadCachedStatus()
        setupUI()

        // Check if service is already running
        if (AudioPlaybackService.isPlaying) {
            Intent(this, AudioPlaybackService::class.java).also { intent ->
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
            streamingStartTime = System.currentTimeMillis()
        }

        updateUI()
    }

    private fun loadAudioSettings() {
        val savedMode = audioPrefs.getString("filter_mode", FilterMode.ORIGINAL.name)
        currentFilterMode = try {
            FilterMode.valueOf(savedMode ?: FilterMode.ORIGINAL.name)
        } catch (e: IllegalArgumentException) {
            FilterMode.ORIGINAL
        }

        val savedVolumeMode = audioPrefs.getString("volume_mode", VolumeMode.NORMAL.name)
        currentVolumeMode = try {
            VolumeMode.valueOf(savedVolumeMode ?: VolumeMode.NORMAL.name)
        } catch (e: IllegalArgumentException) {
            VolumeMode.NORMAL
        }

        val savedVisualization = audioPrefs.getString("visualization_mode", "FREQUENCY_BARS")
        currentVisualizationMode = try {
            AdvancedAudioVisualizer.VisualizationMode.valueOf(savedVisualization ?: "FREQUENCY_BARS")
        } catch (e: IllegalArgumentException) {
            AdvancedAudioVisualizer.VisualizationMode.FREQUENCY_BARS
        }

        visualizationModeIndex = visualizationModes.indexOf(currentVisualizationMode)

        val savedHudMode = audioPrefs.getString("hud_mode", HudMode.EXPANDED.name)
        hudMode = try {
            HudMode.valueOf(savedHudMode ?: HudMode.EXPANDED.name)
        } catch (e: IllegalArgumentException) {
            HudMode.EXPANDED
        }
    }

    private fun loadCachedStatus() {
        try {
            val cachedStatus = secureSettings.getLastDeviceStatus()
            if (!cachedStatus.isNullOrEmpty()) {
                val status = gson.fromJson(cachedStatus, DeviceStatus::class.java)
                childBatteryLevel = status?.batteryLevel
                childCharging = status?.isCharging
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached device status", e)
        }
    }

    private fun saveAudioSettings() {
        audioPrefs.edit()
            .putString("filter_mode", currentFilterMode.name)
            .putString("volume_mode", currentVolumeMode.name)
            .putString("visualization_mode", currentVisualizationMode.name)
            .putString("hud_mode", hudMode.name)
            .apply()
    }

    private fun setupUI() {
        binding.deviceIdText.text = getString(R.string.audio_monitor_device_id, deviceId)

        hudModeButton = binding.hudModeButton
        hudModeButton.setOnClickListener { toggleHudMode() }
        applyHudMode()

        // Setup quality mode chips
        setupQualityModeChips()
        
        // Setup visualization mode button
        setupVisualizationModeButton()

        // Setup volume mode button
        setupVolumeModeButton()

        // Start/Stop streaming button
        binding.toggleStreamingBtn.setOnClickListener {
            Log.d(TAG, "Button clicked. isPlaying=${AudioPlaybackService.isPlaying}")
            if (AudioPlaybackService.isPlaying) {
                Log.d(TAG, "Calling stopStreaming()")
                stopStreaming()
            } else {
                Log.d(TAG, "Calling startStreaming()")
                startStreaming()
            }
        }

        // Toggle recording switch
        binding.recordingSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (AudioPlaybackService.isPlaying) {
                toggleRecording(isChecked)
            }
        }

        // Volume control
        binding.volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val volume = (value / 100f).coerceIn(0f, 1f)
                audioService?.setVolume(volume)
                binding.volumeText.text = "${value.toInt()}%"
            }
        }

        // Open recordings library
        binding.openRecordingsButton.setOnClickListener {
            startActivity(Intent(this, RecordingsLibraryActivity::class.java))
        }

        // Advanced controls removed - no custom mode in new filter system

        updateBatteryHud()
    }

    private fun setupQualityModeChips() {
        // Setup filter mode RecyclerView with cards
        val filterItems = listOf(
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.ORIGINAL,
                "🎧",
                getString(R.string.audio_monitor_filter_original),
                getString(R.string.audio_monitor_filter_original_desc)
            ),
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.VOICE,
                "🗣️",
                getString(R.string.audio_monitor_filter_voice),
                getString(R.string.audio_monitor_filter_voice_desc)
            ),
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.QUIET_SOUNDS,
                "🔈",
                getString(R.string.audio_monitor_filter_quiet),
                getString(R.string.audio_monitor_filter_quiet_desc)
            ),
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.MUSIC,
                "🎵",
                getString(R.string.audio_monitor_filter_music),
                getString(R.string.audio_monitor_filter_music_desc)
            ),
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.OUTDOOR,
                "🌬️",
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

    private fun setupVisualizationModeButton() {
        binding.visualizationModeBtn.setOnClickListener {
            cycleVisualizationMode()
        }
        updateVisualizationModeButton()
    }

    private fun setupVolumeModeButton() {
        binding.volumeModeBtn.setOnClickListener {
            toggleVolumeMode()
        }
        updateVolumeModeButton()
    }

    private fun toggleHudMode() {
        hudMode = if (hudMode == HudMode.EXPANDED) HudMode.COMPACT else HudMode.EXPANDED
        applyHudMode()
        saveAudioSettings()
    }

    private fun applyHudMode() {
        binding.hudRow2.isVisible = hudMode == HudMode.EXPANDED
        hudModeButton.text = getString(
            if (hudMode == HudMode.EXPANDED) {
                R.string.audio_monitor_hud_toggle_compact
            } else {
                R.string.audio_monitor_hud_toggle_expanded
            }
        )
    }

    private fun startChildStatusPolling() {
        if (statusPollingJob?.isActive == true) return
        statusPollingJob = lifecycleScope.launch {
            fetchChildStatus()
            while (isActive) {
                delay(30_000)
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
            val response = networkClient.getChildDeviceStatus(deviceId)
            if (response.isSuccessful) {
                val status = response.body()?.status
                if (status != null) {
                    childBatteryLevel = status.batteryLevel
                    childCharging = status.isCharging
                    secureSettings.setLastDeviceStatus(gson.toJson(status))
                    secureSettings.setLastDeviceStatusTimestamp(System.currentTimeMillis())
                    updateBatteryHud()
                }
            } else {
                Log.w(TAG, "Device status request failed: ${'$'}{response.code()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching device status", e)
        }
    }

    private fun toggleVolumeMode() {
        currentVolumeMode = when (currentVolumeMode) {
            VolumeMode.QUIET -> VolumeMode.NORMAL
            VolumeMode.NORMAL -> VolumeMode.LOUD
            VolumeMode.LOUD -> VolumeMode.BOOST
            VolumeMode.BOOST -> VolumeMode.QUIET
        }

        audioService?.let { service ->
            val currentConfig = service.getAudioEnhancerConfig()
            val newConfig = currentConfig.copy(volumeMode = currentVolumeMode)
            service.setAudioEnhancerConfig(newConfig)
        }

        updateVolumeModeButton()
        saveAudioSettings()

        Log.d(TAG, "Volume mode changed to: $currentVolumeMode")
    }

    private fun updateVolumeModeButton() {
        val (icon, labelRes) = when (currentVolumeMode) {
            VolumeMode.QUIET -> "🎧" to R.string.audio_monitor_volume_mode_quiet
            VolumeMode.NORMAL -> "🔊" to R.string.audio_monitor_volume_mode_normal
            VolumeMode.LOUD -> "📣" to R.string.audio_monitor_volume_mode_loud
            VolumeMode.BOOST -> "🚀" to R.string.audio_monitor_volume_mode_boost
        }
        binding.volumeModeBtn.text = getString(labelRes).let { "$icon $it" }
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

    private fun cycleVisualizationMode() {
        visualizationModeIndex = (visualizationModeIndex + 1) % visualizationModes.size
        currentVisualizationMode = visualizationModes[visualizationModeIndex]
        
        binding.advancedAudioVisualizer.setVisualizationMode(currentVisualizationMode)
        updateVisualizationModeButton()
        saveAudioSettings()
        
        Log.d(TAG, "Visualization mode changed to: $currentVisualizationMode")
    }

    private fun updateVisualizationModeButton() {
        val modeText = when (currentVisualizationMode) {
            AdvancedAudioVisualizer.VisualizationMode.FREQUENCY_BARS -> getString(R.string.audio_monitor_visualization_equalizer)
            AdvancedAudioVisualizer.VisualizationMode.WAVEFORM -> getString(R.string.audio_monitor_visualization_waveform)
            AdvancedAudioVisualizer.VisualizationMode.VOLUME_METER -> getString(R.string.audio_monitor_visualization_volume)
            AdvancedAudioVisualizer.VisualizationMode.CIRCULAR -> getString(R.string.audio_monitor_visualization_circular)
        }
        binding.visualizationModeBtn.text = modeText
    }

    private fun updateSignalLevel(audioData: ByteArray) {
        if (audioData.isEmpty()) {
            runOnUiThread {
                binding.signalLevelText.text = getString(R.string.audio_monitor_signal_no_data)
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
            percentage > 50 -> getString(R.string.audio_monitor_signal_strong)
            percentage > 20 -> getString(R.string.audio_monitor_signal_medium)
            percentage > 5 -> getString(R.string.audio_monitor_signal_weak)
            else -> getString(R.string.audio_monitor_signal_silence)
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

    private fun startStreaming() {
        Log.d(TAG, "Starting audio streaming...")

        // Improvement: Keep screen on during streaming to prevent system sleep
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "ЁЯТб Screen lock enabled - preventing sleep during streaming")

        // Start visualizer
        binding.advancedAudioVisualizer.start()

        val intent = Intent(this, AudioPlaybackService::class.java).apply {
            action = AudioPlaybackService.ACTION_START_PLAYBACK
            putExtra(AudioPlaybackService.EXTRA_DEVICE_ID, deviceId)
            putExtra(AudioPlaybackService.EXTRA_SERVER_URL, serverUrl)
            putExtra(AudioPlaybackService.EXTRA_RECORDING, binding.recordingSwitch.isChecked)
        }

        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        streamingStartTime = System.currentTimeMillis()
        updateUI()
    }

    private fun stopStreaming() {
        Log.d(TAG, "Stopping audio streaming...")

        // Improvement: Allow screen to sleep when streaming stops
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "ЁЯТб Screen lock disabled - allowing normal sleep")

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

        updateUI()
    }

    private fun toggleRecording(recording: Boolean) {
        val intent = Intent(this, AudioPlaybackService::class.java).apply {
            action = AudioPlaybackService.ACTION_TOGGLE_RECORDING
            putExtra(AudioPlaybackService.EXTRA_RECORDING, recording)
        }
        startForegroundService(intent)

        val toastRes = if (recording) {
            R.string.audio_monitor_toast_recording_on
        } else {
            R.string.audio_monitor_toast_recording_off
        }
        Toast.makeText(this, getString(toastRes), Toast.LENGTH_SHORT).show()
    }

    private fun startUIUpdateLoop() {
        lifecycleScope.launch {
            while (isActive) {
                updateUI()
                delay(1000) // Update every second
            }
        }
    }

    private fun updateUI() {
        val isPlaying = AudioPlaybackService.isPlaying

        // Update button text and state
        binding.toggleStreamingBtn.text = getString(
            if (isPlaying) R.string.audio_monitor_toggle_stop else R.string.audio_monitor_toggle_start
        )
        binding.toggleStreamingBtn.isEnabled = true

        // Update status
        binding.statusText.text = getString(
            if (isPlaying) R.string.audio_monitor_state_running else R.string.audio_monitor_state_stopped
        )
        binding.statusText.setTextColor(
            if (isPlaying) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.darker_gray)
        )

        // Update recording switch
        binding.recordingSwitch.isEnabled = isPlaying

        // Update connection quality from service
        binding.connectionQualityText.text = if (isPlaying) {
            AudioPlaybackService.connectionQuality
        } else {
            getString(R.string.audio_monitor_placeholder_dash)
        }

        // Update time displays with defensive checks
        if (isPlaying) {
            // Sync streamingStartTime with service if not set locally
            if (streamingStartTime == 0L && AudioPlaybackService.streamingStartTime > 0) {
                streamingStartTime = AudioPlaybackService.streamingStartTime
            }

            if (streamingStartTime > 0) {
                val currentTime = System.currentTimeMillis()
                val duration = currentTime - streamingStartTime

                if (duration >= 0) {
                    val startTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    binding.startTimeText.text = startTimeFormat.format(Date(streamingStartTime))

                    val durationFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    durationFormat.timeZone = TimeZone.getTimeZone("UTC")
                    binding.durationText.text = durationFormat.format(Date(duration))

                    binding.chunksReceivedText.text = AudioPlaybackService.chunksReceived.toString()
                } else {
                    streamingStartTime = currentTime
                }
            } else {
                binding.startTimeText.text = getString(R.string.audio_monitor_time_placeholder)
                binding.durationText.text = getString(R.string.audio_monitor_duration_placeholder)
                binding.chunksReceivedText.text = AudioPlaybackService.chunksReceived.toString()
            }
        } else {
            binding.startTimeText.text = getString(R.string.audio_monitor_time_placeholder)
            binding.durationText.text = getString(R.string.audio_monitor_duration_placeholder)
            binding.chunksReceivedText.text = getString(R.string.audio_monitor_placeholder_zero)
            streamingStartTime = 0L // Reset local timer
        }
    }

    override fun onResume() {
        super.onResume()
        startChildStatusPolling()
    }

    override fun onPause() {
        super.onPause()
        stopChildStatusPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopChildStatusPolling()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        binding.advancedAudioVisualizer.stop()
    }

    /**
     * Enhanced HUD with 2 rows of detailed metrics
     */
    private fun updateHUD(metrics: AudioStreamMetrics) {
        // === ROW 1: Connection & Network ===

        // WS Status with icon and session time
        val wsIcon = when (metrics.wsStatus) {
            WsStatus.CONNECTED -> "🟢"
            WsStatus.CONNECTING -> "🟡"
            WsStatus.RETRYING -> "🟠"
            WsStatus.ERROR -> "🔴"
            else -> "⚫"
        }
        val duration = formatDuration(metrics.connectionDuration)
        binding.hudWsStatus.text = "$wsIcon $duration"

        // Network Type with icon
        val netIcon = when (metrics.networkType) {
            NetworkType.WIFI -> "📡"
            NetworkType.MOBILE -> "📱"
            NetworkType.ETHERNET -> "🌐"
            else -> "❌"
        }
        val networkLabel = if (metrics.networkName.isNotEmpty() && metrics.networkName != "Wi-Fi") {
            metrics.networkName.take(5)
        } else {
            when (metrics.networkType) {
                NetworkType.WIFI -> "WiFi"
                NetworkType.MOBILE -> "LTE"
                NetworkType.ETHERNET -> "LAN"
                else -> "—"
            }
        }
        binding.hudNetwork.text = "$netIcon $networkLabel"

        // Data Rate with icon
        val dataRateKB = metrics.bytesPerSecond / 1024
        val rateText = if (dataRateKB > 0) "${dataRateKB}KB/s" else "—"
        binding.hudDataRate.text = "▼ $rateText"

        // Ping with color
        val pingText = if (metrics.pingMs > 0) "${metrics.pingMs}ms" else "—"
        binding.hudPing.text = pingText
        binding.hudPing.setTextColor(getPingColor(metrics.pingStatus))

        // Battery with icon from metrics
        val batteryDisplay = if (metrics.batteryLevel > 0) metrics.batteryLevel else getLocalBatteryLevel()
        val batteryIcon = when {
            metrics.batteryCharging -> "⚡"
            batteryDisplay >= 80 -> "🔋"
            batteryDisplay >= 50 -> "🔋"
            batteryDisplay >= 20 -> "🪫"
            else -> "🪫"
        }
        binding.hudBattery.text = "$batteryIcon $batteryDisplay%"

        // === ROW 2: Audio & Quality ===

        // Audio Status with icon
        val audioIcon = when (metrics.audioStatus) {
            AudioStatus.PLAYING -> "▶️"
            AudioStatus.BUFFERING -> "⏳"
            AudioStatus.RECORDING -> "🔴"
            AudioStatus.ERROR -> "⚠️"
            else -> "⏸️"
        }
        val audioLabel = when (metrics.audioStatus) {
            AudioStatus.PLAYING -> "Play"
            AudioStatus.BUFFERING -> "Buf"
            AudioStatus.RECORDING -> "Rec"
            AudioStatus.ERROR -> "Err"
            else -> "Stop"
        }
        binding.hudAudioStatus.text = "$audioIcon $audioLabel"

        // Queue with underrun indicator
        val queueText = if (metrics.queueCapacity > 0) {
            val underrunIndicator = if (metrics.underrunCount > 0) " ⚠️${metrics.underrunCount}" else ""
            "Q:${metrics.queueDepth}/${metrics.queueCapacity}$underrunIndicator"
        } else {
            "Q:—"
        }
        binding.hudQueue.text = queueText

        // Total Data with icon
        val totalBytes = metrics.framesTotal * metrics.frameSize
        val totalMB = totalBytes / (1024.0 * 1024.0)
        val totalText = when {
            totalMB >= 1.0 -> "%.1fM".format(totalMB)
            totalBytes > 0 -> "%.0fK".format(totalBytes / 1024.0)
            else -> "—"
        }
        binding.hudTotalData.text = "Σ $totalText"

        // Sample Rate with icon
        val sampleRateKHz = metrics.sampleRate / 1000.0
        binding.hudSampleRate.text = "♫ %.1fk".format(sampleRateKHz)

        // Toggle row 2 visibility based on HUD mode
        binding.hudRow2.isVisible = hudMode == HudMode.EXPANDED
    }

    private fun updateBatteryHud() {
        val batteryDisplay = childBatteryLevel?.coerceIn(0, 100) ?: getLocalBatteryLevel()
        val charging = childCharging == true
        val batteryIcon = when {
            charging -> "⚡"
            batteryDisplay >= 80 -> "🔋"
            batteryDisplay >= 50 -> "🔋"
            batteryDisplay >= 20 -> "🪫"
            else -> "🪫"
        }
        binding.hudBattery.text = "$batteryIcon $batteryDisplay%"
    }


    /**
     * Format duration in milliseconds to human-readable string
     */
    private fun formatDuration(ms: Long): String {
        if (ms == 0L) return "тАФ"

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
