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

        internal fun createActions(
            model: MemoXModel,
            actionHandler: NoteActionHandler,
            topActions: Collection<EditAction>,
            bottomAction: EditAction? = null,
        ): List<Action> {
            val allPossibleActions = EditAction.entries

            val actionsInBottomSheet =
                allPossibleActions.filter {
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
