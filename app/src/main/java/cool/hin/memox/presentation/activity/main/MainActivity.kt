package cool.hin.memox.presentation.activity.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.transition.TransitionManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.transition.platform.MaterialFade
import cool.hin.memox.R
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.databinding.ActivityMainBinding
import cool.hin.memox.presentation.activity.LockedActivity
import cool.hin.memox.presentation.activity.main.fragment.DisplayLabelFragment.Companion.EXTRA_DISPLAYED_LABEL
import cool.hin.memox.presentation.activity.main.fragment.MemoXFragment
import cool.hin.memox.presentation.activity.note.EditListActivity
import cool.hin.memox.presentation.activity.note.EditNoteActivity
import cool.hin.memox.presentation.activity.note.NoteActionHandler
import cool.hin.memox.presentation.activity.note.handleRejection
import cool.hin.memox.presentation.dp
import cool.hin.memox.presentation.setupProgressDialog
import cool.hin.memox.presentation.viewmodel.BaseNoteModel
import cool.hin.memox.presentation.viewmodel.ExportMimeType
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences.Companion.START_VIEW_DEFAULT
import cool.hin.memox.presentation.viewmodel.progress.MigrationProgress
import cool.hin.memox.utils.LATEST_DATA_SCHEMA
import cool.hin.memox.utils.backup.exportNotes
import cool.hin.memox.utils.runMigrations
import cool.hin.memox.utils.security.showBiometricOrPinPrompt
import kotlinx.coroutines.launch

class MainActivity : LockedActivity<ActivityMainBinding>() {

    private lateinit var navController: NavController
    private lateinit var configuration: AppBarConfiguration
    private lateinit var exportFileActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var exportNotesActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var identityVerifyActivityResultLauncher: ActivityResultLauncher<Intent>

    private var pendingIdentityVerifiedAction: (() -> Unit)? = null

    private var isStartViewFragment = false
    private val actionModeCancelCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                baseModel.actionMode.close(true)
            }
        }

    var getCurrentFragmentNotes: (() -> Collection<BaseNote>?)? = null

    override fun onSupportNavigateUp(): Boolean {
        baseModel.keyword = ""
        return navController.navigateUp(configuration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.Toolbar)
        configureEdgeToEdgeInsets()

        setupFAB()
        setupActionMode()
        setupNavigation()

        setupActivityResultLaunchers()

        checkForMigrations(savedInstanceState)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (baseModel.actionMode.enabled.value) {
                        return
                    }
                    if (
                        !isStartViewFragment &&
                            !intent.getBooleanExtra(EXTRA_SKIP_START_VIEW_ON_BACK, false)
                    ) {
                        navigateToStartView()
                    } else {
                        finish()
                    }
                }
            },
        )
        onBackPressedDispatcher.addCallback(this, actionModeCancelCallback)

        baseModel.progress.setupProgressDialog(this)
    }

    override fun initViewModel() {}

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            NoteActionHandler.REQUEST_NOTIFICATION_PERMISSION_PIN_TO_STATUS -> {
                if (
                    grantResults.isNotEmpty() &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    val baseNotes = baseModel.actionMode.selectedNotes.values
                    baseModel.pinBaseNotesToStatusBar(
                        this@MainActivity,
                        baseNotes.any { !it.isPinnedToStatus },
                    )
                } else handleRejection(R.string.to_pin_note_status_bar)
            }
        }
    }

    private fun checkForMigrations(savedInstanceState: Bundle?) {
        val proceed: () -> Unit = {
            baseModel.startObserving()
            val fragmentIdToLoad = intent.getIntExtra(EXTRA_FRAGMENT_TO_OPEN, -1)
            if (fragmentIdToLoad != -1) {
                navController.navigate(fragmentIdToLoad, intent.extras)
            } else if (savedInstanceState == null) {
                navigateToStartView()
            }
        }
        if (preferences.dataSchemaId.value < LATEST_DATA_SCHEMA) {
            val migrationProgress = MutableLiveData<MigrationProgress>()
            migrationProgress.setupProgressDialog(this)
            lifecycleScope.launch {
                migrationProgress.postValue(
                    MigrationProgress(R.string.migrating_data, indeterminate = true)
                )
                application.runMigrations { titleId ->
                    migrationProgress.postValue(MigrationProgress(titleId, indeterminate = true))
                }
                migrationProgress.postValue(
                    MigrationProgress(R.string.migrating_data, inProgress = false)
                )
                proceed()
            }
        } else {
            proceed()
        }
    }

    private fun configureEdgeToEdgeInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val navHostFragment = binding.NavHostFragment
        ViewCompat.setOnApplyWindowInsetsListener(binding.RelativeLayout) { view, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())

            binding.Toolbar.apply {
                (layoutParams as ViewGroup.MarginLayoutParams).topMargin = systemBarsInsets.top
                requestLayout()
            }

            binding.ActionMode.apply {
                (layoutParams as ViewGroup.MarginLayoutParams).topMargin = systemBarsInsets.top
                requestLayout()
            }

            binding.TakeNote.apply {
                val marginLayoutParams = layoutParams as ViewGroup.MarginLayoutParams
                marginLayoutParams.bottomMargin = 16.dp + systemBarsInsets.bottom + imeInsets.bottom
                marginLayoutParams.marginEnd = 16.dp
                requestLayout()
            }

            navHostFragment.apply {
                setPadding(
                    paddingLeft,
                    paddingTop,
                    paddingRight,
                    systemBarsInsets.bottom + imeInsets.bottom,
                )
            }
            insets
        }
    }

    private fun getStartViewNavigation(): Pair<Int, Bundle> {
        return when (val startView = preferences.startView.value) {
            START_VIEW_DEFAULT -> Pair(R.id.Notes, Bundle())
            else -> {
                val bundle = Bundle().apply { putString(EXTRA_DISPLAYED_LABEL, startView) }
                Pair(R.id.DisplayLabel, bundle)
            }
        }
    }

    private fun navigateToStartView() {
        val (id, bundle) = getStartViewNavigation()
        navController.navigate(id, bundle)
    }

    private fun setupFAB() {
        binding.TakeNote.setOnClickListener {
            val intent = Intent(this, EditNoteActivity::class.java)
            startActivity(prepareNewNoteIntent(intent))
        }
        binding.MakeList.setOnClickListener {
            val intent = Intent(this, EditListActivity::class.java)
            startActivity(prepareNewNoteIntent(intent))
        }
    }

    private fun prepareNewNoteIntent(intent: Intent): Intent {
        return supportFragmentManager
            .findFragmentById(R.id.NavHostFragment)
            ?.childFragmentManager
            ?.fragments
            ?.firstOrNull()
            ?.let { fragment ->
                return if (fragment is MemoXFragment) {
                    fragment.prepareNewNoteIntent(intent)
                } else intent
            } ?: intent
    }

    private fun setupActionMode() {
        binding.ActionMode.setNavigationOnClickListener { baseModel.actionMode.close(true) }

        val transition =
            MaterialFade().apply {
                secondaryAnimatorProvider = null
                excludeTarget(binding.NavHostFragment, true)
                excludeChildren(binding.NavHostFragment, true)
                excludeTarget(binding.TakeNote, true)
                excludeTarget(binding.MakeList, true)
            }

        baseModel.actionMode.enabled.observe(this) { enabled ->
            TransitionManager.beginDelayedTransition(binding.RelativeLayout, transition)
            if (enabled) {
                binding.Toolbar.visibility = View.GONE
                binding.ActionMode.visibility = View.VISIBLE
            } else {
                binding.Toolbar.visibility = View.VISIBLE
                binding.ActionMode.visibility = View.GONE
            }
            actionModeCancelCallback.isEnabled = enabled
        }

        val menu = binding.ActionMode.menu
        baseModel.folder.observe(this@MainActivity, ModelFolderObserver(this, menu, baseModel))
        baseModel.actionMode.loading.observe(this@MainActivity) { loading ->
            menu.setGroupEnabled(Menu.NONE, !loading)
        }
    }

    internal fun exportSelectedNotes(mimeType: ExportMimeType) {
        exportNotes(
            baseModel.actionMode.selectedNotes.values,
            mimeType,
            exportFileActivityResultLauncher,
            exportNotesActivityResultLauncher,
        )
    }

    private fun setupNavigation() {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.NavHostFragment) as NavHostFragment
        navController = navHostFragment.navController
        configuration = AppBarConfiguration(setOf(R.id.Notes))
        setupActionBarWithNavController(navController, configuration)

        navController.addOnDestinationChangedListener { _, destination, bundle ->
            when (destination.id) {
                R.id.DisplayLabel ->
                    bundle?.getString(EXTRA_DISPLAYED_LABEL)?.let {
                        baseModel.currentLabel = it
                    }
                else -> {
                    baseModel.currentLabel = BaseNoteModel.CURRENT_LABEL_EMPTY
                }
            }
            when (destination.id) {
                R.id.Notes,
                R.id.DisplayLabel -> {
                    binding.TakeNote.show()
                    binding.MakeList.show()
                }
                else -> {
                    binding.TakeNote.hide()
                    binding.MakeList.hide()
                }
            }
            isStartViewFragment = isStartViewFragment(destination.id, bundle)
        }
    }

    private fun isStartViewFragment(id: Int, bundle: Bundle?): Boolean {
        val (startViewId, startViewBundle) = getStartViewNavigation()
        return startViewId == id &&
            startViewBundle.getString(EXTRA_DISPLAYED_LABEL) ==
                bundle?.getString(EXTRA_DISPLAYED_LABEL)
    }

    internal fun navigateWithAnimation(id: Int) {
        val options = navOptions {
            launchSingleTop = true
            anim {
                exit = androidx.navigation.ui.R.anim.nav_default_exit_anim
                enter = androidx.navigation.ui.R.anim.nav_default_enter_anim
                popExit = androidx.navigation.ui.R.anim.nav_default_pop_exit_anim
                popEnter = androidx.navigation.ui.R.anim.nav_default_pop_enter_anim
            }
            popUpTo(navController.graph.startDestination) { inclusive = false }
        }
        navController.navigate(id, null, options)
    }

    private fun setupActivityResultLaunchers() {
        exportFileActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let { uri ->
                        baseModel.exportSelectedNoteToFile(uri, binding.root)
                    }
                }
            }
        exportNotesActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let { uri ->
                        baseModel.exportSelectedNotesToFolder(uri, binding.root)
                    }
                }
            }
        identityVerifyActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    pendingIdentityVerifiedAction?.invoke()
                }
                pendingIdentityVerifiedAction = null
            }
    }

    fun verifyIdentityThen(action: () -> Unit) {
        showBiometricOrPinPrompt(
            isForDecrypt = false,
            cipherIv = null,
            activityResultLauncher = identityVerifyActivityResultLauncher,
            titleResId = R.string.unlock,
            descriptionResId = R.string.note_locked,
            onSuccess = { _ -> action() },
        ) { errorCode ->
            if (
                errorCode ==
                    android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS ||
                    errorCode ==
                        android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT
            ) {
                action()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val currentDestinationId = navController.currentDestination?.id
        if (!ACTIVITES_WITHOUT_TOOLBAR_ICONS.contains(currentDestinationId)) {
            menu.add(Menu.NONE, ACTION_SEARCH, Menu.NONE, R.string.search)
                .setIcon(R.drawable.search)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, ACTION_LABELS, Menu.NONE, R.string.labels)
                .setIcon(R.drawable.label_more)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, ACTION_SETTINGS, Menu.NONE, R.string.settings)
                .setIcon(R.drawable.settings)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            ACTION_SEARCH -> {
                // Toggle search bar visibility in the current fragment
                val fragment = supportFragmentManager.findFragmentById(R.id.NavHostFragment)
                    ?.childFragmentManager?.fragments?.firstOrNull()
                if (fragment is MemoXFragment) {
                    fragment.toggleSearchBar()
                }
                true
            }
            ACTION_LABELS -> {
                navController.navigate(R.id.Labels)
                true
            }
            ACTION_SETTINGS -> {
                navController.navigate(R.id.Settings)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        const val EXTRA_FRAGMENT_TO_OPEN = "memox.intent.extra.FRAGMENT_TO_OPEN"
        const val EXTRA_SKIP_START_VIEW_ON_BACK = "memox.intent.extra.SKIP_START_VIEW_ON_BACK"
        private const val ACTION_SEARCH = 1000
        private const val ACTION_LABELS = 1001
        private const val ACTION_SETTINGS = 1002
        val ACTIVITES_WITHOUT_TOOLBAR_ICONS =
            setOf(
                R.id.Settings,
                R.id.SettingsAppearance,
                R.id.SettingsBackup,
                R.id.SettingsData,
                R.id.SettingsAbout,
                R.id.Labels,
                R.id.Search,
            )
    }
}
