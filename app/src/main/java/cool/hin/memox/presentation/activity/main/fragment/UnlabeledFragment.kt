package cool.hin.memox.presentation.activity.main.fragment

import android.os.Bundle
import android.view.View
import androidx.lifecycle.LiveData
import cool.hin.memox.R
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.Item

class UnlabeledFragment : MemoXFragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        model.folder.value = Folder.NOTES
    }

    override fun getBackground() = R.drawable.label_off

    override fun getObservable(): LiveData<List<Item>> {
        return model.getNotesWithoutLabel()
    }
}
