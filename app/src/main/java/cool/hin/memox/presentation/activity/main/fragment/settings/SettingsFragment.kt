package cool.hin.memox.presentation.activity.main.fragment.settings

import android.content.ContextWrapper
import android.content.Intent
import android.content.Intent.ACTION_OPEN_DOCUMENT_TREE
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity.RESULT_OK
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
import cool.hin.memox.MemoXApplication
import cool.hin.memox.R
import cool.hin.memox.cancelAutoRemoveOldDeletedNotes
import cool.hin.memox.data.imports.Display
import cool.hin.memox.data.imports.FOLDER_OR_FILE_MIMETYPE
import cool.hin.memox.data.imports.ImportSource
import cool.hin.memox.data.imports.txt.APPLICATION_TEXT_MIME_TYPES
import cool.hin.memox.databinding.DialogTextInputBinding
import cool.hin.memox.databinding.FragmentSettingsBinding
import cool.hin.memox.presentation.activity.main.MainActivity
import cool.hin.memox.presentation.format
import cool.hin.memox.presentation.getQuantityStringPlain
import cool.hin.memox.presentation.setCancelButton
import cool.hin.memox.presentation.setEnabledSecureFlag
import cool.hin.memox.presentation.setupImportProgressDialog
import cool.hin.memox.presentation.showAndFocus
import cool.hin.memox.presentation.showDialog
import cool.hin.memox.presentation.showToast
import cool.hin.memox.presentation.view.misc.TextWithIconAdapter
import cool.hin.memox.presentation.viewmodel.BaseNoteModel
import cool.hin.memox.presentation.viewmodel.preference.Constants.PASSWORD_EMPTY
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import cool.hin.memox.scheduleAutoRemoveOldDeletedNotes
import cool.hin.memox.utils.MIME_TYPE_JSON
import cool.hin.memox.utils.MIME_TYPE_ZIP
import cool.hin.memox.utils.backup.exportPreferences
import cool.hin.memox.utils.log
import cool.hin.memox.utils.security.DecryptionException
import cool.hin.memox.utils.security.EncryptionException
import cool.hin.memox.utils.security.showBiometricOrPinPrompt
import cool.hin.memox.utils.UpdateChecker
import cool.hin.memox.utils.showErrorDialog
import cool.hin.memox.utils.viewLogs
import cool.hin.memox.utils.wrapWithChooser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private val model: BaseNoteModel by activityViewModels()

    private lateinit var importBackupActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var importOtherActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var exportBackupActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var setupLockActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var disableLockActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var exportSettingsActivityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var importSettingsActivityResultLauncher: ActivityResultLauncher<Intent>

    private lateinit var selectedImportSource: ImportSource

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = FragmentSettingsBinding.inflate(inflater)
        model.preferences.apply {
            setupAppearance(binding)
            setupBackup(binding)
            setupWebDav(binding)
            setupOneDrive(binding)
            setupSecurity(binding)
            setupSettings(binding)
        }
        setupAbout(binding)
        applyCategory(binding)
        return binding.root
    }

    /**
     * The settings screen is a landing page (category entries) plus several category sub-pages that
     * share the same layout and setup code, toggling section visibility based on the [category]
     * navigation argument. "root" shows the landing; otherwise only the matching section is shown.
     */
    private fun applyCategory(binding: FragmentSettingsBinding) {
        val category = arguments?.getString(EXTRA_SETTINGS_CATEGORY) ?: CATEGORY_ROOT
        when (category) {
            CATEGORY_APPEARANCE -> showSection(binding, binding.SectionAppearance)
            CATEGORY_BACKUP -> showSection(binding, binding.SectionBackup)
            CATEGORY_DATA -> showSection(binding, binding.SectionData)
            CATEGORY_ABOUT -> showSection(binding, binding.SectionAbout)
            else -> {
                // Root: show landing, hide all sections.
                binding.SettingsLanding.visibility = View.VISIBLE
                binding.SectionAppearance.visibility = View.GONE
                binding.SectionBackup.visibility = View.GONE
                binding.SectionData.visibility = View.GONE
                binding.SectionAbout.visibility = View.GONE
                binding.GoToAppearance.setOnClickListener {
                    findNavController().navigate(R.id.SettingsAppearance)
                }
                binding.GoToBackup.setOnClickListener {
                    findNavController().navigate(R.id.SettingsBackup)
                }
                binding.GoToData.setOnClickListener {
                    findNavController().navigate(R.id.SettingsData)
                }
            }
        }
    }

    private fun showSection(binding: FragmentSettingsBinding, section: View) {
        binding.SettingsLanding.visibility = View.GONE
        binding.SectionAppearance.visibility =
            if (section === binding.SectionAppearance) View.VISIBLE else View.GONE
        binding.SectionBackup.visibility =
            if (section === binding.SectionBackup) View.VISIBLE else View.GONE
        binding.SectionData.visibility =
            if (section === binding.SectionData) View.VISIBLE else View.GONE
        binding.SectionAbout.visibility =
            if (section === binding.SectionAbout) View.VISIBLE else View.GONE
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupActivityResultLaunchers()
    }

    private fun setupActivityResultLaunchers() {
        importBackupActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let { importBackup(it) }
                }
            }
        importOtherActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let { uri ->
                        model.importFromOtherApp(uri, selectedImportSource)
                    }
                }
            }
        exportBackupActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let { uri ->
                        model.exportBackup(uri) {
                            // Continue with pending biometric action (enable/disable) after export
                            pendingBiometricContinuation?.invoke()
                            pendingBiometricContinuation = null
                        }
                    }
                } else {
                    // User canceled export picker; do not keep a stale continuation around
                    pendingBiometricContinuation = null
                }
            }
        setupLockActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                showEnableBiometricLock()
            }
        disableLockActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                showDisableBiometricLock()
            }
        exportSettingsActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let { uri ->
                        if (requireContext().exportPreferences(model.preferences, uri)) {
                            showToast(R.string.export_settings_success)
                        } else {
                            showToast(R.string.export_settings_failure)
                        }
                    }
                }
            }
        importSettingsActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let { uri ->
                        model.importPreferences(
                            requireContext(),
                            uri,
                            { showToast(R.string.import_settings_success) },
                        ) {
                            showToast(R.string.import_settings_failure)
                        }
                    }
                }
            }
    }

    private fun importBackup(uri: Uri) {
        when (requireContext().contentResolver.getType(uri)) {
            "text/xml" -> {
                model.importXmlBackup(uri)
            }

            MIME_TYPE_ZIP -> {
                val layout = DialogTextInputBinding.inflate(layoutInflater, null, false)
                val password = model.preferences.backupPassword.value
                layout.InputText.apply {
                    if (password != PASSWORD_EMPTY) {
                        setText(password)
                    }
                    transformationMethod = PasswordTransformationMethod.getInstance()
                }
                layout.InputTextLayout.endIconMode = END_ICON_PASSWORD_TOGGLE
                layout.Message.apply {
                    setText(R.string.import_backup_password_hint)
                    visibility = View.VISIBLE
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.backup_password)
                    .setView(layout.root)
                    .setPositiveButton(R.string.import_backup) { dialog, _ ->
                        dialog.cancel()
                        val usedPassword = layout.InputText.text.toString()
                        model.importZipBackup(uri, usedPassword)
                    }
                    .setCancelButton()
                    .show()
            }
        }
    }

    private fun MemoXPreferences.setupAppearance(binding: FragmentSettingsBinding) {
        notesView.observe(viewLifecycleOwner) { value ->
            binding.View.setup(notesView, value, requireContext()) { newValue ->
                model.savePreference(notesView, newValue)
            }
        }

        theme.merge(useDynamicColors).observe(viewLifecycleOwner) {
            (themeValue, useDynamicColorsValue) ->
            binding.Theme.setup(
                theme,
                themeValue,
                useDynamicColorsValue,
                requireContext(),
                layoutInflater,
            ) { newThemeValue, newUseDynamicColorsValue ->
                model.savePreference(theme, newThemeValue)
                model.savePreference(useDynamicColors, newUseDynamicColorsValue)
                val packageManager = requireContext().packageManager
                val intent = packageManager.getLaunchIntentForPackage(requireContext().packageName)
                val componentName = intent!!.component
                val mainIntent =
                    Intent.makeRestartActivityTask(componentName).apply {
                        putExtra(MainActivity.EXTRA_FRAGMENT_TO_OPEN, R.id.Settings)
                    }
                mainIntent.setPackage(requireContext().packageName)
                requireContext().startActivity(mainIntent)
                requireActivity().finish()
            }
        }

        dateFormat.merge(timeFormat).observe(viewLifecycleOwner) { (date, time) ->
            binding.DateFormat.setupDateTimeFormat(
                R.string.date_format,
                dateFormat,
                timeFormat,
                requireContext(),
                layoutInflater,
            ) { newDate, newTime ->
                model.savePreference(dateFormat, newDate)
                model.savePreference(timeFormat, newTime)
            }
        }

        textSizeNoteEditor.merge(textSizeOverview).observe(viewLifecycleOwner) {
            (editorValue, overviewValue) ->
            binding.TextSize.setupTextSize(
                textSizeNoteEditor,
                textSizeOverview,
                editorValue,
                overviewValue,
                requireContext(),
                layoutInflater,
                onEditorChange = { newValue ->
                    model.savePreference(textSizeNoteEditor, newValue)
                },
                onOverviewChange = { newValue ->
                    model.savePreference(textSizeOverview, newValue)
                },
            )
        }
        notesSorting.observe(viewLifecycleOwner) { notesSort ->
            binding.NotesSortOrder.setup(
                notesSorting,
                notesSort,
                requireContext(),
                layoutInflater,
                model,
            )
        }

        listItemSorting.observe(viewLifecycleOwner) { value ->
            binding.CheckedListItemSorting.setup(listItemSorting, value, requireContext()) {
                newValue ->
                model.savePreference(listItemSorting, newValue)
            }
        }

        autoRemoveDeletedNotesAfterDays.observe(viewLifecycleOwner) { value ->
            binding.AutoEmptyBin.setup(
                autoRemoveDeletedNotesAfterDays,
                requireContext(),
                labelFormatter = { v ->
                    if (v == 0) requireContext().getString(R.string.off)
                    else "$v ${requireContext().getQuantityStringPlain(R.plurals.days, v)}"
                },
            ) { newValue ->
                Log.d("Stepper", "save auto remove")
                model.savePreference(autoRemoveDeletedNotesAfterDays, newValue)
                val workManager = WorkManager.getInstance(requireContext())
                if (newValue > 0) {
                    workManager.scheduleAutoRemoveOldDeletedNotes(
                        requireContext() as ContextWrapper
                    )
                } else {
                    workManager.cancelAutoRemoveOldDeletedNotes()
                }
            }
        }

        binding.MaxLabels.setup(maxLabels, requireContext()) { newValue ->
            model.savePreference(maxLabels, newValue)
        }

        labelTagsHiddenInOverview.observe(viewLifecycleOwner) { value ->
            binding.LabelsHiddenInOverview.setup(
                labelTagsHiddenInOverview,
                value,
                requireContext(),
                layoutInflater,
                R.string.labels_hidden_in_overview,
            ) { enabled ->
                model.savePreference(labelTagsHiddenInOverview, enabled)
            }
        }

        startView.merge(model.labels).observe(viewLifecycleOwner) { (startViewValue, labelsValue) ->
            binding.StartView.setupStartView(
                startView,
                startViewValue,
                labelsValue?.map { it.value },
                requireContext(),
                layoutInflater,
            ) { newValue ->
                model.savePreference(startView, newValue)
            }
        }
    }

    private fun MemoXPreferences.setupBackup(binding: FragmentSettingsBinding) {
        binding.apply {
            ImportBackup.setOnClickListener {
                val intent =
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .apply {
                            type = "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(MIME_TYPE_ZIP, "text/xml"))
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        .wrapWithChooser(requireContext())
                importBackupActivityResultLauncher.launch(intent)
            }
            ImportOther.setOnClickListener { importFromOtherApp() }
            ExportBackup.setOnClickListener {
                val intent =
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .apply {
                            type = MIME_TYPE_ZIP
                            addCategory(Intent.CATEGORY_OPENABLE)
                            putExtra(Intent.EXTRA_TITLE, buildBackupTitle())
                        }
                        .wrapWithChooser(requireContext())
                exportBackupActivityResultLauncher.launch(intent)
            }
        }
        model.importProgress.setupImportProgressDialog(this@SettingsFragment)
    }

    private fun MemoXPreferences.setupWebDav(binding: FragmentSettingsBinding) {
        binding.WebDavSync.Title.setText(R.string.webdav_sync)
        binding.WebDavSync.root.setOnClickListener {
            WebDavSettingsDialog()
                .show(childFragmentManager, WebDavSettingsDialog.TAG)
        }
        webdavSyncEnabled.observe(viewLifecycleOwner) { enabled ->
            binding.WebDavSync.Value.text =
                if (enabled) {
                    val lastSync = webdavLastSyncTime.value
                    if (lastSync > 0) {
                        val date = java.text.SimpleDateFormat.getDateTimeInstance()
                            .format(java.util.Date(lastSync))
                        getString(R.string.webdav_last_sync, date)
                    } else {
                        getString(R.string.webdav_last_sync_never)
                    }
                } else {
                    getString(R.string.webdav_not_configured)
                }
        }
    }

    private fun MemoXPreferences.setupOneDrive(binding: FragmentSettingsBinding) {
        binding.OneDriveSync.Title.setText(R.string.onedrive_sync)
        binding.OneDriveSync.root.setOnClickListener {
            OneDriveSettingsDialog()
                .show(childFragmentManager, OneDriveSettingsDialog.TAG)
        }
        onedriveSyncEnabled.observe(viewLifecycleOwner) { enabled ->
            binding.OneDriveSync.Value.text =
                if (enabled) {
                    val lastSync = onedriveLastSyncTime.value
                    if (lastSync > 0) {
                        val date = java.text.SimpleDateFormat.getDateTimeInstance()
                            .format(java.util.Date(lastSync))
                        getString(R.string.onedrive_last_sync, date)
                    } else {
                        getString(R.string.onedrive_last_sync_never)
                    }
                } else {
                    getString(R.string.onedrive_not_signed_in)
                }
        }
    }

    private fun importFromOtherApp() {
        val notallyItem =
            mutableListOf(
                object : Display {
                    override fun getTextId(): Int {
                        return R.string.notally
                    }

                    override fun getIconId(): Int {
                        return R.drawable.icon_notally
                    }
                }
            )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.choose_other_app)
            .setAdapter(
                TextWithIconAdapter(
                    requireContext(),
                    notallyItem + ImportSource.entries.toMutableList(),
                    { item -> getString(item.getTextId()) },
                    Display::getIconId,
                )
            ) { _, which ->
                if (which == 0) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage(
                            getString(
                                R.string.import_from_notally,
                                getString(R.string.import_backup),
                            )
                        )
                        .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
                        .show()
                    return@setAdapter
                }
                selectedImportSource = ImportSource.entries[which - 1]
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(selectedImportSource.helpTextResId)
                    .setPositiveButton(R.string.import_action) { dialog, _ ->
                        dialog.cancel()
                        when (selectedImportSource.mimeType) {
                            FOLDER_OR_FILE_MIMETYPE ->
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle(selectedImportSource.displayNameResId)
                                    .setItems(
                                        arrayOf(
                                            getString(R.string.folder),
                                            getString(R.string.single_file),
                                        )
                                    ) { _, which ->
                                        when (which) {
                                            0 ->
                                                importOtherActivityResultLauncher.launch(
                                                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                                                        .apply {
                                                            addCategory(Intent.CATEGORY_DEFAULT)
                                                        }
                                                        .wrapWithChooser(requireContext())
                                                )
                                            1 ->
                                                importOtherActivityResultLauncher.launch(
                                                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                                                        .apply {
                                                            type = "text/*"
                                                            addCategory(Intent.CATEGORY_OPENABLE)
                                                            putExtra(
                                                                Intent.EXTRA_MIME_TYPES,
                                                                arrayOf("text/*") +
                                                                    APPLICATION_TEXT_MIME_TYPES,
                                                            )
                                                        }
                                                        .wrapWithChooser(requireContext())
                                                )
                                        }
                                    }
                                    .setCancelButton()
                                    .show()
                            else ->
                                importOtherActivityResultLauncher.launch(
                                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                                        .apply {
                                            type = "application/*"
                                            putExtra(
                                                Intent.EXTRA_MIME_TYPES,
                                                arrayOf(selectedImportSource.mimeType),
                                            )
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                        }
                                        .wrapWithChooser(requireContext())
                                )
                        }
                    }
                    .also {
                        selectedImportSource.documentationUrl?.let<String, Unit> { docUrl ->
                            it.setNegativeButton(R.string.help) { _, _ ->
                                val intent =
                                    Intent(Intent.ACTION_VIEW)
                                        .apply { data = Uri.parse(docUrl) }
                                        .wrapWithChooser(requireContext())
                                startActivity(intent)
                            }
                        }
                    }
                    .setNeutralButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
                    .showAndFocus(allowFullSize = true)
            }
            .setCancelButton()
            .show()
    }

    private fun MemoXPreferences.setupSecurity(binding: FragmentSettingsBinding) {
        backupPassword.observe(viewLifecycleOwner) { value ->
            binding.BackupPassword.setupBackupPassword(
                backupPassword,
                value,
                requireContext(),
                layoutInflater,
            ) { newValue ->
                model.savePreference(backupPassword, newValue)
            }
        }

        secureFlag.observe(viewLifecycleOwner) { value ->
            binding.SecureFlag.setup(secureFlag, value, requireContext(), layoutInflater) { newValue
                ->
                model.savePreference(secureFlag, newValue)
                activity?.setEnabledSecureFlag(newValue)
            }
        }
    }

    private fun MemoXPreferences.setupSettings(binding: FragmentSettingsBinding) {
        binding.apply {
            ImportSettings.setOnClickListener {
                showDialog(
                    R.string.import_settings_message,
                    R.string.import_action,
                    { _, _ ->
                        val intent =
                            Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .apply {
                                    type = MIME_TYPE_JSON
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    putExtra(Intent.EXTRA_TITLE, "memoX_Settings.json")
                                }
                                .wrapWithChooser(requireContext())
                        importSettingsActivityResultLauncher.launch(intent)
                    },
                )
            }
            ExportSettings.setOnClickListener {
                showDialog(
                    R.string.export_settings_message,
                    R.string.export,
                    { _, _ ->
                        val intent =
                            Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .apply {
                                    type = MIME_TYPE_JSON
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    putExtra(Intent.EXTRA_TITLE, "memoX_Settings.json")
                                }
                                .wrapWithChooser(requireContext())
                        exportSettingsActivityResultLauncher.launch(intent)
                    },
                )
            }
            ResetSettings.setOnClickListener {
                showDialog(
                    R.string.reset_settings_message,
                    R.string.reset_settings,
                    { _, _ ->
                        lifecycleScope.launch {
                            model.resetPreferences { _ ->
                                showToast(R.string.reset_settings_success)
                            }
                        }
                    },
                )
            }
            dataInPublicFolder.observe(viewLifecycleOwner) { value ->
                binding.DataInPublicFolder.setup(
                    dataInPublicFolder,
                    value,
                    requireContext(),
                    layoutInflater,
                    R.string.data_in_public_message,
                ) { enabled ->
                    if (enabled) {
                        model.enableDataInPublic()
                    } else {
                        model.disableDataInPublic()
                    }
                }
            }
            AutoSaveAfterIdle.setup(
                autoSaveAfterIdleTime,
                requireContext(),
                labelFormatter = { v ->
                    if (v == -1) requireContext().getString(R.string.off) else "${v}s"
                },
            ) { newValue ->
                model.savePreference(autoSaveAfterIdleTime, newValue)
            }

            ClearData.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.clear_data_message)
                    .setPositiveButton(R.string.delete_all) { _, _ -> model.deleteAll() }
                    .setCancelButton()
                    .show()
            }
        }
    }

    private fun setupAbout(binding: FragmentSettingsBinding) {
        binding.apply {
            AuthorLink.setOnClickListener { openLink("https://hin.cool") }
            SourceCode.setOnClickListener { openLink("https://cnb.cool/hin/memoX") }
            ViewLogs.setOnClickListener { (requireContext() as ContextWrapper).viewLogs() }

            try {
                val pInfo =
                    requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
                val version = pInfo.versionName
                VersionText.text = "v$version"
            } catch (_: PackageManager.NameNotFoundException) {}

            val openUpdate = {
                val info = UpdateChecker.state.value
                if (info != null && UpdateChecker.isNewer(info)) {
                    UpdateChecker.showUpdateDialog(requireActivity(), info)
                }
            }
            VersionText.setOnClickListener { openUpdate() }
            NewBadge.setOnClickListener { openUpdate() }

            UpdateChecker.state.observe(viewLifecycleOwner) { info ->
                val show = info != null && UpdateChecker.shouldShow(requireContext(), info)
                NewBadge.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    // Holds a continuation to run (enable/disable biometric) after user-triggered export completes
    private var pendingBiometricContinuation: (() -> Unit)? = null

    private fun showEnableBiometricLock() {
        showBiometricBackupAdvice {
            showBiometricOrPinPrompt(
                false,
                setupLockActivityResultLauncher,
                R.string.enable_lock_title,
                R.string.enable_lock_description,
                onSuccess = { cipher ->
                    val app = (requireActivity().application as MemoXApplication)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        lifecycleScope.launch {
                            try {
                                model.enableBiometricLock(cipher)
                            } catch (e: EncryptionException) {
                                app.log(TAG, throwable = e)
                                showErrorDialog(
                                    e,
                                    R.string.biometrics_setup_failure,
                                    getString(
                                        R.string.biometrics_setup_failure_encrypt,
                                        getString(R.string.report_bug),
                                    ),
                                )
                                return@launch
                            }
                            showToast(R.string.biometrics_setup_success)
                        }
                    }
                },
            ) {
                showBiometricsNotSetupDialog()
            }
        }
    }

    private fun showDisableBiometricLock() {
        showBiometricBackupAdvice {
            showBiometricOrPinPrompt(
                true,
                disableLockActivityResultLauncher,
                R.string.disable_lock_title,
                R.string.disable_lock_description,
                model.preferences.iv.value!!,
                onSuccess = { cipher ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val app = (requireActivity().application as MemoXApplication)
                        lifecycleScope.launch {
                            try {
                                model.disableBiometricLock(cipher)
                            } catch (e: DecryptionException) {
                                app.log(TAG, throwable = e)
                                showErrorDialog(
                                    e,
                                    R.string.biometrics_setup_failure,
                                    getString(
                                        R.string.biometrics_setup_failure_decrypt,
                                        getString(R.string.report_bug),
                                    ),
                                )
                                return@launch
                            }
                            showToast(R.string.biometrics_disable_success)
                        }
                    }
                },
            ) {}
        }
    }

    private fun showBiometricBackupAdvice(onContinue: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.biometric_backup_advice)
            .setPositiveButton(R.string.export) { _, _ ->
                // After export finishes, continue with biometric action
                pendingBiometricContinuation = onContinue
                val intent =
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .apply {
                            type = MIME_TYPE_ZIP
                            addCategory(Intent.CATEGORY_OPENABLE)
                            putExtra(Intent.EXTRA_TITLE, buildBackupTitle())
                        }
                        .wrapWithChooser(requireContext())
                exportBackupActivityResultLauncher.launch(intent)
            }
            .setNeutralButton(R.string.continue_) { _, _ -> onContinue() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildBackupTitle(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.getDefault())
        val ts = sdf.format(Date())
        return "memoX Backup $ts.zip"
    }

    private fun showBiometricsNotSetupDialog() {
        showDialog(
            R.string.biometrics_not_setup,
            R.string.tap_to_set_up,
            { _, _ ->
                val intent =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        Intent(Settings.ACTION_FINGERPRINT_ENROLL)
                    } else {
                        Intent(Settings.ACTION_SECURITY_SETTINGS)
                    }
                setupLockActivityResultLauncher.launch(intent)
            },
        )
    }

    private fun openLink(link: String) {
        val uri = link.toUri()
        val intent = Intent(Intent.ACTION_VIEW, uri).wrapWithChooser(requireContext())
        startActivity(intent)
    }

    private fun openDocsLink(docPath: String) {
        openLink("https://crustack.github.io/memoX/docs/$docPath")
    }

    companion object {
        private const val TAG = "SettingsFragment"

        const val EXTRA_SETTINGS_CATEGORY = "category"
        const val CATEGORY_ROOT = "root"
        const val CATEGORY_APPEARANCE = "appearance"
        const val CATEGORY_BACKUP = "backup"
        const val CATEGORY_DATA = "data"
        const val CATEGORY_ABOUT = "about"
    }
}
