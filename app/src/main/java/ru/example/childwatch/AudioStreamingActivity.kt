package ru.example.childwatch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.example.childwatch.audio.AudioEnhancer
import ru.example.childwatch.audio.FilterMode
import ru.example.childwatch.databinding.ActivityAudioStreamingBinding
import ru.example.childwatch.diagnostics.AudioStreamMetrics
import ru.example.childwatch.diagnostics.WsStatus
import ru.example.childwatch.diagnostics.NetworkType
import ru.example.childwatch.diagnostics.PingStatus
import android.graphics.Color
import ru.example.childwatch.recordings.RecordingsLibraryActivity
import ru.example.childwatch.service.AudioPlaybackService
import ru.example.childwatch.ui.AdvancedAudioVisualizer
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

    // Audio Filter Management
    private var currentFilterMode = FilterMode.ORIGINAL
    
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

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioPlaybackService.LocalBinder
            audioService = binder.getService()
            syncFilterModeWithService()
            serviceBound = true
            Log.d(TAG, "Service connected")

            // Set waveform callback for advanced visualizer
            audioService?.setWaveformCallback { audioData ->
                runOnUiThread {
                    binding.advancedAudioVisualizer.updateVisualization(audioData)
                    updateSignalLevel(audioData)
                }
            }

            // Этап D: Subscribe to metrics updates
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
                    Log.d(TAG, "✅ Button enabled. isPlaying=${AudioPlaybackService.isPlaying}")
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

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: run {
            Toast.makeText(this, "Device ID не указан", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: run {
            Toast.makeText(this, "Server URL не указан", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Load preferences
        audioPrefs = getSharedPreferences("audio_streaming", MODE_PRIVATE)
        loadAudioSettings()

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

        val savedVisualization = audioPrefs.getString("visualization_mode", "FREQUENCY_BARS")
        currentVisualizationMode = try {
            AdvancedAudioVisualizer.VisualizationMode.valueOf(savedVisualization ?: "FREQUENCY_BARS")
        } catch (e: IllegalArgumentException) {
            AdvancedAudioVisualizer.VisualizationMode.FREQUENCY_BARS
        }

        visualizationModeIndex = visualizationModes.indexOf(currentVisualizationMode)
    }

    private fun saveAudioSettings() {
        audioPrefs.edit()
            .putString("filter_mode", currentFilterMode.name)
            .putString("visualization_mode", currentVisualizationMode.name)
            .apply()
    }

    private fun setupUI() {
        binding.deviceIdText.text = "Устройство: $deviceId"

        // Setup quality mode chips
        setupQualityModeChips()
        
        // Setup visualization mode button
        setupVisualizationModeButton()

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
    }

    private fun setupQualityModeChips() {
        // Setup filter mode RecyclerView with cards
        val filterItems = listOf(
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.ORIGINAL,
                "📡", "Оригинал", "Без обработки, чистый звук"
            ),
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.VOICE,
                "🎤", "Голос", "Усиление речи, шумоподавление"
            ),
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.QUIET_SOUNDS,
                "🔇", "Тихие звуки", "Максимальное усиление"
            ),
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.MUSIC,
                "🎵", "Музыка", "Естественное звучание"
            ),
            ru.example.childwatch.audio.AudioFilterItem(
                FilterMode.OUTDOOR,
                "🌳", "Улица", "Подавление ветра и шума"
            )
        )

        val filterAdapter = ru.example.childwatch.audio.AudioFilterAdapter(
            items = filterItems,
            selectedMode = currentFilterMode,
            onFilterSelected = { mode ->
                setFilterMode(mode)
                android.widget.Toast.makeText(this, "Фильтр: ${getFilterName(mode)}", android.widget.Toast.LENGTH_SHORT).show()
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
            FilterMode.ORIGINAL -> "Оригинал"
            FilterMode.VOICE -> "Голос"
            FilterMode.QUIET_SOUNDS -> "Тихие звуки"
            FilterMode.MUSIC -> "Музыка"
            FilterMode.OUTDOOR -> "Улица"
        }
    }

    private fun setupVisualizationModeButton() {
        binding.visualizationModeBtn.setOnClickListener {
            cycleVisualizationMode()
        }
        updateVisualizationModeButton()
    }

    private fun setFilterMode(mode: FilterMode) {
        currentFilterMode = mode

        // Update service with new filter mode
        if (AudioPlaybackService.isPlaying) {
            audioService?.setFilterMode(mode)
        }

        // Update UI
        updateModeDescription()
        saveAudioSettings()

        Log.d(TAG, "Filter mode changed to: $mode")
    }

    private fun updateModeDescription() {
        val description = when (currentFilterMode) {
            FilterMode.ORIGINAL -> "Оригинальный звук без обработки"
            FilterMode.VOICE -> "Усиление речи, подавление шума"
            FilterMode.QUIET_SOUNDS -> "Максимальное усиление тихих звуков"
            FilterMode.MUSIC -> "Естественное звучание музыки"
            FilterMode.OUTDOOR -> "Подавление ветра и уличного шума"
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
            AdvancedAudioVisualizer.VisualizationMode.FREQUENCY_BARS -> "Эквалайзер"
            AdvancedAudioVisualizer.VisualizationMode.WAVEFORM -> "Волна"
            AdvancedAudioVisualizer.VisualizationMode.VOLUME_METER -> "Громкость"
            AdvancedAudioVisualizer.VisualizationMode.CIRCULAR -> "Круг"
        }
        binding.visualizationModeBtn.text = modeText
    }

    private fun updateSignalLevel(audioData: ByteArray) {
        if (audioData.isEmpty()) {
            runOnUiThread {
                binding.signalLevelText.text = "Нет данных"
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
            percentage > 50 -> "Сильный"
            percentage > 20 -> "Средний"
            percentage > 5 -> "Слабый"
            else -> "Тишина"
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

    private fun startStreaming() {
        Log.d(TAG, "Starting audio streaming...")
        
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
        
        if (recording) {
            Toast.makeText(this, "🔴 Запись начата", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "⏹️ Запись остановлена", Toast.LENGTH_SHORT).show()
        }
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
        binding.toggleStreamingBtn.text = if (isPlaying) "Остановить прослушку" else "Начать прослушку"
        binding.toggleStreamingBtn.isEnabled = true

        // Update status
        binding.statusText.text = if (isPlaying) "Активна" else "Остановлена"
        binding.statusText.setTextColor(
            if (isPlaying) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.darker_gray)
        )

        // Update recording switch
        binding.recordingSwitch.isEnabled = isPlaying

        // Update connection quality from service
        if (isPlaying) {
            binding.connectionQualityText.text = AudioPlaybackService.connectionQuality
        } else {
            binding.connectionQualityText.text = "--"
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

                // Defensive check: duration should be positive
                if (duration >= 0) {
                    val startTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    binding.startTimeText.text = startTimeFormat.format(Date(streamingStartTime))

                    val durationFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    durationFormat.timeZone = TimeZone.getTimeZone("UTC")
                    binding.durationText.text = durationFormat.format(Date(duration))

                    // Update chunks count
                    binding.chunksReceivedText.text = AudioPlaybackService.chunksReceived.toString()
                } else {
                    // Reset if duration is negative (clock sync issue)
                    streamingStartTime = currentTime
                }
            } else {
                // No valid start time - show default
                binding.startTimeText.text = "--:--:--"
                binding.durationText.text = "00:00:00"
                binding.chunksReceivedText.text = AudioPlaybackService.chunksReceived.toString()
            }
        } else {
            // Not playing - reset displays
            binding.startTimeText.text = "--:--:--"
            binding.durationText.text = "00:00:00"
            binding.chunksReceivedText.text = "0"
            binding.connectionQualityText.text = "--"
            streamingStartTime = 0L // Reset local timer
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        binding.advancedAudioVisualizer.stop()
    }

    /**
     * Этап D: Update HUD with metrics
     */
    private fun updateHUD(metrics: AudioStreamMetrics) {
        // WS Status with icon
        val wsIcon = when (metrics.wsStatus) {
            WsStatus.CONNECTED -> "🟢"
            WsStatus.CONNECTING -> "🟡"
            WsStatus.RETRYING -> "🟠"
            else -> "🔴"
        }
        val duration = formatDuration(metrics.connectionDuration)
        binding.hudWsStatus.text = "$wsIcon $duration"

        // Network
        val netIcon = when (metrics.networkType) {
            NetworkType.WIFI -> "📡"
            NetworkType.MOBILE -> "📱"
            NetworkType.ETHERNET -> "🌐"
            else -> "❌"
        }
        val networkText = if (metrics.networkName.isNotEmpty() && metrics.networkName != "Wi-Fi") {
            metrics.networkName.take(5) // Truncate long names
        } else {
            when (metrics.networkType) {
                NetworkType.WIFI -> "WiFi"
                NetworkType.MOBILE -> "LTE"
                else -> "—"
            }
        }
        binding.hudNetwork.text = "$netIcon $networkText"

        // Data Rate
        val dataRateKB = metrics.bytesPerSecond / 1024
        val rateText = if (dataRateKB > 0) "${dataRateKB}KB/s" else "—"
        binding.hudDataRate.text = "▼ $rateText"

        // Queue
        val queueText = if (metrics.queueCapacity > 0) {
            "${metrics.queueDepth}/${metrics.queueCapacity}"
        } else {
            "—"
        }
        binding.hudQueue.text = "Q:$queueText"

        // Ping
        val pingText = if (metrics.pingMs > 0) "${metrics.pingMs}ms" else "—"
        binding.hudPing.text = pingText
        binding.hudPing.setTextColor(getPingColor(metrics.pingStatus))
    }

    /**
     * Format duration in milliseconds to human-readable string
     */
    private fun formatDuration(ms: Long): String {
        if (ms == 0L) return "—"

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
}
