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
import io.github.maximus5.ffnotifyimporter.network.dedupSearchQuery
import io.github.maximus5.ffnotifyimporter.network.searchUrl
import io.github.maximus5.ffnotifyimporter.network.transactionUrl
import io.github.maximus5.ffnotifyimporter.posting.DuplicateCheckResult
import io.github.maximus5.ffnotifyimporter.posting.checkForDuplicate
import io.github.maximus5.ffnotifyimporter.posting.findDuplicate
import io.github.maximus5.ffnotifyimporter.posting.toRemoteCandidate
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

    /** Verifies [existingId] still exists before trusting it - **fixed a real "pretends everything
     * is fine" bug, the user's own catch/report (screenshotted)**: tapping an already-linked
     * message used to go straight to [showMatchedDialog] with zero verification, so a transaction
     * deleted by hand in Firefly left "Open transaction" pointing at a dead link with nothing on
     * screen explaining why. Still there -> [showMatchedDialog] exactly as before. Confirmed gone
     * (`404`) -> falls through to the exact same search-then-show/create flow
     * [checkThenOpenCreateOrShowFound] already runs for a fresh match - a replacement match, if
     * search finds one, is shown with [showDuplicateConfirmDialog]'s `staleLinkedId` warning rather
     * than presenting it as if nothing had ever been linked; nothing found falls through to
     * [CreateTransactionActivity] same as any other fresh match. The stale link itself is left
     * alone here, deliberately - see the inline comment at the call site for why (the user's own
     * catch: don't change state before the user's actually chosen something). A verification
     * failure (network/server error, *not* confirmed
     * gone) shows the error and stops - same "don't guess" rule every dedup check in this file now
     * follows (see `DuplicateCheckResult.SearchFailed`'s KDoc). */
    private fun verifyLinkedThenShow(context: Context, outcome: ParseOutcome.Matched, message: CapturedMessage, existingId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val prefs = FireflyPrefs(context)
            val client = FireflyApiClient(prefs.getBaseUrl(), prefs.getToken())
            when (val result = client.getTransaction(existingId)) {
                is FireflyApiClient.Result.Success -> {
                    if (result.value != null) {
                        showMatchedDialog(context, outcome, message, existingId)
                    } else {
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
                    }
                }
                is FireflyApiClient.Result.Failure ->
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
     * not-yet-linked, configured match never reaches this dialog at all anymore. */
    private fun showMatchedDialog(context: Context, outcome: ParseOutcome.Matched, message: CapturedMessage, existingTransactionId: String?) {
        val draft = outcome.draft
        val builder = AlertDialog.Builder(context)
            .setTitle(outcome.rule.label)
            .setMessage(buildMatchedBody(draft))
            .setPositiveButton(android.R.string.ok, null)
        if (existingTransactionId != null) {
            builder.setNeutralButton(R.string.create_open_transaction) { _, _ -> openTransaction(context, existingTransactionId) }
            builder.setNegativeButton(R.string.create_again) { _, _ -> confirmCreateAgain(context, draft, message, existingTransactionId) }
        }
        builder.show()
    }

    /** Confirms before "Create Again" actually runs - the user's own call, after noticing the tap
     * had no confirmation at all. [startCreate] below still runs its own preemptive
     * [checkForDuplicate] (same reasoning as [checkThenOpenCreateOrShowFound]: surface a found
     * duplicate's info before the user even gets to the editor, rather than only at the very end),
     * but no longer *posts* on a clean result - every path out of [startCreate] now lands in
     * [CreateTransactionActivity] for review first, posting is never implicit. This dialog is the
     * one guaranteed "are you sure", shown every time regardless of dedup settings, since
     * [existingTransactionId] already proves a transaction exists for this message. Cancel always
     * rightmost/last (house convention, AGENTS.md). */
    private fun confirmCreateAgain(context: Context, draft: TransactionDraft, message: CapturedMessage, existingTransactionId: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.create_again_confirm_title)
            .setMessage(getString(R.string.create_again_confirm_message, existingTransactionId))
            .setNegativeButton(R.string.create_again) { _, _ -> startCreate(draft, message) }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    /** Entry point for "Create Again" - gated behind [confirmCreateAgain] above. Never posts
     * directly (see that function's own KDoc for why this changed): a clean/failed duplicate
     * check opens [CreateTransactionActivity] for review, same as [checkThenOpenCreateOrShowFound]
     * does for a fresh match; a found duplicate shows the usual duplicate-confirm dialog instead,
     * whose own "Create anyway" also opens that same review screen rather than posting from here. */
    private fun startCreate(draft: TransactionDraft, message: CapturedMessage) {
        val context = requireContext()
        if (draft.amount == null) {
            Toast.makeText(context, R.string.create_amount_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = checkForDuplicate(context, draft)) {
                is DuplicateCheckResult.None ->
                    startActivity(CreateTransactionActivity.intent(context, message))
                is DuplicateCheckResult.SearchFailed ->
                    showResultDialog(context, R.string.check_search_failed_title, result.message)
                is DuplicateCheckResult.Found -> showDuplicateConfirmDialog(context, result.id, message, knownCandidate = result.candidate)
                is DuplicateCheckResult.FoundMultiple -> {
                    Toast.makeText(
                        context,
                        getString(R.string.create_remote_duplicates_found, result.count),
                        Toast.LENGTH_LONG,
                    ).show()
                    showDuplicateConfirmDialog(context, result.mostRecentId, message, knownCandidate = result.candidate)
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
     * judge whether "Use Created" is really correct, not just trust the id. [knownCandidate] is
     * passed straight through when this came from the remote search (already fetched in full,
     * no reason to ask again); for a local-log hit (`CreatedTransactionsDb` only ever has the id)
     * this fetches it itself (`FireflyApiClient.getTransaction`) - suspends briefly for that, a
     * worthwhile trade for not showing a bare id the user has no way to independently verify.
     * [descriptionMismatch] (only ever true from [checkIfCreated]'s retry-without-description -
     * see its own KDoc) prepends an explicit warning that this match ignored description.
     * [staleLinkedId] (the user's own catch/report, screenshotted) prepends a second, independent
     * warning when [message] already had a *different* linked id that's been confirmed gone (404)
     * - see [staleLinkedIdOrNull] - so "here's what matches now" doesn't read as if nothing had
     * ever been linked, when actually something was, and it's since vanished from Firefly. */
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
    private suspend fun fetchCandidateInfo(context: Context, id: String): RemoteTransactionCandidate? {
        val prefs = FireflyPrefs(context)
        val client = FireflyApiClient(prefs.getBaseUrl(), prefs.getToken())
        return when (val result = client.getTransaction(id)) {
            is FireflyApiClient.Result.Success -> result.value?.toRemoteCandidate()
            is FireflyApiClient.Result.Failure -> null
        }
    }

    /** [message]'s own linked id (`CapturedMessage.fireflyTransactionId`), live-verified -
     * independent of `posting.findDuplicate`'s own local-log verification (`CreatedTransactionsDb`'s
     * hash log and a message's own linked id are tracked separately and can diverge: "Use Created"
     * links a message to an id with no local-log row at all, and the reverse is possible too).
     * The user's own catch/report: tapping an already-linked message used to go straight to
     * [showMatchedDialog] with zero verification, so a transaction deleted by hand in Firefly left
     * "Open transaction" pointing at a dead link with nothing on screen explaining why.
     * Returns the id only when it's *confirmed* gone (`getTransaction`'s `404` -> `null`-success
     * mapping, see the `network` section's KDoc) - null for "nothing linked", "still there", and a
     * failed check alike, since this is a secondary cross-check feeding a warning message, not
     * something worth blocking the caller's own primary flow over. */
    private suspend fun staleLinkedIdOrNull(context: Context, message: CapturedMessage): String? {
        val existingId = message.fireflyTransactionId ?: return null
        val prefs = FireflyPrefs(context)
        val client = FireflyApiClient(prefs.getBaseUrl(), prefs.getToken())
        return when (val result = client.getTransaction(existingId)) {
            is FireflyApiClient.Result.Success -> existingId.takeIf { result.value == null }
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
            val staleLinkedId = staleLinkedIdOrNull(context, message)
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
