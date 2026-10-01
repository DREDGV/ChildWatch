package ru.example.parentwatch.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import ru.example.parentwatch.service.AppUsageTracker

/**
 * Collects device battery and hardware information for ParentWatch uploads.
 */
object DeviceInfoCollector {
    private const val APP_USAGE_CACHE_TTL_MS = 60 * 1000L
    private const val APP_USAGE_UPLOAD_TTL_MS = 2 * 60 * 1000L

    /** A failed collection retries after this short pause instead of on every upload. */
    private const val APP_USAGE_FAILURE_RETRY_MS = 15 * 1000L

    @Volatile
    private var cachedAppUsageJson: String? = null

    @Volatile
    private var cachedAppUsageAt: Long = 0L

    /** Last collection attempt, successful or not, so a failing device is not polled non-stop. */
    @Volatile
    private var lastAppUsageAttemptAt: Long = 0L

    /**
     * Last daily snapshot that actually carried activity. A later empty or partial
     * snapshot must never replace it: the server hands the parent only the newest
     * status, so losing the snapshot here looks like "there is no data at all".
     */
    @Volatile
    private var lastGoodDailyUsageJson: String? = null

    @Volatile
    private var lastGoodDailyUsageAt: Long = 0L

    @Volatile
    private var lastUsageSnapshotUploadAt: Long = 0L

    private var usageScope: String? = null
    private var lastAttemptJson: String? = null

    @Synchronized
    private fun ensureUsageScope(context: Context) {
        val resolver = ru.example.parentwatch.session.ChildEffectiveContextResolver(context)
        val identity = JSONArray().put(resolver.resolveServerUrl().trimEnd('/'))
            .put(resolver.resolveFamilyId()).put(resolver.resolveChildDeviceId()).toString()
        val scope = java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        if (scope == usageScope) return
        usageScope = scope
        cachedAppUsageJson = null; cachedAppUsageAt = 0L
        lastGoodDailyUsageJson = null; lastGoodDailyUsageAt = 0L
        lastAppUsageAttemptAt = 0L; lastUsageSnapshotUploadAt = 0L; lastAttemptJson = null
        val stored = context.getSharedPreferences("last_good_usage", Context.MODE_PRIVATE).getString(scope, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val daily = stored.optJSONObject("dailyUsage")?.takeIf(::isDailyUsageUsable) ?: return
        val at = stored.optLong("collectedAt", 0L)
        if (at <= 0L || at > System.currentTimeMillis() + 60_000L) return
        lastGoodDailyUsageJson = daily.toString(); lastGoodDailyUsageAt = at
        // Restore history only. A prior foreground app is not today's current app.
        cachedAppUsageJson = JSONObject().put("dailyUsage", daily).toString(); cachedAppUsageAt = at
    }

    private fun persistGoodUsage(context: Context, daily: JSONObject, at: Long) {
        val scope = usageScope ?: return
        context.getSharedPreferences("last_good_usage", Context.MODE_PRIVATE).edit()
            .putString(scope, JSONObject().put("dailyUsage", daily).put("collectedAt", at).toString()).apply()
    }

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
    @Synchronized
    fun getDeviceInfo(context: Context, includeCurrentApp: Boolean = true): JSONObject {
        ensureUsageScope(context)
        val usageGranted = AppUsageTracker(context).hasUsageStatsPermission()
        return JSONObject().apply {
            put("battery", getBatteryInfo(context))
            put("device", getDeviceDetails())
            put("camera", CameraDiagnostics.snapshot(context))
            put("locationReadiness", getLocationReadiness(context))
            put("usagePermissionGranted", usageGranted)
            // Every status must carry the last collected usage snapshot: the server
            // returns the newest status, so omitting it makes the activity list vanish.
            // Reuse the cached JSON between scheduled collections, without querying Android.
            if (includeCurrentApp || cachedAppUsageJson != null) {
                val appUsage = if (includeCurrentApp) getAppUsageInfo(context)
                    else JSONObject(cachedAppUsageJson ?: "{}")
                put("currentApp", if (usageGranted) appUsage.optJSONObject("currentApp") ?: JSONObject()
                    else JSONObject().put("error", "Permission not granted").put("permissionRequired", "PACKAGE_USAGE_STATS"))
                put("recentApps", if (usageGranted) appUsage.optJSONArray("recentApps") ?: JSONArray() else JSONArray())
                // The daily block is sent from the newest snapshot, but only when that
                // snapshot really has activity. An empty or partial snapshot is replaced
                // by the last good one, so the parent keeps a real list and an honest
                // "data for HH:mm" time instead of switching to "no data".
                val freshDaily = appUsage.optJSONObject("dailyUsage")
                    ?.takeIf { isDailyUsageUsable(it) }
                val lastGoodDaily = lastGoodDailyUsageJson
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.takeIf { isDailyUsageUsable(it) }
                val daily = freshDaily ?: lastGoodDaily
                if (daily != null) {
                    // A fresh snapshot and one still inside its short lifetime are both
                    // current; older data is repeated honestly with its own time.
                    val isFresh = usageGranted && freshDaily != null && lastGoodDailyUsageAt > 0L &&
                        System.currentTimeMillis() - lastGoodDailyUsageAt in 0 until APP_USAGE_CACHE_TTL_MS
                    put("dailyUsage", daily)
                    put(
                        "appUsageCollectedAt",
                        if (isFresh) lastGoodDailyUsageAt.takeIf { it > 0L } ?: cachedAppUsageAt
                        else lastGoodDailyUsageAt
                    )
                    put("appUsageStale", !isFresh)
                } else {
                    // Nothing good has ever been collected: say so honestly instead of
                    // sending an empty object, which reads as "no data" on the parent.
                    put("dailyUsage", JSONObject().apply {
                        put("available", false)
                        put("timeZone", java.util.TimeZone.getDefault().id)
                    })
                    put("appUsageCollectedAt", cachedAppUsageAt)
                    put("appUsageStale", false)
                }
                if (includeCurrentApp) lastUsageSnapshotUploadAt = System.currentTimeMillis()
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

    /** Current prerequisites only; permission does not prove a fresh GPS position. */
    private fun getLocationReadiness(context: Context): JSONObject = JSONObject().apply {
        put("collectedAt", System.currentTimeMillis())
        fun granted(permission: String) = androidx.core.content.ContextCompat.checkSelfPermission(
            context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        put("permissionGranted", granted(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(android.Manifest.permission.ACCESS_COARSE_LOCATION))
        put("precisePermissionGranted", granted(android.Manifest.permission.ACCESS_FINE_LOCATION))
        put("backgroundPermissionGranted", Build.VERSION.SDK_INT < 29 ||
            granted(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        runCatching {
            manager?.let {
                if (Build.VERSION.SDK_INT >= 28) it.isLocationEnabled
                else it.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                    it.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            }
        }.getOrNull()?.let { put("enabled", it) }
    }

    /**
     * A daily snapshot counts as good only when it covers a real interval and lists
     * apps with a positive foreground time. "Empty", "available = false", a missing
     * key or an all-filtered list are all failures and must not overwrite good data.
     */
    private fun isDailyUsageUsable(daily: JSONObject?): Boolean {
        if (daily == null || !daily.optBoolean("available", false)) return false
        if (daily.has("start") && daily.isNull("start")) return false
        if (daily.has("end") && daily.isNull("end")) return false
        val start = daily.optLong("start", 0L)
        val end = daily.optLong("end", 0L)
        if (daily.opt("start") !is Number || daily.opt("end") !is Number || start <= 0L || end <= start) return false
        val apps = daily.optJSONArray("apps") ?: return false
        if (apps.length() == 0) return false
        var total = 0L
        for (index in 0 until apps.length()) {
            total += apps.optJSONObject(index)?.optLong("totalTimeInForeground", 0L) ?: 0L
        }
        return total > 0L
    }

    /**
     * Get cached foreground + recent app information.
     */
    @Synchronized
    private fun getAppUsageInfo(context: Context): JSONObject {
        ensureUsageScope(context)
        val now = System.currentTimeMillis()
        val appUsageTracker = AppUsageTracker(context)
        val hasPermission = appUsageTracker.hasUsageStatsPermission()
        val currentDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        cachedAppUsageJson?.takeIf { hasPermission && cachedAppUsageAt >= currentDay && now - cachedAppUsageAt in 0 until APP_USAGE_CACHE_TTL_MS }?.let {
            return JSONObject(it)
        }
        if (hasPermission && now - lastAppUsageAttemptAt in 0 until APP_USAGE_FAILURE_RETRY_MS) {
            lastAttemptJson?.let { return JSONObject(it) }
        }

        val result = if (hasPermission) {
            val currentApp = appUsageTracker.getCurrentApp()
            val recentApps = appUsageTracker.getRecentApps(limit = 30)
            // A locked device or unavailable Android event store must not stop
            // battery/status uploads, or masquerade as zero minutes of activity.
            val daily = runCatching { appUsageTracker.getDailyUsage() }.getOrNull()
            JSONObject().apply {
                put("dailyUsage", JSONObject().apply {
                    put("available", daily?.available == true)
                    if (daily != null) {
                        put("start", daily.start)
                        put("end", daily.end)
                        put("timeZone", java.util.TimeZone.getDefault().id)
                        put("totalTime", daily.apps.sumOf { it.totalTimeInForeground })
                        put("apps", JSONArray().apply {
                            daily.apps.forEach { app -> put(JSONObject().apply {
                                put("packageName", app.packageName)
                                put("appName", app.appName)
                                put("lastUsed", app.lastTimeUsed)
                                app.firstTimeUsed?.let { put("firstUsed", it) }
                                app.lastForegroundAt?.let { put("lastForegroundAt", it) }
                                put("totalTimeInForeground", app.totalTimeInForeground)
                            }) }
                        })
                    }
                })
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
                put("recentApps", JSONArray().apply {
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
                put("recentApps", JSONArray())
            }
        }
        lastAppUsageAttemptAt = now

        val freshDaily = result.optJSONObject("dailyUsage")
        val goodDaily = freshDaily?.takeIf { isDailyUsageUsable(it) }
        if (goodDaily != null) {
            // A usable snapshot is by definition "data is here"; the parent reads this
            // flag, so it must not stay false on a snapshot we send as good data.
            goodDaily.put("available", true)
            lastGoodDailyUsageJson = goodDaily.toString()
            lastGoodDailyUsageAt = now
            persistGoodUsage(context, freshDaily, now)
            // Only a snapshot with real activity enters the cache. A failed one must not
            // replace it: the next upload has to retry, and the parent keeps receiving
            // the last good block with its own collection time.
            cachedAppUsageJson = result.toString()
            cachedAppUsageAt = now
        } else {
            // Inside this class an empty object means "this attempt failed"; getDeviceInfo()
            // never sends it, it substitutes the last good snapshot instead.
            result.put("dailyUsage", JSONObject())
        }
        lastAttemptJson = result.toString()
        return result
    }

    fun getRecentAppsInfo(context: Context): JSONArray {
        val appUsage = getAppUsageInfo(context)
        return appUsage.optJSONArray("recentApps") ?: JSONArray()
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
