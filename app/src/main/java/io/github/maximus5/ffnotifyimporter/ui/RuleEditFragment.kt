package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.databinding.FragmentRuleEditBinding
import io.github.maximus5.ffnotifyimporter.notifications.AppLabelResolver
import io.github.maximus5.ffnotifyimporter.parser.namedGroupNames
import io.github.maximus5.ffnotifyimporter.prefs.MonitoredAppsPrefs
import io.github.maximus5.ffnotifyimporter.rules.CurrenciesDb
import io.github.maximus5.ffnotifyimporter.rules.FIXED_MACROS
import io.github.maximus5.ffnotifyimporter.rules.ParseOutcome
import io.github.maximus5.ffnotifyimporter.rules.ParseRule
import io.github.maximus5.ffnotifyimporter.rules.RuleEngine
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

        binding.pickMessageButton.setOnClickListener { showMessagePicker() }
        binding.pickPatternButton.setOnClickListener { showPatternPicker() }
        binding.testAgainstHistoryButton.setOnClickListener { testAgainstHistory() }
        binding.saveRuleButton.setOnClickListener { save() }
        binding.deleteRuleButton.setOnClickListener { confirmDelete() }
        binding.homeCurrencyLayout.setEndIconOnClickListener { showCurrencyPicker() }

        setupMacroInsertButtons()
        updateAvailableMacros()
    }

    /** Picks from the user-maintained code<->symbol table (Settings -> Currencies) instead of
     * hand-typing a code and risking a typo `normalizeCurrency` would never recognize. */
    private fun showCurrencyPicker() {
        val context = requireContext()
        val entries = CurrenciesDb.getInstance(context).loadAll()
        if (entries.isEmpty()) return
        val labels = entries.map { "${it.code} (${it.symbol})" }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(R.string.rule_pick_currency)
            .setItems(labels) { _, which -> binding?.homeCurrencyInput?.setText(entries[which].code) }
            .show()
    }

    /**
     * Every template field (anything below the regex field that's macro-driven - account names,
     * description/notes/category/tags) gets a custom end icon that inserts a `{name}` macro at
     * the cursor, instead of requiring it to be typed out by hand. Deliberately an insert at the
     * current selection, not a `setText` - the field may already have other text around where the
     * macro goes. Not home currency - that's a plain comparison value, not a template, and has
     * its own end icon ([showCurrencyPicker]) that `setText`s the whole field instead.
     */
    private fun setupMacroInsertButtons() {
        val binding = binding ?: return
        val targets = listOf(
            binding.sourceAccountLayout to binding.sourceAccountInput,
            binding.destinationAccountLayout to binding.destinationAccountInput,
            binding.descriptionTemplateLayout to binding.descriptionTemplateInput,
            binding.notesTemplateLayout to binding.notesTemplateInput,
            binding.categoryTemplateLayout to binding.categoryTemplateInput,
            binding.tagsTemplateLayout to binding.tagsTemplateInput,
        )
        targets.forEach { (layout, input) ->
            layout.setEndIconOnClickListener { showMacroPicker(input) }
        }
    }

    /**
     * One list mixing two kinds of insertable text: real `{macro}` tokens (declared groups plus
     * [FIXED_MACROS]), and the current app's name as a plain literal string - not a macro, since
     * a rule is always scoped to exactly one app (no `{appname}` macro exists; see
     * `MacroTemplate.kt`'s KDoc for why that was removed).
     */
    private fun showMacroPicker(target: EditText) {
        val context = requireContext()
        val pattern = binding?.patternInput?.text?.toString().orEmpty()
        val groups = runCatching { namedGroupNames(pattern) }.getOrDefault(emptyList())
        val macroEntries = (groups + FIXED_MACROS).map { "{$it}" to "{$it}" }
        val appLabel = monitoredApps.firstOrNull { it.first == selectedPackageName }?.second
        val appEntry = appLabel?.let { listOf(getString(R.string.rule_insert_app_name, it) to it) }.orEmpty()
        val entries = macroEntries + appEntry
        if (entries.isEmpty()) return

        AlertDialog.Builder(context)
            .setTitle(R.string.rule_insert_macro)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> insertTextAtCursor(target, entries[which].second) }
            .show()
    }

    private fun insertTextAtCursor(target: EditText, text: String) {
        val start = target.selectionStart.coerceAtLeast(0)
        val end = target.selectionEnd.coerceAtLeast(0)
        val insertAt = minOf(start, end)
        target.text?.replace(insertAt, maxOf(start, end), text)
        // The end icon click (and the picker dialog it opened) moved focus away from the field,
        // so the cursor needs to be put back explicitly - otherwise it's left wherever it was
        // (or nowhere), not right after what was just inserted.
        target.requestFocus()
        target.setSelection(insertAt + text.length)
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
        val macros = (groups + FIXED_MACROS).joinToString(" ") { "{$it}" }
        binding.availableMacrosText.text = getString(R.string.rule_available_macros, macros)
    }

    /**
     * Lets the user pick a real captured message (filtered to the app currently selected above -
     * a rule can only ever match that app's notifications anyway) and drops its combined
     * "title\nbody" into the pattern field as a literal starting point, instead of having to
     * retype or hand-copy the bank's wording from the Messages screen. In-memory only
     * (`MessageStore.shared`), so this only offers whatever's been captured so far this session.
     */
    private fun showMessagePicker() {
        val context = requireContext()
        val packageName = selectedPackageName
        if (packageName == null) {
            Toast.makeText(context, R.string.rule_no_monitored_apps, Toast.LENGTH_SHORT).show()
            return
        }
        val messages = MessageStore.shared.snapshot().filter { it.packageName == packageName }
        if (messages.isEmpty()) {
            Toast.makeText(context, R.string.rule_no_captured_messages, Toast.LENGTH_SHORT).show()
            return
        }
        val adapter = PickerItemAdapter(context, messages.map { it.title to it.text }, monospaceBody = false)

        AlertDialog.Builder(context)
            .setTitle(R.string.rule_pick_message)
            .setAdapter(adapter) { _, which -> setPatternFrom(messages[which]) }
            .show()
    }

    private fun setPatternFrom(message: CapturedMessage) {
        binding?.patternInput?.setText("${message.title}\n${message.text}")
    }

    /**
     * Runs the rule as currently drafted (unsaved edits included - deliberately not gated on
     * `validateRule`, since fields irrelevant to matching like a blank label shouldn't block a
     * pattern sanity check) against this session's captured messages for the selected app,
     * newest first, stopping once [MAX_TEST_MATCHES] matches are found - just enough for a
     * representative sample without scanning a long history for nothing.
     */
    private fun testAgainstHistory() {
        val context = requireContext()
        val packageName = selectedPackageName
        if (packageName == null) {
            Toast.makeText(context, R.string.rule_no_monitored_apps, Toast.LENGTH_SHORT).show()
            return
        }
        val messages = MessageStore.shared.snapshot().filter { it.packageName == packageName }
        if (messages.isEmpty()) {
            Toast.makeText(context, R.string.rule_no_captured_messages, Toast.LENGTH_SHORT).show()
            return
        }

        val draftRule = buildDraft()
        val declaredGroups = namedGroupNames(draftRule.pattern)
        val engine = RuleEngine(listOf(draftRule), CurrenciesDb.getInstance(context).symbolToCodeMap())
        val matches = mutableListOf<Pair<CapturedMessage, Map<String, String>>>()
        var scanned = 0
        for (message in messages) {
            scanned++
            val outcome = engine.match(message)
            if (outcome is ParseOutcome.Matched) matches.add(message to outcome.groups)
            if (matches.size >= MAX_TEST_MATCHES) break
        }

        // Shows what the pattern actually captured for each match - amount/account/etc are
        // derived from rule config that may not be filled in yet, which isn't what "does my
        // regex work" is asking. Same bordered-card rows as the message/pattern pickers (title
        // bold, captured groups normal weight below it) instead of one long message string with
        // "──────────" text separators between matches.
        if (matches.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle(R.string.rule_test_title)
                .setMessage(getString(R.string.rule_test_no_matches, scanned))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        // AlertDialog.Builder's setMessage + setAdapter don't actually combine - the adapter's
        // list silently doesn't render once a message is also set (found the hard way: v0.26
        // shipped with the summary line showing and zero match rows below it). Built as a plain
        // custom view instead - a summary TextView, then one inflated item_picker_two_line row
        // per match - since MAX_TEST_MATCHES keeps the list short enough that ListView's
        // recycling has no value here anyway.
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_test_results, null, false)
        val container = view.findViewById<ViewGroup>(R.id.testResultsContainer)
        container.addView(
            TextView(context).apply { text = getString(R.string.rule_test_matches_found, matches.size, scanned) },
        )
        matches.forEach { (message, groups) ->
            val capturedGroups = declaredGroups.mapNotNull { name -> groups[name]?.let { "$name: $it" } }
            val row = LayoutInflater.from(context).inflate(R.layout.item_picker_two_line, container, false)
            bindPickerItemRow(row, message.title, capturedGroups.joinToString("\n"), monospaceBody = false)
            container.addView(row)
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.rule_test_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showPatternPicker() {
        val context = requireContext()
        val saved = rulesDb.distinctPatterns()
        // The illustrative example is always offered too, not just when nothing's saved yet -
        // it's a handy syntax reminder regardless of how many real rules already exist.
        // distinctBy guards against listing it twice if a saved regex happens to match it
        // verbatim.
        val regexes = (saved + (EXAMPLE_PATTERN to getString(R.string.rule_pattern_example_label)))
            .distinctBy { it.first }
        // Full regex shown right here in the list, label above it - not truncated to one line,
        // since a cut-off regex is too easy to misread (and previously looked like a rendering
        // glitch). Seeing it in full here, before tapping, is the "shown before applying" step -
        // no separate confirm dialog needed on top of that. Monospace matches the pattern field
        // itself, same reasoning as that field's own font choice.
        val adapter = PickerItemAdapter(
            context,
            regexes.map { (regex, label) -> label to regex },
            monospaceBody = true,
        )

        AlertDialog.Builder(context)
            .setTitle(R.string.rule_pick_existing_pattern)
            .setAdapter(adapter) { _, which -> binding?.patternInput?.setText(regexes[which].first) }
            .show()
    }

    private fun buildDraft(): ParseRule {
        val binding = requireBinding()
        return ParseRule(
            id = existingRule?.id ?: "",
            label = binding.labelInput.text?.toString().orEmpty().trim(),
            packageNames = selectedPackageName?.let { setOf(it) } ?: emptySet(),
            kind = selectedKind,
            sourceAccountName = binding.sourceAccountInput.text?.toString().orEmpty().trim(),
            destinationAccountName = binding.destinationAccountInput.text?.toString().orEmpty().trim(),
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
        private const val MAX_TEST_MATCHES = 3

        // Always offered in the pattern picker, alongside any saved patterns - just enough to
        // demonstrate the named-group syntax, not meant to match anything real.
        private const val EXAMPLE_PATTERN =
            """(?<amount>[\d.,]+)\s*(?<currency>[A-Z]{3}|[€£$]).*?(?:at|bei)\s+(?<counterparty>.+?)\.?$"""

        fun newInstance(ruleId: String?): RuleEditFragment = RuleEditFragment().apply {
            arguments = Bundle().apply { putString(ARG_RULE_ID, ruleId) }
        }
    }
}

/**
 * Shared two-line, bordered row for both picker dialogs (existing regex / captured message) -
 * `item_picker_two_line.xml`'s bold header + normal-or-monospace body, instead of plain
 * `setItems` text that gave no visual separation between a row's two lines or between rows.
 */
private class PickerItemAdapter(
    context: Context,
    private val items: List<Pair<String, String>>,
    private val monospaceBody: Boolean,
) : ArrayAdapter<Pair<String, String>>(context, 0, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_picker_two_line, parent, false)
        val (header, body) = items[position]
        bindPickerItemRow(view, header, body, monospaceBody)
        return view
    }
}

/** Shared by [PickerItemAdapter] and the "Test results" dialog's static rows. */
private fun bindPickerItemRow(view: View, header: String, body: String, monospaceBody: Boolean) {
    view.findViewById<TextView>(R.id.pickerItemHeader).text = header
    view.findViewById<TextView>(R.id.pickerItemBody).apply {
        text = body
        typeface = if (monospaceBody) Typeface.MONOSPACE else Typeface.DEFAULT
    }
}
