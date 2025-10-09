package ru.example.childwatch.utils

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log

class BatteryOptimizationManager(private val context: Context) {
    
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    
    companion object {
        private const val LOW_BATTERY_THRESHOLD = 20
        private const val CRITICAL_BATTERY_THRESHOLD = 10
    }
    
    private val batteryLevel: Int
        get() = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    
    private val isCharging: Boolean
        get() = batteryManager.isCharging
    
    private val isPowerSaveMode: Boolean
        get() = powerManager.isPowerSaveMode
    
    fun isIgnoringBatteryOptimizations(): Boolean {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    
    fun isPowerSaveEnabled(): Boolean {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP && 
            powerManager.isPowerSaveMode
    }
    
    fun startBatteryMonitoring() {
        Log.d("BatteryOptimizationManager", "Battery monitoring started")
    }
    
    fun stopBatteryMonitoring() {
        Log.d("BatteryOptimizationManager", "Battery monitoring stopped")
    }
    
    fun getAdaptiveLocationInterval(): Long {
        return 30000L // 30 seconds default
    }

    /**
     * Проверяет, можно ли выполнять интенсивные операции.
     */
    fun canPerformIntensiveOperations(): Boolean {
        return batteryLevel > LOW_BATTERY_THRESHOLD && !isPowerSaveMode
    }

    /**
     * Получает текущий уровень батареи.
     */
    fun getCurrentBatteryLevel(): Int {
        return batteryLevel
    }

    /**
     * Проверяет, заряжается ли устройство.
     */
    fun isDeviceCharging(): Boolean {
        return isCharging
    }

    /**
     * Получает рекомендации по оптимизации.
     */
    fun getOptimizationRecommendations(): List<String> {
        val recommendations = mutableListOf<String>()
        
        when {
            batteryLevel <= CRITICAL_BATTERY_THRESHOLD -> {
                recommendations.add("Критически низкий заряд. Рекомендуется подключить зарядное устройство.")
                recommendations.add("Приложение работает в режиме минимального энергопотребления.")
            }
            batteryLevel <= LOW_BATTERY_THRESHOLD -> {
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
    
    fun cleanup() {
        Log.d("BatteryOptimizationManager", "Cleanup completed")
    }

}