package net.belllap.bugreport

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.belllap.BellLapApp
import java.util.concurrent.TimeUnit

/** Runs the queue when the network is up. WorkManager's own back-off re-runs it after [ProcessResult.RETRY_LATER]. */
class ReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BellLapApp
        return when (app.bugReports.queue.process()) {
            ProcessResult.DONE -> Result.success()
            ProcessResult.RETRY_LATER -> Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "bugreport-upload"
        private const val BACKOFF_SECONDS = 30L

        /**
         * One unique work. REPLACE is safe: the uploader persists after every step, so a cancelled run resumes
         * from `report.json` instead of starting over.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReportWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
