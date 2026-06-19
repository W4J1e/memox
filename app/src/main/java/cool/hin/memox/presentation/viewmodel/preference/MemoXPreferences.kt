package cool.hin.memox.presentation.viewmodel.preference

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cool.hin.memox.R
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.model.Type
import cool.hin.memox.presentation.viewmodel.preference.Constants.PASSWORD_EMPTY
import cool.hin.memox.utils.backup.importPreferences
import cool.hin.memox.utils.toCamelCase
import org.json.JSONArray
import org.json.JSONObject

class MemoXPreferences private constructor(private val context: Context) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)

    private val encryptedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "secret_shared_prefs",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val theme = createEnumPreference(preferences, "theme", Theme.FOLLOW_SYSTEM, R.string.theme)
    val useDynamicColors = BooleanPreference("useDynamicColors", preferences, false)
    val textSizeNoteEditor =
        FloatPreference(
            "textSizeNoteEditor",
            preferences,
            16f,
            12f,
            32f,
            R.string.text_size_note_editor,
        )
    val textSizeOverview =
        FloatPreference("textSizeOverview", preferences, 16f, 12f, 32f, R.string.text_size_overview)
    val dateFormatOverview =
        createEnumPreference(
            preferences,
            "dateFormatOverview",
            DateFormat.DD_MM_YY_GER,
            R.string.date_format_overview,
        )
    val timeFormatOverview =
        createEnumPreference(
            preferences,
            "timeFormatOverview",
            TimeFormat.NONE,
            R.string.time_format_overview,
        )
    val dateFormatNoteView =
        createEnumPreference(
            preferences,
            "dateFormatNoteView",
            DateFormat.DD_MM_YY_GER,
            R.string.date_format_note_view,
        )
    val timeFormatNoteView =
        createEnumPreference(
            preferences,
            "timeFormatNoteView",
            TimeFormat.TWENTY_FOUR_H,
            R.string.time_format_note_view,
        )

    val notesView = createEnumPreference(preferences, "view", NotesView.LIST, R.string.view)
    val notesSorting = NotesSortPreference(preferences)
    val startView =
        StringPreference("startView", preferences, START_VIEW_DEFAULT, R.string.start_view)
    val listItemSorting =
        createEnumPreference(
            preferences,
            "listItemSorting",
            ListItemSort.AUTO_SORT_BY_CHECKED,
            R.string.list_item_auto_sort,
        )

    val defaultListNoteViewMode =
        createEnumPreference(
            preferences,
            "defaultListNoteViewMode",
            DefaultListNoteViewMode.LAST_USED,
            R.string.default_list_note_view_mode,
        )

    val labelsHidden = StringSetPreference("labelsHiddenInNavigation", preferences, setOf())
    val labelTagsHiddenInOverview =
        BooleanPreference(
            "labelsHiddenInOverview",
            preferences,
            false,
            R.string.labels_hidden_in_overview_title,
        )
    val maxLabels =
        IntPreference(
            "maxLabelsInNavigation",
            preferences,
            5,
            0,
            200,
            R.string.max_labels_to_display,
        )

    val backupsFolder =
        StringPreference("autoBackup", preferences, EMPTY_PATH, R.string.auto_backups_folder)
    val backupOnSave =
        BooleanPreference("backupOnSave", preferences, false, R.string.auto_backup_on_save)
    val periodicBackups = PeriodicBackupsPreference(preferences)
    val periodicBackupLastExecution =
        LongPreference("periodicBackupLastExecution", preferences, -1L)
    val autoRemoveDeletedNotesAfterDays =
        IntPreference(
            "autoRemoveDeletedNotesAfterDays",
            preferences,
            0,
            0,
            3650,
            R.string.auto_remove_deleted_notes,
        )

    val backupPassword by lazy {
        StringPreference(
            "backupPassword",
            encryptedPreferences,
            PASSWORD_EMPTY,
            R.string.backup_password,
        )
    }

    // WebDAV sync preferences
    val webdavUrl =
        StringPreference("webdav_url", preferences, "", R.string.webdav_url)
    val webdavUsername =
        StringPreference("webdav_username", preferences, "", R.string.webdav_username)
    val webdavPassword by lazy {
        StringPreference(
            "webdav_password",
            encryptedPreferences,
            "",
            R.string.webdav_password,
        )
    }
    val webdavSyncEnabled =
        BooleanPreference("webdav_sync_enabled", preferences, false, R.string.webdav_sync_enabled)
    val webdavAutoSync =
        BooleanPreference("webdav_auto_sync", preferences, false, R.string.webdav_auto_sync)
    val webdavLastSyncTime =
        LongPreference("webdav_last_sync_time", preferences, 0L)

    val autoSaveAfterIdleTime =
        IntPreference(
            "autoSaveAfterIdleTime",
            preferences,
            5,
            -1,
            60 * 60 * 5,
            R.string.auto_save_after_idle_time,
        )

    val biometricLock =
        createEnumPreference(
            preferences,
            "biometricLock",
            BiometricLock.DISABLED,
            R.string.biometric_lock,
        )

    val iv = ByteArrayPreference("encryption_iv", preferences, null)
    val databaseEncryptionKey =
        EncryptedPassphrasePreference("database_encryption_key", preferences, ByteArray(0))
    val fallbackDatabaseEncryptionKey by lazy {
        ByteArrayPreference("fallback_database_encryption_key", encryptedPreferences, ByteArray(0))
    }
    val secureFlag =
        BooleanPreference("secureFlag", preferences, false, R.string.disallow_screenshots)

    val dataInPublicFolder =
        BooleanPreference("dataOnExternalStorage", preferences, false, R.string.data_in_public)

    val editNoteActivityTopActions =
        createEnumListPreference(
            preferences,
            "editNoteActivityTopActions",
            DEFAULT_EDIT_NOTE_TOP_ACTIONS,
        )

    val editNoteActivityBottomAction: EnumPreference<EditAction> =
        createEnumPreference(
            preferences,
            "editNoteActivityBottomAction",
            DEFAULT_EDIT_NOTE_BOTTOM_ACTION,
        )

    fun getSafeEditNoteActivityTopActions(): List<EditAction> {
        return editNoteActivityTopActions.value.let { actions ->
            if (actions.size != 3) {
                editNoteActivityTopActions.save(DEFAULT_EDIT_NOTE_TOP_ACTIONS)
                DEFAULT_EDIT_NOTE_TOP_ACTIONS
            } else {
                actions
            }
        }
    }

    /**
     * Tracks app-internal data schema/migration steps. 0 = initial state, no migrations run yet See
     * [DataSchemaMigrations.kt]
     */
    val dataSchemaId = IntPreference("dataSchemaId", preferences, 0, 0, Integer.MAX_VALUE)

    val defaultNoteColor = StringPreference("defaultNoteColor", preferences, BaseNote.COLOR_DEFAULT)

    fun setDataSchemaId(value: Int) {
        preferences.edit(true) { putInt(dataSchemaId.key, value) }
        dataSchemaId.refresh()
    }

    fun getWidgetData(id: Int) = preferences.getLong("widget:$id", 0)

    fun getWidgetNoteType(id: Int) =
        preferences.getString("widgetNoteType:$id", null)?.let { Type.valueOf(it) }

    fun deleteWidget(id: Int) {
        preferences.edit(true) {
            remove("widget:$id")
            remove("widgetNoteType:$id")
        }
    }

    fun updateWidget(id: Int, noteId: Long, noteType: Type) {
        preferences.edit(true) {
            putLong("widget:$id", noteId)
            putString("widgetNoteType:$id", noteType.name)
            commit()
        }
    }

    fun getUpdatableWidgets(noteIds: LongArray? = null): List<Pair<Int, Long>> {
        val updatableWidgets = ArrayList<Pair<Int, Long>>()
        val pairs = preferences.all
        pairs.keys.forEach { key ->
            val token = "widget:"
            if (key.startsWith(token)) {
                val end = key.substringAfter(token)
                val id = end.toIntOrNull()
                if (id != null) {
                    val value = pairs[key] as? Long
                    if (value != null) {
                        if (noteIds == null || noteIds.contains(value)) {
                            updatableWidgets.add(Pair(id, value))
                        }
                    }
                }
            }
        }
        return updatableWidgets
    }

    fun showDateCreated(): Boolean {
        return dateFormatNoteView.value != DateFormat.NONE ||
            timeFormatNoteView.value != TimeFormat.NONE
    }

    fun toJsonString(): String {
        val jsonObject = JSONObject()
        for ((key, value) in preferences.all) {
            if (key in listOf(biometricLock.key, iv.key, databaseEncryptionKey.key)) {
                continue
            }
            when (value) {
                is Collection<*> -> jsonObject.put(key, JSONArray(value))
                is Enum<*> -> jsonObject.put(key, value.name.toCamelCase())
                else -> jsonObject.put(key, value)
            }
        }
        return jsonObject.toString(4)
    }

    fun import(context: Context, uri: Uri) =
        context.importPreferences(uri, preferences.edit()).also { reload() }

    fun reset() {
        preferences.edit().clear().commit()
        encryptedPreferences.edit().clear().apply()
        backupsFolder.refresh()
        dataInPublicFolder.refresh()
        theme.refresh()
        reload()
        startView.refresh()
    }

    val isLockEnabled: Boolean
        get() = biometricLock.value == BiometricLock.ENABLED

    private fun reload() {
        setOf(
                textSizeNoteEditor,
                textSizeOverview,
                dateFormatOverview,
                timeFormatOverview,
                dateFormatNoteView,
                timeFormatNoteView,
                notesView,
                notesSorting,
                listItemSorting,
                secureFlag,
                labelsHidden,
                labelTagsHiddenInOverview,
                maxLabels,
                periodicBackups,
                backupPassword,
                backupOnSave,
                autoSaveAfterIdleTime,
                autoRemoveDeletedNotesAfterDays,
                editNoteActivityTopActions,
                editNoteActivityBottomAction,
                defaultNoteColor,
                defaultListNoteViewMode,
            )
            .forEach { it.refresh() }
    }

    companion object {
        private const val TAG = "MemoXPreferences"
        const val EMPTY_PATH = "emptyPath"
        const val START_VIEW_DEFAULT = ""
        const val START_VIEW_UNLABELED = "cool.hin.memox.startview.UNLABELED"

        val DEFAULT_EDIT_NOTE_TOP_ACTIONS =
            listOf(EditAction.SEARCH, EditAction.LABELS, EditAction.SHARE)
        val DEFAULT_EDIT_NOTE_BOTTOM_ACTION = EditAction.TOGGLE_VIEW_MODE

        @Volatile private var instance: MemoXPreferences? = null

        fun getInstance(context: Context): MemoXPreferences {
            return instance
                ?: synchronized(this) {
                    val instance = MemoXPreferences(context)
                    Companion.instance = instance
                    return instance
                }
        }
    }
}

val MemoXPreferences.autoSortByCheckedEnabled
    get() = listItemSorting.value.isAutoSortChecked
