package cool.hin.memox.presentation.activity.main.fragment

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.LiveData
import cool.hin.memox.R
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.Item

class DisplayFolderFragment : MemoXFragment() {

    private lateinit var folderId: String

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        model.folder.value = Folder.NOTES
    }

    override fun getBackground() = R.drawable.folder

    override fun getObservable(): LiveData<List<Item>> {
        folderId =
            requireNotNull(
                requireArguments().getString(EXTRA_DISPLAYED_FOLDER),
                { "DisplayFolderFragment does not have '$EXTRA_DISPLAYED_FOLDER' arg" },
            )
        return model.getNotesByFolder(folderId)
    }

    override fun prepareNewNoteIntent(intent: Intent): Intent {
        return intent.putExtra(EXTRA_DISPLAYED_FOLDER, folderId)
    }

    companion object {
        const val EXTRA_DISPLAYED_FOLDER = "memox.intent.extra.DISPLAYED_FOLDER"
    }
}
