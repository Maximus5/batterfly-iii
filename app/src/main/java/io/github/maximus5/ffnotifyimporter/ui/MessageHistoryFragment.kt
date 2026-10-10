package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.data.MessageHistoryDb
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.data.RetentionController
import io.github.maximus5.ffnotifyimporter.databinding.FragmentMessageHistoryBinding
import io.github.maximus5.ffnotifyimporter.prefs.RetentionPrefs
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionsDb

/** The "save history to local storage" switch, the retention-window radio group, plus a button to
 * [AddTestMessageFragment] (the user's own call, moved here from its own top-level Settings row - a
 * debug tool, grouped with the other thing it most directly feeds, this screen's own captured-message
 * history, rather than sitting as a permanent fixture on the main Settings list). */
class MessageHistoryFragment : Fragment() {

    private var binding: FragmentMessageHistoryBinding? = null
    private lateinit var retentionPrefs: RetentionPrefs

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentMessageHistoryBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        retentionPrefs = RetentionPrefs(requireContext())

        binding.persistSwitch.isChecked = retentionPrefs.isPersistToDisk()
        radioIdFor(retentionPrefs.getRetentionDays())?.let { binding.retentionOptions.check(it) }

        binding.persistSwitch.setOnCheckedChangeListener { _, checked ->
            retentionPrefs.setPersistToDisk(checked)
            applySettings()
        }
        binding.retentionOptions.setOnCheckedChangeListener { _, checkedId ->
            val days = daysForRadioId(checkedId) ?: return@setOnCheckedChangeListener
            retentionPrefs.setRetentionDays(days)
            applySettings()
        }

        binding.addTestMessageButton.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                AddTestMessageFragment(),
                R.string.settings_add_test_message_title,
            )
        }
    }

    private fun applySettings() {
        val context = requireContext()
        RetentionController.applyOnSettingChanged(
            settings = retentionPrefs.get(),
            store = MessageStore.shared,
            db = MessageHistoryDb.getInstance(context),
            createdTransactions = CreatedTransactionsDb.getInstance(context),
        )
    }

    private fun radioIdFor(days: Int): Int? {
        val binding = binding ?: return null
        return when (days) {
            1 -> binding.retention1.id
            3 -> binding.retention3.id
            5 -> binding.retention5.id
            7 -> binding.retention7.id
            30 -> binding.retention30.id
            else -> null
        }
    }

    private fun daysForRadioId(radioId: Int): Int? {
        val binding = binding ?: return null
        return when (radioId) {
            binding.retention1.id -> 1
            binding.retention3.id -> 3
            binding.retention5.id -> 5
            binding.retention7.id -> 7
            binding.retention30.id -> 30
            else -> null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
