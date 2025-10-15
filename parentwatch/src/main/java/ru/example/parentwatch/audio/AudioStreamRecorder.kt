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
import ru.example.parentwatch.utils.RemoteLogger
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
    private var hasReportedMissingWebSocket = false

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
        hasReportedMissingWebSocket = false

        Log.d(TAG, "Starting audio streaming via WebSocket - recording mode: $recordingMode")
        RemoteLogger.info(
            serverUrl = this.serverUrl,
            deviceId = this.deviceId,
            source = TAG,
            message = "Starting audio streaming via WebSocket",
            meta = mapOf("recordingMode" to recordingMode)
        )

        // Initialize WebSocket connection
        webSocketClient = WebSocketClient(serverUrl, deviceId)
        webSocketClient?.setCommandCallback { commandType, data ->
            Log.d(TAG, "Command callback invoked: $commandType")
            when (commandType) {
                "start_audio_stream" -> {
                    Log.d(TAG, "START AUDIO STREAM command received - beginning audio recording!")
                    RemoteLogger.info(
                        serverUrl = this.serverUrl,
                        deviceId = this.deviceId,
                        source = TAG,
                        message = "START command received via WebSocket"
                    )
                    startActualRecording()
                }
                "stop_audio_stream" -> {
                    Log.d(TAG, "STOP AUDIO STREAM command received!")
                    RemoteLogger.info(
                        serverUrl = this.serverUrl,
                        deviceId = this.deviceId,
                        source = TAG,
                        message = "STOP command received via WebSocket"
                    )
                    stopStreaming()
                }
            }
        }

        webSocketClient?.setParentConnectedCallback {
            Log.d(TAG, "Parent connected notification received - starting audio")
            RemoteLogger.info(
                serverUrl = this.serverUrl,
                deviceId = this.deviceId,
                source = TAG,
                message = "Parent connected notification received"
            )
            startActualRecording()
        }

        webSocketClient?.setParentDisconnectedCallback {
            Log.d(TAG, "Parent disconnected from stream - stopping audio")
            RemoteLogger.warn(
                serverUrl = this.serverUrl,
                deviceId = this.deviceId,
                source = TAG,
                message = "Parent disconnected notification received"
            )
            stopStreaming()
        }

        webSocketClient?.connect(
            onConnected = {
                Log.d(TAG, "WebSocket connected - waiting for start command from server...")
                RemoteLogger.info(
                    serverUrl = this.serverUrl,
                    deviceId = this.deviceId,
                    source = TAG,
                    message = "WebSocket connected, awaiting START command"
                )
                webSocketClient?.startHeartbeat()
                webSocketConnected = true
            },
            onError = { error ->
                Log.e(TAG, "WebSocket connection failed: $error")
                RemoteLogger.error(
                    serverUrl = this.serverUrl,
                    deviceId = this.deviceId,
                    source = TAG,
                    message = "WebSocket connection failed: $error"
                )
                webSocketConnected = false
            }
        )

        Log.d(TAG, "✅ AudioStreamRecorder setup complete - waiting for server command or parent connection")
        RemoteLogger.info(
            serverUrl = this.serverUrl,
            deviceId = this.deviceId,
            source = TAG,
            message = "AudioStreamRecorder setup complete; waiting for trigger"
        )
    }

    /**
     * Actually start recording (called when command received from server)
     */
    private fun startActualRecording() {
        Log.d(TAG, "🎤 startActualRecording() called - checking conditions...")
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "startActualRecording() called",
            meta = mapOf(
                "isRecording" to isRecording,
                "webSocketConnected" to webSocketConnected
            )
        )

        if (isRecording) {
            Log.w(TAG, "⚠️ Already recording - skipping!")
            RemoteLogger.warn(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "startActualRecording called while already recording"
            )
            return
        }

        Log.d(TAG, "🎙️ Starting actual audio recording...")
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "STARTING ACTUAL AUDIO RECORDING NOW"
        )

        // Initialize AudioRecord
        initializeAudioRecord()

        if (audioRecord == null || audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "❌ AudioRecord not initialized - cannot start recording!")
            RemoteLogger.error(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "AudioRecord not initialized - aborting recording"
            )
            return
        }

        isRecording = true
        Log.d(TAG, "✅ isRecording set to TRUE - launching recording coroutine...")
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "Launching recording coroutine"
        )

        recordingJob = streamScope.launch {
            try {
                Log.d(TAG, "📡 Recording coroutine started - entering loop...")
                var chunkCount = 0
                while (isRecording) {
                    recordAndSendChunk()
                    chunkCount++
                    if (chunkCount == 1) {
                        Log.d(TAG, "✅ First chunk recorded and sent!")
                        RemoteLogger.info(
                            serverUrl = serverUrl,
                            deviceId = deviceId,
                            source = TAG,
                            message = "First audio chunk processed"
                        )
                    }
                }
                Log.d(TAG, "🛑 Recording loop exited - total chunks: $chunkCount")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Streaming error in coroutine", e)
                RemoteLogger.error(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    source = TAG,
                    message = "Streaming error in recording coroutine",
                    throwable = e
                )
                stopStreaming()
            }
        }

        Log.d(TAG, "✅ Recording job launched successfully")
    }

    /**
     * Stop audio streaming
     */
    fun stopStreaming() {
        Log.d(TAG, "Stopping audio streaming")
        RemoteLogger.info(
            serverUrl = serverUrl,
            deviceId = deviceId,
            source = TAG,
            message = "Stopping audio streaming"
        )

        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        // Cleanup WebSocket
        webSocketClient?.cleanup()
        webSocketClient = null
        webSocketConnected = false
        hasReportedMissingWebSocket = false

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
                RemoteLogger.warn(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    source = TAG,
                    message = "No audio data recorded for chunk",
                    meta = mapOf("sequence" to sequence)
                )
                return
            }

            if (!webSocketConnected) {
                Log.w(TAG, "WebSocket unavailable, skipping chunk #$sequence")
                if (!hasReportedMissingWebSocket) {
                    RemoteLogger.warn(
                        serverUrl = serverUrl,
                        deviceId = deviceId,
                        source = TAG,
                        message = "WebSocket unavailable when sending chunk",
                        meta = mapOf("sequence" to sequence)
                    )
                    hasReportedMissingWebSocket = true
                }
                return
            }

            webSocketClient?.sendAudioChunk(
                sequence = sequence,
                audioData = audioData,
                recording = recordingMode,
                onSuccess = {
                    Log.d(TAG, "Chunk #$sequence sent successfully (${audioData.size} bytes)")
                    hasReportedMissingWebSocket = false
                    if (sequence == 0) {
                        RemoteLogger.info(
                            serverUrl = serverUrl,
                            deviceId = deviceId,
                            source = TAG,
                            message = "First audio chunk sent successfully",
                            meta = mapOf("bytes" to audioData.size)
                        )
                    }
                },
                onError = { error ->
                    Log.e(TAG, "Failed to send chunk #$sequence: $error")
                    RemoteLogger.error(
                        serverUrl = serverUrl,
                        deviceId = deviceId,
                        source = TAG,
                        message = "Failed to send audio chunk: $error",
                        meta = mapOf("sequence" to sequence)
                    )
                    webSocketConnected = false
                }
            )

            sequence++
        } catch (e: Exception) {
            Log.e(TAG, "Error recording/sending chunk #$sequence", e)
            RemoteLogger.error(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "Error recording or sending chunk",
                throwable = e,
                meta = mapOf("sequence" to sequence)
            )
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
                RemoteLogger.error(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    source = TAG,
                    message = "AudioRecord failed to initialize"
                )
                releaseRecorder()
                return
            }

            audioRecord?.startRecording()
            Log.d(TAG, "AudioRecord initialized and started")
            RemoteLogger.info(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "AudioRecord initialized and started",
                meta = mapOf("bufferSize" to bufferSize)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AudioRecord", e)
            RemoteLogger.error(
                serverUrl = serverUrl,
                deviceId = deviceId,
                source = TAG,
                message = "Error initializing AudioRecord",
                throwable = e
            )
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
                RemoteLogger.error(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    source = TAG,
                    message = "Error reading from AudioRecord",
                    throwable = e
                )
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
