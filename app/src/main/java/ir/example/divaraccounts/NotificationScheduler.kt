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
    private const val LEGACY_PERIODIC_NAME = "divar_unread_hourly"
    private const val PERIODIC_NAME = "divar_unread_periodic"
    private const val STARTUP_NAME = "divar_unread_startup"

    // No battery constraint: a message notification that waits for a charged battery is not reliable.
    private fun periodicConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    // A one-off catch-up check should run as soon as connectivity returns, even if the battery is low.
    private fun catchUpConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensureScheduled(context: Context) {
        // 15 minutes is the shortest period Android allows for background work.
        val periodic = PeriodicWorkRequestBuilder<UnreadPollingWorker>(15, TimeUnit.MINUTES)
            .setConstraints(periodicConstraints())
            .build()
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_PERIODIC_NAME)
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