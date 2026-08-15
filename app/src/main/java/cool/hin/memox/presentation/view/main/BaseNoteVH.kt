package cool.hin.memox.presentation.view.main

import android.view.View.GONE
import android.view.View.VISIBLE
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import android.os.Handler
import android.os.Looper
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import cool.hin.memox.R
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.SpanRepresentation
import cool.hin.memox.data.model.Type
import cool.hin.memox.databinding.RecyclerBaseNoteBinding
import cool.hin.memox.presentation.applySpans
import cool.hin.memox.presentation.displayFormattedTimestamp
import cool.hin.memox.presentation.dp
import cool.hin.memox.presentation.extractColor
import cool.hin.memox.presentation.setControlsContrastColorForAllViews
import cool.hin.memox.presentation.setTextSizeSp
import cool.hin.memox.presentation.setupReminderChip
import cool.hin.memox.presentation.view.misc.ItemListener
import cool.hin.memox.presentation.viewmodel.preference.DateFormat
import cool.hin.memox.presentation.viewmodel.preference.NotesSortBy
import cool.hin.memox.presentation.viewmodel.preference.TimeFormat
import cool.hin.memox.presentation.viewmodel.preference.displayBodySize
import cool.hin.memox.presentation.viewmodel.preference.displaySmallerSize
import cool.hin.memox.presentation.viewmodel.preference.displayTitleSize
import cool.hin.memox.presentation.withoutImagePlaceholders
import java.io.File

data class BaseNoteVHPreferences(
    val textSize: Float,
    val hideLabels: Boolean,
    val sortedBy: NotesSortBy,
)

class BaseNoteVH(
    private val binding: RecyclerBaseNoteBinding,
    private val dateFormat: DateFormat,
    private val timeFormat: TimeFormat,
    private val preferences: BaseNoteVHPreferences,
    listener: ItemListener,
) : RecyclerView.ViewHolder(binding.root) {

    private var searchKeyword: String = ""

    /** True when the list is shown as a staggered grid (half-width cards). Drives header sizing. */
    private var isGrid: Boolean = false

    companion object {
        // Coalesces multiple post-load rebalances into one so the staggered grid settles once
        // instead of shuffling on every image.
        private val rebalanceHandler = Handler(Looper.getMainLooper())
        private var rebalanceRunnable: Runnable? = null
    }

    fun setSearchKeyword(keyword: String) {
        this.searchKeyword = keyword
    }

    init {

        binding.apply {
            val titleTextSize = preferences.textSize.displayTitleSize
            val bodyTextSize = preferences.textSize.displayBodySize
            Title.setTextSizeSp(titleTextSize)
            Date.setTextSizeSp(bodyTextSize)
            Note.setTextSizeSp(bodyTextSize)

            // Overview: one title line, up to four body lines (no forced blank space).
            Title.maxLines = 1
            Note.maxLines = 4

            root.setOnClickListener { listener.onClick(absoluteAdapterPosition) }

            root.setOnLongClickListener {
                listener.onLongClick(absoluteAdapterPosition)
                return@setOnLongClickListener true
            }

            ReminderChip.setOnClickListener { listener.onReminderClick(absoluteAdapterPosition) }
            ReminderChip.setOnLongClickListener {
                listener.onLongClick(absoluteAdapterPosition)
                return@setOnLongClickListener true
            }
        }
    }

    fun updateCheck(checked: Boolean, color: String) {
        val ctx = binding.root.context
        val outline = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorOutline,
            Color.GRAY,
        )
        val primary = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorPrimary,
            Color.BLUE,
        )
        if (checked) {
            binding.root.strokeWidth = 2.dp
            binding.root.setStrokeColor(primary)
            binding.root.cardElevation = 4.dp.toFloat()
        } else {
            binding.root.strokeWidth = if (color == BaseNote.COLOR_DEFAULT) 1.dp else 0
            binding.root.setStrokeColor(outline)
            binding.root.cardElevation = 1.dp.toFloat()
        }
        binding.root.isChecked = checked
    }

    fun bind(baseNote: BaseNote, imageRoot: File?, checked: Boolean, sortBy: NotesSortBy, isGrid: Boolean) {
        this.isGrid = isGrid
        // Tighter horizontal margins in grid so the two columns sit closer together; list keeps
        // the 8dp from the layout XML.
        val hMargin = if (isGrid) 4.dp else 8.dp
        (binding.root.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
            marginStart = hMargin
            marginEnd = hMargin
        }
        updateCheck(checked, baseNote.color)

        bindNote(baseNote, searchKeyword)
        val (date, datePrefixResId) =
            when (sortBy) {
                NotesSortBy.CREATION_DATE -> Pair(baseNote.timestamp, R.string.creation_date)
                NotesSortBy.MODIFIED_DATE ->
                    Pair(baseNote.modifiedTimestamp, R.string.modified_date)
                else -> Pair(null, null)
            }
        binding.Date.apply {
            displayFormattedTimestamp(date, dateFormat, timeFormat, datePrefixResId)
            setTextSizeSp(preferences.textSize.displaySmallerSize)
        }

        setImages(baseNote, imageRoot)
        setFiles()

        binding.Title.apply {
            isVisible = baseNote.title.isNotEmpty() || baseNote.locked
            updatePadding(bottom = if (baseNote.hasNoContents()) 0 else 8.dp)
            if (searchKeyword.isNotBlank()) {
                val snippet = extractSearchSnippet(baseNote.title, searchKeyword)
                if (snippet != null) {
                    showSearchSnippet(snippet)
                } else text = baseNote.title
            } else text = baseNote.title
        }

        // #2/#7: 首页卡片显示所属文件夹（图标 + 名称，无则不显示），标签改为 "#标签" 文本跟随
        if (baseNote.folderId.isNotEmpty()) {
            binding.FolderName.text = baseNote.folderId
            binding.FolderChip.visibility = VISIBLE
        } else {
            binding.FolderChip.visibility = GONE
        }
        if (!preferences.hideLabels && baseNote.labels.isNotEmpty()) {
            binding.LabelsText.text = baseNote.labels.joinToString(" ") { "#$it" }
            binding.LabelsText.visibility = VISIBLE
        } else {
            binding.LabelsText.visibility = GONE
        }

        if (baseNote.isEmpty()) {
            binding.Title.apply {
                setText(baseNote.getEmptyMessage())
                isVisible = true
            }
        }
        binding.ReminderChip.setupReminderChip(
            baseNote,
            dateFormat,
            timeFormat,
            preferences.textSize.displaySmallerSize,
        )
        setColor(baseNote.color)
    }

    private fun bindNote(baseNote: BaseNote, keyword: String) {
        binding.LinearLayout.visibility = GONE
        if (baseNote.locked) {
            // For locked notes, only show title - hide body content
            binding.Note.visibility = GONE
            return
        }
        if (keyword.isBlank()) {
            bindNote(baseNote.body, baseNote.spans, baseNote.title.isEmpty())
            return
        }
        binding.Note.apply {
            val snippet = extractSearchSnippet(baseNote.body.withoutImagePlaceholders(), keyword)
            if (snippet == null) {
                bindNote(baseNote.body, baseNote.spans, baseNote.title.isEmpty())
            } else {
                showSearchSnippet(snippet)
            }
        }
    }

    private fun bindNote(body: String, spans: List<SpanRepresentation>, isTitleEmpty: Boolean) {
        binding.Note.apply {
            // Strip inline image placeholders for the overview; spans auto-adjust to the remaining
            // text. The full images are still shown via setImages(..) below.
            text = body.applySpans(spans).withoutImagePlaceholders()
            // Show up to four lines of body in the overview (short notes don't get blank padding).
            maxLines = 4
            isVisible = body.isNotEmpty()
        }
    }

    private fun setColor(color: String) {
        binding.root.apply {
            val colorInt = context.extractColor(color)
            setCardBackgroundColor(colorInt)
            setControlsContrastColorForAllViews(colorInt)
        }
    }

    private fun setImages(baseNote: BaseNote, mediaRoot: File?) {
        binding.apply {
            // Hide legacy placeholders that are no longer used in the overview.
            Message.visibility = GONE
            ImageViewMore.visibility = GONE
            NoteThumbnail.visibility = GONE
            Glide.with(NoteThumbnail.context).clear(NoteThumbnail)
            Glide.with(ImageView.context).clear(ImageView)
            ImageView.visibility = GONE
            ImageLayout.visibility = GONE

            // Reset header to a clean, mode-neutral baseline on EVERY bind. Without this, a card
            // recycled between modes keeps leftover scaleType / dimensionRatio / height and renders
            // wrong: grid -> list showed a cropped banner with a big empty gap; list -> grid cropped
            // every image into the 150dp container.
            val ivLp = ImageView.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            ivLp.dimensionRatio = null
            ivLp.height = 0
            ivLp.width = 0
            ImageView.layoutParams = ivLp
            ImageView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            ImageView.adjustViewBounds = false
            ImageView.maxHeight = Int.MAX_VALUE

            val showBanner = baseNote.type == Type.NOTE && !baseNote.locked
            val firstImage = baseNote.images.firstOrNull()
            if (showBanner && firstImage != null && mediaRoot != null) {
                val file = File(mediaRoot, firstImage.localName)
                Log.d("MemoXImg", "setImages isGrid=$isGrid file=${firstImage.localName}")
                if (isGrid) {
                    // GRID (half width): show the FULL image, never crop. The container
                    // (ImageLayout) wraps the image height (WRAP_CONTENT) and the ImageView uses
                    // adjustViewBounds so it sizes itself to the image's own aspect ratio — NO async
                    // layout-param mutation, so a card recycled grid -> list can never keep a stale
                    // grid height that bleeds into list mode (that was the "list still shows grid's
                    // cropped tall header" bug). Very tall portraits are capped to 85% of the screen
                    // via maxHeight; fitCenter then letterboxes the full image (no crop).
                    //
                    // ImageLayout starts GONE; reveal it (VISIBLE) before Glide loads so the
                    // ViewTarget can measure a non-zero width and decode (no deadlock).
                    val ilLp = ImageLayout.layoutParams
                    ilLp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ImageLayout.layoutParams = ilLp
                    ImageLayout.visibility = VISIBLE
                    ImageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    ImageView.adjustViewBounds = true
                    val ivLp = ImageView.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                    ivLp.width = 0
                    ivLp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ivLp.dimensionRatio = null
                    ImageView.layoutParams = ivLp
                    ImageView.maxHeight = (binding.root.resources.displayMetrics.heightPixels * 0.85f).toInt()
                    ImageView.visibility = VISIBLE
                    val maxW = binding.root.resources.displayMetrics.widthPixels
                    val maxH = (binding.root.resources.displayMetrics.heightPixels * 0.85f).toInt()
                    Glide.with(ImageView.context)
                        .load(file)
                        .override(maxW, maxH)
                        .listener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(
                                e: GlideException?,
                                model: Any?,
                                target: Target<Drawable>,
                                isFirstResource: Boolean,
                            ): Boolean {
                                ImageLayout.visibility = GONE
                                ImageView.visibility = GONE
                                return false
                            }

                            override fun onResourceReady(
                                resource: Drawable?,
                                model: Any?,
                                target: Target<Drawable>,
                                dataSource: DataSource,
                                isFirstResource: Boolean,
                            ): Boolean {
                                // Sizing is handled by adjustViewBounds; nothing to mutate here.
                                // Guard against a stale callback that fired after this card was
                                // already recycled to list mode.
                                if (isGrid) {
                                    rebalanceGrid()
                                }
                                return false
                            }
                        })
                        .into(ImageView)
                    Log.d("MemoXImg", "  GRID wrap maxHeight=${(binding.root.resources.displayMetrics.heightPixels * 0.85f).toInt()}")
                } else {
                    // LIST (full width): fixed 150dp banner + centerCrop (e171ad26 / 63877692 style).
                    // Pin the banner height DIRECTLY on the ImageView (concrete px, not 0dp match-
                    // constraint) AND force Glide's decode size via .override(). This makes the
                    // banner immune to recycled grid state on ImageLayout and to Glide decoding to
                    // a stale (tall) measured size — the two remaining causes of "grid -> list
                    // still shows a taller, more-cropped header".
                    val density = binding.root.resources.displayMetrics.density
                    val bannerPx = (150f * density).toInt()
                    val cardW = binding.root.width.takeIf { it > 0 }
                        ?: binding.root.resources.displayMetrics.widthPixels

                    // ImageLayout back to 150dp (grid leaves it WRAP_CONTENT).
                    val ilLp = ImageLayout.layoutParams
                    ilLp.height = bannerPx
                    ImageLayout.layoutParams = ilLp

                    // ImageView pinned to 150dp explicitly; clear adjustViewBounds / maxHeight /
                    // dimensionRatio left by grid so a recycled card can't keep grid's tall sizing.
                    val ivLp2 =
                        ImageView.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                    ivLp2.width = 0
                    ivLp2.height = bannerPx
                    ivLp2.dimensionRatio = null
                    ImageView.layoutParams = ivLp2
                    ImageView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    ImageView.adjustViewBounds = false
                    ImageView.maxHeight = Int.MAX_VALUE

                    ImageLayout.visibility = VISIBLE
                    ImageView.visibility = VISIBLE

                    Glide.with(ImageView.context)
                        .load(file)
                        .override(cardW, bannerPx)
                        .centerCrop()
                        .into(ImageView)
                    Log.d("MemoXImg", "  LIST bannerPx=$bannerPx cardW=$cardW")
                }
            } else {
                if (baseNote.locked) {
                    // Locked notes hide their body/list; show a lock icon in the thumbnail slot.
                    NoteThumbnail.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    NoteThumbnail.setPadding(8.dp, 8.dp, 8.dp, 8.dp)
                    NoteThumbnail.setImageResource(R.drawable.lock_big)
                    NoteThumbnail.visibility = VISIBLE
                }
            }
        }
    }

    private fun rebalanceGrid() {
        val rv = binding.root.parent as? RecyclerView ?: return
        if (rebalanceRunnable == null) {
            rebalanceRunnable = Runnable {
                rebalanceRunnable = null
                (rv.layoutManager as? StaggeredGridLayoutManager)?.invalidateSpanAssignments()
            }
        }
        rebalanceHandler.removeCallbacks(rebalanceRunnable!!)
        rebalanceHandler.postDelayed(rebalanceRunnable!!, 100)
    }

    private fun setFiles() {
        binding.apply {
            // Always hide files in overview to avoid OBJ icon display issue
            FileViewLayout.visibility = GONE
        }
    }

    private fun BaseNote.isEmpty() = title.isBlank() && hasNoContents() && images.isEmpty()

    private fun BaseNote.hasNoContents() = body.isEmpty() && items.isEmpty()

    private fun BaseNote.getEmptyMessage() = R.string.empty_note
}
