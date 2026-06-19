package cool.hin.memox.presentation.view.note.listitem.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.NestedScrollViewItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import cool.hin.memox.data.model.ListItem
import cool.hin.memox.data.model.NoteViewMode
import cool.hin.memox.databinding.RecyclerListItemBinding
import cool.hin.memox.presentation.view.note.listitem.ListItemDragCallback
import cool.hin.memox.presentation.view.note.listitem.ListManager
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import cool.hin.memox.presentation.viewmodel.preference.TextSizeSp

data class ListItemHighlight(
    val itemPos: Int,
    val resultPos: Int,
    val startIdx: Int,
    val endIdx: Int,
    var selected: Boolean,
)

abstract class ListItemAdapterBase(
    private val adapter: RecyclerView.Adapter<*>,
    @ColorInt var backgroundColor: Int,
    private val textSize: TextSizeSp,
    elevation: Float,
    private val preferences: MemoXPreferences,
    private val listManager: ListManager,
    private val isCheckedListAdapter: Boolean,
    scrollView: NestedScrollView,
) {

    private val callback = ListItemDragCallback(elevation, listManager)
    private val touchHelper = NestedScrollViewItemTouchHelper(callback, scrollView)
    private val highlights = mutableMapOf<Int, MutableList<ListItemHighlight>>()

    fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        touchHelper.attachToRecyclerView(recyclerView)
    }

    fun onBindViewHolder(holder: ListItemVH, position: Int, viewMode: NoteViewMode) {
        val item = getItem(position)
        holder.bind(
            backgroundColor,
            item,
            position,
            highlights[position],
            preferences.listItemSorting.value,
            viewMode,
        )
    }

    abstract fun getItem(position: Int): ListItem

    fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ListItemVH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = RecyclerListItemBinding.inflate(inflater, parent, false)
        binding.root.background = parent.background
        return ListItemVH(binding, listManager, touchHelper, textSize, isCheckedListAdapter)
    }

    internal fun setBackgroundColor(@ColorInt color: Int) {
        backgroundColor = color
        adapter.notifyDataSetChanged()
    }

    internal fun clearHighlights(): Set<Int> {
        val highlightedItemPos =
            highlights.entries.flatMap { (_, value) -> value.map { it.itemPos } }.toSet()
        highlights.clear()
        highlightedItemPos.forEach { adapter.notifyItemChanged(it) }
        return highlightedItemPos
    }

    fun highlightText(highlight: ListItemHighlight) {
        if (highlights.containsKey(highlight.itemPos)) {
            highlights[highlight.itemPos]!!.add(highlight)
        } else {
            highlights[highlight.itemPos] = mutableListOf(highlight)
        }
        adapter.notifyItemChanged(highlight.itemPos)
    }

    internal fun selectHighlight(pos: Int): Int {
        var selectedItemPos = -1
        highlights.entries.forEach { (_, value) ->
            value.forEach {
                val isSelected = it.selected
                it.selected = it.resultPos == pos
                if (isSelected != it.selected) {
                    adapter.notifyItemChanged(it.itemPos)
                }
                if (it.selected) {
                    selectedItemPos = it.itemPos
                }
            }
        }
        return selectedItemPos
    }
}
