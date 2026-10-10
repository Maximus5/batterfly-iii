package io.github.maximus5.ffnotifyimporter.rules

import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.parser.extractGroups
import io.github.maximus5.ffnotifyimporter.parser.parseAmount
import io.github.maximus5.ffnotifyimporter.parser.parseDate
import java.math.BigDecimal
import java.time.Instant

/** Collapses a run of whitespace (including a stray double space some notification sources emit
 * - the user's own catch: the same merchant's name came through with a single space from a bank
 * notification but a double space from Google Wallet's) down to one, so [RuleEngine.renderOptional]
 * can normalize [ParseRule.sourceAccountName]/[destinationAccountName]/[categoryTemplate] before
 * they're used for dedup matching or posted to Firefly - otherwise the two sources would end up as
 * two different Firefly accounts for what's really the same counterparty. */
private val whitespaceRun = Regex("\\s+")

/**
 * Matches a [CapturedMessage] against an ordered list of rules. List order IS priority: the
 * first rule whose pattern matches AND yields a parsable amount wins, nothing further is tried.
 * (No "Ambiguous" outcome - by design, see TODO.md/ARCHITECTURE.md history.)
 *
 * [currencySymbols] (symbol -> code, from [CurrenciesDb.symbolToCodeMap]) normalizes a captured
 * currency symbol ("€") to its code ("EUR") before it's compared against [ParseRule.homeCurrency]
 * or stored - banks print symbols far more often than ISO codes. Defaults to empty (no
 * translation) so tests and call sites that don't care about this can ignore it.
 *
 * [allTransactionsTag] (`FireflyPrefs.getAllTransactionsTag()`) - the user's own request: a tag
 * appended to every draft's own [ParseRule.tagsTemplate] output, so a tag wanted on everything
 * doesn't have to be repeated in every single rule. A plain `String`, not a `FireflyPrefs`/`Context`
 * reference - same "external config injected as data" shape [currencySymbols] already uses, so
 * `rules` stays free of any Android/Firefly dependency. Defaults to blank (no-op) for the same
 * "callers that don't care can ignore it" reason.
 */
class RuleEngine(
    private val rules: List<ParseRule>,
    private val currencySymbols: Map<String, String> = emptyMap(),
    private val allTransactionsTag: String = "",
) {

    fun match(message: CapturedMessage): ParseOutcome {
        val candidates = rules.filter {
            it.enabled && (it.packageNames.isEmpty() || message.packageName in it.packageNames)
        }
        if (candidates.isEmpty()) return ParseOutcome.NoMatch(NoMatchReason.NO_RULES_FOR_APP)

        val combinedInput = "${message.title}\n${message.text}"
        var anyPatternMatched = false

        for (rule in candidates) {
            val groups = extractGroups(rule.pattern, combinedInput) ?: continue
            anyPatternMatched = true
            val draft = buildDraft(rule, groups, message) ?: continue
            return ParseOutcome.Matched(rule, draft, groups)
        }

        val reason = if (anyPatternMatched) NoMatchReason.AMOUNT_UNPARSABLE else NoMatchReason.NO_PATTERN_MATCHED
        return ParseOutcome.NoMatch(reason)
    }

    private fun buildDraft(
        rule: ParseRule,
        groups: Map<String, String>,
        message: CapturedMessage,
    ): TransactionDraft? {
        val amount = groups[Groups.AMOUNT]?.let { parseAmount(it) } ?: return null
        val currency = groups[Groups.CURRENCY]?.let { normalizeCurrency(it, currencySymbols) }
        var foreignAmount = groups[Groups.FOREIGN_AMOUNT]?.let { parseAmount(it) }
        var foreignCurrency = groups[Groups.FOREIGN_CURRENCY]?.let { normalizeCurrency(it, currencySymbols) }
        var localAmount: BigDecimal? = amount
        val issues = mutableSetOf<DraftIssue>()

        if (currency != null && !currency.equals(rule.homeCurrency, ignoreCase = true) && foreignAmount == null) {
            // Only a non-home-currency amount is known: keep it as foreign, the home-currency
            // amount has to be entered manually (or fixed later from the bank statement).
            foreignAmount = amount
            foreignCurrency = currency
            localAmount = null
            issues += DraftIssue.LOCAL_AMOUNT_MISSING
        }

        // The notification's own arrival time, unless the rule opted into reading the
        // transaction's *own* date from the pattern instead (e.g. "the charge happens on
        // 01.02.30", a date that can differ from when the notification arrived).
        var date = Instant.ofEpochMilli(message.timestampMillis)
        val capturedDate = groups[Groups.DATE]
        if (capturedDate != null && rule.dateFormat.isNotBlank()) {
            val parsed = parseDate(capturedDate, rule.dateFormat)
            if (parsed != null) {
                date = parsed
            } else {
                issues += DraftIssue.DATE_UNPARSABLE
            }
        }

        // Renders to a non-empty value, or null (field wasn't set / rendered empty). Used for
        // every field that's "this or nothing" rather than always-a-string.
        fun renderOptional(template: String) =
            renderTemplate(template, groups, message.title, message.text)
                .replace(whitespaceRun, " ").trim().takeIf { it.isNotEmpty() }

        val ruleTags = renderTemplate(rule.tagsTemplate, groups, message.title, message.text)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val tags = if (allTransactionsTag.isNotBlank() && allTransactionsTag !in ruleTags) {
            ruleTags + allTransactionsTag
        } else {
            ruleTags
        }

        return TransactionDraft(
            kind = rule.kind,
            date = date,
            amount = localAmount,
            currencyCode = rule.homeCurrency,
            foreignAmount = foreignAmount,
            foreignCurrencyCode = foreignCurrency,
            sourceName = renderOptional(rule.sourceAccountName),
            sourceAccountId = null,
            destinationName = renderOptional(rule.destinationAccountName),
            destinationAccountId = null,
            description = renderTemplate(rule.descriptionTemplate, groups, message.title, message.text),
            notes = renderTemplate(rule.notesTemplate, groups, message.title, message.text),
            category = renderOptional(rule.categoryTemplate),
            tags = tags,
            issues = issues,
        )
    }
}
