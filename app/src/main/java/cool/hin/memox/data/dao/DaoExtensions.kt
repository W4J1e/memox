package cool.hin.memox.data.dao

import android.content.Context
import cool.hin.memox.data.model.Folder
import cool.hin.memox.utils.cancelPinAndReminders
import cool.hin.memox.utils.pinAndScheduleReminders

suspend fun Context.moveBaseNotes(baseNoteDao: BaseNoteDao, ids: LongArray, folder: Folder) {
    // Only reminders of notes in NOTES folder are active
    if (folder == Folder.DELETED) {
        baseNoteDao.move(ids, folder, System.currentTimeMillis())
    } else {
        baseNoteDao.move(ids, folder)
    }
    val notes = baseNoteDao.getByIds(ids)
    // Only reminders of notes in NOTES folder are active
    when (folder) {
        Folder.NOTES -> pinAndScheduleReminders(notes)
        else -> cancelPinAndReminders(notes)
    }
}
