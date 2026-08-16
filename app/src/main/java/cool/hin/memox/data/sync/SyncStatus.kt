package cool.hin.memox.data.sync

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

enum class SyncState {
    IDLE,
    SYNCING,
    COMPLETED,
}

/**
 * 灵动岛要展示的一帧状态。
 *
 * [message] 为空时由 UI 使用默认的同步文案；非空时直接展示该文案，供「批量压缩图片」这类
 * 非同步的长任务复用灵动岛显示自己的进度。
 */
data class SyncUiState(val state: SyncState, val message: String? = null)

/**
 * Process-wide observable for the current sync status. The sync workers post updates here so
 * the UI (e.g. the "Dynamic Island" indicator in MainActivity) can react without polling.
 */
object SyncStatus {

    private val _state = MutableLiveData(SyncUiState(SyncState.IDLE))
    val state: LiveData<SyncUiState> = _state

    fun setSyncing() {
        _state.postValue(SyncUiState(SyncState.SYNCING))
    }

    fun setCompleted() {
        _state.postValue(SyncUiState(SyncState.COMPLETED))
    }

    fun setIdle() {
        _state.postValue(SyncUiState(SyncState.IDLE))
    }

    /** 非同步的长任务进行中（转圈 + 自定义文案），如批量压缩存量图片。 */
    fun setBusy(message: String) {
        _state.postValue(SyncUiState(SyncState.SYNCING, message))
    }

    /** 非同步的长任务完成（对勾 + 自定义文案），若干秒后自动收起。 */
    fun setDone(message: String) {
        _state.postValue(SyncUiState(SyncState.COMPLETED, message))
    }
}
