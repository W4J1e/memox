package cool.hin.memox.presentation.activity.main.fragment

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LiveData
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SortedListAdapterCallback
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.snackbar.Snackbar
import cool.hin.memox.R
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.Item
import cool.hin.memox.databinding.FragmentNotesBinding
import cool.hin.memox.presentation.activity.main.MainActivity
import cool.hin.memox.presentation.activity.main.fragment.SearchFragment.Companion.EXTRA_INITIAL_FOLDER
import cool.hin.memox.presentation.activity.main.fragment.SearchFragment.Companion.EXTRA_INITIAL_LABEL
import cool.hin.memox.presentation.activity.note.EditActivity
import cool.hin.memox.presentation.activity.note.EditActivity.Companion.EXTRA_FOLDER_FROM
import cool.hin.memox.presentation.activity.note.EditActivity.Companion.EXTRA_FOLDER_TO
import cool.hin.memox.presentation.activity.note.EditActivity.Companion.EXTRA_NOTE_ID
import cool.hin.memox.presentation.activity.note.EditActivity.Companion.EXTRA_SELECTED_BASE_NOTE
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
import cool.hin.memox.presentation.dp
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
        setupPullToSearch()

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
                    // 编辑器可能因生命周期竞态（finish 与 lifecycleScope 取消）未能即时刷新列表，
                    // 这里强制整卡重绑，确保锁定/置顶等不影响排序位置的状态变化立即反映到卡片
                    // （否则锁定笔记返回主页后仍显示旧的正文/图片预览，需重开 app 才恢复）。
                    notesAdapter?.notifyDataSetChanged()
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
                    goToActivity(EditNoteActivity::class.java, item)
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
        // Search is now driven entirely by the top-bar search pill in MainActivity. The
        // in-fragment EnterSearchKeyword box is kept only for result highlighting and stays
        // hidden, so we just keep the adapter's highlight keyword in sync with the destination.
        val navController = findNavController()
        navController.addOnDestinationChangedListener { controller, destination, arguments ->
            binding?.EnterSearchKeywordLayout?.visibility = View.GONE
            notesAdapter?.setSearchKeyword(if (destination.id == R.id.Search) model.keyword else "")
        }
    }

    /**
     * 首页下拉呼出搜索框：列表处于顶部时，向下拖动超过阈值即呼出（无需悬停）。
     * 用 RecyclerView.addOnItemTouchListener 在 RV 处理触摸之前截获下拉手势，比 setOnTouchListener 更可靠；
     * 呼出后若用户向上浏览列表、清空搜索词或按返回键则收起（见 MainActivity）。
     */
    private var isListAtTop = true
    private var searchBarShownAt = 0L
    private val pullToSearchThresholdPx by lazy(LazyThreadSafetyMode.NONE) { 40.dp }

    private fun setupPullToSearch() {
        val recyclerView = binding?.MainListView ?: return
        var startY = -1f
        var pullingFromTop = false
        var triggered = false

        // 用 item touch listener 在 RV 处理触摸之前截获下拉手势，比 setOnTouchListener 更可靠。
        recyclerView.addOnItemTouchListener(
            object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startY = e.y
                            triggered = false
                            pullingFromTop = isRecyclerViewAtTop()
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!triggered && pullingFromTop && startY >= 0) {
                                val deltaY = e.y - startY
                                if (deltaY > pullToSearchThresholdPx) {
                                    (activity as? MainActivity)?.showSearchBar(clearText = true)
                                    searchBarShownAt = System.currentTimeMillis()
                                    triggered = true
                                    startY = -1f // 本次下拉手势已触发，避免同一手势重复触发
                                }
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            startY = -1f
                        }
                    }
                    return false
                }
            },
        )

        recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val lm = rv.layoutManager as? LinearLayoutManager
                    isListAtTop =
                        if (lm != null) {
                            lm.findFirstVisibleItemPosition() == 0 &&
                                (lm.findViewByPosition(0)?.top ?: 0) >= 0
                        } else {
                            rv.computeVerticalScrollOffset() == 0
                        }
                    // 下拉呼出搜索框后若未输入且用户向上浏览列表，则收起搜索框。
                    // 加 400ms 守卫，避免呼出时键盘弹出导致布局重排触发的一次性误收起。
                    val main = activity as? MainActivity
                    if (main != null &&
                        main.isSearchBarVisible() &&
                        findNavController().currentDestination?.id != R.id.Search &&
                        binding?.EnterSearchKeyword?.text.toString().isEmpty() &&
                        dy < 0 &&
                        System.currentTimeMillis() - searchBarShownAt > 400
                    ) {
                        main.hideSearchBar()
                    }
                }
            },
        )
    }

    private fun isRecyclerViewAtTop(): Boolean {
        val rv = binding?.MainListView ?: return false
        val lm = rv.layoutManager as? LinearLayoutManager
            ?: return rv.computeVerticalScrollOffset() == 0
        return lm.findFirstVisibleItemPosition() == 0 &&
            (lm.findViewByPosition(0)?.top ?: 0) >= 0
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
                    dateFormat.value,
                    timeFormat.value,
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

        model.preferences.notesView.observe(viewLifecycleOwner) {
            // Rebuild the layout manager when the view (grid/list) is toggled from the top bar.
            setupRecyclerView()
            // Swapping LayoutManager moves the existing cards into the scrap cache and re-attaches
            // them WITHOUT calling onBindViewHolder again. A card bound in grid mode would then keep
            // its grid header state (WRAP_CONTENT / adjustViewBounds) when shown in list mode, and
            // vice-versa — that was the "list header still cropped like grid" bug after switching.
            // Force a full rebind so setImages re-applies the correct sizing for the active mode.
            notesAdapter?.notifyDataSetChanged()
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
        binding?.MainListView?.itemAnimator = DefaultItemAnimator().apply {
            addDuration = 200
            removeDuration = 200
            moveDuration = 250
            changeDuration = 200
        }
    }

    @Suppress("DEPRECATION")
    private fun goToActivity(activity: Class<*>, baseNote: BaseNote) {
        val intent = Intent(requireContext(), activity)
        intent.putExtra(EXTRA_SELECTED_BASE_NOTE, baseNote.id)
        // If launched from Search fragment with a non-empty keyword, pass it to the editor to
        // auto-highlight
        val isInSearch = findNavController().currentDestination?.id == R.id.Search
        if (isInSearch && model.keyword.isNotBlank()) {
            intent.putExtra(EditActivity.EXTRA_INITIAL_SEARCH_QUERY, model.keyword)
        }
        // Keep-style open: editor zoom-fades in (R.anim.note_zoom_in); the underlying list stays
        // put (0 = no exit animation) so only the editor animates — the top/bottom bars scale
        // uniformly with the content, avoiding the stiff per-bar scaling of a shared-element
        // morph. Close animation is applied in EditActivity.finish().
        openNoteActivityResultLauncher.launch(intent)
        requireActivity().overridePendingTransition(R.anim.note_zoom_in, 0)
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
