package cool.hin.memox.presentation.viewmodel.progress

import cool.hin.memox.R
import cool.hin.memox.presentation.view.misc.Progress

open class AddFilesProgress(
    current: Int = 0,
    total: Int = 0,
    inProgress: Boolean = true,
    indeterminate: Boolean = false,
) : Progress(R.string.adding_files, current, total, inProgress, indeterminate)
