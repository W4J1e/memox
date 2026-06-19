package cool.hin.memox.presentation.view.note.action

import androidx.annotation.ColorInt
import cool.hin.memox.R
import cool.hin.memox.presentation.activity.note.NoteActionHandler

/** BottomSheet inside text note for adding files, recording audio. */
class AddNoteBottomSheet(actionHandler: NoteActionHandler, @ColorInt color: Int?) :
    ActionBottomSheet(createActions(actionHandler), color) {

    companion object {
        const val TAG = "AddNoteBottomSheet"

        fun createActions(actionHandler: NoteActionHandler) =
            AddBottomSheet.createActions(actionHandler) +
                listOf(
                    Action(R.string.link_note, R.drawable.notebook) { _ ->
                        actionHandler.addNoteLink()
                        true
                    }
                )
    }
}
