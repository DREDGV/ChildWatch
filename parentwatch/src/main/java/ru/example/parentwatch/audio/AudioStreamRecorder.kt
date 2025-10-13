package ru.example.parentwatch.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        private const val CHUNK_DURATION_MS = 2_000L
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val RECONNECTION_DELAY_MS = 3_000L
        private const val INITIAL_CONNECTION_TIMEOUT_MS = 7_000L
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var sequence = 0

    private var deviceId: String? = null
    private var serverUrl: String? = null
    private var recordingMode: Boolean = false
    private var webSocketClient: WebSocketClient? = null
    private var webSocketConnected = false
    private val streamScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionDeferred: CompletableDeferred<Boolean>? = null

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
        webSocketConnected = false

        Log.d(TAG, "Starting audio streaming via WebSocket (recording mode: $recordingMode)")

        initializeAudioRecord()
        connectWebSocket()

        isRecording = true
        recordingJob = streamScope.launch {
            try {
                val connected = waitForConnection()
                if (!connected) {
                    Log.w(TAG, "WebSocket not ready after initial wait; will retry while recording")
                }

                while (isRecording) {
                    if (!webSocketConnected) {
                        Log.w(TAG, "WebSocket disconnected, attempting reconnection before sending chunk")
                        ensureConnected()
                        delay(500)
                        continue
                    }

                    recordAndSendChunk()
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
        Log.d(TAG, "Stopping audio streaming")

        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        webSocketClient?.cleanup()
        webSocketClient = null
        webSocketConnected = false
        connectionDeferred?.cancel()
        connectionDeferred = null

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
            Log.d(TAG, "Recording chunk #$sequence ...")

            val audioData = recordChunk()
            if (audioData == null || audioData.isEmpty()) {
                Log.w(TAG, "No audio data recorded for chunk #$sequence")
                return
            }

            if (!webSocketConnected) {
                Log.w(TAG, "WebSocket unavailable, skipping chunk #$sequence")
                ensureConnected()
                return
            }

            webSocketClient?.sendAudioChunk(
                sequence = sequence,
                audioData = audioData,
                recording = recordingMode,
                onSuccess = {
                    Log.d(TAG, "Chunk #$sequence sent successfully (${audioData.size} bytes)")
                },
                onError = { error ->
                    Log.e(TAG, "Failed to send chunk #$sequence: $error")
                    webSocketConnected = false
                }
            )

            sequence++
        } catch (e: Exception) {
            Log.e(TAG, "Error recording/sending chunk #$sequence", e)
        }
    }

    private fun connectWebSocket() {
        val deviceId = this.deviceId ?: return
        val serverUrl = this.serverUrl ?: return

        if (connectionDeferred?.isActive == true) {
            return
        }

        val client = webSocketClient ?: WebSocketClient(serverUrl, deviceId).also { webSocketClient = it }
        connectionDeferred = CompletableDeferred()
        client.connect(
            onConnected = {
                Log.d(TAG, "WebSocket connected - ready to stream audio")
                webSocketConnected = true
                connectionDeferred?.takeIf { !it.isCompleted && !it.isCancelled }?.complete(true)
                client.startHeartbeat()
            },
            onError = { error ->
                Log.e(TAG, "WebSocket connection error: $error")
                webSocketConnected = false
                connectionDeferred?.takeIf { !it.isCompleted && !it.isCancelled }?.complete(false)
                if (isRecording) {
                    streamScope.launch {
                        delay(RECONNECTION_DELAY_MS)
                        connectWebSocket()
                    }
                }
            }
        )
    }

    private suspend fun waitForConnection(timeoutMs: Long = INITIAL_CONNECTION_TIMEOUT_MS): Boolean {
        if (webSocketConnected) return true
        val deferred = connectionDeferred ?: return false
        return withTimeoutOrNull(timeoutMs) { deferred.await() } == true
    }

    private fun ensureConnected() {
        if (!webSocketConnected) {
            connectWebSocket()
        }
    }

    /**
     * Initialize AudioRecord for continuous recording
     */
    private fun initializeAudioRecord() {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "RECORD_AUDIO permission not granted")
                return
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 4
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            Log.d(TAG, "AudioRecord initialized and started - continuous recording")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AudioRecord", e)
        }
    }

    /**
     * Record single audio chunk from continuous AudioRecord stream
     */
    private suspend fun recordChunk(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED ||
                audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord not recording - state: ${audioRecord?.state}, recording: ${audioRecord?.recordingState}")
                return@withContext null
            }

            val chunkSize = (SAMPLE_RATE * (CHUNK_DURATION_MS / 1000.0) * 2).toInt()
            val audioBuffer = ByteArray(chunkSize)
            var totalRead = 0

            while (totalRead < chunkSize && isRecording) {
                val bytesRead = audioRecord?.read(audioBuffer, totalRead, chunkSize - totalRead) ?: 0
                if (bytesRead > 0) {
                    totalRead += bytesRead
                } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION || bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "Error reading audio data: $bytesRead")
                    break
                } else {
                    Log.w(TAG, "No audio data read, bytesRead: $bytesRead")
                }
            }

            if (totalRead > 0) {
                audioBuffer.copyOf(totalRead)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recording chunk #$sequence", e)
            null
        }
    }

    /**
     * Release audio recorder
     */
    private fun releaseRecorder() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.d(TAG, "AudioRecord stopped and released")
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
