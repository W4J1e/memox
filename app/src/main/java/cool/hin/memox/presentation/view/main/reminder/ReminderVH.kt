package cool.hin.memox.presentation.view.main.reminder

import androidx.recyclerview.widget.RecyclerView
import cool.hin.memox.data.model.Reminder
import cool.hin.memox.data.model.toRepetitionText
import cool.hin.memox.databinding.RecyclerReminderBinding
import cool.hin.memox.presentation.format
import cool.hin.memox.presentation.viewmodel.preference.DateFormat
import cool.hin.memox.presentation.viewmodel.preference.TimeFormat

class ReminderVH(
    private val binding: RecyclerReminderBinding,
    private val dateFormat: DateFormat,
    private val timeFormat: TimeFormat,
    private val listener: ReminderListener,
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(value: Reminder) {
        binding.apply {
            DateTime.text = value.dateTime.format(dateFormat, timeFormat, ensureFullFormat = true)
            Repetition.text = value.toRepetitionText(itemView.context)
            EditButton.setOnClickListener { listener.edit(value) }
            DeleteButton.setOnClickListener { listener.delete(value) }
        }
    }
}
