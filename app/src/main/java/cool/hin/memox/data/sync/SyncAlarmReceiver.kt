package cool.hin.memox.data.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Receives the periodic-sync alarm and BOOT_COMPLETED, then triggers an immediate (debounced)
 * sync via [SyncRouter]. For exact alarms (API 31+) it re-arms the next firing.
 */
class SyncAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> SyncAlarmScheduler.schedule(context)
            SyncAlarmScheduler.ACTION_PERIODIC_SYNC -> {
                SyncRouter.syncNow(context)
                SyncAlarmScheduler.rescheduleExact(context)
            }
        }
    }
}
