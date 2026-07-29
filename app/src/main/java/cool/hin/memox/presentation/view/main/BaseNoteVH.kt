package cool.hin.memox.presentation.view.main

import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import cool.hin.memox.R
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.FileAttachment
import cool.hin.memox.data.model.SpanRepresentation
import cool.hin.memox.data.model.Type
import cool.hin.memox.databinding.RecyclerBaseNoteBinding
import cool.hin.memox.presentation.applySpans
import cool.hin.memox.presentation.bindLabels
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

            // Overview is always condensed: one title line and one body line.
            Title.maxLines = 1
            Note.maxLines = 1

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
        if (checked) {
            binding.root.strokeWidth = 3.dp
        } else {
            binding.root.strokeWidth = if (color == BaseNote.COLOR_DEFAULT) 1.dp else 0
        }
        binding.root.isChecked = checked
    }

    fun bind(baseNote: BaseNote, imageRoot: File?, checked: Boolean, sortBy: NotesSortBy) {
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
        setFiles(baseNote.files)

        binding.Title.apply {
            isVisible = baseNote.title.isNotEmpty() || baseNote.locked
            updatePadding(
                bottom =
                    if (baseNote.hasNoContents() || shouldOnlyDisplayTitle(baseNote)) 0 else 8.dp
            )
            if (searchKeyword.isNotBlank()) {
                val snippet = extractSearchSnippet(baseNote.title, searchKeyword)
                if (snippet != null) {
                    showSearchSnippet(snippet)
                } else text = baseNote.title
            } else text = baseNote.title
        }

        if (preferences.hideLabels) {
            binding.LabelGroup.visibility = GONE
        } else {
            binding.LabelGroup.bindLabels(
                baseNote.labels,
                preferences.textSize,
                binding.Note.isVisible || binding.Title.isVisible,
            )
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
            // Always show exactly one line of body in the overview, regardless of how long the
            // note is.
            maxLines = 1
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
            // Always hide the legacy top gallery and its placeholders in the overview.
            ImageLayout.visibility = GONE
            Message.visibility = GONE
            ImageViewMore.visibility = GONE
            ImageView.visibility = GONE
            Glide.with(ImageView.context).clear(ImageView)

            val showThumbnail = baseNote.type == Type.NOTE && !baseNote.locked
            val firstImage = baseNote.images.firstOrNull()
            when {
                // Locked notes hide their body/list, so the thumbnail area is free: show a lock
                // icon there (same 64dp box as the preview image) instead of a left title icon.
                baseNote.locked -> {
                    Glide.with(NoteThumbnail.context).clear(NoteThumbnail)
                    NoteThumbnail.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    NoteThumbnail.setPadding(8.dp, 8.dp, 8.dp, 8.dp)
                    NoteThumbnail.setImageResource(R.drawable.lock_big)
                    NoteThumbnail.visibility = VISIBLE
                }
                showThumbnail && firstImage != null && mediaRoot != null -> {
                    val file = File(mediaRoot, firstImage.localName)
                    NoteThumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    NoteThumbnail.setPadding(0, 0, 0, 0)
                    Glide.with(NoteThumbnail.context).load(file).centerCrop().into(NoteThumbnail)
                    NoteThumbnail.visibility = VISIBLE
                }
                else -> {
                    Glide.with(NoteThumbnail.context).clear(NoteThumbnail)
                    NoteThumbnail.visibility = GONE
                }
            }
        }
    }

    private fun setFiles(files: List<FileAttachment>) {
        binding.apply {
            // Always hide files in overview to avoid OBJ icon display issue
            FileViewLayout.visibility = GONE
        }
    }

    private fun shouldOnlyDisplayTitle(baseNote: BaseNote) = false

    private fun BaseNote.isEmpty() = title.isBlank() && hasNoContents() && images.isEmpty()

    private fun BaseNote.hasNoContents() = body.isEmpty() && items.isEmpty()

    private fun BaseNote.getEmptyMessage() = R.string.empty_note
}
