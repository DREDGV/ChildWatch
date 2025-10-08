package ru.example.childwatch.utils

import android.content.Context
import android.os.PowerManager
import android.util.Log

class BatteryOptimizationManager(private val context: Context) {
    
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    
    fun isIgnoringBatteryOptimizations(): Boolean {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
<<<<<<< Current (Your changes)
    
    fun isPowerSaveEnabled(): Boolean {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP && 
            powerManager.isPowerSaveMode
    }
    
    fun startBatteryMonitoring() {
        Log.d("BatteryOptimizationManager", "Battery monitoring started")
=======

    /**
     * Проверяет, можно ли выполнять интенсивные операции.
     */
    fun canPerformIntensiveOperations(): Boolean {
        return currentBatteryLevel > LOW_BATTERY_THRESHOLD && !isPowerSaveMode
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
>>>>>>> Incoming (Background Agent changes)
    }
    
    fun stopBatteryMonitoring() {
        Log.d("BatteryOptimizationManager", "Battery monitoring stopped")
    }
    
    fun getAdaptiveLocationInterval(): Long {
        return if (isPowerSaveEnabled()) 30000L else 10000L // 30s vs 10s
    }
    
    fun cleanup() {
        Log.d("BatteryOptimizationManager", "Cleanup completed")
    }

    /**
     * Проверяет, игнорирует ли приложение оптимизацию батареи.
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    /**
     * Проверяет, включен ли режим энергосбережения.
     */
    fun isPowerSaveModeEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            powerManager.isPowerSaveMode
        } else {
            false
        }
    }
}