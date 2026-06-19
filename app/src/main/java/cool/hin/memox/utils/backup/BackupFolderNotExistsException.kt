package cool.hin.memox.utils.backup

class BackupFolderNotExistsException(val path: String, cause: Throwable? = null) :
    IllegalArgumentException("Folder '$path' does not exist", cause)
