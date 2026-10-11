package io.github.maximus5.ffnotifyimporter.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.prefs.SetupChecklistPrefs
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.databinding.FragmentAddTestMessageBinding
import io.github.maximus5.ffnotifyimporter.notifications.AppLabelResolver
import android.content.Context
import io.github.maximus5.ffnotifyimporter.posting.AutoCreateScheduler
import io.github.maximus5.ffnotifyimporter.posting.ReviewNotificationScheduler
import io.github.maximus5.ffnotifyimporter.prefs.MonitoredAppsPrefs
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug tool: manually inject a message into [MessageStore], exactly like a real notification
 * would, without needing to actually trigger one. Lets rule-processing (once it exists) be
 * tested against arbitrary input instead of waiting for a real bank notification.
 */
class AddTestMessageFragment : Fragment() {

    private var binding: FragmentAddTestMessageBinding? = null
    private var appOptions: List<AppOption> = emptyList()

    /** Defaults to "now" at the moment the screen opens, same as a real captured notification
     * would get - editable via the date and time fields so a test message can reproduce a rule bug tied
     * to a specific real notification's timestamp instead of only ever being "just now". */
    private var selectedTimestampMillis: Long = System.currentTimeMillis()
    private var timestampEdited = false

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
        addSaveMenu(onSave = ::addTestMessage).apply {
            label = getString(R.string.add_test_message_add_button)
            enabled = hasApps
        }

        binding.appSpinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            appOptions,
        )

        updateDateTimeFields()
        binding.messageDateInput.setOnClickListener { showDatePicker() }
        binding.messageTimeInput.setOnClickListener { showTimePicker() }
    }

    /** Same long-date-plus-weekday and short-time display as `CreateTransactionFragment`. */
    private fun updateDateTimeFields() {
        val binding = binding ?: return
        val context = requireContext()
        val date = Date(selectedTimestampMillis)
        val longDate = DateFormat.getLongDateFormat(context).format(date)
        val weekday = SimpleDateFormat("EEE", Locale.getDefault()).format(date)
        binding.messageDateInput.setText("$longDate, $weekday")
        binding.messageTimeInput.setText(DateFormat.getTimeFormat(context).format(date))
    }

    /** Each field only changes its own half of [selectedTimestampMillis]. */
    private fun showDatePicker() {
        val context = requireContext()
        val calendar = Calendar.getInstance().apply { timeInMillis = selectedTimestampMillis }
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                selectedTimestampMillis = calendar.timeInMillis
                timestampEdited = true
                updateDateTimeFields()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun showTimePicker() {
        val context = requireContext()
        val calendar = Calendar.getInstance().apply { timeInMillis = selectedTimestampMillis }
        TimePickerDialog(
            context,
            { _, hourOfDay, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.SECOND, 0)
                selectedTimestampMillis = calendar.timeInMillis
                timestampEdited = true
                updateDateTimeFields()
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            DateFormat.is24HourFormat(context),
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
        if (binding.delayCheckbox.isChecked) {
            // Lets the tester leave this screen and background the app before the message lands, to
            // exercise what a real notification does while the app is not in front. The handler lives
            // on the application context so it outlives this screen; an untouched date/time becomes
            // "now" at the moment of adding, like a real notification's would.
            val appContext = context.applicationContext
            val timestamp = if (timestampEdited) selectedTimestampMillis else null
            Toast.makeText(context, R.string.add_test_message_delayed_toast, Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({
                inject(appContext, selected.packageName, selected.label, title, text, timestamp ?: System.currentTimeMillis())
            }, DELAY_MILLIS)
            (activity as? SettingsActivity)?.leaveScreen()
            return
        }
        if (inject(context, selected.packageName, selected.label, title, text, selectedTimestampMillis)) {
            // Back to whoever opened this screen (the Messages list from the checklist, or
            // Message history) - the new message is visible there right away.
            (activity as? SettingsActivity)?.leaveScreen()
        }
    }

    /** True when the message was added (false: dropped as a duplicate). */
    private fun inject(context: Context, packageName: String, label: String, title: String, text: String, timestampMillis: Long): Boolean {
        val added = MessageStore.shared.add(packageName, label, title, text, timestampMillis)
        val resultMessage = if (added != null) {
            R.string.add_test_message_added
        } else {
            R.string.add_test_message_duplicate
        }
        Toast.makeText(context, resultMessage, Toast.LENGTH_SHORT).show()

        if (added != null) {
            SetupChecklistPrefs(context).retire()
            // "Exactly like a real notification would" (this file's own KDoc) - includes
            // triggering auto-create the same way BankNotificationListenerService does.
            AutoCreateScheduler.scheduleIfEnabled(context, added)
            ReviewNotificationScheduler.scheduleIfNeeded(context, added)
        }
        return added != null
    }

    private companion object {
        const val DELAY_MILLIS = 10_000L
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
