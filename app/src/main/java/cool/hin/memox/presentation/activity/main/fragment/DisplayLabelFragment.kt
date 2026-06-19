package cool.hin.memox.presentation.activity.main.fragment

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.LiveData
import cool.hin.memox.R
import cool.hin.memox.data.model.Folder
import cool.hin.memox.data.model.Item

class DisplayLabelFragment : MemoXFragment() {

    private lateinit var label: String

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        model.folder.value = Folder.NOTES
    }

    override fun getBackground() = R.drawable.label

    override fun getObservable(): LiveData<List<Item>> {
        label =
            requireNotNull(
                requireArguments().getString(EXTRA_DISPLAYED_LABEL),
                { "DisplayLabelFragment does not have '$EXTRA_DISPLAYED_LABEL' arg" },
            )
        return model.getNotesByLabel(label)
    }

    override fun prepareNewNoteIntent(intent: Intent): Intent {
        return intent.putExtra(EXTRA_DISPLAYED_LABEL, label)
    }

    companion object {
        const val EXTRA_DISPLAYED_LABEL = "memox.intent.extra.DISPLAYED_LABEL"
    }
}
