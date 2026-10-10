package io.github.maximus5.ffnotifyimporter.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import io.github.maximus5.ffnotifyimporter.network.FireflyTransactionRequest
import io.github.maximus5.ffnotifyimporter.posting.DuplicateCheckResult
import io.github.maximus5.ffnotifyimporter.posting.LinkedTransactionCheck
import io.github.maximus5.ffnotifyimporter.posting.ReviewNotifications
import io.github.maximus5.ffnotifyimporter.posting.checkForDuplicate
import io.github.maximus5.ffnotifyimporter.posting.verifyLinkedTransaction
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionsDb
import io.github.maximus5.ffnotifyimporter.rules.CurrenciesDb
import io.github.maximus5.ffnotifyimporter.rules.DraftIssue
import io.github.maximus5.ffnotifyimporter.rules.ParseOutcome
import io.github.maximus5.ffnotifyimporter.rules.RemoteTransactionCandidate
import io.github.maximus5.ffnotifyimporter.rules.RuleEngine
import io.github.maximus5.ffnotifyimporter.rules.RuleKind
import io.github.maximus5.ffnotifyimporter.rules.RulesDb
import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import io.github.maximus5.ffnotifyimporter.rules.applyApproxConversion
import io.github.maximus5.ffnotifyimporter.rules.transactionDedupHash
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Calendar
import java.util.Date

/**
 * Pre-create review/edit screen - two modes, same fields/layout, decided by which
 * `CreateTransactionActivity` factory built the launching `Intent` (see its own KDoc):
 *
 * - **Create**: replaces what used to be `MessagesFragment.showTestMatch`'s read-only test-match
 *   dialog for a *fresh* match, per the user's own request (see TODO.md's dedup history) - every
 *   field editable before posting, re-parsed via [RuleEngine.match] the same way
 *   `posting.AutoCreateWorker` reconstructs a draft from `WorkManager.Data` (plain fields across
 *   the Activity boundary, not a serialized `TransactionDraft`).
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
        binding.dateTimeButton.setOnClickListener { showDateTimePicker() }
        binding.categoryLayout.setEndIconOnClickListener { showCategoryPicker(binding.categoryInput) }

        val editId = CreateTransactionActivity.editTransactionIdFrom(requireActivity().intent)
        if (editId != null) {
            loadForEdit(context, editId)
        } else {
            loadForCreate(context)
        }
    }

    private fun loadForCreate(context: Context) {
        val binding = binding ?: return
        val rules = RulesDb.getInstance(context).loadAllOrdered()
        val currencySymbols = CurrenciesDb.getInstance(context).symbolToCodeMap()
        val outcome = RuleEngine(rules, currencySymbols).match(message)
        val draft = (outcome as? ParseOutcome.Matched)?.draft
        if (draft == null) {
            // Shouldn't happen in practice - this screen is only ever launched right after a
            // successful match - but the message could in theory have been edited away from under
            // it (it can't be, there's no edit feature for the *notification* itself) or a rule
            // deleted/disabled in between.
            Toast.makeText(context, R.string.create_tx_no_rule_matched, Toast.LENGTH_SHORT).show()
            requireActivity().finish()
            return
        }

        kind = draft.kind
        populateFields(draft)
        updateAccountFieldModes()

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

        binding.createTransactionButton.text = getString(R.string.test_match_create)
        binding.createTransactionButton.setOnClickListener { onCreateClicked() }

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
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = verifyLinkedTransaction(FireflyPrefs(context), transactionId)) {
                is LinkedTransactionCheck.Found -> {
                    val binding = binding ?: return@launch
                    kind = result.candidate.kind
                    populateFieldsFromCandidate(result.candidate)
                    updateAccountFieldModes()
                    binding.createTransactionButton.text = getString(R.string.create_tx_save)
                    binding.createTransactionButton.setOnClickListener { onSaveClicked(transactionId) }
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

        binding.kindText.text = getString(
            when (kind) {
                RuleKind.WITHDRAWAL -> R.string.rule_kind_withdrawal
                RuleKind.DEPOSIT -> R.string.rule_kind_deposit
                RuleKind.TRANSFER -> R.string.rule_kind_transfer
            },
        ) + " · " + message.appLabel

        binding.amountInput.setText(draft.amount?.let { formatAmountForDisplay(it) }.orEmpty())
        binding.currencyCodeInput.setText(draft.currencyCode)
        binding.foreignAmountInput.setText(draft.foreignAmount?.let { formatAmountForDisplay(it) }.orEmpty())
        binding.foreignCurrencyCodeInput.setText(draft.foreignCurrencyCode.orEmpty())

        selectedTimestampMillis = draft.date.toEpochMilli()
        updateDateTimeButtonText(context)

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

        binding.kindText.text = getString(
            when (kind) {
                RuleKind.WITHDRAWAL -> R.string.rule_kind_withdrawal
                RuleKind.DEPOSIT -> R.string.rule_kind_deposit
                RuleKind.TRANSFER -> R.string.rule_kind_transfer
            },
        ) + " · " + message.appLabel

        binding.amountInput.setText(formatAmountForDisplay(candidate.amount))
        binding.currencyCodeInput.setText(candidate.currencyCode)
        binding.foreignAmountInput.setText(candidate.foreignAmount?.let { formatAmountForDisplay(it) }.orEmpty())
        binding.foreignCurrencyCodeInput.setText(candidate.foreignCurrencyCode.orEmpty())

        selectedTimestampMillis = candidate.date.toEpochMilli()
        updateDateTimeButtonText(context)

        binding.sourceAccountInput.setText(candidate.sourceName.orEmpty())
        binding.destinationAccountInput.setText(candidate.destinationName.orEmpty())
        sourceAccountPick = candidate.sourceId?.let { PickedAccount(it, candidate.sourceName.orEmpty()) }
        destinationAccountPick = candidate.destinationId?.let { PickedAccount(it, candidate.destinationName.orEmpty()) }

        binding.descriptionInput.setText(candidate.description)
        binding.notesInput.setText(candidate.notes.orEmpty())
        binding.categoryInput.setText(candidate.category.orEmpty())
        binding.tagsInput.setText(candidate.tags.joinToString(", "))
    }

    /** Only the asset side of the pair gets the account picker (`FireflyApiClient.
     * listAssetAccounts`, same end-icon shape as `RuleEditFragment`'s) - the counterparty side
     * Firefly auto-creates by name, so it's always plain free text here, no picker, unlike
     * `RuleEditFragment`'s own macro-insert icon on that side (there's no template/macro concept
     * once a message has already matched and produced concrete values). */
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

    private fun updateDateTimeButtonText(context: Context) {
        val binding = binding ?: return
        val date = Date(selectedTimestampMillis)
        binding.dateTimeButton.text = "${DateFormat.getDateFormat(context).format(date)} ${DateFormat.getTimeFormat(context).format(date)}"
    }

    /** Date picker, then time picker, chained - same pattern `AddTestMessageFragment` already
     * uses, the standard two-step Android shape for picking one date-time with only platform
     * dialogs. */
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
                        updateDateTimeButtonText(context)
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
        val binding = binding ?: return
        val draft = buildDraft() ?: return
        val context = requireContext()

        viewLifecycleOwner.lifecycleScope.launch {
            binding.createTransactionButton.isEnabled = false
            when (val result = checkForDuplicate(context, draft)) {
                is DuplicateCheckResult.None -> post(draft)
                is DuplicateCheckResult.SearchFailed -> {
                    binding.createTransactionButton.isEnabled = true
                    AlertDialog.Builder(context)
                        .setTitle(R.string.check_search_failed_title)
                        .setMessage(result.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                is DuplicateCheckResult.Found -> {
                    binding.createTransactionButton.isEnabled = true
                    confirmCreateAnyway(draft, result.id)
                }
                is DuplicateCheckResult.FoundMultiple -> {
                    binding.createTransactionButton.isEnabled = true
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
            is FireflyApiClient.Result.Failure -> onSaveFailure(context, binding, result.message)
        }
    }

    /** Edit mode's submit - no duplicate check first, unlike [onCreateClicked]: there's no
     * "might this already exist" question when the whole point is editing a transaction already
     * confirmed to exist. */
    private fun onSaveClicked(transactionId: String) {
        val binding = binding ?: return
        val draft = buildDraft() ?: return
        binding.createTransactionButton.isEnabled = false
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
            is FireflyApiClient.Result.Failure -> onSaveFailure(context, binding, result.message)
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

    private fun onSaveFailure(context: Context, binding: FragmentCreateTransactionBinding, message: String) {
        binding.createTransactionButton.isEnabled = true
        AlertDialog.Builder(context)
            .setTitle(R.string.create_failed_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Builds the final [TransactionDraft] from the current field values, validating as it goes -
     * [kind]/[message] carry over from the original match/fetch unchanged (kind isn't
     * user-editable in either mode, see this class's own KDoc), everything else is read fresh
     * from the form. `issues` is always empty - the two [DraftIssue] flags were about
     * automated-parse uncertainty, which no longer applies once a human has directly
     * reviewed/filled in the fields (true in edit mode from the start - there was never a parse
     * to begin with). Returns null (after showing the relevant field's error) on the first
     * validation failure - same "stop at the first problem" shape `FireflyConnectionFragment.save`/
     * `TransactionCreationFragment.save` already use. */
    private fun buildDraft(): TransactionDraft? {
        val binding = binding ?: return null

        val amountText = binding.amountInput.text?.toString()?.trim().orEmpty()
        val amount = amountText.toBigDecimalOrNull()
        if (amount == null) {
            binding.amountInput.error = getString(R.string.create_tx_amount_invalid)
            return null
        }

        val description = binding.descriptionInput.text?.toString()?.trim().orEmpty()
        if (description.isBlank()) {
            binding.descriptionInput.error = getString(R.string.create_tx_description_required)
            return null
        }

        val sourceName = binding.sourceAccountInput.text?.toString()?.trim().orEmpty()
        val destinationName = binding.destinationAccountInput.text?.toString()?.trim().orEmpty()
        if (kind != RuleKind.DEPOSIT && sourceName.isBlank()) {
            binding.sourceAccountInput.error = getString(R.string.create_tx_source_required)
            return null
        }
        if (kind != RuleKind.WITHDRAWAL && destinationName.isBlank()) {
            binding.destinationAccountInput.error = getString(R.string.create_tx_destination_required)
            return null
        }

        val currencyCode = binding.currencyCodeInput.text?.toString()?.trim().orEmpty()
        val foreignAmountText = binding.foreignAmountInput.text?.toString()?.trim().orEmpty()
        val foreignCurrencyCode = binding.foreignCurrencyCodeInput.text?.toString()?.trim().orEmpty()
        val tags = binding.tagsInput.text?.toString().orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
        val category = binding.categoryInput.text?.toString()?.trim().orEmpty().takeIf { it.isNotBlank() }

        return TransactionDraft(
            kind = kind,
            date = Instant.ofEpochMilli(selectedTimestampMillis),
            amount = amount,
            currencyCode = currencyCode,
            foreignAmount = foreignAmountText.toBigDecimalOrNull(),
            foreignCurrencyCode = foreignCurrencyCode.takeIf { it.isNotBlank() },
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
