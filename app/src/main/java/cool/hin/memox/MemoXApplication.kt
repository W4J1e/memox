package cool.hin.memox

import android.app.Activity
import android.app.Application
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.color.DynamicColors
import cool.hin.memox.MemoXApplication.Companion.AUTO_REMOVE_DELETED_NOTES
import cool.hin.memox.MemoXApplication.Companion.TAG
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.presentation.setEnabledSecureFlag
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences.Companion.EMPTY_PATH
import cool.hin.memox.presentation.viewmodel.preference.Theme
import cool.hin.memox.presentation.widget.WidgetProvider
import cool.hin.memox.utils.AutoRemoveDeletedNotesWorker
import cool.hin.memox.utils.PinnedNotificationManager
import cool.hin.memox.utils.backup.AUTO_BACKUP_WORK_NAME
import cool.hin.memox.utils.backup.autoBackupOnSave
import cool.hin.memox.utils.backup.autoBackupOnSaveFileExists
import cool.hin.memox.utils.backup.cancelAutoBackup
import cool.hin.memox.utils.backup.containsNonCancelled
import cool.hin.memox.utils.backup.createBackup
import cool.hin.memox.utils.backup.deleteModifiedNoteBackup
import cool.hin.memox.utils.backup.isEqualTo
import cool.hin.memox.utils.backup.modifiedNoteBackupExists
import cool.hin.memox.utils.backup.scheduleAutoBackup
import cool.hin.memox.utils.backup.updateAutoBackup
import cool.hin.memox.utils.log
import cool.hin.memox.utils.observeOnce
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MemoXApplication : Application(), Application.ActivityLifecycleCallbacks {

    private lateinit var preferences: MemoXPreferences

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        if (isTestRunner()) return
        preferences = MemoXPreferences.getInstance(this)
        if (preferences.useDynamicColors.value) {
            if (DynamicColors.isDynamicColorAvailable()) {
                DynamicColors.applyToActivitiesIfAvailable(this)
            }
        } else {
            setTheme(R.style.AppTheme)
        }
        restorePinnedNotifications()
        preferences.theme.observeForeverWithPrevious { (oldTheme, theme) ->
            when (theme) {
                Theme.DARK,
                Theme.SUPER_DARK ->
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

                Theme.LIGHT ->
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

                Theme.FOLLOW_SYSTEM ->
                    AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    )
            }
            if (oldTheme != null) {
                WidgetProvider.updateWidgets(this)
            }
        }

        preferences.backupsFolder.observeForeverWithPrevious { (backupFolderBefore, backupFolder) ->
            checkUpdatePeriodicBackup(
                backupFolderBefore,
                backupFolder,
                preferences.periodicBackups.value.periodInDays.toLong(),
                execute = true,
            )
            checkUpdateAutoBackupOnSave(backupFolderBefore, backupFolder)
        }
        preferences.periodicBackups.observeForever { value ->
            val backupFolder = preferences.backupsFolder.value
            checkUpdatePeriodicBackup(backupFolder, backupFolder, value.periodInDays.toLong())
        }
        preferences.autoRemoveDeletedNotesAfterDays.observeForever { value ->
            checkUpdateAutoRemoveOldDeletedNotes(value)
        }

        preferences.backupPassword.observeForeverWithPrevious {
            (previousBackupPassword, backupPassword) ->
            if (preferences.backupOnSave.value) {
                val backupPath = preferences.backupsFolder.value
                if (backupPath != EMPTY_PATH) {
                    if (
                        !modifiedNoteBackupExists(backupPath) ||
                            (previousBackupPassword != null &&
                                previousBackupPassword != backupPassword)
                    ) {
                        deleteModifiedNoteBackup(backupPath)
                        runOnIODispatcher {
                            autoBackupOnSave(
                                backupPath,
                                savedNote = null,
                                password = backupPassword,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun folderChanged(folderBefore: String?, folderAfter: String): Boolean {
        if (folderBefore == null || folderAfter == EMPTY_PATH) {
            return false
        }
        return folderBefore != folderAfter
    }

    private fun restorePinnedNotifications() {
        runOnIODispatcher {
            MemoXDatabase.getDatabase(this@MemoXApplication, false)
                .value
                .getBaseNoteDao()
                .getAllPinnedToStatusNotes()
                .forEach { note ->
                    if (note.isPinnedToStatus) {
                        PinnedNotificationManager.notify(this@MemoXApplication, note)
                    }
                }
        }
    }

    private fun checkUpdatePeriodicBackup(
        backupFolderBefore: String?,
        backupFolder: String,
        periodInDays: Long,
        execute: Boolean = false,
    ) {
        val workManager = getWorkManagerSafe() ?: return
        workManager.getWorkInfosForUniqueWorkLiveData(AUTO_BACKUP_WORK_NAME).observeOnce { workInfos
            ->
            if (backupFolder == EMPTY_PATH || periodInDays < 1) {
                if (workInfos?.containsNonCancelled() == true) {
                    workManager.cancelAutoBackup()
                }
            } else if (
                workInfos.isNullOrEmpty() ||
                    workInfos.all { it.state == WorkInfo.State.CANCELLED } ||
                    folderChanged(backupFolderBefore, backupFolder)
            ) {
                workManager.scheduleAutoBackup(this, periodInDays)
                if (execute) {
                    runOnIODispatcher { createBackup() }
                }
            } else if (
                workInfos.first().periodicityInfo?.isEqualTo(periodInDays, TimeUnit.DAYS) == false
            ) {
                workManager.updateAutoBackup(workInfos, periodInDays)
                if (execute) {
                    runOnIODispatcher { createBackup() }
                }
            }
        }
    }

    private fun checkUpdateAutoBackupOnSave(backupFolderBefore: String?, backupFolder: String) {
        if (preferences.backupOnSave.value) {
            if (
                backupFolderBefore == null &&
                    backupFolder != EMPTY_PATH &&
                    !autoBackupOnSaveFileExists(backupFolder)
            ) {
                runOnIODispatcher {
                    autoBackupOnSave(backupFolder, preferences.backupPassword.value, null)
                }
            }
        } else if (folderChanged(backupFolderBefore, backupFolder)) {
            runOnIODispatcher {
                autoBackupOnSave(backupFolder, preferences.backupPassword.value, null)
            }
        }
    }

    private fun checkUpdateAutoRemoveOldDeletedNotes(days: Int) {
        val workManager = getWorkManagerSafe() ?: return
        if (days > 0) {
            workManager.scheduleAutoRemoveOldDeletedNotes(this)
        } else {
            workManager.cancelAutoRemoveOldDeletedNotes()
        }
    }

    private fun getWorkManagerSafe(): WorkManager? {
        return try {
            WorkManager.getInstance(this)
        } catch (e: Exception) {
            // TODO: Happens when ErrorActivity is launched
            null
        }
    }

    private fun <T> runOnIODispatcher(block: suspend CoroutineScope.() -> T) {
        MainScope().launch { withContext(Dispatchers.IO, block) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activity.setEnabledSecureFlag(preferences.secureFlag.value)
    }

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        const val TAG = "MemoXApplication"
        const val AUTO_REMOVE_DELETED_NOTES = "cool.hin.memox.AutoRemoveDeletedNotes"

        fun isTestRunner(): Boolean {
            return Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        }
    }
}

fun WorkManager.scheduleAutoRemoveOldDeletedNotes(context: ContextWrapper) {
    Log.d(TAG, "Scheduling auto removal of old deleted notes")
    val request =
        PeriodicWorkRequest.Builder(AutoRemoveDeletedNotesWorker::class.java, 1, TimeUnit.DAYS)
            .build()
    try {
        enqueueUniquePeriodicWork(
            AUTO_REMOVE_DELETED_NOTES,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    } catch (e: IllegalStateException) {
        // only happens in Unit-Tests
        context.log(TAG, "Scheduling auto removal of old deleted notes failed", throwable = e)
    }
}

fun WorkManager.cancelAutoRemoveOldDeletedNotes() {
    Log.d(TAG, "Cancelling auto removal of old deleted notes")
    cancelUniqueWork(AUTO_REMOVE_DELETED_NOTES)
}
