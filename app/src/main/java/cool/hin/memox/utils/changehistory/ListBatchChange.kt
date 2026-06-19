package cool.hin.memox.utils.changehistory

import cool.hin.memox.presentation.view.note.listitem.ListManager
import cool.hin.memox.presentation.view.note.listitem.ListState

open class ListBatchChange(old: ListState, new: ListState, private val listManager: ListManager) :
    ValueChange<ListState>(old, new) {

    override fun update(value: ListState, isUndo: Boolean) {
        listManager.setState(if (isUndo) oldValue else newValue)
    }

    override fun toString(): String {
        return javaClass.simpleName
    }
}
