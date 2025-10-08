package ru.example.childwatch.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

/**
 * Simple audio post-processor for streamed PCM 16-bit data.
 * Applies optional noise gating and gain boosting before playback/recording.
 */
class AudioEnhancer {

    @Volatile
    private var config: Config = Config()

    private val shortBuffer = ThreadLocal.withInitial { ShortArray(0) }

    data class Config(
        val noiseSuppressionEnabled: Boolean = true,
        val gainBoostDb: Int = 0
    ) {
        val hasGain: Boolean = gainBoostDb != 0
        val gainMultiplier: Float = 10.0f.pow(gainBoostDb / 20f)
    }

    fun updateConfig(newConfig: Config) {
        config = newConfig
    }

    fun getConfig(): Config = config

    fun process(chunk: ByteArray): ByteArray {
        val localConfig = config
        if (!localConfig.noiseSuppressionEnabled && !localConfig.hasGain) {
            return chunk
        }

        val sampleCount = chunk.size / 2
        val shortArray = ensureShortBuffer(sampleCount)
        ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shortArray, 0, sampleCount)

        if (localConfig.noiseSuppressionEnabled) {
            applyNoiseGate(shortArray, sampleCount)
        }
        if (localConfig.hasGain) {
            applyGain(shortArray, sampleCount, localConfig.gainMultiplier)
        }

        val byteBuffer = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.asShortBuffer().put(shortArray, 0, sampleCount)
        return byteBuffer.array()
    }

    private fun ensureShortBuffer(size: Int): ShortArray {
        val buffer = shortBuffer.get()
        if (buffer.size >= size) {
            return buffer
        }
        val newBuffer = ShortArray(size)
        shortBuffer.set(newBuffer)
        return newBuffer
    }

    private fun applyNoiseGate(data: ShortArray, length: Int) {
        if (length == 0) return
        var noiseAccumulator = 0f
        for (i in 0 until length) {
            noiseAccumulator += abs(data[i].toFloat())
        }
        val averageAmplitude = noiseAccumulator / length
        val threshold = (averageAmplitude * 0.35f).coerceAtLeast(600f)

        for (i in 0 until length) {
            val value = data[i]
            if (abs(value.toFloat()) < threshold) {
                data[i] = 0
            }
        }
    }

    private fun applyGain(data: ShortArray, length: Int, multiplier: Float) {
        for (i in 0 until length) {
            val boosted = (data[i] * multiplier).toInt()
            data[i] = boosted.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
