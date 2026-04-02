package ru.example.parentwatch.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject
import ru.example.parentwatch.service.AppUsageTracker

/**
 * Collects device battery and hardware information for ParentWatch uploads.
 */
object DeviceInfoCollector {
    private const val APP_USAGE_CACHE_TTL_MS = 2 * 60 * 1000L
    private const val APP_USAGE_UPLOAD_TTL_MS = 5 * 60 * 1000L

    @Volatile
    private var cachedAppUsageJson: String? = null

    @Volatile
    private var cachedAppUsageAt: Long = 0L

    @Volatile
    private var lastUsageSnapshotUploadAt: Long = 0L

    private val cachedDeviceDetailsJson by lazy {
        JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("device", Build.DEVICE)
            put("brand", Build.BRAND)
        }.toString()
    }

    data class BatterySnapshot(
        val level: Int?,
        val isCharging: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Aggregate device status information as a JSON payload.
     */
    fun getDeviceInfo(context: Context, includeCurrentApp: Boolean = true): JSONObject {
        return JSONObject().apply {
            put("battery", getBatteryInfo(context))
            put("device", getDeviceDetails())
            if (includeCurrentApp) {
                val appUsage = getAppUsageInfo(context)
                put("currentApp", appUsage.optJSONObject("currentApp") ?: JSONObject())
                put("recentApps", appUsage.optJSONArray("recentApps") ?: org.json.JSONArray())
                lastUsageSnapshotUploadAt = System.currentTimeMillis()
            }
            put("timestamp", System.currentTimeMillis())
        }
    }

    /**
     * Child-side usage snapshots are heavier than battery/device info, so we only
     * attach them periodically instead of on every background upload.
     */
    fun shouldIncludeAppUsageSnapshot(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastUsageSnapshotUploadAt) >= APP_USAGE_UPLOAD_TTL_MS
    }

    /**
     * Get cached foreground + recent app information.
     */
    private fun getAppUsageInfo(context: Context): JSONObject {
        val now = System.currentTimeMillis()
        cachedAppUsageJson?.takeIf { (now - cachedAppUsageAt) < APP_USAGE_CACHE_TTL_MS }?.let {
            return JSONObject(it)
        }

        val appUsageTracker = AppUsageTracker(context)

        val result = if (appUsageTracker.hasUsageStatsPermission()) {
            val currentApp = appUsageTracker.getCurrentApp()
            val recentApps = appUsageTracker.getRecentApps(limit = 8)
            JSONObject().apply {
                put(
                    "currentApp",
                    if (currentApp != null) {
                        JSONObject().apply {
                            put("packageName", currentApp.packageName)
                            put("appName", currentApp.appName)
                            put("lastUsed", currentApp.lastTimeUsed)
                            put("isSystemApp", currentApp.isSystemApp)
                        }
                    } else {
                        JSONObject().apply {
                            put("error", "No app data available")
                        }
                    }
                )
                put("recentApps", org.json.JSONArray().apply {
                    recentApps.forEach { app ->
                        put(JSONObject().apply {
                            put("packageName", app.packageName)
                            put("appName", app.appName)
                            put("lastUsed", app.lastTimeUsed)
                            put("totalTimeInForeground", app.totalTimeInForeground)
                            put("isSystemApp", app.isSystemApp)
                        })
                    }
                })
            }
        } else {
            JSONObject().apply {
                put("currentApp", JSONObject().apply {
                    put("error", "Permission not granted")
                    put("permissionRequired", "PACKAGE_USAGE_STATS")
                })
                put("recentApps", org.json.JSONArray())
            }
        }
        cachedAppUsageJson = result.toString()
        cachedAppUsageAt = now
        return result
    }

    fun getRecentAppsInfo(context: Context): org.json.JSONArray {
        val appUsage = getAppUsageInfo(context)
        return appUsage.optJSONArray("recentApps") ?: org.json.JSONArray()
    }

    private fun getBatteryInfo(context: Context): JSONObject {
        val snapshot = getBatterySnapshot(context)
        val statusIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugType = statusIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val chargingType = when (plugType) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> null
        }

        val temperatureRaw = statusIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temperatureC = if (temperatureRaw > 0) temperatureRaw / 10.0 else null

        val voltageRaw = statusIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val voltageV = if (voltageRaw > 0) voltageRaw / 1000.0 else null

        val health = statusIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val healthLabel = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over-voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

        return JSONObject().apply {
            put("level", snapshot.level ?: JSONObject.NULL)
            put("isCharging", snapshot.isCharging)
            put("chargingType", chargingType ?: JSONObject.NULL)
            put("temperature", temperatureC ?: JSONObject.NULL)
            put("voltage", voltageV ?: JSONObject.NULL)
            put("health", healthLabel)
        }
    }

    fun getBatterySnapshot(context: Context): BatterySnapshot {
        val statusIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = statusIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = statusIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null

        val status = statusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        return BatterySnapshot(
            level = percent?.takeIf { it in 0..100 },
            isCharging = isCharging
        )
    }

    private fun getDeviceDetails(): JSONObject {
        return JSONObject(cachedDeviceDetailsJson)
    }

    fun getBatteryLevel(context: Context): Int {
        val snapshot = getBatterySnapshot(context)
        if (snapshot.level != null) {
            return snapshot.level
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val capacity = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (capacity in 0..100) {
                return capacity
            }
        }
        return fallbackBatteryLevel(context)
    }

    private fun fallbackBatteryLevel(context: Context): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }

    fun getBatteryStatus(context: Context): String {
        val snapshot = getBatterySnapshot(context)
        val level = snapshot.level ?: getBatteryLevel(context)
        val isCharging = snapshot.isCharging

        return when {
            isCharging && level >= 0 -> "Charging $level%"
            isCharging -> "Charging"
            level < 0 -> "Battery unknown"
            level > 80 -> "Battery high ($level%)"
            level > 50 -> "Battery medium ($level%)"
            level > 20 -> "Battery low ($level%)"
            else -> "Battery critical ($level%)"
        }
    }
}
