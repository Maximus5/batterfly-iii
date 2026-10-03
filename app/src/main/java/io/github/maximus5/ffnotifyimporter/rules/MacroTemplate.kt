package io.github.maximus5.ffnotifyimporter.rules

/**
 * Builds [ParseRule.descriptionTemplate]/[ParseRule.notesTemplate] output. Lives in `rules`,
 * not `parser`: the available macros are a rules-layer convention (a pattern's named groups,
 * plus the raw notification fields), not a parsing concern.
 */

private val macroRegex = Regex("""\{(\w+)\}""")

/** Macros always available regardless of what the pattern declares - not a captured group. */
val FIXED_MACROS = setOf("title", "body")

/**
 * Substitutes `{name}` macros in [template]: `{title}`/`{body}` are the raw notification
 * fields, anything else looks up [groups] (the pattern's named-group captures for this match).
 * An unknown macro is left as literal text - `validateRule` is what catches that at save time,
 * this function doesn't fail.
 *
 * No `{appname}` macro - a rule is always scoped to exactly one app (`packageNames` is written
 * as a singleton set by the rule editor), so which app it is never varies per match. A macro that
 * can only ever resolve to one fixed value adds indirection without adding flexibility; the rule
 * editor's insert-macro picker offers the app's name as a literal string to insert instead.
 */
fun renderTemplate(template: String, groups: Map<String, String>, title: String, body: String): String =
    macroRegex.replace(template) { match ->
        when (val name = match.groupValues[1]) {
            "title" -> title
            "body" -> body
            else -> groups[name] ?: match.value
        }
    }

/** Every macro name referenced in [template] (`"{name}"` -> `"name"`), for validation. */
fun templateMacroNames(template: String): Set<String> =
    macroRegex.findAll(template).map { it.groupValues[1] }.toSet()
