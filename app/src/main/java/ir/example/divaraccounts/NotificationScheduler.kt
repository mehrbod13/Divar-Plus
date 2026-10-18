package ir.example.divaraccounts

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object NotificationScheduler {
    private const val PERIODIC_NAME = "divar_unread_hourly"
    private const val STARTUP_NAME = "divar_unread_startup"

    private fun periodicConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    // A one-off catch-up check should run as soon as connectivity returns, even if the battery is low.
    private fun catchUpConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensureScheduled(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<UnreadPollingWorker>(1, TimeUnit.HOURS)
            .setConstraints(periodicConstraints())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
        requestImmediateCheck(context)
    }

    /** Runs after returning from an account so badges reflect messages read in the WebView. */
    fun requestImmediateCheck(context: Context) {
        val request = OneTimeWorkRequestBuilder<UnreadPollingWorker>()
            .setConstraints(catchUpConstraints())
            .setInitialDelay(8, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            STARTUP_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
