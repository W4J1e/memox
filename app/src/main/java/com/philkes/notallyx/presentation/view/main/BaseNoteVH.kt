package com.philkes.notallyx.presentation.view.main

import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.philkes.notallyx.R
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.FileAttachment
import com.philkes.notallyx.data.model.ListItem
import com.philkes.notallyx.data.model.SpanRepresentation
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.databinding.RecyclerBaseNoteBinding
import com.philkes.notallyx.presentation.applySpans
import com.philkes.notallyx.presentation.bindLabels
import com.philkes.notallyx.presentation.displayFormattedTimestamp
import com.philkes.notallyx.presentation.dp
import com.philkes.notallyx.presentation.extractColor
import com.philkes.notallyx.presentation.setControlsContrastColorForAllViews
import com.philkes.notallyx.presentation.setTextSizeSp
import com.philkes.notallyx.presentation.setupReminderChip
import com.philkes.notallyx.presentation.view.misc.ItemListener
import com.philkes.notallyx.presentation.view.misc.highlightableview.HighlightableTextView
import com.philkes.notallyx.presentation.view.misc.highlightableview.SEARCH_SNIPPET_ITEM_LINES
import com.philkes.notallyx.presentation.view.note.listitem.init
import com.philkes.notallyx.presentation.viewmodel.preference.DateFormat
import com.philkes.notallyx.presentation.viewmodel.preference.NotesSortBy
import com.philkes.notallyx.presentation.viewmodel.preference.TimeFormat
import com.philkes.notallyx.presentation.viewmodel.preference.displayBodySize
import com.philkes.notallyx.presentation.viewmodel.preference.displaySmallerSize
import com.philkes.notallyx.presentation.viewmodel.preference.displayTitleSize
import com.philkes.notallyx.presentation.withoutImagePlaceholders
import java.io.File

data class BaseNoteVHPreferences(
    val textSize: Float,
    val maxItems: Int,
    val maxLines: Int,
    val maxTitleLines: Int,
    val hideLabels: Boolean,
    val hideImages: Boolean,
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

            Title.maxLines = preferences.maxTitleLines
            Note.maxLines = preferences.maxLines

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

        when (baseNote.type) {
            Type.NOTE -> bindNote(baseNote, searchKeyword)
            Type.LIST -> bindList(baseNote, searchKeyword)
        }
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

        setImages(baseNote.images, imageRoot)
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

            setCompoundDrawablesWithIntrinsicBounds(
                if (baseNote.locked) R.drawable.lock_big
                else if (baseNote.type == Type.LIST && preferences.maxItems < 1)
                    R.drawable.checkbox_small
                else 0,
                0,
                0,
                0,
            )
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
            if (preferences.maxLines < 1) {
                isVisible = isTitleEmpty
                maxLines = if (isTitleEmpty) 1 else preferences.maxLines
            } else {
                isVisible = body.isNotEmpty()
            }
        }
    }

    /** Shows a snippet of ListItems around the ListItem that contains keyword */
    private fun LinearLayout.bindListSearch(
        initializedItems: List<ListItem>,
        keyword: String,
        isTitleEmpty: Boolean,
    ) {
        binding.LinearLayout.visibility = VISIBLE
        val keywordItemIdx =
            initializedItems.indexOfFirst { it.body.contains(keyword, ignoreCase = true) }
        if (keywordItemIdx == -1) {
            return bindList(initializedItems, isTitleEmpty, preferences.textSize.displayBodySize)
        }
        val listItemViews = children.filterIsInstance(HighlightableTextView::class.java).toList()
        listItemViews.forEach { it.visibility = GONE }
        val startItemIdx = (keywordItemIdx - SEARCH_SNIPPET_ITEM_LINES).coerceAtLeast(0)
        val endItemIdx =
            (keywordItemIdx + SEARCH_SNIPPET_ITEM_LINES).coerceAtMost(initializedItems.lastIndex)
        (startItemIdx..endItemIdx).forEachIndexed { viewIdx, itemIdx ->
            listItemViews[viewIdx].apply {
                val item = initializedItems[itemIdx]
                text = item.body
                if (itemIdx == keywordItemIdx) {
                    highlight(keyword)
                }
                handleChecked(this, item.checked)
                visibility = VISIBLE
                updateLayoutParams<LinearLayout.LayoutParams> {
                    marginStart = if (item.isChild) 20.dp else 0
                }
            }
        }
        bindItemsRemaining(initializedItems.size, endItemIdx - startItemIdx + 1)
    }

    private fun bindList(baseNote: BaseNote, keyword: String) {
        binding.Note.visibility = GONE
        if (baseNote.locked) {
            // For locked notes, only show title - hide list items
            binding.LinearLayout.visibility = GONE
            return
        }
        val initializedItems = baseNote.items.init()
        if (baseNote.items.isEmpty()) {
            binding.LinearLayout.visibility = GONE
            return
        }
        if (keyword.isBlank()) {
            bindList(
                initializedItems,
                baseNote.title.isEmpty(),
                preferences.textSize.displayBodySize,
            )
            return
        }
        binding.LinearLayout.bindListSearch(initializedItems, keyword, baseNote.title.isEmpty())
    }

    private fun bindItemsRemaining(totalItems: Int, displayedItems: Int) {
        if (displayedItems > 0 && totalItems > displayedItems) {
            binding.ItemsRemaining.apply {
                visibility = VISIBLE
                text = (totalItems - displayedItems).toString()
            }
        } else binding.ItemsRemaining.visibility = GONE
    }

    private fun bindList(initializedItems: List<ListItem>, isTitleEmpty: Boolean, textSize: Float) {
        binding.apply {
            bindItemsRemaining(initializedItems.size, preferences.maxItems)
            if (initializedItems.isEmpty()) {
                LinearLayout.visibility = GONE
            } else {
                LinearLayout.visibility = VISIBLE
                val forceShowFirstItem = preferences.maxItems < 1 && isTitleEmpty
                val filteredList =
                    initializedItems.take(if (forceShowFirstItem) 1 else preferences.maxItems)
                LinearLayout.children
                    .filterIsInstance(HighlightableTextView::class.java)
                    .forEachIndexed { index, view ->
                        if (index < filteredList.size) {
                            val item = filteredList[index]
                            view.apply {
                                text = item.body
                                handleChecked(this, item.checked)
                                visibility = VISIBLE
                                updateLayoutParams<LinearLayout.LayoutParams> {
                                    marginStart = if (item.isChild) 20.dp else 0
                                }
                                if (index == filteredList.lastIndex) {
                                    updatePadding(bottom = 0)
                                }
                                setTextSizeSp(textSize)
                            }
                        } else view.visibility = GONE
                    }
            }
        }
    }

    private fun setColor(color: String) {
        binding.root.apply {
            val colorInt = context.extractColor(color)
            setCardBackgroundColor(colorInt)
            setControlsContrastColorForAllViews(colorInt)
        }
    }

    private fun setImages(images: List<FileAttachment>, mediaRoot: File?) {
        binding.apply {
            // Always hide images in overview to avoid OBJ icon display issue
            ImageLayout.visibility = GONE
            Message.visibility = GONE
            ImageViewMore.visibility = GONE
            ImageView.visibility = GONE
            Glide.with(ImageView.context).clear(ImageView)
        }
    }

    private fun setFiles(files: List<FileAttachment>) {
        binding.apply {
            // Always hide files in overview to avoid OBJ icon display issue
            FileViewLayout.visibility = GONE
        }
    }

    private fun shouldOnlyDisplayTitle(baseNote: BaseNote) =
        when (baseNote.type) {
            Type.NOTE -> preferences.maxLines < 1
            Type.LIST -> preferences.maxItems < 1
        }

    private fun BaseNote.isEmpty() = title.isBlank() && hasNoContents() && images.isEmpty()

    private fun BaseNote.hasNoContents() = body.isEmpty() && items.isEmpty()

    private fun BaseNote.getEmptyMessage() =
        when (type) {
            Type.NOTE -> R.string.empty_note
            Type.LIST -> R.string.empty_list
        }

    private fun handleChecked(textView: TextView, checked: Boolean) {
        if (checked) {
            textView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.checkbox_16,
                0,
                0,
                0,
            )
        } else
            textView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.checkbox_outline_16,
                0,
                0,
                0,
            )
    }
}
