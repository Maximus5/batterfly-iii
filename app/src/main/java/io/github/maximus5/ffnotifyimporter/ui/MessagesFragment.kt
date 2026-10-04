package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.maximus5.ffnotifyimporter.R
import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.databinding.FragmentMessagesBinding
import io.github.maximus5.ffnotifyimporter.network.ExchangeRateClient
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.network.FireflyTransactionCandidate
import io.github.maximus5.ffnotifyimporter.network.FireflyTransactionRequest
import io.github.maximus5.ffnotifyimporter.network.dedupSearchQuery
import io.github.maximus5.ffnotifyimporter.network.searchUrl
import io.github.maximus5.ffnotifyimporter.network.transactionUrl
import io.github.maximus5.ffnotifyimporter.posting.DuplicateCheckResult
import io.github.maximus5.ffnotifyimporter.posting.checkForDuplicate
import io.github.maximus5.ffnotifyimporter.posting.findDuplicate
import io.github.maximus5.ffnotifyimporter.posting.toRemoteCandidate
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionsDb
import io.github.maximus5.ffnotifyimporter.rules.CurrenciesDb
import io.github.maximus5.ffnotifyimporter.rules.DraftIssue
import io.github.maximus5.ffnotifyimporter.rules.NoMatchReason
import io.github.maximus5.ffnotifyimporter.rules.ParseOutcome
import io.github.maximus5.ffnotifyimporter.rules.RemoteTransactionCandidate
import io.github.maximus5.ffnotifyimporter.rules.RuleEngine
import io.github.maximus5.ffnotifyimporter.rules.RulesDb
import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import io.github.maximus5.ffnotifyimporter.rules.applyApproxConversion
import io.github.maximus5.ffnotifyimporter.rules.transactionDedupHash
import java.math.RoundingMode
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.launch

class MessagesFragment : Fragment() {

    private var binding: FragmentMessagesBinding? = null
    private lateinit var adapter: MessagesAdapter
    private var inSelectionMode = false

    private val listener: (List<CapturedMessage>) -> Unit = { messages ->
        adapter.submitList(messages)
        binding?.messagesEmptyText?.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
        if (inSelectionMode) {
            if (adapter.selectedCount() == 0) clearSelectionAndExit() else updateSelectionCountDisplay()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentMessagesBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = MessagesAdapter(onClick = ::handleClick, onLongClick = ::handleLongClick, onOverflowClick = ::showMessageOverflowMenu)
        binding?.messagesRecyclerView?.layoutManager = LinearLayoutManager(requireContext())
        binding?.messagesRecyclerView?.adapter = adapter
    }

    private fun handleClick(message: CapturedMessage) {
        if (inSelectionMode) {
            adapter.toggleSelection(message)
            if (adapter.selectedCount() == 0) clearSelectionAndExit() else updateSelectionCountDisplay()
        } else {
            showTestMatch(message)
        }
    }

    private fun showTestMatch(message: CapturedMessage) {
        val rules = RulesDb.getInstance(requireContext()).loadAllOrdered()
        val currencySymbols = CurrenciesDb.getInstance(requireContext()).symbolToCodeMap()
        val outcome = RuleEngine(rules, currencySymbols).match(message)

        // The title *is* the outcome - the matched rule's own label, or "No match" - instead of
        // a generic "Test match" that made every dialog look the same regardless of which rule
        // (if any) actually fired.
        val title: CharSequence
        val body: CharSequence
        var draft: TransactionDraft? = null
        when (outcome) {
            is ParseOutcome.NoMatch -> {
                title = getString(R.string.test_match_no_match_title)
                body = getString(reasonStringRes(outcome.reason))
            }
            is ParseOutcome.Matched -> {
                title = outcome.rule.label
                body = buildMatchedBody(outcome.draft)
                draft = outcome.draft
            }
        }

        val context = requireContext()
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)

        // Offered once there's an actual draft to post and a Firefly connection to post it to. A
        // message that already has a linked transaction (`message.fireflyTransactionId`) gets
        // "Open" in the neutral slot instead of "Create Transaction" - "Create Again" moves to
        // negative rather than displacing it, so the single most relevant secondary action always
        // has the neutral slot (create when nothing's linked yet, open when something is).
        //
        // For a *fresh* create (nothing linked yet), the neutral button starts disabled as
        // "Checking…" rather than an immediately-tappable "Create transaction" - a real user-
        // reported problem with tapping it blind: the dedup check (local log, then Firefly's own
        // search) only ran *after* the tap, so the same button could silently post straight
        // through, silently reuse an existing transaction, or pop up a second "possible duplicate"
        // dialog, and there was no way to tell which in advance. Now the check runs first, and the
        // button only becomes tappable once its own outcome is already decided - see the
        // `draft?.let` block below. "Create Again" deliberately keeps the old instant behavior
        // (own user decision, not changed) - it's already an explicit "yes, another one anyway".
        val existingTransactionId = message.fireflyTransactionId
        val canCreate = draft != null && FireflyPrefs(context).isConfigured()
        if (canCreate) {
            if (existingTransactionId != null) {
                builder.setNeutralButton(R.string.create_open_transaction) { _, _ ->
                    openTransaction(context, existingTransactionId)
                }
                builder.setNegativeButton(R.string.create_again) { _, _ ->
                    draft?.let { confirmCreateAgain(context, it, message, existingTransactionId) }
                }
            } else {
                builder.setNeutralButton(R.string.create_checking, null)
            }
        }

        val dialog = builder.show()
        if (canCreate && existingTransactionId == null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
        }

        val initialDraft = draft ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            var resolved = initialDraft
            if (DraftIssue.LOCAL_AMOUNT_MISSING in resolved.issues) {
                resolved = resolveApproxConversion(resolved, dialog)
            }
            if (existingTransactionId != null) return@launch // "Create Again" stays instant.

            val button = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            if (resolved.amount == null) {
                // Conversion failed/missing - enable normally rather than leaving the button stuck
                // disabled with no way forward; tapping falls through to startCreate's own
                // "amount not ready" toast, same as before this change.
                button.text = getString(R.string.test_match_create)
                button.isEnabled = true
                button.setOnClickListener { startCreate(resolved, message) }
                return@launch
            }

            when (val result = checkForDuplicate(context, resolved)) {
                is DuplicateCheckResult.None, is DuplicateCheckResult.SearchFailed -> {
                    button.text = getString(R.string.test_match_create)
                    button.isEnabled = true
                    button.setOnClickListener {
                        dialog.dismiss()
                        postTransaction(resolved, message)
                    }
                }
                is DuplicateCheckResult.Found -> {
                    dialog.dismiss()
                    showDuplicateConfirmDialog(context, result.id, resolved, message, knownCandidate = result.candidate)
                }
                is DuplicateCheckResult.FoundMultiple -> {
                    Toast.makeText(
                        context,
                        getString(R.string.create_remote_duplicates_found, result.count),
                        Toast.LENGTH_LONG,
                    ).show()
                    dialog.dismiss()
                    showDuplicateConfirmDialog(context, result.mostRecentId, resolved, message, knownCandidate = result.candidate)
                }
            }
        }
    }

    /** Resolves [draft]'s amount via a live exchange-rate fetch if it's missing
     * (`DraftIssue.LOCAL_AMOUNT_MISSING`), updating [dialog]'s body to show the resolved amount in
     * place - returns [draft] unchanged if nothing needed resolving. A failed lookup appends why
     * to the body rather than blocking or retrying; either way this suspends until done, so the
     * caller can chain the dedup check after it using the final, resolved amount. */
    private suspend fun resolveApproxConversion(draft: TransactionDraft, dialog: AlertDialog): TransactionDraft {
        val foreignCurrency = draft.foreignCurrencyCode ?: return draft
        val context = requireContext()
        return when (val result = ExchangeRateClient().fetchRate(foreignCurrency, draft.currencyCode, draft.date)) {
            is ExchangeRateClient.Result.Success -> {
                val approxTag = FireflyPrefs(context).getApproxConversionTag()
                val updated = applyApproxConversion(draft, result.rate, approxTag)
                dialog.setMessage(buildMatchedBody(updated))
                updated
            }
            is ExchangeRateClient.Result.Failure -> {
                val updatedBody = SpannableStringBuilder(buildMatchedBody(draft))
                    .append("\n")
                    .append(getString(R.string.test_match_rate_lookup_failed, result.message))
                dialog.setMessage(updatedBody)
                draft
            }
        }
    }

    /** Confirms before "Create Again" actually runs - the user's own call, after noticing the tap
     * had no confirmation at all. [startCreate] below does still run its own [checkForDuplicate]
     * afterward, but that's a best-effort safety net (silently skipped entirely when
     * `FireflyPrefs.isDedupEnabled()` is off, or when the dedup window has since lapsed) - this
     * dialog is the one guaranteed "are you sure", shown every time regardless of dedup settings,
     * since [existingTransactionId] already proves a transaction exists for this message. Cancel
     * always rightmost/last (house convention, AGENTS.md). */
    private fun confirmCreateAgain(context: Context, draft: TransactionDraft, message: CapturedMessage, existingTransactionId: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.create_again_confirm_title)
            .setMessage(getString(R.string.create_again_confirm_message, existingTransactionId))
            .setNegativeButton(R.string.create_again) { _, _ -> startCreate(draft, message) }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    /** Entry point for "Create Again" only now - the fresh-create path runs [checkForDuplicate]
     * preemptively instead (see [showTestMatch]). Kept instant/click-first per the user's own
     * original call; now gated behind [confirmCreateAgain] above instead, per a later, separate
     * request. */
    private fun startCreate(draft: TransactionDraft, message: CapturedMessage) {
        val context = requireContext()
        if (draft.amount == null) {
            Toast.makeText(context, R.string.create_amount_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // A failed remote check (SearchFailed) is treated the same as a clean miss (None)
            // here - it's an extra safety net on top of the local dedup log, not the primary
            // mechanism, so it shouldn't block creation. See checkIfCreated for where that
            // distinction actually matters.
            when (val result = checkForDuplicate(context, draft)) {
                is DuplicateCheckResult.None, is DuplicateCheckResult.SearchFailed -> postTransaction(draft, message)
                is DuplicateCheckResult.Found -> showDuplicateConfirmDialog(context, result.id, draft, message, knownCandidate = result.candidate)
                is DuplicateCheckResult.FoundMultiple -> {
                    Toast.makeText(
                        context,
                        getString(R.string.create_remote_duplicates_found, result.count),
                        Toast.LENGTH_LONG,
                    ).show()
                    showDuplicateConfirmDialog(context, result.mostRecentId, draft, message, knownCandidate = result.candidate)
                }
            }
        }
    }

    /** Four actions ("Use Created"/"Open"/"Create anyway"/"Cancel", Cancel always last) - more
     * than `AlertDialog.Builder`'s positive/negative/neutral slots can hold, so this is a plain
     * custom view (same reasoning as the rule editor's "Test results" dialog) instead of the
     * native button bar: `dialog_duplicate_confirm_4.xml`, a 2x2 grid (not four stacked full-width
     * rows - that left a lot of dead vertical space for four short labels). "Open" deliberately
     * doesn't dismiss - it's "let me go check first", not a decision, so the dialog is still there
     * to act on once the user comes back from Firefly. "Use Created" links the *found*
     * transaction's id to [message] instead of posting a new one - the dedup check already did the
     * work of finding it, no reason to make the user go dig it up by hand in Firefly.
     *
     * [showCreateAnyway] drops to three actions - `dialog_duplicate_confirm_3.xml`, one row
     * instead of a grid - for [checkIfCreated]: that flow is an explicit "just tell me" search,
     * not a step on the way to posting a transaction, so "post one anyway" isn't a meaningful
     * option there; title/message also switch to the check-specific wording in that case.
     *
     * Always shows the found transaction's real fields (date/amount/description/accounts)
     * alongside the id, not just the bare "#<id>" - the user's own call, same reasoning as the
     * not-found dialog's candidate dump: seeing what was actually found is what lets the user
     * judge whether "Use Created" is really correct, not just trust the id. [knownCandidate] is
     * passed straight through when this came from the remote search (already fetched in full,
     * no reason to ask again); for a local-log hit (`CreatedTransactionsDb` only ever has the id)
     * this fetches it itself (`FireflyApiClient.getTransaction`) - suspends briefly for that, a
     * worthwhile trade for not showing a bare id the user has no way to independently verify.
     * [descriptionMismatch] (only ever true from [checkIfCreated]'s retry-without-description -
     * see its own KDoc) prepends an explicit warning that this match ignored description. */
    private suspend fun showDuplicateConfirmDialog(
        context: Context,
        foundId: String,
        draft: TransactionDraft,
        message: CapturedMessage,
        showCreateAnyway: Boolean = true,
        knownCandidate: RemoteTransactionCandidate? = null,
        descriptionMismatch: Boolean = false,
    ) {
        val candidate = knownCandidate ?: fetchCandidateInfo(context, foundId)
        val layoutRes = if (showCreateAnyway) R.layout.dialog_duplicate_confirm_4 else R.layout.dialog_duplicate_confirm_3
        val view = LayoutInflater.from(context).inflate(layoutRes, null, false)
        val baseMessage = if (showCreateAnyway) {
            getString(R.string.create_duplicate_confirm_message, foundId)
        } else {
            getString(R.string.check_found_message, foundId)
        }
        val warning = if (descriptionMismatch) getString(R.string.check_found_description_mismatch_warning) + "\n\n" else ""
        val info = candidate?.let { buildFoundTransactionInfo(it) }.orEmpty()
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (showCreateAnyway) R.string.create_duplicate_confirm_title else R.string.check_found_title)
            .setMessage(warning + baseMessage + info)
            .setView(view)
            .create()

        view.findViewById<View>(R.id.useCreatedButton).setOnClickListener {
            MessageStore.shared.setTransactionId(message, foundId)
            Toast.makeText(context, getString(R.string.create_already_created_linked, foundId), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.openDuplicateButton).setOnClickListener { openTransaction(context, foundId) }
        if (showCreateAnyway) {
            view.findViewById<View>(R.id.createAnywayButton).setOnClickListener {
                postTransaction(draft, message)
                dialog.dismiss()
            }
        }
        view.findViewById<View>(R.id.cancelDuplicateButton).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    /** Fetches a known transaction's full fields for [showDuplicateConfirmDialog] - only needed
     * for a local-log hit, which only ever has the id. Null on any failure (network, malformed
     * response, or a genuinely missing transaction) - the dialog just shows the bare id in that
     * case, same as before this existed, rather than failing to show anything at all. */
    private suspend fun fetchCandidateInfo(context: Context, id: String): RemoteTransactionCandidate? {
        val prefs = FireflyPrefs(context)
        val client = FireflyApiClient(prefs.getBaseUrl(), prefs.getToken())
        return when (val result = client.getTransaction(id)) {
            is FireflyApiClient.Result.Success -> result.value?.toRemoteCandidate()
            is FireflyApiClient.Result.Failure -> null
        }
    }

    /** Labeled field summary appended to the duplicate-confirm dialog's message - reuses
     * `showTestMatch`'s own field labels (`test_match_label_*`) so this reads the same way as the
     * already-familiar match-preview dialog, rather than inventing a second, denser text format.
     * Seeing the found transaction's actual date/amount/description/accounts is what lets the
     * user judge a match independently, not just trust a bare id. Shows the foreign amount/
     * currency alongside the home one when present, for the same reason the home amount alone is
     * unreliable for matching (see `network.dedupSearchQuery`'s KDoc) - it's informative here too,
     * not just for searching. */
    private fun buildFoundTransactionInfo(candidate: RemoteTransactionCandidate): String {
        val amountText = if (candidate.foreignAmount != null && candidate.foreignCurrencyCode != null) {
            "${candidate.amount.toPlainString()} ${candidate.currencyCode} " +
                "(${candidate.foreignAmount.toPlainString()} ${candidate.foreignCurrencyCode})"
        } else {
            "${candidate.amount.toPlainString()} ${candidate.currencyCode}"
        }
        val lines = mutableListOf(
            "${getString(R.string.test_match_label_date)} ${formatDraftDate(candidate.date)}",
            "${getString(R.string.test_match_label_amount)} $amountText",
            "${getString(R.string.test_match_label_description)} ${candidate.description}",
        )
        candidate.sourceName?.let { lines += "${getString(R.string.test_match_label_source)} $it" }
        candidate.destinationName?.let { lines += "${getString(R.string.test_match_label_destination)} $it" }
        return "\n\n" + lines.joinToString("\n")
    }

    /** Records both dedup hash variants on success - see `CreatedTransactionsDb`'s KDoc for why
     * (always both, not just whichever `FireflyPrefs.isDedupIncludeDescription()` says right now,
     * so toggling that setting later never breaks matching against this row). */
    private fun postTransaction(draft: TransactionDraft, message: CapturedMessage) {
        val amount = draft.amount ?: return
        val context = requireContext()
        val prefs = FireflyPrefs(context)
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
        viewLifecycleOwner.lifecycleScope.launch {
            val client = FireflyApiClient(prefs.getBaseUrl(), prefs.getToken())
            when (val result = client.createTransaction(request)) {
                is FireflyApiClient.Result.Success -> {
                    CreatedTransactionsDb.getInstance(context).recordCreated(
                        hashWithDescription = transactionDedupHash(draft, true),
                        hashWithoutDescription = transactionDedupHash(draft, false),
                        transactionDateMillis = draft.date.toEpochMilli(),
                        fireflyTransactionId = result.value,
                    )
                    // Re-created from a message that already had an id (via "Create Again") just
                    // overwrites it with the new one - this message's transaction is whatever was
                    // created most recently, same "latest wins" reasoning as the dedup lookup
                    // itself (ORDER BY date DESC).
                    MessageStore.shared.setTransactionId(message, result.value)
                    showCreateSuccessDialog(context, result.value)
                }
                is FireflyApiClient.Result.Failure -> {
                    showResultDialog(context, R.string.create_failed_title, result.message)
                }
            }
        }
    }

    private fun showCreateSuccessDialog(context: Context, transactionId: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.create_success_title)
            .setMessage(getString(R.string.create_success_message, transactionId))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.create_open_transaction) { _, _ -> openTransaction(context, transactionId) }
            .show()
    }

    private fun openTransaction(context: Context, transactionId: String) {
        val baseUrl = FireflyPrefs(context).getBaseUrl()
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(transactionUrl(baseUrl, transactionId))))
    }

    private fun showResultDialog(context: Context, titleRes: Int, message: String) {
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Bolds each field's label ("Source:", "Amount:", ...) so a multi-field dialog reads as a
     * list of fields rather than a wall of same-weight text. */
    private fun buildMatchedBody(draft: TransactionDraft): CharSequence {
        val text = SpannableStringBuilder()
        fun field(label: String, value: String) {
            if (text.isNotEmpty()) text.append("\n")
            val labelStart = text.length
            text.append(label)
            text.setSpan(StyleSpan(Typeface.BOLD), labelStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append(" ").append(value)
        }

        draft.sourceName?.let { field(getString(R.string.test_match_label_source), it) }
        draft.destinationName?.let { field(getString(R.string.test_match_label_destination), it) }
        field(
            getString(R.string.test_match_label_amount),
            if (draft.amount != null) {
                "${draft.amount.toPlainString()} ${draft.currencyCode}"
            } else {
                getString(R.string.test_match_amount_missing_value)
            },
        )
        draft.foreignAmount?.let {
            field(
                getString(R.string.test_match_label_foreign),
                "${it.toPlainString()} ${draft.foreignCurrencyCode.orEmpty()}",
            )
        }
        field(getString(R.string.test_match_label_date), formatDraftDate(draft.date))
        field(getString(R.string.test_match_label_description), draft.description)
        field(getString(R.string.test_match_label_notes), draft.notes)
        draft.category?.let { field(getString(R.string.test_match_label_category), it) }
        if (draft.tags.isNotEmpty()) field(getString(R.string.test_match_label_tags), draft.tags.joinToString(", "))
        draft.issues.forEach { issue ->
            text.append("\n").append(
                when (issue) {
                    DraftIssue.LOCAL_AMOUNT_MISSING -> getString(R.string.test_match_issue_local_amount_missing)
                    DraftIssue.DATE_UNPARSABLE -> getString(R.string.test_match_issue_date_unparsable)
                },
            )
        }
        return text
    }

    /** Same system date+time formatting `MessagesAdapter` uses for the notification list, for the
     * same reason - respects the device's actual date/time settings rather than guessing from
     * locale alone. Shown for every match, not just when [ParseRule.dateFormat] overrides it, so
     * the override is actually visible: without this field the dialog never showed a date at all,
     * and a wrong override would be invisible until a transaction posted with it. */
    private fun formatDraftDate(date: Instant): String {
        val context = requireContext()
        val javaDate = Date.from(date)
        val dateFormat = DateFormat.getDateFormat(context)
        val timeFormat = DateFormat.getTimeFormat(context)
        return "${dateFormat.format(javaDate)} ${timeFormat.format(javaDate)}"
    }

    private fun reasonStringRes(reason: NoMatchReason) = when (reason) {
        NoMatchReason.NO_RULES_FOR_APP -> R.string.no_match_reason_no_rules_for_app
        NoMatchReason.NO_PATTERN_MATCHED -> R.string.no_match_reason_no_pattern_matched
        NoMatchReason.AMOUNT_UNPARSABLE -> R.string.no_match_reason_amount_unparsable
    }

    private fun handleLongClick(message: CapturedMessage): Boolean {
        if (!inSelectionMode) {
            inSelectionMode = true
            adapter.setSelectionMode(true)
            adapter.toggleSelection(message)
            (requireActivity() as MainActivity).enterSelectionMode(
                count = adapter.selectedCount(),
                onDelete = ::confirmDeleteSelected,
                onCancel = ::clearSelectionAndExit,
            )
        } else {
            adapter.toggleSelection(message)
            if (adapter.selectedCount() == 0) clearSelectionAndExit() else updateSelectionCountDisplay()
        }
        return true
    }

    private fun updateSelectionCountDisplay() {
        (requireActivity() as MainActivity).updateSelectionCount(adapter.selectedCount())
    }

    private fun clearSelectionAndExit() {
        adapter.clearSelection()
        adapter.setSelectionMode(false)
        inSelectionMode = false
        (activity as? MainActivity)?.exitSelectionMode()
    }

    private fun showMessageOverflowMenu(message: CapturedMessage, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, ID_CHECK_IF_CREATED, 0, R.string.messages_check_if_created)
        popup.menu.add(0, ID_DELETE_SINGLE, 1, R.string.action_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_CHECK_IF_CREATED -> checkIfCreated(message)
                ID_DELETE_SINGLE -> confirmDeleteSingle(message)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun confirmDeleteSingle(message: CapturedMessage) {
        // Cancel always rightmost/last (house convention, AGENTS.md).
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_messages_confirm_title)
            .setMessage(R.string.delete_messages_confirm_message)
            .setNegativeButton(R.string.action_delete) { _, _ -> MessageStore.shared.remove(message) }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    /** "Check if created" (the per-row overflow menu) - an explicit, deliberate search, unlike
     * the preemptive check `showTestMatch`/`startCreate` run before posting: it always looks
     * (local log, then Firefly), ignoring `FireflyPrefs.isDedupEnabled()` - that toggle is about
     * whether *creating* gates on a dedup check, not whether this separate, user-initiated "just
     * tell me" action should run at all. Re-parses the message fresh (same `RuleEngine.match` as
     * `showTestMatch`) rather than requiring the message to already have a cached draft.
     *
     * Manual-only retry, the user's own call: if `isDedupIncludeDescription()` is on and the
     * first attempt finds nothing, tries again with description excluded entirely (a second
     * `findDuplicate` call - local log included, since a without-description hash might be
     * populated there too, see `CreatedTransactionsDb`'s KDoc) before giving up. The create-flow
     * callers (`checkForDuplicate`) deliberately do *not* do this - silently treating a
     * description mismatch as "good enough" there would risk posting on top of a genuinely
     * different transaction that just happens to share amount/account/date; a manual lookup has
     * a human looking at the result either way, so showing a weaker match *with a clear warning*
     * is the right trade-off here specifically. */
    private fun checkIfCreated(message: CapturedMessage) {
        val context = requireContext()
        if (!FireflyPrefs(context).isConfigured()) {
            Toast.makeText(context, R.string.rule_firefly_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        val rules = RulesDb.getInstance(context).loadAllOrdered()
        val currencySymbols = CurrenciesDb.getInstance(context).symbolToCodeMap()
        val outcome = RuleEngine(rules, currencySymbols).match(message)
        val draft = (outcome as? ParseOutcome.Matched)?.draft
        if (draft == null) {
            Toast.makeText(context, R.string.messages_check_no_rule_matched, Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val resolved = resolveAmountQuietly(draft)
            if (resolved.amount == null) {
                Toast.makeText(context, R.string.create_amount_not_ready, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val prefs = FireflyPrefs(context)
            val includeDescription = prefs.isDedupIncludeDescription()
            var result = findDuplicate(context, resolved, prefs, includeDescription)
            var effectiveIncludeDescription = includeDescription
            var matchedWithoutDescription = false
            if (result is DuplicateCheckResult.None && includeDescription) {
                when (val retry = findDuplicate(context, resolved, prefs, includeDescription = false)) {
                    is DuplicateCheckResult.Found, is DuplicateCheckResult.FoundMultiple -> {
                        result = retry
                        effectiveIncludeDescription = false
                        matchedWithoutDescription = true
                    }
                    is DuplicateCheckResult.None -> {
                        // Still nothing, but the broader (no-description) search may have turned
                        // up raw candidates the first attempt's query filtered out server-side -
                        // show those instead, the more informative "last/broadest attempt" result.
                        result = retry
                        effectiveIncludeDescription = false
                    }
                    is DuplicateCheckResult.SearchFailed -> Unit // keep the original None as-is
                }
            }
            when (val r = result) {
                is DuplicateCheckResult.None -> {
                    val query = dedupSearchQuery(
                        amount = resolved.amount,
                        currencyCode = resolved.currencyCode,
                        foreignAmount = resolved.foreignAmount,
                        foreignCurrencyCode = resolved.foreignCurrencyCode,
                        centerDate = resolved.date,
                        windowMinutes = prefs.getDedupWindowMinutes(),
                        sourceName = resolved.sourceName,
                        destinationName = resolved.destinationName,
                        description = resolved.description,
                        includeDescription = effectiveIncludeDescription,
                    ).orEmpty()
                    showSearchNotFoundDialog(context, query, r.rawCandidates, message)
                }
                is DuplicateCheckResult.Found ->
                    showDuplicateConfirmDialog(
                        context, r.id, resolved, message,
                        showCreateAnyway = false, knownCandidate = r.candidate, descriptionMismatch = matchedWithoutDescription,
                    )
                is DuplicateCheckResult.FoundMultiple -> {
                    Toast.makeText(
                        context,
                        getString(R.string.create_remote_duplicates_found, r.count),
                        Toast.LENGTH_LONG,
                    ).show()
                    showDuplicateConfirmDialog(
                        context, r.mostRecentId, resolved, message,
                        showCreateAnyway = false, knownCandidate = r.candidate, descriptionMismatch = matchedWithoutDescription,
                    )
                }
                // Unlike the create-flow callers, this is an explicit "just tell me" lookup - a
                // failed search gets shown for what it actually was, not quietly presented as
                // "not found" (which is exactly what the user caught: the same query string found
                // the transaction fine when pasted into Firefly's own web search, so an API-side
                // failure being swallowed as "not found" would be actively misleading here).
                is DuplicateCheckResult.SearchFailed ->
                    showResultDialog(context, R.string.check_search_failed_title, r.message)
            }
        }
    }

    /** Same exchange-rate resolution as [resolveApproxConversion], without an [AlertDialog] to
     * update along the way - for [checkIfCreated], which doesn't show the matched-fields dialog
     * at all. A failed lookup silently keeps the original (still-unresolved) draft, same as
     * [resolveApproxConversion]'s own fallback. */
    private suspend fun resolveAmountQuietly(draft: TransactionDraft): TransactionDraft {
        if (DraftIssue.LOCAL_AMOUNT_MISSING !in draft.issues) return draft
        val foreignCurrency = draft.foreignCurrencyCode ?: return draft
        val context = requireContext()
        return when (val result = ExchangeRateClient().fetchRate(foreignCurrency, draft.currencyCode, draft.date)) {
            is ExchangeRateClient.Result.Success -> {
                val approxTag = FireflyPrefs(context).getApproxConversionTag()
                applyApproxConversion(draft, result.rate, approxTag)
            }
            is ExchangeRateClient.Result.Failure -> draft
        }
    }

    /** No match in Firefly - shows the exact query string `checkIfCreated` searched with (so the
     * user can see/debug what was actually looked for) and offers to open the same search,
     * pre-filled, in Firefly's own web UI (`network.searchUrl`) rather than leaving them to
     * retype it by hand. [rawCandidates] (Firefly's own raw results, before this app's
     * client-side `remoteCandidateMatches` filtering rejected every one of them) are listed with
     * their actual field values when non-empty - "found N but none matched exactly" is a
     * different, more useful signal than "found literally nothing", and seeing the candidate's
     * real date/amount/account/description next to what was searched for is what actually shows
     * *why* the match failed (case, whitespace, a stale date, ...) instead of needing another
     * debug round-trip to find out. When exactly one candidate came back, a "Use anyway" button
     * links [message] to it directly - the user's own call: with only one candidate, the
     * client-side rejection is more likely a field-comparison quirk than a genuine different
     * transaction, so skip the round trip through Firefly's own search UI.
     *
     * `dialog_search_not_found.xml`, not native `setNegativeButton`/`setNeutralButton`/
     * `setPositiveButton` slots: the user caught a real native-AlertDialog quirk directly - once
     * "Use anyway" made this a 3-button dialog, AlertDialog's own button bar stacked them
     * vertically (each right-aligned) instead of one row, since the combined label text didn't
     * fit. Same fix already applied to `dialog_duplicate_confirm_3.xml` for the same reason. */
    private fun showSearchNotFoundDialog(
        context: Context,
        query: String,
        rawCandidates: List<RemoteTransactionCandidate>,
        message: CapturedMessage,
    ) {
        val dialogMessage = if (rawCandidates.isNotEmpty()) {
            val candidateLines = rawCandidates.joinToString("\n") {
                "id=${it.groupId} date=${it.date} amount=${it.amount.setScale(2, RoundingMode.HALF_UP)} ${it.currencyCode} " +
                    "desc=\"${it.description}\" src=${it.sourceId}/${it.sourceName} dst=${it.destinationId}/${it.destinationName}"
            }
            getString(R.string.check_not_found_message_with_candidates, query, rawCandidates.size, candidateLines)
        } else {
            getString(R.string.check_not_found_message, query)
        }
        val onlyCandidate = rawCandidates.singleOrNull()

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_search_not_found, null, false)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.check_not_found_title)
            .setMessage(dialogMessage)
            .setView(view)
            .create()

        view.findViewById<View>(R.id.openSearchButton).setOnClickListener {
            val baseUrl = FireflyPrefs(context).getBaseUrl()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl(baseUrl, query))))
            dialog.dismiss()
        }
        val useAnywayButton = view.findViewById<View>(R.id.useAnywayButton)
        if (onlyCandidate != null) {
            useAnywayButton.setOnClickListener {
                MessageStore.shared.setTransactionId(message, onlyCandidate.groupId)
                Toast.makeText(context, getString(R.string.create_already_created_linked, onlyCandidate.groupId), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        } else {
            useAnywayButton.visibility = View.GONE
        }
        view.findViewById<View>(R.id.cancelSearchButton).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun confirmDeleteSelected() {
        val toDelete = adapter.selectedMessages()
        // Cancel always rightmost/last (house convention, AGENTS.md).
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_messages_confirm_title)
            .setMessage(R.string.delete_messages_confirm_message)
            .setNegativeButton(R.string.action_delete) { _, _ ->
                toDelete.forEach { MessageStore.shared.remove(it) }
                clearSelectionAndExit()
            }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        MessageStore.shared.addListener(listener)
    }

    override fun onStop() {
        super.onStop()
        MessageStore.shared.removeListener(listener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (inSelectionMode) clearSelectionAndExit()
        binding = null
    }

    private companion object {
        const val ID_CHECK_IF_CREATED = 1
        const val ID_DELETE_SINGLE = 2
    }
}
