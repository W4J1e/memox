package cool.hin.memox.presentation.activity.main.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cool.hin.memox.R
import cool.hin.memox.data.model.NoteFolder
import cool.hin.memox.databinding.DialogInputBinding
import cool.hin.memox.databinding.FragmentNotesBinding
import cool.hin.memox.presentation.activity.main.fragment.DisplayFolderFragment.Companion.EXTRA_DISPLAYED_FOLDER
import cool.hin.memox.presentation.add
import cool.hin.memox.presentation.displayEditFolderDialog
import cool.hin.memox.presentation.initListView
import cool.hin.memox.presentation.setCancelButton
import cool.hin.memox.presentation.showAndFocus
import cool.hin.memox.presentation.showToast
import cool.hin.memox.presentation.view.main.folder.FolderAdapter
import cool.hin.memox.presentation.view.main.folder.FolderData
import cool.hin.memox.presentation.view.main.folder.FolderListener
import cool.hin.memox.presentation.viewmodel.BaseNoteModel

class FoldersFragment : Fragment(), FolderListener {

    private var folderAdapter: FolderAdapter? = null
    private var binding: FragmentNotesBinding? = null
    private var itemTouchHelper: ItemTouchHelper? = null

    private val model: BaseNoteModel by activityViewModels()

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
        folderAdapter = null
        itemTouchHelper = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        folderAdapter = FolderAdapter(this)

        binding?.MainListView?.apply {
            initListView(requireContext())
            adapter = folderAdapter
            binding?.ImageView?.setImageResource(R.drawable.folder)
            setupItemTouchHelper(this)
        }

        setupObserver()
    }

    private fun setupItemTouchHelper(recyclerView: RecyclerView) {
        val itemTouchHelperCallback =
            object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

                private var currentList = folderAdapter?.currentList
                private var didReorder = false

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    val fromPosition = viewHolder.bindingAdapterPosition
                    val toPosition = target.bindingAdapterPosition
                    currentList = folderAdapter?.onItemMove(fromPosition, toPosition)
                    didReorder = fromPosition != toPosition
                    return didReorder
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun isLongPressDragEnabled(): Boolean = true

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ) {
                    super.clearView(recyclerView, viewHolder)
                    if (!didReorder) return

                    currentList?.let { list ->
                        val size = list.size
                        val updatedFolders =
                            list.mapIndexed { index, folderData ->
                                NoteFolder(folderData.id, size - 1 - index)
                            }
                        model.updateFolders(updatedFolders)
                    }
                    didReorder = false
                }
            }
        itemTouchHelper = ItemTouchHelper(itemTouchHelperCallback)
        itemTouchHelper?.attachToRecyclerView(recyclerView)
    }

    override fun onStartDrag(viewHolder: RecyclerView.ViewHolder) {
        itemTouchHelper?.startDrag(viewHolder)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        setHasOptionsMenu(true)
        binding = FragmentNotesBinding.inflate(inflater)
        return binding?.root
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu.add(R.string.add_folder, R.drawable.add) { displayAddFolderDialog() }
    }

    override fun onClick(position: Int) {
        folderAdapter?.currentList?.get(position)?.let { folderData ->
            val bundle = Bundle()
            bundle.putString(EXTRA_DISPLAYED_FOLDER, folderData.id)
            findNavController().navigate(R.id.FoldersToDisplayFolder, bundle)
        }
    }

    override fun onEdit(position: Int) {
        folderAdapter?.currentList?.get(position)?.let { folderData ->
            displayEditFolderDialog(folderData.id, model)
        }
    }

    override fun onDelete(position: Int) {
        folderAdapter?.currentList?.get(position)?.let { folderData ->
            confirmDeletion(folderData.id)
        }
    }

    override fun onToggleVisibility(position: Int) {
        folderAdapter?.currentList?.get(position)?.let { value ->
            val hiddenFolders = model.preferences.foldersHidden.value.toMutableSet()
            if (value.visibleInNavigation) {
                hiddenFolders.add(value.id)
            } else {
                hiddenFolders.remove(value.id)
            }
            model.savePreference(model.preferences.foldersHidden, hiddenFolders)

            val currentList = folderAdapter!!.currentList.toMutableList()
            currentList[position] =
                currentList[position].copy(visibleInNavigation = !value.visibleInNavigation)
            folderAdapter!!.submitList(currentList)
        }
    }

    private fun setupObserver() {
        model.folders.observe(viewLifecycleOwner) { folders ->
            val hiddenFolders = model.preferences.foldersHidden.value
            val foldersData =
                folders.map { folder ->
                    FolderData(folder.name, !hiddenFolders.contains(folder.name), folder.order)
                }
            folderAdapter?.submitList(foldersData)
            binding?.ImageView?.isVisible = folders.isEmpty()
        }
    }

    private fun displayAddFolderDialog() {
        val inflater = LayoutInflater.from(requireContext())
        val dialogBinding = DialogInputBinding.inflate(inflater)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_folder)
            .setView(dialogBinding.root)
            .setCancelButton()
            .setPositiveButton(R.string.save) { dialog, _ ->
                val value = dialogBinding.EditText.text.toString().trim()
                if (value.isNotEmpty()) {
                    model.insertFolder(value) { success: Boolean ->
                        if (success) {
                            dialog.dismiss()
                        } else {
                            showToast(R.string.folder_exists)
                        }
                    }
                }
            }
            .showAndFocus(dialogBinding.EditText, allowFullSize = true) { positiveButton ->
                dialogBinding.EditText.doAfterTextChanged { text ->
                    positiveButton.isEnabled = !text.isNullOrEmpty()
                }
                positiveButton.isEnabled = false
            }
    }

    private fun confirmDeletion(value: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_folder)
            .setMessage(R.string.your_notes_associated)
            .setPositiveButton(R.string.delete) { _, _ -> model.deleteFolder(value) }
            .setCancelButton()
            .show()
    }
}
