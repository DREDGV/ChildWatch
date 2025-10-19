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

    /**
     * Audio filter modes optimized for different scenarios
     */
    enum class FilterMode {
        VOICE,          // Голос - усиление речи, подавление фона
        QUIET_SOUNDS,   // Тихие звуки - максимальное усиление, минимум шумоподавления
        MUSIC,          // Музыка - естественное звучание, без агрессивной обработки
        OUTDOOR         // Улица - подавление ветра и шума, усиление речи
    }

    data class Config(
        val mode: FilterMode = FilterMode.VOICE,
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

        val sampleCount = chunk.size / 2
        val shortArray = ensureShortBuffer(sampleCount)
        ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shortArray, 0, sampleCount)

        // Apply mode-specific processing
        when (localConfig.mode) {
            FilterMode.VOICE -> processVoiceMode(shortArray, sampleCount)
            FilterMode.QUIET_SOUNDS -> processQuietSoundsMode(shortArray, sampleCount)
            FilterMode.MUSIC -> processMusicMode(shortArray, sampleCount)
            FilterMode.OUTDOOR -> processOutdoorMode(shortArray, sampleCount)
        }

        val byteBuffer = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.asShortBuffer().put(shortArray, 0, sampleCount)
        return byteBuffer.array()
    }

    /**
     * VOICE mode: Optimize for speech clarity
     * - Moderate noise gate to remove background noise
     * - Boost gain for clear speech
     * - Compression to even out volume
     */
    private fun processVoiceMode(data: ShortArray, length: Int) {
        applyNoiseGate(data, length, threshold = 100f, kneeWidth = 150f)
        applyGain(data, length, multiplier = 2.0f) // +6dB
        applyCompressor(data, length, threshold = 0.7f, ratio = 3.0f)
    }

    /**
     * QUIET_SOUNDS mode: Maximum sensitivity
     * - Minimal noise gate
     * - High gain boost
     * - Light compression
     */
    private fun processQuietSoundsMode(data: ShortArray, length: Int) {
        applyNoiseGate(data, length, threshold = 30f, kneeWidth = 50f)
        applyGain(data, length, multiplier = 4.0f) // +12dB
        applyCompressor(data, length, threshold = 0.6f, ratio = 2.5f)
    }

    /**
     * MUSIC mode: Natural sound
     * - Very light noise gate
     * - Minimal gain
     * - Light compression for dynamics
     */
    private fun processMusicMode(data: ShortArray, length: Int) {
        applyNoiseGate(data, length, threshold = 20f, kneeWidth = 100f)
        applyGain(data, length, multiplier = 1.2f) // +1.6dB
        applyCompressor(data, length, threshold = 0.8f, ratio = 1.5f)
    }

    /**
     * OUTDOOR mode: Reduce wind/traffic noise
     * - Aggressive noise gate for wind/traffic
     * - Moderate gain for speech
     * - Strong compression
     */
    private fun processOutdoorMode(data: ShortArray, length: Int) {
        applyNoiseGate(data, length, threshold = 200f, kneeWidth = 200f)
        applyGain(data, length, multiplier = 2.5f) // +8dB
        applyCompressor(data, length, threshold = 0.65f, ratio = 4.0f)
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

    private fun applyNoiseGate(data: ShortArray, length: Int, threshold: Float, kneeWidth: Float) {
        if (length == 0) return

        for (i in 0 until length) {
            val absValue = abs(data[i].toFloat())

            when {
                // Gate quiet noise
                absValue < threshold -> {
                    data[i] = (data[i] * 0.1f).toInt().toShort() // Reduce by 90%
                }
                // Smooth transition zone (soft knee)
                absValue < threshold + kneeWidth -> {
                    val position = (absValue - threshold) / kneeWidth
                    val reduction = 0.1f + (position * 0.9f) // Fade from 10% to 100%
                    data[i] = (data[i] * reduction).toInt().toShort()
                }
                // Above threshold - pass through unchanged
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
     * Apply compressor to prevent clipping and even out volume
     * @param threshold Compression starts at this fraction of max (0.0-1.0)
     * @param ratio Compression ratio (e.g., 2.0 = 2:1, 4.0 = 4:1)
     */
    private fun applyCompressor(data: ShortArray, length: Int, threshold: Float, ratio: Float) {
        val thresholdValue = Short.MAX_VALUE * threshold

        for (i in 0 until length) {
            val value = data[i].toFloat()
            val absValue = abs(value)

            if (absValue > thresholdValue) {
                // Calculate how much we're over threshold
                val excess = absValue - thresholdValue
                // Reduce excess by ratio
                val compressed = thresholdValue + (excess / ratio)
                // Apply sign and convert back
                data[i] = (compressed * (if (value >= 0) 1 else -1)).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
    }
}
