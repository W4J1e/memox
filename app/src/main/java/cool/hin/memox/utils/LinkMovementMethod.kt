package cool.hin.memox.utils

import android.graphics.RectF
import android.text.Selection
import android.text.Spannable
import android.text.method.ArrowKeyMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.MotionEvent
import android.widget.TextView
import cool.hin.memox.presentation.InlineImageSpan
import cool.hin.memox.presentation.view.note.CheckboxSpan

/**
 * Inspired by https://github.com/saket/Better-Link-Movement-Method Intercepts touch events on links
 * and dispatches them accordingly. Also optionally intercepts touches on [InlineImageSpan]s (inline
 * images in the note body) so they can be opened full-screen, and [CheckboxSpan]s for toggling.
 */
class LinkMovementMethod(
    private val onClick: (span: URLSpan) -> Unit,
    private val onImageClick: ((span: InlineImageSpan) -> Unit)? = null,
    private val onCheckboxClick: ((span: CheckboxSpan) -> Unit)? = null,
) : ArrowKeyMovementMethod() {

    private val touchedLineBounds = RectF()
    private var isUrlHighlighted = false

    private var clickableSpanUnderTouchOnActionDown: ClickableSpan? = null
    private var imageSpanUnderTouchOnActionDown: InlineImageSpan? = null
    private var checkboxSpanUnderTouchOnActionDown: CheckboxSpan? = null

    override fun onTouchEvent(textView: TextView, text: Spannable, event: MotionEvent): Boolean {
        textView.autoLinkMask = 0

        val linkSpanUnderTouch = findLinkSpanUnderTouch(textView, text, event)
        val imageSpanUnderTouch =
            if (onImageClick != null) {
                findImageSpanUnderTouch(textView, text, event)
            } else null
        val checkboxSpanUnderTouch =
            if (onCheckboxClick != null) {
                findCheckboxSpanUnderTouch(textView, text, event)
            } else null

        if (event.action == MotionEvent.ACTION_DOWN) {
            clickableSpanUnderTouchOnActionDown = linkSpanUnderTouch
            imageSpanUnderTouchOnActionDown = imageSpanUnderTouch
            checkboxSpanUnderTouchOnActionDown = checkboxSpanUnderTouch
        }

        val touchStartedOverALinkSpan = clickableSpanUnderTouchOnActionDown != null
        val touchStartedOverAnImageSpan = imageSpanUnderTouchOnActionDown != null
        val touchStartedOverACheckboxSpan = checkboxSpanUnderTouchOnActionDown != null

        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (linkSpanUnderTouch != null) {
                    highlightUrl(linkSpanUnderTouch, text)
                }
                touchStartedOverALinkSpan || touchStartedOverAnImageSpan || touchStartedOverACheckboxSpan
            }
            MotionEvent.ACTION_UP -> {
                if (
                    touchStartedOverALinkSpan &&
                        linkSpanUnderTouch === clickableSpanUnderTouchOnActionDown
                ) {
                    dispatchUrlClick(linkSpanUnderTouch)
                } else if (
                    touchStartedOverAnImageSpan &&
                        imageSpanUnderTouch === imageSpanUnderTouchOnActionDown
                ) {
                    dispatchImageClick(imageSpanUnderTouch)
                } else if (
                    touchStartedOverACheckboxSpan &&
                        checkboxSpanUnderTouch === checkboxSpanUnderTouchOnActionDown
                ) {
                    dispatchCheckboxClick(checkboxSpanUnderTouch)
                }
                cleanupOnTouchUp(textView)
                touchStartedOverALinkSpan || touchStartedOverAnImageSpan || touchStartedOverACheckboxSpan
            }
            MotionEvent.ACTION_CANCEL -> {
                cleanupOnTouchUp(textView)
                false
            }
            MotionEvent.ACTION_MOVE -> {
                if (linkSpanUnderTouch != null) {
                    highlightUrl(linkSpanUnderTouch, text)
                } else removeUrlHighlightColor(textView)
                touchStartedOverALinkSpan || touchStartedOverAnImageSpan || touchStartedOverACheckboxSpan
            }
            else -> false
        }
    }

    private fun cleanupOnTouchUp(textView: TextView) {
        clickableSpanUnderTouchOnActionDown = null
        imageSpanUnderTouchOnActionDown = null
        checkboxSpanUnderTouchOnActionDown = null
        removeUrlHighlightColor(textView)
    }

    /** Returns the character offset under the touch event, or null if it falls outside the text. */
    private fun findOffsetUnderTouch(textView: TextView, event: MotionEvent): Int? {
        val layout = textView.layout ?: return null
        var touchX = event.x.toInt()
        var touchY = event.y.toInt()

        touchX -= textView.totalPaddingLeft
        touchY -= textView.totalPaddingTop

        touchX += textView.scrollX
        touchY += textView.scrollY

        val touchedLine = layout.getLineForVertical(touchY)
        val touchOffset = layout.getOffsetForHorizontal(touchedLine, touchX.toFloat())

        touchedLineBounds.left = layout.getLineLeft(touchedLine)
        touchedLineBounds.top = layout.getLineTop(touchedLine).toFloat()
        touchedLineBounds.right = layout.getLineWidth(touchedLine) + touchedLineBounds.left
        touchedLineBounds.bottom = layout.getLineBottom(touchedLine).toFloat()

        return if (touchedLineBounds.contains(touchX.toFloat(), touchY.toFloat())) {
            touchOffset
        } else null
    }

    private fun findLinkSpanUnderTouch(
        textView: TextView,
        text: Spannable,
        event: MotionEvent,
    ): URLSpan? {
        val offset = findOffsetUnderTouch(textView, event) ?: return null
        return text.getSpans(offset, offset, URLSpan::class.java).firstOrNull()
    }

    private fun findImageSpanUnderTouch(
        textView: TextView,
        text: Spannable,
        event: MotionEvent,
    ): InlineImageSpan? {
        val offset = findOffsetUnderTouch(textView, event) ?: return null
        return text.getSpans(offset, offset, InlineImageSpan::class.java).firstOrNull()
    }

    private fun findCheckboxSpanUnderTouch(
        textView: TextView,
        text: Spannable,
        event: MotionEvent,
    ): CheckboxSpan? {
        val offset = findOffsetUnderTouch(textView, event) ?: return null
        return text.getSpans(offset, offset, CheckboxSpan::class.java).firstOrNull()
    }

    private fun removeUrlHighlightColor(textView: TextView) {
        if (isUrlHighlighted) {
            isUrlHighlighted = false
            Selection.removeSelection(textView.text as Spannable)
        }
    }

    private fun highlightUrl(span: URLSpan?, text: Spannable) {
        if (!isUrlHighlighted) {
            isUrlHighlighted = true
            val spanStart = text.getSpanStart(span)
            val spanEnd = text.getSpanEnd(span)
            Selection.setSelection(text, spanStart, spanEnd)
        }
    }

    private fun dispatchUrlClick(urlSpan: URLSpan?) {
        if (urlSpan != null) {
            onClick.invoke(urlSpan)
        }
    }

    private fun dispatchImageClick(imageSpan: InlineImageSpan?) {
        if (imageSpan != null) {
            onImageClick?.invoke(imageSpan)
        }
    }

    private fun dispatchCheckboxClick(checkboxSpan: CheckboxSpan?) {
        if (checkboxSpan != null) {
            onCheckboxClick?.invoke(checkboxSpan)
        }
    }
}
