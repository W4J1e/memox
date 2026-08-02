package cool.hin.memox.presentation.activity.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.TransitionManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.view.GravityCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.color.MaterialColors
import com.google.android.material.transition.platform.MaterialFade
import cool.hin.memox.presentation.activity.main.fragment.SearchFragment
import cool.hin.memox.presentation.viewmodel.preference.NotesSort
import cool.hin.memox.presentation.viewmodel.preference.NotesSortBy
import cool.hin.memox.presentation.viewmodel.preference.NotesView
import cool.hin.memox.presentation.viewmodel.preference.SortDirection
import cool.hin.memox.R
import cool.hin.memox.data.model.BaseNote
import cool.hin.memox.data.sync.SyncRouter
import cool.hin.memox.data.sync.SyncState
import cool.hin.memox.data.sync.SyncStatus
import cool.hin.memox.data.sync.onedrive.OneDriveAuthHelper
import cool.hin.memox.databinding.ActivityMainBinding
import cool.hin.memox.presentation.activity.LockedActivity
import cool.hin.memox.presentation.activity.main.fragment.DisplayLabelFragment.Companion.EXTRA_DISPLAYED_LABEL
import cool.hin.memox.presentation.activity.main.fragment.MemoXFragment
import cool.hin.memox.presentation.activity.note.EditNoteActivity
import cool.hin.memox.presentation.activity.note.NoteActionHandler
import cool.hin.memox.presentation.activity.note.handleRejection
import cool.hin.memox.presentation.dp
import cool.hin.memox.presentation.showKeyboard
import cool.hin.memox.presentation.setupProgressDialog
import cool.hin.memox.presentation.viewmodel.BaseNoteModel
import cool.hin.memox.presentation.viewmodel.ExportMimeType
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences.Companion.START_VIEW_DEFAULT
import cool.hin.memox.presentation.viewmodel.progress.MigrationProgress
import cool.hin.memox.utils.LATEST_DATA_SCHEMA
import cool.hin.memox.utils.backup.exportNotes
import cool.hin.memox.utils.runMigrations
import cool.hin.memox.utils.security.showBiometricOrPinPrompt
import cool.hin.memox.utils.UpdateChecker
import kotlinx.coroutines.launch

class MainActivity : LockedActivity<ActivityMainBinding>() {

    private lateinit var navController: NavController
    private lateinit var configuration: AppBarConfiguration
    private var restoringSearchText = false
    private lateinit var exportFileActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var exportNotesActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var identityVerifyActivityResultLauncher: ActivityResultLauncher<Intent>

    private var pendingIdentityVerifiedAction: (() -> Unit)? = null

    private var isStartViewFragment = false

    private val topLevelDestinations = setOf(
        R.id.Notes,
        R.id.Reminders,
        R.id.Archive,
        R.id.Deleted,
        R.id.Labels,
        R.id.Settings,
        R.id.SettingsAbout,
    )

    private val syncHideHandler = Handler(Looper.getMainLooper())
    private val syncHideRunnable = Runnable { hideSyncIsland() }
    private val actionModeCancelCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                baseModel.actionMode.close(true)
            }
        }

    var getCurrentFragmentNotes: (() -> Collection<BaseNote>?)? = null

    override fun onSupportNavigateUp(): Boolean {
        baseModel.keyword = ""
        return NavigationUI.navigateUp(navController, configuration)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthRedirect(intent)
    }

    private fun handleOAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "memox" && data.host == "onedrive-auth") {
            lifecycleScope.launch {
                val error = OneDriveAuthHelper.handleRedirect(this@MainActivity, data)
                if (error != null) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        getString(R.string.onedrive_auth_failed, error),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
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
        setupUpdateCheck()
        setupToolbar()
        setupSyncIsland()

        setupActivityResultLaunchers()

        checkForMigrations(savedInstanceState)

        // Sync with the active provider on app start (pull remote updates)
        SyncRouter.syncNow(this)

        // Handle OneDrive OAuth redirect if the activity was (re)launched by it
        handleOAuthRedirect(intent)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (baseModel.actionMode.enabled.value) {
                        return
                    }
                    // 下拉呼出的搜索框（尚未进入 Search 页面）优先收起
                    if (binding.SearchPill.visibility == View.VISIBLE &&
                        navController.currentDestination?.id != R.id.Search
                    ) {
                        hideSearchBar()
                        return
                    }
                    // Let Navigation handle back first (e.g. Settings sub-page -> Settings root)
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else if (
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
            }

        baseModel.actionMode.enabled.observe(this) { enabled ->
            TransitionManager.beginDelayedTransition(binding.RelativeLayout, transition)
            if (enabled) {
                binding.Toolbar.visibility = View.GONE
                binding.ActionMode.visibility = View.VISIBLE
                syncHideHandler.removeCallbacks(syncHideRunnable)
                binding.SyncIsland.visibility = View.GONE
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

    private fun setupSyncIsland() {
        SyncStatus.state.observe(this) { state ->
            when (state) {
                SyncState.SYNCING -> {
                    syncHideHandler.removeCallbacks(syncHideRunnable)
                    showSyncIsland(syncing = true)
                }
                SyncState.COMPLETED -> {
                    showSyncIsland(syncing = false)
                    syncHideHandler.removeCallbacks(syncHideRunnable)
                    syncHideHandler.postDelayed(syncHideRunnable, 3000)
                }
                SyncState.IDLE -> {
                    syncHideHandler.removeCallbacks(syncHideRunnable)
                    hideSyncIsland()
                }
            }
        }
    }

    private fun showSyncIsland(syncing: Boolean) {
        val island = binding.SyncIsland
        binding.SyncIslandText.text =
            if (syncing) getString(R.string.sync_status_syncing)
            else getString(R.string.sync_status_completed)
        binding.SyncIslandSpinner.visibility = if (syncing) View.VISIBLE else View.GONE
        binding.SyncIslandDone.visibility = if (syncing) View.GONE else View.VISIBLE
        if (island.visibility != View.VISIBLE) {
            // Expand from the centre (horizontal "pop") for a Dynamic-Island-like reveal.
            island.alpha = 0f
            island.scaleX = 0.4f
            island.scaleY = 1f
            island.visibility = View.VISIBLE
            island.animate()
                .alpha(1f)
                .scaleX(1f)
                .setDuration(280)
                .setInterpolator(OvershootInterpolator())
                .start()
        }
    }

    private fun hideSyncIsland() {
        val island = binding.SyncIsland
        if (island.visibility == View.VISIBLE) {
            // Contract back towards the centre and fade out.
            island.animate()
                .alpha(0f)
                .scaleX(0.4f)
                .setDuration(220)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { island.visibility = View.GONE }
                .start()
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
        configuration = AppBarConfiguration(topLevelDestinations, binding.DrawerLayout)
        binding.NavView.setupWithNavController(navController)

        // Explicit drawer toggle. The NavigationUI auto-wiring (setupActionBarWithNavController)
        // was not opening the drawer on this device, so we wire the hamburger explicitly.
        val toggle =
            ActionBarDrawerToggle(
                this,
                binding.DrawerLayout,
                binding.Toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close,
            )
        binding.DrawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navController.addOnDestinationChangedListener { _, destination, bundle ->
            val isTopLevel = destination.id in topLevelDestinations
            if (isTopLevel) {
                toggle.isDrawerIndicatorEnabled = true
                toggle.syncState()
            } else {
                // 非顶层（设置二级页等）：显式显示带 tint 的返回箭头。
                // ActionBarDrawerToggle 自带的箭头在某些设备因未着色而不可见。
                toggle.isDrawerIndicatorEnabled = false
                binding.Toolbar.setNavigationIcon(R.drawable.back)
                binding.Toolbar.navigationIcon?.setTint(
                    MaterialColors.getColor(
                        binding.Toolbar,
                        com.google.android.material.R.attr.colorOnSurface,
                        0,
                    ),
                )
            }

            toggle.setToolbarNavigationClickListener {
                if (destination.id in topLevelDestinations) {
                    binding.DrawerLayout.openDrawer(GravityCompat.START)
                } else {
                    NavigationUI.navigateUp(navController, configuration)
                }
            }
            // 搜索栏显隐：进入 Search 目的显示搜索栏，否则显示常态栏
            if (destination.id == R.id.Search) {
                showSearchBar()
            } else {
                hideSearchBar()
                restoringSearchText = true
                binding.SearchEditText.setText(baseModel.keyword)
                restoringSearchText = false
            }
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
                }
                else -> {
                    binding.TakeNote.hide()
                }
            }
            isStartViewFragment = isStartViewFragment(destination.id, bundle)
        }

        setupUpdateBadges()
    }

    /**
     * 启动静默检查更新。有新版本时抽屉「关于」菜单项显示红点；点击「关于」进入关于页面，
     * 关于页版本号后的红点点击才弹更新弹窗。
     */
    private fun setupUpdateCheck() {
        UpdateChecker.checkForUpdates(this)
    }

    /**
     * 有新版本时，在抽屉「关于」菜单项（actionLayout 红点）显示红点。红点持续显示直到没有新版本。
     */
    private fun setupUpdateBadges() {
        binding.NavView.post {
            val aboutDot =
                binding.NavView.menu.findItem(R.id.SettingsAbout).actionView
                    ?.findViewById<View>(R.id.badge_dot)
            UpdateChecker.state.observe(this) { info ->
                val available = info != null && UpdateChecker.shouldShow(this, info)
                aboutDot?.visibility = if (available) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupToolbar() {
        val searchEditText = binding.SearchEditText
        val viewToggle = binding.ViewToggleButton
        val sortButton = binding.SortButton
        val homeViewToggle = binding.HomeViewToggleButton
        val homeSort = binding.HomeSortButton
        val avatar = binding.AvatarButton

        restoringSearchText = true
        searchEditText.setText(baseModel.keyword)
        restoringSearchText = false
        searchEditText.doAfterTextChanged { text ->
            if (restoringSearchText) return@doAfterTextChanged
            val keyword = text?.toString().orEmpty()
            if (baseModel.keyword != keyword) {
                baseModel.keyword = keyword
            }
            val isSearch = navController.currentDestination?.id == R.id.Search
            if (keyword.isNotEmpty() && !isSearch) {
                navController.navigate(
                    R.id.Search,
                    Bundle().apply {
                        putSerializable(SearchFragment.EXTRA_INITIAL_FOLDER, baseModel.folder.value)
                        putSerializable(SearchFragment.EXTRA_INITIAL_LABEL, baseModel.currentLabel)
                    },
                )
            } else if (keyword.isEmpty() && isSearch) {
                navController.popBackStack()
            }
        }

        fun updateToggleIcon() {
            val isGrid = baseModel.preferences.notesView.value == NotesView.GRID
            val icon = if (isGrid) R.drawable.view_list else R.drawable.view_grid
            viewToggle.setImageResource(icon)
            homeViewToggle.setImageResource(icon)
            viewToggle.contentDescription = getString(if (isGrid) R.string.list else R.string.grid)
            homeViewToggle.contentDescription =
                getString(if (isGrid) R.string.list else R.string.grid)
        }
        updateToggleIcon()
        val onToggleClick = View.OnClickListener {
            val next =
                if (baseModel.preferences.notesView.value == NotesView.GRID) {
                    NotesView.LIST
                } else {
                    NotesView.GRID
                }
            baseModel.preferences.notesView.save(next)
            updateToggleIcon()
        }
        viewToggle.setOnClickListener(onToggleClick)
        homeViewToggle.setOnClickListener(onToggleClick)

        fun updateSortIcon() {
            // 排序按钮固定显示「上下箭头组合」图标（↑↓），点击弹出排序字段菜单
            sortButton.setImageResource(R.drawable.sort_arrows)
            homeSort.setImageResource(R.drawable.sort_arrows)
        }
        val onSortClick = View.OnClickListener { showSortMenu(it) }
        sortButton.setOnClickListener(onSortClick)
        homeSort.setOnClickListener(onSortClick)
        updateSortIcon()

        avatar.setOnClickListener {
            AccountSheetDialog().show(supportFragmentManager, AccountSheetDialog.TAG)
        }

        baseModel.preferences.onedriveSyncEnabled.observe(this) { enabled ->
            updateAvatar(enabled)
        }
    }

    /**
     * Shows the Microsoft account picture once OneDrive is connected, falling back to the tinted
     * default icon. If the picture has not been cached yet it is downloaded in the background.
     */
    private fun updateAvatar(onedriveEnabled: Boolean) {
        val avatar = binding.AvatarButton
        val photo = if (onedriveEnabled) OneDriveAuthHelper.getAvatarFile(this) else null
        if (photo != null) {
            avatar.clearColorFilter()
            avatar.imageTintList = null
            Glide.with(this)
                .load(photo)
                .signature(ObjectKey(photo.lastModified()))
                .circleCrop()
                .placeholder(R.drawable.account_circle)
                .into(avatar)
            return
        }

        Glide.with(this).clear(avatar)
        avatar.setImageResource(R.drawable.account_circle)
        val color =
            if (onedriveEnabled) {
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0)
            } else {
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    0,
                )
            }
        avatar.setColorFilter(color)

        if (onedriveEnabled) {
            lifecycleScope.launch {
                OneDriveAuthHelper.fetchAndStoreAvatar(this@MainActivity)
                if (OneDriveAuthHelper.getAvatarFile(this@MainActivity) != null) {
                    updateAvatar(true)
                }
            }
        }
    }

    internal fun showSearchBar(clearText: Boolean = false) {
        if (binding.SearchPill.visibility != View.VISIBLE) {
            binding.SearchPill.visibility = View.VISIBLE
            binding.HomeBar.visibility = View.GONE
        }
        if (clearText) {
            // 手动下拉呼出搜索框时清空上次残留内容：关掉/返回后再拉出应为空白，而非旧文本
            restoringSearchText = true
            binding.SearchEditText.setText("")
            baseModel.keyword = ""
            restoringSearchText = false
        }
        // 进入/呼出搜索框时让输入框重新获得焦点并弹出键盘，
        // 避免搜索结果页接管后输入框丢失输入连接（键盘还挂着却无法输入，必须再点一次）。
        binding.SearchEditText.post {
            binding.SearchEditText.requestFocus()
            showKeyboard(binding.SearchEditText)
        }
    }

    internal fun hideSearchBar() {
        if (binding.SearchPill.visibility != View.GONE) {
            binding.SearchPill.visibility = View.GONE
            binding.HomeBar.visibility = View.VISIBLE
        }
    }

    fun isSearchBarVisible(): Boolean = binding.SearchPill.visibility == View.VISIBLE

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val current = baseModel.preferences.notesSorting.value
        NotesSortBy.entries.forEach { sortBy ->
            val label = getString(sortBy.textResId)
            val title =
                if (sortBy == current.sortedBy) {
                    "$label  ${if (current.sortDirection == SortDirection.DESC) "▼" else "▲"}"
                } else {
                    label
                }
            val item = popup.menu.add(Menu.NONE, sortBy.ordinal, Menu.NONE, title)
            item.isChecked = (sortBy == current.sortedBy)
        }
        popup.menu.setGroupCheckable(Menu.NONE, true, true)
        popup.setOnMenuItemClickListener { item ->
            val sortBy = NotesSortBy.entries[item.itemId]
            val cur = baseModel.preferences.notesSorting.value
            val next =
                if (sortBy == cur.sortedBy) {
                    cur.copy(
                        sortDirection =
                            if (cur.sortDirection == SortDirection.DESC) {
                                SortDirection.ASC
                            } else {
                                SortDirection.DESC
                            },
                    )
                } else {
                    cur.copy(sortedBy = sortBy)
                }
            baseModel.preferences.notesSorting.save(next)
            true
        }
        popup.show()
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
        // Labels and Settings are now reached from the navigation drawer, so the top bar
        // only hosts the search pill and the account avatar (wired in setupToolbar).
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
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

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FRAGMENT_TO_OPEN = "memox.intent.extra.FRAGMENT_TO_OPEN"
        const val EXTRA_SKIP_START_VIEW_ON_BACK = "memox.intent.extra.SKIP_START_VIEW_ON_BACK"
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
