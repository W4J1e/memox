package cool.hin.memox.data.sync.webdav

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cool.hin.memox.data.sync.SyncAlarmScheduler
import cool.hin.memox.data.sync.SyncResult
import cool.hin.memox.data.sync.SyncStatus
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class WebDavSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val appContext = applicationContext as ContextWrapper
            val preferences = MemoXPreferences.getInstance(appContext)
            if (!preferences.webdavSyncEnabled.value || !preferences.webdavAutoSync.value) {
                SyncStatus.setIdle()
                return Result.success()
            }

            SyncStatus.setSyncing()
            val syncService = WebDavSyncService(appContext)
            when (val result = syncService.sync()) {
                is SyncResult.Success -> {
                    Log.i(TAG, "WebDAV auto sync succeeded")
                    SyncStatus.setCompleted()
                    Result.success()
                }
                is SyncResult.Error -> {
                    Log.w(TAG, "WebDAV auto sync failed: ${result.message}")
                    SyncStatus.setIdle()
                    Result.retry()
                }
            }
        } catch (e: CancellationException) {
            // 被新同步请求取消或系统取消时收敛状态，避免灵动岛永远停在"同步中"
            SyncStatus.setIdle()
            throw e
        }
    }

    companion object {
        private const val TAG = "WebDavSyncWorker"
        private const val WORK_NAME_PERIODIC = "webdav_sync_periodic"
        private const val WORK_NAME_IMMEDIATE = "webdav_sync_immediate"

        /** Schedule periodic auto-sync (every 5 minutes via AlarmManager). */
        fun schedule(context: ContextWrapper) {
            SyncAlarmScheduler.schedule(context)
        }

        /** Trigger an immediate sync after note modification (with 30s debounce) */
        fun syncNow(context: Context) {
            val preferences = MemoXPreferences.getInstance(context)
            if (!preferences.webdavSyncEnabled.value || !preferences.webdavAutoSync.value) return

            val request = OneTimeWorkRequest.Builder(WebDavSyncWorker::class.java)
                .setInitialDelay(5, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_IMMEDIATE, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: ContextWrapper) {
            SyncAlarmScheduler.cancel(context)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_IMMEDIATE)
        }
    }
}
