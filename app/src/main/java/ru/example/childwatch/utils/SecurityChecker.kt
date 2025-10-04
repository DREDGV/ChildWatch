package ru.example.childwatch.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.provider.Settings
import android.util.Log

/**
 * Security utility for detecting debug flags and potential security issues
 * 
 * Features:
 * - Detect debug builds
 * - Detect developer options
 * - Detect USB debugging
 * - Detect root detection
 * - Detect emulator environment
 * - Detect debugging tools
 */
object SecurityChecker {
    
    private const val TAG = "SecurityChecker"
    
    /**
     * Check if the app is running in debug mode
     */
    fun isDebugBuild(context: Context): Boolean {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName, 
                PackageManager.GET_META_DATA
            )
            (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            Log.e(TAG, "Error checking debug build", e)
            false
        }
    }
    
    /**
     * Check if developer options are enabled
     */
    fun isDeveloperOptionsEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
            ) != 0
        } catch (e: Exception) {
            Log.e(TAG, "Error checking developer options", e)
            false
        }
    }
    
    /**
     * Check if USB debugging is enabled
     */
    fun isUsbDebuggingEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED, 0
            ) != 0
        } catch (e: Exception) {
            Log.e(TAG, "Error checking USB debugging", e)
            false
        }
    }
    
    /**
     * Check if the device is rooted
     */
    fun isDeviceRooted(): Boolean {
        return try {
            // Check for common root files
            val rootFiles = arrayOf(
                "/system/app/Superuser.apk",
                "/sbin/su",
                "/system/bin/su",
                "/system/xbin/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/su",
                "/su/bin/su"
            )
            
            rootFiles.any { file ->
                try {
                    java.io.File(file).exists()
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking root", e)
            false
        }
    }
    
    /**
     * Check if running on emulator
     */
    fun isEmulator(): Boolean {
        return try {
            Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu") ||
            Build.PRODUCT.contains("sdk") ||
            Build.PRODUCT.contains("google_sdk") ||
            Build.PRODUCT.contains("sdk_gphone") ||
            Build.PRODUCT.contains("vbox86p") ||
            Build.BOARD.contains("goldfish") ||
            Build.BOARD.contains("ranchu") ||
            Build.BOARD.contains("sdk") ||
            Build.BOARD.contains("vbox86") ||
            Build.DEVICE.contains("generic") ||
            Build.DEVICE.contains("goldfish") ||
            Build.DEVICE.contains("ranchu") ||
            Build.DEVICE.contains("vbox86") ||
            Build.DEVICE.contains("emulator") ||
            Build.DEVICE.contains("simulator")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking emulator", e)
            false
        }
    }
    
    /**
     * Check if debugger is attached
     */
    fun isDebuggerAttached(): Boolean {
        return try {
            Debug.isDebuggerConnected()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking debugger", e)
            false
        }
    }
    
    /**
     * Check if app is debuggable
     */
    fun isAppDebuggable(context: Context): Boolean {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName, 
                PackageManager.GET_META_DATA
            )
            (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            Log.e(TAG, "Error checking app debuggable", e)
            false
        }
    }
    
    /**
     * Check if mock locations are enabled
     */
    fun isMockLocationEnabled(context: Context): Boolean {
        return try {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ALLOW_MOCK_LOCATION, 0
            ) != 0
        } catch (e: Exception) {
            Log.e(TAG, "Error checking mock location", e)
            false
        }
    }
    
    /**
     * Get comprehensive security report
     */
    fun getSecurityReport(context: Context): SecurityReport {
        return SecurityReport(
            isDebugBuild = isDebugBuild(context),
            isDeveloperOptionsEnabled = isDeveloperOptionsEnabled(context),
            isUsbDebuggingEnabled = isUsbDebuggingEnabled(context),
            isDeviceRooted = isDeviceRooted(),
            isEmulator = isEmulator(),
            isDebuggerAttached = isDebuggerAttached(),
            isAppDebuggable = isAppDebuggable(context),
            isMockLocationEnabled = isMockLocationEnabled(context),
            timestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * Check if security is compromised
     */
    fun isSecurityCompromised(context: Context): Boolean {
        val report = getSecurityReport(context)
        return report.isDebugBuild ||
               report.isDeveloperOptionsEnabled ||
               report.isUsbDebuggingEnabled ||
               report.isDeviceRooted ||
               report.isEmulator ||
               report.isDebuggerAttached ||
               report.isAppDebuggable ||
               report.isMockLocationEnabled
    }
    
    /**
     * Get security warnings
     */
    fun getSecurityWarnings(context: Context): List<String> {
        val warnings = mutableListOf<String>()
        val report = getSecurityReport(context)
        
        if (report.isDebugBuild) {
            warnings.add("Приложение собрано в debug режиме")
        }
        if (report.isDeveloperOptionsEnabled) {
            warnings.add("Включены опции разработчика")
        }
        if (report.isUsbDebuggingEnabled) {
            warnings.add("Включена отладка по USB")
        }
        if (report.isDeviceRooted) {
            warnings.add("Устройство имеет root права")
        }
        if (report.isEmulator) {
            warnings.add("Приложение запущено в эмуляторе")
        }
        if (report.isDebuggerAttached) {
            warnings.add("Подключен отладчик")
        }
        if (report.isAppDebuggable) {
            warnings.add("Приложение доступно для отладки")
        }
        if (report.isMockLocationEnabled) {
            warnings.add("Включены mock-локации")
        }
        
        return warnings
    }
}

/**
 * Data class for security report
 */
data class SecurityReport(
    val isDebugBuild: Boolean,
    val isDeveloperOptionsEnabled: Boolean,
    val isUsbDebuggingEnabled: Boolean,
    val isDeviceRooted: Boolean,
    val isEmulator: Boolean,
    val isDebuggerAttached: Boolean,
    val isAppDebuggable: Boolean,
    val isMockLocationEnabled: Boolean,
    val timestamp: Long
) {
    val securityScore: Int
        get() {
            var score = 100
            if (isDebugBuild) score -= 20
            if (isDeveloperOptionsEnabled) score -= 15
            if (isUsbDebuggingEnabled) score -= 15
            if (isDeviceRooted) score -= 25
            if (isEmulator) score -= 10
            if (isDebuggerAttached) score -= 20
            if (isAppDebuggable) score -= 20
            if (isMockLocationEnabled) score -= 10
            return maxOf(0, score)
        }
    
    val securityLevel: String
        get() = when {
            securityScore >= 90 -> "Высокий"
            securityScore >= 70 -> "Средний"
            securityScore >= 50 -> "Низкий"
            else -> "Критический"
        }
}
