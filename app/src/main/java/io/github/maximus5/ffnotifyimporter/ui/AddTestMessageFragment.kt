package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.databinding.FragmentAddTestMessageBinding
import io.github.maximus5.ffnotifyimporter.notifications.AppLabelResolver
import io.github.maximus5.ffnotifyimporter.prefs.MonitoredAppsPrefs

/**
 * Debug tool: manually inject a message into [MessageStore], exactly like a real notification
 * would, without needing to actually trigger one. Lets rule-processing (once it exists) be
 * tested against arbitrary input instead of waiting for a real bank notification.
 */
class AddTestMessageFragment : Fragment() {

    private var binding: FragmentAddTestMessageBinding? = null
    private var appOptions: List<AppOption> = emptyList()

    private data class AppOption(val packageName: String, val label: String) {
        override fun toString() = label
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentAddTestMessageBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        val context = requireContext()

        appOptions = MonitoredAppsPrefs(context).getMonitoredPackages()
            .map { packageName -> AppOption(packageName, AppLabelResolver.resolve(context, packageName)) }
            .sortedBy { it.label.lowercase() }

        val hasApps = appOptions.isNotEmpty()
        binding.noMonitoredAppsText.visibility = if (hasApps) View.GONE else View.VISIBLE
        binding.appSpinner.isEnabled = hasApps
        binding.addButton.isEnabled = hasApps

        binding.appSpinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            appOptions,
        )

        binding.addButton.setOnClickListener { addTestMessage() }
    }

    private fun addTestMessage() {
        val binding = binding ?: return
        val selected = appOptions.getOrNull(binding.appSpinner.selectedItemPosition) ?: return
        val title = binding.titleInput.text?.toString().orEmpty()
        val text = binding.textInput.text?.toString().orEmpty()

        if (title.isBlank() && text.isBlank()) {
            Toast.makeText(requireContext(), R.string.add_test_message_empty_error, Toast.LENGTH_SHORT).show()
            return
        }

        val added = MessageStore.shared.add(selected.packageName, selected.label, title, text)
        val resultMessage = if (added) {
            R.string.add_test_message_added
        } else {
            R.string.add_test_message_duplicate
        }
        Toast.makeText(requireContext(), resultMessage, Toast.LENGTH_SHORT).show()

        if (added) {
            binding.titleInput.text?.clear()
            binding.textInput.text?.clear()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
