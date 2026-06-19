package cool.hin.memox.utils.changehistory

import cool.hin.memox.presentation.view.note.listitem.ListManager
import cool.hin.memox.presentation.view.note.listitem.ListState

class DeleteCheckedChange(old: ListState, new: ListState, listManager: ListManager) :
    ListBatchChange(old, new, listManager)
