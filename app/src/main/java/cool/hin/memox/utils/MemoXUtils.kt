package cool.hin.memox.utils

import cool.hin.memox.data.model.Reminder
import cool.hin.memox.data.model.Repetition
import cool.hin.memox.data.model.RepetitionTimeUnit
import cool.hin.memox.data.model.getSafeLong
import cool.hin.memox.data.model.getSafeString
import java.util.Date
import org.json.JSONObject

typealias NotallyReminderJson = String

/**
 * Parse Notally Reminder JSON to memoX Reminder
 *
 * [Notally/Reminder.kt](https://github.com/OmGodse/Notally/blob/master/app/src/main/java/com/omgodse/notally/room/Reminder.kt)
 */
fun NotallyReminderJson?.toMemoXReminder(): Reminder? {
    if (this == null) return null
    return with(JSONObject(this)) {
        val dateTime = getSafeLong("timestamp")?.let { Date(it) } ?: return null
        val repetition =
            getSafeString("frequency")?.let {
                when (it) {
                    "DAILY" -> Repetition(1, RepetitionTimeUnit.DAYS)
                    "MONTHLY" -> Repetition(1, RepetitionTimeUnit.MONTHS)
                    else -> null
                }
            }
        Reminder(0, dateTime, repetition)
    }
}
