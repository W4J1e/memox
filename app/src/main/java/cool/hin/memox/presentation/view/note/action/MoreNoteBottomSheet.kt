package cool.hin.memox.presentation.view.note.action

import androidx.annotation.ColorInt
import cool.hin.memox.data.model.Folder
import cool.hin.memox.presentation.activity.note.NoteActionHandler
import cool.hin.memox.presentation.viewmodel.MemoXModel
import cool.hin.memox.presentation.viewmodel.preference.EditAction

/** BottomSheet inside list-note for all common note actions. */
class MoreNoteBottomSheet(
    model: MemoXModel,
    @ColorInt color: Int?,
    actionHandler: NoteActionHandler,
    topActions: Collection<EditAction> = listOf(),
    bottomAction: EditAction? = null,
) : ActionBottomSheet(createActions(model, actionHandler, topActions, bottomAction), color) {

    companion object {
        const val TAG = "MoreNoteBottomSheet"

        /**
         * Explicit display order of the "more options" sheet.
         *
         * [EditAction.DELETE] and [EditAction.ATTACH_FILE] are intentionally absent: deleting is
         * done from the note list, attaching files moved to the "+" menu in the bottom left.
         * [EditAction.PIN_TO_STATUS] sits last because it has the longest label.
         */
        private val SHEET_ACTION_ORDER =
            listOf(
                EditAction.SEARCH,
                EditAction.PIN,
                EditAction.REMINDERS,
                EditAction.LABELS,
                EditAction.EXPORT,
                EditAction.DUPLICATE,
                EditAction.SHARE,
                EditAction.TOGGLE_VIEW_MODE,
                EditAction.CHANGE_COLOR,
                EditAction.LINK_NOTE,
                EditAction.RESTORE,
                EditAction.DELETE_FOREVER,
                EditAction.PIN_TO_STATUS,
            )

        internal fun createActions(
            model: MemoXModel,
            actionHandler: NoteActionHandler,
            topActions: Collection<EditAction>,
            bottomAction: EditAction? = null,
        ): List<Action> {
            val actionsInBottomSheet =
                SHEET_ACTION_ORDER.filter {
                    it !in topActions &&
                        it != bottomAction &&
                        (it != EditAction.RESTORE || model.folder == Folder.DELETED)
                }

            return actionsInBottomSheet.map { editAction ->
                val (title, icon) =
                    editAction.getTitleAndIcon(
                        model.pinned,
                        model.viewMode.value,
                        model.folder,
                        model.type,
                        model.isPinnedToStatus,
                    )
                Action(title, icon) { _ ->
                    actionHandler.handleAction(editAction)
                    true
                }
            }
        }
    }
}
