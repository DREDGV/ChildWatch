package ru.example.parentwatch.service

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.util.*

/**
 * AppUsageTracker - tracks running and recent apps on child device
 * Uses UsageStatsManager to get accurate app usage information
 */
class AppUsageTracker(private val context: Context) {

    companion object {
        private const val TAG = "AppUsageTracker"
        private const val TIME_WINDOW_MS = 60000L // 1 minute
    }

    private val usageStatsManager: UsageStatsManager? by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    }

    private val packageManager: PackageManager by lazy {
        context.packageManager
    }

    /**
     * Data class for app usage information
     */
    data class AppUsageInfo(
        val packageName: String,
        val appName: String,
        val lastTimeUsed: Long,
        val totalTimeInForeground: Long,
        val isSystemApp: Boolean
    )

    /**
     * Check if usage stats permission is granted
     */
    fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.e(TAG, "Error checking usage stats permission", e)
            false
        }
    }

    /**
     * Open settings to request usage stats permission
     */
    fun requestUsageStatsPermission() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening usage stats settings", e)
        }
    }

    /**
     * Get currently active (foreground) app
     */
    fun getCurrentApp(): AppUsageInfo? {
        if (!hasUsageStatsPermission()) {
            Log.w(TAG, "Usage stats permission not granted")
            return null
        }

        return try {
            val endTime = System.currentTimeMillis()
            val startTime = endTime - TIME_WINDOW_MS

            val usageStatsList = usageStatsManager?.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            if (usageStatsList.isNullOrEmpty()) {
                Log.w(TAG, "No usage stats found")
                return null
            }

            // Get most recently used app
            val recentApp = usageStatsList
                .filter { it.lastTimeUsed > 0 }
                .maxByOrNull { it.lastTimeUsed }

            recentApp?.let { stats ->
                createAppUsageInfo(stats)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current app", e)
            null
        }
    }

    /**
     * Get list of recent apps (last N apps)
     */
    fun getRecentApps(limit: Int = 10): List<AppUsageInfo> {
        if (!hasUsageStatsPermission()) {
            Log.w(TAG, "Usage stats permission not granted")
            return emptyList()
        }

        return try {
            val endTime = System.currentTimeMillis()
            val startTime = endTime - (60 * 60 * 1000L) // Last 1 hour

            val usageStatsList = usageStatsManager?.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            if (usageStatsList.isNullOrEmpty()) {
                return emptyList()
            }

            usageStatsList
                .filter { it.lastTimeUsed > 0 && it.totalTimeInForeground > 0 }
                .sortedByDescending { it.lastTimeUsed }
                .take(limit)
                .mapNotNull { stats ->
                    createAppUsageInfo(stats)
                }
                .filter { !it.isSystemApp } // Filter out system apps
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recent apps", e)
            emptyList()
        }
    }

    /**
     * Create AppUsageInfo from UsageStats
     */
    private fun createAppUsageInfo(stats: UsageStats): AppUsageInfo? {
        return try {
            val appInfo = packageManager.getApplicationInfo(stats.packageName, 0)
            val appName = packageManager.getApplicationLabel(appInfo).toString()
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            AppUsageInfo(
                packageName = stats.packageName,
                appName = appName,
                lastTimeUsed = stats.lastTimeUsed,
                totalTimeInForeground = stats.totalTimeInForeground,
                isSystemApp = isSystemApp
            )
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package not found: ${stats.packageName}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error creating app info for ${stats.packageName}", e)
            null
        }
    }

    /**
     * Get running services (alternative method, less accurate)
     */
    @Suppress("DEPRECATION")
    fun getRunningServices(): List<String> {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val services = activityManager.getRunningServices(100)
            services.map { it.service.packageName }.distinct()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting running services", e)
            emptyList()
        }
    }

    /**
     * Format time duration for display
     */
    fun formatDuration(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}ч ${minutes % 60}м"
            minutes > 0 -> "${minutes}м"
            else -> "${seconds}с"
        }
    }

    /**
     * Format relative time (e.g., "2 minutes ago")
     */
    fun formatRelativeTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "$days дн. назад"
            hours > 0 -> "$hours ч. назад"
            minutes > 0 -> "$minutes мин. назад"
            seconds > 0 -> "$seconds сек. назад"
            else -> "только что"
        }
    }
}
