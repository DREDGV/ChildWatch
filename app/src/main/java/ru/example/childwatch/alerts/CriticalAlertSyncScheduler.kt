package ru.example.childwatch.alerts

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object CriticalAlertSyncScheduler {

    private const val PERIODIC_WORK_NAME = "critical_alert_sync"

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<CriticalAlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )
    }

    fun triggerImmediate(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeRequest = OneTimeWorkRequestBuilder<CriticalAlertWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueue(oneTimeRequest)
    }
}
