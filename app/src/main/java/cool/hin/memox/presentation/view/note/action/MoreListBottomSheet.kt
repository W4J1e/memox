package cool.hin.memox.presentation.view.note.action

import androidx.annotation.ColorInt
import cool.hin.memox.presentation.activity.note.NoteActionHandler
import cool.hin.memox.presentation.activity.note.NoteListActionHandler
import cool.hin.memox.presentation.viewmodel.MemoXModel
import cool.hin.memox.presentation.viewmodel.preference.EditAction
import cool.hin.memox.presentation.viewmodel.preference.EditListAction

/** BottomSheet inside list-note for all common note actions and list-item actions. */
class MoreListBottomSheet(
    model: MemoXModel,
    @ColorInt color: Int?,
    actionHandler: NoteActionHandler,
    listActionHandler: NoteListActionHandler,
    topActions: Collection<EditAction> = listOf(),
    bottomAction: EditAction? = null,
) :
    ActionBottomSheet(
        createActions(model, actionHandler, listActionHandler, topActions, bottomAction),
        color,
    ) {

    companion object {
        const val TAG = "MoreListBottomSheet"

        private fun createActions(
            model: MemoXModel,
            actionHandler: NoteActionHandler,
            listActionHandler: NoteListActionHandler,
            topActions: Collection<EditAction>,
            bottomAction: EditAction? = null,
        ) =
            MoreNoteBottomSheet.createActions(
                model,
                actionHandler,
                topActions = topActions,
                bottomAction = bottomAction,
            ) +
                EditListAction.entries.mapIndexed { index, editAction ->
                    Action(
                        editAction.textResId,
                        editAction.drawableResId,
                        showDividerAbove = index == 0,
                    ) { _ ->
                        listActionHandler.handleAction(editAction)
                        true
                    }
                }
    }
}
