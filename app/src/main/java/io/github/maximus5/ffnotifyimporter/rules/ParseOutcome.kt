package io.github.maximus5.ffnotifyimporter.rules

sealed interface ParseOutcome {
    /** [groups] is the pattern's raw named-group captures for this match - e.g. for a rule
     * editor "did my regex actually capture what I think it did" check, independent of whatever
     * [draft] ended up derived from (which depends on rule config like account templates too). */
    data class Matched(val rule: ParseRule, val draft: TransactionDraft, val groups: Map<String, String>) :
        ParseOutcome
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
