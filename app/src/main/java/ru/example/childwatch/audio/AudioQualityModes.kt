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
    NORMAL(
        displayName = "Обычный",
        description = "Без обработки, оригинальное качество",
        config = AudioEnhancer.Config(
            noiseSuppressionEnabled = false,
            gainBoostDb = 0
        )
    ),
    
    NOISE_REDUCTION(
        displayName = "Шумоподавление",
        description = "Убирает фоновый шум и шипение",
        config = AudioEnhancer.Config(
            noiseSuppressionEnabled = true,
            gainBoostDb = 0
        )
    ),
    
    VOICE_ENHANCED(
        displayName = "Голосовой режим",
        description = "Оптимизирован для речи, убирает шум",
        config = AudioEnhancer.Config(
            noiseSuppressionEnabled = true,
            gainBoostDb = 3 // Легкое усиление для речи
        )
    ),
    
    LOUD_ENVIRONMENT(
        displayName = "Громкая среда",
        description = "Максимальное усиление для тихих звуков",
        config = AudioEnhancer.Config(
            noiseSuppressionEnabled = true,
            gainBoostDb = 9 // Сильное усиление
        )
    ),
    
    CRYSTAL_CLEAR(
        displayName = "Кристальная чистота",
        description = "Максимальное качество с фильтрацией",
        config = AudioEnhancer.Config(
            noiseSuppressionEnabled = true,
            gainBoostDb = 6 // Умеренное усиление
        )
    ),
    
    SLEEP_MODE(
        displayName = "Ночной режим",
        description = "Только громкие звуки, минимум шума",
        config = AudioEnhancer.Config(
            noiseSuppressionEnabled = true,
            gainBoostDb = 12 // Максимальное усиление для тихих звуков
        )
    )
}

/**
 * Audio Quality Manager
 * Handles switching between different quality modes
 */
class AudioQualityManager {
    
    private var currentMode: AudioQualityMode = AudioQualityMode.NOISE_REDUCTION
    private var customConfig: AudioEnhancer.Config? = null
    
    fun getCurrentMode(): AudioQualityMode = currentMode
    
    fun setMode(mode: AudioQualityMode) {
        currentMode = mode
        customConfig = null // Reset custom config when switching modes
    }
    
    fun setCustomConfig(config: AudioEnhancer.Config) {
        customConfig = config
        currentMode = AudioQualityMode.NORMAL // Reset to normal when using custom
    }
    
    fun getCurrentConfig(): AudioEnhancer.Config {
        return customConfig ?: currentMode.config
    }
    
    fun isCustomMode(): Boolean = customConfig != null
    
    fun resetToDefault() {
        currentMode = AudioQualityMode.NOISE_REDUCTION
        customConfig = null
    }
}
