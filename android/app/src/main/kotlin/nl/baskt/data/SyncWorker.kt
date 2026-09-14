package nl.baskt.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.baskt.BasktApp

/** WorkManager persists the outbox wake-up across process death and device restarts. */
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main.immediate) {
        val container = (applicationContext as BasktApp).container
        container.awaitSettings()
        container.basket.tryReconnect()
        if (container.basket.pending.value.isEmpty()) Result.success() else Result.retry()
    }
    companion object {
        fun schedule(context: Context) {
            val work = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("baskt-outbox", ExistingWorkPolicy.KEEP, work)
        }
    }
}
