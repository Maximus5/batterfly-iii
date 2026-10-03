package io.github.maximus5.ffnotifyimporter.rules

import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.parser.extractGroups
import io.github.maximus5.ffnotifyimporter.parser.parseAmount
import java.math.BigDecimal
import java.time.Instant

/**
 * Matches a [CapturedMessage] against an ordered list of rules. List order IS priority: the
 * first rule whose pattern matches AND yields a parsable amount wins, nothing further is tried.
 * (No "Ambiguous" outcome - by design, see TODO.md/ARCHITECTURE.md history.)
 *
 * [currencySymbols] (symbol -> code, from [CurrenciesDb.symbolToCodeMap]) normalizes a captured
 * currency symbol ("€") to its code ("EUR") before it's compared against [ParseRule.homeCurrency]
 * or stored - banks print symbols far more often than ISO codes. Defaults to empty (no
 * translation) so tests and call sites that don't care about this can ignore it.
 */
class RuleEngine(
    private val rules: List<ParseRule>,
    private val currencySymbols: Map<String, String> = emptyMap(),
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

        // Renders to a non-empty value, or null (field wasn't set / rendered empty). Used for
        // every field that's "this or nothing" rather than always-a-string.
        fun renderOptional(template: String) =
            renderTemplate(template, groups, message.title, message.text, message.appLabel).trim().takeIf { it.isNotEmpty() }

        val tags = renderTemplate(rule.tagsTemplate, groups, message.title, message.text, message.appLabel)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return TransactionDraft(
            kind = rule.kind,
            date = Instant.ofEpochMilli(message.timestampMillis),
            amount = localAmount,
            currencyCode = rule.homeCurrency,
            foreignAmount = foreignAmount,
            foreignCurrencyCode = foreignCurrency,
            sourceName = renderOptional(rule.sourceAccountName),
            destinationName = renderOptional(rule.destinationAccountName),
            description = renderTemplate(rule.descriptionTemplate, groups, message.title, message.text, message.appLabel),
            notes = renderTemplate(rule.notesTemplate, groups, message.title, message.text, message.appLabel),
            category = renderOptional(rule.categoryTemplate),
            tags = tags,
            issues = issues,
        )
    }
}
