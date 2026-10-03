package io.github.maximus5.ffnotifyimporter.parser

/**
 * Pure regex mechanics - extracting named capture groups from text. Nothing here knows what
 * the groups mean (amount, counterparty, whatever); that's for callers (the `rules` layer) to
 * decide. Keeps this layer testable and reusable independent of any transaction/Firefly concept.
 */

private val namedGroupNameRegex = Regex("""\(\?<([a-zA-Z][a-zA-Z0-9]*)>""")

/** Named groups [pattern] declares, in the order they appear. */
fun namedGroupNames(pattern: String): List<String> =
    namedGroupNameRegex.findAll(pattern).map { it.groupValues[1] }.toList()

/**
 * Returns the trimmed, non-blank value of every named group [pattern] actually captured in
 * [input], or null if [pattern] didn't match [input] at all. A declared group that matched but
 * captured nothing/blank is simply absent from the result (not an empty string).
 *
 * [DOT_MATCHES_ALL][RegexOption.DOT_MATCHES_ALL]: [input] is always `title + "\n" + body` joined
 * into one string (the `rules` layer's convention, not this layer's), and without this a plain
 * `.`/`.*`/`.+` in the pattern silently can't cross that join - a pattern written expecting
 * "matched against title + text" (as the rule editor's field hint literally says) would then fail
 * to match whenever the two parts it's trying to span land on different lines, for a reason
 * invisible from the pattern text itself. Real example that prompted this: `Zahlung.*?(?<amount>
 * ...)` never matched because "Zahlung" was in the title and the amount was in the body.
 */
fun extractGroups(pattern: String, input: String): Map<String, String>? {
    val match = Regex(pattern, RegexOption.DOT_MATCHES_ALL).find(input) ?: return null
    return namedGroupNames(pattern).mapNotNull { name ->
        val value = try {
            match.groups[name]?.value
        } catch (e: IllegalArgumentException) {
            null // group declared in the string but not recognized by this regex flavor - ignore
        }
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { name to it }
    }.toMap()
}
