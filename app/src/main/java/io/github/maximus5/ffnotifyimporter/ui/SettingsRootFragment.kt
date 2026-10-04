package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.FragmentSettingsRootBinding
import io.github.maximus5.ffnotifyimporter.network.hostOf
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.prefs.RetentionPrefs

class SettingsRootFragment : Fragment() {

    private var binding: FragmentSettingsRootBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentSettingsRootBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return

        binding.rowFirefly.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                FireflySettingsFragment(),
                R.string.settings_firefly_title,
            )
        }
        binding.rowMonitoredApps.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                AppsFragment(),
                R.string.settings_monitored_apps_title,
            )
        }
        binding.rowRules.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                RulesListFragment(),
                R.string.settings_rules_title,
            )
        }
        binding.rowCurrencies.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                CurrenciesFragment(),
                R.string.settings_currencies_title,
            )
        }
        binding.rowMessageHistory.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                MessageHistoryFragment(),
                R.string.settings_message_history_title,
            )
        }
        binding.rowAddTestMessage.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                AddTestMessageFragment(),
                R.string.settings_add_test_message_title,
            )
        }
        binding.rowBackup.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                BackupFragment(),
                R.string.settings_backup_title,
            )
        }
        binding.rowAbout.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                AboutFragment(),
                R.string.settings_about_title,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setScreenTitle(R.string.action_settings)
        updateMessageHistorySubtitle()
        updateFireflySubtitle()
    }

    private fun updateFireflySubtitle() {
        val binding = binding ?: return
        val host = hostOf(FireflyPrefs(requireContext()).getBaseUrl())
        binding.rowFireflySubtitle.text = if (host != null) {
            getString(R.string.settings_firefly_subtitle_configured, host)
        } else {
            getString(R.string.settings_firefly_subtitle_not_configured)
        }
    }

    private fun updateMessageHistorySubtitle() {
        val binding = binding ?: return
        val days = RetentionPrefs(requireContext()).getRetentionDays()
        binding.rowMessageHistorySubtitle.text = when {
            days <= 0 -> getString(R.string.settings_message_history_subtitle_off)
            days == 1 -> getString(R.string.settings_message_history_subtitle_on_one)
            else -> getString(R.string.settings_message_history_subtitle_on_many, days)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
