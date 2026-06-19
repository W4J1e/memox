package cool.hin.memox.data.sync.webdav

import android.content.ContextWrapper
import android.util.Log
import cool.hin.memox.data.MemoXDatabase.Companion.DATABASE_NAME
import cool.hin.memox.data.model.Converters
import cool.hin.memox.data.sync.SyncLog
import cool.hin.memox.data.sync.SyncResult
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import cool.hin.memox.utils.SUBFOLDER_AUDIOS
import cool.hin.memox.utils.SUBFOLDER_FILES
import cool.hin.memox.utils.SUBFOLDER_IMAGES
import cool.hin.memox.utils.backup.copyDatabase
import cool.hin.memox.utils.resolveAttachmentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionLevel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles synchronization with a WebDAV server.
 *
 * Strategy:
 * - Upload: Create a ZIP backup (database + attachments) and upload to WebDAV
 * - Download: Download ZIP from WebDAV and import (reusing existing import logic)
 * - Conflict: Keep both versions (server version renamed, local version uploaded)
 */
class WebDavSyncService(private val context: ContextWrapper) {

    companion object {
        private const val TAG = "WebDavSync"
        const val REMOTE_DIR = "memoX"
        const val REMOTE_BACKUP_PREFIX = "MemoX_Backup_"
        const val REMOTE_LATEST = "MemoX_LatestBackup.zip"
        const val MAX_REMOTE_BACKUPS = 5
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
                // Try to create the memoX directory
                client.createDirectory(REMOTE_DIR)
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

            // Ensure remote directory exists
            client.createDirectory(REMOTE_DIR)

            // Create local backup ZIP (database + attachments)
            val backupFile = createLocalBackupZip() ?: run {
                SyncLog.log("Failed to create local backup")
                return@withContext SyncResult.Error("Failed to create backup")
            }

            // Upload as latest backup
            val bytes = backupFile.readBytes()
            SyncLog.log("Uploading ${bytes.size} bytes to $REMOTE_DIR/$REMOTE_LATEST")

            client.upload("$REMOTE_DIR/$REMOTE_LATEST", bytes).fold(
                onSuccess = {
                    // Also upload as timestamped backup
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val timestampedName = "${REMOTE_BACKUP_PREFIX}${timestamp}.zip"
                    client.upload("$REMOTE_DIR/$timestampedName", bytes)

                    // Clean up old remote backups
                    cleanupOldBackups(client)

                    SyncLog.log("Upload successful")
                    preferences.webdavLastSyncTime.save(System.currentTimeMillis())
                    SyncResult.Success("Upload successful")
                },
                onFailure = { e ->
                    SyncLog.log("Upload failed: ${e.message}")
                    SyncResult.Error(e.message ?: "Upload failed")
                }
            )
        } catch (e: Exception) {
            SyncLog.log("Upload error: ${e.message}")
            SyncResult.Error(e.message ?: "Upload failed")
        }
    }

    /** Download backup from WebDAV and save to temp file for import */
    suspend fun download(): SyncResult = withContext(Dispatchers.IO) {
        val client = createClient()
            ?: return@withContext SyncResult.Error("WebDAV not configured")

        try {
            SyncLog.log("Starting WebDAV download...")

            val result = client.download("$REMOTE_DIR/$REMOTE_LATEST")
            result.fold(
                onSuccess = { bytes ->
                    if (bytes.isEmpty()) {
                        SyncLog.log("Downloaded file is empty")
                        return@withContext SyncResult.Error("Remote backup is empty")
                    }

                    SyncLog.log("Downloaded ${bytes.size} bytes")

                    // Save to temp file
                    val tempFile = File(context.cacheDir, "webdav_download.zip")
                    if (tempFile.exists()) tempFile.delete()
                    tempFile.writeBytes(bytes)

                    SyncLog.log("Download successful, ready to import")
                    preferences.webdavLastSyncTime.save(System.currentTimeMillis())
                    SyncResult.DownloadReady(tempFile.absolutePath)
                },
                onFailure = { e ->
                    SyncLog.log("Download failed: ${e.message}")
                    SyncResult.Error(e.message ?: "Download failed")
                }
            )
        } catch (e: Exception) {
            SyncLog.log("Download error: ${e.message}")
            SyncResult.Error(e.message ?: "Download failed")
        }
    }

    /** Full sync: upload local data first, then download remote for import */
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val client = createClient()
            ?: return@withContext SyncResult.Error("WebDAV not configured")

        try {
            SyncLog.log("Starting WebDAV sync...")

            // Step 1: Ensure remote directory exists
            client.createDirectory(REMOTE_DIR)

            // Step 2: Check if remote backup exists
            val remoteExists = client.exists("$REMOTE_DIR/$REMOTE_LATEST")
            val lastSyncTime = preferences.webdavLastSyncTime.value

            if (!remoteExists || lastSyncTime == 0L) {
                // No remote backup or never synced before - just upload
                SyncLog.log("No remote backup found, uploading...")
                return@withContext upload()
            }

            // Step 3: Download remote backup first (to preserve remote data)
            val downloadResult = client.download("$REMOTE_DIR/$REMOTE_LATEST")
            val remoteBytes = downloadResult.getOrNull() ?: run {
                SyncLog.log("Failed to download remote backup, uploading local instead")
                return@withContext upload()
            }

            // Step 4: Upload local data (overwriting remote)
            val uploadResult = upload()
            if (uploadResult is SyncResult.Error) {
                SyncLog.log("Upload failed during sync: ${uploadResult.message}")
            }

            // Step 5: Save remote backup for import
            val tempFile = File(context.cacheDir, "webdav_sync_remote.zip")
            if (tempFile.exists()) tempFile.delete()
            tempFile.writeBytes(remoteBytes)

            SyncLog.log("Sync complete - remote backup ready for import")
            SyncResult.DownloadReady(tempFile.absolutePath)
        } catch (e: Exception) {
            SyncLog.log("Sync error: ${e.message}")
            SyncResult.Error(e.message ?: "Sync failed")
        }
    }

    /** Create a complete local backup ZIP file (database + all attachments) */
    private fun createLocalBackupZip(): File? {
        return try {
            val tempFile = File(context.cacheDir, "webdav_upload.zip")
            if (tempFile.exists()) tempFile.delete()

            // Use existing copyDatabase() which handles checkpoint, decryption, etc.
            val (database, databaseCopy) = context.copyDatabase()

            val zipFile = ZipFile(tempFile)
            val zipParameters = ZipParameters().apply {
                compressionLevel = CompressionLevel.NO_COMPRESSION
            }

            // Add database file
            zipFile.addFile(databaseCopy, zipParameters.copy(DATABASE_NAME))

            // Add all image attachments
            val images = database.getBaseNoteDao().getAllImages()
            for (json in images) {
                for (file in Converters.jsonToFiles(json)) {
                    val attachment = context.resolveAttachmentFile(SUBFOLDER_IMAGES, file.localName)
                    if (attachment != null && attachment.exists()) {
                        zipFile.addFile(attachment, zipParameters.copy("$SUBFOLDER_IMAGES/${file.localName}"))
                    }
                }
            }

            // Add all file attachments
            val files = database.getBaseNoteDao().getAllFiles()
            for (json in files) {
                for (file in Converters.jsonToFiles(json)) {
                    val attachment = context.resolveAttachmentFile(SUBFOLDER_FILES, file.localName)
                    if (attachment != null && attachment.exists()) {
                        zipFile.addFile(attachment, zipParameters.copy("$SUBFOLDER_FILES/${file.localName}"))
                    }
                }
            }

            // Add all audio attachments
            val audios = database.getBaseNoteDao().getAllAudios()
            for (json in audios) {
                for (audio in Converters.jsonToAudios(json)) {
                    val attachment = context.resolveAttachmentFile(SUBFOLDER_AUDIOS, audio.name)
                    if (attachment != null && attachment.exists()) {
                        zipFile.addFile(attachment, zipParameters.copy("$SUBFOLDER_AUDIOS/${audio.name}"))
                    }
                }
            }

            // Clean up temp database copy
            databaseCopy.delete()

            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create backup ZIP", e)
            null
        }
    }

    /** Remove old timestamped backups, keeping only MAX_REMOTE_BACKUPS */
    private fun cleanupOldBackups(client: WebDavClient) {
        try {
            val files = client.listFiles(REMOTE_DIR).getOrNull() ?: return
            val backups =
                files
                    .filter { it.name.startsWith(REMOTE_BACKUP_PREFIX) && it.name.endsWith(".zip") }
                    .sortedByDescending { it.name }

            // Keep only MAX_REMOTE_BACKUPS timestamped backups (plus the LatestBackup)
            val toDelete = backups.drop(MAX_REMOTE_BACKUPS)
            for (file in toDelete) {
                client.delete(file.path)
                SyncLog.log("Deleted old backup: ${file.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup old backups", e)
        }
    }

    /** Get list of remote backups */
    suspend fun listRemoteBackups(): List<WebDavFile> = withContext(Dispatchers.IO) {
        val client = createClient() ?: return@withContext emptyList()
        client.listFiles(REMOTE_DIR).getOrNull()?.filter { it.name.endsWith(".zip") } ?: emptyList()
    }
}

private fun ZipParameters.copy(fileNameInZip: String? = this.fileNameInZip): ZipParameters {
    return ZipParameters(this).apply { this@apply.fileNameInZip = fileNameInZip }
}
