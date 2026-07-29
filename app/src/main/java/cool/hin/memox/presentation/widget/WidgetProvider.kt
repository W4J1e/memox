package cool.hin.memox.presentation.widget

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import cool.hin.memox.MemoXApplication
import cool.hin.memox.R
import cool.hin.memox.data.MemoXDatabase
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Type
import cool.hin.memox.presentation.activity.ConfigureWidgetActivity
import cool.hin.memox.presentation.activity.note.EditActivity.Companion.EXTRA_SELECTED_BASE_NOTE
import cool.hin.memox.presentation.activity.note.EditNoteActivity
import cool.hin.memox.presentation.extractColor
import cool.hin.memox.presentation.getContrastFontColor
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import cool.hin.memox.presentation.viewmodel.preference.Theme
import cool.hin.memox.utils.embedIntentExtras
import cool.hin.memox.utils.isSystemInDarkMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_NOTES_MODIFIED,
            Intent.ACTION_LOCALE_CHANGED -> {
                val app = context.applicationContext as MemoXApplication
                val preferences = MemoXPreferences.getInstance(context)
                val noteIds = intent.getLongArrayExtra(EXTRA_MODIFIED_NOTES)
                if (noteIds != null) {
                    updateWidgets(context, noteIds)
                }
            }
            ACTION_OPEN_NOTE -> openActivity(context, intent, EditNoteActivity::class.java)
            ACTION_SELECT_NOTE -> openActivity(context, intent, ConfigureWidgetActivity::class.java)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val app = context.applicationContext as Application
        val preferences = MemoXPreferences.getInstance(app)

        appWidgetIds.forEach { id -> preferences.deleteWidget(id) }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val app = context.applicationContext as MemoXApplication
        val preferences = MemoXPreferences.getInstance(app)

        appWidgetIds.forEach { id ->
            val noteId = preferences.getWidgetData(id)
            val noteType = preferences.getWidgetNoteType(id) ?: return
            updateWidget(
                app,
                appWidgetManager,
                id,
                noteId,
                noteType,
            )
        }
    }

    companion object {

        fun updateWidgets(context: Context, noteIds: LongArray? = null) {
            val app = context.applicationContext as Application
            val preferences = MemoXPreferences.getInstance(app)

            val manager = AppWidgetManager.getInstance(context)
            val updatableWidgets = preferences.getUpdatableWidgets(noteIds)

            updatableWidgets.forEach { (id, noteId) ->
                updateWidget(
                    app,
                    manager,
                    id,
                    noteId,
                    preferences.getWidgetNoteType(id),
                )
            }
        }

        fun updateWidget(
            context: ContextWrapper,
            manager: AppWidgetManager,
            id: Int,
            noteId: Long,
            noteType: Type?,
        ) {
            // Widgets displaying the same note share the same factory since only the noteId is
            // embedded
            val intent = Intent(context, WidgetService::class.java)
            intent.putExtra(EXTRA_SELECTED_BASE_NOTE, noteId)
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            intent.embedIntentExtras()

            MainScope().launch {
                val database = MemoXDatabase.getDatabase(context).value
                val color =
                    withContext(Dispatchers.IO) { database.getBaseNoteDao().getColorOfNote(noteId) }
                if (color == null) {
                    val app = context.applicationContext as Application
                    val preferences = MemoXPreferences.getInstance(app)
                    preferences.deleteWidget(id)
                    val view =
                        RemoteViews(context.packageName, R.layout.widget).apply {
                            setRemoteAdapter(R.id.ListView, intent)
                            setEmptyView(R.id.ListView, R.id.Empty)
                            setOnClickPendingIntent(
                                R.id.Empty,
                                Intent(context, WidgetProvider::class.java)
                                    .apply {
                                        action = ACTION_SELECT_NOTE
                                        data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                                    }
                                    .asPendingIntent(context),
                            )
                            setPendingIntentTemplate(
                                R.id.ListView,
                                Intent(context, WidgetProvider::class.java).asPendingIntent(context),
                            )
                        }
                    manager.updateAppWidget(id, view)
                    manager.notifyAppWidgetViewDataChanged(id, R.id.ListView)
                    return@launch
                }
                val view =
                    RemoteViews(context.packageName, R.layout.widget).apply {
                        setRemoteAdapter(R.id.ListView, intent)
                        setEmptyView(R.id.ListView, R.id.Empty)
                        setOnClickPendingIntent(
                            R.id.Empty,
                            Intent(context, WidgetProvider::class.java)
                                .apply {
                                    action = ACTION_SELECT_NOTE
                                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                                }
                                .asPendingIntent(context),
                        )
                        setPendingIntentTemplate(
                            R.id.ListView,
                            Intent(context, WidgetProvider::class.java).asPendingIntent(context),
                        )

                        noteType?.let {
                            setOnClickPendingIntent(
                                R.id.Layout,
                                Intent(context, WidgetProvider::class.java)
                                    .setOpenNoteIntent(noteType, noteId)
                                    .asPendingIntent(context),
                            )
                        }
                        val preferences = MemoXPreferences.getInstance(context)
                        val (backgroundColor, _) =
                            context.extractWidgetColors(color, preferences)
                        setInt(R.id.Layout, "setBackgroundColor", backgroundColor)
                    }
                manager.updateAppWidget(id, view)
                manager.notifyAppWidgetViewDataChanged(id, R.id.ListView)
            }
        }

        fun getWidgetOpenNoteIntent(noteType: Type, noteId: Long): Intent {
            return Intent().setOpenNoteIntent(noteType, noteId)
        }

        private fun Intent.setOpenNoteIntent(noteType: Type, noteId: Long) = apply {
            action = ACTION_OPEN_NOTE
            putExtra(EXTRA_SELECTED_BASE_NOTE, noteId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }

        private fun Intent.asPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                this,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )

        fun Context.extractWidgetColors(
            color: String,
            preferences: MemoXPreferences,
        ): Pair<Int, Int> {
            val backgroundColor =
                if (color == BaseNote.COLOR_DEFAULT) {
                    val id =
                        when (preferences.theme.value) {
                            Theme.DARK -> R.color.ContainerDark
                            Theme.SUPER_DARK -> R.color.ContainerSuperDark
                            Theme.LIGHT -> R.color.ContainerLight
                            Theme.FOLLOW_SYSTEM -> {
                                if (isSystemInDarkMode()) R.color.ContainerDark
                                else R.color.ContainerLight
                            }
                        }
                    ContextCompat.getColor(this, id)
                } else extractColor(color)
            return Pair(backgroundColor, getContrastFontColor(backgroundColor))
        }

        private fun openActivity(context: Context, originalIntent: Intent, clazz: Class<*>) {
            val id = originalIntent.getLongExtra(EXTRA_SELECTED_BASE_NOTE, 0)
            val widgetId = originalIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
            context.startActivity(createIntent(context, clazz, id, widgetId, originalIntent))
        }

        private fun createIntent(
            context: Context,
            clazz: Class<*>,
            noteId: Long,
            widgetId: Int,
            originalIntent: Intent? = null,
        ): Intent {
            val intent = Intent(context, clazz)
            intent.putExtra(EXTRA_SELECTED_BASE_NOTE, noteId)
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            originalIntent?.let { intent.data = it.data }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            return intent
        }

        fun sendBroadcast(context: Context, ids: LongArray) =
            Intent(context, WidgetProvider::class.java).apply {
                action = ACTION_NOTES_MODIFIED
                putExtra(EXTRA_MODIFIED_NOTES, ids)
                context.sendBroadcast(this)
            }

        fun getWidgetSelectNoteIntent(id: Int) =
            Intent(ACTION_SELECT_NOTE).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }

        private const val EXTRA_MODIFIED_NOTES = "cool.hin.memox.EXTRA_MODIFIED_NOTES"
        private const val ACTION_NOTES_MODIFIED = "cool.hin.memox.ACTION_NOTE_MODIFIED"

        const val ACTION_OPEN_NOTE = "cool.hin.memox.ACTION_OPEN_NOTE"
        const val ACTION_SELECT_NOTE = "cool.hin.memox.ACTION_SELECT_NOTE"
    }
}
