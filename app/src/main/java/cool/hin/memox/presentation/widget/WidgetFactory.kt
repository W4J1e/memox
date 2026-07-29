package cool.hin.memox.presentation.widget

import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import cool.hin.memox.MemoXApplication
import cool.hin.memox.R
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import cool.hin.memox.presentation.viewmodel.preference.displayBodySize
import cool.hin.memox.presentation.viewmodel.preference.displayTitleSize
import cool.hin.memox.presentation.widget.WidgetProvider.Companion.extractWidgetColors
import cool.hin.memox.presentation.widget.WidgetProvider.Companion.getWidgetOpenNoteIntent
import cool.hin.memox.presentation.widget.WidgetProvider.Companion.getWidgetSelectNoteIntent
import cool.hin.memox.presentation.withoutImagePlaceholders

class WidgetFactory(
    private val app: MemoXApplication,
    private val id: Long,
    private val widgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    private var baseNote: BaseNote? = null
    private lateinit var database: MemoXDatabase
    private val preferences = MemoXPreferences.getInstance(app)

    init {
        MemoXDatabase.getDatabase(app).observeForever { database = it }
    }

    override fun onCreate() {}

    override fun onDestroy() {}

    override fun getCount(): Int {
        return if (baseNote != null) 1 else 0
    }

    override fun onDataSetChanged() {
        baseNote = database.getBaseNoteDao().get(id)
    }

    override fun getViewAt(position: Int): RemoteViews {
        val copy = baseNote
        requireNotNull(copy, { "baseNote is null" })
        return getNoteView(copy)
    }

    private fun getNoteView(note: BaseNote): RemoteViews {
        return RemoteViews(app.packageName, R.layout.widget_note).apply {
            val textSize = preferences.textSizeNoteEditor.value
            setTextViewTextSize(R.id.Title, TypedValue.COMPLEX_UNIT_SP, textSize.displayTitleSize)
            setTextViewText(R.id.Title, note.title)

            val bodyTextSize = textSize.displayBodySize

            setTextViewTextSize(R.id.Note, TypedValue.COMPLEX_UNIT_SP, bodyTextSize)
            val displayBody = note.body.withoutImagePlaceholders()
            if (displayBody.isNotEmpty()) {
                setTextViewText(R.id.Note, displayBody)
                setViewVisibility(R.id.Note, View.VISIBLE)
            } else setViewVisibility(R.id.Note, View.GONE)

            setOnClickFillInIntent(R.id.ChangeNote, getWidgetSelectNoteIntent(widgetId))
            setOnClickFillInIntent(R.id.LinearLayout, getWidgetOpenNoteIntent(note.type, note.id))

            val (_, controlsColor) = app.extractWidgetColors(note.color, preferences)
            setTextViewsTextColor(listOf(R.id.Title, R.id.Note), controlsColor)
            setImageViewColor(R.id.ChangeNote, controlsColor)
        }
    }

    private fun RemoteViews.setTextViewsTextColor(viewIds: List<Int>, color: Int) {
        viewIds.forEach { viewId -> setInt(viewId, "setTextColor", color) }
    }

    private fun RemoteViews.setImageViewColor(viewId: Int, color: Int) {
        setInt(viewId, "setColorFilter", color)
    }

    override fun getViewTypeCount() = 1

    override fun hasStableIds(): Boolean {
        return false
    }

    override fun getLoadingView(): RemoteViews? {
        return null
    }

    override fun getItemId(position: Int): Long {
        return 1
    }
}
