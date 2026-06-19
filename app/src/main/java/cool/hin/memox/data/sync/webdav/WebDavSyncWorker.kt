package cool.hin.memox.data.sync.webdav

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
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
        return when (val result = syncService.sync()) {
            is SyncResult.Success,
            is SyncResult.DownloadReady,
            -> Result.success()
            is SyncResult.Error -> {
                Log.w(TAG, "WebDAV auto sync failed: ${result.message}")
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "WebDavSyncWorker"
        private const val WORK_NAME = "webdav_sync"

        fun schedule(context: ContextWrapper) {
            val preferences = MemoXPreferences.getInstance(context)
            if (preferences.webdavSyncEnabled.value && preferences.webdavAutoSync.value) {
                val request =
                    PeriodicWorkRequest.Builder(WebDavSyncWorker::class.java, 1, TimeUnit.HOURS)
                        .build()
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            }
        }

        fun cancel(context: ContextWrapper) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
