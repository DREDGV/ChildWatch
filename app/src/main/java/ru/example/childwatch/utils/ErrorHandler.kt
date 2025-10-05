package ru.example.childwatch.utils

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Централизованная система обработки ошибок с graceful degradation.
 * Обеспечивает корректную обработку различных типов ошибок и fallback механизмы.
 */
class ErrorHandler(private val context: Context) {

    companion object {
        private const val TAG = "ErrorHandler"
        
        // Типы ошибок
        enum class ErrorType {
            NETWORK,
            PERMISSION,
            LOCATION,
            AUDIO,
            STORAGE,
            SECURITY,
            UNKNOWN
        }
        
        // Уровни критичности
        enum class Severity {
            LOW,        // Информационные сообщения
            MEDIUM,     // Предупреждения, но приложение продолжает работать
            HIGH,       // Критические ошибки, влияющие на функциональность
            CRITICAL    // Критические ошибки, требующие немедленного внимания
        }
    }

    data class ErrorInfo(
        val type: ErrorType,
        val severity: Severity,
        val message: String,
        val throwable: Throwable? = null,
        val context: String = "",
        val canRetry: Boolean = false,
        val fallbackAction: (() -> Unit)? = null
    )

    private val errorLog = mutableListOf<ErrorInfo>()
    private val maxLogSize = 100

    /**
     * Обрабатывает ошибку с учетом её типа и критичности.
     */
    fun handleError(errorInfo: ErrorInfo) {
        // Логируем ошибку
        logError(errorInfo)
        
        // Добавляем в историю ошибок
        addToErrorLog(errorInfo)
        
        // Выполняем действия в зависимости от критичности
        when (errorInfo.severity) {
            Severity.LOW -> {
                Log.i(TAG, "Low severity error: ${errorInfo.message}")
            }
            Severity.MEDIUM -> {
                Log.w(TAG, "Medium severity error: ${errorInfo.message}")
                showUserMessage(errorInfo.message, Toast.LENGTH_SHORT)
            }
            Severity.HIGH -> {
                Log.e(TAG, "High severity error: ${errorInfo.message}", errorInfo.throwable)
                showUserMessage(errorInfo.message, Toast.LENGTH_LONG)
                executeFallback(errorInfo)
            }
            Severity.CRITICAL -> {
                Log.e(TAG, "Critical error: ${errorInfo.message}", errorInfo.throwable)
                showUserMessage("Критическая ошибка: ${errorInfo.message}", Toast.LENGTH_LONG)
                executeFallback(errorInfo)
                // Для критических ошибок можно добавить отправку в аналитику
                reportCriticalError(errorInfo)
            }
        }
    }

    /**
     * Обрабатывает сетевые ошибки с автоматическими retry.
     */
    fun handleNetworkError(
        throwable: Throwable,
        operation: String,
        retryAction: (() -> Unit)? = null,
        maxRetries: Int = 3
    ) {
        val errorInfo = ErrorInfo(
            type = ErrorType.NETWORK,
            severity = Severity.MEDIUM,
            message = "Ошибка сети при $operation: ${getNetworkErrorMessage(throwable)}",
            throwable = throwable,
            context = operation,
            canRetry = retryAction != null,
            fallbackAction = retryAction
        )
        
        handleError(errorInfo)
    }

    /**
     * Обрабатывает ошибки разрешений.
     */
    fun handlePermissionError(
        permission: String,
        fallbackAction: (() -> Unit)? = null
    ) {
        val errorInfo = ErrorInfo(
            type = ErrorType.PERMISSION,
            severity = Severity.HIGH,
            message = "Отсутствует разрешение: $permission",
            context = "Permission: $permission",
            fallbackAction = fallbackAction
        )
        
        handleError(errorInfo)
    }

    /**
     * Обрабатывает ошибки геолокации.
     */
    fun handleLocationError(
        throwable: Throwable,
        fallbackAction: (() -> Unit)? = null
    ) {
        val errorInfo = ErrorInfo(
            type = ErrorType.LOCATION,
            severity = Severity.MEDIUM,
            message = "Ошибка получения геолокации: ${getLocationErrorMessage(throwable)}",
            throwable = throwable,
            context = "LocationService",
            fallbackAction = fallbackAction
        )
        
        handleError(errorInfo)
    }

    /**
     * Обрабатывает ошибки аудио.
     */
    fun handleAudioError(
        throwable: Throwable,
        fallbackAction: (() -> Unit)? = null
    ) {
        val errorInfo = ErrorInfo(
            type = ErrorType.AUDIO,
            severity = Severity.MEDIUM,
            message = "Ошибка записи аудио: ${getAudioErrorMessage(throwable)}",
            throwable = throwable,
            context = "AudioRecorder",
            fallbackAction = fallbackAction
        )
        
        handleError(errorInfo)
    }

    /**
     * Обрабатывает ошибки безопасности.
     */
    fun handleSecurityError(
        message: String,
        throwable: Throwable? = null,
        fallbackAction: (() -> Unit)? = null
    ) {
        val errorInfo = ErrorInfo(
            type = ErrorType.SECURITY,
            severity = Severity.HIGH,
            message = "Ошибка безопасности: $message",
            throwable = throwable,
            context = "Security",
            fallbackAction = fallbackAction
        )
        
        handleError(errorInfo)
    }

    /**
     * Выполняет fallback действие если оно доступно.
     */
    private fun executeFallback(errorInfo: ErrorInfo) {
        errorInfo.fallbackAction?.let { action ->
            try {
                CoroutineScope(Dispatchers.Main).launch {
                    withContext(Dispatchers.IO) {
                        action()
                    }
                }
                Log.d(TAG, "Fallback action executed for ${errorInfo.type}")
            } catch (e: Exception) {
                Log.e(TAG, "Fallback action failed", e)
            }
        }
    }

    /**
     * Показывает сообщение пользователю.
     */
    private fun showUserMessage(message: String, duration: Int) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Toast.makeText(context, message, duration).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show toast: ${e.message}")
            }
        }
    }

    /**
     * Логирует ошибку с детальной информацией.
     */
    private fun logError(errorInfo: ErrorInfo) {
        val logMessage = buildString {
            appendLine("=== ERROR REPORT ===")
            appendLine("Type: ${errorInfo.type}")
            appendLine("Severity: ${errorInfo.severity}")
            appendLine("Message: ${errorInfo.message}")
            appendLine("Context: ${errorInfo.context}")
            appendLine("Can Retry: ${errorInfo.canRetry}")
            appendLine("Timestamp: ${System.currentTimeMillis()}")
            
            errorInfo.throwable?.let { throwable ->
                appendLine("Exception: ${throwable.javaClass.simpleName}")
                appendLine("Stack Trace:")
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                appendLine(sw.toString())
            }
            appendLine("===================")
        }
        
        when (errorInfo.severity) {
            Severity.LOW -> Log.i(TAG, logMessage)
            Severity.MEDIUM -> Log.w(TAG, logMessage)
            Severity.HIGH, Severity.CRITICAL -> Log.e(TAG, logMessage)
        }
    }

    /**
     * Добавляет ошибку в историю.
     */
    private fun addToErrorLog(errorInfo: ErrorInfo) {
        synchronized(errorLog) {
            errorLog.add(errorInfo)
            if (errorLog.size > maxLogSize) {
                errorLog.removeAt(0) // Удаляем самую старую ошибку
            }
        }
    }

    /**
     * Отправляет критическую ошибку в аналитику (заглушка).
     */
    private fun reportCriticalError(errorInfo: ErrorInfo) {
        // Здесь можно добавить отправку в Firebase Crashlytics, Sentry и т.д.
        Log.e(TAG, "CRITICAL ERROR REPORTED: ${errorInfo.message}")
    }

    /**
     * Получает понятное сообщение для сетевых ошибок.
     */
    private fun getNetworkErrorMessage(throwable: Throwable): String {
        return when {
            throwable.message?.contains("timeout", ignoreCase = true) == true -> 
                "Превышено время ожидания"
            throwable.message?.contains("connection", ignoreCase = true) == true -> 
                "Ошибка подключения к серверу"
            throwable.message?.contains("host", ignoreCase = true) == true -> 
                "Сервер недоступен"
            else -> "Неизвестная сетевая ошибка"
        }
    }

    /**
     * Получает понятное сообщение для ошибок геолокации.
     */
    private fun getLocationErrorMessage(throwable: Throwable): String {
        return when {
            throwable.message?.contains("permission", ignoreCase = true) == true -> 
                "Нет разрешения на геолокацию"
            throwable.message?.contains("provider", ignoreCase = true) == true -> 
                "Провайдер геолокации недоступен"
            throwable.message?.contains("timeout", ignoreCase = true) == true -> 
                "Превышено время ожидания геолокации"
            else -> "Ошибка получения местоположения"
        }
    }

    /**
     * Получает понятное сообщение для ошибок аудио.
     */
    private fun getAudioErrorMessage(throwable: Throwable): String {
        return when {
            throwable.message?.contains("permission", ignoreCase = true) == true -> 
                "Нет разрешения на запись аудио"
            throwable.message?.contains("busy", ignoreCase = true) == true -> 
                "Микрофон занят другим приложением"
            throwable.message?.contains("format", ignoreCase = true) == true -> 
                "Неподдерживаемый формат аудио"
            else -> "Ошибка записи аудио"
        }
    }

    /**
     * Получает историю ошибок.
     */
    fun getErrorHistory(): List<ErrorInfo> {
        return synchronized(errorLog) {
            errorLog.toList()
        }
    }

    /**
     * Очищает историю ошибок.
     */
    fun clearErrorHistory() {
        synchronized(errorLog) {
            errorLog.clear()
        }
    }

    /**
     * Получает статистику ошибок по типам.
     */
    fun getErrorStatistics(): Map<ErrorType, Int> {
        return synchronized(errorLog) {
            errorLog.groupBy { it.type }
                .mapValues { it.value.size }
        }
    }
}
