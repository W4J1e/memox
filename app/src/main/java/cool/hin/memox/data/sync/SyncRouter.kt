package cool.hin.memox.data.sync

import android.content.Context
import android.content.ContextWrapper
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.sync.onedrive.OneDriveSyncService
import cool.hin.memox.data.sync.onedrive.OneDriveSyncWorker
import cool.hin.memox.data.sync.webdav.WebDavSyncService
import cool.hin.memox.data.sync.webdav.WebDavSyncWorker
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences

/**
 * Routes sync operations to the currently active provider (WebDAV or OneDrive).
 * Call sites should use this instead of [WebDavSyncWorker] / [OneDriveSyncWorker] directly.
 */
object SyncRouter {

    /** Trigger an immediate (debounced) sync for the active provider, if auto-sync is on. */
    fun syncNow(context: Context) {
        when (MemoXPreferences.getInstance(context).syncProvider.value) {
            MemoXPreferences.PROVIDER_WEBDAV -> WebDavSyncWorker.syncNow(context)
            MemoXPreferences.PROVIDER_ONEDRIVE -> OneDriveSyncWorker.syncNow(context)
        }
    }

    /**
     * Schedule (or cancel) the periodic auto-sync for the active provider and cancel the
     * other provider's pending work so they never run at the same time.
     */
    fun schedule(context: ContextWrapper) {
        val prefs = MemoXPreferences.getInstance(context)
        when (prefs.syncProvider.value) {
            MemoXPreferences.PROVIDER_WEBDAV -> {
                WebDavSyncWorker.schedule(context)
                OneDriveSyncWorker.cancel(context)
            }
            MemoXPreferences.PROVIDER_ONEDRIVE -> {
                OneDriveSyncWorker.schedule(context)
                WebDavSyncWorker.cancel(context)
            }
            else -> {
                WebDavSyncWorker.cancel(context)
                OneDriveSyncWorker.cancel(context)
            }
        }
    }

    /** Delete a single note (and its attachments) from the active provider's remote store. */
    suspend fun deleteRemoteNote(context: ContextWrapper, note: BaseNote) {
        when (MemoXPreferences.getInstance(context).syncProvider.value) {
            MemoXPreferences.PROVIDER_WEBDAV -> WebDavSyncService(context).deleteRemoteNote(note)
            MemoXPreferences.PROVIDER_ONEDRIVE -> OneDriveSyncService(context).deleteRemoteNote(note)
        }
    }

    /** Delete multiple notes (and their attachments) from the active provider's remote store. */
    suspend fun deleteRemoteNotes(context: ContextWrapper, notes: Collection<BaseNote>) {
        when (MemoXPreferences.getInstance(context).syncProvider.value) {
            MemoXPreferences.PROVIDER_WEBDAV -> WebDavSyncService(context).deleteRemoteNotes(notes)
            MemoXPreferences.PROVIDER_ONEDRIVE -> OneDriveSyncService(context).deleteRemoteNotes(notes)
        }
    }
}
