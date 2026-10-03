package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.databinding.FragmentMessagesBinding

class MessagesFragment : Fragment() {

    private var binding: FragmentMessagesBinding? = null
    private lateinit var adapter: MessagesAdapter
    private var actionMode: ActionMode? = null

    private val listener: (List<CapturedMessage>) -> Unit = { messages ->
        adapter.submitList(messages)
        binding?.messagesEmptyText?.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
        if (adapter.selectedCount() == 0) actionMode?.finish() else updateActionModeTitle()
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, MENU_ITEM_DELETE, Menu.NONE, R.string.action_delete)
                .setIcon(android.R.drawable.ic_menu_delete)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId == MENU_ITEM_DELETE) {
                confirmDeleteSelected()
                return true
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            adapter.clearSelection()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentMessagesBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = MessagesAdapter(onClick = ::handleClick, onLongClick = ::handleLongClick)
        binding?.messagesRecyclerView?.layoutManager = LinearLayoutManager(requireContext())
        binding?.messagesRecyclerView?.adapter = adapter
    }

    private fun handleClick(message: CapturedMessage) {
        if (actionMode == null) return // no detail view to open (yet)
        adapter.toggleSelection(message)
        if (adapter.selectedCount() == 0) actionMode?.finish() else updateActionModeTitle()
    }

    private fun handleLongClick(message: CapturedMessage): Boolean {
        if (actionMode == null) {
            actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(actionModeCallback)
        }
        adapter.toggleSelection(message)
        if (adapter.selectedCount() == 0) actionMode?.finish() else updateActionModeTitle()
        return true
    }

    private fun updateActionModeTitle() {
        actionMode?.title = getString(R.string.messages_selected_count, adapter.selectedCount())
    }

    private fun confirmDeleteSelected() {
        val toDelete = adapter.selectedMessages()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_messages_confirm_title)
            .setMessage(R.string.delete_messages_confirm_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                toDelete.forEach { MessageStore.shared.remove(it) }
                actionMode?.finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        MessageStore.shared.addListener(listener)
    }

    override fun onStop() {
        super.onStop()
        MessageStore.shared.removeListener(listener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        actionMode?.finish()
        binding = null
    }

    private companion object {
        const val MENU_ITEM_DELETE = 1
    }
}
