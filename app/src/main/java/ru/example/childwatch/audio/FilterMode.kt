package ru.example.childwatch.audio

/**
 * Audio filter modes (shared enum)
 * This enum is used across the app to avoid conflicts
 *
 * NOTE: Filtering happens on sender side (ParentWatch) using system effects
 * ChildWatch (receiver) just displays filter selection UI
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
