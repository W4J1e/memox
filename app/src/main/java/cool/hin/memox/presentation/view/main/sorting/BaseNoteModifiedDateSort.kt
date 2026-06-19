package cool.hin.memox.presentation.view.main.sorting

import androidx.recyclerview.widget.RecyclerView
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.presentation.viewmodel.preference.SortDirection

class BaseNoteModifiedDateSort(adapter: RecyclerView.Adapter<*>?, sortDirection: SortDirection) :
    ItemSort(adapter, sortDirection) {

    override fun compare(note1: BaseNote, note2: BaseNote, sortDirection: SortDirection): Int {
        val sort = note1.compareModified(note2)
        return if (sortDirection == SortDirection.ASC) sort else -1 * sort
    }
}

fun BaseNote.compareModified(other: BaseNote) = modifiedTimestamp.compareTo(other.modifiedTimestamp)
