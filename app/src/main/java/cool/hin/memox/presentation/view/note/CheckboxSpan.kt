package cool.hin.memox.presentation.view.note

import android.text.Editable
import android.text.Selection
import android.text.Spannable
import android.text.style.ClickableSpan
import android.view.View

/**
 * A [ClickableSpan] that represents a toggleable checkbox in an EditText.
 * Renders as Unicode characters: ☐ (unchecked) / ☑ (checked).
 * Toggling replaces the character and applies/removes strikethrough on the line content.
 */
class CheckboxSpan(var isChecked: Boolean) : ClickableSpan() {

    companion object {
        const val UNCHECKED = '☐'
        const val CHECKED = '☑'

        fun insertCheckbox(text: Editable, position: Int): CheckboxSpan {
            val span = CheckboxSpan(false)
            text.insert(position, UNCHECKED.toString() + " ")
            val start = position
            val end = position + 1
            text.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            return span
        }
    }

    override fun onClick(widget: View) {
        val text = (widget as? android.widget.EditText)?.text ?: return
        val start = text.getSpanStart(this)
        val end = text.getSpanEnd(this)
        if (start < 0 || end < 0) return

        isChecked = !isChecked
        val newChar = if (isChecked) CHECKED else UNCHECKED
        text.replace(start, end, newChar.toString())
        text.setSpan(this, start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Move cursor after the checkbox space
        val cursorPos = start + 2
        if (cursorPos <= text.length) {
            Selection.setSelection(text, cursorPos)
        }
    }

    override fun updateDrawState(ds: android.text.TextPaint) {
        // No underline for checkbox
    }
}
