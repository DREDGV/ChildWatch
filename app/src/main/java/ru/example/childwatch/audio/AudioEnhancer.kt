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

        // Calculate RMS instead of average amplitude for better noise detection
        var sumOfSquares = 0.0
        for (i in 0 until length) {
            val normalized = data[i].toFloat() / Short.MAX_VALUE
            sumOfSquares += normalized * normalized
        }
        val rms = kotlin.math.sqrt(sumOfSquares / length).toFloat()

        // Much lower threshold - only remove very quiet noise
        // 200f instead of 600f = less aggressive gating
        val baseThreshold = 200f
        val adaptiveThreshold = (rms * Short.MAX_VALUE * 0.15f).coerceAtLeast(baseThreshold)

        // Soft knee for smoother gating (gradual reduction instead of hard cut)
        val kneeWidth = adaptiveThreshold * 0.5f

        for (i in 0 until length) {
            val absValue = abs(data[i].toFloat())

            when {
                // Below threshold - gate completely
                absValue < adaptiveThreshold - kneeWidth -> {
                    data[i] = 0
                }
                // In knee range - gradual reduction
                absValue < adaptiveThreshold + kneeWidth -> {
                    val position = (absValue - (adaptiveThreshold - kneeWidth)) / (2 * kneeWidth)
                    val reduction = position.coerceIn(0f, 1f)
                    data[i] = (data[i] * reduction).toInt().toShort()
                }
                // Above threshold - pass through unchanged
                else -> {
                    // No change
                }
            }
        }
    }

    private fun applyGain(data: ShortArray, length: Int, multiplier: Float) {
        // Apply gain with soft-clipping to prevent harsh distortion
        for (i in 0 until length) {
            val normalized = data[i].toFloat() / Short.MAX_VALUE
            val boosted = normalized * multiplier

            // Soft-clip instead of hard-clip to prevent harsh distortion
            val clipped = when {
                boosted > 1.0f -> {
                    // Soft knee at high levels
                    val excess = boosted - 0.9f
                    0.9f + (excess / (1 + abs(excess)))
                }
                boosted < -1.0f -> {
                    // Soft knee at low levels
                    val excess = boosted + 0.9f
                    -0.9f + (excess / (1 + abs(excess)))
                }
                else -> boosted
            }

            data[i] = (clipped * Short.MAX_VALUE).toInt()
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
