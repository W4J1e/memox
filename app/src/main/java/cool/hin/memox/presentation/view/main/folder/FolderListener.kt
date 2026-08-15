package cool.hin.memox.presentation.view.main.folder

import androidx.recyclerview.widget.RecyclerView

interface FolderListener {

    fun onStartDrag(viewHolder: RecyclerView.ViewHolder)

    fun onClick(position: Int)

    fun onEdit(position: Int)

    fun onDelete(position: Int)

    fun onToggleVisibility(position: Int)
}
