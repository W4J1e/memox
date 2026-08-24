package cool.hin.memox.presentation.view.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SortedList
import androidx.recyclerview.widget.SortedListAdapterCallback
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Header
import cool.hin.memox.data.model.Item
import cool.hin.memox.databinding.RecyclerBaseNoteBinding
import cool.hin.memox.databinding.RecyclerHeaderBinding
import cool.hin.memox.presentation.view.main.sorting.BaseNoteCreationDateSort
import cool.hin.memox.presentation.view.main.sorting.BaseNoteModifiedDateSort
import cool.hin.memox.presentation.view.main.sorting.BaseNoteTitleSort
import cool.hin.memox.presentation.view.misc.ItemListener
import cool.hin.memox.presentation.viewmodel.preference.DateFormat
import cool.hin.memox.presentation.viewmodel.preference.NotesSort
import cool.hin.memox.presentation.viewmodel.preference.NotesSortBy
import cool.hin.memox.presentation.viewmodel.preference.TimeFormat
import java.io.File

class BaseNoteAdapter(
    private val selectedIds: Set<Long>,
    private val dateFormat: DateFormat,
    private val timeFormat: TimeFormat,
    private var notesSortCallback: (adapter: BaseNoteAdapter) -> SortedListAdapterCallback<Item>,
    private val preferences: BaseNoteVHPreferences,
    private val imageRoot: File?,
    private val listener: ItemListener,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var searchKeyword: String = ""

    private var attachedRecyclerView: RecyclerView? = null

    private var list = SortedList(Item::class.java, notesSortCallback(this))

    override fun getItemViewType(position: Int): Int {
        return when (list[position]) {
            is Header -> 0
            is BaseNote -> 1
        }
    }

    override fun getItemCount(): Int {
        return list.size()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        attachedRecyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position < 0 || position >= list.size()) return
        when (val item = list[position]) {
            is Header -> (holder as HeaderVH).bind(item)
            is BaseNote -> {
                val isGrid = attachedRecyclerView?.layoutManager is StaggeredGridLayoutManager
                (holder as BaseNoteVH).apply {
                    setSearchKeyword(searchKeyword)
                    bind(item, imageRoot, selectedIds.contains(item.id), preferences.sortedBy, isGrid)
                }
            }
        }
    }

    fun setSearchKeyword(keyword: String) {
        if (searchKeyword != keyword) {
            val oldKeyword = searchKeyword
            searchKeyword = keyword
            val positions = mutableListOf<Int>()
            for (i in 0 until list.size()) {
                val item = list[i]
                if (item is BaseNote) {
                    if (matchesKeyword(item, oldKeyword) || matchesKeyword(item, keyword)) {
                        positions.add(i)
                    }
                }
            }
            // setSearchKeyword may be invoked while the RecyclerView is computing a layout
            // (e.g. from onBindViewHolder). Calling notifyItemChanged synchronously there throws
            // "Cannot call this method while RecyclerView is computing a layout". Defer to the
            // next frame via the attached RecyclerView's message queue.
            val rv = attachedRecyclerView
            if (rv != null) {
                rv.post { positions.forEach { notifyItemChanged(it) } }
            } else {
                positions.forEach { notifyItemChanged(it) }
            }
        }
    }

    private fun matchesKeyword(baseNote: BaseNote, keyword: String): Boolean {
        if (keyword.isBlank()) {
            return false
        }
        if (baseNote.title.contains(keyword, true)) {
            return true
        }
        if (baseNote.body.contains(keyword, true)) {
            return true
        }
        for (label in baseNote.labels) {
            if (label.contains(keyword, true)) {
                return true
            }
        }
        for (item in baseNote.items) {
            if (item.body.contains(keyword, true)) {
                return true
            }
        }
        return false
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        // payload 0 仅表示"选中勾状态变化"（来自 action mode 的 notifyItemChanged(position, 0)），
        // 这种情况只需刷新勾选样式，无需重绑整张卡片。其它情况——含 SortedList 的内容变化（如锁定 /
        // 置顶状态改变，其 payload 为 null 或非 0）——一律走完整 bind，确保卡片正文与图片预览随
        // locked 等状态正确刷新。否则锁定时卡片仍会显示旧的正文/图片预览，只能重开 app 才会恢复。
        if (payloads.isEmpty() || payloads.any { it != 0 }) {
            onBindViewHolder(holder, position)
        } else {
            handleCheck(holder, position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> {
                val binding = RecyclerHeaderBinding.inflate(inflater, parent, false)
                HeaderVH(binding)
            }
            else -> {
                val binding = RecyclerBaseNoteBinding.inflate(inflater, parent, false)
                BaseNoteVH(binding, dateFormat, timeFormat, preferences, listener)
            }
        }
    }

    fun setNotesSort(notesSort: NotesSort) {
        setNotesSortCallback { adapter -> notesSort.createCallback(adapter) }
    }

    fun setNotesSortCallback(
        notesSortCallback: (adapter: BaseNoteAdapter) -> SortedListAdapterCallback<Item>
    ) {
        this.notesSortCallback = notesSortCallback
        replaceSortCallback(this.notesSortCallback(this))
    }

    fun getItem(position: Int): Item? {
        return list[position]
    }

    val currentList: List<Item>
        get() = list.toList()

    fun submitList(items: List<Item>) {
        list.replaceAll(items)
    }

    private fun replaceSortCallback(sortCallback: SortedListAdapterCallback<Item>) {
        val mutableList = mutableListOf<Item>()
        for (i in 0 until list.size()) {
            mutableList.add(list[i])
        }
        list.clear()
        list = SortedList(Item::class.java, sortCallback)
        list.addAll(mutableList)
    }

    private fun handleCheck(holder: RecyclerView.ViewHolder, position: Int) {
        val baseNote = list[position] as BaseNote
        (holder as BaseNoteVH).updateCheck(selectedIds.contains(baseNote.id), baseNote.color)
    }

    private fun <T> SortedList<T>.toList(): List<T> {
        val mutableList = mutableListOf<T>()
        for (i in 0 until this.size()) {
            mutableList.add(this[i])
        }
        return mutableList.toList()
    }
}

fun NotesSort.createCallback(adapter: RecyclerView.Adapter<*>?) =
    when (sortedBy) {
        NotesSortBy.TITLE -> BaseNoteTitleSort(adapter, sortDirection)
        NotesSortBy.MODIFIED_DATE -> BaseNoteModifiedDateSort(adapter, sortDirection)
        NotesSortBy.CREATION_DATE -> BaseNoteCreationDateSort(adapter, sortDirection)
    }
