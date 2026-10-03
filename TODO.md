# TODO.md

Deferred/known-outstanding work. Add an entry when something is
identified but deliberately not done now (out of scope, needs a
decision, etc.); remove the entry in the same commit that finishes the
work.

- Rule engine (`parser`/`rules`, `RulesDb`, the Rules settings screen,
  and the Messages screen's "test match" tap) is built end to end and
  reachable in the app - see ARCHITECTURE.md. Not yet verified on a
  real device (see the on-device verification item below). Still
  deliberately missing:
  - The Firefly III API client/actual transaction posting - `rules`
    deliberately has no dependency on this; `ParseRule.sourceAccountName`/
    `destinationAccountName`/`homeCurrency` are free-text placeholders
    until it exists (see next item).
  - A way to reorder rules other than one-step-at-a-time up/down
    buttons (fine for a handful of rules; revisit - e.g. drag handles
    - if the list grows long enough to make that tedious).
  - Export/import buttons on the Rules screen (backup, move rules to
    another device, share a rule). Format: JSON is the pick - `org.json`
    is already in the Android framework (no new dependency, matches
    the project's house style of avoiding them), diffs/version-controls
    fine, and is a natural fit for Android's share sheet/Storage Access
    Framework for save-to-file and open-from-file. YAML would read
    nicer for hand-editing (comments, less punctuation) but Android has
    no built-in parser - would mean pulling in snakeyaml or similar
    just for this. XML has a built-in parser too but is more verbose,
    and the regex patterns (full of special characters) are uglier to
    escape as XML attribute/text content than as JSON strings. A binary
    format is a clear no - the whole point of export/import is
    backup/portability/occasionally-eyeballing-it, which an opaque
    binary blob works against, and it's needlessly fragile across
    schema changes to boot.
- Firefly III connection settings screen (base URL + personal access
  token, entered by the user, stored on-device per AGENTS.md's Secrets
  section - never hardcoded, never committed) - not built yet, just a
  future row in `SettingsRootFragment`. Once it exists, `ParseRule`'s
  `sourceAccountName`/`destinationAccountName`/`homeCurrency`
  placeholders need a migration path to real Firefly account/currency
  references - and `CurrenciesDb` (Settings -> Currencies, user-edited
  code<->symbol table, seeded EUR/USD/GBP) could then be replaced or
  backed by Firefly's real enabled-currencies list instead of being
  hand-maintained.
- A pattern can't use the same named group twice (Java regex
  limitation, not something we can configure around) - e.g. a
  currency symbol that appears sometimes before the amount, sometimes
  after, *within one bank's notification format*, can't be captured by
  one `(?<currency>...)` group placed in two spots or two alternation
  branches. Hasn't come up for real yet (a given bank is consistent).
  If it does: would need named-group aliases (e.g. `currencyBefore`/
  `currencyAfter`, both treated as `Groups.CURRENCY` by `RuleEngine`)
  rather than forcing the user to pick one position.
- No way to write a literal `{word}` in a template field (description/
  notes/category/tags/account names) when it isn't meant as a macro -
  e.g. a real Firefly tag named `{User}`. Two distinct problems today,
  found while discussing this with the user:
  - `validateRule` always treats any `{word}` as a macro reference and
    rejects the rule at save time if `word` isn't a declared group or
    `FIXED_MACROS` ("Unknown macro(s): User") - even though
    `renderTemplate` itself would have rendered it fine (an unknown
    macro is left as literal text, see `MacroTemplate.kt`). So this
    isn't a render-time mangling bug, it's a save-time false positive
    that blocks the rule outright.
  - If `word` *does* happen to collide with an actual declared group
    name (or a fixed macro), it silently gets substituted instead of
    staying literal - no way to say "I mean the literal text, not the
    macro" in that case.
  Proposed fix (not yet implemented, needs a decision - this is a
  template-syntax change, worth getting right once rather than
  revisiting): an escape syntax, doubled braces - `{{User}}` in the
  field renders to literal `{User}`, braces-in-braces stripped down to
  one level, content *not* macro-resolved regardless of whether `User`
  happens to be a declared group. Would need:
  - `renderTemplate`'s regex to prefer an escaped-pair match
    (`\{\{(\w+)\}\}`) over the existing single-brace macro match
    (`\{(\w+)\}`) at the same position, emitting `{$word}` literally
    for the former.
  - `templateMacroNames` to *not* count an escaped pair as a macro
    reference (so `validateRule` doesn't flag `User` as unknown just
    because it's quoted).
  - The rule editor's "available macros" hint and insert-macro picker
    presumably stay as-is (they're about inserting real macros, not
    escaping literals) - but worth a quick UI gut-check once this
    lands, in case users need a visible hint that escaping exists at
    all.
- Real on-device verification needed for: message history retention
  (toggle on, capture a notification, restart the app, confirm it's
  still there; toggle back off, confirm the on-disk file is actually
  cleared), and the whole rules flow (add/edit/duplicate/delete/
  reorder a rule, pick an existing pattern, "test match" tap on a
  message, including the foreign-currency/LOCAL_AMOUNT_MISSING case)
  - see ARCHITECTURE.md's Known bugs/gotchas. Notification capture +
  dedup + fullscreen fix are already confirmed working on a real
  device.
- No cap on `MessageStore`'s in-memory list size, or on
  `MessageHistoryDb` beyond the retention-window purge - for personal,
  low-volume bank notifications this is unlikely to matter. Revisit if
  it turns out to matter in practice.
- No foreground service - chosen deliberately (no persistent
  notification icon) at the cost of the captured-messages list being
  more likely to be lost if Android kills the backgrounded app under
  memory pressure (when message history retention is off - the
  default), not just on reboot.
- `ic_launcher` is a placeholder vector icon, not a real app icon.
- Optional fullscreen/edge-to-edge mode as a user setting (in the
  future "Appearance" settings row). The default is now the classic
  non-overlapping look via `ui/SystemBarInsets.kt`'s
  `useClassicSystemBars` (see ARCHITECTURE.md for why it's built the
  way it is). A fullscreen *setting* just needs to make that padding
  conditional (skip it, or only reserve space for one bar) rather than
  redo the insets plumbing.
- Only English and Russian are localized
  (`values/strings.xml` and `values-ru/strings.xml`). Default/base is
  English; add more `values-<lang>/strings.xml` as needed, keep
  `strings.xml` in sync across locales when adding new strings.
