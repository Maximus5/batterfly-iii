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
import io.github.maximus5.ffnotifyimporter.network.FireflyTransactionRequest
import io.github.maximus5.ffnotifyimporter.network.transactionUrl
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
import io.github.maximus5.ffnotifyimporter.rules.remoteCandidateMatches
import io.github.maximus5.ffnotifyimporter.rules.transactionDedupHash
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
        adapter = MessagesAdapter(onClick = ::handleClick, onLongClick = ::handleLongClick)
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

        // Offered once there's an actual draft to post and a Firefly connection to post it to -
        // reads the latest `draft` at click time, not a stale copy, so it still works after
        // fetchApproxConversionIfNeeded swaps in a resolved amount underneath it. A message that
        // already has a linked transaction (`message.fireflyTransactionId`) gets "Open" in the
        // neutral slot instead of "Create Transaction" - "Create Again" moves to negative rather
        // than displacing it, so the single most relevant secondary action always has the neutral
        // slot (create when nothing's linked yet, open when something is).
        val existingTransactionId = message.fireflyTransactionId
        if (draft != null && FireflyPrefs(context).isConfigured()) {
            if (existingTransactionId != null) {
                builder.setNeutralButton(R.string.create_open_transaction) { _, _ ->
                    openTransaction(context, existingTransactionId)
                }
                builder.setNegativeButton(R.string.create_again) { _, _ -> draft?.let { startCreate(it, message) } }
            } else {
                builder.setNeutralButton(R.string.test_match_create) { _, _ -> draft?.let { startCreate(it, message) } }
            }
        }

        val dialog = builder.show()

        draft?.let {
            fetchApproxConversionIfNeeded(it, dialog) { updated -> draft = updated }
        }
    }

    /** When a draft is missing its home-currency amount (foreign currency only), fetches a real
     * exchange rate for the message's own date and swaps the already-shown dialog's body in place
     * once it resolves, reporting the resolved draft back via [onUpdated] so "Создать" posts the
     * right amount. Preview-only beyond that - a failed lookup just appends why, it doesn't block
     * or retry anything. */
    private fun fetchApproxConversionIfNeeded(
        draft: TransactionDraft,
        dialog: AlertDialog,
        onUpdated: (TransactionDraft) -> Unit,
    ) {
        if (DraftIssue.LOCAL_AMOUNT_MISSING !in draft.issues) return
        val foreignCurrency = draft.foreignCurrencyCode ?: return
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = ExchangeRateClient().fetchRate(foreignCurrency, draft.currencyCode, draft.date)) {
                is ExchangeRateClient.Result.Success -> {
                    val approxTag = FireflyPrefs(context).getApproxConversionTag()
                    val updated = applyApproxConversion(draft, result.rate, approxTag)
                    dialog.setMessage(buildMatchedBody(updated))
                    onUpdated(updated)
                }
                is ExchangeRateClient.Result.Failure -> {
                    val updatedBody = SpannableStringBuilder(buildMatchedBody(draft))
                        .append("\n")
                        .append(getString(R.string.test_match_rate_lookup_failed, result.message))
                    dialog.setMessage(updatedBody)
                }
            }
        }
    }

    /** Entry point for "Создать"/"Create Again" - checks the dedup log (if enabled) before
     * actually posting, per the user's own call on this: manual creation asks for confirmation on
     * a likely duplicate rather than silently blocking or silently allowing it. Local dedup first
     * (`CreatedTransactionsDb`, this app's own log); only if *that* comes up empty does it also
     * ask Firefly itself (`FireflyApiClient.searchNearbyTransactions`) - a second device/app
     * notifying about the same real payment wouldn't be in this app's own log at all, so a clean
     * local lookup alone can't rule that out. */
    private fun startCreate(draft: TransactionDraft, message: CapturedMessage) {
        val context = requireContext()
        if (draft.amount == null) {
            Toast.makeText(context, R.string.create_amount_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = FireflyPrefs(context)
        if (!prefs.isDedupEnabled()) {
            postTransaction(draft, message)
            return
        }

        val includeDescription = prefs.isDedupIncludeDescription()
        val windowMinutes = prefs.getDedupWindowMinutes()
        val hash = transactionDedupHash(draft, includeDescription)
        val localFoundId = CreatedTransactionsDb.getInstance(context)
            .findWithinWindow(hash, includeDescription, draft.date.toEpochMilli(), windowMinutes)

        if (localFoundId != null) {
            showDuplicateConfirmDialog(context, localFoundId, draft, message)
            return
        }

        // Only an asset account has a Firefly id on this app's side (see FireflyAccount's KDoc) -
        // without one there's no account to scope the remote query to, so it's skipped entirely
        // rather than searching unscoped (same amount/date could coincidentally match an unrelated
        // transaction on a different account).
        val accountId = draft.sourceAccountId ?: draft.destinationAccountId
        if (accountId == null) {
            postTransaction(draft, message)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val client = FireflyApiClient(prefs.getBaseUrl(), prefs.getToken())
            when (val result = client.searchNearbyTransactions(accountId, draft.date, windowMinutes)) {
                is FireflyApiClient.Result.Success -> {
                    val matches = result.value
                        .map {
                            RemoteTransactionCandidate(
                                groupId = it.groupId,
                                date = it.date,
                                amount = it.amount,
                                currencyCode = it.currencyCode,
                                description = it.description,
                                sourceId = it.sourceId,
                                sourceName = it.sourceName,
                                destinationId = it.destinationId,
                                destinationName = it.destinationName,
                            )
                        }
                        .filter { remoteCandidateMatches(draft, it, includeDescription, windowMinutes) }
                    when {
                        matches.isEmpty() -> postTransaction(draft, message)
                        matches.size == 1 -> showDuplicateConfirmDialog(context, matches.first().groupId, draft, message)
                        else -> {
                            // More than one plausible match - the user's own call: an extra
                            // warning on top of (not instead of) the usual duplicate-confirm
                            // dialog, since this is a *stronger* signal of a real duplicate, not a
                            // weaker one. The dialog itself only has room for one "found" id - the
                            // most recent match, same tie-break `findWithinWindow` already uses.
                            Toast.makeText(
                                context,
                                getString(R.string.create_remote_duplicates_found, matches.size),
                                Toast.LENGTH_LONG,
                            ).show()
                            val mostRecent = matches.maxBy { it.date }
                            showDuplicateConfirmDialog(context, mostRecent.groupId, draft, message)
                        }
                    }
                }
                is FireflyApiClient.Result.Failure -> {
                    // A failed remote check shouldn't block creation - it's an extra safety net on
                    // top of the local dedup log above, not the primary mechanism; that one already
                    // ran and found nothing.
                    postTransaction(draft, message)
                }
            }
        }
    }

    /** Four actions, not three ("Use Created"/"Open"/"Create anyway"/"Cancel", Cancel always
     * last) - more than `AlertDialog.Builder`'s positive/negative/neutral slots can hold, so this
     * is a plain custom view (`dialog_duplicate_confirm.xml`, same reasoning as the rule editor's
     * "Test results" dialog) instead of the native button bar. "Open" deliberately doesn't
     * dismiss - it's "let me go check first", not a decision, so the dialog is still there to act
     * on once the user comes back from Firefly. "Use Created" links the *found* transaction's id
     * to [message] instead of posting a new one - the dedup check already did the work of finding
     * it, no reason to make the user go dig it up by hand in Firefly. */
    private fun showDuplicateConfirmDialog(
        context: Context,
        foundId: String,
        draft: TransactionDraft,
        message: CapturedMessage,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_duplicate_confirm, null, false)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.create_duplicate_confirm_title)
            .setMessage(getString(R.string.create_duplicate_confirm_message, foundId))
            .setView(view)
            .create()

        view.findViewById<View>(R.id.useCreatedButton).setOnClickListener {
            MessageStore.shared.setTransactionId(message, foundId)
            Toast.makeText(context, getString(R.string.create_already_created_linked, foundId), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.openDuplicateButton).setOnClickListener { openTransaction(context, foundId) }
        view.findViewById<View>(R.id.createAnywayButton).setOnClickListener {
            postTransaction(draft, message)
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.cancelDuplicateButton).setOnClickListener { dialog.dismiss() }

        dialog.show()
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
        inSelectionMode = false
        (activity as? MainActivity)?.exitSelectionMode()
    }

    private fun confirmDeleteSelected() {
        val toDelete = adapter.selectedMessages()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_messages_confirm_title)
            .setMessage(R.string.delete_messages_confirm_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                toDelete.forEach { MessageStore.shared.remove(it) }
                clearSelectionAndExit()
            }
            .setNegativeButton(android.R.string.cancel, null)
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
}
