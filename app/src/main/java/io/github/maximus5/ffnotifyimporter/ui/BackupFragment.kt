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
import io.github.maximus5.ffnotifyimporter.databinding.FragmentBackupBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-device backup/restore - monitored apps, currencies, rules, message history. Deliberately
 * *not* the Firefly connection (base URL/token) - see `FullBackupData`'s KDoc. Separate from the
 * Rules screen's own export/import ([RulesListFragment]), which is a sharing format (append-only,
 * rules only), not a replace-everything restore. */
class BackupFragment : Fragment() {

    private var binding: FragmentBackupBinding? = null

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportTo(uri)
    }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestoreFrom(uri)
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
    }

    private fun defaultBackupFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "ffnotifyimporter-backup-$date.json"
    }

    private fun exportTo(uri: Uri) {
        val context = requireContext()
        try {
            val json = fullBackupToJson(BackupManager.collect(context))
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(context, R.string.backup_export_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.backup_export_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /** Restoring replaces everything (see `BackupManager.restore`'s KDoc) - confirmed with the
     * user first, the same "ask before a destructive/hard-to-reverse action" instinct that was
     * missing from this app's old SQLite onUpgrade pattern (see ARCHITECTURE.md's Known bugs). */
    private fun confirmRestoreFrom(uri: Uri) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_restore_confirm_title)
            .setMessage(R.string.backup_restore_confirm_message)
            .setPositiveButton(R.string.backup_restore_confirm_button) { _, _ -> restoreFrom(uri) }
            .setNegativeButton(android.R.string.cancel, null)
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
            Toast.makeText(context, R.string.backup_restore_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.backup_restore_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
