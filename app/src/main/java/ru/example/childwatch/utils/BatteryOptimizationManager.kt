package ru.example.childwatch.utils

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import ru.example.childwatch.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * РњРµРЅРµРґР¶РµСЂ РѕРїС‚РёРјРёР·Р°С†РёРё Р±Р°С‚Р°СЂРµРё.
 * РђРґР°РїС‚РёРІРЅРѕ СѓРїСЂР°РІР»СЏРµС‚ С‡Р°СЃС‚РѕС‚РѕР№ РѕР±РЅРѕРІР»РµРЅРёР№ Рё РёРЅС‚РµРЅСЃРёРІРЅРѕСЃС‚СЊСЋ СЂР°Р±РѕС‚С‹ РІ Р·Р°РІРёСЃРёРјРѕСЃС‚Рё РѕС‚ СѓСЂРѕРІРЅСЏ Р·Р°СЂСЏРґР°.
 */
class BatteryOptimizationManager(private val context: Context) {

    companion object {
        private const val TAG = "BatteryOptimizationManager"
        private const val BATTERY_NOTIFICATION_ID = 950
        private const val BATTERY_CHECK_INTERVAL = 60_000L // 1 РјРёРЅСѓС‚Р°
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

    // РђРґР°РїС‚РёРІРЅС‹Рµ РёРЅС‚РµСЂРІР°Р»С‹ РІ Р·Р°РІРёСЃРёРјРѕСЃС‚Рё РѕС‚ СѓСЂРѕРІРЅСЏ Р±Р°С‚Р°СЂРµРё
    private var adaptiveLocationInterval = 5 * 60 * 1000L // 5 РјРёРЅСѓС‚ РїРѕ СѓРјРѕР»С‡Р°РЅРёСЋ
    private var adaptiveAudioDuration = 30 * 1000L // 30 СЃРµРєСѓРЅРґ РїРѕ СѓРјРѕР»С‡Р°РЅРёСЋ
    private var adaptivePhotoInterval = 10 * 60 * 1000L // 10 РјРёРЅСѓС‚ РїРѕ СѓРјРѕР»С‡Р°РЅРёСЋ

    /**
     * Р—Р°РїСѓСЃРєР°РµС‚ РјРѕРЅРёС‚РѕСЂРёРЅРі Р±Р°С‚Р°СЂРµРё.
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
     * РћСЃС‚Р°РЅР°РІР»РёРІР°РµС‚ РјРѕРЅРёС‚РѕСЂРёРЅРі Р±Р°С‚Р°СЂРµРё.
     */
    fun stopBatteryMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            Log.d(TAG, "Stopping battery monitoring")
            scope.cancel()
        }
    }

    /**
     * РћР±РЅРѕРІР»СЏРµС‚ СЃС‚Р°С‚СѓСЃ Р±Р°С‚Р°СЂРµРё.
     */
    private fun updateBatteryStatus() {
        try {
            // РџРѕР»СѓС‡Р°РµРј СѓСЂРѕРІРµРЅСЊ Р·Р°СЂСЏРґР°
            currentBatteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            
            // РџСЂРѕРІРµСЂСЏРµРј, Р·Р°СЂСЏР¶Р°РµС‚СЃСЏ Р»Рё СѓСЃС‚СЂРѕР№СЃС‚РІРѕ
            val batteryStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            isCharging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                    batteryStatus == BatteryManager.BATTERY_STATUS_FULL
            
            // РџСЂРѕРІРµСЂСЏРµРј СЂРµР¶РёРј СЌРЅРµСЂРіРѕСЃР±РµСЂРµР¶РµРЅРёСЏ
            isPowerSaveMode = powerManager.isPowerSaveMode
            
            Log.d(TAG, "Battery status: $currentBatteryLevel%, charging: $isCharging, power save: $isPowerSaveMode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update battery status", e)
        }
    }

    /**
     * РђРґР°РїС‚РёРІРЅРѕ РЅР°СЃС‚СЂР°РёРІР°РµС‚ РїР°СЂР°РјРµС‚СЂС‹ РїСЂРѕРёР·РІРѕРґРёС‚РµР»СЊРЅРѕСЃС‚Рё.
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
     * Р РµР¶РёРј РєСЂРёС‚РёС‡РµСЃРєРё РЅРёР·РєРѕРіРѕ Р·Р°СЂСЏРґР° Р±Р°С‚Р°СЂРµРё.
     */
    private fun setCriticalBatteryMode() {
        Log.w(TAG, "Critical battery mode activated")

        adaptiveLocationInterval = 30 * 60 * 1000L
        adaptiveAudioDuration = 10 * 1000L
        adaptivePhotoInterval = 60 * 60 * 1000L

        disableNonEssentialFeatures()

        if (lastNotificationMode != BatteryMode.CRITICAL) {
            notifyBatteryStatus(context.getString(R.string.battery_status_critical), "CRITICAL")
            lastNotificationMode = BatteryMode.CRITICAL
        }
    }

    /**
     * Р РµР¶РёРј РЅРёР·РєРѕРіРѕ Р·Р°СЂСЏРґР° Р±Р°С‚Р°СЂРµРё.
     */
    private fun setLowBatteryMode() {
        Log.w(TAG, "Low battery mode activated")

        adaptiveLocationInterval = 15 * 60 * 1000L
        adaptiveAudioDuration = 20 * 1000L
        adaptivePhotoInterval = 30 * 60 * 1000L

        limitNonEssentialFeatures()

        if (lastNotificationMode != BatteryMode.LOW) {
            notifyBatteryStatus(context.getString(R.string.battery_status_low), "WARNING")
            lastNotificationMode = BatteryMode.LOW
        }
    }

    /**
     * Р РµР¶РёРј РІС‹СЃРѕРєРѕРіРѕ Р·Р°СЂСЏРґР° Р±Р°С‚Р°СЂРµРё (РїСЂРё Р·Р°СЂСЏРґРєРµ).
     */
    private fun setHighBatteryMode() {
        Log.d(TAG, "High battery mode activated")
        
        // РџРѕРІС‹С€РµРЅРЅР°СЏ Р°РєС‚РёРІРЅРѕСЃС‚СЊ
        adaptiveLocationInterval = 2 * 60 * 1000L // 2 РјРёРЅСѓС‚С‹
        adaptiveAudioDuration = 60 * 1000L // 60 СЃРµРєСѓРЅРґ
        adaptivePhotoInterval = 5 * 60 * 1000L // 5 РјРёРЅСѓС‚
        
        // Р’РєР»СЋС‡Р°РµРј РІСЃРµ С„СѓРЅРєС†РёРё
        enableAllFeatures()
        lastNotificationMode = BatteryMode.NORMAL
    }

    /**
     * Р РµР¶РёРј СЌРЅРµСЂРіРѕСЃР±РµСЂРµР¶РµРЅРёСЏ СЃРёСЃС‚РµРјС‹.
     */
    private fun setPowerSaveMode() {
        Log.w(TAG, "Power save mode activated")

        adaptiveLocationInterval = 20 * 60 * 1000L
        adaptiveAudioDuration = 15 * 1000L
        adaptivePhotoInterval = 45 * 60 * 1000L

        limitBackgroundActivity()

        if (lastNotificationMode != BatteryMode.POWER_SAVE) {
            notifyBatteryStatus(context.getString(R.string.power_save_warning), "WARNING")
            lastNotificationMode = BatteryMode.POWER_SAVE
        }
    }

    /**
     * РћР±С‹С‡РЅС‹Р№ СЂРµР¶РёРј СЂР°Р±РѕС‚С‹.
     */
    private fun setNormalMode() {
        Log.d(TAG, "Normal mode activated")

        adaptiveLocationInterval = 5 * 60 * 1000L
        adaptiveAudioDuration = 30 * 1000L
        adaptivePhotoInterval = 10 * 60 * 1000L

        enableAllFeatures()
        lastNotificationMode = BatteryMode.NORMAL
    }

    /**
     * РћС‚РєР»СЋС‡Р°РµС‚ РЅРµСЃСѓС‰РµСЃС‚РІРµРЅРЅС‹Рµ С„СѓРЅРєС†РёРё.
     */
    private fun disableNonEssentialFeatures() {
        // Р—РґРµСЃСЊ РјРѕР¶РЅРѕ РґРѕР±Р°РІРёС‚СЊ Р»РѕРіРёРєСѓ РѕС‚РєР»СЋС‡РµРЅРёСЏ РЅРµСЃСѓС‰РµСЃС‚РІРµРЅРЅС‹С… С„СѓРЅРєС†РёР№
        Log.d(TAG, "Non-essential features disabled")
    }

    /**
     * РћРіСЂР°РЅРёС‡РёРІР°РµС‚ РЅРµСЃСѓС‰РµСЃС‚РІРµРЅРЅС‹Рµ С„СѓРЅРєС†РёРё.
     */
    private fun limitNonEssentialFeatures() {
        // Р—РґРµСЃСЊ РјРѕР¶РЅРѕ РґРѕР±Р°РІРёС‚СЊ Р»РѕРіРёРєСѓ РѕРіСЂР°РЅРёС‡РµРЅРёСЏ С„СѓРЅРєС†РёР№
        Log.d(TAG, "Non-essential features limited")
    }

    /**
     * Р’РєР»СЋС‡Р°РµС‚ РІСЃРµ С„СѓРЅРєС†РёРё.
     */
    private fun enableAllFeatures() {
        // Р—РґРµСЃСЊ РјРѕР¶РЅРѕ РґРѕР±Р°РІРёС‚СЊ Р»РѕРіРёРєСѓ РІРєР»СЋС‡РµРЅРёСЏ РІСЃРµС… С„СѓРЅРєС†РёР№
        Log.d(TAG, "All features enabled")
    }

    /**
     * РћРіСЂР°РЅРёС‡РёРІР°РµС‚ С„РѕРЅРѕРІСѓСЋ Р°РєС‚РёРІРЅРѕСЃС‚СЊ.
     */
    private fun limitBackgroundActivity() {
        // Р—РґРµСЃСЊ РјРѕР¶РЅРѕ РґРѕР±Р°РІРёС‚СЊ Р»РѕРіРёРєСѓ РѕРіСЂР°РЅРёС‡РµРЅРёСЏ С„РѕРЅРѕРІРѕР№ Р°РєС‚РёРІРЅРѕСЃС‚Рё
        Log.d(TAG, "Background activity limited")
    }

    /**
     * РЈРІРµРґРѕРјР»СЏРµС‚ Рѕ СЃС‚Р°С‚СѓСЃРµ Р±Р°С‚Р°СЂРµРё.
     */
    private fun notifyBatteryStatus(message: String, severity: String) {
        Log.i(TAG, "Battery notification: $message")
        AlertNotifier.show(
            context,
            context.getString(R.string.battery_alert_title),
            message
        )
        CriticalEventReporter.report(
            context = context,
            eventType = "BATTERY",
            severity = severity,
            message = message
        )
    }

    /**
     * РџРѕР»СѓС‡Р°РµС‚ Р°РґР°РїС‚РёРІРЅС‹Р№ РёРЅС‚РµСЂРІР°Р» РґР»СЏ РіРµРѕР»РѕРєР°С†РёРё.
     */
    fun getAdaptiveLocationInterval(): Long {
        return adaptiveLocationInterval
    }

    /**
     * РџРѕР»СѓС‡Р°РµС‚ Р°РґР°РїС‚РёРІРЅСѓСЋ РґР»РёС‚РµР»СЊРЅРѕСЃС‚СЊ Р°СѓРґРёРѕР·Р°РїРёСЃРё.
     */
    fun getAdaptiveAudioDuration(): Long {
        return adaptiveAudioDuration
    }

    /**
     * РџРѕР»СѓС‡Р°РµС‚ Р°РґР°РїС‚РёРІРЅС‹Р№ РёРЅС‚РµСЂРІР°Р» РґР»СЏ С„РѕС‚РѕРіСЂР°С„РёР№.
     */
    fun getAdaptivePhotoInterval(): Long {
        return adaptivePhotoInterval
    }

    /**
     * РџСЂРѕРІРµСЂСЏРµС‚, РЅСѓР¶РЅРѕ Р»Рё РѕРіСЂР°РЅРёС‡РёС‚СЊ Р°РєС‚РёРІРЅРѕСЃС‚СЊ.
     */
    fun shouldLimitActivity(): Boolean {
        return currentBatteryLevel <= LOW_BATTERY_THRESHOLD || isPowerSaveMode
    }

    /**
     * РџСЂРѕРІРµСЂСЏРµС‚, РјРѕР¶РЅРѕ Р»Рё РІС‹РїРѕР»РЅСЏС‚СЊ РёРЅС‚РµРЅСЃРёРІРЅС‹Рµ РѕРїРµСЂР°С†РёРё.
     */
    fun canPerformIntensiveOperations(): Boolean {
        return currentBatteryLevel >= HIGH_BATTERY_THRESHOLD && isCharging
    }

    /**
     * РџРѕР»СѓС‡Р°РµС‚ С‚РµРєСѓС‰РёР№ СѓСЂРѕРІРµРЅСЊ Р±Р°С‚Р°СЂРµРё.
     */
    fun getCurrentBatteryLevel(): Int {
        return currentBatteryLevel
    }

    /**
     * РџСЂРѕРІРµСЂСЏРµС‚, Р·Р°СЂСЏР¶Р°РµС‚СЃСЏ Р»Рё СѓСЃС‚СЂРѕР№СЃС‚РІРѕ.
     */
    fun isDeviceCharging(): Boolean {
        return isCharging
    }

    /**
     * РџСЂРѕРІРµСЂСЏРµС‚, РІРєР»СЋС‡РµРЅ Р»Рё СЂРµР¶РёРј СЌРЅРµСЂРіРѕСЃР±РµСЂРµР¶РµРЅРёСЏ.
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    fun isPowerSaveModeEnabled(): Boolean {
        return isPowerSaveMode
    }

    /**
     * РџРѕР»СѓС‡Р°РµС‚ СЂРµРєРѕРјРµРЅРґР°С†РёРё РїРѕ РѕРїС‚РёРјРёР·Р°С†РёРё.
     */
    fun getOptimizationRecommendations(): List<String> {
        val recommendations = mutableListOf<String>()
        
        when {
            currentBatteryLevel <= CRITICAL_BATTERY_THRESHOLD -> {
                recommendations.add("РљСЂРёС‚РёС‡РµСЃРєРё РЅРёР·РєРёР№ Р·Р°СЂСЏРґ. Р РµРєРѕРјРµРЅРґСѓРµС‚СЃСЏ РїРѕРґРєР»СЋС‡РёС‚СЊ Р·Р°СЂСЏРґРЅРѕРµ СѓСЃС‚СЂРѕР№СЃС‚РІРѕ.")
                recommendations.add("РџСЂРёР»РѕР¶РµРЅРёРµ СЂР°Р±РѕС‚Р°РµС‚ РІ СЂРµР¶РёРјРµ РјРёРЅРёРјР°Р»СЊРЅРѕРіРѕ СЌРЅРµСЂРіРѕРїРѕС‚СЂРµР±Р»РµРЅРёСЏ.")
            }
            currentBatteryLevel <= LOW_BATTERY_THRESHOLD -> {
                recommendations.add("РќРёР·РєРёР№ Р·Р°СЂСЏРґ Р±Р°С‚Р°СЂРµРё. Р§Р°СЃС‚РѕС‚Р° РѕР±РЅРѕРІР»РµРЅРёР№ СЃРЅРёР¶РµРЅР°.")
                recommendations.add("Р Р°СЃСЃРјРѕС‚СЂРёС‚Рµ РІРѕР·РјРѕР¶РЅРѕСЃС‚СЊ РїРѕРґРєР»СЋС‡РµРЅРёСЏ Р·Р°СЂСЏРґРЅРѕРіРѕ СѓСЃС‚СЂРѕР№СЃС‚РІР°.")
            }
            isPowerSaveMode -> {
                recommendations.add("Р’РєР»СЋС‡РµРЅ СЂРµР¶РёРј СЌРЅРµСЂРіРѕСЃР±РµСЂРµР¶РµРЅРёСЏ СЃРёСЃС‚РµРјС‹.")
                recommendations.add("РџСЂРёР»РѕР¶РµРЅРёРµ Р°РґР°РїС‚РёСЂРѕРІР°РЅРѕ Рє СЃРёСЃС‚РµРјРЅС‹Рј РЅР°СЃС‚СЂРѕР№РєР°Рј.")
            }
            else -> {
                recommendations.add("Р‘Р°С‚Р°СЂРµСЏ РІ РЅРѕСЂРјРµ. Р’СЃРµ С„СѓРЅРєС†РёРё РґРѕСЃС‚СѓРїРЅС‹.")
            }
        }
        
        return recommendations
    }

    /**
     * РћСЃРІРѕР±РѕР¶РґР°РµС‚ СЂРµСЃСѓСЂСЃС‹.
     */
    fun cleanup() {
        stopBatteryMonitoring()
        scope.cancel()
    }
}
