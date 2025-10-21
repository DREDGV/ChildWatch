package ru.example.parentwatch.audio

import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * System Audio Effects Manager (Этап B)
 * Uses built-in Android audio effects instead of custom DSP
 *
 * Available effects:
 * - NoiseSuppressor: Removes background noise
 * - AutomaticGainControl (AGC): Normalizes volume levels
 * - AcousticEchoCanceler (AEC): Cancels echo (for speakerphone)
 */
class SystemAudioEffects(
    private val audioSessionId: Int
) {
    companion object {
        private const val TAG = "AUDIO"

        /**
         * Audio source selection based on filter mode (Этап B)
         */
        fun getAudioSourceForMode(mode: FilterMode): Int {
            return when (mode) {
                FilterMode.ORIGINAL -> MediaRecorder.AudioSource.MIC // Pure, no preprocessing
                FilterMode.VOICE -> MediaRecorder.AudioSource.VOICE_COMMUNICATION // Auto NS+AGC
                FilterMode.QUIET_SOUNDS -> MediaRecorder.AudioSource.MIC // AGC only
                FilterMode.MUSIC -> MediaRecorder.AudioSource.MIC // No effects
                FilterMode.OUTDOOR -> MediaRecorder.AudioSource.VOICE_COMMUNICATION // NS for wind/noise
            }
        }
    }

    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null

    private var currentMode: FilterMode = FilterMode.ORIGINAL

    /**
     * Check if system effects are available on this device
     */
    fun checkAvailability(): EffectAvailability {
        val nsAvailable = NoiseSuppressor.isAvailable()
        val agcAvailable = AutomaticGainControl.isAvailable()
        val aecAvailable = AcousticEchoCanceler.isAvailable()

        Log.d(TAG, "FX availability: NS=$nsAvailable AGC=$agcAvailable AEC=$aecAvailable")

        return EffectAvailability(
            noiseSuppressor = nsAvailable,
            automaticGainControl = agcAvailable,
            acousticEchoCanceler = aecAvailable
        )
    }

    /**
     * Apply effects based on filter mode
     */
    fun applyMode(mode: FilterMode) {
        Log.d(TAG, "FX applying mode: $mode")
        currentMode = mode

        // Release all effects first
        releaseEffects()

        // Apply mode-specific effects
        when (mode) {
            FilterMode.ORIGINAL -> {
                // No effects - pure original sound
                Log.d(TAG, "FX enabled: NS=false AGC=false AEC=false mode=ORIGINAL src=MIC")
            }

            FilterMode.VOICE -> {
                // NS + AGC for speech clarity
                enableNoiseSuppressor()
                enableAutomaticGainControl()
                Log.d(TAG, "FX enabled: NS=true AGC=true AEC=false mode=VOICE src=VOICE_COMMUNICATION")
            }

            FilterMode.QUIET_SOUNDS -> {
                // AGC only to boost quiet sounds
                enableAutomaticGainControl()
                Log.d(TAG, "FX enabled: NS=false AGC=true AEC=false mode=QUIET_SOUNDS src=MIC")
            }

            FilterMode.MUSIC -> {
                // No effects - preserve natural dynamics
                Log.d(TAG, "FX enabled: NS=false AGC=false AEC=false mode=MUSIC src=MIC")
            }

            FilterMode.OUTDOOR -> {
                // NS only to reduce wind/traffic noise
                enableNoiseSuppressor()
                Log.d(TAG, "FX enabled: NS=true AGC=false AEC=false mode=OUTDOOR src=VOICE_COMMUNICATION")
            }
        }
    }

    private fun enableNoiseSuppressor() {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)
                noiseSuppressor?.enabled = true
                Log.d(TAG, "FX NoiseSuppressor enabled")
            } else {
                Log.w(TAG, "FX NoiseSuppressor not available on this device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "FX NoiseSuppressor init error", e)
        }
    }

    private fun enableAutomaticGainControl() {
        try {
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(audioSessionId)
                automaticGainControl?.enabled = true
                Log.d(TAG, "FX AutomaticGainControl enabled")
            } else {
                Log.w(TAG, "FX AGC not available on this device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "FX AGC init error", e)
        }
    }

    private fun enableAcousticEchoCanceler() {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                acousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId)
                acousticEchoCanceler?.enabled = true
                Log.d(TAG, "FX AcousticEchoCanceler enabled")
            } else {
                Log.w(TAG, "FX AEC not available on this device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "FX AEC init error", e)
        }
    }

    /**
     * Release all effects (call when stopping recording)
     */
    fun releaseEffects() {
        try {
            noiseSuppressor?.release()
            noiseSuppressor = null
        } catch (e: Exception) {
            Log.w(TAG, "FX NS release error (ignored)", e)
        }

        try {
            automaticGainControl?.release()
            automaticGainControl = null
        } catch (e: Exception) {
            Log.w(TAG, "FX AGC release error (ignored)", e)
        }

        try {
            acousticEchoCanceler?.release()
            acousticEchoCanceler = null
        } catch (e: Exception) {
            Log.w(TAG, "FX AEC release error (ignored)", e)
        }
    }

    /**
     * Get current effect status for diagnostics
     */
    fun getStatus(): EffectStatus {
        return EffectStatus(
            mode = currentMode,
            nsEnabled = noiseSuppressor?.enabled == true,
            agcEnabled = automaticGainControl?.enabled == true,
            aecEnabled = acousticEchoCanceler?.enabled == true
        )
    }

    data class EffectAvailability(
        val noiseSuppressor: Boolean,
        val automaticGainControl: Boolean,
        val acousticEchoCanceler: Boolean
    )

    data class EffectStatus(
        val mode: FilterMode,
        val nsEnabled: Boolean,
        val agcEnabled: Boolean,
        val aecEnabled: Boolean
    )
}
