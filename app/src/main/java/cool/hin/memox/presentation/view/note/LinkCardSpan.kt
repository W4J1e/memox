package cool.hin.memox.presentation.view.note

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Spannable
import android.text.style.ReplacementSpan
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import cool.hin.memox.utils.LinkPreview
import cool.hin.memox.utils.LinkPreviewCache
import cool.hin.memox.utils.isWebUrl
import java.lang.ref.WeakReference
import java.net.URL

/**
 * Renders a web link as an AnyType-style card (favicon + title + url) inside an EditText / TextView.
 *
 * This span is purely visual. The actual link semantics (click handling, note serialization) are
 * still carried by a co-located [android.text.style.URLSpan]; this span only replaces the link text
 * with a drawn card. The host [TextView] is captured via [attach] (called from `applyLinkCards`)
 * so the preview can be fetched asynchronously and the view invalidated when it arrives.
 */
class LinkCardSpan(private val url: String) : ReplacementSpan() {

    private var tvRef: WeakReference<TextView>? = null
    private var preview: LinkPreview? = null
    private var fetching = false
    private var cardW = 0
    private var cardH = 0
    private var bitmap: Bitmap? = null

    val linkUrl: String get() = url

    fun attach(tv: TextView) {
        tvRef = WeakReference(tv)
        if (!fetching) {
            fetching = true
            preview = LinkPreviewCache.getCached(tv.context, url)
            LinkPreviewCache.fetch(tv.context, url) { p ->
                preview = p
                rebuild(tv)
                tv.post { tv.invalidate() }
            }
        }
    }

    private fun density(tv: TextView) = tv.resources.displayMetrics.density

    private fun host(): String = runCatching { URL(url).host }.getOrDefault(url)

    private fun rebuild(tv: TextView) {
        val d = density(tv)
        val w = if (cardW > 0) cardW else (300 * d).toInt()
        val h = (64 * d).toInt()
        cardW = w
        cardH = h
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val surface = MaterialColors.getColor(tv, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val onSurface = MaterialColors.getColor(tv, android.R.attr.textColorPrimary, Color.BLACK)
        val onSurfaceVar = MaterialColors.getColor(tv, android.R.attr.textColorSecondary, Color.GRAY)
        val r = 12 * d

        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, Paint().apply { color = surface; isAntiAlias = true })
        c.drawRoundRect(
            RectF(0.5f * d, 0.5f * d, w.toFloat() - 0.5f * d, h.toFloat() - 0.5f * d),
            r,
            r,
            Paint().apply { color = Color.argb(20, 0, 0, 0); style = Paint.Style.STROKE; strokeWidth = 1f * d; isAntiAlias = true },
        )

        val favSize = 40 * d
        val fx = 12 * d
        val fy = (h - favSize) / 2f
        val fav = preview?.faviconPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        if (fav != null) {
            val scaled = Bitmap.createScaledBitmap(fav, favSize.toInt(), favSize.toInt(), true)
            c.save()
            c.clipPath(Path().apply { addRoundRect(RectF(fx, fy, fx + favSize, fy + favSize), 8 * d, 8 * d, Path.Direction.CW) })
            c.drawBitmap(scaled, fx, fy, null)
            c.restore()
        } else {
            c.drawRoundRect(RectF(fx, fy, fx + favSize, fy + favSize), 8 * d, 8 * d, Paint().apply { color = Color.argb(30, 0, 0, 0); isAntiAlias = true })
            val tp = Paint().apply { color = onSurface; textSize = 18 * d; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            c.drawText(host().take(1).uppercase(), fx + favSize / 2f, fy + favSize / 2f - (tp.ascent() + tp.descent()) / 2f, tp)
        }

        val tx = fx + favSize + 12 * d
        val maxTextW = (w - tx - 12 * d).coerceAtLeast(1f)
        val titleP = Paint().apply { color = onSurface; textSize = 14 * d; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val urlP = Paint().apply { color = onSurfaceVar; textSize = 12 * d; isAntiAlias = true }
        val title = (preview?.title ?: host()).takeIf { it.isNotBlank() } ?: host()
        c.drawText(ellipsize(titleP, title, maxTextW), tx, h / 2f - 2 * d, titleP)
        c.drawText(ellipsize(urlP, url, maxTextW), tx, h / 2f + 16 * d, urlP)

        bitmap = bmp
    }

    private fun ellipsize(p: Paint, text: String, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        var t = text
        while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val tv = tvRef?.get()
        val d = tv?.resources?.displayMetrics?.density ?: 1f
        val tvW = tv?.width?.takeIf { it > 0 }?.minus(tv.paddingLeft + tv.paddingRight) ?: 0
        cardW = if (tvW > 0) minOf(tvW, (320 * d).toInt()) else (300 * d).toInt()
        cardH = (64 * d).toInt()
        fm?.let {
            it.ascent = -cardH
            it.descent = 0
            it.top = -cardH
            it.bottom = 0
        }
        return cardW
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val tv = tvRef?.get() ?: return
        if (bitmap == null) rebuild(tv)
        bitmap?.let { canvas.drawBitmap(it, x, top.toFloat(), null) }
    }
}

/**
 * Adds a [LinkCardSpan] next to every web [android.text.style.URLSpan] in this text (without
 * duplicating) and attaches each card to [tv] so its preview is fetched and the view redrawn.
 * Call this after the text has been assigned to a TextView/EditText.
 */
fun Spannable.applyLinkCards(tv: TextView) {
    val enabled = MemoXPreferences.getInstance(tv.context).linkCardEnabled.value
    if (!enabled) {
        // 关闭卡片展示：移除已有卡片，回退为可点击的纯文本链接。
        getSpans(0, length, LinkCardSpan::class.java).forEach { removeSpan(it) }
        return
    }
    getSpans(0, length, android.text.style.URLSpan::class.java).forEach { us ->
        val s = getSpanStart(us)
        val e = getSpanEnd(us)
        if (us.url.isWebUrl() && getSpans(s, e, LinkCardSpan::class.java).isEmpty()) {
            setSpan(LinkCardSpan(us.url), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    getSpans(0, length, LinkCardSpan::class.java).forEach { it.attach(tv) }
}
