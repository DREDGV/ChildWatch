package ru.example.childwatch.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Handles battery optimization and power saver prompts for the app.
 */
class BatteryOptimizationHelper(private val activity: Activity) {

    private val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun isIgnoringBatteryOptimizations(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            powerManager.isIgnoringBatteryOptimizations(activity.packageName)
    }

    fun isPowerSaveEnabled(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && powerManager.isPowerSaveMode
    }

    fun requestDisableBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        startIntentSafely(intent) { openBatteryOptimizationSettings() }
    }

    fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        startIntentSafely(intent)
    }

    fun openPowerSaverSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        startIntentSafely(intent) {
            val fallback = Intent(Settings.ACTION_POWER_USAGE_SUMMARY)
            startIntentSafely(fallback)
        }
    }

    private fun startIntentSafely(intent: Intent, onError: (() -> Unit)? = null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (exception: ActivityNotFoundException) {
            Log.w(TAG, "Cannot handle intent ${intent.action}", exception)
            onError?.invoke()
        }
    }

    companion object {
        private const val TAG = "BatteryOptimizationHelper"
    }
}
