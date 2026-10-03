package io.github.maximus5.ffnotifyimporter.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
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

    /** package name to display label, sorted - a rule can only ever match a monitored app. */
    private var monitoredApps: List<Pair<String, String>> = emptyList()
    private var selectedPackageName: String? = null
    private var selectedKind: RuleKind = RuleKind.WITHDRAWAL

    private val kindOrder = listOf(RuleKind.WITHDRAWAL, RuleKind.DEPOSIT, RuleKind.TRANSFER)

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

        setupKindDropdown()
        setupAppsDropdown()

        val ruleId = arguments?.getString(ARG_RULE_ID)
        existingRule = ruleId?.let { id -> rulesDb.loadAllOrdered().firstOrNull { it.id == id } }
        populateFields(existingRule)

        binding.patternInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateAvailableMacros()
        })

        binding.pickPatternButton.setOnClickListener { showPatternPicker() }
        binding.saveRuleButton.setOnClickListener { save() }
        binding.deleteRuleButton.setOnClickListener { confirmDelete() }

        updateAvailableMacros()
    }

    private fun setupKindDropdown() {
        val binding = binding ?: return
        binding.kindInput.setSimpleItems(kindOrder.map { getString(it.labelRes()) }.toTypedArray())
        binding.kindInput.setOnItemClickListener { _, _, position, _ -> selectedKind = kindOrder[position] }
    }

    private fun setupAppsDropdown() {
        val binding = binding ?: return
        val context = requireContext()
        monitoredApps = MonitoredAppsPrefs(context).getMonitoredPackages()
            .map { it to AppLabelResolver.resolve(context, it) }
            .sortedBy { it.second.lowercase() }

        if (monitoredApps.isEmpty()) {
            binding.appInput.isEnabled = false
            Toast.makeText(context, R.string.rule_no_monitored_apps, Toast.LENGTH_LONG).show()
            return
        }

        binding.appInput.setSimpleItems(monitoredApps.map { it.second }.toTypedArray())
        binding.appInput.setOnItemClickListener { _, _, position, _ ->
            selectedPackageName = monitoredApps[position].first
        }
    }

    private fun populateFields(rule: ParseRule?) {
        val binding = binding ?: return

        binding.labelInput.setText(
            rule?.label ?: getString(R.string.rule_default_label, rulesDb.loadAllOrdered().size + 1),
        )

        selectedPackageName = rule?.packageNames?.firstOrNull() ?: monitoredApps.firstOrNull()?.first
        monitoredApps.firstOrNull { it.first == selectedPackageName }?.let { binding.appInput.setText(it.second, false) }

        selectedKind = rule?.kind ?: RuleKind.WITHDRAWAL
        binding.kindInput.setText(getString(selectedKind.labelRes()), false)

        binding.sourceAccountInput.setText(rule?.sourceAccountName.orEmpty())
        binding.destinationAccountInput.setText(rule?.destinationAccountName.orEmpty())
        binding.homeCurrencyInput.setText(rule?.homeCurrency.orEmpty())
        binding.patternInput.setText(rule?.pattern.orEmpty())
        binding.descriptionTemplateInput.setText(rule?.descriptionTemplate.orEmpty())
        binding.notesTemplateInput.setText(rule?.notesTemplate.orEmpty())
        binding.categoryTemplateInput.setText(rule?.categoryTemplate.orEmpty())
        binding.tagsTemplateInput.setText(rule?.tagsTemplate.orEmpty())
        binding.enabledSwitch.isChecked = rule?.enabled ?: true
        binding.deleteRuleButton.visibility = if (rule != null) View.VISIBLE else View.GONE
    }

    private fun updateAvailableMacros() {
        val binding = binding ?: return
        val pattern = binding.patternInput.text?.toString().orEmpty()
        val groups = runCatching { namedGroupNames(pattern) }.getOrDefault(emptyList())
        val macros = (groups + listOf("title", "body")).joinToString(" ") { "{$it}" }
        binding.availableMacrosText.text = getString(R.string.rule_available_macros, macros)
    }

    private fun showPatternPicker() {
        val context = requireContext()
        val saved = rulesDb.distinctPatterns()
        // Nothing saved yet (e.g. the very first rule ever) - offer one illustrative example
        // instead of leaving the picker with nothing to show at all.
        val patterns = saved.ifEmpty { listOf(EXAMPLE_PATTERN to getString(R.string.rule_pattern_example_label)) }
        val labels = patterns.map { (pattern, label) -> "$label: ${pattern.take(PATTERN_PREVIEW_LENGTH)}" }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(R.string.rule_pick_existing_pattern)
            .setItems(labels) { _, which -> binding?.patternInput?.setText(patterns[which].first) }
            .show()
    }

    private fun buildDraft(): ParseRule {
        val binding = requireBinding()
        return ParseRule(
            id = existingRule?.id ?: "",
            label = binding.labelInput.text?.toString().orEmpty().trim(),
            packageNames = selectedPackageName?.let { setOf(it) } ?: emptySet(),
            kind = selectedKind,
            sourceAccountName = binding.sourceAccountInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            destinationAccountName = binding.destinationAccountInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            homeCurrency = binding.homeCurrencyInput.text?.toString().orEmpty().trim(),
            pattern = binding.patternInput.text?.toString().orEmpty(),
            descriptionTemplate = binding.descriptionTemplateInput.text?.toString().orEmpty(),
            notesTemplate = binding.notesTemplateInput.text?.toString().orEmpty(),
            categoryTemplate = binding.categoryTemplateInput.text?.toString().orEmpty(),
            tagsTemplate = binding.tagsTemplateInput.text?.toString().orEmpty(),
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

        // Shown in the pattern picker only as a fallback when the user has no saved rules yet -
        // just enough to demonstrate the named-group syntax, not meant to match anything real.
        private const val EXAMPLE_PATTERN =
            """(?<amount>[\d.,]+)\s*(?<currency>[A-Z]{3}).*?(?:at|bei)\s+(?<counterparty>.+)"""

        fun newInstance(ruleId: String?): RuleEditFragment = RuleEditFragment().apply {
            arguments = Bundle().apply { putString(ARG_RULE_ID, ruleId) }
        }
    }
}
