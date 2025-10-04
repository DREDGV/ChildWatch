package ru.example.childwatch.utils

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Менеджер оптимизации батареи.
 * Адаптивно управляет частотой обновлений и интенсивностью работы в зависимости от уровня заряда.
 */
class BatteryOptimizationManager(private val context: Context) {

    companion object {
        private const val TAG = "BatteryOptimizationManager"
        private const val BATTERY_CHECK_INTERVAL = 60_000L // 1 минута
        private const val LOW_BATTERY_THRESHOLD = 20 // 20%
        private const val CRITICAL_BATTERY_THRESHOLD = 10 // 10%
        private const val HIGH_BATTERY_THRESHOLD = 80 // 80%
    }

    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isMonitoring = AtomicBoolean(false)
    
    private var currentBatteryLevel = 100
    private var isCharging = false
    private var isPowerSaveMode = false

    // Адаптивные интервалы в зависимости от уровня батареи
    private var adaptiveLocationInterval = 5 * 60 * 1000L // 5 минут по умолчанию
    private var adaptiveAudioDuration = 30 * 1000L // 30 секунд по умолчанию
    private var adaptivePhotoInterval = 10 * 60 * 1000L // 10 минут по умолчанию

    /**
     * Запускает мониторинг батареи.
     */
    fun startBatteryMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            Log.d(TAG, "Starting battery monitoring")
            scope.launch {
                while (isMonitoring.get()) {
                    try {
                        updateBatteryStatus()
                        adjustPerformanceSettings()
                        delay(BATTERY_CHECK_INTERVAL)
                    } catch (e: Exception) {
                        Log.e(TAG, "Battery monitoring error", e)
                        delay(BATTERY_CHECK_INTERVAL)
                    }
                }
            }
        }
    }

    /**
     * Останавливает мониторинг батареи.
     */
    fun stopBatteryMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            Log.d(TAG, "Stopping battery monitoring")
            scope.cancel()
        }
    }

    /**
     * Обновляет статус батареи.
     */
    private fun updateBatteryStatus() {
        try {
            // Получаем уровень заряда
            currentBatteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            
            // Проверяем, заряжается ли устройство
            val batteryStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            isCharging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                    batteryStatus == BatteryManager.BATTERY_STATUS_FULL
            
            // Проверяем режим энергосбережения
            isPowerSaveMode = powerManager.isPowerSaveMode
            
            Log.d(TAG, "Battery status: $currentBatteryLevel%, charging: $isCharging, power save: $isPowerSaveMode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update battery status", e)
        }
    }

    /**
     * Адаптивно настраивает параметры производительности.
     */
    private fun adjustPerformanceSettings() {
        when {
            currentBatteryLevel <= CRITICAL_BATTERY_THRESHOLD -> {
                setCriticalBatteryMode()
            }
            currentBatteryLevel <= LOW_BATTERY_THRESHOLD -> {
                setLowBatteryMode()
            }
            currentBatteryLevel >= HIGH_BATTERY_THRESHOLD && isCharging -> {
                setHighBatteryMode()
            }
            isPowerSaveMode -> {
                setPowerSaveMode()
            }
            else -> {
                setNormalMode()
            }
        }
    }

    /**
     * Режим критически низкого заряда батареи.
     */
    private fun setCriticalBatteryMode() {
        Log.w(TAG, "Critical battery mode activated")
        
        // Минимальная активность
        adaptiveLocationInterval = 30 * 60 * 1000L // 30 минут
        adaptiveAudioDuration = 10 * 1000L // 10 секунд
        adaptivePhotoInterval = 60 * 60 * 1000L // 1 час
        
        // Отключаем несущественные функции
        disableNonEssentialFeatures()
        
        // Уведомляем пользователя
        notifyBatteryStatus("Критически низкий заряд батареи. Приложение переведено в режим энергосбережения.")
    }

    /**
     * Режим низкого заряда батареи.
     */
    private fun setLowBatteryMode() {
        Log.w(TAG, "Low battery mode activated")
        
        // Сниженная активность
        adaptiveLocationInterval = 15 * 60 * 1000L // 15 минут
        adaptiveAudioDuration = 20 * 1000L // 20 секунд
        adaptivePhotoInterval = 30 * 60 * 1000L // 30 минут
        
        // Ограничиваем некоторые функции
        limitNonEssentialFeatures()
        
        // Уведомляем пользователя
        notifyBatteryStatus("Низкий заряд батареи. Частота обновлений снижена.")
    }

    /**
     * Режим высокого заряда батареи (при зарядке).
     */
    private fun setHighBatteryMode() {
        Log.d(TAG, "High battery mode activated")
        
        // Повышенная активность
        adaptiveLocationInterval = 2 * 60 * 1000L // 2 минуты
        adaptiveAudioDuration = 60 * 1000L // 60 секунд
        adaptivePhotoInterval = 5 * 60 * 1000L // 5 минут
        
        // Включаем все функции
        enableAllFeatures()
    }

    /**
     * Режим энергосбережения системы.
     */
    private fun setPowerSaveMode() {
        Log.w(TAG, "Power save mode activated")
        
        // Адаптируемся к системному режиму энергосбережения
        adaptiveLocationInterval = 20 * 60 * 1000L // 20 минут
        adaptiveAudioDuration = 15 * 1000L // 15 секунд
        adaptivePhotoInterval = 45 * 60 * 1000L // 45 минут
        
        // Ограничиваем фоновую активность
        limitBackgroundActivity()
    }

    /**
     * Обычный режим работы.
     */
    private fun setNormalMode() {
        Log.d(TAG, "Normal mode activated")
        
        // Стандартные интервалы
        adaptiveLocationInterval = 5 * 60 * 1000L // 5 минут
        adaptiveAudioDuration = 30 * 1000L // 30 секунд
        adaptivePhotoInterval = 10 * 60 * 1000L // 10 минут
        
        // Включаем все функции
        enableAllFeatures()
    }

    /**
     * Отключает несущественные функции.
     */
    private fun disableNonEssentialFeatures() {
        // Здесь можно добавить логику отключения несущественных функций
        Log.d(TAG, "Non-essential features disabled")
    }

    /**
     * Ограничивает несущественные функции.
     */
    private fun limitNonEssentialFeatures() {
        // Здесь можно добавить логику ограничения функций
        Log.d(TAG, "Non-essential features limited")
    }

    /**
     * Включает все функции.
     */
    private fun enableAllFeatures() {
        // Здесь можно добавить логику включения всех функций
        Log.d(TAG, "All features enabled")
    }

    /**
     * Ограничивает фоновую активность.
     */
    private fun limitBackgroundActivity() {
        // Здесь можно добавить логику ограничения фоновой активности
        Log.d(TAG, "Background activity limited")
    }

    /**
     * Уведомляет о статусе батареи.
     */
    private fun notifyBatteryStatus(message: String) {
        // Здесь можно добавить уведомление пользователя
        Log.i(TAG, "Battery notification: $message")
    }

    /**
     * Получает адаптивный интервал для геолокации.
     */
    fun getAdaptiveLocationInterval(): Long {
        return adaptiveLocationInterval
    }

    /**
     * Получает адаптивную длительность аудиозаписи.
     */
    fun getAdaptiveAudioDuration(): Long {
        return adaptiveAudioDuration
    }

    /**
     * Получает адаптивный интервал для фотографий.
     */
    fun getAdaptivePhotoInterval(): Long {
        return adaptivePhotoInterval
    }

    /**
     * Проверяет, нужно ли ограничить активность.
     */
    fun shouldLimitActivity(): Boolean {
        return currentBatteryLevel <= LOW_BATTERY_THRESHOLD || isPowerSaveMode
    }

    /**
     * Проверяет, можно ли выполнять интенсивные операции.
     */
    fun canPerformIntensiveOperations(): Boolean {
        return currentBatteryLevel >= HIGH_BATTERY_THRESHOLD && isCharging
    }

    /**
     * Получает текущий уровень батареи.
     */
    fun getCurrentBatteryLevel(): Int {
        return currentBatteryLevel
    }

    /**
     * Проверяет, заряжается ли устройство.
     */
    fun isDeviceCharging(): Boolean {
        return isCharging
    }

    /**
     * Проверяет, включен ли режим энергосбережения.
     */
    fun isPowerSaveModeEnabled(): Boolean {
        return isPowerSaveMode
    }

    /**
     * Получает рекомендации по оптимизации.
     */
    fun getOptimizationRecommendations(): List<String> {
        val recommendations = mutableListOf<String>()
        
        when {
            currentBatteryLevel <= CRITICAL_BATTERY_THRESHOLD -> {
                recommendations.add("Критически низкий заряд. Рекомендуется подключить зарядное устройство.")
                recommendations.add("Приложение работает в режиме минимального энергопотребления.")
            }
            currentBatteryLevel <= LOW_BATTERY_THRESHOLD -> {
                recommendations.add("Низкий заряд батареи. Частота обновлений снижена.")
                recommendations.add("Рассмотрите возможность подключения зарядного устройства.")
            }
            isPowerSaveMode -> {
                recommendations.add("Включен режим энергосбережения системы.")
                recommendations.add("Приложение адаптировано к системным настройкам.")
            }
            else -> {
                recommendations.add("Батарея в норме. Все функции доступны.")
            }
        }
        
        return recommendations
    }

    /**
     * Освобождает ресурсы.
     */
    fun cleanup() {
        stopBatteryMonitoring()
        scope.cancel()
    }
}
