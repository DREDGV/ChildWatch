package ru.example.childwatch

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        private const val MIN_BUFFER_CHUNKS = 7 // Minimum chunks before starting playback (increased for smoother playback)
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
    private var playbackJob: Job? = null
    private var streamingStartTime: Long = 0L

    // Buffering queue for smooth playback
    private val chunkQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private var isBuffering = true

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
                    streamingStartTime = System.currentTimeMillis()
                    binding.statusText.text = "Прослушка активна"
                    binding.startTimeText.text = getCurrentTime()
                    binding.durationText.text = "00:00:00"
                    binding.chunksReceivedText.text = "0"
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

                // Stop update and playback jobs
                updateJob?.cancel()
                updateJob = null
                playbackJob?.cancel()
                playbackJob = null

                // Clear queue
                chunkQueue.clear()

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
                .setBufferSizeInBytes(bufferSize * 8) // Increased buffer for smoother playback
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            Log.d(TAG, "AudioTrack initialized and playing - state: ${audioTrack?.playState}")

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AudioTrack", e)
            Toast.makeText(this, "Ошибка инициализации аудио", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAudioUpdate() {
        isBuffering = true
        chunkQueue.clear()

        // Job 1: Fetch chunks from server and add to queue
        updateJob = lifecycleScope.launch {
            var totalChunks = 0

            while (isActive && isStreaming) {
                try {
                    // Update duration timer
                    val duration = System.currentTimeMillis() - streamingStartTime
                    val seconds = (duration / 1000) % 60
                    val minutes = (duration / 60000) % 60
                    val hours = duration / 3600000
                    binding.durationText.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)

                    // Get audio chunks from server
                    val chunks = networkClient.getAudioChunks(serverUrl, deviceId)

                    if (chunks != null && chunks.isNotEmpty()) {
                        totalChunks += chunks.size
                        binding.chunksReceivedText.text = "$totalChunks"

                        // Add chunks to playback queue
                        chunks.forEach { chunk ->
                            chunkQueue.offer(chunk.data)
                        }

                        Log.d(TAG, "Added ${chunks.size} chunks to queue (total: $totalChunks, queue size: ${chunkQueue.size})")

                        // Start playback after buffering MIN_BUFFER_CHUNKS
                        if (isBuffering && chunkQueue.size >= MIN_BUFFER_CHUNKS) {
                            isBuffering = false
                            binding.statusText.text = "Воспроизведение..."
                            Log.d(TAG, "Buffering complete, starting playback")
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error in audio update loop", e)
                }

                delay(UPDATE_INTERVAL_MS)
            }
        }

        // Job 2: Continuous playback from queue
        startContinuousPlayback()
    }

    private fun startContinuousPlayback() {
        playbackJob = lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting continuous playback thread")

            while (isActive && isStreaming) {
                try {
                    if (!isBuffering && chunkQueue.isNotEmpty()) {
                        val chunk = chunkQueue.poll()
                        if (chunk != null && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            val bytesWritten = audioTrack?.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING) ?: 0
                            Log.d(TAG, "Played chunk: $bytesWritten bytes (queue size: ${chunkQueue.size})")
                        }
                    } else {
                        // Queue empty or still buffering - wait a bit
                        if (!isBuffering && chunkQueue.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                binding.statusText.text = "Буферизация..."
                            }
                            Log.w(TAG, "Queue empty, waiting for chunks...")
                        }
                        delay(100)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in playback loop", e)
                }
            }

            Log.d(TAG, "Playback thread stopped")
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
