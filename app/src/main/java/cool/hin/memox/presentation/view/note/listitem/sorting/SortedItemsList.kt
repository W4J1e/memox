package cool.hin.memox.presentation.view.note.listitem.sorting

import androidx.recyclerview.widget.SortedList
import cool.hin.memox.data.model.ListItem

class SortedItemsList(val callback: ListItemParentSortCallback) :
    SortedList<ListItem>(ListItem::class.java, callback) {

    init {
        this.callback.setItems(this)
    }
}
