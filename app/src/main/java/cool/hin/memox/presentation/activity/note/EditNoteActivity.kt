package cool.hin.memox.presentation.activity.note

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.style.URLSpan
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cool.hin.memox.R
import cool.hin.memox.data.model.NoteViewMode
import cool.hin.memox.data.model.Type
import cool.hin.memox.data.model.getNoteIdFromUrl
import cool.hin.memox.data.model.getNoteTypeFromUrl
import cool.hin.memox.data.model.isNoteUrl
import cool.hin.memox.databinding.BottomTextFormattingMenuBinding
import cool.hin.memox.databinding.RecyclerToggleBinding
import cool.hin.memox.presentation.InlineImageSpan
import cool.hin.memox.presentation.addIconButton
import cool.hin.memox.presentation.dp
import cool.hin.memox.presentation.hideKeyboard
import cool.hin.memox.presentation.setControlsContrastColorForAllViews
import cool.hin.memox.presentation.setOnNextAction
import cool.hin.memox.presentation.showKeyboard
import cool.hin.memox.presentation.showToast
import cool.hin.memox.presentation.view.note.TextFormattingAdapter
import cool.hin.memox.presentation.viewmodel.preference.EditAction
import cool.hin.memox.presentation.view.note.CheckboxSpan
import cool.hin.memox.utils.LinkMovementMethod
import cool.hin.memox.utils.copyToClipBoard
import cool.hin.memox.utils.findAllOccurrences
import cool.hin.memox.utils.openNote
import cool.hin.memox.utils.wrapWithChooser

class EditNoteActivity : EditActivity(Type.NOTE) {

    private lateinit var textFormatMenu: View

    private var textFormattingAdapter: TextFormattingAdapter? = null

    private var searchResultIndices: List<Pair<Int, Int>>? = null

    override fun configureUI() {
        binding.EnterTitle.setOnNextAction { binding.EnterBody.requestFocus() }

        // NOTE-type notes render images inline in the body, so the top image gallery is hidden.
        binding.ImageLayout.visibility = GONE

        if (notallyModel.isNewNote) {
            binding.EnterBody.requestFocus()
        }
    }

    override fun toggleCanEdit(mode: NoteViewMode) {
        super.toggleCanEdit(mode)
        textFormatMenu.isVisible = mode == NoteViewMode.EDIT
        when {
            mode == NoteViewMode.EDIT -> showKeyboard(binding.EnterBody)
            binding.EnterBody.isFocused -> hideKeyboard(binding.EnterBody)
        }
        binding.EnterBody.setCanEdit(mode == NoteViewMode.EDIT)
        setupEditor()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.apply {
            putInt(EXTRA_SELECTION_START, binding.EnterBody.selectionStart)
            putInt(EXTRA_SELECTION_END, binding.EnterBody.selectionEnd)
        }
    }

    override fun highlightSearchResults(search: String): Int {
        binding.EnterBody.clearHighlights()
        if (search.isEmpty()) {
            return 0
        }
        searchResultIndices =
            notallyModel.body.toString().findAllOccurrences(search).onEach { (startIdx, endIdx) ->
                binding.EnterBody.highlight(startIdx, endIdx, false)
            }
        return searchResultIndices!!.size
    }

    override fun selectSearchResult(resultPos: Int) {
        if (resultPos < 0) {
            binding.EnterBody.unselectHighlight()
            return
        }
        searchResultIndices?.get(resultPos)?.let { (startIdx, endIdx) ->
            val selectedLineTop = binding.EnterBody.highlight(startIdx, endIdx, true)
            selectedLineTop?.let { binding.ScrollView.scrollTo(0, it) }
        }
    }

    override fun setupListeners() {
        super.setupListeners()
        binding.EnterBody.initHistory(changeHistory) { text ->
            val textChanged = !notallyModel.body.toString().contentEquals(text)
            notallyModel.body = text
            notallyModel.syncImagesFromBody()
            if (textChanged) {
                updateSearchResults(search.query)
                updateJumpButtonsVisibility()
            }
        }
    }

    override fun setStateFromModel(savedInstanceState: Bundle?) {
        super.setStateFromModel(savedInstanceState)
        updateEditText()
        savedInstanceState?.let {
            val selectionStart = it.getInt(EXTRA_SELECTION_START, -1)
            val selectionEnd = it.getInt(EXTRA_SELECTION_END, -1)
            if (selectionStart > -1) {
                binding.EnterBody.postOnAnimation {
                    binding.EnterBody.focusAndSelect(selectionStart, selectionEnd)
                }
            }
        }
    }

    private fun updateEditText() {
        binding.EnterBody.text = notallyModel.body
    }

    private fun setupEditor() {
        setupMovementMethod()
        binding.EnterBody.customSelectionActionModeCallback =
            if (canEdit) {
                FormattingActionModeCallback(this, binding.EnterBody)
            } else null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            binding.EnterBody.customInsertionActionModeCallback =
                if (canEdit) {
                    FormattingActionModeCallback(this, binding.EnterBody)
                } else null
        }
        if (canEdit) {
            binding.EnterBody.setOnSelectionChange { selStart, selEnd ->
                if (selEnd - selStart > 0) {
                    if (!textFormatMenu.isEnabled) {
                        initBottomTextFormattingMenu()
                    }
                    textFormatMenu.isEnabled = true
                    textFormattingAdapter?.updateTextFormattingToggles(selStart, selEnd)
                } else {
                    if (textFormatMenu.isEnabled) {
                        initBottomMenu()
                    }
                    textFormatMenu.isEnabled = false
                }
            }
        } else {
            binding.EnterBody.setOnSelectionChange { _, _ -> }
        }
        binding.ContentLayout.setOnClickListener {
            binding.EnterBody.apply {
                requestFocus()
                if (canEdit) {
                    setSelection(length())
                    showKeyboard(this)
                }
            }
        }
    }

    override fun initBottomMenu() {
        binding.BottomAppBarCenter.visibility = GONE
        binding.BottomAppBarLeft.apply {
            removeAllViews()
            updateLayoutParams<ConstraintLayout.LayoutParams> { endToStart = -1 }
            // Image
            addIconButton(R.string.add_images, R.drawable.add_images, colorInt, marginStart = 0) {
                actionHandler.addImages()
            }
            // Recording
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addIconButton(R.string.record_audio, R.drawable.record_audio, colorInt) {
                    actionHandler.recordAudio()
                }
            }
            // Checkbox (insert interactive checkbox)
            addIconButton(R.string.add_checkbox, R.drawable.checkbox, colorInt) {
                insertCheckboxAtCursor()
            }
        }
        // Right side: text format, view/edit, lock, more
        binding.BottomAppBarRight.apply {
            removeAllViews()
            // Text format
            textFormatMenu =
                addIconButton(R.string.edit, R.drawable.text_format, colorInt, marginStart = 0) {
                        initBottomTextFormattingMenu()
                    }
                    .apply { isEnabled = binding.EnterBody.isActionModeOn }
            addBottomAction(preferences.editNoteActivityBottomAction.value)
            addIconButton(R.string.lock_note, R.drawable.lock_big, colorInt) {
                actionHandler.handleAction(EditAction.LOCK_NOTE)
            }
            addIconButton(
                R.string.tap_for_more_options,
                R.drawable.more_vert,
                colorInt,
                marginStart = 0,
            ) {
                openMoreOptionsBottomSheet()
            }
        }
        setBottomAppBarColor(colorInt)
    }

    private fun insertCheckboxAtCursor() {
        val body = binding.EnterBody
        val text = body.text ?: return
        val selStart = body.selectionStart.coerceAtLeast(0)
        val lineStart = text.substring(0, selStart).lastIndexOf('\n') + 1
        CheckboxSpan.insertCheckbox(text, lineStart)
        body.setSelection(lineStart + 2)
    }

    private fun initBottomTextFormattingMenu() {
        binding.BottomAppBarCenter.visibility = GONE
        val extractColor = colorInt
        binding.BottomAppBarRight.apply {
            removeAllViews()
            addView(
                RecyclerToggleBinding.inflate(layoutInflater, this, false).root.apply {
                    setIconResource(R.drawable.close)
                    contentDescription = context.getString(R.string.cancel)
                    setOnClickListener { initBottomMenu() }

                    updateLayoutParams<LinearLayout.LayoutParams> {
                        marginEnd = 0
                        marginStart = 10.dp
                    }
                    setControlsContrastColorForAllViews(extractColor)
                    setBackgroundColor(0)
                }
            )
        }
        binding.BottomAppBarLeft.apply {
            removeAllViews()
            updateLayoutParams<ConstraintLayout.LayoutParams> {
                endToStart = R.id.BottomAppBarRight
            }
            requestLayout()
            val layout = BottomTextFormattingMenuBinding.inflate(layoutInflater, this, false)
            layout.MainListView.apply {
                textFormattingAdapter =
                    TextFormattingAdapter(this@EditNoteActivity, binding.EnterBody, colorInt)
                adapter = textFormattingAdapter
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            }
            addView(layout.root)
        }
    }

    private fun setupMovementMethod() {
        val movementMethod =
            LinkMovementMethod(
                onClick = { span ->
                    val items =
                        if (span.url.isNoteUrl()) {
                            if (canEdit) {
                                arrayOf(
                                    getString(R.string.open_note),
                                    getString(R.string.remove_link),
                                    getString(R.string.change_note),
                                    getString(R.string.edit),
                                )
                            } else arrayOf(getString(R.string.open_note))
                        } else {
                            if (canEdit) {
                                arrayOf(
                                    getString(R.string.open_link),
                                    getString(R.string.copy),
                                    getString(R.string.remove_link),
                                    getString(R.string.edit),
                                )
                            } else arrayOf(getString(R.string.open_link), getString(R.string.copy))
                        }
                    MaterialAlertDialogBuilder(this)
                        .setTitle(
                            if (span.url.isNoteUrl())
                                "${getString(R.string.note)}: ${
                            binding.EnterBody.getSpanText(span)
                        }"
                            else span.url
                        )
                        .setItems(items) { _, which ->
                            when (which) {
                                0 -> openLink(span)
                                1 ->
                                    if (span.url.isNoteUrl()) {
                                        removeLink(span)
                                    } else copyLink(span)
                                2 ->
                                    if (span.url.isNoteUrl()) {
                                        actionHandler.updateNoteLink(span)
                                    } else removeLink(span)
                                3 -> editLink(span)
                            }
                        }
                        .show()
                },
                onImageClick = { span -> openInlineImage(span) },
            )
        binding.EnterBody.movementMethod = movementMethod
    }

    private fun openInlineImage(span: InlineImageSpan) {
        val position =
            notallyModel.images.value
                .indexOfFirst { it.localName == span.attachment.localName }
                .coerceAtLeast(0)
        val intent =
            Intent(this, ViewImageActivity::class.java).apply {
                putExtra(ViewImageActivity.EXTRA_POSITION, position)
                putExtra(EXTRA_SELECTED_BASE_NOTE, notallyModel.id)
            }
        actionHandler.viewImagesActivityResultLauncher.launch(intent)
    }

    private fun openLink(span: URLSpan) {
        span.url?.let {
            if (it.isNoteUrl()) {
                span.navigateToNote()
            } else {
                openLink(span.url)
            }
        }
    }

    private fun editLink(span: URLSpan) {
        binding.EnterBody.showEditDialog(span)
    }

    private fun copyLink(span: URLSpan) {
        copyToClipBoard(span.url)
        showToast(R.string.copied_link)
    }

    private fun removeLink(span: URLSpan) {
        binding.EnterBody.removeSpanWithHistory(
            span,
            span.url.isNoteUrl() || span.url == binding.EnterBody.getSpanText(span),
        )
    }

    private fun openLink(url: String) {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri).wrapWithChooser(this)
        try {
            startActivity(intent)
        } catch (exception: Exception) {
            showToast(R.string.cant_open_link)
        }
    }

    private fun URLSpan.navigateToNote() {
        openNote(this.url.getNoteIdFromUrl(), this.url.getNoteTypeFromUrl())
    }

    companion object {
        private const val EXTRA_SELECTION_START = "memox.intent.extra.EXTRA_SELECTION_START"
        private const val EXTRA_SELECTION_END = "memox.intent.extra.EXTRA_SELECTION_END"
    }
}
