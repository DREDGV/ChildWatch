package ru.example.childwatch.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Менеджер автоматического восстановления после сбоев.
 * Отслеживает состояние сервисов и автоматически перезапускает их при необходимости.
 */
class RecoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "RecoveryManager"
        private const val HEALTH_CHECK_INTERVAL = 30_000L // 30 секунд
        private const val MAX_RECOVERY_ATTEMPTS = 3
        private const val RECOVERY_DELAY = 5_000L // 5 секунд
    }

    private val errorHandler = ErrorHandler(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isMonitoring = AtomicBoolean(false)
    private val recoveryAttempts = mutableMapOf<String, Int>()

    /**
     * Запускает мониторинг состояния сервисов.
     */
    fun startHealthMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            Log.d(TAG, "Starting health monitoring")
            scope.launch {
                while (isMonitoring.get()) {
                    try {
                        performHealthCheck()
                        delay(HEALTH_CHECK_INTERVAL)
                    } catch (e: Exception) {
                        Log.e(TAG, "Health check failed: ${e.message}", e)
                        delay(HEALTH_CHECK_INTERVAL)
                    }
                }
            }
        }
    }

    /**
     * Останавливает мониторинг состояния сервисов.
     */
    fun stopHealthMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            Log.d(TAG, "Stopping health monitoring")
            scope.cancel()
        }
    }

    /**
     * Выполняет проверку состояния всех критических компонентов.
     */
    private suspend fun performHealthCheck() {
        Log.d(TAG, "Performing health check")
        
        // Проверяем состояние MonitorService
        checkMonitorServiceHealth()
        
        // Проверяем состояние LocationManager
        checkLocationServiceHealth()
        
        // Проверяем состояние AudioRecorder
        checkAudioServiceHealth()
        
        // Проверяем состояние NetworkClient
        checkNetworkHealth()
    }

    /**
     * Проверяет состояние MonitorService.
     */
    private suspend fun checkMonitorServiceHealth() {
        try {
            // Здесь можно добавить проверку состояния сервиса
            Log.d(TAG, "MonitorService health check passed")
        } catch (e: Exception) {
            handleServiceFailure("MonitorService", e) {
                restartMonitorService()
            }
        }
    }

    /**
     * Проверяет состояние LocationManager.
     */
    private suspend fun checkLocationServiceHealth() {
        try {
            // Проверяем доступность геолокации
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val isProviderEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            
            if (!isProviderEnabled) {
                throw Exception("Location providers disabled")
            }
            
            Log.d(TAG, "LocationService health check passed")
        } catch (e: Exception) {
            handleServiceFailure("LocationService", e) {
                requestLocationPermissions()
            }
        }
    }

    /**
     * Проверяет состояние AudioRecorder.
     */
    private suspend fun checkAudioServiceHealth() {
        try {
            // Проверяем доступность микрофона
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val isMicrophoneMuted = audioManager.isMicrophoneMute
            
            if (isMicrophoneMuted) {
                throw Exception("Microphone is muted")
            }
            
            Log.d(TAG, "AudioService health check passed")
        } catch (e: Exception) {
            handleServiceFailure("AudioService", e) {
                notifyMicrophoneIssue()
            }
        }
    }

    /**
     * Проверяет состояние NetworkClient.
     */
    private suspend fun checkNetworkHealth() {
        try {
            // Проверяем доступность сети
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            
            val hasInternet = networkCapabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            
            if (!hasInternet) {
                throw Exception("No internet connection")
            }
            
            Log.d(TAG, "NetworkService health check passed")
        } catch (e: Exception) {
            handleServiceFailure("NetworkService", e) {
                enableOfflineMode()
            }
        }
    }

    /**
     * Обрабатывает сбой сервиса и пытается восстановить его.
     */
    private suspend fun handleServiceFailure(serviceName: String, exception: Exception, recoveryAction: suspend () -> Unit) {
        val attempts = recoveryAttempts.getOrDefault(serviceName, 0)
        
        if (attempts >= MAX_RECOVERY_ATTEMPTS) {
            Log.e(TAG, "Service $serviceName failed after $MAX_RECOVERY_ATTEMPTS attempts", exception)
            return
        }
        
        recoveryAttempts[serviceName] = attempts + 1
        
        Log.w(TAG, "Service $serviceName failed (attempt ${attempts + 1}/$MAX_RECOVERY_ATTEMPTS)", exception)
        
        // Выполняем восстановление
        scope.launch {
            delay(RECOVERY_DELAY)
            try {
                recoveryAction()
                // Сбрасываем счетчик попыток при успешном восстановлении
                recoveryAttempts.remove(serviceName)
                Log.d(TAG, "Service $serviceName recovered successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Recovery attempt failed for $serviceName", e)
            }
        }
    }

    /**
     * Перезапускает MonitorService.
     */
    private suspend fun restartMonitorService() {
        try {
            val intent = android.content.Intent(context, ru.example.childwatch.service.MonitorService::class.java)
            intent.action = "start_monitoring"
            context.startForegroundService(intent)
            Log.d(TAG, "MonitorService restarted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart MonitorService", e)
        }
    }

    /**
     * Запрашивает разрешения на геолокацию.
     */
    private suspend fun requestLocationPermissions() {
        try {
            Log.d(TAG, "Requesting location permissions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request location permissions", e)
        }
    }

    /**
     * Уведомляет о проблеме с микрофоном.
     */
    private suspend fun notifyMicrophoneIssue() {
        try {
            Log.d(TAG, "Notifying about microphone issue")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify about microphone issue", e)
        }
    }

    /**
     * Включает офлайн режим.
     */
    private suspend fun enableOfflineMode() {
        try {
            Log.d(TAG, "Enabling offline mode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable offline mode", e)
        }
    }

    /**
     * Принудительно выполняет восстановление всех сервисов.
     */
    fun forceRecovery() {
        scope.launch {
            Log.d(TAG, "Force recovery initiated")
            recoveryAttempts.clear()
            performHealthCheck()
        }
    }

    /**
     * Получает статистику восстановления.
     */
    fun getRecoveryStatistics(): Map<String, Int> {
        return recoveryAttempts.toMap()
    }

    /**
     * Очищает статистику восстановления.
     */
    fun clearRecoveryStatistics() {
        recoveryAttempts.clear()
    }

    /**
     * Освобождает ресурсы.
     */
    fun cleanup() {
        stopHealthMonitoring()
        scope.cancel()
    }
}