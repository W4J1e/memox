package cool.hin.memox.data.sync.onedrive

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cool.hin.memox.data.sync.SyncResult
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import java.util.concurrent.TimeUnit

class OneDriveSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val appContext = applicationContext as ContextWrapper
        val preferences = MemoXPreferences.getInstance(appContext)
        if (!preferences.onedriveSyncEnabled.value || !preferences.onedriveAutoSync.value) {
            return Result.success()
        }
        if (!OneDriveAuthHelper.isLoggedIn(appContext)) {
            return Result.success()
        }

        val syncService = OneDriveSyncService(appContext)
        return when (val result = syncService.sync()) {
            is SyncResult.Success -> {
                Log.i(TAG, "OneDrive auto sync succeeded")
                Result.success()
            }
            is SyncResult.Error -> {
                Log.w(TAG, "OneDrive auto sync failed: ${result.message}")
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "OneDriveSyncWorker"
        private const val WORK_NAME_PERIODIC = "onedrive_sync_periodic"
        private const val WORK_NAME_IMMEDIATE = "onedrive_sync_immediate"

        fun schedule(context: ContextWrapper) {
            val preferences = MemoXPreferences.getInstance(context)
            if (preferences.onedriveSyncEnabled.value && preferences.onedriveAutoSync.value) {
                val request =
                    PeriodicWorkRequest.Builder(OneDriveSyncWorker::class.java, 30, TimeUnit.MINUTES)
                        .build()
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                        WORK_NAME_PERIODIC,
                        ExistingPeriodicWorkPolicy.KEEP,
                        request,
                    )
            } else {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
            }
        }

        fun syncNow(context: Context) {
            val preferences = MemoXPreferences.getInstance(context)
            if (!preferences.onedriveSyncEnabled.value || !preferences.onedriveAutoSync.value) return

            val request = OneTimeWorkRequest.Builder(OneDriveSyncWorker::class.java)
                .setInitialDelay(5, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_IMMEDIATE, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: ContextWrapper) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_IMMEDIATE)
        }
    }
}
