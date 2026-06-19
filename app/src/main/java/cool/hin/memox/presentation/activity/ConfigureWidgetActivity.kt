package cool.hin.memox.presentation.activity

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.presentation.activity.note.PickNoteActivity
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import cool.hin.memox.presentation.widget.WidgetProvider

class ConfigureWidgetActivity : PickNoteActivity() {

    private val id by lazy {
        intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val result = Intent()
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(RESULT_CANCELED, result)
    }

    override fun onClick(position: Int) {
        if (position != -1) {
            val preferences = MemoXPreferences.getInstance(application)
            val baseNote = adapter.getItem(position) as BaseNote
            preferences.updateWidget(id, baseNote.id, baseNote.type)

            val manager = AppWidgetManager.getInstance(this)
            WidgetProvider.updateWidget(application, manager, id, baseNote.id, baseNote.type)

            val success = Intent()
            success.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            setResult(RESULT_OK, success)
            finish()
        }
    }
}
