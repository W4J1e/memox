package cool.hin.memox.data.sync

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 远端笔记文件的"指纹缓存"。
 *
 * 同步时目录列表（WebDAV 的 PROPFIND / Graph 的 children）本来就会返回每个文件的
 * 文件名、大小和版本标识（lastModified / eTag），但旧实现完全没用这些元数据，
 * 而是无条件 GET 每一个 note.json，只为读出里面的 `modifiedTimestamp`。
 * N 条笔记 = N 次串行下载，哪怕一条都没改。
 *
 * 这个缓存把「文件指纹 -> 该文件里的 modifiedTimestamp」记在本地。
 * 下次同步只要指纹没变，就直接复用缓存里的时间戳，不用再下载。
 *
 * 指纹 = 文件名 + 版本标识 + 字节数，三者任一变化即视为内容已变，回退到 GET。
 */
class SyncMetaCache private constructor(
    private val entries: ConcurrentHashMap<Long, Entry>,
) {

    data class Entry(
        val fileName: String,
        val version: String,
        val size: Long,
        val modifiedTimestamp: Long,
    )

    /**
     * 指纹命中则返回缓存的 modifiedTimestamp，否则返回 null（调用方需自行下载）。
     * 版本标识缺失（服务器没返回 lastModified/eTag）时一律返回 null，宁可多下不可下错。
     */
    fun timestampIfUnchanged(noteId: Long, fileName: String, version: String?, size: Long): Long? {
        if (version.isNullOrEmpty()) return null
        val entry = entries[noteId] ?: return null
        if (entry.fileName != fileName) return null
        if (entry.version != version) return null
        if (entry.size != size) return null
        return entry.modifiedTimestamp
    }

    fun put(noteId: Long, fileName: String, version: String?, size: Long, modifiedTimestamp: Long) {
        if (version.isNullOrEmpty()) {
            entries.remove(noteId)
            return
        }
        entries[noteId] = Entry(fileName, version, size, modifiedTimestamp)
    }

    /** 本地刚 PUT 过该笔记，服务端新版本标识未知，先作废，下次同步补一次 GET。 */
    fun invalidate(noteId: Long) {
        entries.remove(noteId)
    }

    /** 丢弃两端都已不存在的笔记，避免缓存无限膨胀。 */
    fun retainOnly(noteIds: Set<Long>) {
        entries.keys.retainAll(noteIds)
    }

    fun serialize(): String {
        val root = JSONObject()
        for ((noteId, entry) in entries) {
            root.put(
                noteId.toString(),
                JSONObject().apply {
                    put(KEY_FILE_NAME, entry.fileName)
                    put(KEY_VERSION, entry.version)
                    put(KEY_SIZE, entry.size)
                    put(KEY_TIMESTAMP, entry.modifiedTimestamp)
                },
            )
        }
        return root.toString()
    }

    companion object {
        private const val KEY_FILE_NAME = "f"
        private const val KEY_VERSION = "v"
        private const val KEY_SIZE = "s"
        private const val KEY_TIMESTAMP = "t"

        fun parse(serialized: String): SyncMetaCache {
            val entries = ConcurrentHashMap<Long, Entry>()
            if (serialized.isNotBlank()) {
                try {
                    val root = JSONObject(serialized)
                    for (key in root.keys()) {
                        val noteId = key.toLongOrNull() ?: continue
                        val obj = root.optJSONObject(key) ?: continue
                        val fileName = obj.optString(KEY_FILE_NAME)
                        val version = obj.optString(KEY_VERSION)
                        if (fileName.isEmpty() || version.isEmpty()) continue
                        entries[noteId] =
                            Entry(
                                fileName = fileName,
                                version = version,
                                size = obj.optLong(KEY_SIZE, -1),
                                modifiedTimestamp = obj.optLong(KEY_TIMESTAMP, 0),
                            )
                    }
                } catch (_: Exception) {
                    // 缓存损坏就当作没有缓存，退化为全量 GET（正确性不受影响）
                }
            }
            return SyncMetaCache(entries)
        }
    }
}
