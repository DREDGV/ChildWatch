package ru.example.childwatch.remote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.profile.ParentEffectiveContextResolver

/** Reports only this handset. No target-child ID, GPS fix, camera or usage collection. */
object ParentDeviceStatusReporter {
    private val lock = Mutex()
    private var lastScope: List<String?>? = null
    private var lastSuccessAt = 0L

    suspend fun report(context: Context) {
        if (!lock.tryLock()) return
        try {
            val app = context.applicationContext
            val resolver = ParentEffectiveContextResolver(app)
            val scope = listOf(resolver.resolveServerUrl().trimEnd('/'), resolver.resolveFamilyId(), resolver.resolveOwnParentId())
            if (scope.any { it.isNullOrBlank() }) return
            if (scope == lastScope && SystemClock.elapsedRealtime() - lastSuccessAt in 0L..59_999L) return
            val now = System.currentTimeMillis()
            fun granted(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
            val readiness = JSONObject().apply {
                put("collectedAt", now)
                put("permissionGranted", granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION))
                put("precisePermissionGranted", granted(Manifest.permission.ACCESS_FINE_LOCATION))
                put("backgroundPermissionGranted", Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                put("sharingEnabled", app.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE).getBoolean("share_parent_location", true))
                runCatching {
                    val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    manager?.let { if (Build.VERSION.SDK_INT >= 28) it.isLocationEnabled
                        else it.isProviderEnabled(LocationManager.GPS_PROVIDER) || it.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
                }.getOrNull()?.let { put("enabled", it) }
            }
            val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val payload = JSONObject().apply {
                put("timestamp", now)
                put("locationReadiness", readiness)
                put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                    .put("androidVersion", Build.VERSION.RELEASE).put("sdkVersion", Build.VERSION.SDK_INT))
                put("battery", JSONObject().apply {
                    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    if (level >= 0 && scale > 0) put("level", (level * 100 / scale).coerceIn(0, 100))
                    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    if (status != null && status != -1) put("isCharging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
                })
            }
            if (NetworkClient(app).uploadOwnDeviceStatus(payload, scope)) {
                lastScope = scope
                lastSuccessAt = SystemClock.elapsedRealtime()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { android.util.Log.w("ParentStatus", "Could not report own handset status", error) }
        finally { lock.unlock() }
    }
}
