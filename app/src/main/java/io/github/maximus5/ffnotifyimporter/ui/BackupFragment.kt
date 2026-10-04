package io.github.maximus5.ffnotifyimporter.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.backup.BackupManager
import io.github.maximus5.ffnotifyimporter.backup.fullBackupFromJson
import io.github.maximus5.ffnotifyimporter.backup.fullBackupToJson
import io.github.maximus5.ffnotifyimporter.backup.rulesFromJson
import io.github.maximus5.ffnotifyimporter.backup.rulesToJson
import io.github.maximus5.ffnotifyimporter.databinding.FragmentBackupBinding
import io.github.maximus5.ffnotifyimporter.rules.RulesDb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-device backup/restore - monitored apps, currencies, rules, message history. Deliberately
 * *not* the Firefly connection (base URL/token) - see `FullBackupData`'s KDoc. Also hosts
 * shortcuts to the Rules screen's own export/import ([RulesListFragment]'s toolbar actions, same
 * `rulesToJson`/`rulesFromJson`/`RulesDb` calls, not a separate implementation) - a sharing format
 * (append-only, rules only), not a replace-everything restore like the full backup above it; kept
 * here too since a user reaching for "Backup & restore" for just the rules shouldn't have to know
 * to look on a different screen for that. */
class BackupFragment : Fragment() {

    private var binding: FragmentBackupBinding? = null

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportTo(uri)
    }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestoreFrom(uri)
    }
    private val exportRulesLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportRulesTo(uri)
    }
    private val importRulesLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importRulesFrom(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentBackupBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        binding.exportBackupButton.setOnClickListener { exportLauncher.launch(defaultBackupFileName()) }
        binding.restoreBackupButton.setOnClickListener { restoreLauncher.launch(arrayOf("application/json")) }
        binding.exportRulesButton.setOnClickListener { exportRulesLauncher.launch(defaultRulesFileName()) }
        binding.importRulesButton.setOnClickListener { importRulesLauncher.launch(arrayOf("application/json")) }
    }

    private fun defaultBackupFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "ffnotifyimporter-backup-$date.json"
    }

    private fun defaultRulesFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "ffnotifyimporter-rules-$date.json"
    }

    /** Identical to `RulesListFragment.exportTo` - same sharing format, same function calls, just
     * a second entry point onto it. */
    private fun exportRulesTo(uri: Uri) {
        val context = requireContext()
        val rules = RulesDb.getInstance(context).loadAllOrdered()
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

    /** Identical to `RulesListFragment.importFrom` - always appends as new rows, never replaces. */
    private fun importRulesFrom(uri: Uri) {
        val context = requireContext()
        try {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return
            val rules = rulesFromJson(json)
            if (rules == null) {
                Toast.makeText(context, R.string.rules_import_invalid_file, Toast.LENGTH_LONG).show()
                return
            }
            val rulesDb = RulesDb.getInstance(context)
            rules.forEach { rulesDb.add(it) }
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

    private fun exportTo(uri: Uri) {
        val context = requireContext()
        try {
            val json = fullBackupToJson(BackupManager.collect(context))
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val name = displayNameOf(context, uri)
            val message = if (name != null) getString(R.string.backup_export_success_named, name) else getString(R.string.backup_export_success)
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.backup_export_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /** Restoring replaces everything (see `BackupManager.restore`'s KDoc) - confirmed with the
     * user first, the same "ask before a destructive/hard-to-reverse action" instinct that was
     * missing from this app's old SQLite onUpgrade pattern (see ARCHITECTURE.md's Known bugs). */
    private fun confirmRestoreFrom(uri: Uri) {
        // Cancel always rightmost/last (house convention, see AGENTS.md) - the action sits in the
        // negative slot instead of positive so Cancel can take the positive (rightmost) one.
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_restore_confirm_title)
            .setMessage(R.string.backup_restore_confirm_message)
            .setNegativeButton(R.string.backup_restore_confirm_button) { _, _ -> restoreFrom(uri) }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    private fun restoreFrom(uri: Uri) {
        val context = requireContext()
        try {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return
            val data = fullBackupFromJson(json)
            if (data == null) {
                Toast.makeText(context, R.string.backup_invalid_file, Toast.LENGTH_LONG).show()
                return
            }
            BackupManager.restore(context, data)
            val name = displayNameOf(context, uri)
            val message = if (name != null) getString(R.string.backup_restore_success_named, name) else getString(R.string.backup_restore_success)
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.backup_restore_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
