# ARCHITECTURE.md

Architecture, module layout, and real bugs found while building
FF-Notify-Importer. Keep this up to date - update it in the same
commit as any code change that changes a module, data flow, or rule
format.

## Overview

Android app, no backend of its own:

1. A `NotificationListenerService` observes notifications posted by
   other apps (bank apps).
2. Each notification is matched against user-defined rules (which
   source app, which text pattern, how to extract amount/payee/etc.).
3. A match is turned into a transaction and posted to a Firefly III
   instance's REST API, using the base URL + personal access token the
   user entered in the app's settings screen.

Nothing is ever sent off the device; Firefly III itself (not part of
this repo) will be the system of record for the resulting
transactions, once posting to it exists. Locally, message content is
in-memory only by default - nothing written to disk - unless the user
explicitly opts into on-device message history retention (see below).

Current iteration (fourth milestone, landed): capture + dedup, a
settings area (monitored apps + opt-in local history), and a full
rule-matching flow - create/edit/duplicate/reorder rules in Settings,
"test match" a captured message by tapping it on the Messages screen.
Still no Firefly III API calls - `rules` deliberately has no
dependency on Firefly, using free-text placeholders for
accounts/currencies until that connection exists (see TODO.md) -
single Gradle module `app`, Kotlin, classic Android Views (no
Compose), minSdk 26.

A deliberate two-layer split for the matching engine, settled after
some back-and-forth on an earlier draft that mixed them:
`parser` knows regex mechanics and nothing else (not even that
`amount` is a meaningful group name); `rules` is where
transaction/Firefly-flavored meaning (kinds, accounts, currencies,
description/notes templates) lives, on top of what `parser` extracts.
Other decisions worth remembering because they're not obvious from the
code alone:
- A notification is always matched as `title + "\n" + body` in one
  regex - never title and body matched separately.
- No per-rule `counterparty` title/text fallback logic - counterparty
  extraction is just whatever the pattern's `counterparty` group
  captures from the combined input; if a bank puts it in the title,
  write the pattern to look there.
- Rule list order *is* priority: `RuleEngine` returns the first rule
  (app-filtered, in list order) whose pattern matches and yields a
  parsable amount. No `priority` field, no "ambiguous match" outcome.
- `description`/`notes`/`category`/`tags` are all macro templates
  (`{groupName}`/`{title}`/`{body}`/`{appname}`), not separate "is this
  dynamic or static" fields - a template with no `{macro}` in it just
  behaves like a plain fixed string, so templates are a strict superset
  of "plain string" at zero extra cost, and keeps one mechanism instead
  of two. `{title}`/`{body}`/`{appname}` (`MacroTemplate.FIXED_MACROS`)
  are always available regardless of what the pattern declares -
  `{appname}` is the monitored app's display label
  (`CapturedMessage.appLabel`, the same string shown in the app
  dropdown), not a captured group, for a rule that wants the source app
  in a description/notes without hardcoding it (useful once one rule's
  pattern is reused loosely-ish across similarly-worded notifications
  from different apps). `tags` is the odd one out structurally: its
  template renders
  to one string, which then gets split on `,` (trimmed, blanks
  dropped) into `TransactionDraft.tags: List<String>`. No default text
  in any of these for a new rule - user feedback was that a silently
  pre-filled default is confusing, better left blank and explicit.
- `RuleKind` is `WITHDRAWAL`/`DEPOSIT`/`TRANSFER` - no `_OUT`/`_IN`
  split (an earlier version had one). Direction comes directly from
  which of `sourceAccountName`/`destinationAccountName` is filled in,
  not from the kind; `TRANSFER` requires both.
- `sourceAccountName`/`destinationAccountName` are macro templates -
  the exact same `{macro}` mechanism as `descriptionTemplate`/etc, not
  a separate "fixed name, with an automatic fallback to whatever the
  pattern's `counterparty` group captured" behavior. That automatic
  fallback existed in an earlier version (silently inherited from a
  third-party draft the user shared early on, never actually
  requested, and confusing once visible - "where did I ask for this")
  and was removed outright; there is no implicit behavior here at all
  now. If a side varies per notification (a merchant/payer name), the
  rule author writes e.g. `{counterparty}` in that field explicitly,
  same as they would in a description template - a plain name with no
  `{}` in it is just a fixed value, same "templates are a strict
  superset of plain strings" reasoning as the other template fields.
  A rule only ever picks from the *monitored* apps list (not every
  installed app) in the UI - it can't match anything else anyway.

Package: `io.github.maximus5.ffnotifyimporter`.

## Module layout

Single `:app` module, packages under
`io.github.maximus5.ffnotifyimporter`:

- `data` - the core, Android-independent pure-Kotlin logic, unit
  tested without instrumentation:
  - `CapturedMessage` (app/title/text/timestamp).
  - `MessageStore` - the in-memory, time-windowed dedup store
    (`MessageStoreTest`). `MessageStore.shared` is the one instance
    used by the listener service and the UI. `add` dedups and notifies
    listeners; `restore` bulk-seeds already-deduplicated entries
    (loaded from disk at startup) without re-running the dedup check;
    `remove` deletes one entry (matched by value equality - there's no
    separate id) for the Messages screen's delete action. The optional
    `onNewMessage`/`onMessageRemoved` hooks are how persistence attaches
    itself without `MessageStore` knowing persistence exists.
  - `MessageHistoryDb` - the on-disk mirror, plain `SQLiteOpenHelper`
    (no ORM), one `messages` table. Only ever written to while
    retention is enabled. `MessageHistoryDb.getInstance(context)` is
    the one shared instance (a `SQLiteOpenHelper` per call would leak
    connections).
  - `RetentionController` (`RetentionControllerTest` for the pure
    cutoff-date math) - wires `MessageStore` and `MessageHistoryDb`
    together based on the user's retention setting.
    `applyOnStartup` (called once, from `FfNotifyImporterApp`) may
    call `MessageStore.restore`; `applyOnSettingChanged` (called from
    the settings UI when the user changes the value mid-session) never
    does, since `restore` has no dedup against what's already in
    memory and calling it twice would duplicate entries already shown.
- `parser` - regex mechanics only, zero knowledge of transactions,
  Firefly, or what any group name means:
  - `extractGroups(pattern, input): Map<String,String>?` /
    `namedGroupNames(pattern)` (`NamedGroupMatcherTest`).
  - `parseAmount(raw)` (`AmountParserTest`) - no configured decimal
    separator (there used to be a per-rule one; removed after user
    feedback that it was unnecessary friction). Auto-detects instead:
    the *last* separator character (`,`/`.`/`'`/space/NBSP) is the
    decimal point only if exactly two digits follow it - real currency
    amounts are written with 0 or 2 decimal digits, never anything
    else, so that distinguishes a decimal point from a thousands
    separator (`"12,50"` -> 12.50, but `"1,234"` -> 1234, not 1.234).
    Known gap: single-digit cents (`"12,5"`) aren't detected - hasn't
    come up with real bank notifications, which always pad to two
    digits.
- `rules` - the Firefly-*flavored* domain, still Firefly-API-free
  (string placeholders - see Overview):
  - `ParseRule` - one parsing template scoped to one app
    (`sourceAccountName`/`destinationAccountName`/`homeCurrency` are
    placeholders; `pattern` matched against `parser`'s combined-input
    convention; `descriptionTemplate`/`notesTemplate` are macro
    templates, blank by default - no silently pre-filled text).
  - `Groups` - the named-group names `rules` knows to look for
    (`amount`, `currency`, `foreignAmount`, `foreignCurrency`,
    `counterparty`) - a convention `parser` has no opinion on.
  - `renderTemplate`/`templateMacroNames`/`FIXED_MACROS`
    (`MacroTemplateTest`) - `{x}` macro substitution for
    description/notes/account names/etc, using the match's captured
    groups plus always-available `{title}`/`{body}`/`{appname}`.
  - `TransactionDraft`/`DraftIssue`/`ParseOutcome`/`NoMatchReason` -
    what a match produced (or why it didn't match), not yet a Firefly
    API request.
  - `RuleEngine.match(message)` (`RuleEngineTest`) - the
    first-rule-in-order-that-matches algorithm described above. Takes
    an optional `currencySymbols: Map<String,String>` (symbol -> code,
    from `CurrenciesDb.symbolToCodeMap()`) and runs captured
    `currency`/`foreignCurrency` values through
    `normalizeCurrency`/`CurrencyNormalizationTest` before comparing
    against `homeCurrency` - banks print symbols ("€50.00") far more
    often than ISO codes, and without this every symbol-only
    notification would look "foreign" even when it's actually the
    home currency.
  - `CurrencyEntry`/`CurrenciesDb` - another user-maintained
    placeholder table (code<->symbol, e.g. `EUR`<->`€`), same idea and
    same "plain SQLite, no ORM, `getInstance(context)` singleton"
    shape as `RulesDb`/`MessageHistoryDb`. Seeded with EUR/USD/GBP on
    first creation; the user edits it from Settings -> Currencies.
  - `validateRule(rule)` (`RuleValidationTest`) - checks a pattern
    defines `amount`, at least one app is picked, the right
    source/destination account is filled per kind (see the `RuleKind`
    note above), and every macro referenced in the templates is either
    a declared group or `title`/`body`. The rule editor calls this
    before letting a save through.
  - `RulesDb` - plain SQLite (no ORM), one row per rule plus an
    explicit `sort_order` column - the only priority mechanism
    `RuleEngine` uses, so `moveUp`/`moveDown` just swap two rows'
    `sort_order`. `duplicate` shifts every later row's order up by one
    and inserts the copy right after the original, in a transaction.
    `distinctPatterns()` is the "pick an existing pattern" source for
    the rule editor - a derived query over this same table, newest
    first, one entry per distinct pattern; no separate library table.
    Still no built-in/canned patterns *in this table* - but
    `RuleEditFragment.showPatternPicker` always appends one hardcoded
    illustrative example (`EXAMPLE_PATTERN`, labeled "Example") after
    whatever's actually saved, so the very first rule a user ever
    creates isn't a blank textarea with zero clue about named-group
    syntax, and it stays available as a syntax reference even once real
    rules exist (it used to be an empty-state-only fallback; removed
    once any real rule existed, which the user flagged as the example
    disappearing right when they'd want to compare against it).
    `EXAMPLE_PATTERN`'s `currency` group accepts `[A-Z]{3}` *or*
    `[€£$]` (banks print symbols far more than ISO codes, same reason
    `CurrenciesDb`/`normalizeCurrency` exist), and its `counterparty`
    group is `(?<counterparty>.+?)\.?$` - lazily matched with an
    optional trailing literal period stripped, not plain `.+`, because
    a notification's sentence-ending "." isn't part of the merchant
    name (real example: "...at Shop 9012 Markt GmbH-Zw." was capturing the
    period into `counterparty` before this).
    `RulesDb.getInstance(context)` is the one shared instance (same
    reasoning as `MessageHistoryDb.getInstance`).
    `DB_VERSION` has been bumped three times already as the rule shape
    settled (2: `account_name`/`counterparty_account_name` columns ->
    `source_account_name`/`destination_account_name`; 3: added
    `category_template`/`tags_template`; 4: dropped
    `decimal_separator` - see `parseAmount` above) - `onUpgrade` just
    drops and recreates the table, fine for a personal,
    not-yet-relied-upon rules list; revisit if that's ever not true.
    Not unit tested - like `MessageHistoryDb`, it's thin glue over real
    `SQLiteOpenHelper`/Android framework classes that don't run under
    plain JVM unit tests.
- `prefs` - thin `SharedPreferences` wrappers for configuration (not
  notification content, so - unlike captured messages by default -
  persisted across restarts/reboots on purpose):
  - `MonitoredAppsPrefs` - which package names to monitor.
  - `RetentionPrefs` - local history retention in days; `0` (default)
    means off/in-memory-only.
- `notifications` - `BankNotificationListenerService`
  (`NotificationListenerService` subclass): for every posted
  notification, checks `MonitoredAppsPrefs`, pulls `EXTRA_TITLE`/
  `EXTRA_TEXT` from the notification extras, and if either is
  non-empty, resolves the app's current label via `AppLabelResolver`
  and calls `MessageStore.shared.add(...)`. Requires the user to grant
  notification-listener access manually in system settings - there is
  no runtime permission dialog for this, only
  `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`.
- `ui`:
  - `MainActivity` - home screen, just hosts `MessagesFragment` plus a
    toolbar with a settings action. `MessagesFragment` subscribes to
    `MessageStore.shared` while visible (`onStart`/`onStop`) and
    renders the current snapshot, newest first. Long-press starts a
    standard `androidx.appcompat.view.ActionMode` multi-select (tap
    toggles more items in or out); its delete action calls
    `MessageStore.shared.remove` for each selected message after a
    confirmation dialog. A plain tap *outside* of that selection mode
    runs `RuleEngine.match` against the tapped message and shows the
    result (rule, source/destination account, amounts/currencies,
    rendered description/notes, issues) in a dialog - "test match".
  - `SettingsActivity` - everything else, as a stack of fragments
    pushed via plain `FragmentManager` back-stack transactions (no
    Navigation Component - the flow is small and linear enough not to
    need it). `SettingsRootFragment` is the menu (monitored apps,
    currencies, rules, message history, add test message, and later
    Firefly III connection / appearance, added one row at a time as
    each is built - currencies sits above rules since rules depend on
    it, same reasoning as monitored apps coming first), linking to:
    - `AppsFragment` (reused as-is, just hosted here instead of a tab;
      lists every launchable installed app via `queryIntentActivities`
      on `ACTION_MAIN`/`CATEGORY_LAUNCHER`, which needs the
      `<queries>` entry in the manifest to see apps on API 30+; loads
      on a background dispatcher, only once the screen is actually
      opened, not eagerly).
    - `MessageHistoryFragment` (the retention radio group, writes to
      `RetentionPrefs` and calls `RetentionController
      .applyOnSettingChanged`).
    - `AddTestMessageFragment` - a debug tool: picks from the
      monitored apps list and calls `MessageStore.shared.add(...)`
      directly with user-entered title/text, exactly like
      `BankNotificationListenerService` would for a real notification
      (same dedup, same persistence-if-enabled) - lets rule processing
      be tested without waiting for a real bank notification.
    - `RulesListFragment` - the ordered rule list (`RulesAdapter`);
      tap opens `RuleEditFragment` to edit, the overflow (⋮) menu per
      row has move up/down, duplicate, delete - all straight
      `RulesDb` calls followed by a reload. A FAB opens
      `RuleEditFragment` with no rule id, to create one. Each row's
      subtitle is `"<app>: <kind>: <source> → <destination>"`
      (`rule_subtitle_format`) - whichever of source/destination isn't
      fixed on the rule (normal for WITHDRAWAL/DEPOSIT, filled
      dynamically at match time instead, see the `RuleKind` note
      above) shows a `"(from notification)"` placeholder rather than
      leaving a blank gap next to the arrow, which read as if text had
      gone missing.
    - `RuleEditFragment` - one form for both create (`ruleId == null`)
      and edit, via `RuleEditFragment.newInstance(ruleId)`. App and
      kind are both Material "exposed dropdown menu" fields (an
      outlined `TextInputLayout` wrapping a
      `MaterialAutoCompleteTextView`) rather than a bare `Spinner` -
      keeps every field on the form visually consistent (a plain
      `Spinner` stood out badly next to the outlined text fields, per
      user feedback on an earlier version). The app dropdown is
      single-select, sourced from `MonitoredAppsPrefs` only (not a
      full re-enumeration of installed apps - a rule can only ever
      match what the listener service already captures) - a rule
      needs exactly one app, not "any monitored app" or several
      (simplified after user feedback; `ParseRule.packageNames` is
      still technically a `Set` for `RuleEngine`'s sake, the UI just
      always writes a singleton set). Field order: identity fields
      first (label, app, kind), then a divider, then the regex
      section: the pattern field itself, then three left-aligned
      link-style buttons in one row *below* it, in this order - "pick
      existing" (every saved rule's distinct pattern, *plus* one
      hardcoded illustrative example always appended at the end
      regardless of how many real patterns already exist - it's a
      syntax reminder, not just an empty-state filler, see `RulesDb`
      below; list previews are truncated with a visible "…" rather than
      cut off silently, and picking one shows the pattern in full in a
      confirm dialog - Apply/Cancel - before it overwrites the pattern
      field, since a one-line truncated preview is easy to misread),
      "pick message"
      (lists `MessageStore.shared.snapshot()` filtered to the currently
      selected app, in-memory session data only - picking one drops
      its raw `"title\nbody"` into the pattern field as a literal
      starting point to then wrap with named groups, instead of
      hand-copying the bank's wording from the Messages screen), "test"
      (runs the rule *as currently drafted* - unsaved edits included,
      not gated on `validateRule` since fields irrelevant to matching
      shouldn't block a pattern sanity check - against this session's
      captured messages for the selected app, newest first, stopping
      after 3 matches found; results are separated by a plain line, no
      numbering - a numbered list of already-multi-line entries read as
      more clutter than help, per user feedback). Each match is shown as
      the notification's raw `title` followed by one `groupname: value`
      line per named group the pattern declares - not the derived
      `TransactionDraft` fields (amount/account/etc), which depend on
      rule config that may still be unfilled while iterating on a
      pattern and so aren't what "does my regex actually capture what I
      want" is asking (`ParseOutcome.Matched.groups` exposes the raw
      capture map for exactly this). Then the live
      "available macros" hint from `parser.namedGroupNames`, then
      *source/destination account* (moved here, below the regex, from
      the identity-fields group above - now that they're macro
      templates (see the `RuleKind` note above) they only make sense
      once you're thinking about what the regex extracts, same
      reasoning `homeCurrency` already had), then `homeCurrency`, then
      the rest of the templates. Every macro-template field (both
      account fields, description/notes/category/tags - *not*
      `homeCurrency`, which is a plain value, not a template) has a
      custom end icon (`TextInputLayout.setEndIconOnClickListener`,
      `ic_insert_macro`) that opens a picker over the currently declared
      macros (the pattern's named groups plus `FIXED_MACROS`) and
      inserts `{name}` at the current cursor position/selection in that
      field - an `Editable.replace`, not `setText`, so it doesn't
      clobber whatever else is already typed there. Saves having to
      hand-type e.g. `{currency}` and getting the spelling wrong.
      Every `TextInputLayout` on this form (and on
      `AddTestMessageFragment`'s) sets `app:expandedHintEnabled="false"`
      so the field label always sits in the compact/floating position -
      without it, an empty unfocused field shows its hint as large
      inline placeholder text instead, and a form with some fields
      focused/filled and others not looks visually inconsistent ("zoo
      of labels", per user feedback). Save runs `validateRule` and
      shows every error in one dialog before writing anything.
    - `CurrenciesFragment`/`CurrenciesAdapter` - list + add/edit (one
      `AlertDialog` with two fields: code, symbol) + delete over
      `CurrenciesDb`. No separate full-screen editor like
      `RuleEditFragment` - two fields doesn't warrant one.
  - `SystemBarInsets.kt` - `useClassicSystemBars(root)`, the shared
    edge-to-edge-but-padded-back-to-classic logic both activities call
    (see Known bugs/gotchas below for why it's done this way). Also
    folds `WindowInsetsCompat.Type.ime()` into the same padding calc,
    not just `systemBars()` - needed because edge-to-edge opts the app
    out of the classic `adjustResize` auto-scroll-the-keyboard-out-of-
    the-way behavior, which otherwise left a focused field on a form
    (e.g. `RuleEditFragment`) hidden behind the keyboard. Both
    activities also declare `android:windowSoftInputMode="adjustResize"`
    in the manifest, which this relies on for reliable IME inset
    dispatch.
- `FfNotifyImporterApp` (`Application` subclass, declared in the
  manifest) - the one place startup wiring happens:
  `RetentionController.applyOnStartup`.

## Data flow

`NotificationListenerService.onNotificationPosted` (only for monitored
packages) -> extract title/text -> `MessageStore.add` (dedups
app+title+text within a 15-minute window) -> `MessagesFragment`
re-renders via the store's listener callback; if retention is enabled,
`MessageStore`'s `onNewMessage` hook also mirrors the entry into
`MessageHistoryDb` and prunes rows older than the retention window. On
process start, `FfNotifyImporterApp` hydrates `MessageStore` from
`MessageHistoryDb` if retention was left enabled. Tapping a message
(outside selection mode) loads rules from `RulesDb`, runs
`RuleEngine.match`, and shows the `ParseOutcome` in a dialog - nothing
is written anywhere by this, it's purely a preview. No Firefly III API
call exists yet; that's the only remaining step once a Firefly
connection exists (see TODO.md).

## Known bugs / gotchas

- Android 15+ (targetSdk 35+, which this app uses) forces the window
  to draw edge-to-edge with no permanent opt-out -
  `android:windowOptOutEdgeToEdgeEnforcement` looked like one but is
  registered in the SDK's own resource table as a `STAGED_ID`
  (AOSP's flag-guarded-API marker), and had zero effect on a real test
  device despite matching Google's documented usage. `SystemBarInsets`
  instead lets the window draw edge-to-edge (as the platform wants)
  and manually pads the root view by `WindowInsetsCompat.Type
  .systemBars()`, which doesn't depend on that flag at all and is
  confirmed working on a real device.
- `NotificationListenerService` access, once granted, can get silently
  revoked by some OEM battery-optimization/"app hibernation" features.
  No detection for that beyond the existing "is access enabled" banner
  check on resume.
- The settings area, including the whole rules flow added this
  milestone, hasn't had a real-device pass yet - unit tests and
  `assembleDebug` pass, but opening each settings screen, toggling
  retention, and exercising rule create/edit/duplicate/reorder/delete
  plus "test match" needs manual verification per AGENTS.md's workflow
  rule.
