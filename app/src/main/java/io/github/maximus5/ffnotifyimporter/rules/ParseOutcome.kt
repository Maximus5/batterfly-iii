package io.github.maximus5.ffnotifyimporter.rules

sealed interface ParseOutcome {
    data class Matched(val rule: ParseRule, val draft: TransactionDraft) : ParseOutcome
    data class NoMatch(val reason: NoMatchReason) : ParseOutcome
}

enum class NoMatchReason {
    /** No enabled rule applies to this notification's app at all. */
    NO_RULES_FOR_APP,

    /** At least one rule applied to the app, but no pattern matched the notification text. */
    NO_PATTERN_MATCHED,

    /** A pattern matched, but none of the matches yielded a parsable `amount`. */
    AMOUNT_UNPARSABLE,
}
