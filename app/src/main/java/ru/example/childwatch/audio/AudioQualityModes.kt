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
            gainBoostDb = 0,
            compressionEnabled = false
        )
    ),

    NOISE_REDUCTION(
        displayName = "Шумоподавление",
        description = "Убирает фоновый шум и шипение",
        config = AudioEnhancer.Config(
            noiseSuppressionEnabled = true,
            gainBoostDb = 0,
            compressionEnabled = false
        )
    ),

    VOICE_ENHANCED(
        displayName = "Голосовой режим",
        description = "Оптимизирован для речи с компрессией",
        config = AudioEnhancer.Config(
            noiseSuppressionEnabled = true,
            gainBoostDb = 3, // Легкое усиление
            compressionEnabled = true // Компрессия для ровного звука
        )
    ),

    BALANCED(
        displayName = "Сбалансированный",
        description = "Оптимальный баланс качества и громкости",
        config = AudioEnhancer.Config(
            noiseSuppressionEnabled = true,
            gainBoostDb = 2, // Минимальное усиление
            compressionEnabled = true // Компрессия предотвращает искажения
        )
    ),

    CRYSTAL_CLEAR(
        displayName = "Кристальная чистота",
        description = "Максимальное качество с защитой от искажений",
        config = AudioEnhancer.Config(
            noiseSuppressionEnabled = true,
            gainBoostDb = 4, // Умеренное усиление (снижено с 6)
            compressionEnabled = true // Обязательная компрессия
        )
    ),

    SLEEP_MODE(
        displayName = "Ночной режим",
        description = "Усиление тихих звуков для сна ребенка",
        config = AudioEnhancer.Config(
            noiseSuppressionEnabled = true,
            gainBoostDb = 6, // Максимум 6 dB (было 12 - слишком много!)
            compressionEnabled = true // Обязательная компрессия против клиппинга
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
