package cool.hin.memox.presentation.view.note

import androidx.annotation.ColorInt
import androidx.recyclerview.widget.RecyclerView
import cool.hin.memox.databinding.RecyclerToggleBinding
import cool.hin.memox.presentation.setControlsContrastColorForAllViews

class ToggleVH(private val binding: RecyclerToggleBinding, @ColorInt private val color: Int?) :
    RecyclerView.ViewHolder(binding.root) {

    fun bind(toggle: Toggle) {
        binding.root.apply {
            setIconResource(toggle.drawable)
            contentDescription = context.getString(toggle.titleResId)
            setOnClickListener { toggle.onToggle(toggle) }
            isChecked = toggle.checked
            color?.let { setControlsContrastColorForAllViews(it, overwriteBackground = false) }
        }
    }
}
