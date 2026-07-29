package cool.hin.memox.data.sync

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

enum class SyncState {
    IDLE,
    SYNCING,
    COMPLETED,
}

/**
 * Process-wide observable for the current sync status. The sync workers post updates here so
 * the UI (e.g. the "Dynamic Island" indicator in MainActivity) can react without polling.
 */
object SyncStatus {

    private val _state = MutableLiveData<SyncState>(SyncState.IDLE)
    val state: LiveData<SyncState> = _state

    fun setSyncing() {
        _state.postValue(SyncState.SYNCING)
    }

    fun setCompleted() {
        _state.postValue(SyncState.COMPLETED)
    }

    fun setIdle() {
        _state.postValue(SyncState.IDLE)
    }
}
