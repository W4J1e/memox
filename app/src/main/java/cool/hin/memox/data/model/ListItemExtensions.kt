package cool.hin.memox.data.model

import cool.hin.memox.presentation.view.note.listitem.areAllChecked
import cool.hin.memox.utils.uniqueCurrentMillis

operator fun ListItem.plus(list: List<ListItem>): List<ListItem> {
    return mutableListOf(this) + list
}

fun ListItem.findChild(childId: Int): ListItem? {
    return this.children.find { child -> child.id == childId }
}

fun ListItem.check(checked: Boolean, checkChildren: Boolean = true) {
    this.checked = checked
    val checkedTimestamp = if (checked) uniqueCurrentMillis() else null
    this.checkedTimestamp = checkedTimestamp
    if (checkChildren) {
        this.children.forEach { child ->
            child.checked = checked
            child.checkedTimestamp = checkedTimestamp
        }
    }
}

fun ListItem.shouldParentBeUnchecked(): Boolean {
    return children.isNotEmpty() && !children.areAllChecked() && checked
}

fun ListItem.shouldParentBeChecked(): Boolean {
    return children.isNotEmpty() && children.areAllChecked() && !checked
}
