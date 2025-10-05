package ru.example.parentwatch.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import ru.example.parentwatch.network.NetworkHelper
import java.io.File

/**
 * Audio Stream Recorder
 * Records audio in chunks and uploads to server for real-time streaming
 * Uses PCM format for compatibility with AudioTrack playback
 */
class AudioStreamRecorder(
    private val context: Context,
    private val networkHelper: NetworkHelper
) {
    companion object {
        private const val TAG = "AudioStreamRecorder"
        private const val CHUNK_DURATION_MS = 2000L // 2 seconds per chunk
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

    private val bufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, "audio_chunks").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Start audio streaming
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

        Log.d(TAG, "Starting audio streaming - recording mode: $recordingMode")

        isRecording = true
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                while (isRecording) {
                    recordAndUploadChunk()
                    delay(CHUNK_DURATION_MS)
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
     * Record one chunk and upload to server
     */
    private suspend fun recordAndUploadChunk() {
        try {
            // Record chunk
            val audioData = recordChunk()

            if (audioData == null || audioData.isEmpty()) {
                Log.w(TAG, "No audio data recorded")
                return
            }

            // Upload to server
            val deviceId = this.deviceId ?: return
            val serverUrl = this.serverUrl ?: return

            val uploaded = networkHelper.uploadAudioChunk(
                serverUrl = serverUrl,
                deviceId = deviceId,
                audioData = audioData,
                sequence = sequence,
                recording = recordingMode
            )

            if (uploaded) {
                Log.d(TAG, "Chunk $sequence uploaded successfully (${audioData.size} bytes)")
            } else {
                Log.e(TAG, "Failed to upload chunk $sequence")
            }

            sequence++

        } catch (e: Exception) {
            Log.e(TAG, "Error recording/uploading chunk", e)
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
                Log.d(TAG, "Recorded $totalRead bytes of PCM audio")
                audioBuffer.copyOf(totalRead)
            } else {
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
