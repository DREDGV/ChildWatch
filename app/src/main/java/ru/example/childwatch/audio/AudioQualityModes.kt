package ru.example.childwatch.audio

/**
 * Audio Quality Modes for different listening scenarios
 * Provides predefined configurations for optimal audio experience
 */
enum class AudioQualityMode(
    val displayName: String,
    val description: String,
    val config: AudioEnhancer.Config
) {
    ORIGINAL(
        displayName = "📡 Оригинал",
        description = "Без обработки, чистый звук (по умолчанию)",
        config = AudioEnhancer.Config(
            noiseSuppressionEnabled = false,
            gainBoostDb = 0,
            compressionEnabled = false
        )
    ),

    VOICE(
        displayName = "🎤 Голос",
        description = "Оптимизация для речи: шумоподавление, компрессия, лёгкое усиление",
        config = AudioEnhancer.Config(
            noiseSuppressionEnabled = true,
            gainBoostDb = 2,
            compressionEnabled = true
        )
    ),

    QUIET_SOUNDS(
        displayName = "🔇 Тихие звуки",
        description = "Максимальное усиление, минимум шумоподавления — для слабых сигналов",
        config = AudioEnhancer.Config(
            noiseSuppressionEnabled = false,
            gainBoostDb = 6,
            compressionEnabled = true
        )
    ),

    OUTDOOR(
        displayName = "🌳 Улица",
        description = "Агрессивное шумоподавление, защита от ветра и транспорта",
        config = AudioEnhancer.Config(
            noiseSuppressionEnabled = true,
            gainBoostDb = 1,
            compressionEnabled = true
        )
    )
}

/**
 * Audio Quality Manager
 * Handles switching between different quality modes
 */
class AudioQualityManager {
    
    private var currentMode: AudioQualityMode = AudioQualityMode.ORIGINAL
    private var customConfig: AudioEnhancer.Config? = null
    
    fun getCurrentMode(): AudioQualityMode = currentMode
    
    fun setMode(mode: AudioQualityMode) {
        currentMode = mode
        customConfig = null // Reset custom config when switching modes
    }
    
    fun setCustomConfig(config: AudioEnhancer.Config) {
        customConfig = config
        currentMode = AudioQualityMode.ORIGINAL // Reset to original when using custom
    }
    
    fun getCurrentConfig(): AudioEnhancer.Config {
        return customConfig ?: currentMode.config
    }
    
    fun isCustomMode(): Boolean = customConfig != null
    
    fun resetToDefault() {
        currentMode = AudioQualityMode.ORIGINAL
        customConfig = null
    }
}
