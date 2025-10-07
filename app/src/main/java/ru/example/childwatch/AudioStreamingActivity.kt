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
import ru.example.childwatch.network.WebSocketClient
import java.text.SimpleDateFormat
import java.util.*

/**
 * Audio Streaming Activity (WebSocket Version)
 *
 * Features:
 * - Real-time audio streaming via WebSocket
 * - Instant chunk delivery (no polling)
 * - Buffering queue for smooth playback
 * - Optional recording mode
 * - Live status updates
 */
class AudioStreamingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AudioStreamingActivity"
        private const val MIN_BUFFER_CHUNKS = 3 // Minimum chunks before starting playback (WebSocket is faster)
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
    private var playbackJob: Job? = null
    private var streamingStartTime: Long = 0L
    private var chunksReceived = 0

    // WebSocket client for real-time audio
    private var webSocketClient: WebSocketClient? = null

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
                binding.statusText.text = "Подключение к WebSocket..."
                binding.toggleStreamingBtn.isEnabled = false

                // HTTP request to start streaming session
                val success = networkClient.startAudioStreaming(serverUrl, deviceId, isRecording)

                if (success) {
                    isStreaming = true
                    streamingStartTime = System.currentTimeMillis()
                    chunksReceived = 0
                    binding.startTimeText.text = getCurrentTime()
                    binding.durationText.text = "00:00:00"
                    binding.chunksReceivedText.text = "0"

                    // Initialize audio playback
                    initializeAudioTrack()

                    // Connect to WebSocket for real-time audio
                    connectWebSocket()

                    binding.statusText.text = "Прослушка активна (WebSocket)"
                    Toast.makeText(this@AudioStreamingActivity, "🎙️ WebSocket подключен", Toast.LENGTH_SHORT).show()
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

    private fun connectWebSocket() {
        try {
            webSocketClient = WebSocketClient(serverUrl, deviceId)

            // Set callback for receiving audio chunks
            webSocketClient?.setAudioChunkCallback { audioData, sequence, timestamp ->
                // Add chunk to playback queue
                chunkQueue.offer(audioData)
                chunksReceived++

                // Update UI every 10 chunks
                if (chunksReceived % 10 == 0) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.chunksReceivedText.text = chunksReceived.toString()
                    }
                }

                // Start playback if buffered enough
                if (isBuffering && chunkQueue.size >= MIN_BUFFER_CHUNKS) {
                    isBuffering = false
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.statusText.text = "Воспроизведение..."
                    }
                }
            }

            // Set callback for child disconnect
            webSocketClient?.setChildDisconnectedCallback {
                lifecycleScope.launch(Dispatchers.Main) {
                    Toast.makeText(this@AudioStreamingActivity, "⚠️ Устройство отключилось", Toast.LENGTH_SHORT).show()
                }
            }

            // Connect
            webSocketClient?.connect(
                onConnected = {
                    Log.d(TAG, "✅ WebSocket connected")
                    webSocketClient?.startHeartbeat()

                    // Start playback job
                    startPlaybackJob()
                },
                onError = { error ->
                    Log.e(TAG, "❌ WebSocket error: $error")
                    lifecycleScope.launch(Dispatchers.Main) {
                        Toast.makeText(this@AudioStreamingActivity, "WebSocket ошибка: $error", Toast.LENGTH_LONG).show()
                    }
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting WebSocket", e)
        }
    }

    private fun stopStreaming() {
        lifecycleScope.launch {
            try {
                binding.statusText.text = "Остановка..."
                binding.toggleStreamingBtn.isEnabled = false

                // Stop playback job
                playbackJob?.cancel()
                playbackJob = null

                // Disconnect WebSocket
                webSocketClient?.cleanup()
                webSocketClient = null

                // Clear queue
                chunkQueue.clear()

                // Stop audio playback
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null

                // HTTP request to stop streaming
                val success = networkClient.stopAudioStreaming(serverUrl, deviceId)

                if (success) {
                    isStreaming = false
                    isRecording = false
                    binding.statusText.text = "Остановлено"
                    binding.recordingSwitch.isChecked = false
                    Toast.makeText(this@AudioStreamingActivity, "🛑 Прослушка остановлена", Toast.LENGTH_SHORT).show()
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

    private fun startPlaybackJob() {
        isBuffering = true
        chunkQueue.clear()

        // Continuous playback from WebSocket queue
        playbackJob = lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "🎧 Starting continuous playback from WebSocket")

            while (isActive && isStreaming) {
                try {
                    // Update duration timer
                    val duration = System.currentTimeMillis() - streamingStartTime
                    val seconds = (duration / 1000) % 60
                    val minutes = (duration / 60000) % 60
                    val hours = duration / 3600000
                    withContext(Dispatchers.Main) {
                        binding.durationText.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    }

                    // Play chunks from queue
                    if (!isBuffering && chunkQueue.isNotEmpty()) {
                        val chunk = chunkQueue.poll()
                        if (chunk != null && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            val bytesWritten = audioTrack?.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING) ?: 0

                            // Log every 10th chunk
                            if (chunksReceived % 10 == 0) {
                                Log.d(TAG, "▶️ Played chunk: $bytesWritten bytes (queue: ${chunkQueue.size})")
                            }
                        }
                    } else {
                        // Queue empty or still buffering - wait a bit
                        if (!isBuffering && chunkQueue.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                binding.statusText.text = "Буферизация..."
                            }
                            Log.w(TAG, "⏸️ Queue empty, waiting for chunks...")
                        }
                        delay(100)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in playback loop", e)
                }
            }

            Log.d(TAG, "🛑 Playback thread stopped")
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
        playbackJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
    }
}
