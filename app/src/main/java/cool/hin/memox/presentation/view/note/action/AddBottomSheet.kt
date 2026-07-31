package cool.hin.memox.presentation.view.note.action

import android.os.Build
import androidx.annotation.ColorInt
import cool.hin.memox.R
import cool.hin.memox.presentation.activity.note.NoteActionHandler

/** BottomSheet inside note for adding images, recording audio, attaching files, inserting checkbox. */
class AddBottomSheet(
    handler: NoteActionHandler,
    @ColorInt color: Int?,
    onInsertCheckbox: (() -> Unit)? = null,
) : ActionBottomSheet(createActions(handler, onInsertCheckbox), color) {

    companion object {
        const val TAG = "AddBottomSheet"

        fun createActions(
            actionHandler: NoteActionHandler,
            onInsertCheckbox: (() -> Unit)? = null,
        ) = listOf(
            Action(R.string.add_images, R.drawable.add_images) { _ ->
                actionHandler.addImages()
                true
            },
        ) + (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                listOf(
                    Action(R.string.record_audio, R.drawable.record_audio) { _ ->
                        actionHandler.recordAudio()
                        true
                    },
                )
            else listOf()
        ) + listOf(
            Action(R.string.attach_file, R.drawable.text_file) { _ ->
                actionHandler.attachFiles()
                true
            }
        ) + (
            if (onInsertCheckbox != null)
                listOf(
                    Action(R.string.add_checkbox, R.drawable.checkbox) { _ ->
                        onInsertCheckbox?.invoke()
                        true
                    }
                )
            else listOf()
        )
    }
}
