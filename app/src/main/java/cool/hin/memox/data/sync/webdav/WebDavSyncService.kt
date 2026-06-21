package cool.hin.memox.data.sync.webdav

import android.content.ContextWrapper
import android.util.Log
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Converters
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.Label
import cool.hin.memox.data.sync.SyncLog
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
            val allNotes = dao.getAllNotes()

            SyncLog.log("Found ${allNotes.size} notes to upload")

            // Get list of existing remote note files (map: noteId -> fileName)
            val remoteFiles = client.listFiles(REMOTE_NOTES_DIR).getOrNull() ?: emptyList()
            val remoteNoteIdToFileName = mutableMapOf<Long, String>()
            for (file in remoteFiles) {
                if (!file.isDirectory && file.name.endsWith(".json")) {
                    extractNoteId(file.name)?.let { id ->
                        remoteNoteIdToFileName[id] = file.name
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
                    // Delete old file if filename changed (e.g., title changed)
                    val oldFileName = remoteNoteIdToFileName.remove(note.id)
                    if (oldFileName != null && oldFileName != newFileName) {
                        client.delete("$REMOTE_NOTES_DIR/$oldFileName")
                    }
                    uploadAttachments(client, note)
                } else {
                    failed++
                    SyncLog.log("Failed to upload note ${note.id}: ${result.exceptionOrNull()?.message}")
                }
            }

            // Delete remote notes that no longer exist locally
            for ((_, fileName) in remoteNoteIdToFileName) {
                client.delete("$REMOTE_NOTES_DIR/$fileName")
                SyncLog.log("Deleted remote note: $fileName")
            }

            cleanupOrphanedAttachments(client, allNotes)
            uploadLabels(client)
            uploadSyncMeta(client, allNotes.map { it.id }.toSet())

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

    /** Two-way sync: upload local changes, download remote changes, handle deletions */
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val client = createClient()
            ?: return@withContext SyncResult.Error("WebDAV not configured")

        try {
            SyncLog.log("Starting WebDAV two-way sync...")
            ensureRemoteDirs(client)

            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val dao = database.getBaseNoteDao()
            val localNotes = dao.getAllNotes()
            val localNoteMap = localNotes.associateBy { it.id }
            val lastSyncTime = preferences.webdavLastSyncTime.value

            // Get previously synced note IDs from sync_meta
            val previouslySyncedIds = getSyncedNoteIds(client)

            // Get remote note list
            val remoteFiles = client.listFiles(REMOTE_NOTES_DIR).getOrNull() ?: emptyList()
            val noteFiles = remoteFiles.filter { !it.isDirectory && it.name.endsWith(".json") }

            val remoteNoteMap = mutableMapOf<Long, JSONObject>()
            for (file in noteFiles) {
                val noteId = extractNoteId(file.name) ?: continue
                val result = client.download("$REMOTE_NOTES_DIR/${file.name}")
                result.getOrNull()?.let { bytes ->
                    try {
                        remoteNoteMap[noteId] = JSONObject(String(bytes, Charsets.UTF_8))
                    } catch (_: Exception) {}
                }
            }

            val localIds = localNoteMap.keys
            val remoteIds = remoteNoteMap.keys

            var uploaded = 0
            var downloaded = 0
            var deletedRemote = 0
            var deletedLocal = 0

            // Notes only on local:
            // - If previously synced (was on server before), it was deleted on another device -> delete locally
            // - If not previously synced, it's a new local note -> upload
            for (id in localIds - remoteIds) {
                val note = localNoteMap[id]!!
                if (id in previouslySyncedIds) {
                    // Was on server before, now gone -> deleted on another device
                    dao.delete(longArrayOf(id))
                    context.deleteAttachments(note.images + note.files + note.audios)
                    deletedLocal++
                    SyncLog.log("Deleted local note $id (was deleted on another device)")
                } else {
                    // New local note -> upload
                    uploadNote(client, note)
                    uploaded++
                }
            }

            // Notes only on remote:
            // - If modifiedTimestamp > lastSyncTime -> new from another device -> download
            // - If modifiedTimestamp <= lastSyncTime -> was deleted locally -> delete from remote
            for (id in remoteIds - localIds) {
                val json = remoteNoteMap[id]!!
                val remoteTimestamp = json.optLong("modifiedTimestamp", 0)
                if (lastSyncTime == 0L || remoteTimestamp > lastSyncTime) {
                    // New note from another device (modified after our last sync)
                    try {
                        val note = jsonToNote(json)
                        downloadAttachments(client, note)
                        dao.insertSafe(context, note)
                        downloaded++
                    } catch (_: Exception) {}
                } else {
                    // Note existed before our last sync but we don't have it locally -> we deleted it
                    // Delete from remote
                    val fileName = noteFiles.find { extractNoteId(it.name) == id }?.name
                    if (fileName != null) {
                        client.delete("$REMOTE_NOTES_DIR/$fileName")
                        // Also delete remote attachments
                        try {
                            val note = jsonToNote(json)
                            for (img in note.images) client.delete("$REMOTE_IMAGES_DIR/${img.localName}")
                            for (f in note.files) client.delete("$REMOTE_FILES_DIR/${f.localName}")
                            for (audio in note.audios) client.delete("$REMOTE_AUDIOS_DIR/${audio.name}")
                        } catch (_: Exception) {}
                        deletedRemote++
                        SyncLog.log("Deleted remote note $id (was deleted locally)")
                    }
                }
            }

            // Notes on both -> compare timestamps
            for (id in localIds.intersect(remoteIds)) {
                val local = localNoteMap[id]!!
                val remoteJson = remoteNoteMap[id]!!
                val remoteTimestamp = remoteJson.optLong("modifiedTimestamp", 0)

                if (local.modifiedTimestamp >= remoteTimestamp) {
                    uploadNote(client, local)
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

            cleanupOrphanedAttachments(client, localNotes)
            syncLabels(client)

            // Save synced note IDs
            val currentSyncedIds = localIds.toSet() + remoteIds
            uploadSyncMeta(client, currentSyncedIds)

            preferences.webdavLastSyncTime.save(System.currentTimeMillis())
            SyncLog.log("Sync complete: $uploaded uploaded, $downloaded downloaded, $deletedLocal deleted locally, $deletedRemote deleted remotely")
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
        json.put("viewMode", note.viewMode.name)
        json.put("isPinnedToStatus", note.isPinnedToStatus)
        json.put("locked", note.locked)
        return json.toString(2)
    }

    private fun jsonToNote(json: JSONObject): BaseNote {
        return BaseNote(
            id = json.getLong("id"),
            type = cool.hin.memox.data.model.Type.valueOf(json.getString("type")),
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
            viewMode = try {
                cool.hin.memox.data.model.NoteViewMode.valueOf(json.getString("viewMode"))
            } catch (_: Exception) {
                cool.hin.memox.data.model.NoteViewMode.EDIT
            },
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

    /** Upload all attachments for a note */
    private fun uploadAttachments(client: WebDavClient, note: BaseNote) {
        val imgCount = note.images.size
        val fileCount = note.files.size
        val audioCount = note.audios.size
        Log.d(TAG, "uploadAttachments: note ${note.id} has $imgCount images, $fileCount files, $audioCount audios")

        for (img in note.images) {
            val file = getLocalAttachmentFile(SUBFOLDER_IMAGES, img.localName)
            Log.d(TAG, "uploadAttachments: image ${img.localName}, file=$file, exists=${file?.exists()}")
            if (file != null && file.exists()) {
                val result = client.upload("$REMOTE_IMAGES_DIR/${img.localName}", file.readBytes())
                if (result.isFailure) {
                    Log.w(TAG, "uploadAttachments: failed to upload image ${img.localName}: ${result.exceptionOrNull()?.message}")
                }
            }
        }
        for (f in note.files) {
            val file = getLocalAttachmentFile(SUBFOLDER_FILES, f.localName)
            Log.d(TAG, "uploadAttachments: file ${f.localName}, localFile=$file, exists=${file?.exists()}")
            if (file != null && file.exists()) {
                val result = client.upload("$REMOTE_FILES_DIR/${f.localName}", file.readBytes())
                if (result.isFailure) {
                    Log.w(TAG, "uploadAttachments: failed to upload file ${f.localName}: ${result.exceptionOrNull()?.message}")
                }
            }
        }
        for (audio in note.audios) {
            val file = getLocalAttachmentFile(SUBFOLDER_AUDIOS, audio.name)
            Log.d(TAG, "uploadAttachments: audio ${audio.name}, file=$file, exists=${file?.exists()}")
            if (file != null && file.exists()) {
                val result = client.upload("$REMOTE_AUDIOS_DIR/${audio.name}", file.readBytes())
                if (result.isFailure) {
                    Log.w(TAG, "uploadAttachments: failed to upload audio ${audio.name}: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    /** Download all attachments for a note */
    private fun downloadAttachments(client: WebDavClient, note: BaseNote) {
        for (img in note.images) {
            val localFile = ensureLocalAttachmentFile(SUBFOLDER_IMAGES, img.localName)
            if (localFile != null) {
                val result = client.download("$REMOTE_IMAGES_DIR/${img.localName}")
                result.getOrNull()?.let { bytes ->
                    localFile.parentFile?.mkdirs()
                    localFile.writeBytes(bytes)
                }
            }
        }
        for (f in note.files) {
            val localFile = ensureLocalAttachmentFile(SUBFOLDER_FILES, f.localName)
            if (localFile != null) {
                val result = client.download("$REMOTE_FILES_DIR/${f.localName}")
                result.getOrNull()?.let { bytes ->
                    localFile.parentFile?.mkdirs()
                    localFile.writeBytes(bytes)
                }
            }
        }
        for (audio in note.audios) {
            val localFile = ensureLocalAttachmentFile(SUBFOLDER_AUDIOS, audio.name)
            if (localFile != null) {
                val result = client.download("$REMOTE_AUDIOS_DIR/${audio.name}")
                result.getOrNull()?.let { bytes ->
                    localFile.parentFile?.mkdirs()
                    localFile.writeBytes(bytes)
                }
            }
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

    /** Remove remote attachments that are no longer referenced by any note */
    private fun cleanupOrphanedAttachments(client: WebDavClient, allNotes: List<BaseNote>) {
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
        val files = client.listFiles(dir).getOrNull() ?: return
        for (file in files) {
            if (!file.isDirectory && file.name !in referencedNames) {
                client.delete(file.path)
                SyncLog.log("Deleted orphaned attachment: ${file.name}")
            }
        }
    }

    /** Delete a single note and its attachments from WebDAV */
    suspend fun deleteRemoteNote(note: BaseNote) = withContext(Dispatchers.IO) {
        val client = createClient() ?: return@withContext
        try {
            val fileName = noteFileName(note)
            client.delete("$REMOTE_NOTES_DIR/$fileName")
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
            SyncLog.log("Deleted remote note: $fileName")
        } catch (e: Exception) {
            Log.w(TAG, "deleteRemoteNote failed: ${e.message}")
        }
    }

    /** Delete multiple notes and their attachments from WebDAV */
    suspend fun deleteRemoteNotes(notes: Collection<BaseNote>) = withContext(Dispatchers.IO) {
        val client = createClient() ?: return@withContext
        try {
            for (note in notes) {
                val fileName = noteFileName(note)
                client.delete("$REMOTE_NOTES_DIR/$fileName")
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
            SyncLog.log("Deleted ${notes.size} remote notes")
        } catch (e: Exception) {
            Log.w(TAG, "deleteRemoteNotes failed: ${e.message}")
        }
    }

    /** Upload sync metadata including synced note IDs */
    private fun uploadSyncMeta(client: WebDavClient, noteIds: Set<Long>) {
        try {
            val json = JSONObject().apply {
                put("lastSyncTime", System.currentTimeMillis())
                put("noteCount", noteIds.size)
                put("appVersion", "1.0.4")
                val idsArray = JSONArray()
                for (id in noteIds.sorted()) {
                    idsArray.put(id)
                }
                put("syncedNoteIds", idsArray)
            }
            client.upload(REMOTE_SYNC_META, json.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {}
    }

    /** Download sync metadata to get previously synced note IDs */
    private fun getSyncedNoteIds(client: WebDavClient): Set<Long> {
        return try {
            val result = client.download(REMOTE_SYNC_META)
            val bytes = result.getOrNull() ?: return emptySet()
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val idsArray = json.optJSONArray("syncedNoteIds") ?: return emptySet()
            val ids = mutableSetOf<Long>()
            for (i in 0 until idsArray.length()) {
                ids.add(idsArray.getLong(i))
            }
            ids
        } catch (_: Exception) {
            emptySet()
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
            val bytes = result.getOrNull()

            if (bytes == null) {
                // No remote labels file, upload local
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

            // Merge hidden labels: union of local and remote hidden
            val mergedHidden = localHidden + remoteHidden
            preferences.labelsHidden.save(mergedHidden)

            // Upload merged result
            uploadLabels(client)

            Log.d(TAG, "syncLabels: local=${localLabels.size}, remote=${remoteLabels.size}, merged=${mergedLabels.size}")
        } catch (e: Exception) {
            Log.w(TAG, "syncLabels: failed: ${e.message}")
        }
    }
}
