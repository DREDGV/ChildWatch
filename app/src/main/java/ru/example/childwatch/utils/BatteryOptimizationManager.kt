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
        return if (isPowerSaveEnabled()) 30000L else 10000L // 30s vs 10s
    }
    
    fun cleanup() {
        Log.d("BatteryOptimizationManager", "Cleanup completed")
    }
}