package cool.hin.memox.presentation.activity.note

import android.content.ContextWrapper
import android.graphics.Typeface
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import cool.hin.memox.R
import cool.hin.memox.presentation.add
import cool.hin.memox.presentation.createBoldSpan
import cool.hin.memox.presentation.showToast
import cool.hin.memox.presentation.view.misc.StylableEditTextWithHistory
import cool.hin.memox.utils.log

class FormattingActionModeCallback(
    private val context: ContextWrapper,
    private val editTextView: StylableEditTextWithHistory,
) : ActionMode.Callback {
    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        editTextView.isActionModeOn = true
        // Try block is there because this will crash on MiUI as Xiaomi has a broken
        // ActionMode implementation
        try {
            menu?.apply {
                add(R.string.link, 0, showAsAction = MenuItem.SHOW_AS_ACTION_NEVER) {
                    editTextView.showAddLinkDialog(context, mode = mode)
                }
                add(R.string.bold, 0, showAsAction = MenuItem.SHOW_AS_ACTION_NEVER) {
                    editTextView.applySpan(createBoldSpan())
                    mode?.finish()
                }
                add(R.string.italic, 0, showAsAction = MenuItem.SHOW_AS_ACTION_NEVER) {
                    editTextView.applySpan(StyleSpan(Typeface.ITALIC))
                    mode?.finish()
                }
                add(R.string.monospace, 0, showAsAction = MenuItem.SHOW_AS_ACTION_NEVER) {
                    editTextView.applySpan(TypefaceSpan("monospace"))
                    mode?.finish()
                }
                add(R.string.strikethrough, 0, showAsAction = MenuItem.SHOW_AS_ACTION_NEVER) {
                    editTextView.applySpan(StrikethroughSpan())
                    mode?.finish()
                }
                add(R.string.clear_formatting, 0, showAsAction = MenuItem.SHOW_AS_ACTION_NEVER) {
                    editTextView.clearFormatting()
                    mode?.finish()
                }
            }
        } catch (exception: Exception) {
            context.log(TAG, msg = "Initialization failed", throwable = exception)
            context.showToast(
                exception.message ?: "Initialization of FormattingActionModeCallback failed"
            )
            return false
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        editTextView.isActionModeOn = false
    }

    companion object {
        private const val TAG = "FormattingActionModeCallback"
    }
}
