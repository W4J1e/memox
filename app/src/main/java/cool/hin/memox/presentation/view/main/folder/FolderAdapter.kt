package cool.hin.memox.presentation.view.main.folder

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import cool.hin.memox.databinding.RecyclerFolderBinding

class FolderAdapter(private val listener: FolderListener) :
    ListAdapter<FolderData, FolderVH>(DiffCallback) {

    override fun onBindViewHolder(holder: FolderVH, position: Int) {
        val folder = getItem(position)
        holder.bind(folder)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderVH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = RecyclerFolderBinding.inflate(inflater, parent, false)
        return FolderVH(binding, listener)
    }

    fun onItemMove(fromPosition: Int, toPosition: Int): List<FolderData> {
        if (
            fromPosition !in currentList.indices ||
                toPosition !in currentList.indices ||
                fromPosition == toPosition
        ) {
            return currentList
        }
        val list = currentList.toMutableList()
        val fromFolder = list.removeAt(fromPosition)
        list.add(toPosition, fromFolder)
        submitList(list)
        return list
    }
}

data class FolderData(val id: String, var visibleInNavigation: Boolean, val order: Int)

private object DiffCallback : DiffUtil.ItemCallback<FolderData>() {

    override fun areItemsTheSame(oldItem: FolderData, newItem: FolderData): Boolean {
        return oldItem == newItem
    }

    override fun areContentsTheSame(oldItem: FolderData, newItem: FolderData): Boolean {
        return oldItem == newItem
    }
}
