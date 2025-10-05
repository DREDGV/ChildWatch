package ru.example.parentwatch.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import ru.example.parentwatch.network.NetworkHelper
import java.io.File
import java.io.FileInputStream

/**
 * Audio Stream Recorder
 * Records audio in chunks and uploads to server for real-time streaming
 */
class AudioStreamRecorder(
    private val context: Context,
    private val networkHelper: NetworkHelper
) {
    companion object {
        private const val TAG = "AudioStreamRecorder"
        private const val CHUNK_DURATION_MS = 2000L // 2 seconds per chunk
        private const val SAMPLE_RATE = 44100
        private const val BIT_RATE = 64000 // 64 kbps
    }

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var sequence = 0

    private var deviceId: String? = null
    private var serverUrl: String? = null
    private var recordingMode: Boolean = false // true if saving recording

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
        val chunkFile = File(cacheDir, "chunk_${sequence}.webm")

        try {
            // Record chunk
            recordChunk(chunkFile)

            // Read chunk data
            val audioData = FileInputStream(chunkFile).use { it.readBytes() }

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
        } finally {
            // Clean up chunk file
            if (!recordingMode) {
                chunkFile.delete()
            }
        }
    }

    /**
     * Record single audio chunk
     */
    private suspend fun recordChunk(outputFile: File) = withContext(Dispatchers.IO) {
        try {
            releaseRecorder()

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.WEBM)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(outputFile.absolutePath)

                prepare()
                start()

                // Record for CHUNK_DURATION_MS
                delay(CHUNK_DURATION_MS)

                stop()
                reset()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error recording chunk", e)
            releaseRecorder()
        }
    }

    /**
     * Release media recorder
     */
    private fun releaseRecorder() {
        try {
            mediaRecorder?.release()
            mediaRecorder = null
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
