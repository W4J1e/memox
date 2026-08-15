package cool.hin.memox.presentation.view.main.folder

import android.annotation.SuppressLint
import androidx.recyclerview.widget.RecyclerView
import cool.hin.memox.R
import cool.hin.memox.databinding.RecyclerFolderBinding

@SuppressLint("ClickableViewAccessibility")
class FolderVH(private val binding: RecyclerFolderBinding, listener: FolderListener) :
    RecyclerView.ViewHolder(binding.root) {

    init {
        binding.apply {
            FolderText.setOnClickListener { listener.onClick(absoluteAdapterPosition) }
            FolderText.setOnLongClickListener { _ ->
                listener.onStartDrag(this@FolderVH)
                true
            }
            DragHandle.setOnLongClickListener { _ ->
                listener.onStartDrag(this@FolderVH)
                true
            }
            EditButton.setOnClickListener { listener.onEdit(absoluteAdapterPosition) }
            DeleteButton.setOnClickListener { listener.onDelete(absoluteAdapterPosition) }
            VisibilityButton.setOnClickListener {
                listener.onToggleVisibility(absoluteAdapterPosition)
            }
        }
    }

    fun bind(value: FolderData) {
        binding.FolderText.text = value.id
        binding.VisibilityButton.setImageResource(
            if (value.visibleInNavigation) R.drawable.visibility else R.drawable.visibility_off
        )
    }
}
