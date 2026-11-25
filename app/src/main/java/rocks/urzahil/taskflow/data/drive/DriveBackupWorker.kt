package rocks.urzahil.taskflow.data.drive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import rocks.urzahil.taskflow.data.db.AppDatabase
import rocks.urzahil.taskflow.data.repository.TaskRepository

/**
 * Uploads a pending Drive backup when the app is no longer running, so changes made just before
 * the user closes the app still reach Drive without waiting for the next launch. If the app
 * already uploaded, the durable pending flag is clear and this does nothing.
 */
class DriveBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = TaskRepository(AppDatabase.getInstance(applicationContext).taskDao())
        val manager = GoogleDriveBackupManager(applicationContext, repository)

        if (!manager.isBackupPendingDurable()) return Result.success()
        if (!manager.isAutoBackupEnabled()) {
            manager.setBackupPendingDurable(false)
            return Result.success()
        }
        // Not connected, or waiting for the user to resolve a conflict: keep the flag for later.
        if (!manager.isDriveConnected() || manager.isSyncConflictPending()) return Result.success()

        val result = manager.backupSnapshot(repository.getBackupSnapshot())
        return when {
            result.isSuccess -> {
                manager.setBackupPendingDurable(false)
                Result.success()
            }
            // Access was removed: retrying can't help until the user reconnects in the app.
            result.exceptionOrNull() is DriveAccessLostException -> Result.failure()
            else -> Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "drive_pending_backup"

        /** Schedules (or reschedules) one upload attempt once the device is online. */
        fun schedule(context: Context, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
