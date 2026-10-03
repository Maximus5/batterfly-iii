package io.github.maximus5.ffnotifyimporter.rules

/**
 * Builds [ParseRule.descriptionTemplate]/[ParseRule.notesTemplate] output. Lives in `rules`,
 * not `parser`: the available macros are a rules-layer convention (a pattern's named groups,
 * plus the raw notification fields), not a parsing concern.
 */

private val macroRegex = Regex("""\{(\w+)\}""")

/**
 * Substitutes `{name}` macros in [template]: `{title}`/`{body}` are the raw notification
 * fields, anything else looks up [groups] (the pattern's named-group captures for this match).
 * An unknown macro is left as literal text - `validateRule` is what catches that at save time,
 * this function doesn't fail.
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
