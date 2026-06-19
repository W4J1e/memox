package cool.hin.memox.utils

import cool.hin.memox.presentation.viewmodel.MemoXModel

data class FileError(
    val name: String,
    val description: String,
    val fileType: MemoXModel.FileType,
)
