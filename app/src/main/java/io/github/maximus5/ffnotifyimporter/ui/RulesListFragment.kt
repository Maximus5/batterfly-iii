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

class RulesListFragment : Fragment() {

    private var binding: FragmentRulesListBinding? = null
    private lateinit var adapter: RulesAdapter
    private lateinit var rulesDb: RulesDb

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

        adapter = RulesAdapter(onClick = ::openEditor, onOverflowClick = ::showOverflowMenu)
        binding.rulesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.rulesRecyclerView.adapter = adapter

        // Toolbar action icons, not a FAB - all three (add/export/import) render in the same
        // style this way (plain tinted icon, no colored background or elevation shadow), which a
        // custom FAB-plus-ImageButtons mix couldn't give for free; see ARCHITECTURE.md's
        // RulesListFragment notes.
        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.menu_rules_list, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
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

    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setScreenTitle(R.string.settings_rules_title)
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
