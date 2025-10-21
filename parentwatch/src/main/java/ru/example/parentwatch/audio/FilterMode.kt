package ru.example.parentwatch.audio

/**
 * Audio filter modes (shared enum)
 * This enum is used across the app to avoid conflicts
 */
enum class FilterMode {
    /**
     * Original - no filtering, pure sound
     * - AudioSource: MIC (no preprocessing)
     * - Effects: None
     */
    ORIGINAL,

    /**
     * Voice - optimize for speech clarity
     * - AudioSource: VOICE_COMMUNICATION
     * - Effects: NoiseSuppressor + AutomaticGainControl
     */
    VOICE,

    /**
     * Quiet Sounds - boost quiet audio
     * - AudioSource: MIC
     * - Effects: AutomaticGainControl only
     */
    QUIET_SOUNDS,

    /**
     * Music - natural sound preservation
     * - AudioSource: MIC
     * - Effects: None
     */
    MUSIC,

    /**
     * Outdoor - reduce wind/traffic noise
     * - AudioSource: VOICE_COMMUNICATION
     * - Effects: NoiseSuppressor only
     */
    OUTDOOR
}
