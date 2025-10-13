package ru.example.parentwatch.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject

/**
 * Collects device battery and hardware information for ParentWatch uploads.
 */
object DeviceInfoCollector {

    /**
     * Aggregate device status information as a JSON payload.
     */
    fun getDeviceInfo(context: Context): JSONObject {
        return JSONObject().apply {
            put("battery", getBatteryInfo(context))
            put("device", getDeviceDetails())
            put("timestamp", System.currentTimeMillis())
        }
    }

    private fun getBatteryInfo(context: Context): JSONObject {
        val statusIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = statusIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = statusIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1

        val status = statusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

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
            put("level", if (percent >= 0) percent else JSONObject.NULL)
            put("isCharging", isCharging)
            put("chargingType", chargingType ?: JSONObject.NULL)
            put("temperature", temperatureC ?: JSONObject.NULL)
            put("voltage", voltageV ?: JSONObject.NULL)
            put("health", healthLabel)
        }
    }

    private fun getDeviceDetails(): JSONObject {
        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("device", Build.DEVICE)
            put("brand", Build.BRAND)
        }
    }

    fun getBatteryLevel(context: Context): Int {
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
        val level = getBatteryLevel(context)
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

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
