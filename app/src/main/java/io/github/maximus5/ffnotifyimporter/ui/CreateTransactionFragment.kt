package io.github.maximus5.ffnotifyimporter.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.databinding.FragmentCreateTransactionBinding
import io.github.maximus5.ffnotifyimporter.network.ExchangeRateClient
import io.github.maximus5.ffnotifyimporter.network.FireflyAccount
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.network.FireflyCategory
import io.github.maximus5.ffnotifyimporter.network.FireflyCurrency
import io.github.maximus5.ffnotifyimporter.network.FireflyTransactionRequest
import io.github.maximus5.ffnotifyimporter.posting.DuplicateCheckResult
import io.github.maximus5.ffnotifyimporter.posting.LinkedTransactionCheck
import io.github.maximus5.ffnotifyimporter.posting.ReviewNotifications
import io.github.maximus5.ffnotifyimporter.posting.checkForDuplicate
import io.github.maximus5.ffnotifyimporter.posting.matchMessage
import io.github.maximus5.ffnotifyimporter.posting.verifyLinkedTransaction
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionsDb
import io.github.maximus5.ffnotifyimporter.rules.DraftIssue
import io.github.maximus5.ffnotifyimporter.rules.ParseOutcome
import io.github.maximus5.ffnotifyimporter.rules.RemoteTransactionCandidate
import io.github.maximus5.ffnotifyimporter.rules.RuleEngine
import io.github.maximus5.ffnotifyimporter.rules.RuleKind
import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import io.github.maximus5.ffnotifyimporter.rules.applyApproxConversion
import io.github.maximus5.ffnotifyimporter.rules.transactionDedupHash
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Pre-create review/edit screen - two modes, same fields/layout, decided by which
 * `CreateTransactionActivity` factory built the launching `Intent` (see its own KDoc):
 *
 * - **Create**: replaces what used to be `MessagesFragment.showTestMatch`'s read-only test-match
 *   dialog for a *fresh* match, per the user's own request (see TODO.md's dedup history) - every
 *   field editable before posting, re-parsed via [RuleEngine.match] the same way
 *   `posting.AutoCreateWorker` reconstructs a draft from `WorkManager.Data` (plain fields across
 *   the Activity boundary, not a serialized `TransactionDraft`). [kind] isn't a field you set
 *   directly here - both account fields are always pickers in this mode
 *   ([enableAccountPickersForCreate]), and whichever one(s) actually resolve to a real asset
 *   account determines it ([inferKindFromAccounts]) - a plain non-interactive label, reverted
 *   from an earlier tap-to-pick design the user judged unintuitive once it existed on a real
 *   screen.
 * - **Edit**: the user's own request - editing a transaction Firefly already has, fields
 *   populated from its *real* current data (`posting.verifyLinkedTransaction`), not a rule-derived
 *   guess, and saved via `FireflyApiClient.updateTransaction` (`PUT`) instead of `createTransaction`
 *   (`POST`) - deliberately a genuine *partial* update (see that function's own KDoc): this screen
 *   only ever sends the fields it exposes, so anything else already on the transaction (budget,
 *   piggy bank, SEPA fields, reconciliation state, ...) is left exactly as it was. No duplicate
 *   check before saving - unlike create, there's no "might this already exist" question here, the
 *   whole point is editing a transaction that's already confirmed to exist.
 *
 * Category in particular picked from Firefly's own real category list
 * ([FireflyApiClient.listCategories]) instead of only ever being free text via `ParseRule.
 * categoryTemplate` - visual style (end-icon picker on a [TextInputLayout], cached-per-screen
 * account/category list) is deliberately the same shape as [RuleEditFragment]'s own asset-account
 * picker, the user's own suggested starting point.
 */
class CreateTransactionFragment : Fragment() {

    private var binding: FragmentCreateTransactionBinding? = null
    private lateinit var message: CapturedMessage
    private lateinit var kind: RuleKind
    private var isEditMode = false
    private var selectedTimestampMillis: Long = 0L

    private data class PickedAccount(val id: String, val name: String)
    private var sourceAccountPick: PickedAccount? = null
    private var destinationAccountPick: PickedAccount? = null

    /** Fetched once per screen session on first use - same reasoning as `RuleEditFragment.
     * assetAccounts`: a personal Firefly instance's account/category list changes rarely enough
     * that a stale cache for the rest of this one screen is an acceptable trade for not hitting
     * the network on every picker tap. */
    private var assetAccounts: List<FireflyAccount>? = null
    private var categories: List<FireflyCategory>? = null
    private var enabledCurrencies: List<FireflyCurrency>? = null

    private var saveMenu: SaveMenuHandle? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentCreateTransactionBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        val context = requireContext()

        message = CreateTransactionActivity.messageFrom(requireActivity().intent)
        binding.transactionDateInput.setOnClickListener { showDatePicker() }
        binding.transactionTimeInput.setOnClickListener { showTimePicker() }
        binding.categoryLayout.setEndIconOnClickListener { showCategoryPicker(binding.categoryInput) }
        binding.currencyCodeLayout.setEndIconOnClickListener { showCurrencyPicker(binding.currencyCodeInput) }
        binding.foreignCurrencyCodeLayout.setEndIconOnClickListener { showCurrencyPicker(binding.foreignCurrencyCodeInput) }

        // A no-op in edit mode (inferKindFromAccounts returns immediately there) - registered
        // unconditionally rather than only in create mode, since which mode this is isn't known
        // yet this early in onViewCreated (loadForCreate/loadForEdit haven't run).
        binding.sourceAccountInput.addTextChangedListener(afterTextChanged = { inferKindFromAccounts() })
        binding.destinationAccountInput.addTextChangedListener(afterTextChanged = { inferKindFromAccounts() })

        val editId = CreateTransactionActivity.editTransactionIdFrom(requireActivity().intent)
        saveMenu = addSaveMenu(onSave = { if (editId != null) onSaveClicked(editId) else onCreateClicked() }).also {
            it.label = getString(if (editId != null) R.string.create_tx_save else R.string.create_tx_create)
            it.enabled = false
        }
        if (editId != null) {
            loadForEdit(context, editId)
        } else {
            loadForCreate(context)
        }
    }

    /** Launched on `Dispatchers.Main.immediate`, so the form still fills in synchronously unless
     * [matchMessage] has to wait on a Firefly currency-symbol lookup (an unknown symbol only). */
    private fun loadForCreate(context: Context) {
        viewLifecycleOwner.lifecycleScope.launch {
            val matched = matchMessage(context, message) as? ParseOutcome.Matched
            showCreateForm(context, matched)
        }
    }

    private fun showCreateForm(context: Context, matched: ParseOutcome.Matched?) {
        val binding = binding ?: return
        val fireflyPrefs = FireflyPrefs(context)
        if (matched == null) {
            // Shouldn't happen in practice - this screen is only ever launched right after a
            // successful match - but the message could in theory have been edited away from under
            // it (it can't be, there's no edit feature for the *notification* itself) or a rule
            // deleted/disabled in between.
            Toast.makeText(context, R.string.create_tx_no_rule_matched, Toast.LENGTH_SHORT).show()
            requireActivity().finish()
            return
        }
        val draft = matched.draft

        // Create mode only - edit mode has no "which rule matched" concept at all, the fields
        // come from Firefly's own live data instead of a fresh RuleEngine.match (see this
        // class's own KDoc). The user's own request, so it's clear at a glance which rule fired
        // before trusting (or correcting) what it filled in.
        binding.ruleLabelText.text = boldLabel(getString(R.string.create_tx_rule_prefix), matched.rule.label)
        binding.ruleLabelText.visibility = View.VISIBLE

        kind = draft.kind
        populateFields(draft)
        enableAccountPickersForCreate()
        prefillDefaultSourceAccountIfBothBlank(draft, fireflyPrefs)
        updateKindText()

        // Best-effort, silent - [inferKindFromAccounts] can already resolve kind from whichever
        // side the user explicitly *picks* via the dropdown without this (the picked account is
        // self-evidently an asset account), but resolving it from *typed* text (an account name
        // the rule's own template already filled in, or the user types by hand without ever
        // opening the picker) needs the list loaded first. A failure here just means that
        // specific convenience doesn't kick in until the user taps a picker icon once - not worth
        // a retry loop or an error toast for a background prefetch the user never asked for.
        prefetchAssetAccountsForInference(context)

        // The user's own follow-up catch: falling through here because a previously-linked id
        // was just confirmed gone (MessagesFragment.verifyLinkedThenShow) used to show no warning
        // at all - silently dropping the fact that something *had* been linked, instead of saying
        // so. "Already linked" itself is gone as a concept here - that case now opens the editor
        // directly instead (see CreateTransactionActivity's own KDoc) - so this banner only ever
        // has one thing left to say.
        val staleLinkedId = CreateTransactionActivity.staleLinkedIdFrom(requireActivity().intent)
        if (staleLinkedId != null) {
            binding.alreadyLinkedWarningText.text = getString(R.string.create_tx_stale_linked_warning, staleLinkedId)
            binding.alreadyLinkedWarningText.visibility = View.VISIBLE
        }

        saveMenu?.enabled = true

        if (DraftIssue.LOCAL_AMOUNT_MISSING in draft.issues) {
            viewLifecycleOwner.lifecycleScope.launch { resolveApproxConversion(draft) }
        }
    }

    /** Edit mode - the user's own request: tapping an already-linked message with a *live* id
     * opens straight in here instead of the old read-only "Open Transaction"/"Create Again" info
     * dialog. Re-verifies [transactionId] itself rather than trusting the caller's own earlier
     * check (`MessagesFragment.verifyLinkedThenShow`) - see `CreateTransactionActivity.
     * editIntent`'s KDoc for why that's not redundant: it closes the race where the transaction
     * gets deleted in the gap between the list-row tap and this screen's own network round-trip.
     * Confirmed gone or a check failure both just back out - nothing to edit either way, and
     * `CreateTransactionActivity.intent` (the fresh-match path) isn't the right fallback either,
     * since there's no [TransactionDraft] here to fall back to showing. */
    private fun loadForEdit(context: Context, transactionId: String) {
        isEditMode = true
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = verifyLinkedTransaction(FireflyPrefs(context), transactionId)) {
                is LinkedTransactionCheck.Found -> {
                    val binding = binding ?: return@launch
                    kind = result.candidate.kind
                    populateFieldsFromCandidate(result.candidate)
                    updateAccountFieldModes()
                    updateKindText()
                    saveMenu?.enabled = true
                }
                is LinkedTransactionCheck.Gone -> {
                    Toast.makeText(context, getString(R.string.open_transaction_gone_message, transactionId), Toast.LENGTH_LONG).show()
                    requireActivity().finish()
                }
                is LinkedTransactionCheck.CheckFailed -> {
                    AlertDialog.Builder(context)
                        .setTitle(R.string.check_search_failed_title)
                        .setMessage(result.message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> requireActivity().finish() }
                        .show()
                }
            }
        }
    }

    private fun populateFields(draft: TransactionDraft) {
        val binding = binding ?: return
        val context = requireContext()

        binding.amountInput.setText(draft.amount?.let { formatAmountForDisplay(it) }.orEmpty())
        binding.currencyCodeInput.setText(draft.currencyCode)
        binding.foreignAmountInput.setText(draft.foreignAmount?.let { formatAmountForDisplay(it) }.orEmpty())
        binding.foreignCurrencyCodeInput.setText(draft.foreignCurrencyCode.orEmpty())

        selectedTimestampMillis = draft.date.toEpochMilli()
        updateDateTimeFields(context)

        binding.sourceAccountInput.setText(draft.sourceName.orEmpty())
        binding.destinationAccountInput.setText(draft.destinationName.orEmpty())
        sourceAccountPick = draft.sourceAccountId?.let { PickedAccount(it, draft.sourceName.orEmpty()) }
        destinationAccountPick = draft.destinationAccountId?.let { PickedAccount(it, draft.destinationName.orEmpty()) }

        binding.descriptionInput.setText(draft.description)
        binding.notesInput.setText(draft.notes)
        binding.categoryInput.setText(draft.category.orEmpty())
        binding.tagsInput.setText(draft.tags.joinToString(", "))
    }

    /** Edit mode's counterpart to [populateFields] - same fields, sourced from a live-verified
     * [RemoteTransactionCandidate] instead of a freshly re-parsed [TransactionDraft]. Kept as its
     * own function rather than unifying the two behind a shared interface - the two source types
     * (`TransactionDraft` vs `RemoteTransactionCandidate`) differ enough (nullable amount/kind-as-
     * enum vs not) that forcing one shape on both would cost more than the few duplicated lines
     * save. [formatAmountForDisplay] matters more here than in [populateFields] - Firefly's own
     * `amount`/`foreign_amount` JSON fields come back at whatever scale its `DECIMAL(32,12)`
     * column happens to store (the user's own catch/screenshot: an edit screen showing
     * "61.160000000000" instead of "61.16"), unlike a freshly-parsed draft's amount, which is
     * already clean from the notification text. */
    private fun populateFieldsFromCandidate(candidate: RemoteTransactionCandidate) {
        val binding = binding ?: return
        val context = requireContext()

        binding.amountInput.setText(formatAmountForDisplay(candidate.amount))
        binding.currencyCodeInput.setText(candidate.currencyCode)
        binding.foreignAmountInput.setText(candidate.foreignAmount?.let { formatAmountForDisplay(it) }.orEmpty())
        binding.foreignCurrencyCodeInput.setText(candidate.foreignCurrencyCode.orEmpty())

        selectedTimestampMillis = candidate.date.toEpochMilli()
        updateDateTimeFields(context)

        binding.sourceAccountInput.setText(candidate.sourceName.orEmpty())
        binding.destinationAccountInput.setText(candidate.destinationName.orEmpty())
        sourceAccountPick = candidate.sourceId?.let { PickedAccount(it, candidate.sourceName.orEmpty()) }
        destinationAccountPick = candidate.destinationId?.let { PickedAccount(it, candidate.destinationName.orEmpty()) }

        binding.descriptionInput.setText(candidate.description)
        binding.notesInput.setText(candidate.notes.orEmpty())
        binding.categoryInput.setText(candidate.category.orEmpty())
        binding.tagsInput.setText(candidate.tags.joinToString(", "))
    }

    private fun updateKindText() {
        val binding = binding ?: return
        binding.kindText.text = SpannableStringBuilder()
            .append(boldLabel(getString(R.string.create_tx_kind_prefix), getString(kind.labelRes())))
            .append(" · ")
            .append(boldLabel(getString(R.string.create_tx_app_prefix), message.appLabel))
    }

    private fun boldLabel(label: String, value: String) = SpannableStringBuilder().apply {
        append(label)
        setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(" ").append(value)
    }

    /** Edit mode only - `kind` is fixed to the real transaction's own type and never recomputed
     * (the user's own explicit call: changing kind on a transaction Firefly already has isn't
     * supported), so only the one side (or both, for TRANSFER) that's actually the asset side
     * gets the account picker (`FireflyApiClient.listAssetAccounts`, same end-icon shape as
     * `RuleEditFragment`'s) - the counterparty side Firefly auto-creates by name, so it's always
     * plain free text here, no picker. */
    private fun updateAccountFieldModes() {
        val binding = binding ?: return
        val (sourceIsAsset, destinationIsAsset) = when (kind) {
            RuleKind.WITHDRAWAL -> true to false
            RuleKind.DEPOSIT -> false to true
            RuleKind.TRANSFER -> true to true
        }
        setAccountFieldMode(binding.sourceAccountLayout, binding.sourceAccountInput, sourceIsAsset) { sourceAccountPick = it }
        setAccountFieldMode(binding.destinationAccountLayout, binding.destinationAccountInput, destinationIsAsset) { destinationAccountPick = it }
    }

    /** Create mode only: both sides always offer the account picker here, unlike edit mode's
     * [updateAccountFieldModes] - `kind` isn't fixed yet in create mode, so there's no "the" asset
     * side to single out in advance. It's the other way around: whichever account(s) the user
     * actually ends up filling in with a real asset account name (picked or typed) IS what
     * determines `kind` ([inferKindFromAccounts]), the user's own explicit design - a plain,
     * non-interactive `kindText` label (reverted from an earlier tap-to-pick design the user
     * judged unintuitive) just reflects whatever that resolves to. */
    private fun enableAccountPickersForCreate() {
        val binding = binding ?: return
        setAccountFieldMode(binding.sourceAccountLayout, binding.sourceAccountInput, true) {
            sourceAccountPick = it
            inferKindFromAccounts()
        }
        setAccountFieldMode(binding.destinationAccountLayout, binding.destinationAccountInput, true) {
            destinationAccountPick = it
            inferKindFromAccounts()
        }
    }

    /** Pre-fills [FragmentCreateTransactionBinding.sourceAccountInput] with `FireflyPrefs.
     * getDefaultSourceAccount()` - but **only** when [draft] leaves both account sides blank, the
     * user's own explicit scoping (not "source blank" alone): pre-filling source unconditionally
     * could turn a legitimate DEPOSIT rule's own already-filled destination into a
     * wrongly-inferred TRANSFER once source also looked like a real asset account (see
     * [inferKindFromAccounts]). Deliberately just a text fill, not a full [PickedAccount] - the
     * account-field `TextWatcher` already registered in `onViewCreated` re-runs
     * [inferKindFromAccounts] off this same text change, same as if the user had typed it by hand;
     * no special-casing needed here for that. A no-op when the setting is blank (most users
     * haven't visited the settings screen yet) or the setting is unset. */
    private fun prefillDefaultSourceAccountIfBothBlank(draft: TransactionDraft, fireflyPrefs: FireflyPrefs) {
        val binding = binding ?: return
        if (draft.sourceName != null || draft.destinationName != null) return
        val defaultSourceAccount = fireflyPrefs.getDefaultSourceAccount()
        if (defaultSourceAccount.isBlank()) return
        binding.sourceAccountInput.setText(defaultSourceAccount)
    }

    /** Create mode only (a no-op in edit mode - `kind` is fixed there, never recomputed). Re-runs
     * on every account-field change (the `addTextChangedListener` calls in [onViewCreated], and
     * [enableAccountPickersForCreate]'s own `onPicked` callbacks) to decide which of
     * WITHDRAWAL/DEPOSIT/TRANSFER actually matches what's in the two account fields *right now*:
     * an asset account on the source side only means WITHDRAWAL, destination side only means
     * DEPOSIT, both sides means TRANSFER. Only updates [kind]/the label when that comes out
     * unambiguous - neither side resolving yet (e.g. mid-typing, or a built-in rule match that
     * left both blank) leaves the last-known `kind` showing rather than flickering to an
     * arbitrary default; [buildDraft] re-resolves it one final time at submit and refuses to save
     * if it's still ambiguous then. */
    private fun inferKindFromAccounts() {
        if (isEditMode) return
        val binding = binding ?: return
        val sourceIsAsset = isAssetAccountName(binding.sourceAccountInput.text?.toString().orEmpty(), sourceAccountPick)
        val destinationIsAsset = isAssetAccountName(binding.destinationAccountInput.text?.toString().orEmpty(), destinationAccountPick)
        val inferred = resolveKindFromAssetSides(sourceIsAsset, destinationIsAsset) ?: return
        if (inferred != kind) {
            kind = inferred
            updateKindText()
        }
    }

    private fun resolveKindFromAssetSides(sourceIsAsset: Boolean, destinationIsAsset: Boolean): RuleKind? = when {
        sourceIsAsset && destinationIsAsset -> RuleKind.TRANSFER
        sourceIsAsset -> RuleKind.WITHDRAWAL
        destinationIsAsset -> RuleKind.DEPOSIT
        else -> null
    }

    /** True once [text] (trimmed) is known to name a real Firefly asset account - either because
     * it's exactly what [pick] (set by [enableAccountPickersForCreate]'s own `onPicked`) already
     * names, which is self-evidently true regardless of whether [assetAccounts] has loaded yet
     * (the picker dialog it came from is itself built from [FireflyApiClient.listAssetAccounts]),
     * or because it matches a name in the cached list - which only ever covers free-typed text,
     * not a pick. */
    private fun isAssetAccountName(text: String, pick: PickedAccount?): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (pick?.name == trimmed) return true
        return assetAccounts?.any { it.name == trimmed } == true
    }

    /** Create mode only - loads [assetAccounts] right away instead of waiting for the account
     * picker's own first tap (its usual lazy-fetch-on-first-use shape, still there as a fallback
     * for when this fails), purely so [isAssetAccountName] can resolve *typed* account names
     * (not just picked ones) without the user ever having to open a picker dialog once. Silent on
     * failure - see the call site's own comment for why. */
    private fun prefetchAssetAccountsForInference(context: Context) {
        if (assetAccounts != null) return
        val prefs = FireflyPrefs(context)
        if (!prefs.isConfigured()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = FireflyApiClient(prefs.getBaseUrl(), prefs.getToken()).listAssetAccounts()
            if (result is FireflyApiClient.Result.Success) {
                assetAccounts = result.value
                inferKindFromAccounts()
            }
        }
    }

    private fun setAccountFieldMode(layout: TextInputLayout, input: EditText, isAsset: Boolean, onPicked: (PickedAccount) -> Unit) {
        if (!isAsset) {
            layout.endIconMode = TextInputLayout.END_ICON_NONE
            return
        }
        layout.endIconMode = TextInputLayout.END_ICON_CUSTOM
        layout.setEndIconDrawable(R.drawable.ic_dropdown)
        layout.endIconContentDescription = getString(R.string.rule_pick_account)
        layout.setEndIconOnClickListener { showAccountPicker(input, onPicked) }
    }

    private fun showAccountPicker(target: EditText, onPicked: (PickedAccount) -> Unit) {
        val context = requireContext()
        val prefs = FireflyPrefs(context)
        if (!prefs.isConfigured()) {
            Toast.makeText(context, R.string.rule_firefly_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        val cached = assetAccounts
        if (cached != null) {
            showAccountPickerDialog(target, cached, onPicked)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = FireflyApiClient(prefs.getBaseUrl(), prefs.getToken()).listAssetAccounts()) {
                is FireflyApiClient.Result.Success -> {
                    assetAccounts = result.value
                    showAccountPickerDialog(target, result.value, onPicked)
                }
                is FireflyApiClient.Result.Failure ->
                    Toast.makeText(context, getString(R.string.rule_firefly_accounts_failed, result.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAccountPickerDialog(target: EditText, accounts: List<FireflyAccount>, onPicked: (PickedAccount) -> Unit) {
        if (accounts.isEmpty()) {
            Toast.makeText(requireContext(), R.string.rule_firefly_no_accounts, Toast.LENGTH_LONG).show()
            return
        }
        val currentName = target.text?.toString()?.trim()
        val selectedIndex = accounts.indexOfFirst { it.name == currentName }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rule_pick_account)
            .setSingleChoiceItems(accounts.map { it.name }.toTypedArray(), selectedIndex) { dialog, which ->
                val account = accounts[which]
                target.setText(account.name)
                onPicked(PickedAccount(account.id, account.name))
                dialog.dismiss()
            }
            .show()
    }

    /** No id-staleness tracking needed here the way accounts have it - `FireflyTransactionRequest.
     * categoryName` is the only field Firefly's create/update-transaction endpoints accept for
     * category (no `category_id`, confirmed against `buildTransactionSplit`), so picking just
     * fills in a real name, nothing more to reconcile at build time. */
    private fun showCategoryPicker(target: EditText) {
        val context = requireContext()
        val prefs = FireflyPrefs(context)
        if (!prefs.isConfigured()) {
            Toast.makeText(context, R.string.rule_firefly_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        val cached = categories
        if (cached != null) {
            showCategoryPickerDialog(target, cached)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = FireflyApiClient(prefs.getBaseUrl(), prefs.getToken()).listCategories()) {
                is FireflyApiClient.Result.Success -> {
                    categories = result.value
                    showCategoryPickerDialog(target, result.value)
                }
                is FireflyApiClient.Result.Failure ->
                    Toast.makeText(context, getString(R.string.create_tx_categories_failed, result.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showCategoryPickerDialog(target: EditText, categories: List<FireflyCategory>) {
        if (categories.isEmpty()) {
            Toast.makeText(requireContext(), R.string.create_tx_no_categories, Toast.LENGTH_LONG).show()
            return
        }
        val currentName = target.text?.toString()?.trim()
        val selectedIndex = categories.indexOfFirst { it.name == currentName }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.create_tx_pick_category)
            .setSingleChoiceItems(categories.map { it.name }.toTypedArray(), selectedIndex) { dialog, which ->
                target.setText(categories[which].name)
                dialog.dismiss()
            }
            .show()
    }

    /** Long date format (`DateFormat.getLongDateFormat`, e.g. "October 6, 2026" in en, "6 октября
     * 2026 г." in ru - locale-ordered and -translated, same system API `MessagesAdapter`'s own
     * date column already uses for its short form) plus a short weekday name - the user's own
     * request, once this field stopped having to share a row with the time field (see
     * [showDatePicker]'s own KDoc): "there's more room now, so show more". The weekday is its own
     * `SimpleDateFormat("EEE", ...)` call, not part of one combined pattern - `getLongDateFormat`
     * returns a fixed, locale-chosen `java.text.DateFormat` instance with no weekday of its own to
     * extend. */
    private fun updateDateTimeFields(context: Context) {
        val binding = binding ?: return
        val date = Date(selectedTimestampMillis)
        val longDate = DateFormat.getLongDateFormat(context).format(date)
        val weekday = SimpleDateFormat("EEE", Locale.getDefault()).format(date)
        binding.transactionDateInput.setText("$longDate, $weekday")
        binding.transactionTimeInput.setText(DateFormat.getTimeFormat(context).format(date))
    }

    /** Two separate tap-to-open fields, not one combined button - the user's own call, after
     * judging the old single borderless `Button` ("12/31/25 11:59 PM") visually inconsistent
     * with every other field on this screen being a boxed `TextInputLayout`. Each field only ever
     * changes its own half of [selectedTimestampMillis] - picking a new date keeps today's
     * already-chosen time of day, and vice versa, same as the old combined picker already did via
     * its own `calendar` carrying both forward between the two dialogs. Not focusable/no
     * keyboard (`android:focusable="false"`/`inputType="none"` in the layout) - this is a picker
     * trigger, not free text, same convention `kindText` already uses for its own tap-to-open
     * `AlertDialog`. */
    private fun showDatePicker() {
        val context = requireContext()
        val calendar = Calendar.getInstance().apply { timeInMillis = selectedTimestampMillis }
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                selectedTimestampMillis = calendar.timeInMillis
                updateDateTimeFields(context)
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
                updateDateTimeFields(context)
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            DateFormat.is24HourFormat(context),
        ).show()
    }

    /** Both currency fields (home and foreign) pick from the currencies actually *enabled* in the
     * user's own Firefly instance - the user's own request, same data source and caching shape
     * `TransactionCreationFragment.showCurrencyPicker`'s "Default account currency" picker already
     * uses (`FireflyApiClient.listCurrencies`, client-side filtered to `enabled`). Both fields stay
     * freely typeable too, same as every other Firefly-sourced picker in this app - the picker is
     * a shortcut, not a lock. */
    private fun showCurrencyPicker(target: EditText) {
        val context = requireContext()
        val prefs = FireflyPrefs(context)
        if (!prefs.isConfigured()) {
            Toast.makeText(context, R.string.rule_firefly_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        val cached = enabledCurrencies
        if (cached != null) {
            showCurrencyPickerDialog(target, cached)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = FireflyApiClient(prefs.getBaseUrl(), prefs.getToken()).listCurrencies()) {
                is FireflyApiClient.Result.Success -> {
                    val enabled = result.value.filter { it.enabled }
                    enabledCurrencies = enabled
                    showCurrencyPickerDialog(target, enabled)
                }
                is FireflyApiClient.Result.Failure ->
                    Toast.makeText(context, getString(R.string.firefly_pick_currency_failed, result.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showCurrencyPickerDialog(target: EditText, currencies: List<FireflyCurrency>) {
        if (currencies.isEmpty()) {
            Toast.makeText(requireContext(), R.string.firefly_no_currencies, Toast.LENGTH_LONG).show()
            return
        }
        val currentCode = target.text?.toString()?.trim()
        val selectedIndex = currencies.indexOfFirst { it.code == currentCode }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rule_pick_currency)
            .setSingleChoiceItems(currencies.map { it.code }.toTypedArray(), selectedIndex) { dialog, which ->
                target.setText(currencies[which].code)
                dialog.dismiss()
            }
            .show()
    }

    /** Same exchange-rate resolution `MessagesFragment.resolveAmountQuietly` does - but only fills
     * [FragmentCreateTransactionBinding.amountInput] if it's still blank once the fetch resolves,
     * not unconditionally: the user may already have typed an amount by hand while the async fetch
     * was in flight, and that shouldn't get silently overwritten. Create mode only - edit mode
     * always has a real amount already, nothing to resolve. */
    private suspend fun resolveApproxConversion(draft: TransactionDraft) {
        val foreignCurrency = draft.foreignCurrencyCode ?: return
        val context = requireContext()
        val result = ExchangeRateClient().fetchRate(foreignCurrency, draft.currencyCode, draft.date)
        val binding = binding ?: return
        if (result !is ExchangeRateClient.Result.Success) return
        if (!binding.amountInput.text.isNullOrBlank()) return
        val approxTag = FireflyPrefs(context).getApproxConversionTag()
        val updated = applyApproxConversion(draft, result.rate, approxTag)
        binding.amountInput.setText(updated.amount?.toPlainString().orEmpty())
        if (approxTag.isNotBlank() && binding.tagsInput.text.isNullOrBlank()) {
            binding.tagsInput.setText(approxTag)
        }
    }

    private fun onCreateClicked() {
        val draft = buildDraft() ?: return
        val context = requireContext()

        viewLifecycleOwner.lifecycleScope.launch {
            saveMenu?.enabled = false
            when (val result = checkForDuplicate(context, draft)) {
                is DuplicateCheckResult.None -> post(draft)
                is DuplicateCheckResult.SearchFailed -> {
                    saveMenu?.enabled = true
                    AlertDialog.Builder(context)
                        .setTitle(R.string.check_search_failed_title)
                        .setMessage(result.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                is DuplicateCheckResult.Found -> {
                    saveMenu?.enabled = true
                    confirmCreateAnyway(draft, result.id)
                }
                is DuplicateCheckResult.FoundMultiple -> {
                    saveMenu?.enabled = true
                    Toast.makeText(context, getString(R.string.create_remote_duplicates_found, result.count), Toast.LENGTH_LONG).show()
                    confirmCreateAnyway(draft, result.mostRecentId)
                }
            }
        }
    }

    /** Cancel always rightmost/last (house convention, AGENTS.md) - unlike the rest of this
     * screen (a full form, no on-screen Cancel button, same shape as `RuleEditFragment`), this
     * genuinely is a dialog, so the convention applies here. */
    private fun confirmCreateAnyway(draft: TransactionDraft, foundId: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.create_duplicate_confirm_title)
            .setMessage(getString(R.string.create_duplicate_confirm_message, foundId))
            .setNegativeButton(R.string.create_duplicate_confirm_button) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { post(draft) }
            }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun post(draft: TransactionDraft) {
        val binding = binding ?: return
        val context = requireContext()
        val prefs = FireflyPrefs(context)
        val amount = draft.amount ?: return
        val request = buildTransactionRequest(draft, amount)
        val client = FireflyApiClient(prefs.getBaseUrl(), prefs.getToken())
        when (val result = client.createTransaction(request)) {
            is FireflyApiClient.Result.Success -> onSaveSuccess(context, draft, result.value)
            is FireflyApiClient.Result.Failure -> onSaveFailure(context, result.message)
        }
    }

    /** Edit mode's submit - no duplicate check first, unlike [onCreateClicked]: there's no
     * "might this already exist" question when the whole point is editing a transaction already
     * confirmed to exist. */
    private fun onSaveClicked(transactionId: String) {
        val draft = buildDraft() ?: return
        saveMenu?.enabled = false
        viewLifecycleOwner.lifecycleScope.launch { save(draft, transactionId) }
    }

    private suspend fun save(draft: TransactionDraft, transactionId: String) {
        val binding = binding ?: return
        val context = requireContext()
        val prefs = FireflyPrefs(context)
        val amount = draft.amount ?: return
        val request = buildTransactionRequest(draft, amount)
        val client = FireflyApiClient(prefs.getBaseUrl(), prefs.getToken())
        when (val result = client.updateTransaction(transactionId, request)) {
            is FireflyApiClient.Result.Success -> onSaveSuccess(context, draft, result.value)
            is FireflyApiClient.Result.Failure -> onSaveFailure(context, result.message)
        }
    }

    private fun buildTransactionRequest(draft: TransactionDraft, amount: BigDecimal) = FireflyTransactionRequest(
        type = draft.kind.name.lowercase(),
        dateIso = draft.date.toString(),
        amount = amount.toPlainString(),
        description = draft.description,
        currencyCode = draft.currencyCode,
        sourceId = draft.sourceAccountId,
        sourceName = draft.sourceName,
        destinationId = draft.destinationAccountId,
        destinationName = draft.destinationName,
        categoryName = draft.category,
        tags = draft.tags,
        notes = draft.notes.takeIf { it.isNotBlank() },
        foreignAmount = draft.foreignAmount?.toPlainString(),
        foreignCurrencyCode = draft.foreignCurrencyCode,
    )

    /** Shared by [post] (create) and [save] (edit) - recording the dedup hash and linking the
     * message is identical either way: a successful edit's fields are exactly as likely to need
     * matching against later as a freshly-created one's, so the stored hash gets refreshed here
     * too rather than only ever reflecting the transaction's *original* fields. [ReviewNotifications.
     * cancel] is a no-op when this screen wasn't opened from one (a plain in-app tap, or auto-create
     * on) - see that function's own KDoc. */
    private fun onSaveSuccess(context: Context, draft: TransactionDraft, transactionId: String) {
        CreatedTransactionsDb.getInstance(context).recordCreated(
            hashWithDescription = transactionDedupHash(draft, true),
            hashWithoutDescription = transactionDedupHash(draft, false),
            transactionDateMillis = draft.date.toEpochMilli(),
            fireflyTransactionId = transactionId,
        )
        MessageStore.shared.setTransactionId(message, transactionId)
        ReviewNotifications.cancel(context, message)
        Toast.makeText(context, getString(R.string.create_success_message, transactionId), Toast.LENGTH_LONG).show()
        requireActivity().finish()
    }

    private fun onSaveFailure(context: Context, message: String) {
        saveMenu?.enabled = true
        AlertDialog.Builder(context)
            .setTitle(R.string.create_failed_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Builds the final [TransactionDraft] from the current field values, validating as it goes -
     * [message] carries over from the original match/fetch unchanged. In create mode, [kind] is
     * re-resolved one final time from the account fields right here ([resolveKindFromAssetSides],
     * same as [inferKindFromAccounts]'s own live updates, but refusing to save at all if it's
     * still ambiguous - the user's own explicit call) rather than trusted as whatever
     * [inferKindFromAccounts] last left it showing; in edit mode `kind` is never touched, it stays
     * fixed to the real transaction's own type. Everything else is read fresh from the form.
     * `issues` is always empty - the two [DraftIssue] flags were about automated-parse
     * uncertainty, which no longer applies once a human has directly reviewed/filled in the
     * fields (true in edit mode from the start - there was never a parse to begin with). Returns
     * null (after showing the relevant field's error) on the first validation failure - same
     * "stop at the first problem" shape `FireflyConnectionFragment.save`/
     * `TransactionCreationFragment.save` already use.
     *
     * Errors are shown via [TextInputLayout.setError], not the plain `EditText.setError` this
     * used before - the user's own catch/screenshot: the account fields' own custom end-icon
     * (the dropdown picker) visually clashed with `EditText`'s native error glyph trying to
     * occupy the same spot, and that native error's explanatory tooltip only ever showed once the
     * field had focus, which a button tap elsewhere never gives it - so the two account-field
     * errors landed as two unexplained red icons with nothing else on screen. `TextInputLayout`'s
     * own error instead draws a red-outlined box plus the message as caption text directly below
     * the field, always visible, no focus needed, and it leaves an already-set custom end icon
     * alone rather than fighting it for the same space. Cleared at the top of every call so a
     * fixed field doesn't keep showing a stale error from the previous attempt. */
    private fun buildDraft(): TransactionDraft? {
        val binding = binding ?: return null
        binding.amountLayout.error = null
        binding.descriptionLayout.error = null
        binding.sourceAccountLayout.error = null
        binding.destinationAccountLayout.error = null

        val amountText = binding.amountInput.text?.toString()?.trim().orEmpty()
        val amount = amountText.toBigDecimalOrNull()
        if (amount == null) {
            binding.amountLayout.error = getString(R.string.create_tx_amount_invalid)
            return null
        }

        val description = binding.descriptionInput.text?.toString()?.trim().orEmpty()
        if (description.isBlank()) {
            binding.descriptionLayout.error = getString(R.string.create_tx_description_required)
            return null
        }

        val sourceName = binding.sourceAccountInput.text?.toString()?.trim().orEmpty()
        val destinationName = binding.destinationAccountInput.text?.toString()?.trim().orEmpty()

        if (!isEditMode) {
            val resolved = resolveKindFromAssetSides(
                isAssetAccountName(sourceName, sourceAccountPick),
                isAssetAccountName(destinationName, destinationAccountPick),
            )
            if (resolved == null) {
                binding.sourceAccountLayout.error = getString(R.string.create_tx_no_asset_account_error)
                binding.destinationAccountLayout.error = getString(R.string.create_tx_no_asset_account_error)
                return null
            }
            kind = resolved
            updateKindText()
        }

        if (kind != RuleKind.DEPOSIT && sourceName.isBlank()) {
            binding.sourceAccountLayout.error = getString(R.string.create_tx_source_required)
            return null
        }
        if (kind != RuleKind.WITHDRAWAL && destinationName.isBlank()) {
            binding.destinationAccountLayout.error = getString(R.string.create_tx_destination_required)
            return null
        }

        val currencyCode = binding.currencyCodeInput.text?.toString()?.trim().orEmpty()
        val foreignAmountText = binding.foreignAmountInput.text?.toString()?.trim().orEmpty()
        val foreignAmount = foreignAmountText.toBigDecimalOrNull()
        // The user's own call: a typed-but-then-cleared foreign amount used to leave a stale
        // foreign currency code behind in the saved/created transaction - there's no foreign
        // amount for it to describe any more, so it's cleared alongside it rather than kept as an
        // orphaned field.
        val foreignCurrencyCode = if (foreignAmount == null) {
            null
        } else {
            binding.foreignCurrencyCodeInput.text?.toString()?.trim().orEmpty().takeIf { it.isNotBlank() }
        }
        val tags = binding.tagsInput.text?.toString().orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
        val category = binding.categoryInput.text?.toString()?.trim().orEmpty().takeIf { it.isNotBlank() }

        return TransactionDraft(
            kind = kind,
            date = Instant.ofEpochMilli(selectedTimestampMillis),
            amount = amount,
            currencyCode = currencyCode,
            foreignAmount = foreignAmount,
            foreignCurrencyCode = foreignCurrencyCode,
            sourceName = sourceName.takeIf { it.isNotBlank() },
            sourceAccountId = sourceAccountPick?.takeIf { it.name == sourceName }?.id,
            destinationName = destinationName.takeIf { it.isNotBlank() },
            destinationAccountId = destinationAccountPick?.takeIf { it.name == destinationName }?.id,
            description = description,
            notes = binding.notesInput.text?.toString().orEmpty(),
            category = category,
            tags = tags,
            issues = emptySet(),
        )
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? = try {
        BigDecimal(this).setScale(2, RoundingMode.HALF_UP)
    } catch (e: NumberFormatException) {
        null
    } catch (e: ArithmeticException) {
        null
    }

    /** The user's own catch/screenshot: Firefly's `amount`/`foreign_amount` JSON fields come back
     * at whatever scale its `DECIMAL(32,12)` column stores, e.g. "61.160000000000" - correct, but
     * unreadable dropped straight into a field meant for typing. Strips trailing zeros, then pads
     * back up to 2 decimal places if stripping went below that (cents, what most currencies use) -
     * "61.160000000000" -> "61.16", "100.000000000000" -> "100.00", a genuinely 3-decimal amount
     * stays 3-decimal rather than getting rounded down to 2. */
    private fun formatAmountForDisplay(amount: BigDecimal): String {
        val stripped = amount.stripTrailingZeros()
        return (if (stripped.scale() < 2) stripped.setScale(2) else stripped).toPlainString()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
