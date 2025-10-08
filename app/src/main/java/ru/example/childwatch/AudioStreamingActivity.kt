package ru.example.childwatch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.example.childwatch.databinding.ActivityAudioStreamingBinding
import ru.example.childwatch.service.AudioPlaybackService
import java.text.SimpleDateFormat
import java.util.*

/**
 * Audio Streaming Activity (Service-based)
 *
 * Features:
 * - Works in background via Foreground Service
 * - Continues when screen is off or app is minimized
 * - Real-time audio streaming via WebSocket
 * - Live status updates from service
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

    private var audioService: AudioPlaybackService? = null
    private var serviceBound = false
    private var streamingStartTime: Long = 0L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioPlaybackService.LocalBinder
            audioService = binder.getService()
            serviceBound = true
            Log.d(TAG, "Service connected")

            // Set waveform callback
            audioService?.setWaveformCallback { audioData ->
                runOnUiThread {
                    binding.audioWaveform.updateWaveform(audioData)
                }
            }

            // Wait for service to actually start, then update UI
            runOnUiThread {
                // Give service 1 second to start streaming
                binding.toggleStreamingBtn.postDelayed({
                    binding.toggleStreamingBtn.isEnabled = true
                    updateUI()
                    Log.d(TAG, "✅ Button enabled. isPlaying=${AudioPlaybackService.isPlaying}, Text: ${binding.toggleStreamingBtn.text}")
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

        setupUI()

        // Check if service is already running
        if (AudioPlaybackService.isPlaying) {
            // Service is already running, bind to it
            Intent(this, AudioPlaybackService::class.java).also { intent ->
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
            streamingStartTime = System.currentTimeMillis() // Approximate
        }

        updateUI()
    }

    private fun setupUI() {
        binding.deviceIdText.text = "Устройство: $deviceId"

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

        // Volume slider
        binding.volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val volumePercent = value.toInt()
                binding.volumeText.text = "$volumePercent%"
                audioService?.setVolume(volumePercent)
            }
        }
    }

    private fun startStreaming() {
        Log.d(TAG, "▶️ startStreaming() called")
        binding.statusText.text = "Запуск..."
        binding.toggleStreamingBtn.isEnabled = false

        // Initial UI setup
        binding.startTimeText.text = getCurrentTime()
        binding.durationText.text = "00:00:00"
        binding.chunksReceivedText.text = "0"

        // Start service
        Log.d(TAG, "Starting AudioPlaybackService...")
        AudioPlaybackService.startPlayback(this, deviceId, serverUrl, binding.recordingSwitch.isChecked)

        // Bind to service for updates
        Intent(this, AudioPlaybackService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Start waveform animation
        binding.audioWaveform.start()

        Toast.makeText(this, "🎧 Прослушка запущена", Toast.LENGTH_SHORT).show()

        // Button will be enabled in serviceConnection.onServiceConnected
    }

    private fun stopStreaming() {
        Log.d(TAG, "⏹️ stopStreaming() called")
        binding.statusText.text = "Остановка..."
        binding.toggleStreamingBtn.isEnabled = false

        // Stop waveform animation
        binding.audioWaveform.stop()

        // Stop service
        Log.d(TAG, "Stopping AudioPlaybackService...")
        AudioPlaybackService.stopPlayback(this)

        // Unbind from service
        if (serviceBound) {
            audioService?.setWaveformCallback(null)
            unbindService(serviceConnection)
            serviceBound = false
        }

        binding.statusText.text = "Остановлено"
        binding.recordingSwitch.isChecked = false
        binding.toggleStreamingBtn.isEnabled = true
        updateUI()
        Log.d(TAG, "UI updated. Button text: ${binding.toggleStreamingBtn.text}")

        Toast.makeText(this, "🛑 Прослушка остановлена", Toast.LENGTH_SHORT).show()
    }

    private fun toggleRecording(enabled: Boolean) {
        AudioPlaybackService.toggleRecording(this, enabled)

        val message = if (enabled) "Запись включена" else "Запись выключена"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun startUIUpdateLoop() {
        lifecycleScope.launch {
            while (isActive && AudioPlaybackService.isPlaying) {
                try {
                    // Update duration from service
                    val startTime = AudioPlaybackService.streamingStartTime
                    if (startTime > 0) {
                        val duration = System.currentTimeMillis() - startTime
                        val seconds = (duration / 1000) % 60
                        val minutes = (duration / 60000) % 60
                        val hours = duration / 3600000
                        binding.durationText.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    }

                    // Update chunks received
                    binding.chunksReceivedText.text = AudioPlaybackService.chunksReceived.toString()

                    // Update status
                    binding.statusText.text = AudioPlaybackService.currentStatus

                    // Update connection quality
                    val quality = AudioPlaybackService.connectionQuality
                    binding.connectionQualityText.text = quality

                    // Update color based on quality
                    val colorRes = when (quality) {
                        "Отлично" -> android.R.color.holo_green_dark
                        "Хорошо" -> android.R.color.holo_green_light
                        "Удовл." -> android.R.color.holo_orange_light
                        "Плохо" -> android.R.color.holo_red_light
                        else -> android.R.color.darker_gray
                    }
                    binding.connectionQualityText.setTextColor(getColor(colorRes))
                    binding.signalIcon.setColorFilter(getColor(colorRes))

                    delay(1000) // Update every second
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating UI", e)
                }
            }
        }
    }

    private fun updateUI() {
        val isPlaying = AudioPlaybackService.isPlaying
        Log.d(TAG, "updateUI() called. isPlaying=$isPlaying")

        binding.toggleStreamingBtn.text = if (isPlaying) {
            "Остановить прослушку"
        } else {
            "Начать прослушку"
        }
        binding.recordingSwitch.isEnabled = isPlaying

        Log.d(TAG, "Button text set to: ${binding.toggleStreamingBtn.text}")
    }

    private fun getCurrentTime(): String {
        val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return format.format(Date())
    }

    override fun onDestroy() {
        super.onDestroy()

        // Unbind from service if still bound
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }

        // Note: We don't stop the service here - it continues in background!
        // User must explicitly stop it
    }
}
