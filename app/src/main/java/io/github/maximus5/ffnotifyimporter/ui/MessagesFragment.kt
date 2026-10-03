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
import io.github.maximus5.ffnotifyimporter.rules.DraftIssue
import io.github.maximus5.ffnotifyimporter.rules.NoMatchReason
import io.github.maximus5.ffnotifyimporter.rules.ParseOutcome
import io.github.maximus5.ffnotifyimporter.rules.RuleEngine
import io.github.maximus5.ffnotifyimporter.rules.RulesDb

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
        if (actionMode != null) {
            adapter.toggleSelection(message)
            if (adapter.selectedCount() == 0) actionMode?.finish() else updateActionModeTitle()
        } else {
            showTestMatch(message)
        }
    }

    private fun showTestMatch(message: CapturedMessage) {
        val rules = RulesDb.getInstance(requireContext()).loadAllOrdered()
        val outcome = RuleEngine(rules).match(message)

        val body = when (outcome) {
            is ParseOutcome.NoMatch -> getString(R.string.test_match_no_match, getString(reasonStringRes(outcome.reason)))
            is ParseOutcome.Matched -> buildString {
                val draft = outcome.draft
                appendLine(getString(R.string.test_match_rule_label, outcome.rule.label))
                draft.sourceName?.let { appendLine(getString(R.string.test_match_source, it)) }
                draft.destinationName?.let { appendLine(getString(R.string.test_match_destination, it)) }
                if (draft.amount != null) {
                    appendLine(getString(R.string.test_match_amount, draft.amount.toPlainString(), draft.currencyCode))
                } else {
                    appendLine(getString(R.string.test_match_amount_missing))
                }
                if (draft.foreignAmount != null) {
                    appendLine(
                        getString(
                            R.string.test_match_foreign_amount,
                            draft.foreignAmount.toPlainString(),
                            draft.foreignCurrencyCode.orEmpty(),
                        ),
                    )
                }
                appendLine(getString(R.string.test_match_description, draft.description))
                appendLine(getString(R.string.test_match_notes, draft.notes))
                draft.category?.let { appendLine(getString(R.string.test_match_category, it)) }
                if (draft.tags.isNotEmpty()) {
                    appendLine(getString(R.string.test_match_tags, draft.tags.joinToString(", ")))
                }
                draft.issues.forEach { issue ->
                    appendLine(
                        when (issue) {
                            DraftIssue.LOCAL_AMOUNT_MISSING -> getString(R.string.test_match_issue_local_amount_missing)
                        },
                    )
                }
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.test_match_title)
            .setMessage(body.trim())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun reasonStringRes(reason: NoMatchReason) = when (reason) {
        NoMatchReason.NO_RULES_FOR_APP -> R.string.no_match_reason_no_rules_for_app
        NoMatchReason.NO_PATTERN_MATCHED -> R.string.no_match_reason_no_pattern_matched
        NoMatchReason.AMOUNT_UNPARSABLE -> R.string.no_match_reason_amount_unparsable
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
