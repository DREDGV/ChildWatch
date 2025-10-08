package ru.example.childwatch.audio

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles writing streamed PCM audio into a WAV file.
 */
class StreamRecorder(private val context: Context) {

    private var randomAccessFile: RandomAccessFile? = null
    private var targetFile: File? = null
    private var totalBytes: Long = 0
    private var sampleRate: Int = 44100
    private var channelCount: Int = 1
    private var startTimestamp: Long = 0L

    fun start(sampleRate: Int, channelCount: Int): Boolean {
        stopInternal(save = false)

        this.sampleRate = sampleRate
        this.channelCount = channelCount
        this.totalBytes = 0
        this.startTimestamp = System.currentTimeMillis()

        return try {
            val dir = resolveOutputDir()
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val fileName = buildFileName(startTimestamp)
            val file = File(dir, fileName)
            val raf = RandomAccessFile(file, "rw")
            writeWavHeaderPlaceholder(raf, sampleRate, channelCount)

            targetFile = file
            randomAccessFile = raf
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start stream recorder", e)
            targetFile = null
            randomAccessFile = null
            false
        }
    }

    fun write(buffer: ByteArray) {
        val raf = randomAccessFile ?: return
        try {
            raf.seek(raf.length())
            raf.write(buffer)
            totalBytes += buffer.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write audio chunk", e)
        }
    }

    fun stop(): RecordingMetadata? {
        return stopInternal(save = true)
    }

    fun cancel() {
        stopInternal(save = false)
    }

    private fun stopInternal(save: Boolean): RecordingMetadata? {
        val raf = randomAccessFile ?: return null
        val file = targetFile
        randomAccessFile = null
        targetFile = null

        return try {
            raf.channel.force(true)
            updateHeader(raf, totalBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize WAV header", e)
            null
        } finally {
            try {
                raf.close()
            } catch (ignored: Exception) {
            }
            val valid = save && totalBytes > 0 && file != null
            if (!valid && file?.exists() == true) {
                file.delete()
            }
            val durationMs = if (totalBytes > 0) {
                (totalBytes * 1000L) / (sampleRate * channelCount * 2L)
            } else {
                0L
            }
            val metadata = if (valid && file != null) {
                RecordingMetadata(
                    id = file.nameWithoutExtension,
                    fileName = file.name,
                    filePath = file.absolutePath,
                    createdAt = startTimestamp,
                    durationMs = durationMs,
                    sizeBytes = totalBytes
                )
            } else {
                null
            }
            totalBytes = 0
            metadata
        }
    }

    private fun resolveOutputDir(): File {
        val externalDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        return if (externalDir != null) {
            File(externalDir, RECORDINGS_FOLDER)
        } else {
            File(RecordingRepository.getRecordingsDir(context), RECORDINGS_FOLDER)
        }
    }

    private fun buildFileName(timestamp: Long): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return "stream_${formatter.format(Date(timestamp))}.wav"
    }

    private fun writeWavHeaderPlaceholder(raf: RandomAccessFile, sampleRate: Int, channelCount: Int) {
        val bitsPerSample = 16
        val byteRate = sampleRate * channelCount * bitsPerSample / 8
        val blockAlign = channelCount * bitsPerSample / 8

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(0) // Placeholder for chunk size
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // Subchunk1 size
        header.putShort(1) // Audio format PCM
        header.putShort(channelCount.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(0) // Placeholder for data size
        header.flip()
        raf.seek(0)
        raf.write(header.array())
    }

    private fun updateHeader(raf: RandomAccessFile, dataSize: Long) {
        val riffSize = (36 + dataSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val dataSizeInt = dataSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        raf.seek(4)
        raf.write(intToLe(riffSize))
        raf.seek(40)
        raf.write(intToLe(dataSizeInt))
    }

    private fun intToLe(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    companion object {
        private const val TAG = "StreamRecorder"
        private const val RECORDINGS_FOLDER = "stream_recordings"
    }
}
