package io.github.maximus5.ffnotifyimporter.posting

import android.content.Context
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.network.FireflyTransactionCandidate
import io.github.maximus5.ffnotifyimporter.network.dedupSearchQuery
import io.github.maximus5.ffnotifyimporter.prefs.FireflyPrefs
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionsDb
import io.github.maximus5.ffnotifyimporter.rules.RemoteTransactionCandidate
import io.github.maximus5.ffnotifyimporter.rules.TransactionDraft
import io.github.maximus5.ffnotifyimporter.rules.remoteCandidateMatches
import io.github.maximus5.ffnotifyimporter.rules.transactionDedupHash

/**
 * Shared duplicate-check pipeline - extracted out of `ui.MessagesFragment` (where it originally
 * lived as private members) so [AutoCreateWorker] can reuse the exact same logic a manual
 * "Создать" tap already goes through, rather than re-deriving it a second way and risking the two
 * paths drifting apart. `ui.MessagesFragment` still owns everything UI-specific (the
 * duplicate-confirm dialog, "Check if created"'s retry-without-description) - this file is only
 * the Context/network-dependent lookup itself.
 */
sealed interface DuplicateCheckResult {
    /** [rawCandidates] is whatever Firefly's search actually returned before client-side
     * filtering ([remoteCandidateMatches]) rejected every one of them - empty for a local-only
     * miss (dedup disabled, no query possible, or the local log already found nothing and the
     * remote search genuinely returned nothing either). A non-empty list alongside [None] means
     * Firefly found *something* for the query but nothing passed the exact-match check -
     * `MessagesFragment.checkIfCreated` shows each candidate's actual field values next to the
     * query, so a mismatch (case, whitespace, a stale date, ...) is visible on sight instead of
     * needing another debug round-trip to find. */
    data class None(val rawCandidates: List<RemoteTransactionCandidate> = emptyList()) : DuplicateCheckResult

    /** [candidate] is the full matched record when this came from the remote search (already
     * fetched, no reason to ask Firefly for it again) - null for a local-log hit, which only ever
     * has the id ([CreatedTransactionsDb] doesn't store full fields); the UI fetches it itself in
     * that case (`FireflyApiClient.getTransaction`) so it can always show what the found
     * transaction actually looks like, not just a bare id. */
    data class Found(val id: String, val candidate: RemoteTransactionCandidate? = null) : DuplicateCheckResult
    data class FoundMultiple(val mostRecentId: String, val count: Int, val candidate: RemoteTransactionCandidate? = null) : DuplicateCheckResult

    /** The remote search's HTTP call itself failed (network error, non-2xx response) - kept
     * distinct from [None] so a caller that cares (`MessagesFragment.checkIfCreated`) can show
     * the *real* reason instead of silently reporting "not found" for what was actually an error.
     * Every other caller (the create-flow's preemptive check, [AutoCreateWorker]) treats this the
     * same as [None] - best-effort, falls through to posting normally rather than blocking on it. */
    data class SearchFailed(val message: String) : DuplicateCheckResult
}

/** Gated by [FireflyPrefs.isDedupEnabled] - the setting is about whether *creating* a transaction
 * checks for a duplicate first, which is what this gates for (every caller except
 * `MessagesFragment.checkIfCreated`'s explicit, user-initiated "just tell me" action, which calls
 * [findDuplicate] directly instead, bypassing this toggle entirely - a separate question from
 * whether creation should pause on one). */
suspend fun checkForDuplicate(context: Context, draft: TransactionDraft): DuplicateCheckResult {
    val prefs = FireflyPrefs(context)
    if (!prefs.isDedupEnabled()) return DuplicateCheckResult.None()
    return findDuplicate(context, draft, prefs, prefs.isDedupIncludeDescription())
}

/** Local dedup log first ([CreatedTransactionsDb], this app's own log); only if *that* comes up
 * empty does it also ask Firefly itself (`FireflyApiClient.searchTransactionsByQuery`) - a second
 * device/app notifying about the same real payment wouldn't be in this app's own log at all, so a
 * clean local lookup alone can't rule that out. [includeDescription] is an explicit parameter
 * rather than always reading `prefs.isDedupIncludeDescription()` so `MessagesFragment.
 * checkIfCreated` can call this twice with different values (see its own KDoc for why). */
suspend fun findDuplicate(context: Context, draft: TransactionDraft, prefs: FireflyPrefs, includeDescription: Boolean): DuplicateCheckResult {
    val windowMinutes = prefs.getDedupWindowMinutes()
    val hash = transactionDedupHash(draft, includeDescription)
    val localFoundId = CreatedTransactionsDb.getInstance(context)
        .findWithinWindow(hash, includeDescription, draft.date.toEpochMilli(), windowMinutes)
    if (localFoundId != null) return DuplicateCheckResult.Found(localFoundId)

    // Known fragility - see dedupSearchQuery's KDoc: Firefly's account_is operators silently
    // zero out the *entire* search if the given name doesn't exactly match a real account, which
    // this app's often-free-typed account names can't guarantee. Not yet resolved (see TODO.md) -
    // two fixes were proposed and rejected, so this still searches by name as-is.
    val query = dedupSearchQuery(
        amount = draft.amount,
        currencyCode = draft.currencyCode,
        foreignAmount = draft.foreignAmount,
        foreignCurrencyCode = draft.foreignCurrencyCode,
        centerDate = draft.date,
        windowMinutes = windowMinutes,
        sourceName = draft.sourceName,
        destinationName = draft.destinationName,
        description = draft.description,
        includeDescription = includeDescription,
    ) ?: return DuplicateCheckResult.None()

    val client = FireflyApiClient(prefs.getBaseUrl(), prefs.getToken())
    return when (val result = client.searchTransactionsByQuery(query)) {
        is FireflyApiClient.Result.Success -> {
            val candidates = result.value.map { it.toRemoteCandidate() }
            val matches = candidates.filter { remoteCandidateMatches(draft, it, includeDescription, windowMinutes) }
            when {
                matches.isEmpty() -> DuplicateCheckResult.None(rawCandidates = candidates)
                matches.size == 1 -> DuplicateCheckResult.Found(matches.first().groupId, matches.first())
                // The dialog only has room for one "found" id - the most recent match, same
                // tie-break CreatedTransactionsDb.findWithinWindow already uses.
                else -> matches.maxBy { it.date }.let { DuplicateCheckResult.FoundMultiple(it.groupId, matches.size, it) }
            }
        }
        is FireflyApiClient.Result.Failure -> DuplicateCheckResult.SearchFailed(result.message)
    }
}

fun FireflyTransactionCandidate.toRemoteCandidate() = RemoteTransactionCandidate(
    groupId = groupId,
    date = date,
    amount = amount,
    currencyCode = currencyCode,
    foreignAmount = foreignAmount,
    foreignCurrencyCode = foreignCurrencyCode,
    description = description,
    sourceId = sourceId,
    sourceName = sourceName,
    destinationId = destinationId,
    destinationName = destinationName,
)
