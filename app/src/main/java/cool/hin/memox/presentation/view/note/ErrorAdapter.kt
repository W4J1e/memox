package cool.hin.memox.presentation.view.note

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cool.hin.memox.databinding.ErrorBinding
import cool.hin.memox.utils.FileError

class ErrorAdapter(private val items: List<FileError>) : RecyclerView.Adapter<ErrorVH>() {

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ErrorVH, position: Int) {
        val error = items[position]
        holder.bind(error)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ErrorVH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ErrorBinding.inflate(inflater, parent, false)
        return ErrorVH(binding)
    }
}
