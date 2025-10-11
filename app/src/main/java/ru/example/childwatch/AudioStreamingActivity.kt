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
import ru.example.childwatch.audio.AudioQualityManager
import ru.example.childwatch.audio.AudioQualityMode
import ru.example.childwatch.databinding.ActivityAudioStreamingBinding
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
    
    // Audio Quality Management
    private lateinit var audioQualityManager: AudioQualityManager
    private var currentQualityMode = AudioQualityMode.NOISE_REDUCTION
    
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
            syncAudioEnhancerWithService()
            serviceBound = true
            Log.d(TAG, "Service connected")

            // Set waveform callback for advanced visualizer
            audioService?.setWaveformCallback { audioData ->
                runOnUiThread {
                    binding.advancedAudioVisualizer.updateVisualization(audioData)
                    updateSignalLevel(audioData)
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

        // Initialize audio quality manager
        audioQualityManager = AudioQualityManager()
        
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
        val savedMode = audioPrefs.getString("quality_mode", "NOISE_REDUCTION")
        currentQualityMode = try {
            AudioQualityMode.valueOf(savedMode ?: "NOISE_REDUCTION")
        } catch (e: IllegalArgumentException) {
            AudioQualityMode.NOISE_REDUCTION
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
            .putString("quality_mode", currentQualityMode.name)
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

        // Advanced controls (for custom mode)
        binding.noiseSuppressionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (audioQualityManager.isCustomMode()) {
                val config = audioQualityManager.getCurrentConfig()
                audioQualityManager.setCustomConfig(config.copy(noiseSuppressionEnabled = isChecked))
                syncAudioEnhancerWithService()
            }
        }

        binding.gainBoostSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && audioQualityManager.isCustomMode()) {
                val config = audioQualityManager.getCurrentConfig()
                audioQualityManager.setCustomConfig(config.copy(gainBoostDb = value.toInt()))
                syncAudioEnhancerWithService()
                binding.gainBoostValueText.text = "${value.toInt()} дБ"
            }
        }
    }

    private fun setupQualityModeChips() {
        // Map chips to modes
        val chipModeMap = mapOf(
            binding.normalModeChip to AudioQualityMode.NORMAL,
            binding.noiseReductionChip to AudioQualityMode.NOISE_REDUCTION,
            binding.voiceEnhancedChip to AudioQualityMode.VOICE_ENHANCED,
            binding.loudEnvironmentChip to AudioQualityMode.LOUD_ENVIRONMENT,
            binding.crystalClearChip to AudioQualityMode.CRYSTAL_CLEAR,
            binding.sleepModeChip to AudioQualityMode.SLEEP_MODE
        )

        // Set up click listeners
        chipModeMap.forEach { (chip, mode) ->
            chip.setOnClickListener {
                setQualityMode(mode)
            }
        }

        // Set initial selection
        setQualityMode(currentQualityMode)
    }

    private fun setupVisualizationModeButton() {
        binding.visualizationModeBtn.setOnClickListener {
            cycleVisualizationMode()
        }
        updateVisualizationModeButton()
    }

    private fun setQualityMode(mode: AudioQualityMode) {
        currentQualityMode = mode
        audioQualityManager.setMode(mode)
        
        // Update UI
        updateModeDescription()
        updateAdvancedControlsVisibility()
        syncAudioEnhancerWithService()
        saveAudioSettings()
        
        Log.d(TAG, "Quality mode changed to: ${mode.displayName}")
    }

    private fun updateModeDescription() {
        binding.modeDescriptionText.text = currentQualityMode.description
    }

    private fun updateAdvancedControlsVisibility() {
        val isCustomMode = audioQualityManager.isCustomMode()
        binding.advancedControlsLayout.visibility = if (isCustomMode) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
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
        
        binding.signalLevelText.text = levelText
    }

    private fun syncAudioEnhancerWithService() {
        val config = audioQualityManager.getCurrentConfig()
        audioService?.updateAudioEnhancerConfig(config)
        Log.d(TAG, "Audio enhancer config synced: noiseSuppression=${config.noiseSuppressionEnabled}, gain=${config.gainBoostDb}dB")
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
        
        // Update time displays
        if (isPlaying && streamingStartTime > 0) {
            val currentTime = System.currentTimeMillis()
            val duration = currentTime - streamingStartTime
            
            val startTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            binding.startTimeText.text = startTimeFormat.format(Date(streamingStartTime))
            
            val durationFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            durationFormat.timeZone = TimeZone.getTimeZone("UTC")
            binding.durationText.text = durationFormat.format(Date(duration))
            
            // Update chunks count
            binding.chunksReceivedText.text = AudioPlaybackService.chunksReceived.toString()
        } else {
            binding.startTimeText.text = "--:--:--"
            binding.durationText.text = "00:00:00"
            binding.chunksReceivedText.text = "0"
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
}
