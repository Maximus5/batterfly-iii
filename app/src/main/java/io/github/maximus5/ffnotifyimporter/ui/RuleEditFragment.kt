package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.appcompat.app.AlertDialog
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.databinding.FragmentRuleEditBinding
import io.github.maximus5.ffnotifyimporter.notifications.AppLabelResolver
import io.github.maximus5.ffnotifyimporter.parser.namedGroupNames
import io.github.maximus5.ffnotifyimporter.prefs.MonitoredAppsPrefs
import io.github.maximus5.ffnotifyimporter.rules.ParseRule
import io.github.maximus5.ffnotifyimporter.rules.RuleKind
import io.github.maximus5.ffnotifyimporter.rules.RulesDb
import io.github.maximus5.ffnotifyimporter.rules.validateRule

/** Create (ruleId == null) or edit (ruleId != null) one [ParseRule]. */
class RuleEditFragment : Fragment() {

    private var binding: FragmentRuleEditBinding? = null
    private lateinit var rulesDb: RulesDb
    private var existingRule: ParseRule? = null
    private var selectedPackages: Set<String> = emptySet()

    private val kindOrder = listOf(
        RuleKind.WITHDRAWAL,
        RuleKind.DEPOSIT,
        RuleKind.TRANSFER_OUT,
        RuleKind.TRANSFER_IN,
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentRuleEditBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        rulesDb = RulesDb.getInstance(requireContext())

        binding.kindSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            kindOrder.map { it.name },
        )
        binding.kindSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                updateCounterpartyVisibility()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })

        val ruleId = arguments?.getString(ARG_RULE_ID)
        existingRule = ruleId?.let { id -> rulesDb.loadAllOrdered().firstOrNull { it.id == id } }
        populateFields(existingRule)

        binding.patternInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateAvailableMacros()
        })

        binding.chooseAppsButton.setOnClickListener { showAppsPicker() }
        binding.pickPatternButton.setOnClickListener { showPatternPicker() }
        binding.saveRuleButton.setOnClickListener { save() }
        binding.deleteRuleButton.setOnClickListener { confirmDelete() }

        updateAppsSummary()
        updateAvailableMacros()
    }

    private fun populateFields(rule: ParseRule?) {
        val binding = binding ?: return
        selectedPackages = rule?.packageNames ?: emptySet()
        binding.labelInput.setText(rule?.label.orEmpty())
        binding.kindSpinner.setSelection(kindOrder.indexOf(rule?.kind ?: RuleKind.WITHDRAWAL))
        binding.accountNameInput.setText(rule?.accountName.orEmpty())
        binding.homeCurrencyInput.setText(rule?.homeCurrency.orEmpty())
        binding.counterpartyAccountInput.setText(rule?.counterpartyAccountName.orEmpty())
        binding.patternInput.setText(rule?.pattern.orEmpty())
        binding.decimalSeparatorInput.setText((rule?.decimalSeparator ?: ',').toString())
        binding.descriptionTemplateInput.setText(rule?.descriptionTemplate ?: "{title}")
        binding.notesTemplateInput.setText(rule?.notesTemplate ?: "{body}")
        binding.enabledSwitch.isChecked = rule?.enabled ?: true
        binding.deleteRuleButton.visibility = if (rule != null) View.VISIBLE else View.GONE
        updateCounterpartyVisibility()
    }

    private fun updateCounterpartyVisibility() {
        val binding = binding ?: return
        val kind = kindOrder.getOrNull(binding.kindSpinner.selectedItemPosition) ?: return
        val isTransfer = kind == RuleKind.TRANSFER_OUT || kind == RuleKind.TRANSFER_IN
        binding.counterpartyAccountLayout.visibility = if (isTransfer) View.VISIBLE else View.GONE
    }

    private fun updateAvailableMacros() {
        val binding = binding ?: return
        val pattern = binding.patternInput.text?.toString().orEmpty()
        val groups = runCatching { namedGroupNames(pattern) }.getOrDefault(emptyList())
        val macros = (groups + listOf("title", "body")).joinToString(" ") { "{$it}" }
        binding.availableMacrosText.text = getString(R.string.rule_available_macros, macros)
    }

    private fun showAppsPicker() {
        val context = requireContext()
        val packages = MonitoredAppsPrefs(context).getMonitoredPackages()
            .map { it to AppLabelResolver.resolve(context, it) }
            .sortedBy { it.second.lowercase() }
        if (packages.isEmpty()) {
            Toast.makeText(context, R.string.rule_no_monitored_apps, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = packages.map { it.second }.toTypedArray()
        val checked = packages.map { it.first in selectedPackages }.toBooleanArray()

        AlertDialog.Builder(context)
            .setTitle(R.string.rule_field_apps)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                selectedPackages = packages.filterIndexed { index, _ -> checked[index] }.map { it.first }.toSet()
                updateAppsSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateAppsSummary() {
        val binding = binding ?: return
        binding.appsSummaryText.text = if (selectedPackages.isEmpty()) {
            getString(R.string.rule_apps_any)
        } else {
            getString(R.string.rule_apps_count, selectedPackages.size)
        }
    }

    private fun showPatternPicker() {
        val context = requireContext()
        val patterns = rulesDb.distinctPatterns()
        if (patterns.isEmpty()) {
            Toast.makeText(context, R.string.rule_no_existing_patterns, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = patterns.map { (pattern, label) -> "$label: ${pattern.take(PATTERN_PREVIEW_LENGTH)}" }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(R.string.rule_pick_existing_pattern)
            .setItems(labels) { _, which -> binding?.patternInput?.setText(patterns[which].first) }
            .show()
    }

    private fun buildDraft(): ParseRule {
        val binding = requireBinding()
        val kind = kindOrder.getOrNull(binding.kindSpinner.selectedItemPosition) ?: RuleKind.WITHDRAWAL
        return ParseRule(
            id = existingRule?.id ?: "",
            label = binding.labelInput.text?.toString().orEmpty().trim(),
            packageNames = selectedPackages,
            kind = kind,
            accountName = binding.accountNameInput.text?.toString().orEmpty().trim(),
            homeCurrency = binding.homeCurrencyInput.text?.toString().orEmpty().trim(),
            counterpartyAccountName = binding.counterpartyAccountInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            pattern = binding.patternInput.text?.toString().orEmpty(),
            decimalSeparator = binding.decimalSeparatorInput.text?.toString()?.firstOrNull() ?: ',',
            descriptionTemplate = binding.descriptionTemplateInput.text?.toString().orEmpty(),
            notesTemplate = binding.notesTemplateInput.text?.toString().orEmpty(),
            enabled = binding.enabledSwitch.isChecked,
        )
    }

    private fun save() {
        val draft = buildDraft()
        val errors = validateRule(draft)
        if (errors.isNotEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.rule_validation_failed)
                .setMessage(errors.joinToString("\n") { "• $it" })
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        if (existingRule != null) {
            rulesDb.update(draft)
        } else {
            rulesDb.add(draft)
        }
        parentFragmentManager.popBackStack()
    }

    private fun confirmDelete() {
        val id = existingRule?.id ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rule_delete_confirm_title)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                rulesDb.delete(id)
                parentFragmentManager.popBackStack()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requireBinding() = binding ?: error("binding accessed after onDestroyView")

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {
        private const val ARG_RULE_ID = "ruleId"
        private const val PATTERN_PREVIEW_LENGTH = 40

        fun newInstance(ruleId: String?): RuleEditFragment = RuleEditFragment().apply {
            arguments = Bundle().apply { putString(ARG_RULE_ID, ruleId) }
        }
    }
}
