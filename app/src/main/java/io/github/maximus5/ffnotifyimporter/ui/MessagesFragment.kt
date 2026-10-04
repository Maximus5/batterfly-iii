package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
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
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionsDb
import io.github.maximus5.ffnotifyimporter.rules.CurrenciesDb
import io.github.maximus5.ffnotifyimporter.rules.DraftIssue
import io.github.maximus5.ffnotifyimporter.rules.NoMatchReason
import io.github.maximus5.ffnotifyimporter.rules.ParseOutcome
import io.github.maximus5.ffnotifyimporter.rules.RuleEngine
import io.github.maximus5.ffnotifyimporter.rules.RulesDb
import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import io.github.maximus5.ffnotifyimporter.rules.applyApproxConversion
import io.github.maximus5.ffnotifyimporter.rules.transactionDedupHash
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

        // "Создать" only offered once there's an actual draft to post and a Firefly connection to
        // post it to - reads the latest `draft` at click time, not a stale copy, so it still works
        // after fetchApproxConversionIfNeeded swaps in a resolved amount underneath it.
        if (draft != null && FireflyPrefs(context).isConfigured()) {
            builder.setNeutralButton(R.string.test_match_create) { _, _ -> draft?.let { startCreate(it) } }
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

    /** Entry point for the "Создать" button - checks the dedup log (if enabled) before actually
     * posting, per the user's own call on this: manual creation asks for confirmation on a likely
     * duplicate rather than silently blocking or silently allowing it. */
    private fun startCreate(draft: TransactionDraft) {
        val context = requireContext()
        if (draft.amount == null) {
            Toast.makeText(context, R.string.create_amount_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = FireflyPrefs(context)
        val hash = transactionDedupHash(draft, prefs.isDedupIncludeDescription())
        val isDuplicate = prefs.isDedupEnabled() &&
            CreatedTransactionsDb.getInstance(context)
                .existsWithinWindow(hash, draft.date.toEpochMilli(), prefs.getDedupWindowMinutes())

        if (isDuplicate) {
            AlertDialog.Builder(context)
                .setTitle(R.string.create_duplicate_confirm_title)
                .setMessage(R.string.create_duplicate_confirm_message)
                .setPositiveButton(R.string.create_duplicate_confirm_button) { _, _ -> postTransaction(draft, hash) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            postTransaction(draft, hash)
        }
    }

    private fun postTransaction(draft: TransactionDraft, hash: String) {
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
                    CreatedTransactionsDb.getInstance(context).recordCreated(hash, draft.date.toEpochMilli(), result.value)
                    showResultDialog(context, R.string.create_success_title, getString(R.string.create_success_message, result.value))
                }
                is FireflyApiClient.Result.Failure -> {
                    showResultDialog(context, R.string.create_failed_title, result.message)
                }
            }
        }
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
        field(getString(R.string.test_match_label_description), draft.description)
        field(getString(R.string.test_match_label_notes), draft.notes)
        draft.category?.let { field(getString(R.string.test_match_label_category), it) }
        if (draft.tags.isNotEmpty()) field(getString(R.string.test_match_label_tags), draft.tags.joinToString(", "))
        draft.issues.forEach { issue ->
            text.append("\n").append(
                when (issue) {
                    DraftIssue.LOCAL_AMOUNT_MISSING -> getString(R.string.test_match_issue_local_amount_missing)
                },
            )
        }
        return text
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
