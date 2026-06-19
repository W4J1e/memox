package cool.hin.memox.presentation.view.main

import android.content.res.ColorStateList
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import cool.hin.memox.R
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.ColorString
import cool.hin.memox.databinding.RecyclerColorBinding
import cool.hin.memox.presentation.dp
import cool.hin.memox.presentation.extractColor
import cool.hin.memox.presentation.getColorFromAttr
import cool.hin.memox.presentation.getContrastFontColor
import cool.hin.memox.presentation.view.misc.ItemListener

class ColorVH(private val binding: RecyclerColorBinding, listener: ItemListener) :
    RecyclerView.ViewHolder(binding.root) {

    init {
        binding.CardView.setOnClickListener { listener.onClick(absoluteAdapterPosition) }
        binding.CardView.setOnLongClickListener {
            listener.onLongClick(absoluteAdapterPosition)
            true
        }
    }

    fun bind(color: ColorString, isSelected: Boolean, isNoteDefault: Boolean = false) {
        val showAddIcon = color == BaseNote.COLOR_NEW
        val context = binding.root.context
        val value =
            if (showAddIcon) context.getColorFromAttr(R.attr.colorOnSurface)
            else context.extractColor(color)
        val controlsColor = context.getContrastFontColor(value)
        binding.apply {
            CardView.apply {
                contentDescription = color
                setCardBackgroundColor(value)
                if (color == BaseNote.COLOR_DEFAULT) {
                    setBackgroundResource(R.drawable.dashed_background)
                }
                if (isSelected) {
                    strokeWidth = 4.dp
                    strokeColor = controlsColor
                } else {
                    strokeWidth = 1.dp
                    strokeColor = controlsColor
                }
            }
            CardIcon.apply {
                if (showAddIcon) {
                    setImageResource(R.drawable.add)
                } else if (isSelected) {
                    setImageResource(R.drawable.checked_circle)
                }
                imageTintList = ColorStateList.valueOf(controlsColor)
                isVisible = showAddIcon || isSelected
            }
            DefaultColorIcon.apply {
                isVisible = isNoteDefault
                imageTintList = ColorStateList.valueOf(controlsColor)
            }
        }
    }
}
