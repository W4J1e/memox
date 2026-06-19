package cool.hin.memox.presentation

import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.style.ImageSpan
import cool.hin.memox.data.model.FileAttachment

/**
 * The Unicode OBJECT REPLACEMENT CHARACTER used as an inline placeholder for images inside the note
 * body text. The persisted body string keeps these characters so that each image's position is
 * implicitly encoded by the character itself (the N-th placeholder corresponds to the N-th image in
 * the note's `images` list). An [InlineImageSpan] is attached to each placeholder at runtime to
 * actually render the image.
 */
const val IMAGE_PLACEHOLDER = '\uFFFC'

/**
 * [ImageSpan] that additionally carries the [FileAttachment] it represents, so the image list can
 * be re-derived from the body's spans (in text order) whenever the body changes.
 */
class InlineImageSpan(drawable: Drawable, val attachment: FileAttachment) : ImageSpan(drawable)

/** Returns a copy of this string with all inline image placeholders removed. */
fun String.withoutImagePlaceholders(): String = replace(IMAGE_PLACEHOLDER.toString(), "")

/**
 * Removes all inline image placeholders from this [Editable] in place. SpannableStringBuilder
 * automatically adjusts the bounds of remaining spans when characters are deleted, so style spans
 * (bold/italic/links) keep pointing at the correct text. Only meant for read-only display contexts
 * (overview, widget, export) ? never call this on the editor's live body.
 */
fun Editable.withoutImagePlaceholders(): Editable {
    var i = 0
    while (i < length) {
        if (this[i] == IMAGE_PLACEHOLDER) {
            delete(i, i + 1)
        } else {
            i++
        }
    }
    return this
}
