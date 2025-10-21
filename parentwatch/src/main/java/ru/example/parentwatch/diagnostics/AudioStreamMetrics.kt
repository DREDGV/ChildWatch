package ru.example.parentwatch.diagnostics

/**
 * Этап D: Comprehensive metrics for audio streaming diagnostics
 * Used by both sender (ParentWatch) and receiver (ChildWatch)
 */
data class AudioStreamMetrics(
    // WebSocket status
    val wsStatus: WsStatus = WsStatus.DISCONNECTED,
    val wsRetryAttempt: Int = 0,
    val wsMaxRetries: Int = 5,
    val connectionDuration: Long = 0, // milliseconds since connection

    // Audio stream status
    val audioStatus: AudioStatus = AudioStatus.STOPPED,
    val bytesPerSecond: Long = 0,
    val framesTotal: Long = 0,
    val frameSize: Int = 640, // bytes per frame (20ms at 16kHz)

    // Network
    val networkType: NetworkType = NetworkType.NONE,
    val networkName: String = "",
    val signalStrength: SignalStrength = SignalStrength.UNKNOWN,

    // Ping (RTT - Round Trip Time)
    val pingMs: Long = 0, // -1 = not measured yet
    val pingStatus: PingStatus = PingStatus.UNKNOWN,

    // Queue (receiver only)
    val queueDepth: Int = 0,
    val queueCapacity: Int = 100,
    val underrunCount: Int = 0, // How many times queue was empty when needed

    // System
    val batteryLevel: Int = 0, // 0-100%
    val batteryCharging: Boolean = false,

    // Audio effects (sender only - ParentWatch)
    val activeEffects: Set<AudioEffect> = emptySet(),
    val audioSource: AudioSourceType = AudioSourceType.MIC,

    // Errors
    val lastError: ErrorInfo? = null,
    val errorCount: Int = 0,

    // Metadata
    val timestamp: Long = System.currentTimeMillis(),
    val sampleRate: Int = 16000, // Hz
    val channelCount: Int = 1 // mono
) {
    /**
     * Expected bytes per second for current configuration
     * Formula: sampleRate * channelCount * 2 bytes (16-bit PCM)
     */
    val expectedBytesPerSecond: Long
        get() = sampleRate.toLong() * channelCount * 2

    /**
     * Data rate percentage (0-100%)
     * 100% = sending/receiving at expected rate
     */
    val dataRatePercent: Int
        get() = if (expectedBytesPerSecond > 0) {
            ((bytesPerSecond * 100) / expectedBytesPerSecond).toInt().coerceIn(0, 200)
        } else 0

    /**
     * Queue fill percentage (0-100%)
     */
    val queueFillPercent: Int
        get() = if (queueCapacity > 0) {
            ((queueDepth * 100) / queueCapacity).coerceIn(0, 100)
        } else 0

    /**
     * Estimated buffer duration in milliseconds
     */
    val bufferDurationMs: Long
        get() = if (frameSize > 0) {
            (queueDepth * frameSize * 1000L) / expectedBytesPerSecond
        } else 0

    /**
     * Overall health status
     */
    val healthStatus: HealthStatus
        get() = when {
            wsStatus != WsStatus.CONNECTED -> HealthStatus.ERROR
            audioStatus == AudioStatus.ERROR -> HealthStatus.ERROR
            lastError?.severity == ErrorSeverity.ERROR -> HealthStatus.ERROR
            pingMs > 200 -> HealthStatus.WARNING
            dataRatePercent < 80 -> HealthStatus.WARNING
            underrunCount > 5 -> HealthStatus.WARNING
            else -> HealthStatus.GOOD
        }
}

enum class WsStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RETRYING,
    ERROR
}

enum class AudioStatus {
    STOPPED,
    RECORDING,  // ParentWatch - sending
    PLAYING,    // ChildWatch - receiving
    BUFFERING,  // ChildWatch - waiting for initial buffer
    ERROR
}

enum class NetworkType {
    NONE,
    WIFI,
    MOBILE,
    ETHERNET,
    UNKNOWN
}

enum class SignalStrength {
    UNKNOWN,
    POOR,      // 0-1 bars
    FAIR,      // 2 bars
    GOOD,      // 3 bars
    EXCELLENT  // 4+ bars
}

enum class PingStatus {
    UNKNOWN,     // Not measured yet
    EXCELLENT,   // < 50ms
    GOOD,        // 50-100ms
    FAIR,        // 100-200ms
    POOR         // > 200ms
}

enum class AudioEffect {
    NOISE_SUPPRESSOR,
    AUTO_GAIN_CONTROL,
    ACOUSTIC_ECHO_CANCELER
}

enum class AudioSourceType {
    MIC,
    VOICE_COMMUNICATION,
    CAMCORDER,
    UNPROCESSED
}

enum class HealthStatus {
    GOOD,
    WARNING,
    ERROR
}

data class ErrorInfo(
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val severity: ErrorSeverity = ErrorSeverity.ERROR,
    val exception: String? = null // Exception class name
) {
    fun toShortString(): String {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
        return "$time - $message"
    }
}

enum class ErrorSeverity {
    INFO,
    WARNING,
    ERROR
}
