package ru.example.childwatch.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Улучшенная система логирования и аудита действий.
 * Обеспечивает детальное логирование всех операций для отладки и мониторинга.
 */
class AuditLogger(private val context: Context) {

    companion object {
        private const val TAG = "AuditLogger"
        private const val MAX_LOG_SIZE = 1000 // Максимальное количество записей в памяти
        private const val LOG_FILE_MAX_SIZE = 5 * 1024 * 1024 // 5MB
        private const val LOG_RETENTION_DAYS = 7 // Хранить логи 7 дней
        
        // Уровни логирования
        enum class LogLevel {
            DEBUG, INFO, WARN, ERROR, AUDIT
        }
        
        // Категории событий
        enum class EventCategory {
            AUTHENTICATION,
            LOCATION,
            AUDIO,
            NETWORK,
            SECURITY,
            SETTINGS,
            SYSTEM,
            USER_ACTION
        }
    }

    data class LogEntry(
        val timestamp: Long,
        val level: LogLevel,
        val category: EventCategory,
        val message: String,
        val details: Map<String, Any>? = null,
        val userId: String? = null,
        val sessionId: String? = null
    )

    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val sessionId = UUID.randomUUID().toString()
    
    // Файлы логов
    private val logDir = File(context.filesDir, "logs")
    private val auditLogFile = File(logDir, "audit.log")
    private val errorLogFile = File(logDir, "error.log")
    private val debugLogFile = File(logDir, "debug.log")

    init {
        // Создаем директорию для логов
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        
        // Запускаем периодическую очистку логов
        startLogCleanup()
        
        // Логируем старт сессии
        logAudit("Session started", EventCategory.SYSTEM, mapOf("sessionId" to sessionId))
    }

    /**
     * Логирует аудитное событие.
     */
    fun logAudit(message: String, category: EventCategory, details: Map<String, Any>? = null) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = LogLevel.AUDIT,
            category = category,
            message = message,
            details = details,
            sessionId = sessionId
        )
        addLogEntry(entry)
    }

    /**
     * Логирует информационное событие.
     */
    fun logInfo(message: String, category: EventCategory, details: Map<String, Any>? = null) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = LogLevel.INFO,
            category = category,
            message = message,
            details = details,
            sessionId = sessionId
        )
        addLogEntry(entry)
    }

    /**
     * Логирует предупреждение.
     */
    fun logWarning(message: String, category: EventCategory, details: Map<String, Any>? = null) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = LogLevel.WARN,
            category = category,
            message = message,
            details = details,
            sessionId = sessionId
        )
        addLogEntry(entry)
    }

    /**
     * Логирует ошибку.
     */
    fun logError(message: String, category: EventCategory, throwable: Throwable? = null, details: Map<String, Any>? = null) {
        val errorDetails = mutableMapOf<String, Any>()
        details?.let { errorDetails.putAll(it) }
        
        throwable?.let {
            errorDetails["exception"] = it.javaClass.simpleName
            errorDetails["exceptionMessage"] = it.message ?: "No message"
            errorDetails["stackTrace"] = it.stackTraceToString()
        }
        
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = LogLevel.ERROR,
            category = category,
            message = message,
            details = errorDetails,
            sessionId = sessionId
        )
        addLogEntry(entry)
    }

    /**
     * Логирует действие пользователя.
     */
    fun logUserAction(action: String, details: Map<String, Any>? = null) {
        logAudit("User action: $action", EventCategory.USER_ACTION, details)
    }

    /**
     * Логирует событие аутентификации.
     */
    fun logAuthentication(event: String, success: Boolean, details: Map<String, Any>? = null) {
        val authDetails = mutableMapOf<String, Any>()
        authDetails["success"] = success
        details?.let { authDetails.putAll(it) }
        
        logAudit("Authentication: $event", EventCategory.AUTHENTICATION, authDetails)
    }

    /**
     * Логирует событие геолокации.
     */
    fun logLocationEvent(event: String, latitude: Double? = null, longitude: Double? = null, accuracy: Float? = null) {
        val details = mutableMapOf<String, Any>()
        latitude?.let { details["latitude"] = it }
        longitude?.let { details["longitude"] = it }
        accuracy?.let { details["accuracy"] = it }
        
        logInfo("Location: $event", EventCategory.LOCATION, details)
    }

    /**
     * Логирует событие аудио.
     */
    fun logAudioEvent(event: String, duration: Long? = null, fileSize: Long? = null) {
        val details = mutableMapOf<String, Any>()
        duration?.let { details["duration"] = it }
        fileSize?.let { details["fileSize"] = it }
        
        logInfo("Audio: $event", EventCategory.AUDIO, details)
    }

    /**
     * Логирует событие сети.
     */
    fun logNetworkEvent(event: String, url: String? = null, statusCode: Int? = null, responseTime: Long? = null) {
        val details = mutableMapOf<String, Any>()
        url?.let { details["url"] = it }
        statusCode?.let { details["statusCode"] = it }
        responseTime?.let { details["responseTime"] = it }
        
        logInfo("Network: $event", EventCategory.NETWORK, details)
    }

    /**
     * Логирует событие безопасности.
     */
    fun logSecurityEvent(event: String, severity: String, details: Map<String, Any>? = null) {
        val securityDetails = mutableMapOf<String, Any>()
        securityDetails["severity"] = severity
        details?.let { securityDetails.putAll(it) }
        
        logAudit("Security: $event", EventCategory.SECURITY, securityDetails)
    }

    /**
     * Добавляет запись в очередь логов.
     */
    private fun addLogEntry(entry: LogEntry) {
        logQueue.offer(entry)
        
        // Ограничиваем размер очереди
        while (logQueue.size > MAX_LOG_SIZE) {
            logQueue.poll()
        }
        
        // Записываем в файл асинхронно
        scope.launch {
            writeToFile(entry)
        }
        
        // Также выводим в системный лог для отладки
        when (entry.level) {
            LogLevel.DEBUG -> Log.d(TAG, formatLogEntry(entry))
            LogLevel.INFO -> Log.i(TAG, formatLogEntry(entry))
            LogLevel.WARN -> Log.w(TAG, formatLogEntry(entry))
            LogLevel.ERROR -> Log.e(TAG, formatLogEntry(entry))
            LogLevel.AUDIT -> Log.i(TAG, "[AUDIT] ${formatLogEntry(entry)}")
        }
    }

    /**
     * Записывает запись в соответствующий файл лога.
     */
    private suspend fun writeToFile(entry: LogEntry) {
        try {
            val logFile = when (entry.level) {
                LogLevel.ERROR -> errorLogFile
                LogLevel.AUDIT -> auditLogFile
                else -> debugLogFile
            }
            
            val logLine = formatLogEntry(entry) + "\n"
            
            // Проверяем размер файла и ротируем при необходимости
            if (logFile.exists() && logFile.length() > LOG_FILE_MAX_SIZE) {
                rotateLogFile(logFile)
            }
            
            logFile.appendText(logLine)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log entry to file", e)
        }
    }

    /**
     * Ротирует файл лога.
     */
    private fun rotateLogFile(logFile: File) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val rotatedFile = File(logFile.parent, "${logFile.nameWithoutExtension}_$timestamp${logFile.extension}")
            logFile.renameTo(rotatedFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log file", e)
        }
    }

    /**
     * Форматирует запись лога для вывода.
     */
    private fun formatLogEntry(entry: LogEntry): String {
        val timestamp = dateFormat.format(Date(entry.timestamp))
        val details = entry.details?.let { 
            " | Details: ${it.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
        } ?: ""
        
        return "[$timestamp] [${entry.level}] [${entry.category}] ${entry.message}$details"
    }

    /**
     * Запускает периодическую очистку старых логов.
     */
    private fun startLogCleanup() {
        scope.launch {
            while (true) {
                try {
                    cleanupOldLogs()
                    delay(24 * 60 * 60 * 1000L) // Каждые 24 часа
                } catch (e: Exception) {
                    Log.e(TAG, "Log cleanup failed", e)
                    delay(60 * 60 * 1000L) // Повторить через час при ошибке
                }
            }
        }
    }

    /**
     * Очищает старые файлы логов.
     */
    private fun cleanupOldLogs() {
        try {
            val cutoffTime = System.currentTimeMillis() - (LOG_RETENTION_DAYS * 24 * 60 * 60 * 1000L)
            
            logDir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoffTime) {
                    file.delete()
                    Log.d(TAG, "Deleted old log file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old logs", e)
        }
    }

    /**
     * Получает последние записи логов.
     */
    fun getRecentLogs(count: Int = 100): List<LogEntry> {
        return logQueue.toList().takeLast(count)
    }

    /**
     * Получает логи по категории.
     */
    fun getLogsByCategory(category: EventCategory, count: Int = 100): List<LogEntry> {
        return logQueue.filter { it.category == category }.toList().takeLast(count)
    }

    /**
     * Получает логи по уровню.
     */
    fun getLogsByLevel(level: LogLevel, count: Int = 100): List<LogEntry> {
        return logQueue.filter { it.level == level }.toList().takeLast(count)
    }

    /**
     * Экспортирует логи в файл.
     */
    fun exportLogs(outputFile: File, category: EventCategory? = null, level: LogLevel? = null): Boolean {
        return try {
            val filteredLogs = logQueue.filter { entry ->
                (category == null || entry.category == category) &&
                (level == null || entry.level == level)
            }
            
            val logContent = filteredLogs.joinToString("\n") { formatLogEntry(it) }
            outputFile.writeText(logContent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs", e)
            false
        }
    }

    /**
     * Очищает все логи.
     */
    fun clearLogs() {
        logQueue.clear()
        try {
            logDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear log files", e)
        }
    }

    /**
     * Получает статистику логов.
     */
    fun getLogStatistics(): Map<String, Any> {
        val totalLogs = logQueue.size
        val logsByLevel = logQueue.groupBy { it.level }.mapValues { it.value.size }
        val logsByCategory = logQueue.groupBy { it.category }.mapValues { it.value.size }
        
        return mapOf(
            "totalLogs" to totalLogs,
            "logsByLevel" to logsByLevel,
            "logsByCategory" to logsByCategory,
            "sessionId" to sessionId,
            "logDirSize" to getLogDirectorySize()
        )
    }

    /**
     * Получает размер директории логов.
     */
    private fun getLogDirectorySize(): Long {
        return try {
            logDir.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Освобождает ресурсы.
     */
    fun cleanup() {
        scope.cancel()
        logAudit("Session ended", EventCategory.SYSTEM)
    }
}
