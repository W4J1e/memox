package cool.hin.memox.presentation.view.note

import androidx.recyclerview.widget.RecyclerView
import cool.hin.memox.databinding.ErrorBinding
import cool.hin.memox.utils.FileError

class ErrorVH(private val binding: ErrorBinding) : RecyclerView.ViewHolder(binding.root) {

    fun bind(error: FileError) {
        binding.Name.text = error.name
        binding.Description.text = error.description
    }
}
