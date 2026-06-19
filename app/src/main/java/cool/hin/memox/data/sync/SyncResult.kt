package cool.hin.memox.data.sync

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Result of a sync operation.
 */
sealed class SyncResult {
    data class Success(val message: String) : SyncResult()
    data class Error(val message: String) : SyncResult()
    data class DownloadReady(val filePath: String) : SyncResult()
}

/**
 * Current status of sync.
 */
enum class SyncStatus {
    IDLE,
    CONNECTING,
    UPLOADING,
    DOWNLOADING,
    SYNCING,
    SUCCESS,
    ERROR,
}

/**
 * Simple sync log for debugging.
 */
object SyncLog {
    private val logs = mutableListOf<String>()
    private var listener: ((String) -> Unit)? = null

    fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "[$timestamp] $message"
        logs.add(entry)
        listener?.invoke(entry)
    }

    fun getLogs(): List<String> = logs.toList()

    fun clear() {
        logs.clear()
    }

    fun setListener(listener: ((String) -> Unit)?) {
        this.listener = listener
    }
}
