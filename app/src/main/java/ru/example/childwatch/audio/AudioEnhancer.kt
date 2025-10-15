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
        val gainBoostDb: Int = 0,
        val compressionEnabled: Boolean = false
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
        if (!localConfig.noiseSuppressionEnabled && !localConfig.hasGain && !localConfig.compressionEnabled) {
            return chunk
        }

        val sampleCount = chunk.size / 2
        val shortArray = ensureShortBuffer(sampleCount)
        ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shortArray, 0, sampleCount)

        // Process in order: noise gate → gain → compression
        if (localConfig.noiseSuppressionEnabled) {
            applyNoiseGate(shortArray, sampleCount)
        }
        if (localConfig.hasGain) {
            applyGain(shortArray, sampleCount, localConfig.gainMultiplier)
        }
        if (localConfig.compressionEnabled) {
            applyCompressor(shortArray, sampleCount)
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

        // Very gentle noise gate - only removes extremely quiet background noise
        // Threshold: 50 = very quiet (was 200, too aggressive)
        val threshold = 50f

        // Very wide soft knee for natural sound
        val kneeWidth = 150f

        for (i in 0 until length) {
            val absValue = abs(data[i].toFloat())

            when {
                // Only gate EXTREMELY quiet noise
                absValue < threshold -> {
                    data[i] = (data[i] * 0.1f).toInt().toShort() // Reduce by 90%, don't mute completely
                }
                // Very wide smooth transition zone
                absValue < threshold + kneeWidth -> {
                    val position = (absValue - threshold) / kneeWidth
                    val reduction = 0.1f + (position * 0.9f) // Fade from 10% to 100%
                    data[i] = (data[i] * reduction).toInt().toShort()
                }
                // Above threshold - pass through unchanged
                // This is where speech and normal sounds live!
            }
        }
    }

    private fun applyGain(data: ShortArray, length: Int, multiplier: Float) {
        // Simple gain with gentle limiting
        for (i in 0 until length) {
            val boosted = data[i] * multiplier

            // Simple tanh-like soft limiting for natural sound
            val limited = when {
                boosted > Short.MAX_VALUE * 0.8f -> {
                    val excess = boosted - Short.MAX_VALUE * 0.8f
                    Short.MAX_VALUE * 0.8f + (excess * 0.5f) // Compress the peaks gently
                }
                boosted < Short.MIN_VALUE * 0.8f -> {
                    val excess = boosted - Short.MIN_VALUE * 0.8f
                    Short.MIN_VALUE * 0.8f + (excess * 0.5f)
                }
                else -> boosted
            }

            data[i] = limited.toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    /**
     * Apply simple compressor to prevent clipping and even out volume
     * Threshold at 70% of max, with 2:1 ratio
     */
    private fun applyCompressor(data: ShortArray, length: Int) {
        val threshold = Short.MAX_VALUE * 0.7f // Compress above 70%
        val ratio = 2.0f // 2:1 compression ratio

        for (i in 0 until length) {
            val value = data[i].toFloat()
            val absValue = abs(value)

            if (absValue > threshold) {
                // Calculate how much we're over threshold
                val excess = absValue - threshold
                // Reduce excess by ratio
                val compressed = threshold + (excess / ratio)
                // Apply sign and convert back
                data[i] = (compressed * (if (value >= 0) 1 else -1)).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
    }
}
