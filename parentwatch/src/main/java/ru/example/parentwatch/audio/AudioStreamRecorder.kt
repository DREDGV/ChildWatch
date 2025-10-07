package ru.example.parentwatch.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import ru.example.parentwatch.network.NetworkHelper
import ru.example.parentwatch.network.WebSocketClient
import java.io.File

/**
 * Audio Stream Recorder
 * Records audio in chunks and transmits via WebSocket for real-time streaming
 * Uses PCM format for compatibility with AudioTrack playback
 */
class AudioStreamRecorder(
    private val context: Context,
    private val networkHelper: NetworkHelper
) {
    companion object {
        private const val TAG = "AudioStreamRecorder"
        private const val CHUNK_DURATION_MS = 2000L // 2 seconds per chunk (WebSocket is faster than HTTP)
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var sequence = 0

    private var deviceId: String? = null
    private var serverUrl: String? = null
    private var recordingMode: Boolean = false // true if saving recording
    private var webSocketClient: WebSocketClient? = null

    private val bufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, "audio_chunks").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Start audio streaming via WebSocket
     */
    fun startStreaming(
        deviceId: String,
        serverUrl: String,
        recordingMode: Boolean = false
    ) {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        this.deviceId = deviceId
        this.serverUrl = serverUrl
        this.recordingMode = recordingMode
        this.sequence = 0

        Log.d(TAG, "🎙️ Starting audio streaming via WebSocket - recording mode: $recordingMode")

        // Initialize WebSocket connection
        webSocketClient = WebSocketClient(serverUrl, deviceId)
        webSocketClient?.connect(
            onConnected = {
                Log.d(TAG, "✅ WebSocket connected - starting audio recording")
                webSocketClient?.startHeartbeat()
            },
            onError = { error ->
                Log.e(TAG, "❌ WebSocket connection failed: $error")
            }
        )

        isRecording = true
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Wait a bit for WebSocket to connect
                delay(1000)

                while (isRecording) {
                    recordAndSendChunk()
                    // No delay needed - send immediately after recording
                }
            } catch (e: Exception) {
                Log.e(TAG, "Streaming error", e)
                stopStreaming()
            }
        }
    }

    /**
     * Stop audio streaming
     */
    fun stopStreaming() {
        Log.d(TAG, "🛑 Stopping audio streaming")

        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        // Cleanup WebSocket
        webSocketClient?.cleanup()
        webSocketClient = null

        releaseRecorder()
        cleanupChunks()
    }

    /**
     * Set recording mode (save to server or just stream)
     */
    fun setRecordingMode(enabled: Boolean) {
        Log.d(TAG, "Recording mode: $enabled")
        this.recordingMode = enabled
    }

    /**
     * Record one chunk and send via WebSocket
     */
    private suspend fun recordAndSendChunk() {
        try {
            // Record chunk
            val audioData = recordChunk()

            if (audioData == null || audioData.isEmpty()) {
                Log.w(TAG, "No audio data recorded")
                return
            }

            // Send via WebSocket (instant transmission)
            webSocketClient?.sendAudioChunk(
                sequence = sequence,
                audioData = audioData,
                recording = recordingMode,
                onSuccess = {
                    // Log every 10th chunk to reduce spam
                    if (sequence % 10 == 0) {
                        Log.d(TAG, "✅ Chunk $sequence sent via WebSocket (${audioData.size} bytes)")
                    }
                },
                onError = { error ->
                    Log.e(TAG, "❌ Failed to send chunk $sequence: $error")
                }
            )

            sequence++

        } catch (e: Exception) {
            Log.e(TAG, "Error recording/sending chunk", e)
        }
    }

    /**
     * Record single audio chunk using AudioRecord (PCM format)
     */
    private suspend fun recordChunk(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // Initialize AudioRecord
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 4
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                releaseRecorder()
                return@withContext null
            }

            audioRecord?.startRecording()

            // Calculate buffer size for CHUNK_DURATION_MS
            val chunkSize = (SAMPLE_RATE * (CHUNK_DURATION_MS / 1000.0) * 2).toInt() // 2 bytes per sample (16-bit)
            val audioBuffer = ByteArray(chunkSize)
            var totalRead = 0

            // Read audio data for CHUNK_DURATION_MS
            val startTime = System.currentTimeMillis()
            while (totalRead < chunkSize && System.currentTimeMillis() - startTime < CHUNK_DURATION_MS) {
                val bytesRead = audioRecord?.read(audioBuffer, totalRead, chunkSize - totalRead) ?: 0
                if (bytesRead > 0) {
                    totalRead += bytesRead
                } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION || bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "Error reading audio data: $bytesRead")
                    break
                }
            }

            audioRecord?.stop()
            releaseRecorder()

            if (totalRead > 0) {
                Log.d(TAG, "Recorded $totalRead bytes of PCM audio (${totalRead/2} samples)")
                audioBuffer.copyOf(totalRead)
            } else {
                Log.w(TAG, "No audio data recorded")
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error recording chunk", e)
            releaseRecorder()
            null
        }
    }

    /**
     * Release audio recorder
     */
    private fun releaseRecorder() {
        try {
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing recorder", e)
        }
    }

    /**
     * Clean up old chunks
     */
    private fun cleanupChunks() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up chunks", e)
        }
    }

    /**
     * Check if currently streaming
     */
    fun isStreaming(): Boolean = isRecording
}
