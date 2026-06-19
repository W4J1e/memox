package cool.hin.memox.presentation.activity.note

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import cool.hin.memox.R
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Header
import cool.hin.memox.databinding.ActivityPickNoteBinding
import cool.hin.memox.presentation.activity.LockedActivity
import cool.hin.memox.presentation.view.main.BaseNoteAdapter
import cool.hin.memox.presentation.view.main.BaseNoteVHPreferences
import cool.hin.memox.presentation.view.main.createCallback
import cool.hin.memox.presentation.view.misc.ItemListener
import cool.hin.memox.presentation.viewmodel.BaseNoteModel
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import cool.hin.memox.presentation.viewmodel.preference.NotesView
import cool.hin.memox.utils.getCurrentImagesDirectory
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class PickNoteActivity : LockedActivity<ActivityPickNoteBinding>(), ItemListener {

    protected lateinit var adapter: BaseNoteAdapter

    private val excludedNoteId by lazy { intent.getLongExtra(EXTRA_EXCLUDE_NOTE_ID, -1L) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPickNoteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureEdgeToEdgeInsets()

        val result = Intent()
        setResult(RESULT_CANCELED, result)

        val preferences = MemoXPreferences.getInstance(application)

        adapter =
            with(preferences) {
                BaseNoteAdapter(
                    Collections.emptySet(),
                    dateFormatOverview.value,
                    timeFormatOverview.value,
                    { adapter -> notesSorting.value.createCallback(adapter) },
                    BaseNoteVHPreferences(
                        textSizeOverview.value,
                        labelTagsHiddenInOverview.value,
                        notesSorting.value.sortedBy,
                    ),
                    application.getCurrentImagesDirectory(),
                    this@PickNoteActivity,
                )
            }

        binding.MainListView.apply {
            adapter = this@PickNoteActivity.adapter
            setHasFixedSize(true)
            layoutManager =
                if (preferences.notesView.value == NotesView.GRID) {
                    StaggeredGridLayoutManager(2, RecyclerView.VERTICAL)
                } else LinearLayoutManager(this@PickNoteActivity)
        }

        val database = MemoXDatabase.getDatabase(application)

        val pinned = Header(getString(R.string.pinned))
        val others = Header(getString(R.string.others))

        database.observe(this) {
            lifecycleScope.launch {
                val notes =
                    withContext(Dispatchers.IO) {
                        val raw =
                            it.getBaseNoteDao().getAllNotes().filter { it.id != excludedNoteId }
                        BaseNoteModel.transform(raw, pinned, others)
                    }
                adapter.submitList(notes)
                binding.EmptyView.visibility =
                    if (notes.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    private fun configureEdgeToEdgeInsets() {
        // 1. Enable edge-to-edge display for the activity window.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 3. Apply window insets to specific views to prevent content from being obscured.
        // Set an OnApplyWindowInsetsListener on the root layout.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())

            // Adjust the top margin of the Toolbar to account for the status bar.
            binding.Toolbar.apply {
                (layoutParams as ViewGroup.MarginLayoutParams).topMargin = systemBarsInsets.top
                requestLayout()
            }

            // Apply padding to the RecyclerView and EmptyView to account for the navigation bar and
            // keyboard.
            // Preserve existing horizontal padding.
            binding.MainListView.apply {
                setPadding(
                    paddingLeft,
                    paddingTop,
                    paddingRight,
                    systemBarsInsets.bottom + imeInsets.bottom,
                )
            }
            binding.EmptyView.apply {
                setPadding(
                    paddingLeft,
                    paddingTop,
                    paddingRight,
                    systemBarsInsets.bottom + imeInsets.bottom,
                )
            }
            insets
        }
    }

    override fun onClick(position: Int) {
        if (position != -1) {
            val note = (adapter.getItem(position) as BaseNote)
            val success = Intent()
            success.putExtra(EXTRA_PICKED_NOTE_ID, note.id)
            success.putExtra(EXTRA_PICKED_NOTE_TITLE, note.title)
            success.putExtra(EXTRA_PICKED_NOTE_TYPE, note.type.name)
            setResult(RESULT_OK, success)
            finish()
        }
    }

    override fun onLongClick(position: Int) {}

    companion object {
        const val EXTRA_EXCLUDE_NOTE_ID = "memox.intent.extra.EXCLUDE_NOTE_ID"

        const val EXTRA_PICKED_NOTE_ID = "memox.intent.extra.PICKED_NOTE_ID"
        const val EXTRA_PICKED_NOTE_TITLE = "memox.intent.extra.PICKED_NOTE_TITLE"
        const val EXTRA_PICKED_NOTE_TYPE = "memox.intent.extra.PICKED_NOTE_TYPE"
    }
}
