package cool.hin.memox.presentation.activity.main.fragment.settings

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cool.hin.memox.R
import cool.hin.memox.data.sync.SyncResult
import cool.hin.memox.data.sync.onedrive.OneDriveAuthHelper
import cool.hin.memox.data.sync.onedrive.OneDriveSyncService
import cool.hin.memox.data.sync.onedrive.OneDriveSyncWorker
import cool.hin.memox.databinding.DialogOnedriveSettingsBinding
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

class OneDriveSettingsDialog : DialogFragment() {

    private var _binding: DialogOnedriveSettingsBinding? = null
    private val binding get() = _binding!!

    private val preferences: MemoXPreferences by lazy {
        MemoXPreferences.getInstance(requireContext())
    }

    private val syncService: OneDriveSyncService by lazy {
        OneDriveSyncService(requireActivity())
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogOnedriveSettingsBinding.inflate(LayoutInflater.from(context))

        loadPreferences()
        setupClickListeners()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.onedrive_sync)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ -> savePreferences() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        // The OAuth flow opens the browser and redirects back here; refresh account state on return.
        updateAccountStatus()
    }

    private fun loadPreferences() {
        binding.onedriveSyncEnabledSwitch.isChecked = preferences.onedriveSyncEnabled.value
        binding.onedriveAutoSyncSwitch.isChecked = preferences.onedriveAutoSync.value
        updateLastSyncText()
        updateSyncOptionsVisibility()
        updateAccountStatus()
    }

    private fun savePreferences() {
        val enabling = binding.onedriveSyncEnabledSwitch.isChecked
        // Mutual exclusion: enabling OneDrive disables WebDAV (and vice-versa).
        if (enabling && preferences.webdavSyncEnabled.value) {
            preferences.webdavSyncEnabled.save(false)
            preferences.webdavAutoSync.save(false)
        }
        preferences.onedriveSyncEnabled.save(enabling)
        preferences.onedriveAutoSync.save(binding.onedriveAutoSyncSwitch.isChecked)
        preferences.syncProvider.save(
            if (enabling) MemoXPreferences.PROVIDER_ONEDRIVE
            else if (preferences.webdavSyncEnabled.value) MemoXPreferences.PROVIDER_WEBDAV
            else MemoXPreferences.PROVIDER_NONE
        )
        OneDriveSyncWorker.schedule(requireActivity())
    }

    private fun setupClickListeners() {
        binding.onedriveSyncEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSyncOptionsVisibility()
        }

        binding.onedriveSignInButton.setOnClickListener {
            val url = OneDriveAuthHelper.buildAuthUrl(requireContext())
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        binding.onedriveSignOutButton.setOnClickListener {
            OneDriveAuthHelper.signOut(requireContext())
            preferences.onedriveSyncEnabled.save(false)
            preferences.onedriveAutoSync.save(false)
            if (preferences.syncProvider.value == MemoXPreferences.PROVIDER_ONEDRIVE) {
                preferences.syncProvider.save(MemoXPreferences.PROVIDER_NONE)
            }
            OneDriveSyncWorker.cancel(requireActivity())
            updateAccountStatus()
            updateSyncOptionsVisibility()
        }

        binding.onedriveTestConnectionButton.setOnClickListener { testConnection() }
        binding.onedriveUploadButton.setOnClickListener { performUpload() }
        binding.onedriveDownloadButton.setOnClickListener { confirmAndDownload() }
        binding.onedriveSyncNowButton.setOnClickListener { performSync() }
    }

    private fun updateSyncOptionsVisibility() {
        val enabled = binding.onedriveSyncEnabledSwitch.isChecked
        binding.onedriveSyncOptions.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun updateAccountStatus() {
        val account = preferences.onedriveAccount.value
        if (OneDriveAuthHelper.isLoggedIn(requireContext()) && account.isNotEmpty()) {
            binding.onedriveAccountText.text = getString(R.string.onedrive_signed_in_as, account)
            binding.onedriveSignInButton.visibility = View.GONE
            binding.onedriveSignOutButton.visibility = View.VISIBLE
        } else if (OneDriveAuthHelper.isLoggedIn(requireContext())) {
            binding.onedriveAccountText.text = getString(R.string.onedrive_signed_in_as, "Microsoft account")
            binding.onedriveSignInButton.visibility = View.GONE
            binding.onedriveSignOutButton.visibility = View.VISIBLE
        } else {
            binding.onedriveAccountText.text = getString(R.string.onedrive_not_signed_in)
            binding.onedriveSignInButton.visibility = View.VISIBLE
            binding.onedriveSignOutButton.visibility = View.GONE
        }
    }

    private fun updateLastSyncText() {
        val lastSync = preferences.onedriveLastSyncTime.value
        if (lastSync > 0) {
            val date = SimpleDateFormat.getDateTimeInstance().format(Date(lastSync))
            binding.onedriveLastSyncText.text = getString(R.string.onedrive_last_sync, date)
        } else {
            binding.onedriveLastSyncText.text = getString(R.string.onedrive_last_sync_never)
        }
    }

    private fun testConnection() {
        if (!OneDriveAuthHelper.isLoggedIn(requireContext())) {
            Toast.makeText(requireContext(), R.string.onedrive_not_signed_in, Toast.LENGTH_SHORT).show()
            return
        }
        setStatus(R.string.onedrive_connecting)
        lifecycleScope.launch {
            when (val result = syncService.testConnection()) {
                is SyncResult.Success -> {
                    setStatus(null)
                    Toast.makeText(requireContext(), R.string.onedrive_success, Toast.LENGTH_SHORT).show()
                }
                is SyncResult.Error -> {
                    setStatus(null)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.onedrive_error, result.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun performUpload() {
        setStatus(R.string.onedrive_uploading)
        lifecycleScope.launch {
            when (val result = syncService.upload()) {
                is SyncResult.Success -> {
                    setStatus(null)
                    updateLastSyncText()
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
                is SyncResult.Error -> {
                    setStatus(null)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.onedrive_error, result.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun confirmAndDownload() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.onedrive_download)
            .setMessage(R.string.onedrive_import_confirm)
            .setPositiveButton(R.string.onedrive_download) { _, _ -> performDownload() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performDownload() {
        setStatus(R.string.onedrive_downloading)
        lifecycleScope.launch {
            when (val result = syncService.download()) {
                is SyncResult.Success -> {
                    setStatus(null)
                    updateLastSyncText()
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
                is SyncResult.Error -> {
                    setStatus(null)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.onedrive_error, result.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun performSync() {
        setStatus(R.string.onedrive_syncing)
        lifecycleScope.launch {
            when (val result = syncService.sync()) {
                is SyncResult.Success -> {
                    setStatus(null)
                    updateLastSyncText()
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
                is SyncResult.Error -> {
                    setStatus(null)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.onedrive_error, result.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun setStatus(statusResId: Int?) {
        if (statusResId != null) {
            binding.onedriveStatusText.text = getString(statusResId)
            binding.onedriveStatusText.visibility = View.VISIBLE
        } else {
            binding.onedriveStatusText.visibility = View.GONE
        }
    }

    companion object {
        const val TAG = "OneDriveSettingsDialog"
    }
}
