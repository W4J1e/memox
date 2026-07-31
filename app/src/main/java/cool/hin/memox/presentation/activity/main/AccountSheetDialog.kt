package cool.hin.memox.presentation.activity.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import cool.hin.memox.R
import cool.hin.memox.presentation.activity.main.fragment.settings.OneDriveSettingsDialog
import cool.hin.memox.presentation.activity.main.fragment.settings.WebDavSettingsDialog
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences

class AccountSheetDialog : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_account, container, false)

        val prefs = MemoXPreferences.getInstance(requireContext())
        val onedriveStatus = view.findViewById<TextView>(R.id.onedriveStatus)
        onedriveStatus.text =
            if (prefs.onedriveSyncEnabled.value) {
                getString(R.string.connected)
            } else {
                getString(R.string.not_connected)
            }

        view.findViewById<View>(R.id.onedriveRow).setOnClickListener {
            dismiss()
            OneDriveSettingsDialog().show(
                requireActivity().supportFragmentManager,
                OneDriveSettingsDialog.TAG,
            )
        }
        view.findViewById<View>(R.id.webdavRow).setOnClickListener {
            dismiss()
            WebDavSettingsDialog().show(
                requireActivity().supportFragmentManager,
                WebDavSettingsDialog.TAG,
            )
        }
        return view
    }

    companion object {
        const val TAG = "AccountSheetDialog"
    }
}
