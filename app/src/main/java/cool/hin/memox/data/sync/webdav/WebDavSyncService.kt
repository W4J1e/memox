package cool.hin.memox.data.sync.webdav

import android.content.ContextWrapper
import android.util.Log
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Converters
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.sync.SyncLog
import cool.hin.memox.data.sync.SyncResult
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import cool.hin.memox.utils.SUBFOLDER_AUDIOS
import cool.hin.memox.utils.SUBFOLDER_FILES
import cool.hin.memox.utils.SUBFOLDER_IMAGES
import cool.hin.memox.utils.resolveAttachmentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles synchronization with a WebDAV server.
 *
 * Strategy:
 * - Each note is stored as a separate JSON file: memoX/notes/{id}.json
 * - Attachments are stored in: memoX/attachments/images/, memoX/attachments/audios/, memoX/attachments/files/
 * - Upload: Compare local notes with remote, upload new/modified notes and their attachments,
 *   delete remote notes that no longer exist locally
 * - Download: Compare remote notes with local, download new/modified notes and their attachments,
 *   delete local notes that no longer exist remotely
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
                // Try to create the memoX directories
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

    /** Upload current data to WebDAV (full upload of all notes) */
    suspend fun upload(): SyncResult = withContext(Dispatchers.IO) {
        val client = createClient()
            ?: return@withContext SyncResult.Error("WebDAV not configured")

        try {
            SyncLog.log("Starting WebDAV upload...")

            // Ensure remote directories exist
            ensureRemoteDirs(client)

            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val dao = database.getBaseNoteDao()
            val allNotes = dao.getAllNotes()

            SyncLog.log("Found ${allNotes.size} notes to upload")

            // Get list of existing remote note files
            val remoteFiles = client.listFiles(REMOTE_NOTES_DIR).getOrNull() ?: emptyList()
            val remoteNoteIds = remoteFiles
                .filter { !it.isDirectory && it.name.endsWith(".json") }
                .mapNotNull { it.name.removeSuffix(".json").toLongOrNull() }
                .toMutableSet()

            var uploaded = 0
            var failed = 0

            // Upload each note as a JSON file
            for (note in allNotes) {
                val json = noteToJson(note)
                val path = "$REMOTE_NOTES_DIR/${note.id}.json"
                val result = client.upload(path, json.toByteArray(Charsets.UTF_8))
                if (result.isSuccess) {
                    uploaded++
                    remoteNoteIds.remove(note.id)

                    // Upload attachments for this note
                    uploadAttachments(client, note)
                } else {
                    failed++
                    SyncLog.log("Failed to upload note ${note.id}: ${result.exceptionOrNull()?.message}")
                }
            }

            // Delete remote notes that no longer exist locally
            for (remoteId in remoteNoteIds) {
                val path = "$REMOTE_NOTES_DIR/$remoteId.json"
                client.delete(path)
                SyncLog.log("Deleted remote note $remoteId")
            }

            // Clean up orphaned remote attachments
            cleanupOrphanedAttachments(client, allNotes)

            // Update sync metadata
            uploadSyncMeta(client, allNotes.size)

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

            // Get local note IDs for cleanup
            val localIds = dao.getAllIds().toSet()

            var downloaded = 0
            var failed = 0
            val remoteIds = mutableSetOf<Long>()

            for (file in noteFiles) {
                val noteId = file.name.removeSuffix(".json").toLongOrNull()
                if (noteId == null) continue
                remoteIds.add(noteId)

                val path = "$REMOTE_NOTES_DIR/${file.name}"
                val result = client.download(path)
                result.fold(
                    onSuccess = { bytes ->
                        try {
                            val json = JSONObject(String(bytes, Charsets.UTF_8))
                            val note = jsonToNote(json)

                            // Download attachments for this note
                            downloadAttachments(client, note)

                            // Insert or update note (use insertSafe for truncation handling)
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

            preferences.webdavLastSyncTime.save(System.currentTimeMillis())
            SyncLog.log("Download complete: $downloaded downloaded, $failed failed")
            SyncResult.Success("Download complete: $downloaded notes downloaded")
        } catch (e: Exception) {
            SyncLog.log("Download error: ${e.message}")
            SyncResult.Error(e.message ?: "Download failed")
        }
    }

    /** Two-way sync: upload local changes, download remote changes */
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

            // Get remote note list
            val remoteFiles = client.listFiles(REMOTE_NOTES_DIR).getOrNull() ?: emptyList()
            val noteFiles = remoteFiles.filter { !it.isDirectory && it.name.endsWith(".json") }

            val remoteNoteMap = mutableMapOf<Long, JSONObject>()
            for (file in noteFiles) {
                val noteId = file.name.removeSuffix(".json").toLongOrNull() ?: continue
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

            // Notes only on local -> upload
            for (id in localIds - remoteIds) {
                val note = localNoteMap[id]!!
                uploadNote(client, note)
                uploaded++
            }

            // Notes only on remote -> download
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
                val local = localNoteMap[id]!!
                val remoteJson = remoteNoteMap[id]!!
                val remoteTimestamp = remoteJson.optLong("modifiedTimestamp", 0)

                if (local.modifiedTimestamp >= remoteTimestamp) {
                    // Local is newer or same -> upload
                    uploadNote(client, local)
                    uploaded++
                } else {
                    // Remote is newer -> download
                    try {
                        val note = jsonToNote(remoteJson)
                        downloadAttachments(client, note)
                        dao.insertSafe(context, note)
                        downloaded++
                    } catch (_: Exception) {}
                }
            }

            // Delete remote notes that no longer exist locally (only if local has been synced before)
            val lastSyncTime = preferences.webdavLastSyncTime.value
            if (lastSyncTime > 0) {
                for (id in remoteIds - localIds) {
                    // Only delete if the note was deleted locally after last sync
                    // For safety, we don't delete remote-only notes during sync
                    // (they might have been created on another device)
                }
            }

            // Delete local notes that no longer exist remotely
            // For safety, we don't auto-delete during two-way sync
            // Users can use "download" to force a full replace

            // Clean up orphaned attachments
            cleanupOrphanedAttachments(client, localNotes)

            // Update sync metadata
            uploadSyncMeta(client, localNotes.size)

            preferences.webdavLastSyncTime.save(System.currentTimeMillis())
            SyncLog.log("Sync complete: $uploaded uploaded, $downloaded downloaded")
            SyncResult.Success("Sync complete: $uploaded up, $downloaded down")
        } catch (e: Exception) {
            SyncLog.log("Sync error: ${e.message}")
            SyncResult.Error(e.message ?: "Sync failed")
        }
    }

    private suspend fun ensureRemoteDirs(client: WebDavClient) {
        client.createDirectory(REMOTE_DIR)
        client.createDirectory(REMOTE_NOTES_DIR)
        client.createDirectory(REMOTE_IMAGES_DIR)
        client.createDirectory(REMOTE_AUDIOS_DIR)
        client.createDirectory(REMOTE_FILES_DIR)
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
        val path = "$REMOTE_NOTES_DIR/${note.id}.json"
        val result = client.upload(path, json.toByteArray(Charsets.UTF_8))
        if (result.isSuccess) {
            uploadAttachments(client, note)
        }
        return result
    }

    /** Upload all attachments for a note */
    private fun uploadAttachments(client: WebDavClient, note: BaseNote) {
        // Upload images
        for (img in note.images) {
            val file = context.resolveAttachmentFile(SUBFOLDER_IMAGES, img.localName)
            if (file != null && file.exists()) {
                client.upload("$REMOTE_IMAGES_DIR/${img.localName}", file.readBytes())
            }
        }
        // Upload files
        for (f in note.files) {
            val file = context.resolveAttachmentFile(SUBFOLDER_FILES, f.localName)
            if (file != null && file.exists()) {
                client.upload("$REMOTE_FILES_DIR/${f.localName}", file.readBytes())
            }
        }
        // Upload audios
        for (audio in note.audios) {
            val file = context.resolveAttachmentFile(SUBFOLDER_AUDIOS, audio.name)
            if (file != null && file.exists()) {
                client.upload("$REMOTE_AUDIOS_DIR/${audio.name}", file.readBytes())
            }
        }
    }

    /** Download all attachments for a note */
    private fun downloadAttachments(client: WebDavClient, note: BaseNote) {
        // Download images
        for (img in note.images) {
            val localFile = context.resolveAttachmentFile(SUBFOLDER_IMAGES, img.localName)
            if (localFile != null && !localFile.exists()) {
                val result = client.download("$REMOTE_IMAGES_DIR/${img.localName}")
                result.getOrNull()?.let { bytes ->
                    localFile.parentFile?.mkdirs()
                    localFile.writeBytes(bytes)
                }
            }
        }
        // Download files
        for (f in note.files) {
            val localFile = context.resolveAttachmentFile(SUBFOLDER_FILES, f.localName)
            if (localFile != null && !localFile.exists()) {
                val result = client.download("$REMOTE_FILES_DIR/${f.localName}")
                result.getOrNull()?.let { bytes ->
                    localFile.parentFile?.mkdirs()
                    localFile.writeBytes(bytes)
                }
            }
        }
        // Download audios
        for (audio in note.audios) {
            val localFile = context.resolveAttachmentFile(SUBFOLDER_AUDIOS, audio.name)
            if (localFile != null && !localFile.exists()) {
                val result = client.download("$REMOTE_AUDIOS_DIR/${audio.name}")
                result.getOrNull()?.let { bytes ->
                    localFile.parentFile?.mkdirs()
                    localFile.writeBytes(bytes)
                }
            }
        }
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

    /** Upload sync metadata */
    private fun uploadSyncMeta(client: WebDavClient, noteCount: Int) {
        try {
            val json = JSONObject().apply {
                put("lastSyncTime", System.currentTimeMillis())
                put("noteCount", noteCount)
                put("appVersion", "1.0.2")
            }
            client.upload(REMOTE_SYNC_META, json.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {}
    }
}
