package cool.hin.memox.data.sync.webdav

import android.content.ContextWrapper
import android.util.Log
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Converters
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.Label
import cool.hin.memox.data.sync.SyncLog
import cool.hin.memox.data.sync.SyncMetaCache
import cool.hin.memox.data.sync.SyncResult
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import cool.hin.memox.utils.SUBFOLDER_AUDIOS
import cool.hin.memox.utils.SUBFOLDER_FILES
import cool.hin.memox.utils.SUBFOLDER_IMAGES
import cool.hin.memox.utils.deleteAttachments
import cool.hin.memox.utils.getCurrentAudioDirectory
import cool.hin.memox.utils.getCurrentFilesDirectory
import cool.hin.memox.utils.getCurrentImagesDirectory
import cool.hin.memox.utils.resolveAttachmentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles synchronization with a WebDAV server.
 *
 * Strategy:
 * - Each note is stored as a separate JSON file: memoX/notes/{title}_{id}.json
 * - If note has no title, filename is: memoX/notes/{id}.json
 * - Attachments are stored in: memoX/attachments/images/, memoX/attachments/audios/, memoX/attachments/files/
 * - Upload: Upload all notes and their attachments, delete remote notes no longer local
 * - Download: Download all notes and their attachments, delete local notes no longer remote
 * - Sync: Two-way sync based on modifiedTimestamp - newer version wins
 */
class WebDavSyncService(private val context: ContextWrapper) {

    companion object {
        private const val TAG = "WebDavSync"
        const val REMOTE_DIR = "memoX"
        const val REMOTE_NOTES_DIR = "memoX/notes"
        const val REMOTE_IMAGES_DIR = "memoX/attachments/images"
        const val REMOTE_AUDIOS_DIR = "memoX/attachments/audios"
        const val REMOTE_FILES_DIR = "memoX/attachments/files"
        const val REMOTE_SYNC_META = "memoX/sync_meta.json"
        const val REMOTE_LABELS_FILE = "memoX/labels.json"

        /** 附件目录，用于一次性建立远端附件索引。 */
        private val ATTACHMENT_DIRS = listOf(REMOTE_IMAGES_DIR, REMOTE_FILES_DIR, REMOTE_AUDIOS_DIR)

        /** 并发上限。取小值以免部分 WebDAV 网关限速或返回 429。 */
        private const val SYNC_CONCURRENCY = 4

        /** Generate a safe filename for a note: {title}_{id}.json or {id}.json */
        fun noteFileName(note: BaseNote): String {
            val safeTitle = note.title
                .trim()
                .replace(Regex("[/\\\\:*?\"<>|\\n\\r]"), "_")
                .take(50)
                .trimEnd('_')
                .trim()
            return if (safeTitle.isNotEmpty()) "${safeTitle}_${note.id}.json" else "${note.id}.json"
        }

        /** Extract note ID from filename. Format: {title}_{id}.json or {id}.json */
        fun extractNoteId(fileName: String): Long? {
            if (!fileName.endsWith(".json")) return null
            val name = fileName.removeSuffix(".json")
            // Try to parse as plain ID first (old format: 123.json)
            name.toLongOrNull()?.let { return it }
            // New format: {title}_{id}.json - extract ID from the last _{digits}
            val lastUnderscore = name.lastIndexOf('_')
            if (lastUnderscore > 0) {
                return name.substring(lastUnderscore + 1).toLongOrNull()
            }
            return null
        }
    }

    private val preferences: MemoXPreferences by lazy { MemoXPreferences.getInstance(context) }

    /**
     * 本次操作期间的远端附件索引：远端目录 -> (文件名 -> 字节数)。
     *
     * 由一次目录列表填充，之后被三处共用：
     * - 上传前比对：远端已有同名同大小则跳过（旧实现无任何校验，每次同步全量重传所有附件）
     * - 下载前比对：本地已有同名同大小则跳过（旧实现同样无条件重下）
     * - 清理孤儿附件：直接复用，省掉重复的目录列表请求
     *
     * 为 null 表示索引不可用，此时退化为旧的"总是传输"行为，正确性不受影响。
     */
    private var remoteAttachmentIndex: Map<String, ConcurrentHashMap<String, Long>>? = null

    /** 远端目录是否已确认存在，避免每次同步都无条件发 6 个 MKCOL。 */
    private var remoteDirsEnsured = false

    private fun createClient(): WebDavClient? {
        val url = preferences.webdavUrl.value
        val username = preferences.webdavUsername.value
        val password = preferences.webdavPassword.value
        if (url.isBlank()) return null
        return WebDavClient(url, username, password)
    }

    /** Test WebDAV connection */
    suspend fun testConnection(): SyncResult = withContext(Dispatchers.IO) {
        val client = createClient()
            ?: return@withContext SyncResult.Error("WebDAV not configured")

        client.testConnection().fold(
            onSuccess = {
                client.createDirectory(REMOTE_DIR)
                client.createDirectory(REMOTE_NOTES_DIR)
                client.createDirectory(REMOTE_IMAGES_DIR)
                client.createDirectory(REMOTE_AUDIOS_DIR)
                client.createDirectory(REMOTE_FILES_DIR)
                SyncResult.Success("Connection successful")
            },
            onFailure = { SyncResult.Error(it.message ?: "Connection failed") }
        )
    }

    /** Upload current data to WebDAV */
    suspend fun upload(): SyncResult = withContext(Dispatchers.IO) {
        val client = createClient()
            ?: return@withContext SyncResult.Error("WebDAV not configured")

        try {
            SyncLog.log("Starting WebDAV upload...")
            ensureRemoteDirs(client)

            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val dao = database.getBaseNoteDao()
            val allNotes = dao.getAllNotesIncludingDeleted()

            SyncLog.log("Found ${allNotes.size} notes to upload")

            // 安全守卫：本地为空时，拒绝"用空库覆盖服务器"，避免清空远程所有笔记与附件。
            if (allNotes.isEmpty()) {
                SyncLog.log("upload: 本地笔记为空，跳过上传以免清空服务器")
                return@withContext SyncResult.Success("本地无数据，已跳过上传（未改动服务器）")
            }

            // Get list of existing remote note files (map: noteId -> list of filenames, to handle duplicates)
            val remoteFiles = client.listFiles(REMOTE_NOTES_DIR).getOrNull() ?: emptyList()
            val remoteNoteIdToFileNames = mutableMapOf<Long, MutableList<String>>()
            for (file in remoteFiles) {
                if (!file.isDirectory && file.name.endsWith(".json")) {
                    extractNoteId(file.name)?.let { id ->
                        remoteNoteIdToFileNames.getOrPut(id) { mutableListOf() }.add(file.name)
                    }
                }
            }

            var uploaded = 0
            var failed = 0

            // Upload each note
            for (note in allNotes) {
                val json = noteToJson(note)
                val newFileName = noteFileName(note)
                val path = "$REMOTE_NOTES_DIR/$newFileName"
                val result = client.upload(path, json.toByteArray(Charsets.UTF_8))
                if (result.isSuccess) {
                    uploaded++
                    // Delete old files if filename changed or duplicates exist
                    val oldFileNames = remoteNoteIdToFileNames.remove(note.id) ?: emptyList()
                    for (oldName in oldFileNames) {
                        if (oldName != newFileName) {
                            client.delete("$REMOTE_NOTES_DIR/$oldName")
                        }
                    }
                    uploadAttachments(client, note)
                } else {
                    failed++
                    SyncLog.log("Failed to upload note ${note.id}: ${result.exceptionOrNull()?.message}")
                }
            }

            // Delete remote notes that no longer exist locally
            for ((_, fileNames) in remoteNoteIdToFileNames) {
                for (fileName in fileNames) {
                    client.delete("$REMOTE_NOTES_DIR/$fileName")
                    SyncLog.log("Deleted remote note: $fileName")
                }
            }

            cleanupOrphanedAttachments(client, allNotes)
            uploadLabels(client)
            val tombstones = preferences.webdavDeletedNoteIds.value
                .mapNotNull { it.toLongOrNull() }
                .toSet()
            uploadSyncMeta(client, allNotes.map { it.id }.toSet(), tombstones)

            preferences.webdavLastSyncTime.save(System.currentTimeMillis())
            SyncLog.log("Upload complete: $uploaded uploaded, $failed failed")
            SyncResult.Success("Upload complete: $uploaded notes uploaded")
        } catch (e: Exception) {
            SyncLog.log("Upload error: ${e.message}")
            SyncResult.Error(e.message ?: "Upload failed")
        }
    }

    /** Download all notes from WebDAV */
    suspend fun download(): SyncResult = withContext(Dispatchers.IO) {
        val client = createClient()
            ?: return@withContext SyncResult.Error("WebDAV not configured")

        try {
            SyncLog.log("Starting WebDAV download...")
            ensureRemoteDirs(client)

            val remoteFiles = client.listFiles(REMOTE_NOTES_DIR).getOrNull() ?: emptyList()
            val noteFiles = remoteFiles.filter { !it.isDirectory && it.name.endsWith(".json") }

            if (noteFiles.isEmpty()) {
                SyncLog.log("No remote notes found")
                return@withContext SyncResult.Error("No notes found on server")
            }

            SyncLog.log("Found ${noteFiles.size} remote notes")

            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val dao = database.getBaseNoteDao()
            val localIds = dao.getAllIds().toSet()

            var downloaded = 0
            var failed = 0
            val remoteIds = mutableSetOf<Long>()

            for (file in noteFiles) {
                val noteId = extractNoteId(file.name) ?: continue
                remoteIds.add(noteId)

                val path = "$REMOTE_NOTES_DIR/${file.name}"
                val result = client.download(path)
                result.fold(
                    onSuccess = { bytes ->
                        try {
                            val json = JSONObject(String(bytes, Charsets.UTF_8))
                            val note = jsonToNote(json)
                            downloadAttachments(client, note)
                            dao.insertSafe(context, note)
                            downloaded++
                        } catch (e: Exception) {
                            failed++
                            SyncLog.log("Failed to parse note $noteId: ${e.message}")
                        }
                    },
                    onFailure = { e ->
                        failed++
                        SyncLog.log("Failed to download note $noteId: ${e.message}")
                    }
                )
            }

            // Delete local notes that no longer exist remotely
            val deletedIds = localIds - remoteIds
            if (deletedIds.isNotEmpty()) {
                dao.delete(deletedIds.toLongArray())
                SyncLog.log("Deleted ${deletedIds.size} local notes not on server")
            }

            downloadLabels(client)

            preferences.webdavLastSyncTime.save(System.currentTimeMillis())
            SyncLog.log("Download complete: $downloaded downloaded, $failed failed")
            SyncResult.Success("Download complete: $downloaded notes downloaded")
        } catch (e: Exception) {
            SyncLog.log("Download error: ${e.message}")
            SyncResult.Error(e.message ?: "Download failed")
        }
    }

    /** Two-way sync: upload local changes, download remote changes, handle deletions via tombstones */
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val client = createClient()
            ?: return@withContext SyncResult.Error("WebDAV not configured")

        try {
            SyncLog.log("Starting WebDAV two-way sync...")
            ensureRemoteDirs(client)

            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val dao = database.getBaseNoteDao()
            val localNotes = dao.getAllNotesIncludingDeleted()
            val localNoteMap = localNotes.associateBy { it.id }

            // Download remote sync_meta to get tombstones (deletedNoteIds)
            val remoteMeta = downloadSyncMeta(client)
            val remoteTombstones = remoteMeta?.deletedNoteIds?.toMutableSet() ?: mutableSetOf()

            // Get local tombstones
            val localTombstones = preferences.webdavDeletedNoteIds.value
                .mapNotNull { it.toLongOrNull() }
                .toMutableSet()

            // Merge tombstones: union of local and remote
            val mergedTombstones = (localTombstones + remoteTombstones).toMutableSet()

            // Apply remote tombstones locally: delete local notes that were deleted on another device
            var deletedLocal = 0
            for (id in mergedTombstones) {
                val localNote = localNoteMap[id]
                if (localNote != null) {
                    dao.delete(longArrayOf(id))
                    context.deleteAttachments(localNote.images + localNote.files + localNote.audios)
                    deletedLocal++
                    SyncLog.log("Deleted local note $id (tombstone from another device)")
                }
            }

            // Re-read local notes after applying tombstones
            val updatedLocalNotes = dao.getAllNotesIncludingDeleted()
            val updatedLocalNoteMap = updatedLocalNotes.associateBy { it.id }

            // Get remote note list - track ALL filenames per note ID to detect duplicates
            val remoteFiles = client.listFiles(REMOTE_NOTES_DIR).getOrNull() ?: emptyList()
            val noteFiles = remoteFiles.filter { !it.isDirectory && it.name.endsWith(".json") }

            // Map: noteId -> list of filenames (to detect and clean up duplicates)
            val remoteNoteIdToFileNames = mutableMapOf<Long, MutableList<String>>()
            for (file in noteFiles) {
                val noteId = extractNoteId(file.name) ?: continue
                remoteNoteIdToFileNames.getOrPut(noteId) { mutableListOf() }.add(file.name)
            }

            // Download and parse remote notes (use the last file if duplicates exist)
            val remoteNoteMap = mutableMapOf<Long, JSONObject>()
            for ((noteId, fileNames) in remoteNoteIdToFileNames) {
                // Skip notes in tombstones (they were deleted)
                if (noteId in mergedTombstones) continue
                // Use the last filename (most recent upload) for the note data
                val fileName = fileNames.last()
                val result = client.download("$REMOTE_NOTES_DIR/$fileName")
                result.getOrNull()?.let { bytes ->
                    try {
                        remoteNoteMap[noteId] = JSONObject(String(bytes, Charsets.UTF_8))
                    } catch (_: Exception) {}
                }
                // Clean up duplicate files for the same note ID
                if (fileNames.size > 1) {
                    for (i in 0 until fileNames.size - 1) {
                        client.delete("$REMOTE_NOTES_DIR/${fileNames[i]}")
                        SyncLog.log("Deleted duplicate remote file: ${fileNames[i]} for note $noteId")
                    }
                }
            }

            // Delete remote notes that are in tombstones
            var deletedRemote = 0
            for (id in mergedTombstones) {
                val fileNames = remoteNoteIdToFileNames.remove(id)
                if (fileNames != null) {
                    for (fileName in fileNames) {
                        client.delete("$REMOTE_NOTES_DIR/$fileName")
                    }
                    // Also delete remote attachments (try to get from remote data)
                    val noteJson = remoteNoteMap.remove(id)
                    if (noteJson != null) {
                        try {
                            val note = jsonToNote(noteJson)
                            for (img in note.images) client.delete("$REMOTE_IMAGES_DIR/${img.localName}")
                            for (f in note.files) client.delete("$REMOTE_FILES_DIR/${f.localName}")
                            for (audio in note.audios) client.delete("$REMOTE_AUDIOS_DIR/${audio.name}")
                        } catch (_: Exception) {}
                    }
                    deletedRemote++
                    SyncLog.log("Deleted remote note $id (tombstone)")
                }
            }

            val localIds = updatedLocalNoteMap.keys
            val remoteIds = remoteNoteMap.keys

            var uploaded = 0
            var downloaded = 0

            // Notes only on local -> upload (new local notes)
            for (id in localIds - remoteIds) {
                val note = updatedLocalNoteMap[id]!!
                uploadNote(client, note)
                uploaded++
            }

            // Notes only on remote -> download (new from another device)
            for (id in remoteIds - localIds) {
                val json = remoteNoteMap[id]!!
                try {
                    val note = jsonToNote(json)
                    downloadAttachments(client, note)
                    dao.insertSafe(context, note)
                    downloaded++
                } catch (_: Exception) {}
            }

            // Notes on both -> compare timestamps
            for (id in localIds.intersect(remoteIds)) {
                val local = updatedLocalNoteMap[id]!!
                val remoteJson = remoteNoteMap[id]!!
                val remoteTimestamp = remoteJson.optLong("modifiedTimestamp", 0)

                if (local.modifiedTimestamp >= remoteTimestamp) {
                    // Upload local version, also clean up old filename if title changed
                    val newFileName = noteFileName(local)
                    val oldFileNames = remoteNoteIdToFileNames[id] ?: emptyList()
                    uploadNote(client, local)
                    for (oldName in oldFileNames) {
                        if (oldName != newFileName) {
                            client.delete("$REMOTE_NOTES_DIR/$oldName")
                            SyncLog.log("Deleted old remote file: $oldName (renamed to $newFileName)")
                        }
                    }
                    uploaded++
                } else {
                    try {
                        val note = jsonToNote(remoteJson)
                        downloadAttachments(client, note)
                        dao.insertSafe(context, note)
                        downloaded++
                    } catch (_: Exception) {}
                }
            }

            // 重新读取本地笔记（此时远程笔记已下载/合并进本地库），
            // 用最新状态判断孤儿附件，避免用过时的"空/子集"列表误删服务器附件。
            cleanupOrphanedAttachments(client, dao.getAllNotesIncludingDeleted())
            syncLabels(client)

            // Clean up tombstones: remove IDs that no longer exist on either side
            val currentNoteIds = localIds + remoteIds
            mergedTombstones.retainAll { it !in currentNoteIds }

            // Save merged tombstones locally and upload sync_meta
            preferences.webdavDeletedNoteIds.save(mergedTombstones.map { it.toString() }.toSet())
            uploadSyncMeta(client, currentNoteIds, mergedTombstones.toSet())

            preferences.webdavLastSyncTime.save(System.currentTimeMillis())
            SyncLog.log("Sync complete: $uploaded uploaded, $downloaded downloaded, $deletedLocal deleted locally, $deletedRemote deleted remotely, ${mergedTombstones.size} tombstones")
            SyncResult.Success("Sync complete: $uploaded up, $downloaded down, $deletedLocal local del, $deletedRemote remote del")
        } catch (e: Exception) {
            SyncLog.log("Sync error: ${e.message}")
            SyncResult.Error(e.message ?: "Sync failed")
        }
    }

    private suspend fun ensureRemoteDirs(client: WebDavClient) {
        client.createDirectory(REMOTE_DIR)           // memoX
        client.createDirectory(REMOTE_NOTES_DIR)     // memoX/notes
        client.createDirectory("memoX/attachments")  // memoX/attachments (intermediate)
        client.createDirectory(REMOTE_IMAGES_DIR)    // memoX/attachments/images
        client.createDirectory(REMOTE_AUDIOS_DIR)    // memoX/attachments/audios
        client.createDirectory(REMOTE_FILES_DIR)     // memoX/attachments/files
        remoteDirsEnsured = true
    }

    /**
     * 只在确实需要时建目录。
     * 旧实现每次同步开头无条件发 6 个 MKCOL；实际上目录只需建一次，
     * 后续靠"目录列表失败"来兜底自愈即可。
     */
    private suspend fun ensureRemoteDirsOnce(client: WebDavClient) {
        if (remoteDirsEnsured) return
        ensureRemoteDirs(client)
    }

    /**
     * 一次性拉取三个附件目录的清单，建立索引。
     * 这三次请求原本就要在清理孤儿附件时发一遍，现在提前发、全流程共用，总请求数反而更少。
     */
    private suspend fun loadRemoteAttachmentIndex(client: WebDavClient) {
        val index = mutableMapOf<String, ConcurrentHashMap<String, Long>>()
        var anyDirMissing = false
        for (dir in ATTACHMENT_DIRS) {
            val map = ConcurrentHashMap<String, Long>()
            val files = client.listFiles(dir).getOrNull()
            if (files == null) {
                anyDirMissing = true
            } else {
                for (file in files) if (!file.isDirectory) map[file.name] = file.size
            }
            index[dir] = map
        }
        // 列不出来通常意味着目录还没建过，补一次 MKCOL；空索引正好等价于"远端什么都没有"
        if (anyDirMissing) ensureRemoteDirsOnce(client)
        remoteAttachmentIndex = index
    }

    /** 限流并发执行。部分 WebDAV 网关对并发敏感，故上限取小值。 */
    private suspend fun <T> Collection<T>.forEachParallel(action: suspend (T) -> Unit) {
        when {
            isEmpty() -> return
            size == 1 -> action(first())
            else ->
                coroutineScope {
                    val semaphore = Semaphore(SYNC_CONCURRENCY)
                    map { item ->
                            async(Dispatchers.IO) { semaphore.withPermit { action(item) } }
                        }
                        .awaitAll()
                }
        }
    }

    private fun noteToJson(note: BaseNote): String {
        val json = JSONObject()
        json.put("id", note.id)
        json.put("type", note.type.name)
        json.put("folder", note.folder.name)
        json.put("color", note.color)
        json.put("title", note.title)
        json.put("pinned", note.pinned)
        json.put("timestamp", note.timestamp)
        json.put("modifiedTimestamp", note.modifiedTimestamp)
        json.put("labels", Converters.labelsToJson(note.labels))
        json.put("body", note.body)
        json.put("spans", Converters.spansToJson(note.spans))
        json.put("items", Converters.itemsToJson(note.items))
        json.put("images", Converters.filesToJson(note.images))
        json.put("files", Converters.filesToJson(note.files))
        json.put("audios", Converters.audiosToJson(note.audios))
        json.put("reminders", Converters.remindersToJson(note.reminders))
        json.put("isPinnedToStatus", note.isPinnedToStatus)
        json.put("locked", note.locked)
        return json.toString(2)
    }

    private fun jsonToNote(json: JSONObject): BaseNote {
        return BaseNote(
            id = json.getLong("id"),
            type = cool.hin.memox.data.model.Type.valueOfOrDefault(json.getString("type")),
            folder = Folder.valueOf(json.getString("folder")),
            color = json.getString("color"),
            title = json.optString("title", ""),
            pinned = json.optBoolean("pinned", false),
            timestamp = json.getLong("timestamp"),
            modifiedTimestamp = json.optLong("modifiedTimestamp", json.getLong("timestamp")),
            labels = Converters.jsonToLabels(JSONArray(json.optString("labels", "[]"))),
            body = json.optString("body", ""),
            spans = Converters.jsonToSpans(json.optString("spans", "[]")),
            items = Converters.jsonToItems(JSONArray(json.optString("items", "[]"))),
            images = Converters.jsonToFiles(JSONArray(json.optString("images", "[]"))),
            files = Converters.jsonToFiles(JSONArray(json.optString("files", "[]"))),
            audios = Converters.jsonToAudios(JSONArray(json.optString("audios", "[]"))),
            reminders = Converters.jsonToReminders(JSONArray(json.optString("reminders", "[]"))),
            isPinnedToStatus = json.optBoolean("isPinnedToStatus", false),
            locked = json.optBoolean("locked", false),
        )
    }

    private fun uploadNote(client: WebDavClient, note: BaseNote): Result<Unit> {
        val json = noteToJson(note)
        val path = "$REMOTE_NOTES_DIR/${noteFileName(note)}"
        val result = client.upload(path, json.toByteArray(Charsets.UTF_8))
        if (result.isSuccess) {
            uploadAttachments(client, note)
        }
        return result
    }

    /** Upload all attachments for a note (skips what the server already has) */
    private fun uploadAttachments(client: WebDavClient, note: BaseNote) {
        for (img in note.images) {
            uploadAttachment(client, REMOTE_IMAGES_DIR, SUBFOLDER_IMAGES, img.localName)
        }
        for (f in note.files) {
            uploadAttachment(client, REMOTE_FILES_DIR, SUBFOLDER_FILES, f.localName)
        }
        for (audio in note.audios) {
            uploadAttachment(client, REMOTE_AUDIOS_DIR, SUBFOLDER_AUDIOS, audio.name)
        }
    }

    /**
     * 上传单个附件。远端已存在同名且字节数一致时直接跳过。
     *
     * 附件名由本地生成且唯一，"同名 + 同大小"即同一份内容。
     * 旧实现没有任何校验：只要笔记被判定为需要上传，它的全部图片/音频/附件就整体重传一遍，
     * 与 `>=` 的时间戳判断叠加后，等于每次同步都把所有附件字节重新推一遍。
     */
    private fun uploadAttachment(
        client: WebDavClient,
        remoteDir: String,
        subfolder: String,
        name: String,
    ) {
        val file = getLocalAttachmentFile(subfolder, name)
        if (file == null || !file.exists()) {
            Log.d(TAG, "uploadAttachment: local file missing, skip $subfolder/$name")
            return
        }
        val localSize = file.length()
        val known = remoteAttachmentIndex?.get(remoteDir)
        if (known != null && known[name] == localSize) return

        val result = client.upload("$remoteDir/$name", file.readBytes())
        if (result.isSuccess) {
            known?.put(name, localSize)
        } else {
            Log.w(
                TAG,
                "uploadAttachment: failed $remoteDir/$name: ${result.exceptionOrNull()?.message}",
            )
        }
    }

    /** Download all attachments for a note (skips what is already on disk) */
    private fun downloadAttachments(client: WebDavClient, note: BaseNote) {
        for (img in note.images) {
            downloadAttachment(client, REMOTE_IMAGES_DIR, SUBFOLDER_IMAGES, img.localName)
        }
        for (f in note.files) {
            downloadAttachment(client, REMOTE_FILES_DIR, SUBFOLDER_FILES, f.localName)
        }
        for (audio in note.audios) {
            downloadAttachment(client, REMOTE_AUDIOS_DIR, SUBFOLDER_AUDIOS, audio.name)
        }
    }

    /** 下载单个附件。本地已有同名非空文件（且大小与远端一致）时跳过。 */
    private fun downloadAttachment(
        client: WebDavClient,
        remoteDir: String,
        subfolder: String,
        name: String,
    ) {
        val localFile = ensureLocalAttachmentFile(subfolder, name) ?: return
        val remoteSize = remoteAttachmentIndex?.get(remoteDir)?.get(name)
        if (
            localFile.exists() &&
                localFile.length() > 0 &&
                (remoteSize == null || remoteSize == localFile.length())
        ) {
            return
        }
        client.download("$remoteDir/$name").getOrNull()?.let { bytes ->
            localFile.parentFile?.mkdirs()
            localFile.writeBytes(bytes)
        }
    }

    /** Get local attachment file (may return null if directory can't be resolved) */
    private fun getLocalAttachmentFile(subfolder: String, localName: String): File? {
        return context.resolveAttachmentFile(subfolder, localName)
    }

    /** Ensure the local attachment file path exists (creates parent dirs), returns the File */
    private fun ensureLocalAttachmentFile(subfolder: String, localName: String): File? {
        val dir = when (subfolder) {
            SUBFOLDER_IMAGES -> context.getCurrentImagesDirectory()
            SUBFOLDER_FILES -> context.getCurrentFilesDirectory()
            SUBFOLDER_AUDIOS -> context.getCurrentAudioDirectory()
            else -> return null
        }
        return File(dir, localName)
    }

    /**
     * Remove remote attachments that are no longer referenced by any note.
     *
     * 安全守卫：本地笔记为空时【绝不】删除服务器附件。
     * 空本地库（例如全新安装、或与正式版共用同一 WebDAV 的 debug 构建）绝不能
     * 被解读为"服务器上的附件都没用了"。否则一次空库的同步/上传会把整个服务器清空。
     */
    private fun cleanupOrphanedAttachments(client: WebDavClient, allNotes: List<BaseNote>) {
        if (allNotes.isEmpty()) {
            SyncLog.log("cleanupOrphanedAttachments: 本地笔记为空，跳过以免误删服务器附件")
            return
        }
        try {
            val referencedImages = allNotes.flatMap { it.images.map { img -> img.localName } }.toSet()
            val referencedFiles = allNotes.flatMap { it.files.map { f -> f.localName } }.toSet()
            val referencedAudios = allNotes.flatMap { it.audios.map { a -> a.name } }.toSet()

            cleanupOrphanedDir(client, REMOTE_IMAGES_DIR, referencedImages)
            cleanupOrphanedDir(client, REMOTE_FILES_DIR, referencedFiles)
            cleanupOrphanedDir(client, REMOTE_AUDIOS_DIR, referencedAudios)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup orphaned attachments", e)
        }
    }

    private fun cleanupOrphanedDir(client: WebDavClient, dir: String, referencedNames: Set<String>) {
        // 优先复用本次同步已建立的索引，避免重复发目录列表请求
        val known = remoteAttachmentIndex?.get(dir)
        val names =
            known?.keys?.toList()
                ?: client.listFiles(dir).getOrNull()?.filterNot { it.isDirectory }?.map { it.name }
                ?: return
        for (name in names) {
            if (name !in referencedNames) {
                client.delete("$dir/$name")
                known?.remove(name)
                SyncLog.log("Deleted orphaned attachment: $name")
            }
        }
    }

    /** Delete a single note and its attachments from WebDAV, and record tombstone */
    suspend fun deleteRemoteNote(note: BaseNote) = withContext(Dispatchers.IO) {
        val client = createClient() ?: return@withContext
        try {
            // Delete all possible filenames for this note ID
            val currentFileName = noteFileName(note)
            val idOnlyFileName = "${note.id}.json"
            client.delete("$REMOTE_NOTES_DIR/$currentFileName")
            if (currentFileName != idOnlyFileName) {
                client.delete("$REMOTE_NOTES_DIR/$idOnlyFileName")
            }
            // Also try to find and delete any other files with this note ID
            val remoteFiles = client.listFiles(REMOTE_NOTES_DIR).getOrNull() ?: emptyList()
            for (file in remoteFiles) {
                if (!file.isDirectory && file.name.endsWith(".json")) {
                    if (extractNoteId(file.name) == note.id && file.name != currentFileName && file.name != idOnlyFileName) {
                        client.delete("$REMOTE_NOTES_DIR/${file.name}")
                    }
                }
            }
            // Delete remote attachments
            for (img in note.images) {
                client.delete("$REMOTE_IMAGES_DIR/${img.localName}")
            }
            for (f in note.files) {
                client.delete("$REMOTE_FILES_DIR/${f.localName}")
            }
            for (audio in note.audios) {
                client.delete("$REMOTE_AUDIOS_DIR/${audio.name}")
            }
            // Record tombstone
            addTombstone(client, note.id)
            SyncLog.log("Deleted remote note: $currentFileName (and any duplicate files)")
        } catch (e: Exception) {
            Log.w(TAG, "deleteRemoteNote failed: ${e.message}")
        }
    }

    /** Delete multiple notes and their attachments from WebDAV, and record tombstones */
    suspend fun deleteRemoteNotes(notes: Collection<BaseNote>) = withContext(Dispatchers.IO) {
        val client = createClient() ?: return@withContext
        try {
            // Get all remote files to find duplicates
            val remoteFiles = client.listFiles(REMOTE_NOTES_DIR).getOrNull() ?: emptyList()

            for (note in notes) {
                val currentFileName = noteFileName(note)
                val idOnlyFileName = "${note.id}.json"
                client.delete("$REMOTE_NOTES_DIR/$currentFileName")
                if (currentFileName != idOnlyFileName) {
                    client.delete("$REMOTE_NOTES_DIR/$idOnlyFileName")
                }
                // Delete any other files with this note ID
                for (file in remoteFiles) {
                    if (!file.isDirectory && file.name.endsWith(".json")) {
                        if (extractNoteId(file.name) == note.id && file.name != currentFileName && file.name != idOnlyFileName) {
                            client.delete("$REMOTE_NOTES_DIR/${file.name}")
                        }
                    }
                }
                for (img in note.images) {
                    client.delete("$REMOTE_IMAGES_DIR/${img.localName}")
                }
                for (f in note.files) {
                    client.delete("$REMOTE_FILES_DIR/${f.localName}")
                }
                for (audio in note.audios) {
                    client.delete("$REMOTE_AUDIOS_DIR/${audio.name}")
                }
            }
            // Record tombstones
            addTombstones(client, notes.map { it.id }.toSet())
            SyncLog.log("Deleted ${notes.size} remote notes")
        } catch (e: Exception) {
            Log.w(TAG, "deleteRemoteNotes failed: ${e.message}")
        }
    }

    /** Add a note ID to the tombstone list and upload updated sync_meta */
    private fun addTombstone(client: WebDavClient, noteId: Long) {
        addTombstones(client, setOf(noteId))
    }

    /** Add note IDs to the tombstone list and upload updated sync_meta */
    private fun addTombstones(client: WebDavClient, noteIds: Set<Long>) {
        val currentTombstones = preferences.webdavDeletedNoteIds.value
            .mapNotNull { it.toLongOrNull() }
            .toMutableSet()
        currentTombstones.addAll(noteIds)
        preferences.webdavDeletedNoteIds.save(currentTombstones.map { it.toString() }.toSet())

        // Upload updated sync_meta with tombstones
        try {
            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val dao = database.getBaseNoteDao()
            val localIds = dao.getAllIds().toSet()
            uploadSyncMeta(client, localIds, currentTombstones)
        } catch (_: Exception) {}
    }

    /** Upload sync metadata including synced note IDs and tombstones */
    private fun uploadSyncMeta(client: WebDavClient, noteIds: Set<Long>, tombstones: Set<Long>) {
        try {
            val json = JSONObject().apply {
                put("lastSyncTime", System.currentTimeMillis())
                put("noteCount", noteIds.size)
                put("appVersion", "1.0.5")
                val idsArray = JSONArray()
                for (id in noteIds.sorted()) {
                    idsArray.put(id)
                }
                put("syncedNoteIds", idsArray)
                val deletedArray = JSONArray()
                for (id in tombstones.sorted()) {
                    deletedArray.put(id)
                }
                put("deletedNoteIds", deletedArray)
            }
            client.upload(REMOTE_SYNC_META, json.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {}
    }

    /** Data class for sync metadata */
    private data class SyncMeta(
        val syncedNoteIds: Set<Long>,
        val deletedNoteIds: Set<Long>,
    )

    /** Download sync metadata */
    private fun downloadSyncMeta(client: WebDavClient): SyncMeta? {
        return try {
            val result = client.download(REMOTE_SYNC_META)
            val bytes = result.getOrNull() ?: return null
            val json = JSONObject(String(bytes, Charsets.UTF_8))

            val syncedIds = mutableSetOf<Long>()
            val syncedArray = json.optJSONArray("syncedNoteIds")
            if (syncedArray != null) {
                for (i in 0 until syncedArray.length()) {
                    syncedIds.add(syncedArray.getLong(i))
                }
            }

            val deletedIds = mutableSetOf<Long>()
            val deletedArray = json.optJSONArray("deletedNoteIds")
            if (deletedArray != null) {
                for (i in 0 until deletedArray.length()) {
                    deletedIds.add(deletedArray.getLong(i))
                }
            }

            SyncMeta(syncedIds, deletedIds)
        } catch (_: Exception) {
            null
        }
    }

    /** Upload labels and hidden labels config to WebDAV */
    private suspend fun uploadLabels(client: WebDavClient) {
        try {
            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val labelDao = database.getLabelDao()
            val labels = labelDao.getArrayOfAll()
            val hiddenLabels = preferences.labelsHidden.value

            val json = JSONObject().apply {
                val labelsArray = JSONArray()
                for (label in labels) {
                    labelsArray.put(label)
                }
                put("labels", labelsArray)
                val hiddenArray = JSONArray()
                for (hidden in hiddenLabels) {
                    hiddenArray.put(hidden)
                }
                put("hiddenLabels", hiddenArray)
            }
            client.upload(REMOTE_LABELS_FILE, json.toString().toByteArray(Charsets.UTF_8))
            preferences.labelsHiddenLastSynced.save(hiddenLabels)
            Log.d(TAG, "uploadLabels: uploaded ${labels.size} labels, ${hiddenLabels.size} hidden")
        } catch (e: Exception) {
            Log.w(TAG, "uploadLabels: failed: ${e.message}")
        }
    }

    /** Download labels and hidden labels config from WebDAV, replacing local data */
    private suspend fun downloadLabels(client: WebDavClient) {
        try {
            val result = client.download(REMOTE_LABELS_FILE)
            val bytes = result.getOrNull() ?: return
            val json = JSONObject(String(bytes, Charsets.UTF_8))

            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val labelDao = database.getLabelDao()

            // Parse labels from JSON
            val labelsArray = json.optJSONArray("labels") ?: return
            val labels = mutableListOf<Label>()
            for (i in 0 until labelsArray.length()) {
                val value = labelsArray.getString(i)
                labels.add(Label(value, i))
            }

            // Replace local labels
            labelDao.deleteAll()
            labelDao.insert(labels)

            // Parse and apply hidden labels
            val hiddenArray = json.optJSONArray("hiddenLabels")
            if (hiddenArray != null) {
                val hiddenSet = mutableSetOf<String>()
                for (i in 0 until hiddenArray.length()) {
                    hiddenSet.add(hiddenArray.getString(i))
                }
                preferences.labelsHidden.save(hiddenSet)
                preferences.labelsHiddenLastSynced.save(hiddenSet)
            }

            Log.d(TAG, "downloadLabels: downloaded ${labels.size} labels")
        } catch (e: Exception) {
            Log.w(TAG, "downloadLabels: failed: ${e.message}")
        }
    }

    /** Two-way sync for labels: merge local and remote labels */
    private suspend fun syncLabels(client: WebDavClient) {
        try {
            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val labelDao = database.getLabelDao()
            val localLabels = labelDao.getArrayOfAll().toSet()
            val localHidden = preferences.labelsHidden.value

            val result = client.download(REMOTE_LABELS_FILE)
            if (result.isFailure) {
                // Download failed (network/auth/transient). Do NOT overwrite the remote labels
                // file with local state here, otherwise a transient failure on this device would
                // clobber another device's (e.g. hidden-label) changes that are already on the server.
                Log.w(TAG, "syncLabels: download failed, skipping: ${result.exceptionOrNull()?.message}")
                return
            }
            val bytes = result.getOrNull()
            if (bytes == null) {
                // Remote labels file does not exist yet -> upload local state.
                uploadLabels(client)
                return
            }

            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val remoteArray = json.optJSONArray("labels") ?: return
            val remoteLabels = mutableSetOf<String>()
            for (i in 0 until remoteArray.length()) {
                remoteLabels.add(remoteArray.getString(i))
            }

            val remoteHiddenArray = json.optJSONArray("hiddenLabels")
            val remoteHidden = mutableSetOf<String>()
            if (remoteHiddenArray != null) {
                for (i in 0 until remoteHiddenArray.length()) {
                    remoteHidden.add(remoteHiddenArray.getString(i))
                }
            }

            // Merge: union of local and remote labels
            val mergedLabels = localLabels + remoteLabels
            val maxOrder = labelDao.getMaxOrder() ?: -1

            // Delete labels not in merged set, then insert missing ones
            val toInsert = mergedLabels - localLabels
            for ((index, value) in toInsert.withIndex()) {
                labelDao.insert(Label(value, maxOrder + 1 + index))
            }

            // Three-way merge of hidden-label state (base = snapshot from last successful sync)
            // so that un-hiding (removals) propagates across devices. A plain union would keep a
            // label hidden forever once any device had hidden it.
            val lastSynced = preferences.labelsHiddenLastSynced.value
            val mergedHidden = lastSynced.toMutableSet()
            (remoteHidden - lastSynced).forEach { mergedHidden.add(it) }
            (lastSynced - remoteHidden).forEach { mergedHidden.remove(it) }
            (localHidden - lastSynced).forEach { mergedHidden.add(it) }
            (lastSynced - localHidden).forEach { mergedHidden.remove(it) }
            preferences.labelsHidden.save(mergedHidden)

            // Upload merged result (uploadLabels also refreshes labelsHiddenLastSynced to mergedHidden)
            uploadLabels(client)

            Log.d(TAG, "syncLabels: local=${localLabels.size}, remote=${remoteLabels.size}, merged=${mergedLabels.size}")
        } catch (e: Exception) {
            Log.w(TAG, "syncLabels: failed: ${e.message}")
        }
    }
}
