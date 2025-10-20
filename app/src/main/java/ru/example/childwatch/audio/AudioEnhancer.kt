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
        ORIGINAL,       // Оригинал - без фильтров и обработки (по умолчанию)
        VOICE,          // Голос - усиление речи, подавление фона
        QUIET_SOUNDS,   // Тихие звуки - максимальное усиление, минимум шумоподавления
        MUSIC,          // Музыка - естественное звучание, без агрессивной обработки
        OUTDOOR         // Улица - подавление ветра и шума, усиление речи
    }

    data class Config(
        val mode: FilterMode = FilterMode.ORIGINAL,
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
            FilterMode.ORIGINAL -> {
                // No processing - pass through original audio
                // Audio remains unchanged in shortArray
            }
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
     * - Light noise gate to remove background noise
     * - Moderate boost for clear speech
     * - Gentle compression to even out volume
     */
    private fun processVoiceMode(data: ShortArray, length: Int) {
        applyNoiseGate(data, length, threshold = 80f, kneeWidth = 200f)
        applyGain(data, length, multiplier = 1.5f) // +3.5dB (reduced from +6dB)
        applyCompressor(data, length, threshold = 0.75f, ratio = 2.5f)
    }

    /**
     * QUIET_SOUNDS mode: Maximum sensitivity
     * - Very light noise gate
     * - Moderate gain boost
     * - Light compression
     */
    private fun processQuietSoundsMode(data: ShortArray, length: Int) {
        applyNoiseGate(data, length, threshold = 40f, kneeWidth = 80f)
        applyGain(data, length, multiplier = 2.5f) // +8dB (reduced from +12dB)
        applyCompressor(data, length, threshold = 0.7f, ratio = 2.0f)
    }

    /**
     * MUSIC mode: Natural sound
     * - Minimal noise gate
     * - Very light gain
     * - Minimal compression for dynamics
     */
    private fun processMusicMode(data: ShortArray, length: Int) {
        applyNoiseGate(data, length, threshold = 15f, kneeWidth = 120f)
        applyGain(data, length, multiplier = 1.1f) // +0.8dB (reduced from +1.6dB)
        applyCompressor(data, length, threshold = 0.85f, ratio = 1.3f)
    }

    /**
     * OUTDOOR mode: Reduce wind/traffic noise
     * - Moderate noise gate for wind/traffic
     * - Balanced gain for speech
     * - Moderate compression
     */
    private fun processOutdoorMode(data: ShortArray, length: Int) {
        applyNoiseGate(data, length, threshold = 150f, kneeWidth = 250f)
        applyGain(data, length, multiplier = 1.8f) // +5.1dB (reduced from +8dB)
        applyCompressor(data, length, threshold = 0.7f, ratio = 3.0f)
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
        // Адаптивный порог: вычисляем среднюю громкость
        val avg = if (length > 0) data.map { abs(it.toFloat()) }.average().toFloat() else threshold
        val adaptiveThreshold = (threshold + avg * 0.25f).coerceAtMost(threshold * 2)
        for (i in 0 until length) {
            val absValue = abs(data[i].toFloat())
            when {
                absValue < adaptiveThreshold -> {
                    data[i] = (data[i] * 0.07f).toInt().toShort() // Reduce by 93%
                }
                absValue < adaptiveThreshold + kneeWidth -> {
                    val position = (absValue - adaptiveThreshold) / kneeWidth
                    val reduction = 0.07f + (position * 0.93f)
                    data[i] = (data[i] * reduction).toInt().toShort()
                }
            }
        }
    }

    private fun applyGain(data: ShortArray, length: Int, multiplier: Float) {
        // Мягкое усиление с адаптивным лимитированием
        val softLimit = Short.MAX_VALUE * 0.80f
        for (i in 0 until length) {
            val boosted = data[i] * multiplier
            val limited = when {
                boosted > softLimit -> {
                    val excess = boosted - softLimit
                    softLimit + (excess * 0.18f) // Ещё мягче
                }
                boosted < -softLimit -> {
                    val excess = boosted + softLimit
                    -softLimit + (excess * 0.18f)
                }
                else -> boosted
            }
            data[i] = limited.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
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
                val excess = absValue - thresholdValue
                // Более плавная компрессия: чуть выше threshold, ratio ниже
                val compressed = thresholdValue + (excess / (ratio * 1.2f))
                data[i] = (compressed * (if (value >= 0) 1 else -1)).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
    }
}
