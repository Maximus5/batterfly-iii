package io.github.maximus5.ffnotifyimporter.rules

/**
 * Builds [ParseRule.descriptionTemplate]/[ParseRule.notesTemplate] output. Lives in `rules`,
 * not `parser`: the available macros are a rules-layer convention (a pattern's named groups,
 * plus the raw notification fields), not a parsing concern.
 */

/** Escaped-pair alternative first - at a given start position Kotlin/Java regex alternation
 * tries left-to-right and takes the first one that matches there, so `{{word}}` is always
 * preferred over treating it as a plain `{word}` macro at a shifted position; `{word}` (single
 * braces) only ever falls through to the second alternative. Group 1 is the escaped form's
 * content, group 2 the plain macro's name - exactly one is non-null for any given match. */
private val macroRegex = Regex("""\{\{(\w+)\}\}|\{(\w+)\}""")

/** Macros always available regardless of what the pattern declares - not a captured group. */
val FIXED_MACROS = setOf("title", "body")

/**
 * Substitutes `{name}` macros in [template]: `{title}`/`{body}` are the raw notification
 * fields, anything else looks up [groups] (the pattern's named-group captures for this match).
 * An unknown macro is left as literal text - `validateRule` is what catches that at save time,
 * this function doesn't fail.
 *
 * `{{name}}` (doubled braces) is the escape for a literal `{name}` that isn't meant as a macro at
 * all - e.g. a real Firefly tag named `{User}` - renders to `{name}` verbatim, braces-in-braces
 * stripped down to one level, *never* macro-resolved even when `name` happens to collide with a
 * real declared group or [FIXED_MACROS]. Raised by the user: before this existed, a field
 * containing literal brace text had two different ways to go wrong - `validateRule` rejected the
 * rule outright if the bracketed word wasn't a known macro (a save-time false positive, since
 * [renderTemplate] itself would have rendered an unknown one as literal text fine on its own), or,
 * if it *did* happen to match a real macro name, it silently got substituted with no way to say
 * "I mean the literal text, not the macro".
 *
 * No `{appname}` macro - a rule is always scoped to exactly one app (`packageNames` is written
 * as a singleton set by the rule editor), so which app it is never varies per match. A macro that
 * can only ever resolve to one fixed value adds indirection without adding flexibility; the rule
 * editor's insert-macro picker offers the app's name as a literal string to insert instead.
 */
fun renderTemplate(template: String, groups: Map<String, String>, title: String, body: String): String =
    macroRegex.replace(template) { match ->
        val escaped = match.groups[1]?.value
        if (escaped != null) {
            "{$escaped}"
        } else {
            when (val name = match.groups[2]!!.value) {
                "title" -> title
                "body" -> body
                else -> groups[name] ?: match.value
            }
        }
    }

/** Every *plain* macro name referenced in [template] (`"{name}"` -> `"name"`), for validation -
 * an escaped `{{name}}` is deliberately never counted as a macro reference (see [renderTemplate]'s
 * own KDoc), so `validateRule` never flags `name` as "unknown" just because it's quoted. */
fun templateMacroNames(template: String): Set<String> =
    macroRegex.findAll(template).mapNotNull { it.groups[2]?.value }.toSet()
