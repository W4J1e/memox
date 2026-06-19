package cool.hin.memox.presentation.activity.main.fragment

import android.os.Bundle
import android.view.View
import cool.hin.memox.R
import cool.hin.memox.data.model.Folder

class NotesFragment : MemoXFragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        model.folder.value = Folder.NOTES
    }

    override fun getObservable() = model.baseNotes!!

    override fun getBackground() = R.drawable.notebook
}
