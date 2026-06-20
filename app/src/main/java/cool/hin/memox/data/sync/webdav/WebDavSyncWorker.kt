package cool.hin.memox.data.sync.webdav

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

class WebDavSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val appContext = applicationContext as ContextWrapper
        val preferences = MemoXPreferences.getInstance(appContext)
        if (!preferences.webdavSyncEnabled.value || !preferences.webdavAutoSync.value) {
            return Result.success()
        }

        val syncService = WebDavSyncService(appContext)
        return when (val result = syncService.upload()) {
            is SyncResult.Success -> {
                Log.i(TAG, "WebDAV auto sync succeeded")
                Result.success()
            }
            is SyncResult.Error -> {
                Log.w(TAG, "WebDAV auto sync failed: ${result.message}")
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "WebDavSyncWorker"
        private const val WORK_NAME_PERIODIC = "webdav_sync_periodic"
        private const val WORK_NAME_IMMEDIATE = "webdav_sync_immediate"

        /** Schedule periodic auto-sync (every 1 hour) */
        fun schedule(context: ContextWrapper) {
            val preferences = MemoXPreferences.getInstance(context)
            if (preferences.webdavSyncEnabled.value && preferences.webdavAutoSync.value) {
                val request =
                    PeriodicWorkRequest.Builder(WebDavSyncWorker::class.java, 1, TimeUnit.HOURS)
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

        /** Trigger an immediate sync after note modification (with 30s debounce) */
        fun syncNow(context: Context) {
            val preferences = MemoXPreferences.getInstance(context)
            if (!preferences.webdavSyncEnabled.value || !preferences.webdavAutoSync.value) return

            val request = OneTimeWorkRequest.Builder(WebDavSyncWorker::class.java)
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
