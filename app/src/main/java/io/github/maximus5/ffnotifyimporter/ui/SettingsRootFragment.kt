package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.FragmentSettingsRootBinding
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

        binding.rowMonitoredApps.setOnClickListener {
            (activity as? SettingsActivity)?.openScreen(
                AppsFragment(),
                R.string.settings_monitored_apps_title,
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
    }

    override fun onResume() {
        super.onResume()
        updateMessageHistorySubtitle()
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
