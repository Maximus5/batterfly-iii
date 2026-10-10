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
import io.github.maximus5.ffnotifyimporter.posting.checkForDuplicate
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionsDb
import io.github.maximus5.ffnotifyimporter.rules.CurrenciesDb
import io.github.maximus5.ffnotifyimporter.rules.DraftIssue
import io.github.maximus5.ffnotifyimporter.rules.ParseOutcome
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
 * Pre-create review/edit screen for a matched rule - replaces what used to be
 * `MessagesFragment.showTestMatch`'s read-only test-match dialog for a *fresh* match, per the
 * user's own request (see TODO.md's dedup history): every field is editable before posting,
 * category in particular picked from Firefly's own real category list
 * ([FireflyApiClient.listCategories]) instead of only ever being free text via `ParseRule.
 * categoryTemplate` - visual style (end-icon picker on a [TextInputLayout], cached-per-screen
 * account/category list) is deliberately the same shape as [RuleEditFragment]'s own asset-account
 * picker, the user's own suggested starting point.
 *
 * Re-parses the message fresh via [RuleEngine.match] rather than receiving a [TransactionDraft]
 * directly - [CreateTransactionActivity] only ever passes the message's own plain fields across
 * the Activity boundary, same "reconstruct from primitives, don't serialize a BigDecimal/Instant-
 * carrying data class" approach `posting.AutoCreateWorker` already uses for the same reason.
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
        val rules = RulesDb.getInstance(context).loadAllOrdered()
        val currencySymbols = CurrenciesDb.getInstance(context).symbolToCodeMap()
        val outcome = RuleEngine(rules, currencySymbols).match(message)
        val draft = (outcome as? ParseOutcome.Matched)?.draft
        if (draft == null) {
            // Shouldn't happen in practice - this screen is only ever launched right after a
            // successful match - but the message could in theory have been edited away from under
            // it (it can't be, there's no edit feature) or a rule deleted/disabled in between.
            Toast.makeText(context, R.string.create_tx_no_rule_matched, Toast.LENGTH_SHORT).show()
            requireActivity().finish()
            return
        }

        kind = draft.kind
        populateFields(draft)
        updateAccountFieldModes()

        // The user's own request: shown instead of the separate confirmation dialog
        // "Create Again" used to pop before ever reaching this screen - see
        // CreateTransactionActivity's own KDoc for why. Informational while editing, not a gate;
        // the real "are you sure" already happens on submit, via this screen's own
        // checkForDuplicate finding the same linked transaction again (confirmCreateAnyway below).
        val staleLinkedId = CreateTransactionActivity.staleLinkedIdFrom(requireActivity().intent)
        when {
            message.fireflyTransactionId != null -> {
                binding.alreadyLinkedWarningText.text =
                    getString(R.string.create_tx_already_linked_warning, message.fireflyTransactionId)
                binding.alreadyLinkedWarningText.visibility = View.VISIBLE
            }
            // The user's own follow-up catch: falling through here because a previously-linked
            // id was just confirmed gone (MessagesFragment.verifyLinkedThenShow) used to show no
            // warning at all - silently dropping the fact that something *had* been linked,
            // instead of saying so. Same banner, different wording - "already linked" would be
            // false here, this is specifically what was just disproven.
            staleLinkedId != null -> {
                binding.alreadyLinkedWarningText.text = getString(R.string.create_tx_stale_linked_warning, staleLinkedId)
                binding.alreadyLinkedWarningText.visibility = View.VISIBLE
            }
        }

        binding.dateTimeButton.setOnClickListener { showDateTimePicker() }
        binding.categoryLayout.setEndIconOnClickListener { showCategoryPicker(binding.categoryInput) }
        binding.createTransactionButton.setOnClickListener { onCreateClicked() }

        if (DraftIssue.LOCAL_AMOUNT_MISSING in draft.issues) {
            viewLifecycleOwner.lifecycleScope.launch { resolveApproxConversion(draft) }
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

        binding.amountInput.setText(draft.amount?.toPlainString().orEmpty())
        binding.currencyCodeInput.setText(draft.currencyCode)
        binding.foreignAmountInput.setText(draft.foreignAmount?.toPlainString().orEmpty())
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
     * categoryName` is the only field Firefly's create-transaction endpoint accepts for category
     * (no `category_id`, confirmed against `buildTransactionRequestBody`), so picking just fills
     * in a real name, nothing more to reconcile at build time. */
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
     * was in flight, and that shouldn't get silently overwritten. */
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
        val request = FireflyTransactionRequest(
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
        val client = FireflyApiClient(prefs.getBaseUrl(), prefs.getToken())
        when (val result = client.createTransaction(request)) {
            is FireflyApiClient.Result.Success -> {
                CreatedTransactionsDb.getInstance(context).recordCreated(
                    hashWithDescription = transactionDedupHash(draft, true),
                    hashWithoutDescription = transactionDedupHash(draft, false),
                    transactionDateMillis = draft.date.toEpochMilli(),
                    fireflyTransactionId = result.value,
                )
                MessageStore.shared.setTransactionId(message, result.value)
                Toast.makeText(context, getString(R.string.create_success_message, result.value), Toast.LENGTH_LONG).show()
                requireActivity().finish()
            }
            is FireflyApiClient.Result.Failure -> {
                binding.createTransactionButton.isEnabled = true
                AlertDialog.Builder(context)
                    .setTitle(R.string.create_failed_title)
                    .setMessage(result.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /** Builds the final [TransactionDraft] from the current field values, validating as it goes -
     * [kind]/[message] carry over from the original match unchanged (kind isn't user-editable, see
     * this class's own KDoc), everything else is read fresh from the form. `issues` is always
     * empty - the two [DraftIssue] flags were about automated-parse uncertainty, which no longer
     * applies once a human has directly reviewed/filled in the fields. Returns null (after showing
     * the relevant field's error) on the first validation failure - same "stop at the first
     * problem" shape `FireflySettingsFragment.save` already uses. */
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

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
