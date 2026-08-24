package cool.hin.memox.presentation.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.print.PdfPrintListener
import android.view.View
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import cool.hin.memox.R
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.MemoXDatabase.Companion.DATABASE_NAME
import cool.hin.memox.data.dao.BaseNoteDao
import cool.hin.memox.data.dao.CommonDao
import cool.hin.memox.data.sync.SyncRouter
import cool.hin.memox.data.sync.SyncStatus
import cool.hin.memox.data.dao.FolderDao
import cool.hin.memox.data.dao.LabelDao
import cool.hin.memox.data.dao.NoteReminder
import cool.hin.memox.data.dao.moveBaseNotes
import cool.hin.memox.data.imports.ImportException
import cool.hin.memox.data.imports.ImportProgress
import cool.hin.memox.data.imports.ImportSource
import cool.hin.memox.data.imports.NotesImporter
import cool.hin.memox.data.model.Attachment
import cool.hin.memox.data.model.Audio
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Content
import cool.hin.memox.data.model.Converters
import cool.hin.memox.data.model.FileAttachment
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.Header
import cool.hin.memox.data.model.Item
import cool.hin.memox.data.model.Label
import cool.hin.memox.data.model.NoteFolder
import cool.hin.memox.data.model.SearchResult
import cool.hin.memox.data.model.deepCopy
import cool.hin.memox.presentation.activity.note.refreshStatusBarPin
import cool.hin.memox.presentation.exportedText
import cool.hin.memox.presentation.getQuantityString
import cool.hin.memox.presentation.restartApplication
import cool.hin.memox.presentation.setCancelButton
import cool.hin.memox.presentation.showSnackbar
import cool.hin.memox.presentation.showToast
import cool.hin.memox.presentation.view.misc.NotNullLiveData
import cool.hin.memox.presentation.view.misc.Progress
import cool.hin.memox.presentation.viewmodel.preference.BasePreference
import cool.hin.memox.presentation.viewmodel.preference.BiometricLock
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences.Companion.START_VIEW_DEFAULT
import cool.hin.memox.presentation.viewmodel.preference.Theme
import cool.hin.memox.presentation.viewmodel.progress.ExportNotesProgress
import cool.hin.memox.utils.ActionMode
import cool.hin.memox.utils.Cache
import cool.hin.memox.utils.MIME_TYPE_JSON
import cool.hin.memox.utils.backup.copyDatabase
import cool.hin.memox.utils.backup.exportAsZip
import cool.hin.memox.utils.backup.exportPdfFile
import cool.hin.memox.utils.backup.exportPdfFileFolder
import cool.hin.memox.utils.backup.exportPlainTextFile
import cool.hin.memox.utils.backup.exportPlainTextFileFolder
import cool.hin.memox.utils.backup.importZip
import cool.hin.memox.utils.backup.readAsBackup
import cool.hin.memox.utils.cancelPinAndReminders
import cool.hin.memox.utils.copyToLarge
import cool.hin.memox.utils.deleteAttachments
import cool.hin.memox.utils.getBackupDir
import cool.hin.memox.utils.getCurrentImagesDirectory
import cool.hin.memox.utils.getExternalMediaDirectory
import cool.hin.memox.utils.ImageCompressor
import cool.hin.memox.utils.log
import cool.hin.memox.utils.migrateAllAttachments
import cool.hin.memox.utils.security.DecryptionException
import cool.hin.memox.utils.security.EncryptionException
import cool.hin.memox.utils.security.decryptDatabase
import cool.hin.memox.utils.security.encryptDatabase
import cool.hin.memox.utils.security.isEncryptedDatabase
import cool.hin.memox.utils.security.isUnencryptedDatabase
import cool.hin.memox.utils.toReadablePath
import cool.hin.memox.utils.viewFile
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BaseNoteModel(private val app: Application) : AndroidViewModel(app) {

    private lateinit var database: MemoXDatabase
    private lateinit var labelDao: LabelDao
    private lateinit var folderDao: FolderDao
    private lateinit var commonDao: CommonDao
    private lateinit var baseNoteDao: BaseNoteDao

    private val labelCache = HashMap<String, Content>()
    private val folderCache = HashMap<String, Content>()

    lateinit var selectedExportMimeType: ExportMimeType

    var labels: LiveData<List<Label>> = NotNullLiveData(mutableListOf())
    var folders: LiveData<List<NoteFolder>> = NotNullLiveData(mutableListOf())
    var reminders: LiveData<List<NoteReminder>> = NotNullLiveData(mutableListOf())
    private var allNotes: LiveData<List<BaseNote>>? = NotNullLiveData(mutableListOf())
    private var allNotesObserver: Observer<List<BaseNote>>? = null
    var baseNotes: Content? = Content(MutableLiveData(), ::transform)
    var deletedNotes: Content? = Content(MutableLiveData(), ::transform)
    var archivedNotes: Content? = null
    var reminderNotes: Content? = Content(MutableLiveData(), ::transform)

    val folder = NotNullLiveData(Folder.NOTES)

    var currentLabel: String? = CURRENT_LABEL_EMPTY

    var keyword = String()
        set(value) {
            if (field != value || searchResults?.value?.isEmpty() == true) {
                field = value
                searchResults?.fetch(keyword, folder.value, currentLabel)
            }
        }

    var searchResults: SearchResult? = null

    private val pinned = Header(app.getString(R.string.pinned))
    private val others = Header(app.getString(R.string.others))

    val preferences = MemoXPreferences.getInstance(app)

    val imageRoot
        get() = app.getCurrentImagesDirectory()

    val importProgress = MutableLiveData<ImportProgress>()
    val progress = MutableLiveData<Progress>()

    val actionMode = ActionMode()

    private var labelsHiddenObserver: Observer<Set<String>>? = null
    private var foldersHiddenObserver: Observer<Set<String>>? = null

    fun startObserving() {
        MemoXDatabase.getDatabase(app).observeForever(::init)
        folder.observeForever { newFolder ->
            searchResults?.fetch(keyword, newFolder, currentLabel)
        }
    }

    private fun init(database: MemoXDatabase) {
        this.database = database
        baseNoteDao = database.getBaseNoteDao()
        labelDao = database.getLabelDao()
        folderDao = database.getFolderDao()
        commonDao = database.getCommonDao()

        labels = labelDao.getAll()
        folders = folderDao.getAll()
        //        colors = baseNoteDao.getAllColorsAsync()

        allNotesObserver?.let { allNotes?.removeObserver(it) }
        allNotesObserver = Observer { list -> Cache.list = list }
        allNotes = baseNoteDao.getAllAsync()
        allNotes!!.observeForever(allNotesObserver!!)

        labelsHiddenObserver?.let { preferences.labelsHidden.removeObserver(it) }
        labelsHiddenObserver = Observer {
            // Do NOT reassign `baseNotes` here: NotesFragment observes the existing
            // Content instance (model.baseNotes!!). Recreating it would orphan the
            // observed instance, so changes triggered by a background sync would not
            // reach the home list until the fragment is recreated.
            initBaseNotes()
        }
        preferences.labelsHidden.observeForever(labelsHiddenObserver!!)

        foldersHiddenObserver?.let { preferences.foldersHidden.removeObserver(it) }
        foldersHiddenObserver = Observer { initBaseNotes() }
        preferences.foldersHidden.observeForever(foldersHiddenObserver!!)

        if (deletedNotes == null) {
            deletedNotes = Content(baseNoteDao.getFrom(Folder.DELETED), ::transform)
        } else {
            deletedNotes!!.setObserver(baseNoteDao.getFrom(Folder.DELETED))
        }

        if (archivedNotes == null) {
            archivedNotes = Content(baseNoteDao.getFrom(Folder.ARCHIVED), ::transform)
        } else {
            archivedNotes!!.setObserver(baseNoteDao.getFrom(Folder.ARCHIVED))
        }

        if (reminderNotes == null) {
            reminderNotes = Content(baseNoteDao.getAllBaseNotesWithReminders(), ::transform)
        } else {
            reminderNotes!!.setObserver(baseNoteDao.getAllBaseNotesWithReminders())
        }

        if (searchResults == null) {
            searchResults = SearchResult(app, viewModelScope, baseNoteDao, ::transform)
        } else {
            searchResults!!.baseNoteDao = baseNoteDao
        }
        // 初始拉取，防止 init 之前设置的 keyword 因 searchResults 尚未就绪而丢失
        if (keyword.isNotEmpty()) {
            searchResults?.fetch(keyword, folder.value, currentLabel)
        }
    }

    private fun initBaseNotes() {
        val labelsHidden = preferences.labelsHidden.value
        val foldersHidden = preferences.foldersHidden.value
        val overviewNotes =
            baseNoteDao.getFrom(Folder.NOTES).map { list ->
                list.filter { baseNote ->
                    baseNote.labels.none { labelsHidden.contains(it) } &&
                        (baseNote.folderId.isEmpty() ||
                            !foldersHidden.contains(baseNote.folderId))
                }
            }
        if (baseNotes == null) {
            baseNotes = Content(overviewNotes, ::transform)
        } else {
            baseNotes!!.setObserver(overviewNotes)
        }
    }

    fun getNotesByLabel(label: String): Content {
        if (labelCache[label] == null) {
            labelCache[label] =
                Content(baseNoteDao.getBaseNotesByLabel(label), ::transform, viewModelScope)
        }
        return requireNotNull(labelCache[label], { "labelCache has no '$label' value" })
    }

    fun getNotesByFolder(folderId: String): Content {
        if (folderCache[folderId] == null) {
            folderCache[folderId] =
                Content(baseNoteDao.getBaseNotesByFolder(folderId), ::transform, viewModelScope)
        }
        return requireNotNull(folderCache[folderId], { "folderCache has no '$folderId' value" })
    }

    fun getNotesWithoutLabel(): Content {
        return Content(
            baseNoteDao.getBaseNotesWithoutLabel(Folder.NOTES),
            ::transform,
            viewModelScope,
        )
    }

    private fun transform(list: List<BaseNote>) = transform(list, pinned, others)

    fun enableDataInPublic(callback: (() -> Unit)? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val database = MemoXDatabase.getDatabase(app, observePreferences = false).value
                database.checkpoint()
                val targetDirectory = MemoXDatabase.getExternalDatabaseFile(app).parentFile
                val internalDatabaseFiles = MemoXDatabase.getInternalDatabaseFiles(app)
                internalDatabaseFiles.forEach {
                    it.copyToLarge(File(targetDirectory, it.name), overwrite = true)
                }
                val notallyDatabase = MemoXDatabase.getFreshDatabase(app, true)
                val ping =
                    try {
                        notallyDatabase.ping()
                    } catch (e: Exception) {
                        throw RuntimeException(
                            "Moving internal '${internalDatabaseFiles.map { it.name }}' to public '$targetDirectory' folder failed",
                            e,
                        )
                    }
                if (!ping) {
                    throw RuntimeException(
                        "Moving internal '${internalDatabaseFiles.map { it.name }}' to public '$targetDirectory' folder failed"
                    )
                }
                app.migrateAllAttachments(toPrivate = false)
            }
            savePreference(preferences.dataInPublicFolder, true)
            callback?.invoke()
        }
    }

    fun disableDataInPublic(callback: (() -> Unit)? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val database = MemoXDatabase.getDatabase(app, observePreferences = false).value
                database.checkpoint()
                val targetDirectory = MemoXDatabase.getInternalDatabaseFile(app).parentFile
                val externalDatabaseFiles = MemoXDatabase.getExternalDatabaseFiles(app)
                externalDatabaseFiles.forEach {
                    it.copyToLarge(File(targetDirectory, it.name), overwrite = true)
                }
                val notallyDatabase = MemoXDatabase.getFreshDatabase(app, false)
                val ping =
                    try {
                        notallyDatabase.ping()
                    } catch (e: Exception) {
                        throw RuntimeException(
                            "Moving public '${externalDatabaseFiles.map { it.name }}' to internal '$targetDirectory' folder failed",
                            e,
                        )
                    }
                if (!ping) {
                    throw RuntimeException(
                        "Moving public '${externalDatabaseFiles.map { it.name }}' to internal '$targetDirectory' folder failed"
                    )
                }
                app.migrateAllAttachments(toPrivate = true)
            }
            savePreference(preferences.dataInPublicFolder, false)
            callback?.invoke()
        }
    }

    suspend fun enableBiometricLock(cipher: Cipher) {
        savePreference(preferences.iv, cipher.iv)
        val passphrase = preferences.databaseEncryptionKey.init(cipher)
        withContext(Dispatchers.IO) {
            database.close()
            val (_, dbFileCopy) = app.copyDatabase(suffix = "-encrypt")
            val (_, dbFileBackup) = app.copyDatabase(suffix = "-encrypt-backup")
            encryptDatabase(app, dbFileCopy, passphrase)
            val originalDbFile = MemoXDatabase.getCurrentDatabaseFile(app)
            dbFileCopy.copyToLarge(originalDbFile, overwrite = true)
            if (originalDbFile.isUnencryptedDatabase) {
                dbFileBackup.copyToLarge(originalDbFile, overwrite = true)
                val externalBackupFile =
                    File(app.getExternalMediaDirectory(), "${DATABASE_NAME}_Backup-encrypt")
                dbFileBackup.copyToLarge(externalBackupFile, overwrite = true)
                throw EncryptionException(
                    "Encrypt succeeded but overwritten database is not encrypted"
                )
            }
            savePreference(preferences.fallbackDatabaseEncryptionKey, passphrase)
            savePreference(preferences.biometricLock, BiometricLock.ENABLED)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    suspend fun disableBiometricLock(cipher: Cipher? = null, callback: (() -> Unit)? = null) {
        val encryptedPassphrase = preferences.databaseEncryptionKey.value
        val passphrase =
            cipher?.doFinal(encryptedPassphrase)
                ?: preferences.fallbackDatabaseEncryptionKey.value!!
        withContext(Dispatchers.IO) {
            database.close()
            val (_, dbFileCopy) = app.copyDatabase(decrypt = false, suffix = "-decrypt")
            val (_, dbFileBackup) = app.copyDatabase(decrypt = false, suffix = "-decrypt-backup")
            decryptDatabase(app, dbFileCopy, passphrase)
            val originalDbFile = MemoXDatabase.getCurrentDatabaseFile(app)
            dbFileCopy.copyToLarge(originalDbFile, overwrite = true)
            if (originalDbFile.isEncryptedDatabase) {
                dbFileBackup.copyToLarge(originalDbFile, overwrite = true)
                val externalBackupFile =
                    File(app.getExternalMediaDirectory(), "${DATABASE_NAME}_Backup-decrypt")
                dbFileBackup.copyToLarge(externalBackupFile, overwrite = true)
                throw DecryptionException(
                    "Decrypt succeeded but overwritten database is still encrypted"
                )
            }
            savePreference(preferences.biometricLock, BiometricLock.DISABLED)
            callback?.invoke()
        }
    }

    fun <T> savePreference(preference: BasePreference<T>, value: T) {
        viewModelScope.launch(Dispatchers.IO) { preference.save(value) }
    }

    /**
     * 对**已有**笔记图片做一次批量「感知无损」压缩（仅当 [MemoXPreferences.compressExistingImages]
     * 被开启时由设置页触发）。遍历所有笔记（含已删除）的图片附件，对超过阈值的图片重新编码；若换了
     * 容器格式（如 PNG→WebP）则自动改名并回写数据库，保证文件名与 [FileAttachment] 始终一致。
     *
     * 注意：同一张图片文件可能被多条笔记引用（理论上本 App 不会，但稳妥起见按 localName 聚合后
     * 对每条引用它的笔记都更新其附件记录），因此这里按「文件名 → 引用列表」聚合，再逐笔记累积改动，
     * 避免一条笔记含多张大图时后处理的图覆盖先处理图的更新。
     *
     * 回写时会 **bump `modifiedTimestamp`**：图片字节确实变了，只有时间戳前进，下一次同步才会把
     * 压好的小图上传覆盖远端（同步按时间戳比大小决定方向）；换了容器格式的旧远端文件会在同步末尾
     * 的孤儿附件清理里被删掉，因此用户**不需要手动做任何上传动作**。
     *
     * @param onProgress 每处理完一个候选文件回调一次 `(已处理, 候选总数)`，用于灵动岛展示进度。
     * @return 实际被压缩的图片数量。
     */
    suspend fun compressExistingImages(onProgress: ((Int, Int) -> Unit)? = null): Int {
        return withContext(Dispatchers.IO) {
            val dir = app.getCurrentImagesDirectory()
            if (!dir.exists() || !dir.isDirectory) return@withContext 0
            val notes = baseNoteDao.getAllNotesIncludingDeleted()
            val notesById = notes.associateBy { it.id }

            // localName -> 引用它的 (noteId, FileAttachment)
            val byName = LinkedHashMap<String, MutableList<Pair<Long, FileAttachment>>>()
            for (note in notes) {
                for (att in note.images) {
                    byName.getOrPut(att.localName) { ArrayList() }.add(note.id to att)
                }
            }

            // 先筛出「体积超阈值」的候选，这样进度总数一开始就是确定的（而不是边压边变）。
            val candidates =
                byName.entries.filter { (localName, _) ->
                    val file = File(dir, localName)
                    file.isFile && file.length() > ImageCompressor.THRESHOLD_BYTES
                }
            val total = candidates.size
            onProgress?.invoke(0, total)
            if (total == 0) return@withContext 0

            // noteId -> 压缩后累积的 images 列表
            val updated = LinkedHashMap<Long, MutableList<FileAttachment>>()
            var compressed = 0
            var processed = 0

            for ((localName, refs) in candidates) {
                val file = File(dir, localName)
                val sourceMime = refs.first().second.mimeType
                val result = ImageCompressor.compressFile(file, sourceMime)
                processed++
                onProgress?.invoke(processed, total)
                if (result == null) continue
                compressed++

                for ((noteId, att) in refs) {
                    val baseNote = notesById[noteId] ?: continue
                    val list = updated.getOrPut(noteId) { ArrayList(baseNote.images) }
                    val newOriginal = renameOriginalExtension(att.originalName, result.extension)
                    val newAtt = FileAttachment(result.localName, newOriginal, result.mimeType)
                    val idx = list.indexOfFirst { it.localName == localName }
                    if (idx >= 0) list[idx] = newAtt else list.add(newAtt)
                }
            }

            val now = System.currentTimeMillis()
            for ((noteId, images) in updated) {
                baseNoteDao.updateImages(noteId, images, now)
            }
            compressed
        }
    }

    /**
     * 设置页触发的入口：在 **Activity 级** 的 [viewModelScope] 里跑批量压缩，
     * 因此离开设置页、切到别的页面都不会中断；进度通过 [SyncStatus] 显示在灵动岛上，
     * 结束后自动触发一次同步，把压好的小图推上云端。
     */
    fun startCompressExistingImages() {
        viewModelScope.launch {
            SyncStatus.setBusy(app.getString(R.string.compress_existing_progress, 0, 0))
            val count =
                compressExistingImages { done, total ->
                    SyncStatus.setBusy(
                        app.getString(R.string.compress_existing_progress, done, total)
                    )
                }
            if (count > 0) {
                SyncStatus.setDone(app.getString(R.string.compress_existing_done, count))
                // 让「已压缩 N 张」先被看见，再让同步接管灵动岛文案。
                delay(1500)
                SyncRouter.syncNow(app)
            } else {
                SyncStatus.setDone(app.getString(R.string.compress_existing_none))
            }
        }
    }

    private fun renameOriginalExtension(original: String, newExt: String): String {
        val dot = original.lastIndexOf('.')
        return if (dot >= 0) original.substring(0, dot) + "." + newExt else "$original.$newExt"
    }

    fun exportBackup(uri: Uri, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            val exportedNotesAndAttachments =
                withContext(Dispatchers.IO) {
                    app.log(TAG, msg = "Exporting backup to '$uri'...")
                    return@withContext app.exportAsZip(
                            uri,
                            password = preferences.backupPassword.value,
                            backupProgress = progress,
                        )
                        .also { app.log(TAG, msg = "Finished exporting backup to '$uri'") }
                }

            app.showToast(app.exportedText(exportedNotesAndAttachments))
            onComplete?.invoke()
        }
    }

    fun importZipBackup(uri: Uri, password: String) {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            app.log(TAG, throwable = throwable)
            app.showToast("${app.getString(R.string.invalid_backup)}: ${throwable.message}")
        }

        val backupDir = app.getBackupDir()
        viewModelScope.launch(exceptionHandler) {
            app.importZip(uri, backupDir, password, importProgress)
        }
    }

    fun importXmlBackup(uri: Uri) {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            app.log(TAG, throwable = throwable)
            app.showToast("${app.getString(R.string.invalid_backup)}: ${throwable.message}")
        }

        viewModelScope.launch(exceptionHandler) {
            val result =
                withContext(Dispatchers.IO) {
                    val stream =
                        requireNotNull(
                            app.contentResolver.openInputStream(uri),
                            { "InputStream for '$uri' is null" },
                        )
                    val (baseNotes, labels) = stream.readAsBackup()
                    commonDao.importBackup(baseNotes, labels)
                }
            val baseMsg = app.getQuantityString(R.plurals.imported_notes, result.inserted)
            val message =
                if (result.duplicates > 0)
                    "$baseMsg (${app.getQuantityString(R.plurals.duplicates, result.duplicates)})"
                else baseMsg
            app.showToast(message)
        }
    }

    fun importFromOtherApp(uri: Uri, importSource: ImportSource) {
        val database = MemoXDatabase.getDatabase(app, observePreferences = false).value
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            app.log(TAG, throwable = throwable)
            if (throwable is ImportException) {
                app.showToast(throwable.textResId)
            } else {
                app.showToast("${app.getString(R.string.invalid_backup)}: ${throwable.message}")
            }
        }

        viewModelScope.launch(exceptionHandler) {
            val result =
                withContext(Dispatchers.IO) {
                    NotesImporter(app, database).import(uri, importSource, importProgress)
                }
            val baseMsg = app.getQuantityString(R.plurals.imported_notes, result.inserted)
            val message =
                if (result.duplicates > 0) "$baseMsg (${result.duplicates} duplicates skipped)"
                else baseMsg
            app.showToast(message)
        }
    }

    fun exportNoteToFile(fileUri: Uri, note: BaseNote, snackbarView: View) {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            app.log(TAG, throwable = throwable)
            actionMode.close(true)
            app.showToast(R.string.something_went_wrong)
        }
        viewModelScope.launch(exceptionHandler) {
            when (selectedExportMimeType) {
                ExportMimeType.PDF -> {
                    exportPdfFile(
                        app,
                        note,
                        DocumentFile.fromSingleUri(app, fileUri)!!,
                        pdfPrintListener =
                            object : PdfPrintListener {
                                override fun onSuccess(file: DocumentFile) {
                                    actionMode.close(true)
                                    val message = app.getQuantityString(R.plurals.exported_notes, 1)
                                    snackbarView.showFileSnackbar(
                                        "$message to '${app.toReadablePath(fileUri)}'",
                                        fileUri,
                                        ExportMimeType.PDF,
                                    )
                                }

                                override fun onFailure(message: CharSequence?) {
                                    app.log(TAG, stackTrace = message as String?)
                                    actionMode.close(true)
                                }
                            },
                    )
                }
                else -> {
                    exportPlainTextFile(
                        app,
                        note,
                        DocumentFile.fromSingleUri(app, fileUri)!!,
                        selectedExportMimeType,
                    )
                    actionMode.close(true)
                    val message = app.getQuantityString(R.plurals.exported_notes, 1)
                    snackbarView.showFileSnackbar(
                        "$message to '${app.toReadablePath(fileUri)}'",
                        fileUri,
                        selectedExportMimeType,
                    )
                }
            }
        }
    }

    fun exportNotesToFolder(folderUri: Uri, notes: Collection<BaseNote>, snackbarView: View) {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            app.log(TAG, throwable = throwable)
            actionMode.close(true)
            progress.postValue(ExportNotesProgress(inProgress = false))
            app.showToast(R.string.something_went_wrong)
        }
        viewModelScope.launch(exceptionHandler) {
            val counter = AtomicInteger(0)
            progress.postValue(ExportNotesProgress(total = notes.size))
            when (selectedExportMimeType) {
                ExportMimeType.PDF -> {
                    for (note in notes) {
                        exportPdfFileFolder(
                            app,
                            note,
                            DocumentFile.fromTreeUri(app, folderUri)!!,
                            progress = progress,
                            counter = counter,
                            total = notes.size,
                            pdfPrintListener =
                                object : PdfPrintListener {
                                    override fun onSuccess(file: DocumentFile) {
                                        actionMode.close(true)
                                        progress.postValue(ExportNotesProgress(inProgress = false))
                                        val message =
                                            app.getQuantityString(
                                                R.plurals.exported_notes,
                                                counter.get(),
                                            )
                                        snackbarView.showSnackbar(
                                            "$message to '${app.toReadablePath(folderUri)}'"
                                        )
                                    }

                                    override fun onFailure(message: CharSequence?) {
                                        app.log(TAG, stackTrace = message as String?)
                                        actionMode.close(true)
                                        progress.postValue(ExportNotesProgress(inProgress = false))
                                    }
                                },
                        )
                    }
                }
                else -> {
                    for (note in notes) {
                        exportPlainTextFileFolder(
                            app,
                            note,
                            selectedExportMimeType,
                            DocumentFile.fromTreeUri(app, folderUri)!!,
                            progress = progress,
                            counter = counter,
                            total = notes.size,
                        )
                    }
                    actionMode.close(true)
                    progress.postValue(ExportNotesProgress(inProgress = false))
                    val message = app.getQuantityString(R.plurals.exported_notes, counter.get())
                    snackbarView.showSnackbar("$message to '${app.toReadablePath(folderUri)}'")
                }
            }
        }
    }

    fun exportSelectedNotesToFolder(folderUri: Uri, snackbarView: View) {
        exportNotesToFolder(folderUri, actionMode.selectedNotes.values, snackbarView)
    }

    fun exportSelectedNoteToFile(fileUri: Uri, snackbarView: View) {
        exportNoteToFile(fileUri, actionMode.selectedNotes.values.first(), snackbarView)
    }

    private fun View.showFileSnackbar(msg: String, fileUri: Uri, mimeType: ExportMimeType) {
        showSnackbar(msg, R.string.open_link) { app.viewFile(fileUri, mimeType.mimeType) }
    }

    fun pinBaseNotes(pinned: Boolean) {
        val ids = actionMode.selectedIds.toLongArray()
        actionMode.close(true)
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            baseNoteDao.updatePinned(ids, pinned, now)
            SyncRouter.syncNow(app)
        }
    }

    fun lockBaseNotes(locked: Boolean) {
        val ids = actionMode.selectedIds.toLongArray()
        actionMode.close(true)
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            baseNoteDao.updateLocked(ids, locked, now)
            SyncRouter.syncNow(app)
        }
    }

    fun pinBaseNotesToStatusBar(activity: Activity, pinnedToStatusBar: Boolean) {
        val ids = actionMode.selectedIds.toLongArray()
        actionMode.close(true)
        viewModelScope.launch {
            val updatedNotes =
                withContext(Dispatchers.IO) {
                    baseNoteDao.updatePinnedToStatus(ids, pinnedToStatusBar)
                    baseNoteDao.getByIds(ids)
                }
            updatedNotes.forEach { activity.refreshStatusBarPin(it) }
        }
    }

    fun colorBaseNote(color: String) {
        val ids = actionMode.selectedIds.toLongArray()
        actionMode.close(true)
        viewModelScope.launch(Dispatchers.IO) { baseNoteDao.updateColor(ids, color) }
    }

    fun changeColor(oldColor: String, newColor: String) {
        val defaultColor = preferences.defaultNoteColor.value
        if (oldColor == defaultColor) {
            preferences.defaultNoteColor.save(newColor)
        }
        viewModelScope.launch(Dispatchers.IO) { baseNoteDao.updateColor(oldColor, newColor) }
    }

    fun moveBaseNotes(folder: Folder, callable: (() -> Unit)? = null): LongArray {
        val ids = actionMode.selectedIds.toLongArray()
        actionMode.close(false)
        moveBaseNotes(ids, folder, callable)
        return ids
    }

    fun moveBaseNotes(ids: LongArray, folder: Folder, callable: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.moveBaseNotes(baseNoteDao, ids, folder)
                // Moving between folders (e.g. to trash or restore) changes the note's folder
                // field, which must be synced immediately
                SyncRouter.syncNow(app)
            } finally {
                callable?.invoke()
            }
        }
    }

    fun updateBaseNoteLabels(labels: List<String>, id: Long) {
        actionMode.close(true)
        viewModelScope.launch(Dispatchers.IO) {
            baseNoteDao.updateLabels(id, labels)
            SyncRouter.syncNow(app)
        }
    }

    fun updateBaseNoteFolder(folderId: String, id: Long) {
        updateBaseNoteFolder(folderId, longArrayOf(id))
    }

    fun updateBaseNoteFolder(folderId: String, ids: LongArray) {
        actionMode.close(true)
        viewModelScope.launch(Dispatchers.IO) {
            baseNoteDao.updateFolderId(ids, folderId)
            SyncRouter.syncNow(app)
        }
    }

    suspend fun deleteSelectedBaseNotes(): Collection<BaseNote> {
        return deleteBaseNotes(actionMode.selectedIds.toLongArray())
    }

    fun deleteAll() {
        viewModelScope.launch {
            val (ids, noteReminders) =
                withContext(Dispatchers.IO) {
                    Pair(baseNoteDao.getAllIds().toLongArray(), baseNoteDao.getAllReminders())
                }
            noteReminders.forEach { app.cancelPinAndReminders(it.id, it.reminders) }
            val deletedNotes = deleteBaseNotes(ids)
            app.deleteAttachments(deletedNotes)
            withContext(Dispatchers.IO) { labelDao.deleteAll() }
            savePreference(preferences.startView, START_VIEW_DEFAULT)
            app.showToast(R.string.cleared_data)
        }
    }

    private suspend fun deleteBaseNotes(ids: LongArray): Collection<BaseNote> {
        val notes = withContext(Dispatchers.IO) { baseNoteDao.getByIds(ids) }
        actionMode.close(false)
        app.cancelPinAndReminders(notes)
        // Delete from the active sync provider before deleting locally (need note data for filename/attachments)
        try {
            SyncRouter.deleteRemoteNotes(app, notes)
        } catch (_: Exception) {}
        return withContext(Dispatchers.IO) {
            baseNoteDao.delete(ids)
            return@withContext notes
        }
    }

    fun deleteAllTrashedBaseNotes() {
        viewModelScope.launch {
            val ids: LongArray
            val images = ArrayList<FileAttachment>()
            val files = ArrayList<FileAttachment>()
            val audios = ArrayList<Audio>()
            val notes: List<BaseNote>
            withContext(Dispatchers.IO) {
                ids = baseNoteDao.getDeletedNoteIds()
                notes = baseNoteDao.getByIds(ids)
                val imageStrings = baseNoteDao.getDeletedNoteImages()
                val fileStrings = baseNoteDao.getDeletedNoteFiles()
                val audioStrings = baseNoteDao.getDeletedNoteAudios()
                imageStrings.flatMapTo(images) { json -> Converters.jsonToFiles(json) }
                fileStrings.flatMapTo(files) { json -> Converters.jsonToFiles(json) }
                audioStrings.flatMapTo(audios) { json -> Converters.jsonToAudios(json) }
            }
            // Delete from the active sync provider (records tombstones) before deleting locally,
            // otherwise a manual sync would re-download the remote copies
            try {
                SyncRouter.deleteRemoteNotes(app, notes)
            } catch (_: Exception) {}
            withContext(Dispatchers.IO) { baseNoteDao.deleteFrom(Folder.DELETED) }
            val attachments = ArrayList<Attachment>(images.size + files.size + audios.size)
            attachments.addAll(images)
            attachments.addAll(files)
            attachments.addAll(audios)
            withContext(Dispatchers.IO) { app.deleteAttachments(attachments, ids) }
        }
    }

    suspend fun duplicateNote(note: BaseNote) = duplicateNotes(listOf(note)).first()

    suspend fun duplicateNotes(notes: Collection<BaseNote>): List<Long> {
        val now = System.currentTimeMillis()
        val copies: List<BaseNote> =
            notes.map { original ->
                original
                    .deepCopy()
                    .copy(
                        id = 0L,
                        title =
                            if (original.title.isNotEmpty())
                                "${original.title} (${app.getString(R.string.copy)})"
                            else app.getString(R.string.copy),
                        timestamp = now,
                        modifiedTimestamp = now,
                    )
            }
        return withContext(Dispatchers.IO) { baseNoteDao.insert(copies) }
    }

    fun duplicateSelectedBaseNotes() {
        if (actionMode.isEmpty()) return
        val selected = actionMode.selectedNotes.values.toList()
        viewModelScope.launch {
            duplicateNotes(selected)
            actionMode.close(true)
            app.showToast(app.getQuantityString(R.plurals.duplicates, selected.size))
        }
    }

    suspend fun getAllLabels() = withContext(Dispatchers.IO) { labelDao.getArrayOfAll() }

    suspend fun getAllFolders() = withContext(Dispatchers.IO) { folderDao.getArrayOfAll() }

    fun deleteLabel(value: String) {
        viewModelScope.launch(Dispatchers.IO) { commonDao.deleteLabel(value) }
        val labelsHiddenPreference = preferences.labelsHidden
        val labelsHidden = labelsHiddenPreference.value.toMutableSet()
        if (labelsHidden.contains(value)) {
            labelsHidden.remove(value)
            savePreference(labelsHiddenPreference, labelsHidden)
        }
        if (preferences.startView.value == value) {
            savePreference(preferences.startView, START_VIEW_DEFAULT)
        }
        SyncRouter.syncNow(getApplication())
    }

    fun insertLabel(label: String, onComplete: (success: Boolean) -> Unit) =
        executeAsyncWithCallback(
            { labelDao.insert(Label(label, (labelDao.getMaxOrder() ?: -1) + 1)) },
        ) { success ->
            if (success) SyncRouter.syncNow(getApplication())
            onComplete(success)
        }

    fun updateLabels(labels: List<Label>) {
        viewModelScope.launch(Dispatchers.IO) { labelDao.update(labels) }
        SyncRouter.syncNow(getApplication())
    }

    fun updateLabel(oldValue: String, newValue: String, onComplete: (success: Boolean) -> Unit) {
        executeAsyncWithCallback({ commonDao.updateLabel(oldValue, newValue) }) { success ->
            if (success) SyncRouter.syncNow(getApplication())
            onComplete(success)
        }
        val labelsHiddenPreference = preferences.labelsHidden
        val labelsHidden = labelsHiddenPreference.value.toMutableSet()
        if (labelsHidden.contains(oldValue)) {
            labelsHidden.remove(oldValue)
            labelsHidden.add(newValue)
            savePreference(labelsHiddenPreference, labelsHidden)
        }
    }

    /**
     * Folders are local-only (not synced across devices — only each note's `folderId` travels in
     * the note JSON). Deleting a folder detaches its notes (folderId -> "") which DOES propagate
     * via note sync, so trigger a sync here.
     */
    fun deleteFolder(name: String) {
        viewModelScope.launch(Dispatchers.IO) { commonDao.deleteFolder(name) }
        val foldersHiddenPreference = preferences.foldersHidden
        val foldersHidden = foldersHiddenPreference.value.toMutableSet()
        if (foldersHidden.contains(name)) {
            foldersHidden.remove(name)
            savePreference(foldersHiddenPreference, foldersHidden)
        }
        if (preferences.startView.value == name) {
            savePreference(preferences.startView, START_VIEW_DEFAULT)
        }
        SyncRouter.syncNow(getApplication())
    }

    fun insertFolder(name: String, onComplete: (success: Boolean) -> Unit) =
        executeAsyncWithCallback(
            { folderDao.insert(NoteFolder(name, (folderDao.getMaxOrder() ?: -1) + 1)) },
        ) { success -> onComplete(success) }

    fun updateFolders(folders: List<NoteFolder>) {
        viewModelScope.launch(Dispatchers.IO) { folderDao.update(folders) }
    }

    fun updateFolder(oldName: String, newName: String, onComplete: (success: Boolean) -> Unit) {
        executeAsyncWithCallback({ commonDao.updateFolder(oldName, newName) }) { success ->
            if (success) SyncRouter.syncNow(getApplication())
            onComplete(success)
        }
        val foldersHiddenPreference = preferences.foldersHidden
        val foldersHidden = foldersHiddenPreference.value.toMutableSet()
        if (foldersHidden.contains(oldName)) {
            foldersHidden.remove(oldName)
            foldersHidden.add(newName)
            savePreference(foldersHiddenPreference, foldersHidden)
        }
    }

    suspend fun folderExists(name: String) = withContext(Dispatchers.IO) { folderDao.exists(name) }

    suspend fun resetPreferences(callback: (restartRequired: Boolean) -> Unit) {
        val publicFolder = preferences.dataInPublicFolder.value
        val isThemeDefault = preferences.theme.value == Theme.FOLLOW_SYSTEM
        val finishCallback = { callback(!isThemeDefault) }
        if (preferences.isLockEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                disableBiometricLock {
                    finishResetPreferencesAfterBiometric(publicFolder, finishCallback)
                }
            } else finishResetPreferencesAfterBiometric(publicFolder, finishCallback)
        } else finishResetPreferencesAfterBiometric(publicFolder, finishCallback)
    }

    private fun finishResetPreferencesAfterBiometric(
        publicFolder: Boolean,
        callback: (() -> Unit),
    ) {
        if (publicFolder) {
            refreshDataInPublicFolder(false) { finishResetPreferences(callback) }
        } else finishResetPreferences(callback)
    }

    private fun finishResetPreferences(callback: () -> Unit) {
        preferences.reset()
        callback()
        app.restartApplication(R.id.Settings)
    }

    fun importPreferences(
        context: Context,
        uri: Uri,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ) {
        val dataInPublicFolderBefore = preferences.dataInPublicFolder.value
        val themeBefore = preferences.theme.value
        val useDynamicColorsBefore = preferences.useDynamicColors.value
        val oldStartView = preferences.startView.value

        val success = preferences.import(context, uri)

        val dataInPublicFolder = preferences.dataInPublicFolder.getFreshValue()
        if (dataInPublicFolderBefore != dataInPublicFolder) {
            refreshDataInPublicFolder(dataInPublicFolder) {
                preferences.dataInPublicFolder.refresh()
                finishImportPreferences(
                    themeBefore,
                    useDynamicColorsBefore,
                    oldStartView,
                    context,
                ) {
                    if (success) {
                        onSuccess()
                    } else onFailure()
                }
            }
        } else
            finishImportPreferences(
                themeBefore,
                useDynamicColorsBefore,
                oldStartView,
                context,
            ) {
                if (success) {
                    onSuccess()
                } else onFailure()
            }
    }

    private fun finishImportPreferences(
        themeBefore: Theme,
        useDynamicColorsBefore: Boolean,
        oldStartView: String,
        context: Context,
        callback: () -> Unit,
    ) {
        val startView = preferences.startView.getFreshValue()
        if (oldStartView != startView) {
            refreshStartView(startView, oldStartView)
        }
        preferences.theme.refresh()
        callback()
    }

    private fun refreshDataInPublicFolder(dataInPublicFolder: Boolean, callback: () -> Unit) {
        if (dataInPublicFolder) {
            enableDataInPublic(callback)
        } else {
            disableDataInPublic(callback)
        }
    }

    private fun refreshStartView(startView: String, oldStartView: String) {
        if (startView == START_VIEW_DEFAULT) {
            savePreference(preferences.startView, startView)
        } else {
            viewModelScope.launch {
                val startViewLabelExists =
                    withContext(Dispatchers.IO) { labelDao.exists(startView) }
                savePreference(
                    preferences.startView,
                    if (startViewLabelExists) startView else oldStartView,
                )
            }
        }
    }

    fun saveNotes(notes: List<BaseNote>) {
        viewModelScope.launch(Dispatchers.IO) { baseNoteDao.insert(notes) }
    }

    companion object {
        private const val TAG = "BaseNoteModel"

        const val CURRENT_LABEL_EMPTY = ""
        val CURRENT_LABEL_NONE: String? = null

        fun transform(list: List<BaseNote>, pinned: Header, others: Header): List<Item> {
            if (list.isEmpty()) {
                return list
            } else {
                val firstPinnedNote = list.indexOfFirst { baseNote -> baseNote.pinned }
                val firstUnpinnedNote = list.indexOfFirst { baseNote -> !baseNote.pinned }
                val mutableList: MutableList<Item> = list.toMutableList()
                if (firstPinnedNote != -1) {
                    mutableList.add(firstPinnedNote, pinned)
                    if (firstUnpinnedNote != -1) {
                        mutableList.add(firstUnpinnedNote + 1, others)
                    }
                }
                return mutableList
            }
        }
    }
}

enum class ExportMimeType(val mimeType: String, val fileExtension: String) {
    TXT("text/plain", "txt"),
    MD("text/markdown", "md"),
    PDF("application/pdf", "pdf"),
    JSON(MIME_TYPE_JSON, "json"),
    HTML("text/html", "html"),
}
