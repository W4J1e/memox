package cool.hin.memox.presentation.activity.main.fragment

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LiveData
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SortedListAdapterCallback
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.snackbar.Snackbar
import cool.hin.memox.R
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.Item
import cool.hin.memox.data.model.Type
import cool.hin.memox.databinding.FragmentNotesBinding
import cool.hin.memox.presentation.activity.main.MainActivity
import cool.hin.memox.presentation.activity.main.fragment.SearchFragment.Companion.EXTRA_INITIAL_FOLDER
import cool.hin.memox.presentation.activity.main.fragment.SearchFragment.Companion.EXTRA_INITIAL_LABEL
import cool.hin.memox.presentation.activity.note.EditActivity
import cool.hin.memox.presentation.activity.note.EditActivity.Companion.EXTRA_FOLDER_FROM
import cool.hin.memox.presentation.activity.note.EditActivity.Companion.EXTRA_FOLDER_TO
import cool.hin.memox.presentation.activity.note.EditActivity.Companion.EXTRA_NOTE_ID
import cool.hin.memox.presentation.activity.note.EditActivity.Companion.EXTRA_SELECTED_BASE_NOTE
import cool.hin.memox.presentation.activity.note.EditListActivity
import cool.hin.memox.presentation.activity.note.EditNoteActivity
import cool.hin.memox.presentation.activity.note.reminders.RemindersActivity
import cool.hin.memox.presentation.getQuantityString
import cool.hin.memox.presentation.hideKeyboard
import cool.hin.memox.presentation.movedToResId
import cool.hin.memox.presentation.showKeyboard
import cool.hin.memox.presentation.view.main.BaseNoteAdapter
import cool.hin.memox.presentation.view.main.BaseNoteVHPreferences
import cool.hin.memox.presentation.view.main.createCallback
import cool.hin.memox.presentation.view.misc.ItemListener
import cool.hin.memox.presentation.viewmodel.BaseNoteModel
import cool.hin.memox.presentation.viewmodel.preference.NotesView

abstract class MemoXFragment : Fragment(), ItemListener {

    protected var notesAdapter: BaseNoteAdapter? = null
    private lateinit var openNoteActivityResultLauncher: ActivityResultLauncher<Intent>
    private var lastSelectedNotePosition = -1

    internal var binding: FragmentNotesBinding? = null

    internal val model: BaseNoteModel by activityViewModels()

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
        notesAdapter = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val layoutManager = binding?.MainListView?.layoutManager as? LinearLayoutManager
        if (layoutManager != null) {
            val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
            if (firstVisiblePosition != RecyclerView.NO_POSITION) {
                val firstVisibleView = layoutManager.findViewByPosition(firstVisiblePosition)
                val offset = firstVisibleView?.top ?: 0
                outState.putInt(EXTRA_SCROLL_POS, firstVisiblePosition)
                outState.putInt(EXTRA_SCROLL_OFFSET, offset)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding?.ImageView?.setImageResource(getBackground())

        setupAdapter()
        setupRecyclerView()
        setupObserver()
        setupSearch()

        setupActivityResultLaunchers()

        savedInstanceState?.let { bundle ->
            val scrollPosition = bundle.getInt(EXTRA_SCROLL_POS, -1)
            val scrollOffset = bundle.getInt(EXTRA_SCROLL_OFFSET, 0)
            if (scrollPosition > -1) {
                binding?.MainListView?.post {
                    val layoutManager = binding?.MainListView?.layoutManager as? LinearLayoutManager
                    layoutManager?.scrollToPositionWithOffset(scrollPosition, scrollOffset)
                }
            }
        }
    }

    private fun setupActivityResultLaunchers() {
        openNoteActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    // If a note has been moved inside of EditActivity
                    // present snackbar to undo it
                    val data = result.data
                    val id = data?.getLongExtra(EXTRA_NOTE_ID, -1)
                    if (id != null) {
                        val folderFrom = Folder.valueOf(data.getStringExtra(EXTRA_FOLDER_FROM)!!)
                        val folderTo = Folder.valueOf(data.getStringExtra(EXTRA_FOLDER_TO)!!)
                        Snackbar.make(
                                binding!!.root,
                                requireContext().getQuantityString(folderTo.movedToResId(), 1),
                                Snackbar.LENGTH_SHORT,
                            )
                            .apply {
                                setAction(R.string.undo) {
                                    model.moveBaseNotes(longArrayOf(id), folderFrom)
                                }
                            }
                            .show()
                    }
                }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        setHasOptionsMenu(true)
        binding = FragmentNotesBinding.inflate(inflater)
        return binding?.root
    }

    // See [RecyclerView.ViewHolder.getAdapterPosition]
    override fun onClick(position: Int) {
        if (position != -1) {
            notesAdapter?.getItem(position)?.let { item ->
                if (item is BaseNote) {
                    if (model.actionMode.isEnabled()) {
                        handleNoteSelection(item.id, position, item)
                    } else {
                        when (item.type) {
                            Type.NOTE -> goToActivity(EditNoteActivity::class.java, item)
                            Type.LIST -> goToActivity(EditListActivity::class.java, item)
                        }
                    }
                }
            }
        }
    }

    override fun onReminderClick(position: Int) {
        if (model.actionMode.isEnabled()) {
            onClick(position)
            return
        }
        if (position != -1) {
            notesAdapter?.getItem(position)?.let { item ->
                if (item is BaseNote) {
                    val intent =
                        Intent(requireContext(), RemindersActivity::class.java).apply {
                            putExtra(RemindersActivity.NOTE_ID, item.id)
                        }
                    startActivity(intent)
                }
            }
        }
    }

    override fun onLongClick(position: Int) {
        if (position != -1) {
            if (model.actionMode.selectedNotes.isNotEmpty()) {
                if (lastSelectedNotePosition > position) {
                        position..lastSelectedNotePosition
                    } else {
                        lastSelectedNotePosition..position
                    }
                    .forEach { pos ->
                        notesAdapter!!.getItem(pos)?.let { item ->
                            if (item is BaseNote) {
                                if (!model.actionMode.selectedNotes.contains(item.id)) {
                                    handleNoteSelection(item.id, pos, item)
                                }
                            }
                        }
                    }
            } else {
                notesAdapter?.getItem(position)?.let { item ->
                    if (item is BaseNote) {
                        handleNoteSelection(item.id, position, item)
                    }
                }
            }
        }
    }

    private fun setupSearch() {
        binding?.EnterSearchKeyword?.apply {
            setText(model.keyword)
            val navController = findNavController()
            navController.addOnDestinationChangedListener { controller, destination, arguments ->
                if (destination.id == R.id.Search) {
                    // Always show search bar in Search fragment
                    binding?.EnterSearchKeywordLayout?.visibility = View.VISIBLE
                    requestFocus()
                    activity?.showKeyboard(this)
                    notesAdapter?.setSearchKeyword(model.keyword)
                } else {
                    // Hide search bar by default on other fragments
                    binding?.EnterSearchKeywordLayout?.visibility = View.GONE
                    setText("")
                    clearFocus()
                    activity?.hideKeyboard(this)
                    notesAdapter?.setSearchKeyword("")
                }
            }
            doAfterTextChanged { text ->
                val isSearchFragment = navController.currentDestination?.id == R.id.Search
                if (isSearchFragment) {
                    val newKeyword = text?.toString().orEmpty()
                    if (model.keyword != newKeyword) {
                        model.keyword = newKeyword
                    }
                }
                if (text?.isNotEmpty() == true && !isSearchFragment) {
                    setText("")
                    model.keyword = text.toString()
                    navController.navigate(
                        R.id.Search,
                        Bundle().apply {
                            putSerializable(EXTRA_INITIAL_FOLDER, model.folder.value)
                            putSerializable(EXTRA_INITIAL_LABEL, model.currentLabel)
                        },
                    )
                }
                this@MemoXFragment.binding?.MainListView?.apply {
                    postOnAnimationDelayed({ scrollToPosition(0) }, 10)
                }
            }
        }
        // Pull-down to reveal search bar
        binding?.MainListView?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val firstVisible = layoutManager.findFirstVisibleItemPosition()
                if (firstVisible == 0) {
                    val navController = findNavController()
                    if (navController.currentDestination?.id != R.id.Search) {
                        val searchLayout = binding?.EnterSearchKeywordLayout
                        if (searchLayout?.visibility != View.VISIBLE && dy < 0) {
                            searchLayout?.visibility = View.VISIBLE
                        }
                    }
                }
            }
        })
    }

    fun toggleSearchBar() {
        binding?.EnterSearchKeywordLayout?.let { searchBar ->
            if (searchBar.visibility == View.VISIBLE) {
                searchBar.visibility = View.GONE
                binding?.EnterSearchKeyword?.setText("")
                binding?.EnterSearchKeyword?.clearFocus()
                activity?.hideKeyboard(binding?.EnterSearchKeyword ?: return)
            } else {
                searchBar.visibility = View.VISIBLE
                binding?.EnterSearchKeyword?.requestFocus()
                activity?.showKeyboard(binding?.EnterSearchKeyword ?: return)
            }
        }
    }

    private fun handleNoteSelection(id: Long, position: Int, baseNote: BaseNote) {
        if (model.actionMode.selectedNotes.contains(id)) {
            model.actionMode.remove(id)
        } else {
            model.actionMode.add(id, baseNote)
            lastSelectedNotePosition = position
        }
        notesAdapter?.notifyItemChanged(position, 0)
    }

    private fun setupAdapter() {
        notesAdapter =
            with(model.preferences) {
                BaseNoteAdapter(
                    model.actionMode.selectedIds,
                    dateFormatOverview.value,
                    timeFormatOverview.value,
                    notesAdapterSortCallback(),
                    BaseNoteVHPreferences(
                        textSizeOverview.value,
                        labelTagsHiddenInOverview.value,
                        notesSorting.value.sortedBy,
                    ),
                    model.imageRoot,
                    this@MemoXFragment,
                )
            }

        notesAdapter?.registerAdapterDataObserver(
            object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    if (itemCount > 0) {
                        binding?.MainListView?.scrollToPosition(positionStart)
                    }
                }
            }
        )
        binding?.MainListView?.apply {
            adapter = notesAdapter
            setHasFixedSize(false)
        }
        model.actionMode.addListener = { notesAdapter?.notifyDataSetChanged() }
        if (activity is MainActivity) {
            (activity as MainActivity).getCurrentFragmentNotes = {
                notesAdapter?.currentList?.filterIsInstance<BaseNote>()
            }
        }
    }

    protected open fun notesAdapterSortCallback():
        (BaseNoteAdapter) -> SortedListAdapterCallback<Item> = { adapter ->
        model.preferences.notesSorting.value.createCallback(adapter)
    }

    private fun setupObserver() {
        getObservable().observe(viewLifecycleOwner) { list ->
            notesAdapter?.submitList(list)
            binding?.ImageView?.isVisible = list.isEmpty()
        }

        model.preferences.notesSorting.observe(viewLifecycleOwner) { notesSort ->
            notesAdapter?.setNotesSort(notesSort)
        }

        model.actionMode.closeListener.observe(viewLifecycleOwner) { event ->
            event.handle { ids ->
                notesAdapter?.currentList?.forEachIndexed { index, item ->
                    if (item is BaseNote && ids.contains(item.id)) {
                        notesAdapter?.notifyItemChanged(index, 0)
                    }
                }
            }
        }
    }

    private fun setupRecyclerView() {
        binding?.MainListView?.layoutManager =
            if (model.preferences.notesView.value == NotesView.GRID) {
                StaggeredGridLayoutManager(2, RecyclerView.VERTICAL)
            } else LinearLayoutManager(requireContext())
    }

    private fun goToActivity(activity: Class<*>, baseNote: BaseNote) {
        val intent = Intent(requireContext(), activity)
        intent.putExtra(EXTRA_SELECTED_BASE_NOTE, baseNote.id)
        // If launched from Search fragment with a non-empty keyword, pass it to the editor to
        // auto-highlight
        val isInSearch = view?.findNavController()?.currentDestination?.id == R.id.Search
        if (isInSearch && model.keyword.isNotBlank()) {
            intent.putExtra(EditActivity.EXTRA_INITIAL_SEARCH_QUERY, model.keyword)
        }
        openNoteActivityResultLauncher.launch(intent)
    }

    abstract fun getBackground(): Int

    abstract fun getObservable(): LiveData<List<Item>>

    open fun prepareNewNoteIntent(intent: Intent): Intent {
        return intent
    }

    companion object {
        private const val EXTRA_SCROLL_POS = "memox.intent.extra.SCROLL_POS"
        private const val EXTRA_SCROLL_OFFSET = "memox.intent.extra.SCROLL_OFFSET"
    }
}
