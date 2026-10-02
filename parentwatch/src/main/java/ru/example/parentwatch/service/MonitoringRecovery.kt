package ru.example.parentwatch.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import ru.example.parentwatch.session.ChildEffectiveContextResolver
import java.util.concurrent.TimeUnit

/** Durable user intent, independent of a service instance or a failed start. */
object MonitoringRecovery {
    private const val DESIRED = "monitoring_desired"
    private const val PERIODIC = "child-monitoring-watchdog"
    private const val RETRY = "child-monitoring-recovery"

    fun isDesired(context: Context): Boolean {
        val prefs = context.getSharedPreferences("parentwatch_prefs", Context.MODE_PRIVATE)
        return MonitoringRecoveryPolicy.shouldRecover(
            if (prefs.contains(DESIRED)) prefs.getBoolean(DESIRED, false) else null,
            prefs.getBoolean("service_running", false),
            prefs.getBoolean("auto_start_on_boot", true)
        )
    }

    fun enable(context: Context) {
        context.getSharedPreferences("parentwatch_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(DESIRED, true).commit()
        ensureScheduled(context)
    }

    fun ensureScheduled(context: Context) {
        if (!isDesired(context)) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MonitoringRecoveryWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    fun disable(context: Context) {
        context.getSharedPreferences("parentwatch_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(DESIRED, false).commit()
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(RETRY)
        }
    }

    fun scheduleRetry(context: Context) {
        if (!isDesired(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            RETRY, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MonitoringRecoveryWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        )
    }
}

class MonitoringRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!MonitoringRecovery.isDesired(context)) return Result.success()
        val session = ChildEffectiveContextResolver(context).resolveEffectiveContext()
            ?: return Result.retry()
        if (session.serverUrl.isBlank() || session.ownChildDeviceId.isBlank()) return Result.retry()
        return try {
            // Commands/chat can recover even if the location permission is unavailable.
            if (!ChatBackgroundService.isRunning) {
                ChatBackgroundService.start(context, session.serverUrl, session.ownChildDeviceId)
            }
            if (!LocationService.isMonitoringActive) {
                val intent = Intent(context, LocationService::class.java).apply {
                    action = LocationService.ACTION_START
                    putExtra("server_url", session.serverUrl)
                    putExtra("device_id", session.ownChildDeviceId)
                }
                if (!LocationService.startTrackingService(context, intent, rememberIntent = false)) return Result.retry()
                // A start request is asynchronous; only the service can confirm promotion.
                return Result.retry()
            }
            if (!ChatBackgroundService.isRunning) {
                return Result.retry()
            }
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // WorkManager does not grant an exemption from Android FGS restrictions.
            Log.w("MonitoringRecovery", "Automatic start deferred by the platform", error)
            Result.retry()
        }
    }
}
