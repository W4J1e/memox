package cool.hin.memox.presentation.view.note.action

import androidx.annotation.ColorInt
import cool.hin.memox.presentation.viewmodel.ExportMimeType
import cool.hin.memox.presentation.viewmodel.preference.EditAction

class ExportBottomSheet(@ColorInt color: Int?, callback: (ExportMimeType) -> Unit) :
    ActionBottomSheet(createActions(callback), color) {

    companion object {
        const val TAG = "ExportBottomSheet"

        private fun createActions(callback: (ExportMimeType) -> Unit): List<Action> {
            return ExportMimeType.entries.map { mimeType ->
                Action(labelResId = EditAction.EXPORT.textResId, label = mimeType.name) { _ ->
                    callback.invoke(mimeType)
                    true
                }
            }
        }
    }
}
