package cool.hin.memox.presentation.activity.note

import cool.hin.memox.presentation.view.note.listitem.ListManager
import cool.hin.memox.presentation.viewmodel.preference.EditListAction

class NoteListActionHandler(private val listManager: ListManager) {

    fun handleAction(action: EditListAction) {
        when (action) {
            EditListAction.DELETE_CHECKED -> {
                listManager.deleteCheckedItems()
            }
            EditListAction.CHECK_ALL -> {
                listManager.changeCheckedForAll(true)
            }
            EditListAction.UNCHECK_ALL -> {
                listManager.changeCheckedForAll(false)
            }
        }
    }
}
