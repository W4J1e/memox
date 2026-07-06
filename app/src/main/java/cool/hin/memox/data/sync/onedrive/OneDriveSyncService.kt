package cool.hin.memox.data.sync.onedrive

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
 * Handles synchronization with OneDrive via the Microsoft Graph API.
 *
 * The remote layout and sync algorithm mirror [WebDavSyncService] so that the
 * behaviour is identical, only the underlying transport differs:
 * - Each note is stored as a separate JSON file: memoX/notes/{title}_{id}.json
 * - Attachments are stored in: memoX/attachments/{images,files,audios}/
 * - Two-way sync based on modifiedTimestamp, with a tombstone list in sync_meta.json
 */
class OneDriveSyncService(private val context: ContextWrapper) {

    companion object {
        private const val TAG = "OneDriveSync"
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
            name.toLongOrNull()?.let { return it }
            val lastUnderscore = name.lastIndexOf('_')
            if (lastUnderscore > 0) {
                return name.substring(lastUnderscore + 1).toLongOrNull()
            }
            return null
        }
    }

    private val preferences: MemoXPreferences by lazy { MemoXPreferences.getInstance(context) }

    private fun createClient(): OneDriveClient? {
        if (!OneDriveAuthHelper.isLoggedIn(context)) return null
        return OneDriveClient(context)
    }

    /** Test the OneDrive connection by ensuring the user is signed in. */
    suspend fun testConnection(): SyncResult = withContext(Dispatchers.IO) {
        val client = createClient()
            ?: return@withContext SyncResult.Error("OneDrive not signed in")

        client.testConnection().fold(
            onSuccess = {
                ensureRemoteDirs(client)
                SyncResult.Success("Connection successful")
            },
            onFailure = { SyncResult.Error(it.message ?: "Connection failed") }
        )
    }

    /** Upload current data to OneDrive. */
    suspend fun upload(): SyncResult = withContext(Dispatchers.IO) {
        val client = createClient()
            ?: return@withContext SyncResult.Error("OneDrive not signed in")

        try {
            SyncLog.log("Starting OneDrive upload...")
            ensureRemoteDirs(client)

            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val dao = database.getBaseNoteDao()
            val allNotes = dao.getAllNotes()

            SyncLog.log("Found ${allNotes.size} notes to upload")

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

            for (note in allNotes) {
                val json = noteToJson(note)
                val newFileName = noteFileName(note)
                val path = "$REMOTE_NOTES_DIR/$newFileName"
                val result = client.upload(path, json.toByteArray(Charsets.UTF_8))
                if (result.isSuccess) {
                    uploaded++
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

            for ((_, fileNames) in remoteNoteIdToFileNames) {
                for (fileName in fileNames) {
                    client.delete("$REMOTE_NOTES_DIR/$fileName")
                    SyncLog.log("Deleted remote note: $fileName")
                }
            }

            cleanupOrphanedAttachments(client, allNotes)
            uploadLabels(client)
            val tombstones = preferences.onedriveDeletedNoteIds.value
                .mapNotNull { it.toLongOrNull() }
                .toSet()
            uploadSyncMeta(client, allNotes.map { it.id }.toSet(), tombstones)

            preferences.onedriveLastSyncTime.save(System.currentTimeMillis())
            SyncLog.log("Upload complete: $uploaded uploaded, $failed failed out of ${allNotes.size}")
            val msg = if (failed > 0) {
                "Upload: $uploaded/${allNotes.size} notes, $failed failed"
            } else {
                "Upload complete: $uploaded notes uploaded"
            }
            SyncResult.Success(msg)
        } catch (e: Exception) {
            SyncLog.log("Upload error: ${e.message}")
            SyncResult.Error(e.message ?: "Upload failed")
        }
    }

    /** Download all notes from OneDrive. */
    suspend fun download(): SyncResult = withContext(Dispatchers.IO) {
        val client = createClient()
            ?: return@withContext SyncResult.Error("OneDrive not signed in")

        try {
            SyncLog.log("Starting OneDrive download...")
            ensureRemoteDirs(client)

            val remoteFiles = client.listFiles(REMOTE_NOTES_DIR).getOrNull() ?: emptyList()
            val noteFiles = remoteFiles.filter { !it.isDirectory && it.name.endsWith(".json") }

            if (noteFiles.isEmpty()) {
                SyncLog.log("No remote notes found")
                return@withContext SyncResult.Error("No notes found on OneDrive")
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

            val deletedIds = localIds - remoteIds
            if (deletedIds.isNotEmpty()) {
                dao.delete(deletedIds.toLongArray())
                SyncLog.log("Deleted ${deletedIds.size} local notes not on OneDrive")
            }

            downloadLabels(client)

            preferences.onedriveLastSyncTime.save(System.currentTimeMillis())
            SyncLog.log("Download complete: $downloaded downloaded, $failed failed")
            SyncResult.Success("Download complete: $downloaded notes downloaded")
        } catch (e: Exception) {
            SyncLog.log("Download error: ${e.message}")
            SyncResult.Error(e.message ?: "Download failed")
        }
    }

    /** Two-way sync: upload local changes, download remote changes, handle deletions via tombstones. */
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val client = createClient()
            ?: return@withContext SyncResult.Error("OneDrive not signed in")

        try {
            SyncLog.log("Starting OneDrive two-way sync...")
            ensureRemoteDirs(client)

            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val dao = database.getBaseNoteDao()
            val localNotes = dao.getAllNotes()
            val localNoteMap = localNotes.associateBy { it.id }

            val remoteMeta = downloadSyncMeta(client)
            val remoteTombstones = remoteMeta?.deletedNoteIds?.toMutableSet() ?: mutableSetOf()

            val localTombstones = preferences.onedriveDeletedNoteIds.value
                .mapNotNull { it.toLongOrNull() }
                .toMutableSet()

            val mergedTombstones = (localTombstones + remoteTombstones).toMutableSet()

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

            val updatedLocalNotes = dao.getAllNotes()
            val updatedLocalNoteMap = updatedLocalNotes.associateBy { it.id }

            val remoteFiles = client.listFiles(REMOTE_NOTES_DIR).getOrNull() ?: emptyList()
            val noteFiles = remoteFiles.filter { !it.isDirectory && it.name.endsWith(".json") }

            val remoteNoteIdToFileNames = mutableMapOf<Long, MutableList<String>>()
            for (file in noteFiles) {
                val noteId = extractNoteId(file.name) ?: continue
                remoteNoteIdToFileNames.getOrPut(noteId) { mutableListOf() }.add(file.name)
            }

            val remoteNoteMap = mutableMapOf<Long, JSONObject>()
            for ((noteId, fileNames) in remoteNoteIdToFileNames) {
                if (noteId in mergedTombstones) continue
                val fileName = fileNames.last()
                val result = client.download("$REMOTE_NOTES_DIR/$fileName")
                result.getOrNull()?.let { bytes ->
                    try {
                        remoteNoteMap[noteId] = JSONObject(String(bytes, Charsets.UTF_8))
                    } catch (_: Exception) {}
                }
                if (fileNames.size > 1) {
                    for (i in 0 until fileNames.size - 1) {
                        client.delete("$REMOTE_NOTES_DIR/${fileNames[i]}")
                        SyncLog.log("Deleted duplicate remote file: ${fileNames[i]} for note $noteId")
                    }
                }
            }

            var deletedRemote = 0
            for (id in mergedTombstones) {
                val fileNames = remoteNoteIdToFileNames.remove(id)
                if (fileNames != null) {
                    for (fileName in fileNames) {
                        client.delete("$REMOTE_NOTES_DIR/$fileName")
                    }
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

            for (id in localIds - remoteIds) {
                val note = updatedLocalNoteMap[id]!!
                uploadNote(client, note)
                uploaded++
            }

            for (id in remoteIds - localIds) {
                val json = remoteNoteMap[id]!!
                try {
                    val note = jsonToNote(json)
                    downloadAttachments(client, note)
                    dao.insertSafe(context, note)
                    downloaded++
                } catch (_: Exception) {}
            }

            for (id in localIds.intersect(remoteIds)) {
                val local = updatedLocalNoteMap[id]!!
                val remoteJson = remoteNoteMap[id]!!
                val remoteTimestamp = remoteJson.optLong("modifiedTimestamp", 0)

                if (local.modifiedTimestamp >= remoteTimestamp) {
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

            cleanupOrphanedAttachments(client, updatedLocalNotes)
            syncLabels(client)

            val currentNoteIds = localIds + remoteIds
            mergedTombstones.retainAll { it !in currentNoteIds }

            preferences.onedriveDeletedNoteIds.save(mergedTombstones.map { it.toString() }.toSet())
            uploadSyncMeta(client, updatedLocalNotes.map { it.id }.toSet(), mergedTombstones.toSet())

            preferences.onedriveLastSyncTime.save(System.currentTimeMillis())
            SyncLog.log("Sync complete: $uploaded uploaded, $downloaded downloaded, $deletedLocal deleted locally, $deletedRemote deleted remotely, ${mergedTombstones.size} tombstones")
            SyncResult.Success("Sync complete: $uploaded up, $downloaded down, $deletedLocal local del, $deletedRemote remote del")
        } catch (e: Exception) {
            SyncLog.log("Sync error: ${e.message}")
            SyncResult.Error(e.message ?: "Sync failed")
        }
    }

    private suspend fun ensureRemoteDirs(client: OneDriveClient) {
        val dirs = listOf(
            REMOTE_DIR,
            REMOTE_NOTES_DIR,
            "memoX/attachments",
            REMOTE_IMAGES_DIR,
            REMOTE_AUDIOS_DIR,
            REMOTE_FILES_DIR,
        )
        for (dir in dirs) {
            val result = client.createDirectory(dir)
            if (result.isFailure) {
                SyncLog.log("Failed to create remote dir '$dir': ${result.exceptionOrNull()?.message}")
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

    private suspend fun uploadNote(client: OneDriveClient, note: BaseNote): Result<Unit> {
        val json = noteToJson(note)
        val path = "$REMOTE_NOTES_DIR/${noteFileName(note)}"
        val result = client.upload(path, json.toByteArray(Charsets.UTF_8))
        if (result.isSuccess) {
            uploadAttachments(client, note)
        }
        return result
    }

    private suspend fun uploadAttachments(client: OneDriveClient, note: BaseNote) {
        for (img in note.images) {
            val file = getLocalAttachmentFile(SUBFOLDER_IMAGES, img.localName)
            if (file != null && file.exists()) {
                client.upload("$REMOTE_IMAGES_DIR/${img.localName}", file.readBytes())
            }
        }
        for (f in note.files) {
            val file = getLocalAttachmentFile(SUBFOLDER_FILES, f.localName)
            if (file != null && file.exists()) {
                client.upload("$REMOTE_FILES_DIR/${f.localName}", file.readBytes())
            }
        }
        for (audio in note.audios) {
            val file = getLocalAttachmentFile(SUBFOLDER_AUDIOS, audio.name)
            if (file != null && file.exists()) {
                client.upload("$REMOTE_AUDIOS_DIR/${audio.name}", file.readBytes())
            }
        }
    }

    private suspend fun downloadAttachments(client: OneDriveClient, note: BaseNote) {
        for (img in note.images) {
            val localFile = ensureLocalAttachmentFile(SUBFOLDER_IMAGES, img.localName)
            if (localFile != null) {
                client.download("$REMOTE_IMAGES_DIR/${img.localName}").getOrNull()?.let { bytes ->
                    localFile.parentFile?.mkdirs()
                    localFile.writeBytes(bytes)
                }
            }
        }
        for (f in note.files) {
            val localFile = ensureLocalAttachmentFile(SUBFOLDER_FILES, f.localName)
            if (localFile != null) {
                client.download("$REMOTE_FILES_DIR/${f.localName}").getOrNull()?.let { bytes ->
                    localFile.parentFile?.mkdirs()
                    localFile.writeBytes(bytes)
                }
            }
        }
        for (audio in note.audios) {
            val localFile = ensureLocalAttachmentFile(SUBFOLDER_AUDIOS, audio.name)
            if (localFile != null) {
                client.download("$REMOTE_AUDIOS_DIR/${audio.name}").getOrNull()?.let { bytes ->
                    localFile.parentFile?.mkdirs()
                    localFile.writeBytes(bytes)
                }
            }
        }
    }

    private fun getLocalAttachmentFile(subfolder: String, localName: String): File? {
        return context.resolveAttachmentFile(subfolder, localName)
    }

    private fun ensureLocalAttachmentFile(subfolder: String, localName: String): File? {
        val dir = when (subfolder) {
            SUBFOLDER_IMAGES -> context.getCurrentImagesDirectory()
            SUBFOLDER_FILES -> context.getCurrentFilesDirectory()
            SUBFOLDER_AUDIOS -> context.getCurrentAudioDirectory()
            else -> return null
        }
        return File(dir, localName)
    }

    private suspend fun cleanupOrphanedAttachments(client: OneDriveClient, allNotes: List<BaseNote>) {
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

    private suspend fun cleanupOrphanedDir(client: OneDriveClient, dir: String, referencedNames: Set<String>) {
        val files = client.listFiles(dir).getOrNull() ?: return
        for (file in files) {
            if (!file.isDirectory && file.name !in referencedNames) {
                client.delete(file.path)
                SyncLog.log("Deleted orphaned attachment: ${file.name}")
            }
        }
    }

    suspend fun deleteRemoteNote(note: BaseNote) = withContext(Dispatchers.IO) {
        val client = createClient() ?: return@withContext
        try {
            val currentFileName = noteFileName(note)
            val idOnlyFileName = "${note.id}.json"
            client.delete("$REMOTE_NOTES_DIR/$currentFileName")
            if (currentFileName != idOnlyFileName) {
                client.delete("$REMOTE_NOTES_DIR/$idOnlyFileName")
            }
            val remoteFiles = client.listFiles(REMOTE_NOTES_DIR).getOrNull() ?: emptyList()
            for (file in remoteFiles) {
                if (!file.isDirectory && file.name.endsWith(".json")) {
                    if (extractNoteId(file.name) == note.id && file.name != currentFileName && file.name != idOnlyFileName) {
                        client.delete("$REMOTE_NOTES_DIR/${file.name}")
                    }
                }
            }
            for (img in note.images) client.delete("$REMOTE_IMAGES_DIR/${img.localName}")
            for (f in note.files) client.delete("$REMOTE_FILES_DIR/${f.localName}")
            for (audio in note.audios) client.delete("$REMOTE_AUDIOS_DIR/${audio.name}")
            addTombstone(client, note.id)
            SyncLog.log("Deleted remote note: $currentFileName (and any duplicate files)")
        } catch (e: Exception) {
            Log.w(TAG, "deleteRemoteNote failed: ${e.message}")
        }
    }

    suspend fun deleteRemoteNotes(notes: Collection<BaseNote>) = withContext(Dispatchers.IO) {
        val client = createClient() ?: return@withContext
        try {
            val remoteFiles = client.listFiles(REMOTE_NOTES_DIR).getOrNull() ?: emptyList()

            for (note in notes) {
                val currentFileName = noteFileName(note)
                val idOnlyFileName = "${note.id}.json"
                client.delete("$REMOTE_NOTES_DIR/$currentFileName")
                if (currentFileName != idOnlyFileName) {
                    client.delete("$REMOTE_NOTES_DIR/$idOnlyFileName")
                }
                for (file in remoteFiles) {
                    if (!file.isDirectory && file.name.endsWith(".json")) {
                        if (extractNoteId(file.name) == note.id && file.name != currentFileName && file.name != idOnlyFileName) {
                            client.delete("$REMOTE_NOTES_DIR/${file.name}")
                        }
                    }
                }
                for (img in note.images) client.delete("$REMOTE_IMAGES_DIR/${img.localName}")
                for (f in note.files) client.delete("$REMOTE_FILES_DIR/${f.localName}")
                for (audio in note.audios) client.delete("$REMOTE_AUDIOS_DIR/${audio.name}")
            }
            addTombstones(client, notes.map { it.id }.toSet())
            SyncLog.log("Deleted ${notes.size} remote notes")
        } catch (e: Exception) {
            Log.w(TAG, "deleteRemoteNotes failed: ${e.message}")
        }
    }

    private suspend fun addTombstone(client: OneDriveClient, noteId: Long) {
        addTombstones(client, setOf(noteId))
    }

    private suspend fun addTombstones(client: OneDriveClient, noteIds: Set<Long>) {
        val currentTombstones = preferences.onedriveDeletedNoteIds.value
            .mapNotNull { it.toLongOrNull() }
            .toMutableSet()
        currentTombstones.addAll(noteIds)
        preferences.onedriveDeletedNoteIds.save(currentTombstones.map { it.toString() }.toSet())

        try {
            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val dao = database.getBaseNoteDao()
            val localIds = dao.getAllIds().toSet()
            uploadSyncMeta(client, localIds, currentTombstones)
        } catch (_: Exception) {}
    }

    private suspend fun uploadSyncMeta(client: OneDriveClient, noteIds: Set<Long>, tombstones: Set<Long>) {
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

    private data class SyncMeta(
        val syncedNoteIds: Set<Long>,
        val deletedNoteIds: Set<Long>,
    )

    private suspend fun downloadSyncMeta(client: OneDriveClient): SyncMeta? {
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

    private suspend fun uploadLabels(client: OneDriveClient) {
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
        } catch (e: Exception) {
            Log.w(TAG, "uploadLabels: failed: ${e.message}")
        }
    }

    private suspend fun downloadLabels(client: OneDriveClient) {
        try {
            val result = client.download(REMOTE_LABELS_FILE)
            val bytes = result.getOrNull() ?: return
            val json = JSONObject(String(bytes, Charsets.UTF_8))

            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val labelDao = database.getLabelDao()

            val labelsArray = json.optJSONArray("labels") ?: return
            val labels = mutableListOf<Label>()
            for (i in 0 until labelsArray.length()) {
                val value = labelsArray.getString(i)
                labels.add(Label(value, i))
            }

            labelDao.deleteAll()
            labelDao.insert(labels)

            val hiddenArray = json.optJSONArray("hiddenLabels")
            if (hiddenArray != null) {
                val hiddenSet = mutableSetOf<String>()
                for (i in 0 until hiddenArray.length()) {
                    hiddenSet.add(hiddenArray.getString(i))
                }
                preferences.labelsHidden.save(hiddenSet)
            }
        } catch (e: Exception) {
            Log.w(TAG, "downloadLabels: failed: ${e.message}")
        }
    }

    private suspend fun syncLabels(client: OneDriveClient) {
        try {
            val database = MemoXDatabase.getDatabase(context, observePreferences = false).value
            val labelDao = database.getLabelDao()
            val localLabels = labelDao.getArrayOfAll().toSet()
            val localHidden = preferences.labelsHidden.value

            val result = client.download(REMOTE_LABELS_FILE)
            val bytes = result.getOrNull()

            if (bytes == null) {
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

            val mergedLabels = localLabels + remoteLabels
            val maxOrder = labelDao.getMaxOrder() ?: -1

            val toInsert = mergedLabels - localLabels
            for ((index, value) in toInsert.withIndex()) {
                labelDao.insert(Label(value, maxOrder + 1 + index))
            }

            val mergedHidden = localHidden + remoteHidden
            preferences.labelsHidden.save(mergedHidden)

            uploadLabels(client)
        } catch (e: Exception) {
            Log.w(TAG, "syncLabels: failed: ${e.message}")
        }
    }
}
