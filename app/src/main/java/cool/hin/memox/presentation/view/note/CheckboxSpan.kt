package cool.hin.memox.presentation.view.note

import android.text.Editable
import android.text.Selection
import android.text.Spannable
import android.text.style.ClickableSpan
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.EditText

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
        val editText = widget as? EditText ?: return
        val text = editText.text ?: return
        val start = text.getSpanStart(this)
        val end = text.getSpanEnd(this)
        if (start < 0 || end < 0) return

        isChecked = !isChecked
        val newChar = if (isChecked) CHECKED else UNCHECKED
        text.replace(start, end, newChar.toString())
        text.setSpan(this, start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Apply or remove strikethrough on the rest of the line
        updateStrikethrough(text, start)

        // Move cursor after the checkbox space
        val cursorPos = start + 2
        if (cursorPos <= text.length) {
            Selection.setSelection(text, cursorPos)
        }
    }

    /**
     * Apply or remove strikethrough on the line content after the checkbox.
     * The checkbox character itself is at [checkboxStart], followed by a space,
     * then the line content until the next newline or end of text.
     */
    private fun updateStrikethrough(text: Editable, checkboxStart: Int) {
        // Find the line content range: from after "☐ " to the end of the line
        val lineContentStart = checkboxStart + 2 // skip checkbox char + space

        // Find end of line
        val textStr = text.toString()
        val lineEnd = textStr.indexOf('\n', lineContentStart).let { if (it < 0) text.length else it }

        if (lineContentStart >= lineEnd) return

        if (isChecked) {
            // Apply strikethrough to line content
            text.setSpan(
                StrikethroughSpan(),
                lineContentStart,
                lineEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        } else {
            // Remove strikethrough from line content
            val spans = text.getSpans(lineContentStart, lineEnd, StrikethroughSpan::class.java)
            for (span in spans) {
                text.removeSpan(span)
            }
        }
    }

    override fun updateDrawState(ds: android.text.TextPaint) {
        // No underline for checkbox
    }
}
