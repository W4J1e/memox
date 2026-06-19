package cool.hin.memox.presentation.view.main.label

import androidx.recyclerview.widget.RecyclerView
import cool.hin.memox.data.model.Label
import cool.hin.memox.databinding.RecyclerSelectableLabelBinding

class SelectableLabelVH(
    private val binding: RecyclerSelectableLabelBinding,
    private val onChecked: (position: Int, checked: Boolean) -> Unit,
) : RecyclerView.ViewHolder(binding.root) {

    init {
        binding.root.setOnCheckedChangeListener { _, isChecked ->
            onChecked(absoluteAdapterPosition, isChecked)
        }
    }

    fun bind(label: Label, checked: Boolean) {
        binding.root.apply {
            text = label.value
            isChecked = checked
        }
    }
}
