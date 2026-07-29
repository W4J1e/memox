package cool.hin.memox.utils.backup

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.print.PdfPrintListener
import android.print.printPdf
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import cool.hin.memox.R
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.MemoXDatabase.Companion.DATABASE_NAME
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Converters
import cool.hin.memox.data.model.FileAttachment
import cool.hin.memox.data.model.toHtml
import cool.hin.memox.data.model.toJson
import cool.hin.memox.data.model.toMarkdown
import cool.hin.memox.data.model.toTxt
import cool.hin.memox.presentation.activity.LockedActivity
import cool.hin.memox.presentation.getQuantityString
import cool.hin.memox.presentation.view.misc.Progress
import cool.hin.memox.presentation.viewmodel.BackupFile
import cool.hin.memox.presentation.viewmodel.ExportMimeType
import cool.hin.memox.presentation.viewmodel.preference.Constants.PASSWORD_EMPTY
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import cool.hin.memox.presentation.viewmodel.progress.BackupProgress
import cool.hin.memox.utils.MIME_TYPE_ZIP
import cool.hin.memox.utils.SUBFOLDER_AUDIOS
import cool.hin.memox.utils.SUBFOLDER_FILES
import cool.hin.memox.utils.SUBFOLDER_IMAGES
import cool.hin.memox.utils.ZipVerificationException
import cool.hin.memox.utils.copyToLarge
import cool.hin.memox.utils.createFileSafe
import cool.hin.memox.utils.getCurrentAudioDirectory
import cool.hin.memox.utils.getCurrentFilesDirectory
import cool.hin.memox.utils.getCurrentImagesDirectory
import cool.hin.memox.utils.getCurrentMediaRoot
import cool.hin.memox.utils.getExportedPath
import cool.hin.memox.utils.log
import cool.hin.memox.utils.md5Hash
import cool.hin.memox.utils.recreateDir
import cool.hin.memox.utils.resolveAttachmentFile
import cool.hin.memox.utils.security.decryptDatabase
import cool.hin.memox.utils.security.getInitializedCipherForDecryption
import cool.hin.memox.utils.verify
import cool.hin.memox.utils.wrapWithChooser
import java.io.File
import java.io.File.createTempFile
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.EncryptionMethod

private const val TAG = "ExportExtensions"

typealias NotesAndAttachments = Pair<Int, Int>

fun ContextWrapper.exportAsZip(
    fileUri: Uri,
    compress: Boolean = false,
    password: String = PASSWORD_EMPTY,
    backupProgress: MutableLiveData<Progress>? = null,
    retryOnFail: Boolean = true,
): NotesAndAttachments {
    backupProgress?.postValue(BackupProgress(indeterminate = true))
    val tempFile = createTempFile("export", "tmp", cacheDir)
    try {
        val zipFile =
            ZipFile(tempFile, if (password != PASSWORD_EMPTY) password.toCharArray() else null)
        val zipParameters =
            ZipParameters().apply {
                isEncryptFiles = password != PASSWORD_EMPTY
                if (!compress) {
                    compressionLevel = CompressionLevel.NO_COMPRESSION
                }
                if (isEncryptFiles) {
                    encryptionMethod = EncryptionMethod.AES
                }
            }

        val (databaseOriginal, databaseCopy) = copyDatabase()
        zipFile.addFile(databaseCopy, zipParameters.copy(DATABASE_NAME))

        val totalNotes = databaseOriginal.getBaseNoteDao().count()
        val images = databaseOriginal.getBaseNoteDao().getAllImages().toFileAttachments()
        val files = databaseOriginal.getBaseNoteDao().getAllFiles().toFileAttachments()
        val audios = databaseOriginal.getBaseNoteDao().getAllAudios()
        val totalAttachments = images.count() + files.count() + audios.size
        backupProgress?.postValue(
            BackupProgress(
                0,
                totalAttachments,
                countSuffix = getQuantityString(R.plurals.attachments, totalAttachments),
            )
        )

        val counter = AtomicInteger(0)
        images.export(
            zipFile,
            zipParameters,
            SUBFOLDER_IMAGES,
            this,
            backupProgress,
            totalAttachments,
            counter,
        )
        files.export(
            zipFile,
            zipParameters,
            SUBFOLDER_FILES,
            this,
            backupProgress,
            totalAttachments,
            counter,
        )
        audios
            .asSequence()
            .flatMap { string -> Converters.jsonToAudios(string) }
            .forEach { audio ->
                try {
                    backupAttachmentFile(
                        this,
                        zipFile,
                        zipParameters,
                        SUBFOLDER_AUDIOS,
                        audio.name,
                    )
                } catch (exception: Exception) {
                    log(TAG, throwable = exception)
                } finally {
                    backupProgress?.postValue(
                        BackupProgress(
                            counter.incrementAndGet(),
                            totalAttachments,
                            countSuffix = getQuantityString(R.plurals.attachments, totalAttachments),
                        )
                    )
                }
            }
        try {
            zipFile.verify(databaseCopy)
        } catch (e: ZipVerificationException) {
            log(TAG, throwable = e)
            if (retryOnFail) {
                zipFile.file.delete()
                log(TAG, stackTrace = "Retrying to export ZIP to $fileUri...")
                return exportAsZip(fileUri, compress, password, backupProgress, false)
            } else {
                throw IOException(
                    "exportAsZip failed because created '${zipFile.file}' is not a valid ZIP!"
                )
            }
        }
        contentResolver.openOutputStream(fileUri)?.use { outputStream ->
            FileInputStream(zipFile.file).use { inputStream ->
                inputStream.copyToLarge(outputStream)
                outputStream.flush()
            }
        }
        // Guard against null/IO issues when immediately reopening fileUri via SAF
        val sourceMd5 = runCatching { zipFile.file.md5Hash() }.getOrNull()
        val targetMd5 = runCatching { md5Hash(fileUri) }.getOrNull()
        if (sourceMd5 == null || targetMd5 == null || !targetMd5.contentEquals(sourceMd5)) {
            log(TAG, stackTrace = "Exported zipFile '$fileUri' has wrong MD5 hash!")
            if (retryOnFail) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        contentResolver.delete(fileUri, null)
                    } catch (e: Exception) {
                        log(TAG, msg = "Deleting $fileUri failed", throwable = e)
                    }
                }
                zipFile.file.delete()
                log(TAG, stackTrace = "Retrying to export ZIP to $fileUri...")
                return exportAsZip(fileUri, compress, password, backupProgress, false)
            } else {
                throw IOException(
                    "exportAsZip failed because created '$fileUri' has wrong or unverifiable MD5 hash!"
                )
            }
        }
        zipFile.file.delete()
        databaseCopy.delete()
        backupProgress?.postValue(BackupProgress(inProgress = false))
        return Pair(totalNotes, totalAttachments)
    } finally {
        tempFile.delete()
    }
}

fun Context.exportToZip(
    zipUri: Uri,
    files: List<BackupFile>,
    password: String = PASSWORD_EMPTY,
): Boolean {
    val tempDir = File(cacheDir, "export").recreateDir()
    try {
        val zipInputStream = contentResolver.openInputStream(zipUri) ?: return false
        extractZipToDirectory(zipInputStream, tempDir, password)
        files
            .filter { it.second.exists() }
            .forEach { file ->
                val targetFile =
                    File(tempDir, "${file.first?.let { "$it/" } ?: ""}${file.second.name}")
                file.second.copyToLarge(targetFile, overwrite = true)
            }
        val zipOutputStream = contentResolver.openOutputStream(zipUri, "w") ?: return false
        val tempZipFile = createTempFile("tempZip", ".zip")
        try {
            tempZipFile.deleteOnExit()
            val zipFile =
                ZipFile(
                    tempZipFile,
                    if (password != PASSWORD_EMPTY) password.toCharArray() else null,
                )
            val zipParameters =
                ZipParameters().apply {
                    this.isEncryptFiles = password != PASSWORD_EMPTY
                    this.compressionLevel = CompressionLevel.NO_COMPRESSION
                    if (isEncryptFiles) {
                        this.encryptionMethod = EncryptionMethod.AES
                    }
                    this.isIncludeRootFolder = false
                }
            zipFile.addFolder(tempDir, zipParameters)
            if (!zipFile.isValidZipFile) {
                throw IOException("ZipFile '${zipFile.file}' is not a valid ZIP!")
            }
            val databaseFile = files.find { it.second.name == DATABASE_NAME }
            databaseFile?.let { zipFile.verify(it.second) }
            tempZipFile.inputStream().use { inputStream ->
                inputStream.copyToLarge(zipOutputStream)
            }
        } finally {
            tempZipFile.delete()
        }
    } finally {
        tempDir.deleteRecursively()
    }
    return true
}

private fun extractZipToDirectory(zipInputStream: InputStream, outputDir: File, password: String) {
    val tempZipFile = createTempFile("extractedZip", null, outputDir)
    try {
        tempZipFile.outputStream().use { zipOutputStream ->
            zipInputStream.copyToLarge(zipOutputStream)
        }
        val zipFile =
            ZipFile(tempZipFile, if (password != PASSWORD_EMPTY) password.toCharArray() else null)
        zipFile.extractAll(outputDir.absolutePath)
    } finally {
        tempZipFile.delete()
    }
}

fun ContextWrapper.copyDatabase(
    decrypt: Boolean = true,
    suffix: String = "",
): Pair<MemoXDatabase, File> {
    val database = MemoXDatabase.getDatabase(this, observePreferences = false).value
    database.checkpoint()
    val preferences = MemoXPreferences.getInstance(this)
    val databaseFile = MemoXDatabase.getCurrentDatabaseFile(this)
    return if (
        decrypt && preferences.isLockEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    ) {
        val cipher = getInitializedCipherForDecryption(iv = preferences.iv.value!!)
        val passphrase = cipher.doFinal(preferences.databaseEncryptionKey.value)
        val decryptedFile = File(cacheDir, DATABASE_NAME + suffix)
        decryptDatabase(this, passphrase, databaseFile, decryptedFile)
        Pair(database, decryptedFile)
    } else {
        val dbFile = File(cacheDir, DATABASE_NAME + suffix)
        databaseFile.copyToLarge(dbFile, overwrite = true)
        Pair(database, dbFile)
    }
}

private fun List<String>.toFileAttachments(): Sequence<FileAttachment> {
    return asSequence().flatMap { string -> Converters.jsonToFiles(string) }
}

private fun Sequence<FileAttachment>.export(
    zipFile: ZipFile,
    zipParameters: ZipParameters,
    subfolder: String,
    context: ContextWrapper,
    backupProgress: MutableLiveData<Progress>?,
    total: Int,
    counter: AtomicInteger,
) {
    forEach { file ->
        try {
            backupAttachmentFile(context, zipFile, zipParameters, subfolder, file.localName)
        } catch (exception: Exception) {
            context.log(TAG, throwable = exception)
        } finally {
            backupProgress?.postValue(
                BackupProgress(
                    counter.incrementAndGet(),
                    total,
                    countSuffix = context.getQuantityString(R.plurals.attachments, total),
                )
            )
        }
    }
}

// Try to backup attachment by resolving it according to current/private/public directories.
// Returns true if the file was found and added to the zip, false if missing.
private fun backupAttachmentFile(
    context: ContextWrapper,
    zipFile: ZipFile,
    zipParameters: ZipParameters,
    folder: String,
    name: String,
): Boolean {
    val file = context.resolveAttachmentFile(folder, name)
    return if (file != null && file.exists()) {
        zipFile.addFile(file, zipParameters.copy("$folder/$name"))
        true
    } else {
        false
    }
}

private fun ZipParameters.copy(fileNameInZip: String? = this.fileNameInZip): ZipParameters {
    return ZipParameters(this).apply { this@apply.fileNameInZip = fileNameInZip }
}

fun exportPdfFileFolder(
    app: ContextWrapper,
    note: BaseNote,
    folder: DocumentFile,
    fileName: String = note.title,
    pdfPrintListener: PdfPrintListener? = null,
    progress: MutableLiveData<Progress>? = null,
    counter: AtomicInteger? = null,
    total: Int? = null,
    duplicateFileCount: Int = 1,
) {
    val validFileName = fileName.ifBlank { app.getString(R.string.note) }
    val filePath = "$validFileName.${ExportMimeType.PDF.fileExtension}"
    if (folder.findFile(filePath)?.exists() == true) {
        val duplicateFileName =
            findFreeDuplicateFileName(folder, validFileName, ExportMimeType.PDF.fileExtension)
        return exportPdfFileFolder(
            app,
            note,
            folder,
            duplicateFileName,
            pdfPrintListener,
            progress,
            counter,
            total,
            duplicateFileCount + 1,
        )
    }
    folder
        .createFileSafe(
            ExportMimeType.PDF.mimeType,
            validFileName,
            ".${ExportMimeType.PDF.fileExtension}",
        )
        .let { exportPdfFile(app, note, it, progress, counter, total, pdfPrintListener) }
}

fun exportPdfFile(
    app: ContextWrapper,
    note: BaseNote,
    outputFile: DocumentFile,
    progress: MutableLiveData<Progress>? = null,
    counter: AtomicInteger? = null,
    total: Int? = null,
    pdfPrintListener: PdfPrintListener? = null,
) {
    val tempFile = DocumentFile.fromFile(File(app.getExportedPath(), "temp.pdf"))
    val html =
        note.toHtml(
            MemoXPreferences.getInstance(app).showDateCreated(),
            app.getCurrentImagesDirectory(),
        )
    app.printPdf(
        tempFile,
        html,
        object : PdfPrintListener {
            override fun onSuccess(file: DocumentFile) {
                app.contentResolver.openOutputStream(outputFile.uri)?.use { outStream ->
                    app.contentResolver.openInputStream(file.uri)?.copyTo(outStream)
                }
                if (progress != null) {
                    progress.postValue(
                        BackupProgress(current = counter!!.incrementAndGet(), total = total!!)
                    )
                    if (counter.get() == total) {
                        pdfPrintListener?.onSuccess(file)
                    }
                } else {
                    pdfPrintListener?.onSuccess(file)
                }
            }

            override fun onFailure(message: CharSequence?) {
                pdfPrintListener?.onFailure(message)
            }
        },
    )
}

suspend fun exportPlainTextFileFolder(
    app: ContextWrapper,
    note: BaseNote,
    exportType: ExportMimeType,
    folder: DocumentFile,
    fileName: String = note.title,
    progress: MutableLiveData<Progress>? = null,
    counter: AtomicInteger? = null,
    total: Int? = null,
    duplicateFileCount: Int = 1,
): DocumentFile? {
    val validFileName = fileName.ifBlank { app.getString(R.string.note) }
    if (folder.findFile("$validFileName.${exportType.fileExtension}")?.exists() == true) {
        val duplicateFileName =
            findFreeDuplicateFileName(folder, validFileName, exportType.fileExtension)
        return exportPlainTextFileFolder(
            app,
            note,
            exportType,
            folder,
            duplicateFileName,
            progress,
            counter,
            total,
            duplicateFileCount + 1,
        )
    }
    return withContext(Dispatchers.IO) {
        val file =
            folder
                .createFileSafe(exportType.mimeType, validFileName, ".${exportType.fileExtension}")
                .let {
                    exportPlainTextFile(app, note, it, exportType)
                    it
                }
        progress?.postValue(BackupProgress(current = counter!!.incrementAndGet(), total = total!!))
        return@withContext file
    }
}

fun exportPlainTextFile(
    app: ContextWrapper,
    note: BaseNote,
    outputFile: DocumentFile,
    exportType: ExportMimeType,
) {
    app.contentResolver.openOutputStream(outputFile.uri)?.use { stream ->
        OutputStreamWriter(stream).use { writer ->
            writer.write(
                when (exportType) {
                    ExportMimeType.TXT ->
                        note.toTxt(includeTitle = false, includeCreationDate = false)

                    ExportMimeType.JSON -> note.toJson()
                    ExportMimeType.HTML ->
                        note.toHtml(
                            MemoXPreferences.getInstance(app).showDateCreated(),
                            app.getCurrentImagesDirectory(),
                        )

                    ExportMimeType.MD -> note.toMarkdown()
                    else -> TODO("Unsupported MimeType for Export: $exportType")
                }
            )
        }
    }
}

private fun findFreeDuplicateFileName(
    folder: DocumentFile,
    fileName: String,
    fileExtension: String,
): String {
    val existingNames = folder.listFiles().mapNotNull { it.name }.toSet()
    if ("$fileName.$fileExtension" !in existingNames) return fileName

    var index = 0
    var newName: String

    do {
        index++
        newName = "$fileName ($index).$fileExtension"
    } while (newName in existingNames)

    return "$fileName ($index)"
}

fun Context.exportPreferences(preferences: MemoXPreferences, uri: Uri): Boolean {
    try {
        contentResolver.openOutputStream(uri)?.use {
            it.write(preferences.toJsonString().toByteArray())
        } ?: return false
        return true
    } catch (e: IOException) {
        if (this is ContextWrapper) {
            log(TAG, throwable = e)
        } else {
            Log.e(TAG, "Export preferences failed", e)
        }
        return false
    }
}

fun LockedActivity<*>.exportNotes(
    notes: Collection<BaseNote>,
    mimeType: ExportMimeType,
    exportToFileResultLauncher: ActivityResultLauncher<Intent>,
    exportToFolderResultLauncher: ActivityResultLauncher<Intent>,
) {
    baseModel.selectedExportMimeType = mimeType
    if (notes.size == 1) {
        exportNote(notes.first(), mimeType, exportToFileResultLauncher)
    } else {
        lifecycleScope.launch {
            val intent =
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .apply { addCategory(Intent.CATEGORY_DEFAULT) }
                    .wrapWithChooser(this@exportNotes)
            exportToFolderResultLauncher.launch(intent)
        }
    }
}

fun LockedActivity<*>.exportNote(
    note: BaseNote,
    mimeType: ExportMimeType,
    exportToFileResultLauncher: ActivityResultLauncher<Intent>,
) {
    baseModel.selectedExportMimeType = mimeType
    val suggestedName =
        (note.title.ifBlank { getString(R.string.note) }) + "." + mimeType.fileExtension
    val intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .apply {
                type = mimeType.mimeType
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, suggestedName)
            }
            .wrapWithChooser(this)
    exportToFileResultLauncher.launch(intent)
}
