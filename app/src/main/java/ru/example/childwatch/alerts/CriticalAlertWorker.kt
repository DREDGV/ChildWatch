package ru.example.childwatch.alerts

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.network.CriticalAlert
import ru.example.childwatch.utils.AlertNotifier
import ru.example.childwatch.utils.SecureSettingsManager

class CriticalAlertWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "CriticalAlertWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val secureSettings = SecureSettingsManager(applicationContext)
            val childDeviceId = secureSettings.getChildDeviceId()
            val deviceId = childDeviceId ?: secureSettings.getDeviceId()
            if (deviceId.isNullOrBlank()) {
                Log.w(TAG, "Skipping alert sync: deviceId missing")
                return@withContext Result.success()
            }

            val serverUrl = secureSettings.getServerUrl()
            val networkClient = NetworkClient(applicationContext)
            val alerts = networkClient.fetchCriticalAlerts(serverUrl, deviceId)

            if (alerts.isEmpty()) {
                return@withContext Result.success()
            }

            handleAlerts(alerts, networkClient, serverUrl, deviceId)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Critical alert sync failed", e)
            Result.retry()
        }
    }

    private suspend fun handleAlerts(
        alerts: List<CriticalAlert>,
        networkClient: NetworkClient,
        serverUrl: String,
        deviceId: String
    ) {
        val alertIds = mutableListOf<Long>()
        alerts.forEach { alert ->
            val title = when (alert.severity.uppercase()) {
                "CRITICAL" -> applicationContext.getString(ru.example.childwatch.R.string.critical_alert_title)
                else -> applicationContext.getString(ru.example.childwatch.R.string.battery_alert_title)
            }

            AlertNotifier.show(
                applicationContext,
                title,
                alert.message,
                notificationId = alert.id.toInt()
            )

            alertIds += alert.id
        }

        if (alertIds.isNotEmpty()) {
            val acknowledged = networkClient.acknowledgeCriticalAlerts(serverUrl, deviceId, alertIds)
            Log.d(TAG, "Acknowledged alerts: $acknowledged -> ids=$alertIds")
        }
    }
}
