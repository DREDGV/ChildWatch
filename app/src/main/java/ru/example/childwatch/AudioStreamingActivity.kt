package ru.example.childwatch

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.example.childwatch.databinding.ActivityAudioStreamingBinding
import ru.example.childwatch.network.NetworkClient
import java.text.SimpleDateFormat
import java.util.*

/**
 * Audio Streaming Activity
 *
 * Features:
 * - Start/stop audio streaming from child device
 * - Real-time audio playback
 * - Optional recording mode
 * - Live status updates
 */
class AudioStreamingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AudioStreamingActivity"
        private const val UPDATE_INTERVAL_MS = 2000L // Poll for chunks every 2 seconds
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_SERVER_URL = "server_url"
    }

    private lateinit var binding: ActivityAudioStreamingBinding
    private lateinit var networkClient: NetworkClient
    private lateinit var deviceId: String
    private lateinit var serverUrl: String

    private var isStreaming = false
    private var isRecording = false
    private var audioTrack: AudioTrack? = null
    private var updateJob: Job? = null

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

        networkClient = NetworkClient(this)

        setupUI()
        updateUI()
    }

    private fun setupUI() {
        binding.deviceIdText.text = "Устройство: $deviceId"

        // Start/Stop streaming button
        binding.toggleStreamingBtn.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }

        // Toggle recording switch
        binding.recordingSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isStreaming) {
                toggleRecording(isChecked)
            }
        }
    }

    private fun startStreaming() {
        lifecycleScope.launch {
            try {
                binding.statusText.text = "Запуск прослушки..."
                binding.toggleStreamingBtn.isEnabled = false

                val success = networkClient.startAudioStreaming(serverUrl, deviceId, isRecording)

                if (success) {
                    isStreaming = true
                    binding.statusText.text = "Прослушка активна"
                    binding.startTimeText.text = "Начало: ${getCurrentTime()}"
                    Toast.makeText(this@AudioStreamingActivity, "Прослушка запущена", Toast.LENGTH_SHORT).show()

                    // Initialize audio playback
                    initializeAudioTrack()

                    // Start polling for audio chunks
                    startAudioUpdate()
                } else {
                    binding.statusText.text = "Ошибка запуска"
                    Toast.makeText(this@AudioStreamingActivity, "Не удалось запустить прослушку", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error starting streaming", e)
                binding.statusText.text = "Ошибка"
                Toast.makeText(this@AudioStreamingActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.toggleStreamingBtn.isEnabled = true
                updateUI()
            }
        }
    }

    private fun stopStreaming() {
        lifecycleScope.launch {
            try {
                binding.statusText.text = "Остановка..."
                binding.toggleStreamingBtn.isEnabled = false

                // Stop update job
                updateJob?.cancel()
                updateJob = null

                // Stop audio playback
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null

                val success = networkClient.stopAudioStreaming(serverUrl, deviceId)

                if (success) {
                    isStreaming = false
                    isRecording = false
                    binding.statusText.text = "Остановлено"
                    binding.recordingSwitch.isChecked = false
                    Toast.makeText(this@AudioStreamingActivity, "Прослушка остановлена", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@AudioStreamingActivity, "Ошибка остановки", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error stopping streaming", e)
                Toast.makeText(this@AudioStreamingActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.toggleStreamingBtn.isEnabled = true
                updateUI()
            }
        }
    }

    private fun toggleRecording(enabled: Boolean) {
        lifecycleScope.launch {
            try {
                val success = if (enabled) {
                    networkClient.startRecording(serverUrl, deviceId)
                } else {
                    networkClient.stopRecording(serverUrl, deviceId)
                }

                if (success) {
                    isRecording = enabled
                    val message = if (enabled) "Запись включена" else "Запись выключена"
                    Toast.makeText(this@AudioStreamingActivity, message, Toast.LENGTH_SHORT).show()
                } else {
                    binding.recordingSwitch.isChecked = !enabled
                    Toast.makeText(this@AudioStreamingActivity, "Ошибка переключения записи", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error toggling recording", e)
                binding.recordingSwitch.isChecked = !enabled
            }
        }
    }

    private fun initializeAudioTrack() {
        try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            Log.d(TAG, "AudioTrack initialized and playing")

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AudioTrack", e)
            Toast.makeText(this, "Ошибка инициализации аудио", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAudioUpdate() {
        updateJob = lifecycleScope.launch {
            var totalChunks = 0

            while (isActive && isStreaming) {
                try {
                    // Get audio chunks from server
                    val chunks = networkClient.getAudioChunks(serverUrl, deviceId)

                    if (chunks != null && chunks.isNotEmpty()) {
                        totalChunks += chunks.size
                        binding.chunksReceivedText.text = "Получено фрагментов: $totalChunks"

                        // Play audio chunks
                        chunks.forEach { chunk ->
                            audioTrack?.write(chunk.data, 0, chunk.data.size)
                        }

                        Log.d(TAG, "Played ${chunks.size} audio chunks (total: $totalChunks)")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error in audio update loop", e)
                }

                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun updateUI() {
        binding.toggleStreamingBtn.text = if (isStreaming) "Остановить прослушку" else "Начать прослушку"
        binding.recordingSwitch.isEnabled = isStreaming
    }

    private fun getCurrentTime(): String {
        val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return format.format(Date())
    }

    override fun onDestroy() {
        super.onDestroy()

        // Stop streaming if active
        if (isStreaming) {
            lifecycleScope.launch {
                networkClient.stopAudioStreaming(serverUrl, deviceId)
            }
        }

        // Clean up audio resources
        updateJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
    }
}
