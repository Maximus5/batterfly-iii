package io.github.maximus5.ffnotifyimporter.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
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
import io.github.maximus5.ffnotifyimporter.posting.AutoCreateScheduler
import io.github.maximus5.ffnotifyimporter.prefs.MonitoredAppsPrefs
import java.util.Calendar
import java.util.Date

/**
 * Debug tool: manually inject a message into [MessageStore], exactly like a real notification
 * would, without needing to actually trigger one. Lets rule-processing (once it exists) be
 * tested against arbitrary input instead of waiting for a real bank notification.
 */
class AddTestMessageFragment : Fragment() {

    private var binding: FragmentAddTestMessageBinding? = null
    private var appOptions: List<AppOption> = emptyList()

    /** Defaults to "now" at the moment the screen opens, same as a real captured notification
     * would get - editable via [dateTimeButton] so a test message can reproduce a rule bug tied
     * to a specific real notification's timestamp instead of only ever being "just now". */
    private var selectedTimestampMillis: Long = System.currentTimeMillis()

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

        updateDateTimeButtonText()
        binding.dateTimeButton.setOnClickListener { showDateTimePicker() }

        binding.addButton.setOnClickListener { addTestMessage() }
    }

    private fun updateDateTimeButtonText() {
        val binding = binding ?: return
        val context = requireContext()
        val date = Date(selectedTimestampMillis)
        binding.dateTimeButton.text = "${DateFormat.getDateFormat(context).format(date)} ${DateFormat.getTimeFormat(context).format(date)}"
    }

    /** Date picker, then time picker, chained - the standard two-step Android pattern for picking
     * a single date-time with only platform dialogs, no extra library. */
    private fun showDateTimePicker() {
        val context = requireContext()
        val calendar = Calendar.getInstance().apply { timeInMillis = selectedTimestampMillis }
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        calendar.set(Calendar.MINUTE, minute)
                        calendar.set(Calendar.SECOND, 0)
                        selectedTimestampMillis = calendar.timeInMillis
                        updateDateTimeButtonText()
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    DateFormat.is24HourFormat(context),
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
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

        val context = requireContext()
        val added = MessageStore.shared.add(selected.packageName, selected.label, title, text, selectedTimestampMillis)
        val resultMessage = if (added != null) {
            R.string.add_test_message_added
        } else {
            R.string.add_test_message_duplicate
        }
        Toast.makeText(context, resultMessage, Toast.LENGTH_SHORT).show()

        if (added != null) {
            // "Exactly like a real notification would" (this file's own KDoc) - includes
            // triggering auto-create the same way BankNotificationListenerService does.
            AutoCreateScheduler.scheduleIfEnabled(context, added)
            binding.titleInput.text?.clear()
            binding.textInput.text?.clear()
            selectedTimestampMillis = System.currentTimeMillis()
            updateDateTimeButtonText()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
