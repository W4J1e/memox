package com.philkes.notallyx.presentation.activity

import android.app.Activity
import android.database.sqlite.SQLiteBlobTooBigException
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.philkes.notallyx.NotallyXApplication
import com.philkes.notallyx.R
import com.philkes.notallyx.presentation.setupProgressDialog
import com.philkes.notallyx.presentation.viewmodel.BaseNoteModel
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.presentation.viewmodel.preference.Theme
import com.philkes.notallyx.presentation.viewmodel.progress.MigrationProgress
import com.philkes.notallyx.utils.log
import com.philkes.notallyx.utils.secondsBetween
import com.philkes.notallyx.utils.splitOversizedNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

abstract class LockedActivity<T : ViewBinding> : AppCompatActivity() {

    private lateinit var notallyXApplication: NotallyXApplication

    internal lateinit var binding: T
    internal lateinit var preferences: NotallyXPreferences
    val baseModel: BaseNoteModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupGlobalExceptionHandler()
        initViewModel()
        notallyXApplication = (application as NotallyXApplication)
        preferences = NotallyXPreferences.getInstance(notallyXApplication)
        if (preferences.useDynamicColors.value) {
            if (DynamicColors.isDynamicColorAvailable()) {
                DynamicColors.applyToActivitiesIfAvailable(notallyXApplication)
            }
        } else {
            when (preferences.theme.value) {
                Theme.SUPER_DARK -> theme.applyStyle(R.style.AppTheme_SuperDark, true)
                else -> theme.applyStyle(R.style.AppTheme, true)
            }
        }
    }

    open fun initViewModel() {
        baseModel.startObserving()
    }

    private fun setupGlobalExceptionHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (
                throwable is SQLiteBlobTooBigException ||
                    throwable.cause is SQLiteBlobTooBigException
            ) {
                lifecycleScope.launch {
                    EXCEPTION_HANDLER_MUTEX.withLock {
                        val time = System.currentTimeMillis()
                        if (!isExceptionAlreadyBeingHandled(time)) {
                            EXCEPTION_HANDLER_MUTEX_LAST_TIMESTAMP = time
                            val migrationProgress =
                                MutableLiveData<MigrationProgress>().apply {
                                    setupProgressDialog(this@LockedActivity)
                                    postValue(
                                        MigrationProgress(
                                            R.string.migration_splitting_notes,
                                            indeterminate = true,
                                        )
                                    )
                                }
                            log(
                                TAG,
                                msg =
                                    "SQLiteBlobTooBigException occurred, trying to fix broken notes...",
                            )
                            withContext(Dispatchers.IO) { application.splitOversizedNotes() }
                            migrationProgress.postValue(
                                MigrationProgress(R.string.migrating_data, inProgress = false)
                            )
                        }
                    }
                }
            } else {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun isExceptionAlreadyBeingHandled(time: Long): Boolean =
        EXCEPTION_HANDLER_MUTEX_LAST_TIMESTAMP?.let { it.secondsBetween(time) < 20 } ?: false

    companion object {
        private const val TAG = "LockedActivity"
        private val EXCEPTION_HANDLER_MUTEX = Mutex()
        private var EXCEPTION_HANDLER_MUTEX_LAST_TIMESTAMP: Long? = null
    }
}
