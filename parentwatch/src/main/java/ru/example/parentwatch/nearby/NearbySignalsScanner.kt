package ru.example.parentwatch.nearby

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.childwatch.shared.nearby.NearbySignalCollector
import ru.childwatch.shared.nearby.NearbySignalSource
import ru.childwatch.shared.nearby.NearbySignalsPolicy
import ru.childwatch.shared.nearby.NearbySignalsSnapshot
import ru.childwatch.shared.nearby.NearbySourceReport
import ru.childwatch.shared.nearby.NearbySourceState
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Foreground, voluntary local probe. No connections, pairing, radio enabling or background jobs. */
class NearbySignalsScanner(context: Context) {
    private val app = context.applicationContext

    companion object {
        fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
    private fun locationEnabled(): Boolean = try {
        val location = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (location == null) false
        else if (Build.VERSION.SDK_INT >= 28) location.isLocationEnabled
        else location.isProviderEnabled(LocationManager.GPS_PROVIDER) || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) { false }

    private fun foreground(): Boolean = ActivityManager.RunningAppProcessInfo().let {
        ActivityManager.getMyMemoryState(it)
        it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** Cancellation propagates after owned scans and listeners have been stopped. */
    @Suppress("DEPRECATION", "MissingPermission")
    suspend fun scan(optIn: Boolean, allowActiveWifi: Boolean = false): NearbySignalsSnapshot = withContext(Dispatchers.Main.immediate) {
        val started = SystemClock.elapsedRealtime()
        fun simple(state: NearbySourceState, remaining: Long = 0, reason: String? = null) = NearbySignalsSnapshot(
            System.currentTimeMillis(), started,
            NearbySignalSource.values().map { NearbySourceReport(it, state, reason = reason) }, remaining)
        if (!optIn) return@withContext simple(NearbySourceState.DISABLED)
        if (!foreground()) return@withContext simple(NearbySourceState.CANCELLED, reason = "FOREGROUND_REQUIRED")
        val claim = NearbyScanGate.claim(app, started)
        if (!claim.allowed) return@withContext simple(claim.state, claim.remainingMs, claim.reason)

        val alive = AtomicBoolean(true)
        val lock = Any()
        val collectors = NearbySignalSource.values().associateWith { NearbySignalCollector(it) }
        val reports = NearbySignalSource.values().associateWith { NearbySourceReport(it, NearbySourceState.UNSUPPORTED) }.toMutableMap()
        fun report(source: NearbySignalSource, state: NearbySourceState, reason: String? = null,
                   requested: Boolean = false, scanStarted: Boolean? = null) = synchronized(lock) {
            if (alive.get()) reports[source] = NearbySourceReport(source, state,
                activeScanRequested = requested, activeScanStarted = scanStarted, reason = reason)
        }
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val bluetoothManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        var bleScanner: BluetoothLeScanner? = null
        var bleStarted = false
        var classicStarted = false
        var registered = false
        var wifiRejected = false
        var wifiRequested = false
        var wifiStarted: Boolean? = null
        var screenOff = false
        var captured = started
        var capturedAt = System.currentTimeMillis()
        var frozenSignals: Map<NearbySignalSource, List<ru.childwatch.shared.nearby.NearbySignal>> = emptyMap()

        fun readWifi() {
            if (!alive.get() || wifi == null) return
            try {
                wifi.scanResults.take(500).forEach { result ->
                    collectors.getValue(NearbySignalSource.WIFI).add(result.BSSID.orEmpty(), result.SSID,
                        result.level, result.timestamp / 1_000)
                }
            } catch (_: SecurityException) {
                report(NearbySignalSource.WIFI, NearbySourceState.PERMISSION_REQUIRED, "WIFI_PERMISSION_REVOKED", wifiRequested, wifiStarted)
            } catch (_: Exception) {
                report(NearbySignalSource.WIFI, NearbySourceState.FAILED, "WIFI_RESULTS_FAILED", wifiRequested, wifiStarted)
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!alive.get() || intent == null) return
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> screenOff = true
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> classicStarted = false
                    WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) && locationEnabled()) {
                            if (!intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) && wifiRequested) wifiRejected = true
                            readWifi()
                        }
                    }
                    BluetoothDevice.ACTION_FOUND -> if (classicStarted) {
                        try {
                            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            if (device != null) collectors.getValue(NearbySignalSource.BLUETOOTH_CLASSIC).add(
                                device.address, device.name, intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt(), SystemClock.elapsedRealtime())
                        } catch (_: SecurityException) {
                            report(NearbySignalSource.BLUETOOTH_CLASSIC, NearbySourceState.PERMISSION_REQUIRED, "BLUETOOTH_PERMISSION_REVOKED")
                        } catch (_: Exception) {
                            report(NearbySignalSource.BLUETOOTH_CLASSIC, NearbySourceState.FAILED, "CLASSIC_RESULT_FAILED")
                        }
                    }
                }
            }
        }
        val bleCallback = object : ScanCallback() {
            private fun accept(result: ScanResult) {
                if (!alive.get()) return
                try {
                    collectors.getValue(NearbySignalSource.BLE).add(result.device.address,
                        result.scanRecord?.deviceName ?: result.device.name, result.rssi, result.timestampNanos / 1_000_000)
                } catch (_: SecurityException) {
                    report(NearbySignalSource.BLE, NearbySourceState.PERMISSION_REQUIRED, "BLUETOOTH_PERMISSION_REVOKED")
                } catch (_: Exception) {
                    report(NearbySignalSource.BLE, NearbySourceState.FAILED, "BLE_RESULT_FAILED")
                }
            }
            override fun onScanResult(callbackType: Int, result: ScanResult) = accept(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.take(500).forEach(::accept) }
            override fun onScanFailed(errorCode: Int) = report(NearbySignalSource.BLE, NearbySourceState.SCAN_NOT_STARTED, "BLE_START_FAILED_$errorCode", true, false)
        }

        try {
            val filter = IntentFilter().apply {
                addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            // Bluetooth system broadcasts can originate from a privileged non-system UID.
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, filter)
            registered = true

            val wifiRadioState = try {
                if (wifi?.isWifiEnabled == true) null else NearbySourceState.RADIO_OFF
            } catch (_: SecurityException) { NearbySourceState.PERMISSION_REQUIRED }
                catch (_: Exception) { NearbySourceState.FAILED }
            when {
                wifi == null -> report(NearbySignalSource.WIFI, NearbySourceState.UNSUPPORTED)
                !granted(Manifest.permission.ACCESS_FINE_LOCATION) || !granted(Manifest.permission.ACCESS_WIFI_STATE) -> report(NearbySignalSource.WIFI, NearbySourceState.PERMISSION_REQUIRED)
                !locationEnabled() -> report(NearbySignalSource.WIFI, NearbySourceState.LOCATION_OFF)
                wifiRadioState != null -> report(NearbySignalSource.WIFI, wifiRadioState)
                else -> {
                    report(NearbySignalSource.WIFI, NearbySourceState.SCANNING)
                    readWifi()
                    if (allowActiveWifi && foreground()) {
                        wifiRequested = true
                        if (!granted(Manifest.permission.CHANGE_WIFI_STATE)) {
                            report(NearbySignalSource.WIFI, NearbySourceState.PERMISSION_REQUIRED, "WIFI_ACTIVE_PERMISSION_REQUIRED", true, false)
                        } else try {
                            wifiStarted = wifi.startScan()
                            wifiRejected = wifiStarted == false
                        } catch (_: SecurityException) {
                            report(NearbySignalSource.WIFI, NearbySourceState.PERMISSION_REQUIRED, "WIFI_ACTIVE_PERMISSION_REVOKED", true, false)
                        } catch (_: Exception) {
                            report(NearbySignalSource.WIFI, NearbySourceState.FAILED, "WIFI_START_FAILED", true, false)
                        }
                    }
                }
            }

            // Full-beacon scanning intentionally has no neverForLocation assertion: fine location and
            // the location switch remain necessary on Android 12+ as well as older Android versions.
            val btPermission = granted(Manifest.permission.ACCESS_FINE_LOCATION) && if (Build.VERSION.SDK_INT >= 31)
                granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
                else granted(Manifest.permission.BLUETOOTH) && granted(Manifest.permission.BLUETOOTH_ADMIN)
            val btState = try { when {
                adapter == null -> NearbySourceState.UNSUPPORTED
                !btPermission -> NearbySourceState.PERMISSION_REQUIRED
                !locationEnabled() -> NearbySourceState.LOCATION_OFF
                !adapter.isEnabled -> NearbySourceState.RADIO_OFF
                else -> null
            } } catch (_: SecurityException) { NearbySourceState.PERMISSION_REQUIRED }
                catch (_: Exception) { NearbySourceState.FAILED }
            if (btState != null) {
                report(NearbySignalSource.BLUETOOTH_CLASSIC, btState)
                report(NearbySignalSource.BLE, btState)
            } else if (adapter != null) {
                try {
                    val connected: Boolean? = try {
                        val profiles = mutableListOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
                        if (Build.VERSION.SDK_INT >= 28) profiles.add(BluetoothProfile.HEARING_AID)
                        if (Build.VERSION.SDK_INT >= 33) profiles.add(BluetoothProfile.LE_AUDIO)
                        profiles.any { adapter.getProfileConnectionState(it) != BluetoothProfile.STATE_DISCONNECTED } ||
                            (bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)?.isNotEmpty() ?: true)
                    } catch (denied: SecurityException) { throw denied }
                        catch (_: Exception) { null } // A vendor's unsupported query is not proof that Bluetooth is idle.
                    when {
                        adapter.isDiscovering -> report(NearbySignalSource.BLUETOOTH_CLASSIC, NearbySourceState.SCAN_NOT_STARTED, "DISCOVERY_ALREADY_RUNNING")
                        connected == true -> report(NearbySignalSource.BLUETOOTH_CLASSIC, NearbySourceState.SCAN_NOT_STARTED, "BLUETOOTH_CONNECTION_IN_USE")
                        connected == null -> report(NearbySignalSource.BLUETOOTH_CLASSIC, NearbySourceState.SCAN_NOT_STARTED, "BLUETOOTH_CONNECTION_STATE_UNKNOWN")
                        else -> {
                            classicStarted = adapter.startDiscovery()
                            report(NearbySignalSource.BLUETOOTH_CLASSIC,
                                if (classicStarted) NearbySourceState.SCANNING else NearbySourceState.SCAN_NOT_STARTED,
                                if (classicStarted) null else "CLASSIC_START_REJECTED", true, classicStarted)
                        }
                    }
                } catch (_: SecurityException) {
                    report(NearbySignalSource.BLUETOOTH_CLASSIC, NearbySourceState.PERMISSION_REQUIRED, "BLUETOOTH_PERMISSION_REVOKED")
                } catch (_: Exception) {
                    report(NearbySignalSource.BLUETOOTH_CLASSIC, NearbySourceState.FAILED, "CLASSIC_START_FAILED")
                }
                try {
                    bleScanner = adapter.bluetoothLeScanner
                    if (bleScanner == null || !app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                        report(NearbySignalSource.BLE, NearbySourceState.UNSUPPORTED)
                    } else {
                        report(NearbySignalSource.BLE, NearbySourceState.SCANNING, requested = true, scanStarted = true)
                        bleStarted = true // stop even if a vendor implementation throws after partially starting
                        bleScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).setReportDelay(0).build(), bleCallback)
                    }
                } catch (_: SecurityException) {
                    report(NearbySignalSource.BLE, NearbySourceState.PERMISSION_REQUIRED, "BLUETOOTH_PERMISSION_REVOKED")
                } catch (_: Exception) {
                    report(NearbySignalSource.BLE, NearbySourceState.FAILED, "BLE_START_FAILED")
                }
            }
            coroutineContext.ensureActive()
            // Includes setup time: Classic and BLE share one bounded window, never two sequential 15s windows.
            if (classicStarted || bleStarted || wifiStarted == true)
                delay((NearbySignalsPolicy.SCAN_WINDOW_MS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0))
            coroutineContext.ensureActive()
            // Permissions/radios can change during the window; do not report an empty environment for that case.
            if (wifi != null) {
                val unavailable = try { when {
                    !granted(Manifest.permission.ACCESS_FINE_LOCATION) || !granted(Manifest.permission.ACCESS_WIFI_STATE) -> NearbySourceState.PERMISSION_REQUIRED
                    !locationEnabled() -> NearbySourceState.LOCATION_OFF
                    !wifi.isWifiEnabled -> NearbySourceState.RADIO_OFF
                    else -> null
                } } catch (_: SecurityException) { NearbySourceState.PERMISSION_REQUIRED }
                    catch (_: Exception) { NearbySourceState.FAILED }
                if (unavailable != null) report(NearbySignalSource.WIFI, unavailable,
                    "WIFI_UNAVAILABLE_AT_COMPLETION", wifiRequested, wifiStarted)
            }
            if (adapter != null) {
                val unavailable = try { when {
                    Build.VERSION.SDK_INT >= 31 && (!granted(Manifest.permission.BLUETOOTH_SCAN) || !granted(Manifest.permission.BLUETOOTH_CONNECT)) -> NearbySourceState.PERMISSION_REQUIRED
                    !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> NearbySourceState.PERMISSION_REQUIRED
                    !locationEnabled() -> NearbySourceState.LOCATION_OFF
                    !adapter.isEnabled -> NearbySourceState.RADIO_OFF
                    else -> null
                } } catch (_: SecurityException) { NearbySourceState.PERMISSION_REQUIRED }
                    catch (_: Exception) { NearbySourceState.FAILED }
                if (unavailable != null) {
                    report(NearbySignalSource.BLUETOOTH_CLASSIC, unavailable, "BLUETOOTH_UNAVAILABLE_AT_COMPLETION")
                    report(NearbySignalSource.BLE, unavailable, "BLUETOOTH_UNAVAILABLE_AT_COMPLETION")
                }
            }
            alive.set(false)
            captured = SystemClock.elapsedRealtime()
            capturedAt = System.currentTimeMillis()
            frozenSignals = collectors.mapValues { (_, collector) -> collector.freeze(captured, started) }
        } finally {
            alive.set(false)
            if (bleStarted) try { bleScanner?.stopScan(bleCallback) } catch (_: Exception) { /* no raw vendor error logging */ }
            if (classicStarted) try { adapter?.cancelDiscovery() } catch (_: Exception) { /* do not cancel unowned discovery */ }
            if (registered) try { app.unregisterReceiver(receiver) } catch (_: Exception) { }
            collectors.values.forEach { it.discard() }
            NearbyScanGate.release()
        }
        val finalReports = synchronized(lock) { NearbySignalSource.values().map { source ->
            val old = reports.getValue(source)
            val signals = frozenSignals.getValue(source)
            val state = if (old.state == NearbySourceState.SCANNING)
                if (source == NearbySignalSource.WIFI && wifiRejected) NearbySourceState.SCAN_NOT_STARTED else NearbySignalsPolicy.state(signals)
                else old.state
            old.copy(state = state, signals = signals,
                activeScanRequested = if (source == NearbySignalSource.WIFI) wifiRequested else old.activeScanRequested,
                activeScanStarted = if (source == NearbySignalSource.WIFI) wifiStarted else old.activeScanStarted,
                reason = if (source == NearbySignalSource.WIFI && state == NearbySourceState.SCAN_NOT_STARTED) "WIFI_SCAN_NOT_STARTED" else old.reason)
        } }
        NearbySignalsSnapshot(capturedAt, captured, Collections.unmodifiableList(finalReports),
            NearbySignalsPolicy.cooldownRemaining(captured, started, true), screenOff)
    }
}

/** The gate survives Activity re-entry and process restart; it stores no radio observations. */
private object NearbyScanGate {
    data class Claim(val allowed: Boolean, val state: NearbySourceState, val remainingMs: Long = 0, val reason: String? = null)
    private var busy = false
    private val processStarted = SystemClock.elapsedRealtime()
    private var lastLocalElapsed = -1L

    @Synchronized
    fun claim(context: Context, now: Long): Claim {
        val preferences = context.getSharedPreferences("nearby_probe_cooldown", Context.MODE_PRIVATE)
        val boot = try { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) } catch (_: Exception) { -1 }
        val previous = preferences.getLong("elapsed", -1)
        val previousBoot = preferences.getInt("boot", -1)
        val persisted = if (boot >= 0) NearbySignalsPolicy.cooldownRemaining(now, previous, previousBoot == boot)
            else if (previous >= 0 && lastLocalElapsed < 0) (NearbySignalsPolicy.COOLDOWN_MS - (now - processStarted)).coerceAtLeast(0)
            else 0 // Unknown boot: conservatively wait once after process recreation, never trust wall-clock changes.
        val remaining = maxOf(persisted, NearbySignalsPolicy.cooldownRemaining(now, lastLocalElapsed, true))
        if (busy || remaining > 0) return Claim(false, NearbySourceState.COOLDOWN, maxOf(remaining, if (busy) 1 else 0))
        if (!preferences.edit().putLong("elapsed", now).putInt("boot", boot).commit())
            return Claim(false, NearbySourceState.FAILED, reason = "COOLDOWN_STORAGE_FAILED")
        busy = true
        lastLocalElapsed = now
        return Claim(true, NearbySourceState.SCANNING)
    }
    @Synchronized fun release() { busy = false }
}
