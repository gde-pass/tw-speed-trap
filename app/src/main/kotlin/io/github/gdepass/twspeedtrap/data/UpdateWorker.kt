package io.github.gdepass.twspeedtrap.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Weekly database refresh; unmetered-only by default, no charging required. */
class UpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (DbUpdater(applicationContext).checkAndUpdate()) {
            is UpdateResult.Updated, UpdateResult.UpToDate -> Result.success()
            is UpdateResult.Failed -> Result.retry()
        }

    companion object {
        private const val WORK_NAME = "db-update"

        fun schedule(
            context: Context,
            autoUpdate: Boolean,
            wifiOnly: Boolean,
        ) {
            val workManager = WorkManager.getInstance(context)
            if (!autoUpdate) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
            val request =
                PeriodicWorkRequestBuilder<UpdateWorker>(7, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                    .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
