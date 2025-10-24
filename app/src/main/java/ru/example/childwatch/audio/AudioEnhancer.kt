package ru.example.childwatch.audio

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

/**
 * Post-processing pipeline applied on the parent (listener) device.
 * Provides lightweight DSP without adding noticeable latency.
 */
class AudioEnhancer {

    companion object {
        private const val TAG = "AudioEnhancer"
        private const val MAX_AMPLITUDE = 32767
    }

    @Volatile
    private var config: Config = Config()

    private val scratchBuffer = ThreadLocal.withInitial { ShortArray(0) }

    /**
     * Volume boost presets. Values chosen empirically to avoid harsh clipping.
     */
    enum class VolumeMode {
        QUIET,   // 1.0x
        NORMAL,  // 1.5x
        LOUD,    // 3.0x
        BOOST    // 4.0x (use with caution)
    }

    data class Config(
        val mode: FilterMode = FilterMode.ORIGINAL,
        val volumeMode: VolumeMode = VolumeMode.NORMAL,
        val noiseSuppressionEnabled: Boolean = true,
        val gainBoostDb: Int = 0,
        val compressionEnabled: Boolean = false
    ) {
        val hasGain: Boolean = gainBoostDb != 0
        val gainMultiplier: Float = 10.0f.pow(gainBoostDb / 20f)
        val volumeMultiplier: Float = when (volumeMode) {
            VolumeMode.QUIET -> 1.0f
            VolumeMode.NORMAL -> 1.5f
            VolumeMode.LOUD -> 3.0f
            VolumeMode.BOOST -> 4.0f
        }
    }

    fun updateConfig(newConfig: Config) {
        config = newConfig
    }

    fun getConfig(): Config = config

    /**
     * Entry point. Converts PCM to short array, applies filter + volume,
     * then serialises back to byte array.
     */
    fun process(chunk: ByteArray): ByteArray {
        val localConfig = config
        val sampleCount = chunk.size / 2
        val samples = ensureBuffer(sampleCount)

        ByteBuffer.wrap(chunk)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples, 0, sampleCount)

        when (localConfig.mode) {
            FilterMode.ORIGINAL -> { /* pass-through */ }
            FilterMode.VOICE -> applyVoicePreset(samples, sampleCount)
            FilterMode.QUIET_SOUNDS -> applyQuietPreset(samples, sampleCount)
            FilterMode.MUSIC -> applyMusicPreset(samples, sampleCount)
            FilterMode.OUTDOOR -> applyOutdoorPreset(samples, sampleCount)
        }

        applyVolume(samples, sampleCount, localConfig.volumeMultiplier)

        val output = ByteBuffer.allocate(sampleCount * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        output.asShortBuffer().put(samples, 0, sampleCount)
        return output.array()
    }

    private fun ensureBuffer(size: Int): ShortArray {
        val current = scratchBuffer.get()
        if (current != null && current.size >= size) {
            return current
        }
        val resized = ShortArray(size)
        scratchBuffer.set(resized)
        return resized
    }

    /**
     * Voice preset – remove rumble, boost presence.
     */
    private fun applyVoicePreset(data: ShortArray, length: Int) {
        val highPassAlpha = 0.93f
        var prev = 0f
        for (i in 0 until length) {
            val current = data[i].toFloat()
            val filtered = current - highPassAlpha * prev
            prev = current
            data[i] = clamp(filtered)
        }
        applyCompressor(data, length, threshold = 0.7f, ratio = 2.2f)
    }

    /**
     * Quiet preset – strong gain and soft compression.
     */
    private fun applyQuietPreset(data: ShortArray, length: Int) {
        applyNoiseGate(data, length, threshold = 35f, knee = 70f)
        applySoftGain(data, length, quietMultiplier = 2.4f, loudMultiplier = 1.3f)
        applyCompressor(data, length, threshold = 0.65f, ratio = 1.8f)
    }

    /**
     * Music preset – gentle “smile” EQ.
     */
    private fun applyMusicPreset(data: ShortArray, length: Int) {
        var low = data.firstOrNull()?.toFloat() ?: 0f
        var high = 0f
        for (i in 0 until length) {
            val input = data[i].toFloat()
            low = 0.6f * low + 0.4f * input
            val highComponent = input - 0.88f * high
            high = input
            val mixed = (0.65f * input) + (0.2f * low) + (0.15f * highComponent)
            data[i] = clamp(mixed)
        }
    }

    /**
     * Outdoor preset – aggressive low-pass to tame wind/traffic.
     */
    private fun applyOutdoorPreset(data: ShortArray, length: Int) {
        val alpha = 0.82f
        var prev = data.firstOrNull()?.toFloat() ?: 0f
        for (i in 0 until length) {
            val current = data[i].toFloat()
            val smoothed = alpha * prev + (1 - alpha) * current
            prev = smoothed
            data[i] = clamp(smoothed)
        }
        applyCompressor(data, length, threshold = 0.75f, ratio = 3.0f)
    }

    private fun applySoftGain(data: ShortArray, length: Int, quietMultiplier: Float, loudMultiplier: Float) {
        val softLimit = MAX_AMPLITUDE * 0.85f
        for (i in 0 until length) {
            val value = data[i].toFloat()
            val multiplier = if (abs(value) < softLimit * 0.5f) quietMultiplier else loudMultiplier
            val boosted = value * multiplier
            val limited = when {
                boosted > softLimit -> softLimit + (boosted - softLimit) * 0.2f
                boosted < -softLimit -> -(softLimit + (abs(boosted) - softLimit) * 0.2f)
                else -> boosted
            }
            data[i] = clamp(limited)
        }
    }

    private fun applyNoiseGate(data: ShortArray, length: Int, threshold: Float, knee: Float) {
        if (length == 0) return
        val adaptive = threshold + (estimateRms(data, length) * 0.25f)
        val gate = adaptive.coerceAtMost(threshold * 2)
        for (i in 0 until length) {
            val absValue = abs(data[i].toFloat())
            when {
                absValue < gate -> data[i] = clamp(data[i] * 0.06f)
                absValue < gate + knee -> {
                    val factor = (absValue - gate) / knee
                    val reduction = 0.06f + factor * 0.94f
                    data[i] = clamp(data[i] * reduction)
                }
            }
        }
    }

    private fun applyCompressor(data: ShortArray, length: Int, threshold: Float, ratio: Float) {
        val limit = MAX_AMPLITUDE * threshold
        for (i in 0 until length) {
            val value = data[i].toFloat()
            val absValue = abs(value)
            if (absValue > limit) {
                val excess = absValue - limit
                val compressed = limit + (excess / (ratio * 1.2f))
                data[i] = clamp(compressed * if (value >= 0) 1 else -1)
            }
        }
    }

    private fun applyVolume(data: ShortArray, length: Int, multiplier: Float) {
        if (multiplier == 1.0f) return
        val safeMultiplier = multiplier.coerceAtMost(5.0f)
        for (i in 0 until length) {
            data[i] = clamp(data[i] * safeMultiplier)
        }
    }

    private fun estimateRms(data: ShortArray, length: Int): Float {
        if (length == 0) return 0f
        var sum = 0.0
        for (i in 0 until length) {
            sum += data[i] * data[i]
        }
        return kotlin.math.sqrt(sum / length).toFloat()
    }

    private fun clamp(value: Float): Short {
        return value.toInt().coerceIn(-MAX_AMPLITUDE, MAX_AMPLITUDE).toShort()
    }
}
