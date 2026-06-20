package cool.hin.memox.presentation.activity.main.fragment.settings

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cool.hin.memox.R
import cool.hin.memox.data.sync.SyncResult
import cool.hin.memox.data.sync.webdav.WebDavSyncService
import cool.hin.memox.data.sync.webdav.WebDavSyncWorker
import cool.hin.memox.databinding.DialogWebdavSettingsBinding
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

class WebDavSettingsDialog : DialogFragment() {

    private var _binding: DialogWebdavSettingsBinding? = null
    private val binding get() = _binding!!

    private val preferences: MemoXPreferences by lazy {
        MemoXPreferences.getInstance(requireContext())
    }

    private val syncService: WebDavSyncService by lazy {
        WebDavSyncService(requireActivity())
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogWebdavSettingsBinding.inflate(LayoutInflater.from(context))

        loadPreferences()
        setupClickListeners()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.webdav_sync)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ -> savePreferences() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadPreferences() {
        binding.webdavUrlInput.setText(preferences.webdavUrl.value)
        binding.webdavUsernameInput.setText(preferences.webdavUsername.value)
        binding.webdavPasswordInput.setText(preferences.webdavPassword.value)
        binding.webdavSyncEnabledSwitch.isChecked = preferences.webdavSyncEnabled.value
        binding.webdavAutoSyncSwitch.isChecked = preferences.webdavAutoSync.value
        updateLastSyncText()
        updateSyncOptionsVisibility()
    }

    private fun savePreferences() {
        preferences.webdavUrl.save(binding.webdavUrlInput.text.toString().trim())
        preferences.webdavUsername.save(binding.webdavUsernameInput.text.toString().trim())
        preferences.webdavPassword.save(binding.webdavPasswordInput.text.toString())
        preferences.webdavSyncEnabled.save(binding.webdavSyncEnabledSwitch.isChecked)
        preferences.webdavAutoSync.save(binding.webdavAutoSyncSwitch.isChecked)
        // Schedule or cancel auto sync based on preferences
        WebDavSyncWorker.schedule(requireActivity())
    }

    private fun setupClickListeners() {
        binding.webdavSyncEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSyncOptionsVisibility()
        }

        binding.webdavTestConnectionButton.setOnClickListener {
            testConnection()
        }

        binding.webdavUploadButton.setOnClickListener {
            performUpload()
        }

        binding.webdavDownloadButton.setOnClickListener {
            confirmAndDownload()
        }

        binding.webdavSyncNowButton.setOnClickListener {
            performSync()
        }
    }

    private fun updateSyncOptionsVisibility() {
        val enabled = binding.webdavSyncEnabledSwitch.isChecked
        binding.webdavSyncOptions.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun updateLastSyncText() {
        val lastSync = preferences.webdavLastSyncTime.value
        if (lastSync > 0) {
            val date = SimpleDateFormat.getDateTimeInstance()
                .format(Date(lastSync))
            binding.webdavLastSyncText.text = getString(R.string.webdav_last_sync, date)
        } else {
            binding.webdavLastSyncText.text = getString(R.string.webdav_last_sync_never)
        }
    }

    private fun testConnection() {
        savePreferences()
        setStatus(R.string.webdav_connecting)
        lifecycleScope.launch {
            when (val result = syncService.testConnection()) {
                is SyncResult.Success -> {
                    setStatus(null)
                    Toast.makeText(requireContext(), R.string.webdav_success, Toast.LENGTH_SHORT)
                        .show()
                }
                is SyncResult.Error -> {
                    setStatus(null)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.webdav_error, result.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                else -> {}
            }
        }
    }

    private fun performUpload() {
        savePreferences()
        setStatus(R.string.webdav_uploading)
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
                        getString(R.string.webdav_error, result.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                else -> {}
            }
        }
    }

    private fun confirmAndDownload() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.webdav_download)
            .setMessage(R.string.webdav_import_confirm)
            .setPositiveButton(R.string.webdav_download) { _, _ -> performDownload() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performDownload() {
        savePreferences()
        setStatus(R.string.webdav_downloading)
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
                        getString(R.string.webdav_error, result.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                else -> {}
            }
        }
    }

    private fun performSync() {
        savePreferences()
        setStatus(R.string.webdav_syncing)
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
                        getString(R.string.webdav_error, result.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                else -> {}
            }
        }
    }

    private fun setStatus(statusResId: Int?) {
        if (statusResId != null) {
            binding.webdavStatusText.text = getString(statusResId)
            binding.webdavStatusText.visibility = View.VISIBLE
        } else {
            binding.webdavStatusText.visibility = View.GONE
        }
    }

    companion object {
        const val TAG = "WebDavSettingsDialog"
    }
}
