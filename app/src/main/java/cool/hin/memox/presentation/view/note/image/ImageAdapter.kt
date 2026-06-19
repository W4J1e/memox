package cool.hin.memox.presentation.view.note.image

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cool.hin.memox.data.model.FileAttachment
import cool.hin.memox.databinding.RecyclerImageBinding
import java.io.File

class ImageAdapter(private val mediaRoot: File?, val items: ArrayList<FileAttachment>) :
    RecyclerView.Adapter<ImageVH>() {

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ImageVH, position: Int) {
        val image = items[position]
        val file = if (mediaRoot != null) File(mediaRoot, image.localName) else null
        holder.bind(file)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageVH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = RecyclerImageBinding.inflate(inflater, parent, false)
        return ImageVH(binding)
    }
}
