package ru.example.parentwatch.diagnostics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Этап D: Centralized metrics collection and management
 * Collects metrics from various sources and exposes via StateFlow
 */
class MetricsManager(private val context: Context) {

    companion object {
        private const val TAG = "METRICS"
        private const val MAX_ERROR_HISTORY = 20
        private const val MAX_LOG_HISTORY = 100
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Main metrics state
    private val _metrics = MutableStateFlow(AudioStreamMetrics())
    val metrics: StateFlow<AudioStreamMetrics> = _metrics.asStateFlow()

    // Error history
    private val errorHistory = ConcurrentLinkedQueue<ErrorInfo>()

    // Log history for export
    private val logHistory = ConcurrentLinkedQueue<String>()

    // Connection start time
    private var connectionStartTime: Long = 0

    init {
        startPeriodicUpdates()
    }

    /**
     * Start periodic updates of system metrics (battery, network)
     */
    private fun startPeriodicUpdates() {
        scope.launch {
            while (isActive) {
                updateSystemMetrics()
                delay(2000) // Update every 2 seconds
            }
        }
    }

    /**
     * Update metrics (thread-safe)
     */
    fun update(updater: (AudioStreamMetrics) -> AudioStreamMetrics) {
        _metrics.update(updater)
    }

    /**
     * Update WebSocket status
     */
    fun updateWsStatus(
        status: WsStatus,
        retryAttempt: Int = 0,
        maxRetries: Int = 5
    ) {
        if (status == WsStatus.CONNECTED && connectionStartTime == 0L) {
            connectionStartTime = System.currentTimeMillis()
        } else if (status == WsStatus.DISCONNECTED) {
            connectionStartTime = 0
        }

        update { it.copy(
            wsStatus = status,
            wsRetryAttempt = retryAttempt,
            wsMaxRetries = maxRetries,
            connectionDuration = if (connectionStartTime > 0) {
                System.currentTimeMillis() - connectionStartTime
            } else 0
        )}
    }

    /**
     * Update audio status
     */
    fun updateAudioStatus(status: AudioStatus) {
        update { it.copy(audioStatus = status) }
    }

    /**
     * Update data rate (bytes per second)
     */
    fun updateDataRate(bytesPerSecond: Long, framesTotal: Long = _metrics.value.framesTotal) {
        update { it.copy(
            bytesPerSecond = bytesPerSecond,
            framesTotal = framesTotal
        )}
    }

    /**
     * Update queue metrics (receiver only)
     */
    fun updateQueue(depth: Int, capacity: Int = _metrics.value.queueCapacity) {
        update { it.copy(
            queueDepth = depth,
            queueCapacity = capacity
        )}
    }

    /**
     * Increment underrun counter
     */
    fun incrementUnderrun() {
        update { it.copy(underrunCount = it.underrunCount + 1) }
    }

    /**
     * Update ping (RTT)
     */
    fun updatePing(pingMs: Long) {
        val pingStatus = when {
            pingMs < 0 -> PingStatus.UNKNOWN
            pingMs < 50 -> PingStatus.EXCELLENT
            pingMs < 100 -> PingStatus.GOOD
            pingMs < 200 -> PingStatus.FAIR
            else -> PingStatus.POOR
        }

        update { it.copy(
            pingMs = pingMs,
            pingStatus = pingStatus
        )}
    }

    /**
     * Update active audio effects (sender only)
     */
    fun updateAudioEffects(
        effects: Set<AudioEffect>,
        audioSource: AudioSourceType = AudioSourceType.MIC
    ) {
        update { it.copy(
            activeEffects = effects,
            audioSource = audioSource
        )}
    }

    /**
     * Report an error
     */
    fun reportError(
        message: String,
        severity: ErrorSeverity = ErrorSeverity.ERROR,
        exception: Throwable? = null
    ) {
        val errorInfo = ErrorInfo(
            message = message,
            severity = severity,
            exception = exception?.javaClass?.simpleName
        )

        // Add to history
        errorHistory.offer(errorInfo)
        if (errorHistory.size > MAX_ERROR_HISTORY) {
            errorHistory.poll()
        }

        // Update current error
        update { it.copy(
            lastError = errorInfo,
            errorCount = it.errorCount + 1
        )}

        // Log
        when (severity) {
            ErrorSeverity.ERROR -> Log.e(TAG, message, exception)
            ErrorSeverity.WARNING -> Log.w(TAG, message, exception)
            ErrorSeverity.INFO -> Log.i(TAG, message)
        }
    }

    /**
     * Clear last error
     */
    fun clearError() {
        update { it.copy(lastError = null) }
    }

    /**
     * Reset all stats
     */
    fun resetStats() {
        connectionStartTime = 0
        errorHistory.clear()
        logHistory.clear()
        update { AudioStreamMetrics() }
        Log.i(TAG, "Stats reset")
    }

    /**
     * Update system metrics (battery, network)
     */
    private fun updateSystemMetrics() {
        val batteryInfo = getBatteryInfo()
        val networkInfo = getNetworkInfo()

        update { it.copy(
            batteryLevel = batteryInfo.first,
            batteryCharging = batteryInfo.second,
            networkType = networkInfo.first,
            networkName = networkInfo.second,
            signalStrength = networkInfo.third
        )}
    }

    /**
     * Get battery level and charging status
     */
    private fun getBatteryInfo(): Pair<Int, Boolean> {
        return try {
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

            val batteryPct = (level * 100) / scale
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL

            Pair(batteryPct, isCharging)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get battery info", e)
            Pair(0, false)
        }
    }

    /**
     * Get network type and name
     */
    private fun getNetworkInfo(): Triple<NetworkType, String, SignalStrength> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null) {
                return Triple(NetworkType.UNKNOWN, "", SignalStrength.UNKNOWN)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork
                val capabilities = cm.getNetworkCapabilities(network)

                when {
                    capabilities == null -> Triple(NetworkType.NONE, "", SignalStrength.UNKNOWN)
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                        val wifiName = getWifiNetworkName()
                        Triple(NetworkType.WIFI, wifiName, SignalStrength.GOOD)
                    }
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                        Triple(NetworkType.MOBILE, "Mobile Data", SignalStrength.FAIR)
                    }
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                        Triple(NetworkType.ETHERNET, "Ethernet", SignalStrength.EXCELLENT)
                    }
                    else -> Triple(NetworkType.UNKNOWN, "", SignalStrength.UNKNOWN)
                }
            } else {
                @Suppress("DEPRECATION")
                val activeNetwork = cm.activeNetworkInfo
                when (activeNetwork?.type) {
                    ConnectivityManager.TYPE_WIFI -> {
                        val wifiName = getWifiNetworkName()
                        Triple(NetworkType.WIFI, wifiName, SignalStrength.GOOD)
                    }
                    ConnectivityManager.TYPE_MOBILE -> {
                        Triple(NetworkType.MOBILE, "Mobile Data", SignalStrength.FAIR)
                    }
                    else -> Triple(NetworkType.UNKNOWN, "", SignalStrength.UNKNOWN)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get network info", e)
            Triple(NetworkType.UNKNOWN, "", SignalStrength.UNKNOWN)
        }
    }

    /**
     * Get WiFi network name (SSID)
     */
    private fun getWifiNetworkName(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "Wi-Fi" // Android 10+ requires location permission for SSID
            } else {
                @Suppress("DEPRECATION")
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
                    as? android.net.wifi.WifiManager
                val wifiInfo = wifiManager?.connectionInfo
                wifiInfo?.ssid?.removeSurrounding("\"") ?: "Wi-Fi"
            }
        } catch (e: Exception) {
            "Wi-Fi"
        }
    }

    /**
     * Add log entry
     */
    fun addLog(tag: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date())
        val logEntry = "$timestamp $tag: $message"

        logHistory.offer(logEntry)
        if (logHistory.size > MAX_LOG_HISTORY) {
            logHistory.poll()
        }
    }

    /**
     * Export diagnostics as JSON
     */
    fun exportDiagnosticsJson(): String {
        val current = _metrics.value
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())

        return JSONObject().apply {
            put("timestamp", dateFormat.format(Date()))
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidVersion", Build.VERSION.SDK_INT)

            // Current metrics
            put("metrics", JSONObject().apply {
                put("wsStatus", current.wsStatus.name)
                put("audioStatus", current.audioStatus.name)
                put("bytesPerSecond", current.bytesPerSecond)
                put("dataRatePercent", current.dataRatePercent)
                put("framesTotal", current.framesTotal)
                put("queueDepth", current.queueDepth)
                put("queueCapacity", current.queueCapacity)
                put("bufferDurationMs", current.bufferDurationMs)
                put("underrunCount", current.underrunCount)
                put("pingMs", current.pingMs)
                put("networkType", current.networkType.name)
                put("networkName", current.networkName)
                put("batteryLevel", current.batteryLevel)
                put("batteryCharging", current.batteryCharging)
                put("activeEffects", JSONArray(current.activeEffects.map { it.name }))
                put("audioSource", current.audioSource.name)
                put("healthStatus", current.healthStatus.name)
            })

            // Error history
            put("errors", JSONArray().apply {
                errorHistory.forEach { error ->
                    put(JSONObject().apply {
                        put("timestamp", dateFormat.format(Date(error.timestamp)))
                        put("message", error.message)
                        put("severity", error.severity.name)
                        error.exception?.let { put("exception", it) }
                    })
                }
            })

            // Recent logs
            put("logs", JSONArray(logHistory.toList()))

        }.toString(2) // Pretty print with 2-space indent
    }

    /**
     * Cleanup
     */
    fun destroy() {
        scope.cancel()
    }
}
