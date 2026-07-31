package cool.hin.memox.presentation.activity.main.fragment

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import cool.hin.memox.R
import cool.hin.memox.data.model.Folder

class ArchiveFragment : MemoXFragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        model.folder.value = Folder.ARCHIVED
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        // Bulk actions (restore / delete forever) live in the action-mode menu provided by
        // ModelFolderObserver for the ARCHIVED folder, so nothing is added here.
    }

    override fun getBackground() = R.drawable.archive

    override fun getObservable() = model.archivedNotes!!
}
