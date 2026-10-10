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
import io.github.maximus5.ffnotifyimporter.network.dedupSearchQuery
import io.github.maximus5.ffnotifyimporter.network.searchUrl
import io.github.maximus5.ffnotifyimporter.network.transactionUrl
import io.github.maximus5.ffnotifyimporter.posting.DuplicateCheckResult
import io.github.maximus5.ffnotifyimporter.posting.LinkedTransactionCheck
import io.github.maximus5.ffnotifyimporter.posting.checkForDuplicate
import io.github.maximus5.ffnotifyimporter.posting.findDuplicate
import io.github.maximus5.ffnotifyimporter.posting.verifyLinkedTransaction
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.rules.CurrenciesDb
import io.github.maximus5.ffnotifyimporter.rules.DraftIssue
import io.github.maximus5.ffnotifyimporter.rules.NoMatchReason
import io.github.maximus5.ffnotifyimporter.rules.ParseOutcome
import io.github.maximus5.ffnotifyimporter.rules.RemoteTransactionCandidate
import io.github.maximus5.ffnotifyimporter.rules.RuleEngine
import io.github.maximus5.ffnotifyimporter.rules.RulesDb
import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import io.github.maximus5.ffnotifyimporter.rules.applyApproxConversion
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

    /** The title *is* the outcome - the matched rule's own label, or "No match" - instead of a
     * generic "Test match" that made every dialog look the same regardless of which rule (if any)
     * actually fired.
     *
     * A fresh match (nothing linked yet, Firefly configured) runs the same preemptive duplicate
     * check every other create path does, *before* deciding what to show - a real tap-tested
     * case the user caught: a transaction genuinely already existed for it (confirmed by "Check
     * if created" separately finding it), but tapping the message just opened a blank review
     * screen instead of surfacing that first. Found -> the usual duplicate-confirm dialog; not
     * found (or the remote check failed/was skipped) -> [CreateTransactionActivity] opens as
     * before. The already-linked case (Open/Create Again) now runs [verifyLinkedThenShow] first -
     * see its own KDoc for why - instead of trusting the linked id blindly; "Create Again" itself
     * still leads into [CreateTransactionActivity] (see [startCreate]), same as every other manual
     * create path. The no-match/not-configured cases still show the plain dialog directly - there's
     * nothing to check or review/edit in either. */
    private fun showTestMatch(message: CapturedMessage) {
        val rules = RulesDb.getInstance(requireContext()).loadAllOrdered()
        val currencySymbols = CurrenciesDb.getInstance(requireContext()).symbolToCodeMap()
        val outcome = RuleEngine(rules, currencySymbols).match(message)
        val context = requireContext()

        when (outcome) {
            is ParseOutcome.NoMatch -> {
                AlertDialog.Builder(context)
                    .setTitle(R.string.test_match_no_match_title)
                    .setMessage(getString(reasonStringRes(outcome.reason)))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            is ParseOutcome.Matched -> {
                val configured = FireflyPrefs(context).isConfigured()
                val existingId = message.fireflyTransactionId
                when {
                    existingId == null && configured -> checkThenOpenCreateOrShowFound(context, outcome.draft, message)
                    existingId != null && configured -> verifyLinkedThenShow(context, outcome, message, existingId)
                    else -> showMatchedDialog(context, outcome, message, null)
                }
            }
        }
    }

    /** Verifies [existingId] still exists before trusting it, via the one shared
     * `posting.verifyLinkedTransaction` - **fixed a real "pretends everything is fine" bug, the
     * user's own catch/report (screenshotted)**: tapping an already-linked message used to go
     * straight to [showMatchedDialog] with zero verification, so a transaction deleted by hand in
     * Firefly left "Open transaction" pointing at a dead link with nothing on screen explaining
     * why. Still there -> [showMatchedDialog], now carrying the live-fetched candidate (see its
     * own KDoc for why that matters). Confirmed gone -> falls through to the exact same
     * search-then-show/create flow [checkThenOpenCreateOrShowFound] already runs for a fresh
     * match - a replacement match, if search finds one, is shown with [showDuplicateConfirmDialog]'s
     * `staleLinkedId` warning rather than presenting it as if nothing had ever been linked; nothing
     * found falls through to [CreateTransactionActivity] same as any other fresh match. The stale
     * link itself is left alone here, deliberately - see the inline comment at the call site for
     * why (the user's own catch: don't change state before the user's actually chosen something).
     * A verification failure (network/server error, *not* confirmed gone) shows the error and
     * stops - same "don't guess" rule every dedup check in this file now follows (see
     * `DuplicateCheckResult.SearchFailed`'s KDoc). */
    private fun verifyLinkedThenShow(context: Context, outcome: ParseOutcome.Matched, message: CapturedMessage, existingId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = verifyLinkedTransaction(FireflyPrefs(context), existingId)) {
                is LinkedTransactionCheck.Found ->
                    showMatchedDialog(context, outcome, message, existingId, result.candidate)
                is LinkedTransactionCheck.Gone ->
                    // Deliberately not cleared here - the user's own catch: wiping the stale
                    // link the instant it's confirmed gone, before any dialog is even shown,
                    // changes state with no user action behind it at all (a plain Cancel on
                    // whatever shows next would've silently unlinked the message). The link
                    // only actually changes as a side effect of the user's own choice: "Use
                    // Created" overwrites it with the newly found id, a successful create
                    // (fresh or "Create anyway") overwrites it with the new transaction's id.
                    // A Cancel leaves the stale id exactly as it was - re-verified the same
                    // way next time, not silently forgotten.
                    checkThenOpenCreateOrShowFound(context, outcome.draft, message, staleLinkedId = existingId)
                is LinkedTransactionCheck.CheckFailed ->
                    showResultDialog(context, R.string.check_search_failed_title, result.message)
            }
        }
    }

    /** Resolves the amount (if `DraftIssue.LOCAL_AMOUNT_MISSING`, same lookup `checkIfCreated`
     * already does quietly), then runs [checkForDuplicate] before deciding what to show - see
     * [showTestMatch]'s own KDoc for why. A still-unresolved amount after the lookup skips the
     * check entirely and opens the review screen directly, same "flag it, don't guess, don't
     * block" fallback used everywhere else `LOCAL_AMOUNT_MISSING` shows up - `transactionDedupHash`
     * requires a resolved amount, there's nothing to hash/search with yet. */
    private fun checkThenOpenCreateOrShowFound(
        context: Context,
        initialDraft: TransactionDraft,
        message: CapturedMessage,
        staleLinkedId: String? = null,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            var draft = initialDraft
            if (DraftIssue.LOCAL_AMOUNT_MISSING in draft.issues) {
                draft = resolveAmountQuietly(draft)
            }
            if (draft.amount == null) {
                startActivity(CreateTransactionActivity.intent(context, message))
                return@launch
            }
            when (val result = checkForDuplicate(context, draft)) {
                is DuplicateCheckResult.None ->
                    startActivity(CreateTransactionActivity.intent(context, message))
                is DuplicateCheckResult.SearchFailed ->
                    showResultDialog(context, R.string.check_search_failed_title, result.message)
                is DuplicateCheckResult.Found ->
                    showDuplicateConfirmDialog(context, result.id, message, knownCandidate = result.candidate, staleLinkedId = staleLinkedId)
                is DuplicateCheckResult.FoundMultiple -> {
                    Toast.makeText(context, getString(R.string.create_remote_duplicates_found, result.count), Toast.LENGTH_LONG).show()
                    showDuplicateConfirmDialog(context, result.mostRecentId, message, knownCandidate = result.candidate, staleLinkedId = staleLinkedId)
                }
            }
        }
    }

    /** Offered once there's a linked transaction to act on and a Firefly connection to post a
     * re-create to - "Open" sits in the neutral slot, "Create Again" in negative, same reasoning
     * as before this screen existed: the single most relevant secondary action gets the neutral
     * slot. [existingTransactionId] is only ever non-null here (see [showTestMatch]) - a fresh,
     * not-yet-linked, configured match never reaches this dialog at all anymore.
     *
     * [candidate] (the user's own catch/report): when [existingTransactionId] is set, it's always
     * been live-verified by now ([verifyLinkedThenShow]), so its real current fields are already
     * on hand - [buildCandidateBody] shows *those*, including the transaction id itself (missing
     * entirely before), instead of [buildMatchedBody]'s rule-re-parsed guess, which can disagree
     * with reality if the transaction was edited by hand in Firefly since this app posted it.
     * [candidate] is null only for the not-configured fallback (no id to look anything up with),
     * where the rule-derived body is the only thing there is to show. */
    private fun showMatchedDialog(
        context: Context,
        outcome: ParseOutcome.Matched,
        message: CapturedMessage,
        existingTransactionId: String?,
        candidate: RemoteTransactionCandidate? = null,
    ) {
        val draft = outcome.draft
        val body = if (existingTransactionId != null && candidate != null) {
            buildCandidateBody(existingTransactionId, candidate)
        } else {
            buildMatchedBody(draft)
        }
        val builder = AlertDialog.Builder(context)
            .setTitle(outcome.rule.label)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
        if (existingTransactionId != null) {
            builder.setNeutralButton(R.string.create_open_transaction) { _, _ -> openTransaction(context, existingTransactionId) }
            builder.setNegativeButton(R.string.create_again) { _, _ -> startCreate(context, message) }
        }
        builder.show()
    }

    /** Entry point for "Create Again" - opens [CreateTransactionActivity] directly, no
     * confirmation dialog and no preemptive duplicate check of its own.
     *
     * **Fixed a real "pointless extra step" bug, the user's own catch**: this used to show its own
     * "this will post a second transaction, are you sure?" confirmation dialog first, then (once
     * accepted) run its own preemptive [checkForDuplicate] - which, since [existingTransactionId]
     * already proves a matching transaction exists, would almost always re-find that exact same
     * transaction and show a *second*, differently-worded "possible duplicate, create anyway?"
     * dialog for the same thing the user had just confirmed one screen ago. Two redundant gates
     * before ever reaching the actual review screen. Now there's exactly one: [showTestMatch]
     * already only offers "Create Again" once [existingTransactionId] has been live-verified (see
     * [verifyLinkedThenShow]), [CreateTransactionActivity] shows an explicit warning banner instead
     * of a blocking dialog (informational while editing, not a gate), and the real "are you sure"
     * naturally happens exactly once, at the point that actually matters: the review screen's own
     * on-submit [checkForDuplicate] finds the same linked transaction and asks via
     * `CreateTransactionFragment.confirmCreateAnyway` right before actually posting. */
    private fun startCreate(context: Context, message: CapturedMessage) {
        startActivity(CreateTransactionActivity.intent(context, message))
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
     * "Create anyway" opens [CreateTransactionActivity] for review rather than posting [draft]
     * straight to Firefly - automatic creation bypassing the review screen is only ever allowed
     * for `posting.AutoCreateWorker`'s background auto-create-by-notification path (the user's own
     * explicit rule), never for a manual tap like this one, even when a likely duplicate has
     * already been confirmed by hand.
     *
     * [showCreateAnyway] drops to three actions - `dialog_duplicate_confirm_3.xml`, one row
     * instead of a grid - for [checkIfCreated]: that flow is an explicit "just tell me" search,
     * not a step on the way to posting a transaction, so "post one anyway" isn't a meaningful
     * option there; title/message also switch to the check-specific wording in that case.
     *
     * Always shows the found transaction's real fields (date/amount/description/accounts)
     * alongside the id, not just the bare "#<id>" - the user's own call, same reasoning as the
     * not-found dialog's candidate dump: seeing what was actually found is what lets the user
     * judge whether "Use Created" is really correct, not just trust the id. Bold field labels,
     * same [boldField] helper every field-summary dialog in this fragment uses - the user's own
     * catch, this one used to be plain text. [knownCandidate] is passed straight through when this
     * came from the remote search (already fetched in full, no reason to ask again); for a
     * local-log hit (`CreatedTransactionsDb` only ever has the id) this fetches it itself
     * (`fetchCandidateInfo`) - suspends briefly for that, a worthwhile trade for not showing a bare
     * id the user has no way to independently verify.
     *
     * "Use Created" is hidden when [foundId] already *is* [message]'s own linked id - the user's
     * own catch: relinking a message to the exact id it's already linked to is a pure no-op, and
     * offering it as if it were a real choice (next to "Open"/"Cancel") was just confusing. This
     * is the common case once `checkIfCreated`'s own linked-id-first check (see its KDoc) finds
     * the known id still valid and shows it here directly.
     *
     * [descriptionMismatch] (only ever true from [checkIfCreated]'s retry-without-description -
     * see its own KDoc) prepends an explicit warning that this match ignored description.
     * [staleLinkedId] (the user's own catch/report, screenshotted) prepends a second, independent
     * warning when [message] already had a *different* linked id that's been confirmed gone - see
     * `posting.verifyLinkedTransaction` - so "here's what matches now" doesn't read as if nothing
     * had ever been linked, when actually something was, and it's since vanished from Firefly. */
    private suspend fun showDuplicateConfirmDialog(
        context: Context,
        foundId: String,
        message: CapturedMessage,
        showCreateAnyway: Boolean = true,
        knownCandidate: RemoteTransactionCandidate? = null,
        descriptionMismatch: Boolean = false,
        staleLinkedId: String? = null,
    ) {
        val candidate = knownCandidate ?: fetchCandidateInfo(context, foundId)
        val layoutRes = if (showCreateAnyway) R.layout.dialog_duplicate_confirm_4 else R.layout.dialog_duplicate_confirm_3
        val view = LayoutInflater.from(context).inflate(layoutRes, null, false)
        val baseMessage = if (showCreateAnyway) {
            getString(R.string.create_duplicate_confirm_message, foundId)
        } else {
            getString(R.string.check_found_message, foundId)
        }
        val staleWarning = staleLinkedId?.let { getString(R.string.check_linked_transaction_gone_warning, it) + "\n\n" }.orEmpty()
        val warning = staleWarning + if (descriptionMismatch) getString(R.string.check_found_description_mismatch_warning) + "\n\n" else ""
        val dialogMessage = SpannableStringBuilder(warning + baseMessage)
        candidate?.let {
            dialogMessage.append("\n\n")
            dialogMessage.append(buildFoundTransactionInfo(it))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (showCreateAnyway) R.string.create_duplicate_confirm_title else R.string.check_found_title)
            .setMessage(dialogMessage)
            .setView(view)
            .create()

        val useCreatedButton = view.findViewById<View>(R.id.useCreatedButton)
        if (foundId == message.fireflyTransactionId) {
            useCreatedButton.visibility = View.GONE
        } else {
            useCreatedButton.setOnClickListener {
                MessageStore.shared.setTransactionId(message, foundId)
                Toast.makeText(context, getString(R.string.create_already_created_linked, foundId), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        view.findViewById<View>(R.id.openDuplicateButton).setOnClickListener { openTransaction(context, foundId) }
        if (showCreateAnyway) {
            view.findViewById<View>(R.id.createAnywayButton).setOnClickListener {
                startActivity(CreateTransactionActivity.intent(context, message))
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
    private suspend fun fetchCandidateInfo(context: Context, id: String): RemoteTransactionCandidate? =
        (verifyLinkedTransaction(FireflyPrefs(context), id) as? LinkedTransactionCheck.Found)?.candidate

    /** Labeled field summary appended to the duplicate-confirm dialog's message - reuses
     * `showTestMatch`'s own field labels (`test_match_label_*`) so this reads the same way as the
     * already-familiar match-preview dialog, rather than inventing a second, denser text format.
     * Seeing the found transaction's actual date/amount/description/accounts is what lets the
     * user judge a match independently, not just trust a bare id. Shows the foreign amount/
     * currency alongside the home one when present, for the same reason the home amount alone is
     * unreliable for matching (see `network.dedupSearchQuery`'s KDoc) - it's informative here too,
     * not just for searching. Bold field labels, same [boldField] helper [buildMatchedBody]/
     * [buildCandidateBody] use - the user's own catch: this one used to be plain text, reading
     * inconsistently next to the other two field-summary dialogs in this fragment. */
    private fun buildFoundTransactionInfo(candidate: RemoteTransactionCandidate): CharSequence {
        val amountText = if (candidate.foreignAmount != null && candidate.foreignCurrencyCode != null) {
            "${candidate.amount.toPlainString()} ${candidate.currencyCode} " +
                "(${candidate.foreignAmount.toPlainString()} ${candidate.foreignCurrencyCode})"
        } else {
            "${candidate.amount.toPlainString()} ${candidate.currencyCode}"
        }
        val text = SpannableStringBuilder()
        val field = boldField(text)
        field(getString(R.string.test_match_label_date), formatDraftDate(candidate.date))
        field(getString(R.string.test_match_label_amount), amountText)
        field(getString(R.string.test_match_label_description), candidate.description)
        candidate.sourceName?.let { field(getString(R.string.test_match_label_source), it) }
        candidate.destinationName?.let { field(getString(R.string.test_match_label_destination), it) }
        candidate.notes?.takeIf { it.isNotBlank() }?.let { field(getString(R.string.test_match_label_notes), it) }
        candidate.category?.let { field(getString(R.string.test_match_label_category), it) }
        if (candidate.tags.isNotEmpty()) field(getString(R.string.test_match_label_tags), candidate.tags.joinToString(", "))
        return text
    }

    /** Shared bold-label field appender behind every field-summary dialog in this fragment
     * ([buildMatchedBody]/[buildCandidateBody]/[buildFoundTransactionInfo]) - appends "\n<label>
     * <value>" to [text] (no leading newline the very first time), bolding just the label, so a
     * multi-field dialog reads as a list of fields rather than a wall of same-weight text. */
    private fun boldField(text: SpannableStringBuilder): (String, String) -> Unit = { label, value ->
        if (text.isNotEmpty()) text.append("\n")
        val labelStart = text.length
        text.append(label)
        text.setSpan(StyleSpan(Typeface.BOLD), labelStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append(" ").append(value)
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
     * list of fields rather than a wall of same-weight text - via the shared [boldField] helper. */
    private fun buildMatchedBody(draft: TransactionDraft): CharSequence {
        val text = SpannableStringBuilder()
        val field = boldField(text)

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

    /** Real-data counterpart to [buildMatchedBody] - same bold-label field-summary style, but
     * built from a live-verified [RemoteTransactionCandidate] instead of a freshly re-parsed
     * [TransactionDraft]. The user's own catch/report: the rule-derived draft can disagree with
     * what's actually in Firefly if the transaction was edited by hand there since this app
     * posted it, so showing the draft's own fields next to a dialog about an already-*existing*
     * transaction was actively misleading. Leads with the transaction id itself - previously
     * missing from this dialog entirely. */
    private fun buildCandidateBody(transactionId: String, candidate: RemoteTransactionCandidate): CharSequence {
        val text = SpannableStringBuilder()
        val field = boldField(text)

        field(getString(R.string.test_match_label_transaction_id), "#$transactionId")
        candidate.sourceName?.let { field(getString(R.string.test_match_label_source), it) }
        candidate.destinationName?.let { field(getString(R.string.test_match_label_destination), it) }
        field(getString(R.string.test_match_label_amount), "${candidate.amount.toPlainString()} ${candidate.currencyCode}")
        if (candidate.foreignAmount != null && candidate.foreignCurrencyCode != null) {
            field(getString(R.string.test_match_label_foreign), "${candidate.foreignAmount.toPlainString()} ${candidate.foreignCurrencyCode}")
        }
        field(getString(R.string.test_match_label_date), formatDraftDate(candidate.date))
        field(getString(R.string.test_match_label_description), candidate.description)
        candidate.notes?.takeIf { it.isNotBlank() }?.let { field(getString(R.string.test_match_label_notes), it) }
        candidate.category?.let { field(getString(R.string.test_match_label_category), it) }
        if (candidate.tags.isNotEmpty()) field(getString(R.string.test_match_label_tags), candidate.tags.joinToString(", "))
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
        val context = requireContext()
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, ID_OPEN_IN_FIREFLY, 0, R.string.messages_action_open_in_firefly)
            .isEnabled = message.fireflyTransactionId != null
        popup.menu.add(0, ID_CHECK_IF_CREATED, 1, R.string.messages_check_if_created)
        popup.menu.add(0, ID_DELETE_SINGLE, 2, R.string.messages_action_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_OPEN_IN_FIREFLY -> message.fireflyTransactionId?.let { openTransaction(context, it) }
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
            .setNegativeButton(R.string.messages_action_delete) { _, _ -> MessageStore.shared.remove(message) }
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
     * **Fixed a real "ignores what it already knows" bug, the user's own catch/report**: if
     * [message] already has a linked id, that's a direct answer to "was this created?" on its
     * own - no content search needed, and a content search can genuinely disagree (the user's own
     * example: editing a transaction's description/notes in Firefly by hand can make it stop
     * matching this app's own hash/query, so the old code searched anyway, found a *different*
     * transaction, and offered to link to that instead - confusing and simply wrong when the
     * known id was never in question). Now checks the linked id first, via the one shared
     * `posting.verifyLinkedTransaction`: still there -> shown directly, search skipped entirely;
     * confirmed gone -> falls through to the search below same as before, now carrying the gone
     * id as `staleLinkedId` so a replacement match (if any) says so explicitly; a check failure
     * stops and shows the error, same "don't guess" rule every dedup check in this file follows.
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

        viewLifecycleOwner.lifecycleScope.launch {
            val prefs = FireflyPrefs(context)
            val existingId = message.fireflyTransactionId
            var staleLinkedId: String? = null
            if (existingId != null) {
                when (val linked = verifyLinkedTransaction(prefs, existingId)) {
                    is LinkedTransactionCheck.Found -> {
                        showDuplicateConfirmDialog(context, existingId, message, showCreateAnyway = false, knownCandidate = linked.candidate)
                        return@launch
                    }
                    is LinkedTransactionCheck.CheckFailed -> {
                        showResultDialog(context, R.string.check_search_failed_title, linked.message)
                        return@launch
                    }
                    is LinkedTransactionCheck.Gone -> staleLinkedId = existingId
                }
            }

            val rules = RulesDb.getInstance(context).loadAllOrdered()
            val currencySymbols = CurrenciesDb.getInstance(context).symbolToCodeMap()
            val outcome = RuleEngine(rules, currencySymbols).match(message)
            val draft = (outcome as? ParseOutcome.Matched)?.draft
            if (draft == null) {
                Toast.makeText(context, R.string.messages_check_no_rule_matched, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val resolved = resolveAmountQuietly(draft)
            if (resolved.amount == null) {
                Toast.makeText(context, R.string.create_amount_not_ready, Toast.LENGTH_SHORT).show()
                return@launch
            }
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
                        context, r.id, message,
                        showCreateAnyway = false, knownCandidate = r.candidate, descriptionMismatch = matchedWithoutDescription,
                        staleLinkedId = staleLinkedId,
                    )
                is DuplicateCheckResult.FoundMultiple -> {
                    Toast.makeText(
                        context,
                        getString(R.string.create_remote_duplicates_found, r.count),
                        Toast.LENGTH_LONG,
                    ).show()
                    showDuplicateConfirmDialog(
                        context, r.mostRecentId, message,
                        showCreateAnyway = false, knownCandidate = r.candidate, descriptionMismatch = matchedWithoutDescription,
                        staleLinkedId = staleLinkedId,
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
            .setNegativeButton(R.string.messages_action_delete) { _, _ ->
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
        const val ID_OPEN_IN_FIREFLY = 1
        const val ID_CHECK_IF_CREATED = 2
        const val ID_DELETE_SINGLE = 3
    }
}
