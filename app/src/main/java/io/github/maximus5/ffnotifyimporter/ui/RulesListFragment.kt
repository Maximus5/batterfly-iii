package io.github.maximus5.ffnotifyimporter.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.backup.rulesFromJson
import io.github.maximus5.ffnotifyimporter.backup.rulesToJson
import io.github.maximus5.ffnotifyimporter.databinding.FragmentRulesListBinding
import io.github.maximus5.ffnotifyimporter.rules.ParseRule
import io.github.maximus5.ffnotifyimporter.rules.RulesDb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Long-press a row to multi-select (same gesture `MessagesFragment` already uses) - the
 * toolbar swaps to "N selected" plus move up/move down/delete (`ifRoom`) and enable/disable
 * (`never`, text-only in the overflow) rather than the normal add/export/import menu, hosted by
 * `SettingsActivity` (see its own `updateSelectionCount` KDoc for why that needed only one small
 * new method there - this screen's existing `setBackGuard`/up-arrow plumbing already covers
 * "cancel selection" for free). The per-row overflow menu (move up/down/duplicate/delete, one at
 * a time) is unchanged and still exists side by side - multi-select is an addition for acting on
 * several rules at once, not a replacement.
 *
 * The user's own request, after noticing hand-authoring a regex rule is hard enough without also
 * having to reorder/toggle several at a time one by one. */
class RulesListFragment : Fragment() {

    private var binding: FragmentRulesListBinding? = null
    private lateinit var adapter: RulesAdapter
    private lateinit var rulesDb: RulesDb
    private var inSelectionMode = false

    // Registered in onCreate (before onCreateView), the way these contracts require - a Fragment
    // can't register one after its view has been created.
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportTo(uri)
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentRulesListBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        rulesDb = RulesDb.getInstance(requireContext())

        adapter = RulesAdapter(onClick = ::handleClick, onLongClick = ::handleLongClick, onOverflowClick = ::showOverflowMenu)
        binding.rulesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.rulesRecyclerView.adapter = adapter

        // Toolbar action icons, not a FAB - all three (add/export/import) render in the same
        // style this way (plain tinted icon, no colored background or elevation shadow), which a
        // custom FAB-plus-ImageButtons mix couldn't give for free; see ARCHITECTURE.md's
        // RulesListFragment notes. One `MenuProvider` handles both this screen's normal menu and
        // the multi-select one (below) rather than swapping providers - `requireActivity().
        // invalidateMenu()` (called on every selection-state change) re-runs `onCreateMenu`, which
        // branches on `inSelectionMode` itself.
        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(if (inSelectionMode) R.menu.menu_rules_selection else R.menu.menu_rules_list, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    if (inSelectionMode) {
                        return when (menuItem.itemId) {
                            R.id.action_move_up_selected -> { moveSelected(up = true); true }
                            R.id.action_move_down_selected -> { moveSelected(up = false); true }
                            R.id.action_enable_selected -> { setEnabledForSelected(true); true }
                            R.id.action_disable_selected -> { setEnabledForSelected(false); true }
                            R.id.action_delete_selected_rules -> { confirmDeleteSelected(); true }
                            else -> false
                        }
                    }
                    return when (menuItem.itemId) {
                        R.id.action_add_rule -> {
                            openEditor(null)
                            true
                        }
                        R.id.action_export_rules -> {
                            exportLauncher.launch(defaultExportFileName())
                            true
                        }
                        R.id.action_import_rules -> {
                            importLauncher.launch(arrayOf("application/json"))
                            true
                        }
                        else -> false
                    }
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED,
        )
    }

    private fun handleClick(rule: ParseRule) {
        if (inSelectionMode) {
            adapter.toggleSelection(rule)
            if (adapter.selectedCount() == 0) clearSelectionAndExit() else updateSelectionCountDisplay()
        } else {
            openEditor(rule)
        }
    }

    /** Starts (or extends) the multi-select the toolbar's move/enable/disable/delete actions act
     * on - same long-press-to-select, toolbar-swap shape `MessagesFragment` already uses for the
     * identical gesture, hosted by `SettingsActivity` instead of `MainActivity` since that's where
     * this screen lives (see `SettingsActivity.updateSelectionCount`'s own KDoc for why that
     * needed only one small new method there, reusing `setBackGuard` for "cancel" rather than a
     * second bespoke mechanism). */
    private fun handleLongClick(rule: ParseRule): Boolean {
        if (!inSelectionMode) {
            inSelectionMode = true
            adapter.setSelectionMode(true)
            adapter.toggleSelection(rule)
            requireActivity().invalidateMenu()
            (activity as? SettingsActivity)?.apply {
                updateSelectionCount(adapter.selectedCount())
                setBackGuard(::clearSelectionAndExit)
            }
        } else {
            adapter.toggleSelection(rule)
            if (adapter.selectedCount() == 0) clearSelectionAndExit() else updateSelectionCountDisplay()
        }
        return true
    }

    private fun updateSelectionCountDisplay() {
        (activity as? SettingsActivity)?.updateSelectionCount(adapter.selectedCount())
    }

    private fun clearSelectionAndExit() {
        adapter.clearSelection()
        adapter.setSelectionMode(false)
        inSelectionMode = false
        requireActivity().invalidateMenu()
        (activity as? SettingsActivity)?.apply {
            setBackGuard(null)
            setScreenTitle(R.string.settings_rules_title)
        }
    }

    /** Processes selected ids in on-screen order (top-to-bottom moving up, bottom-to-top moving
     * down) and re-reads that order fresh each call - each `RulesDb.moveUp`/`moveDown` call
     * already re-reads the table's current sort order itself, so a selected id's own neighbor by
     * the time its turn comes may be another just-moved selected id, not its original one. The
     * user's own chosen semantics: every selected rule moves exactly one slot in the chosen
     * direction, independently - processing in this order is what keeps a contiguous selected
     * block moving as a unit (each item's neighbor swap lands it next to the next one in the
     * block) instead of rows leapfrogging each other into a jumbled order. */
    private fun moveSelected(up: Boolean) {
        val ids = adapter.selectedIds()
        val ordered = rulesDb.loadAllOrdered().map { it.id }.filter { it in ids }
        (if (up) ordered else ordered.reversed()).forEach {
            if (up) rulesDb.moveUp(it) else rulesDb.moveDown(it)
        }
        reload()
    }

    private fun setEnabledForSelected(enabled: Boolean) {
        val ids = adapter.selectedIds()
        rulesDb.loadAllOrdered().filter { it.id in ids }.forEach { rulesDb.update(it.copy(enabled = enabled)) }
        reload()
    }

    /** Cancel always rightmost/last (house convention, AGENTS.md) - the real action
     * (`action_delete`/"Delete") goes in [AlertDialog.Builder.setNegativeButton] (left), Cancel in
     * [AlertDialog.Builder.setPositiveButton] (right). */
    private fun confirmDeleteSelected() {
        val ids = adapter.selectedIds()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.rules_delete_selected_confirm_title, ids.size))
            .setMessage(R.string.rules_delete_selected_confirm_message)
            .setNegativeButton(R.string.action_delete) { _, _ ->
                ids.forEach { rulesDb.delete(it) }
                clearSelectionAndExit()
            }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Skipped while a selection is active - would otherwise stomp the "N selected" title
        // (and the up arrow's cancel-selection guard stays whatever handleLongClick set, this
        // never touches it either way) if onResume happens to re-run while selecting, e.g.
        // returning from a backgrounded app.
        if (!inSelectionMode) (activity as? SettingsActivity)?.setScreenTitle(R.string.settings_rules_title)
        reload()
    }

    private fun reload() {
        val binding = binding ?: return
        val rules = rulesDb.loadAllOrdered()
        adapter.submitList(rules)
        binding.rulesEmptyText.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun defaultExportFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "ffnotifyimporter-rules-$date.json"
    }

    /** Sharing format, not a full backup (see `BackupFragment` for that) - every distinct rule
     * currently saved, newest-first-by-sort-order like the list itself shows them. */
    private fun exportTo(uri: Uri) {
        val context = requireContext()
        val rules = rulesDb.loadAllOrdered()
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(rulesToJson(rules).toByteArray(Charsets.UTF_8)) }
            val name = displayNameOf(context, uri)
            val message = if (name != null) {
                getString(R.string.rules_export_success_named, rules.size, name)
            } else {
                getString(R.string.rules_export_success, rules.size)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.rules_export_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /** Always appends as new rows (fresh ids via `RulesDb.add`) - importing is "add these rules
     * too", never "replace what I have", so sharing a rule with someone (or across your own
     * devices) can't accidentally wipe rules they already built themselves. */
    private fun importFrom(uri: Uri) {
        val context = requireContext()
        try {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return
            val rules = rulesFromJson(json)
            if (rules == null) {
                Toast.makeText(context, R.string.rules_import_invalid_file, Toast.LENGTH_LONG).show()
                return
            }
            rules.forEach { rulesDb.add(it) }
            reload()
            val name = displayNameOf(context, uri)
            val message = if (name != null) {
                getString(R.string.rules_import_success_named, rules.size, name)
            } else {
                getString(R.string.rules_import_success, rules.size)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.rules_import_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun openEditor(rule: ParseRule?) {
        (activity as? SettingsActivity)?.openScreen(
            RuleEditFragment.newInstance(rule?.id),
            R.string.settings_rules_title,
        )
    }

    private fun showOverflowMenu(rule: ParseRule, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, ID_MOVE_UP, 0, R.string.rule_move_up)
        popup.menu.add(0, ID_MOVE_DOWN, 1, R.string.rule_move_down)
        popup.menu.add(0, ID_DUPLICATE, 2, R.string.rule_duplicate)
        popup.menu.add(0, ID_DELETE, 3, R.string.action_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_MOVE_UP -> rulesDb.moveUp(rule.id)
                ID_MOVE_DOWN -> rulesDb.moveDown(rule.id)
                ID_DUPLICATE -> rulesDb.duplicate(rule.id)
                ID_DELETE -> rulesDb.delete(rule.id)
                else -> return@setOnMenuItemClickListener false
            }
            reload()
            true
        }
        popup.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private companion object {
        const val ID_MOVE_UP = 1
        const val ID_MOVE_DOWN = 2
        const val ID_DUPLICATE = 3
        const val ID_DELETE = 4
    }
}
