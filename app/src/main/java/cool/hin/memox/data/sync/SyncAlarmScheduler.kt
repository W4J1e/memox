package cool.hin.memox.data.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.WorkManager
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import java.util.concurrent.TimeUnit

/**
 * Schedules a repeating auto-sync using AlarmManager instead of WorkManager's
 * PeriodicWorkRequest. WorkManager enforces a 15-minute minimum interval, which is far too
 * infrequent for note sync; AlarmManager lets us run every 5 minutes.
 *
 * The alarm simply triggers [SyncRouter.syncNow], which routes to the active provider.
 */
object SyncAlarmScheduler {

    const val ACTION_PERIODIC_SYNC = "cool.hin.memox.action.PERIODIC_SYNC"
    private val INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SyncAlarmReceiver::class.java).apply {
            action = ACTION_PERIODIC_SYNC
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 33) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    fun schedule(context: Context) {
        cancelLegacyPeriodicWorks(context)
        val prefs = MemoXPreferences.getInstance(context)
        val enabled = isSyncEnabled(prefs)
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context)
        am.cancel(pi)
        if (!enabled) return
        val triggerAt = System.currentTimeMillis() + INTERVAL_MS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            // Exact alarms not permitted (user revoked permission) -> fall back to inexact repeat
            am.setRepeating(AlarmManager.RTC_WAKEUP, triggerAt, INTERVAL_MS, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** Re-arm the next exact alarm after the current one fired (needed on API 31+). */
    fun rescheduleExact(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (!am.canScheduleExactAlarms()) return
        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + INTERVAL_MS,
            pendingIntent(context),
        )
    }

    fun cancel(context: Context) {
        cancelLegacyPeriodicWorks(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(context))
    }

    private fun isSyncEnabled(prefs: MemoXPreferences): Boolean {
        return when (prefs.syncProvider.value) {
            MemoXPreferences.PROVIDER_WEBDAV ->
                prefs.webdavSyncEnabled.value && prefs.webdavAutoSync.value
            MemoXPreferences.PROVIDER_ONEDRIVE ->
                prefs.onedriveSyncEnabled.value && prefs.onedriveAutoSync.value
            else -> false
        }
    }

    /** Cancel the old WorkManager periodic works so they don't also fire every 30 minutes. */
    private fun cancelLegacyPeriodicWorks(context: Context) {
        try {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork("webdav_sync_periodic")
            wm.cancelUniqueWork("onedrive_sync_periodic")
        } catch (_: Exception) {
            // WorkManager not yet initialised in some rare contexts
        }
    }
}
