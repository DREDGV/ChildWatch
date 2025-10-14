package ru.example.parentwatch.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.os.Build
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
        private const val CHUNK_DURATION_MS = 500L
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
    private var recordingMode: Boolean = false // true if saving recording
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

        Log.d(TAG, "рџЋ™пёЏ Starting audio streaming via WebSocket - recording mode: $recordingMode")

        // Initialize WebSocket connection
        webSocketClient = WebSocketClient(serverUrl, deviceId)
        webSocketClient?.setCommandCallback { commandType, data ->
            Log.d(TAG, "рџ“Ґ Command callback invoked: $commandType")
            when (commandType) {
                "start_audio_stream" -> {
                    Log.d(TAG, "рџЋ™пёЏ Received START command - beginning audio recording!")
                    startActualRecording()
                }
                "stop_audio_stream" -> {
                    Log.d(TAG, "рџ›‘ Received STOP command - halting recording")
                    stopStreaming()
                }
            }
        }

        webSocketClient?.setParentConnectedCallback {
            Log.d(TAG, "👨‍👩‍👧 Parent connected notification received - starting audio")
            startActualRecording()
        }

        webSocketClient?.setParentDisconnectedCallback {
            Log.d(TAG, "🛑 Parent disconnected from stream - stopping audio")
            stopStreaming()
        }

        webSocketClient?.connect(
            onConnected = {
                Log.d(TAG, "вњ… WebSocket connected - waiting for start command from server...")
                webSocketClient?.startHeartbeat()
                webSocketConnected = true
            },
            onError = { error ->
                Log.e(TAG, "вќЊ WebSocket connection failed: $error")
                webSocketConnected = false
            }
        )

        Log.d(TAG, "вЏі WebSocket initialized - waiting for server command to start recording...")
    }

    /**
     * Actually start recording (called when command received from server)
     */
    private fun startActualRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording!")
            return
        }

        Log.d(TAG, "рџЋ¤ Starting actual audio recording...")

        // Initialize AudioRecord
        initializeAudioRecord()

        isRecording = true
        recordingJob = streamScope.launch {
            try {
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
        Log.d(TAG, "рџ›‘ Stopping audio streaming")

        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        // Cleanup WebSocket
        webSocketClient?.cleanup()
        webSocketClient = null
        webSocketConnected = false

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

    /**
     * Initialize AudioRecord
     */
    private fun initializeAudioRecord() {
        try {
            releaseRecorder()

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 4 // Increased buffer size
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                releaseRecorder()
                return
            }

            audioRecord?.startRecording()
            Log.d(TAG, "AudioRecord initialized and started")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AudioRecord", e)
            releaseRecorder()
        }
    }

    /**
     * Record a single chunk of audio
     */
    private fun recordChunk(): ByteArray? {
        val samplesToRecord = (SAMPLE_RATE * CHUNK_DURATION_MS / 1000).toInt()
        val bytesPerSample = 2 // 16-bit PCM
        val chunkSizeInBytes = samplesToRecord * bytesPerSample

        val buffer = ByteArray(chunkSizeInBytes)
        var totalRead = 0

        while (totalRead < chunkSizeInBytes && isRecording) {
            try {
                val toRead = minOf(1024, chunkSizeInBytes - totalRead)
                val read = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioRecord?.read(buffer, totalRead, toRead, AudioRecord.READ_BLOCKING) ?: -1
                } else {
                    audioRecord?.read(buffer, totalRead, toRead) ?: -1
                }

                if (read <= 0) {
                    Log.w(TAG, "AudioRecord.read returned: $read")
                    break
                }

                totalRead += read
            } catch (e: Exception) {
                Log.e(TAG, "Error reading from AudioRecord", e)
                break
            }
        }

        return if (totalRead > 0) buffer else null
    }

    /**
     * Release AudioRecord resources
     */
    private fun releaseRecorder() {
        try {
            audioRecord?.let { recorder ->
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
                recorder.release()
            }
            audioRecord = null
            Log.d(TAG, "AudioRecord released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
    }

    /**
     * Clean up temporary chunk files
     */
    private fun cleanupChunks() {
        try {
            if (cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("chunk_")) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up chunks", e)
        }
    }
}
