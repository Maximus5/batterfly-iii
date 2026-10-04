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

Current iteration (fifth milestone, in progress): capture + dedup, a
settings area (monitored apps + opt-in local history), a full
rule-matching flow - create/edit/duplicate/reorder rules in Settings,
"test match" a captured message by tapping it on the Messages screen -
and the start of the Firefly III connection itself (base URL/token,
Test connection, posting-behavior settings; see `prefs`/`network`
below and TODO.md for what's still missing before a rule match can
actually become a transaction). `rules` still has no *direct*
dependency on Firefly - `ParseRule.sourceAccountName`/
`destinationAccountName`/`homeCurrency` are still free-text
placeholders until the rule editor's asset-account picker lands (see
TODO.md) - single Gradle module `app`, Kotlin, classic Android Views
(no Compose), minSdk 26.

A deliberate two-layer split for the matching engine, settled after
some back-and-forth on an earlier draft that mixed them:
`parser` knows regex mechanics and nothing else (not even that
`amount` is a meaningful group name); `rules` is where
transaction/Firefly-flavored meaning (kinds, accounts, currencies,
description/notes templates) lives, on top of what `parser` extracts.
Other decisions worth remembering because they're not obvious from the
code alone:
- A notification is always matched as `title + "\n" + body` in one
  regex - never title and body matched separately. `extractGroups`
  compiles with `RegexOption.DOT_MATCHES_ALL` so a plain `.`/`.*`/`.+`
  in the pattern can cross that joining newline too - without it, a
  pattern expecting to span "the notification text" (as the rule
  editor's field hint literally promises: "matched against title +
  text") would silently fail to match whenever what it's trying to
  span happens to land on both sides of the title/body boundary, for a
  reason invisible from the pattern text itself (real case: `Zahlung.*?
  (?<amount>...)` never matched because "Zahlung" was in the title and
  the amount was in the body). `validateRule`'s trial-compile uses the
  same option, so a pattern that's only invalid without it never slips
  through as "valid" at save time.
- No per-rule `counterparty` title/text fallback logic - counterparty
  extraction is just whatever the pattern's `counterparty` group
  captures from the combined input; if a bank puts it in the title,
  write the pattern to look there.
- Rule list order *is* priority: `RuleEngine` returns the first rule
  (app-filtered, in list order) whose pattern matches and yields a
  parsable amount. No `priority` field, no "ambiguous match" outcome.
- `description`/`notes`/`category`/`tags` are all macro templates
  (`{groupName}`/`{title}`/`{body}`), not separate "is this dynamic or
  static" fields - a template with no `{macro}` in it just behaves like
  a plain fixed string, so templates are a strict superset of "plain
  string" at zero extra cost, and keeps one mechanism instead of two.
  `{title}`/`{body}` (`MacroTemplate.FIXED_MACROS`) are always
  available regardless of what the pattern declares. There is no
  `{appname}` macro (an earlier version had one, removed) - a rule is
  always scoped to exactly one app (`ParseRule.packageNames` is written
  as a singleton set by the editor), so which app it is never varies
  per match; a macro that can only ever resolve to one fixed value adds
  indirection without adding flexibility. The rule editor's insert-macro
  picker instead offers the app's name as a plain literal string to
  insert (see the `RuleEditFragment.showMacroPicker` note below).
  `tags` is the odd one out structurally: its template renders
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
  This is still true at the data level - `ParseRule`/`RuleEngine` treat
  both fields identically, no model-level "which side is the asset"
  concept exists. The *editor* now constrains one side per `RuleKind`
  to a Firefly-asset-account picker instead of free macro text (see the
  `RuleEditFragment`/`FireflyApiClient` notes below) - a UI-level
  requirement driven by what Firefly's own API will actually accept,
  not a `ParseRule` shape change.
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
    itself without `MessageStore` knowing persistence exists. Dedup key
    is `(packageName, title, text)` - *notification*-level, same app
    only. It says nothing about two *different* apps notifying about
    the same real-world transaction (e.g. a bank's own app plus
    Google/Apple Wallet both firing for one card tap) - that's a
    transaction-level dedup problem for whenever Firefly posting exists,
    see TODO.md.
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
    groups plus always-available `{title}`/`{body}`.
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
  - `validateRule(rule)` (`RuleValidationTest`) - checks the pattern
    compiles and defines `amount`, at least one app is picked, and the
    right source/destination account is filled per kind (see the
    `RuleKind` note above). The rule editor calls this before letting a
    save through. Deliberately *doesn't* check that every macro
    referenced in a template resolves to a declared group - that used
    to be a save-blocking "Unknown macro(s): ..." error, but it doubled
    up with `renderTemplate`'s own graceful handling (an unknown macro
    renders as literal text) and blocked saving over something
    harmless, e.g. simplifying a pattern down to just `amount` while a
    template field still mentions a macro from an earlier, richer draft
    of it. `currency`/`counterparty`/`foreignAmount`/`foreignCurrency`
    are all optional in the pattern for the same reason the fields that
    would reference them are optional.
  - `RulesDb` - plain SQLite (no ORM), one row per rule plus an
    explicit `sort_order` column - the only priority mechanism
    `RuleEngine` uses, so `moveUp`/`moveDown` just swap two rows'
    `sort_order`. `duplicate` shifts every later row's order up by one
    and inserts the copy right after the original, in a transaction.
    `distinctPatterns()` is the "pick an existing pattern" source for
    the rule editor - a derived query over this same table, newest
    first, one entry per distinct pattern; no separate library table.
    Still no built-in/canned patterns *in this table* - but
    `RuleEditFragment.showPatternPicker` always appends two hardcoded
    illustrative examples (`EXAMPLE_PATTERN`/`EXAMPLE_PATTERN_TITLE_ANCHORED`)
    after whatever's actually saved, so the very first rule a user ever
    creates isn't a blank textarea with zero clue about named-group
    syntax, and they stay available as a syntax reference even once real
    rules exist (used to be an empty-state-only fallback; removed once
    any real rule existed, which the user flagged as the example
    disappearing right when they'd want to compare against it).
    `EXAMPLE_PATTERN`'s `currency` group accepts a prefixed-dollar form
    like `US$`/`A$` (`[A-Z]{1,3}\$`), *or* a bare three-letter ISO code
    (`[A-Z]{3}`), *or* a bare symbol (`[€£$¥]`) - banks print symbols
    and prefixed-dollar forms far more than plain ISO codes, same
    reason `CurrenciesDb`/`normalizeCurrency` exist - and its `counterparty`
    group is `(?<counterparty>.+?)\.?$` - lazily matched with an
    optional trailing literal period stripped, not plain `.+`, because
    a notification's sentence-ending "." isn't part of the merchant
    name (real example: "...at Shop 9012 Markt GmbH-Zw." was capturing the
    period into `counterparty` before this).
    `EXAMPLE_PATTERN_TITLE_ANCHORED` is the second example, added after a
    real rule hit a classic regex pitfall head-on: a *greedy* `.*`
    between a literal title anchor and the amount group
    (`Zahlung erfolgreich\s*.*(?<amount>[\d.,]+)`) backtracks from the
    *end* of the string, so the amount group is tried starting as late
    as possible first - against "...45,30 € an Shop 5678 GmbH
    gezahlt.", that settled for `amount="0"` (just the last digit of
    "45,30"), which `parseAmount` then rejects (`signum() > 0`), landing
    as a silent `AMOUNT_UNPARSABLE` rather than a visibly wrong capture.
    The fix pattern uses `[^\d]*` instead - it can't overshoot past the
    number it's skipping to, since it stops at the *first* digit
    regardless of which one that is - and the example also demonstrates
    `(?:gezahlt)?` before the optional trailing period, so a known
    trailing verb doesn't end up inside `counterparty` (the same
    "sentence words leaking into a lazy capture group" class of problem
    as the trailing-period fix above, different symptom). Both pitfalls
    and the fix are covered by `NamedGroupMatcherTest` (regex-engine
    level, not UI-level - this is a property of Java's backtracking
    semantics, nothing `rules`/`ui` can special-case around; the fix has
    to live in the pattern itself).
    `RulesDb.getInstance(context)` is the one shared instance (same
    reasoning as `MessageHistoryDb.getInstance`).
    `DB_VERSION` has been bumped five times already as the rule shape
    settled (2: `account_name`/`counterparty_account_name` columns ->
    `source_account_name`/`destination_account_name`; 3: added
    `category_template`/`tags_template`; 4: dropped
    `decimal_separator` - see `parseAmount` above; 5:
    source/destination_account_name became `NOT NULL`; 6: added
    nullable `source_account_id`/`destination_account_id`, see
    `ParseRule`'s KDoc) - `onUpgrade` just drops and recreates the
    table, fine for a personal, not-yet-relied-upon rules list; revisit
    if that's ever not true.
    Not unit tested - like `MessageHistoryDb`, it's thin glue over real
    `SQLiteOpenHelper`/Android framework classes that don't run under
    plain JVM unit tests.
- `prefs` - thin `SharedPreferences` wrappers for configuration (not
  notification content, so - unlike captured messages by default -
  persisted across restarts/reboots on purpose):
  - `MonitoredAppsPrefs` - which package names to monitor.
  - `RetentionPrefs` - local history retention in days; `0` (default)
    means off/in-memory-only.
  - `FireflyPrefs` - the Firefly III connection + posting-behavior
    settings (`FireflySettingsFragment` is the UI): base URL, personal
    access token, the approximate-conversion tag name, auto-create
    toggle, dedup toggle + include-description toggle + window
    (minutes, default 5). Base URL and token are the only genuinely
    secret values in this app (AGENTS.md's Secrets section) - encrypted
    through `KeystoreCipher` before being stored, in an otherwise plain
    `SharedPreferences` (every other field here is unencrypted, same as
    every other prefs class). *Not* built on
    `androidx.security:security-crypto`'s `EncryptedSharedPreferences`/
    `MasterKey` - checked before using them and found every API in that
    library deprecated as of 1.1.0 with no further releases planned.
  - `KeystoreCipher` - the replacement: AES-GCM via a key generated and
    held *inside* Android Keystore (`KeyGenParameterSpec`/
    `KeyProperties.PURPOSE_ENCRYPT or PURPOSE_DECRYPT`, alias
    `firefly_prefs_key`) - the key material itself never leaves
    hardware-backed storage, only `Cipher` operations cross that
    boundary. One key, reused for both secret strings; stores
    `ivBase64:cipherBase64` as the pref value, since GCM needs the
    exact IV it encrypted with to decrypt and generates a fresh random
    one every call. `decrypt` returns null (never throws) on anything
    it can't make sense of - corrupt/foreign-format stored value, key
    unavailable - so a caller treats that the same as "nothing stored
    yet". Not unit tested - real Android Keystore, same reason
    `RulesDb`/`MessageHistoryDb` aren't.
- `network` - the Firefly III API client, `rules`-API-free (string
  placeholders are what `rules` still uses - see the `ParseRule` note
  above):
  - `FireflyUrl.kt` - pure helpers kept separate from the real network
    call specifically so they're plain-Kotlin unit testable
    (`FireflyUrlTest`): `normalizeBaseUrl` (strips a trailing slash),
    `hostOf` (for the Settings row's "Connected to X" subtitle),
    `isCleartextUrl` (drives the in-UI `http://` warning),
    `parseAboutVersion` (pulls the version string out of
    `GET /api/v1/about`'s `{"data": {"version": ...}}` response, used
    to confirm Test Connection actually talked to a real Firefly
    instance and not just got a 200 from something else on that URL).
  - `FireflyApiClient` - plain `HttpURLConnection` + `org.json`, not
    OkHttp/Retrofit - same "avoid a new dependency when the framework
    already covers it" reasoning as the planned rules export/import
    JSON format (see TODO.md); a handful of endpoints doesn't justify a
    full HTTP client library. `org.json` at runtime is the real
    Android-bundled implementation, but JVM unit tests run against a
    stub `android.jar` where its methods throw "not mocked" - a
    `testImplementation("org.json:json:...")` real-implementation jar
    is what actually makes `FireflyUrlTest`/`FireflyJsonTest` exercise
    real JSON parsing instead of silently no-op'ing.
    `testConnection()` (`GET /api/v1/about`), `listAssetAccounts()`
    (`GET /api/v1/accounts?type=asset`, follows `meta.pagination.
    total_pages` across every page rather than assuming the first 50
    is everything), and `createTransaction(FireflyTransactionRequest)`
    (`POST /v1/transactions`) all exist now - nothing in the UI calls
    the latter two yet (see TODO.md for what still has to exist first:
    an asset-account picker, a dedup log, the "Создать" button).
    `FireflyJson.kt` holds the request-building/response-parsing for
    those two, pulled out the same way `FireflyUrl.kt` was, for the
    same plain-Kotlin-unit-testable reason (`FireflyJsonTest`).
    Request/response field names and shapes (`TransactionSplitStore`,
    `AccountRead`, the `TransactionTypeProperty` enum being exactly
    `withdrawal`/`deposit`/`transfer`, lowercase) were checked against
    Firefly's own OpenAPI spec before writing any of this, not
    guessed - `FireflyTransactionRequest.type` is that exact literal
    string, not `rules.RuleKind`, to keep `network` genuinely decoupled
    from `rules` (the caller maps `RuleKind.name.lowercase()`).
    `createTransaction` always sends `error_if_duplicate_hash: true` -
    a free extra safety net on top of this app's own planned dedup
    check (see TODO.md), since Firefly rejects the request outright if
    *it* also thinks this is a duplicate.
  - Cleartext (`http://`) support: Android blocks it by default at
    API 28+. `res/xml/network_security_config.xml` allows it globally
    (`<base-config cleartextTrafficPermitted="true" />`) rather than
    for a specific domain, since the Firefly host is whatever the user
    types in Settings - not knowable at build time. The URL field warns
    in the UI when it's `http://`; the manifest's
    `android:networkSecurityConfig` is what actually lets that
    connection succeed instead of failing silently.
  - `ExchangeRateClient`/`ExchangeRateUrl` - same split as
    `FireflyApiClient`/`FireflyUrl` (real call vs. plain-Kotlin
    unit-testable URL-building/response-parsing, `ExchangeRateUrlTest`),
    talking to Frankfurter (`api.frankfurter.dev/v2`, a free, keyless,
    ECB-based rate API - completely unrelated to Firefly's own HTTP API,
    just living in the same `network` package since it's this app's
    other outbound HTTP call) rather than Firefly's own built-in
    exchange-rate table - see TODO.md's `LOCAL_AMOUNT_MISSING` entry for
    why: Firefly silently falls back to a rate of 1.0 for a pair it has
    no data for, with nothing in the response flagging that. `GET
    /rate/{base}/{quote}?date=YYYY-MM-DD` (the notification's own date,
    not "today"), response shape `{"date","base","quote","rate"}` -
    verified against the live API, not assumed. No API key. `rate` is
    read via `optString`+`BigDecimal(String)`, not `optDouble`, to avoid
    a double round-trip on a value about to be multiplied against a
    real money amount. The computation itself
    (`rules.applyApproxConversion` - `amount = foreignAmount * rate`,
    `HALF_UP` to 2 decimals, plus appending
    `FireflyPrefs.getApproxConversionTag()` to the draft's tags) lives
    in `rules`, not `network`, and takes a plain `BigDecimal` rate - so
    `rules` still has no dependency on `network`, same direction as
    `network` not depending on `rules`; the caller (currently only
    `MessagesFragment`, see its entry below) is the one bridging both.
- `backup` - export/import and full backup/restore, added directly in
  response to a real data-loss incident (see "Known bugs / gotchas"
  below) - the user asked for both in the same breath once it happened:
  a way to share rules, *and* a way to not lose everything again. Two
  genuinely different tools, not one feature with two buttons - kept
  as separate pure-JSON files/UI entry points because their semantics
  differ (append vs. replace) and conflating them would make it easy
  to pick the wrong one by accident:
  - `RulesJson.kt` (`rulesToJson`/`rulesFromJson`, `RulesJsonTest`) -
    the Rules screen's own Export/Import buttons
    (`RulesListFragment`), a *sharing* format for one or more rules
    between devices/people. Import always appends as new rows
    (`RulesDb.add` assigns a fresh id) - never a replace - so sharing a
    rule can't accidentally wipe rules someone already built
    themselves. Deliberately excludes `id` and
    `sourceAccountId`/`destinationAccountId`: a row id is this
    device's internal identity, and a Firefly account ID is meaningless
    on a *different* Firefly instance (the asset-side account needs
    re-picking after import regardless - the name alone travels fine,
    same as it always has).
  - `FullBackupJson.kt` (`FullBackupData`, `fullBackupToJson`/
    `fullBackupFromJson`, `FullBackupJsonTest`) + `BackupManager`
    (Android-dependent orchestration, not unit tested, same reason
    `RulesDb` isn't) - the new "Backup & restore" Settings row
    (`BackupFragment`), covering monitored apps, currencies, rules
    (reusing `RulesJson`'s per-rule shape), and message history in one
    file. Deliberately excludes the Firefly connection (base URL/
    token): the one real secret in this app, `KeystoreCipher`-encrypted
    against an Android Keystore key that wouldn't even decrypt on a
    different device or after a reinstall, and a backup file is exactly
    the kind of thing that ends up in a cloud folder or gets shared -
    not somewhere a bearer token belongs. `BackupManager.restore`
    *replaces* every store it touches (clears first, then re-adds) -
    restoring puts the device back exactly how the backup found it,
    not merged on top of whatever's currently there. `BackupFragment`
    confirms with the user before calling it (`AlertDialog`,
    "This can't be undone") - the "ask before a destructive/hard-to-
    reverse action" step that was missing from the old `RulesDb`
    `onUpgrade` pattern that prompted this whole feature.
  - Both read/write via Storage Access Framework
    (`ActivityResultContracts.CreateDocument`/`OpenDocument`,
    `application/json`) - no storage permission needed, no app-private
    file path to explain to the user, the system file picker is the
    whole UI for "where does this go"/"where do I get this from".
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
    renders the current snapshot, newest first. Long-press starts
    multi-select (tap toggles more items in or out); its delete action
    calls `MessageStore.shared.remove` for each selected message after
    a confirmation dialog. Multi-select is a hand-rolled toggle of
    `MainActivity`'s own single `Toolbar` - swapping its title to
    "N selected", enabling the up/back arrow (`onSupportNavigateUp`
    cancels selection), and swapping its menu to just a delete action
    (`menu_selection.xml` vs. the normal `menu_main.xml`, chosen in
    `onCreateOptionsMenu` based on whether a selection-delete callback
    is currently set) via `MainActivity.enterSelectionMode`/
    `updateSelectionCount`/`exitSelectionMode`, which `MessagesFragment`
    drives. System back (button or gesture, not just the toolbar's own
    up arrow) also cancels the selection instead of exiting the app -
    an `OnBackPressedCallback` registered once in `onCreate`, enabled
    only between `enterSelectionMode`/`exitSelectionMode` so normal
    back behavior applies the rest of the time. This replaced a standard `androidx.appcompat.view.ActionMode`
    (`startSupportActionMode`) after a real, visible bug: with a
    manually-set `Toolbar` on a `NoActionBar` theme, AppCompat's
    `ActionMode` doesn't replace that toolbar - it renders its own
    contextual bar as an *extra* bar stacked above it, and the combined
    height of both bars pushed the list down enough to cover the first
    row. Reusing the one real `Toolbar` for both states has no such
    ambiguity - it's the same view in the same layout slot throughout,
    just different content. A plain tap *outside* of that selection mode
    runs `RuleEngine.match` against the tapped message and shows the
    result (source/destination account, amounts/currencies, rendered
    description/notes, issues) in a dialog - "test match". The dialog's
    *title* is the outcome itself: the matched rule's own label, or
    "No match" - not a generic "Test match" every time regardless of
    what happened, which made the matched-vs-not distinction easy to
    miss at a glance. The body is a `SpannableStringBuilder` with each
    field's label (`Source:`, `Amount:`, ...) in bold - plain
    `getString(R.string.x, value)` concatenation read as one
    same-weight wall of text; this needed splitting the old combined
    "Label: %1$s" string resources into label-only strings so code can
    bold just the label span. When the draft has `LOCAL_AMOUNT_MISSING`
    (foreign-currency message, no home-currency amount), the dialog
    shows "no amount" immediately and, in the background
    (`fetchApproxConversionIfNeeded`, `viewLifecycleOwner.lifecycleScope`),
    fetches a real rate from `network.ExchangeRateClient` for the
    message's own date and swaps the already-shown dialog's message in
    place (`AlertDialog.setMessage`, works after `.show()`) once it
    resolves - a computed amount + the approx tag on success
    (`rules.applyApproxConversion`), or the original body plus an
    explicit "lookup failed: <reason>" line on failure. This is a
    preview-only convenience - nothing is posted to Firefly from this
    dialog, see TODO.md for the still-missing "Создать" button/posting
    pipeline this would feed into.
  - `SettingsActivity` - everything else, as a stack of fragments
    pushed via plain `FragmentManager` back-stack transactions (no
    Navigation Component - the flow is small and linear enough not to
    need it). `SettingsRootFragment` is the menu (Firefly III
    connection, monitored apps, currencies, rules, message history, add
    test message, backup & restore, about, added one row
    at a time as each is built - Firefly connection sits first since
    it's the gateway to actually doing anything with a rule match now
    that posting is underway; currencies sits above rules since rules
    depend on it, same reasoning as monitored apps coming first; about
    sits last, after backup & restore - nothing to configure, just name/
    version/GitHub link, so it belongs at the very bottom of the list),
    linking to:
    - `FireflySettingsFragment` (see the `prefs`/`network` package
      notes above for `FireflyPrefs`/`FireflyApiClient`) - one form,
      one explicit Save button, not autosave-per-field like
      `MessageHistoryFragment`'s radio group, since several of these
      fields are text the user is still typing/correcting rather than
      a single tap choice. "Test connection" runs
      `FireflyApiClient.testConnection()` on a coroutine
      (`viewLifecycleOwner.lifecycleScope`) against whatever's
      currently typed in the URL/token fields - not what's saved - so
      the user can verify before committing. The Settings row's own
      subtitle ("Connected to X" / "Not configured") only reads
      `hostOf(getBaseUrl())`, not a live connection check - a cheap,
      offline-friendly signal that *something* is saved, not proof it
      still works.
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
    - `AboutFragment` - app name, `versionName` (read from
      `PackageManager.getPackageInfo`, not hardcoded, so it can't drift
      from `app/build.gradle.kts`), and a GitHub link
      (`about_github_url`, opened via `ACTION_VIEW`) - static, no
      prefs/db involved.
    - `RulesListFragment` - the ordered rule list (`RulesAdapter`);
      tap opens `RuleEditFragment` to edit, the overflow (⋮) menu per
      row has move up/down, duplicate, delete - all straight
      `RulesDb` calls followed by a reload. Add/Export/Import
      (`RulesJson`, see `backup` above) are three `menu_rules_list`
      action icons (`ic_add`/`ic_export`/`ic_import`) on the hosting
      `SettingsActivity`'s toolbar, wired via a `MenuProvider` added in
      `onViewCreated` (`viewLifecycleOwner`, `Lifecycle.State.RESUMED`
      - scoped to this fragment's own lifecycle, so it stops
      contributing the moment the user navigates away, same pattern
      `MainActivity`'s selection-mode menu switch uses at the Activity
      level). This replaced an actual `FloatingActionButton` plus two
      `ImageButton`s, round-tripped through several placements this
      session: first a row of icon buttons above the list (matching the
      FAB's "icon, not text" language but not its position - flagged
      directly, "я же просил поставить кнопки рядом с +"), then the
      same icon buttons moved into the FAB's own bottom-end row - which
      the user then rejected outright too, not for position but because
      a filled, colored, elevated Material FAB sitting next to two
      plain borderless icon buttons never reads as "the same kind of
      button" ("какого фига разные кнопки делаешь?"; the FAB's own
      shadow also visibly clipped against its parent's bounds at that
      position, a separate rendering bug this change sidesteps
      entirely). Toolbar action icons give all three the exact same
      rendering for free (tinted icon, ripple, no background, no
      elevation) with no custom layout to get wrong. The icons
      themselves also went through a multi-round design review (plain
      Material up/down tray-arrow icons -> an attempt at a folder
      silhouette with a DB cylinder inside and an arrow through a gap
      in the outline, iterated live via a published Design-type
      Artifact - several rounds fixed in the abstract HTML mockup
      didn't survive contact with the real on-device render at actual
      icon size, e.g. an arrow-to-outline overlap that looked connected
      in the browser read as a floating, disconnected arrow on device -
      before the user called the whole direction a net loss and asked
      for the original tray-arrow icons back). Net result: still the
      original plain up/down tray-arrow `ic_export`/`ic_import` glyphs,
      plus a new matching filled `ic_add` (Material's own "add" glyph)
      so all three toolbar icons share one visual language. Each row's
      subtitle is the app name bold on its own line (`SpannableStringBuilder`
      + `StyleSpan`, not a separate `TextView` - it's the row's identity,
      everything else is detail about that one app), then
      `"<kind>: <source> → <destination>"` on the line below - always
      that pair shape, even when one side is blank by design (normal
      for WITHDRAWAL/DEPOSIT, which only ever fill one side; see the
      `RuleKind` note above). A blank side displays as
      `rule_account_no_name` ("(no name)") rather than leaving a blank
      gap next to the arrow, which read as if text had gone missing -
      deliberately the same placeholder Firefly III itself shows for a
      null account, so it reads as a familiar convention instead of
      app-invented text. Display-only: this is `RulesAdapter.bind`
      string-formatting the already-loaded `ParseRule`, nothing is
      written back to `RulesDb` or (once it exists) sent to Firefly -
      `TransactionDraft.sourceName`/`destinationName` stay genuinely
      `null` when unset (an earlier, different placeholder here -
      `"(from notification)"` - was removed outright after user
      feedback that its meaning wasn't obvious at a glance; this one
      replaces that spot with the Firefly-familiar wording instead of
      reintroducing the same confusion).
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
      section: the regex field itself, then three left-aligned
      link-style buttons in one row *below* it, in this order - "pick
      existing" (every saved rule's distinct regex, *plus* two
      hardcoded illustrative examples always appended at the end
      regardless of how many real ones already exist - they're a syntax
      reminder, not just an empty-state filler, see `RulesDb` below;
      each list item shows its label on one line and the *full* regex
      on the next - not truncated - so it's shown in full before being
      applied without needing a separate confirm step on top of that;
      tapping an item applies it immediately), "pick message" (lists
      `MessageStore.shared.snapshot()` filtered to the currently
      selected app, in-memory session data only - picking one drops
      its raw `"title\nbody"` into the pattern field as a literal
      starting point to then wrap with named groups, instead of
      hand-copying the bank's wording from the Messages screen), "test"
      (runs the rule *as currently drafted* - unsaved edits included,
      not gated on `validateRule` since fields irrelevant to matching
      shouldn't block a pattern sanity check - against this session's
      captured messages for the selected app, newest first, stopping
      after 3 matches found). Each match is shown as the notification's
      raw `title` as the row's bold header, followed by one
      `groupname: value` line per named group the pattern declares as
      the body - not the derived `TransactionDraft` fields
      (amount/account/etc), which depend on rule config that may still
      be unfilled while iterating on a pattern and so aren't what "does
      my regex actually capture what I want" is asking
      (`ParseOutcome.Matched.groups` exposes the raw capture map for
      exactly this); the summary line ("N match(es) found (scanned M)")
      sits above the match rows.
      "pick existing" and "pick message" render their lists through a
      shared `PickerItemAdapter` (`item_picker_two_line.xml` +
      `bg_picker_item.xml`, a thin `?attr/colorOutlineVariant` stroke -
      a Material3 token so it survives light/dark theme switches rather
      than a hardcoded hex - around each row, with outer *padding* not
      *margin* creating the gap between rows, since `ListView` silently
      drops margins on a row's root view) instead of plain
      `AlertDialog.setItems` text: each row is a bold header line over a
      normal-weight body line, with the pattern picker's body
      additionally forced to `Typeface.MONOSPACE` (matching the regex
      field itself) - the message picker's body stays the default
      typeface since raw notification text isn't code. "test" reuses the
      *same* row layout (via a shared `bindPickerItemRow` helper, not
      `PickerItemAdapter` itself) but can't use `setAdapter` for it -
      `AlertDialog.Builder`'s `setMessage` and `setAdapter` don't
      actually combine despite looking like independent content slots:
      setting both silently drops the adapter's list, rendering only the
      message (shipped broken this way for one version - v0.26 - before
      being caught). Fixed by building a plain custom view instead
      (`dialog_test_results.xml`: a `ScrollView` > `LinearLayout`) with
      the summary `TextView` and each match's inflated
      `item_picker_two_line` row added to it programmatically, passed to
      `setView`. `MAX_TEST_MATCHES` keeps this list short enough that
      `ListView`'s view-recycling would add nothing here anyway. Before
      any of this, header/body/separate matches all rendered as one
      undifferentiated block of text with `"──────────"` separators -
      no visible boundary between a row's two lines or between separate
      rows.
      Then the live
      "available macros" hint from `parser.namedGroupNames`, then
      *source/destination account* (moved here, below the regex, from
      the identity-fields group above - once they're macro templates
      they only make sense once you're thinking about what the regex
      extracts, same reasoning `homeCurrency` already had), then
      `homeCurrency` (labeled "Account currency" in the UI - "Currency"
      alone read as ambiguous once cross-currency transactions came up
      in discussion: it's the *account's* currency, a fixed comparison
      value, not "whatever currency this transaction happens to be
      in"), then the rest of the templates.
      Source/destination each switch between *two different* end-icon
      behaviors depending on `selectedKind`
      (`RuleEditFragment.updateAccountFieldModes`, called on kind change
      and once right after `populateFields`) - which side is the
      *asset* account Firefly requires to already exist versus the
      *counterparty* side Firefly auto-creates by name (see the
      `FireflyApiClient` note above for why this split exists at all):
      WITHDRAWAL source=asset/destination=counterparty, DEPOSIT
      source=counterparty/destination=asset, TRANSFER both=asset. The
      counterparty side keeps the macro-template behavior description/
      notes/category/tags already had (`ic_insert_macro`,
      `showMacroPicker`, `Editable.replace` at the cursor, not
      `setText` - the field may have other text around where the macro
      goes; also offers the current app's name as a plain literal
      string, not a `{macro}`, see the `{appname}` removal note above).
      The asset side gets the *same* end icon `homeCurrency` already
      had (`ic_dropdown`) but over `FireflyApiClient.listAssetAccounts()`
      instead of `CurrenciesDb` - `setText`s the whole field on pick,
      same "this holds one fixed value, not a template to build up
      piece by piece" reasoning, fetched once per screen session and
      cached in `RuleEditFragment.assetAccounts` (a personal Firefly
      instance's account list doesn't change often enough to justify a
      network call on every picker tap) rather than failing the whole
      screen when Firefly isn't configured yet, this just falls back to
      a Toast and leaves the field free-typeable - the rule can still be
      drafted and fixed up once a connection exists. The picker is
      `setSingleChoiceItems`, not `setItems` - pre-selects (radio mark)
      whichever account already matches the field's current text, so
      reopening it shows what's already there at a glance instead of an
      unmarked list every time; still applies and dismisses on one tap,
      same as before, no separate confirm step.
      Picking an account also captures its Firefly ID
      (`RuleEditFragment.PickedAccount`, held in
      `sourceAccountPick`/`destinationAccountPick`) alongside the name -
      an ID survives a Firefly-side account rename, a name alone
      doesn't. `buildDraft` only carries the ID into `ParseRule.
      sourceAccountId`/`destinationAccountId` if the field's *current*
      text still equals the name that was picked - if the user edits it
      away afterward, the ID would point at a different account than
      the name now shown, so it's dropped rather than silently sending
      a mismatched ID/name pair. A rule saved before this existed (or
      whose account field has never been through the picker) simply has
      a null ID and keeps resolving by name at post time - see
      `FireflyJson.buildTransactionRequestBody`, which sends `source_id`/
      `destination_id` instead of the `_name` variant whenever an ID is
      present (Firefly's own docs: submit one or the other, not both).
      Every macro-template field (the counterparty account side plus
      description/notes/category/tags - *not* `homeCurrency`, which
      `renderTemplate` never touches: `RuleEngine` compares the captured
      currency *against* it literally to decide local vs. foreign
      amount, so a `{macro}` typed in there wouldn't render to
      anything - it'd just sit as that literal string and never match a
      real currency) has a custom end icon
      (`TextInputLayout.setEndIconOnClickListener`, `ic_insert_macro`)
      that opens a picker (`RuleEditFragment.showMacroPicker`) over the
      currently declared macros (the pattern's named groups plus
      `FIXED_MACROS`) *plus* the current app's name as a plain literal
      string and inserts the picked text at the current cursor
      position/selection in that field. The end icon tap (and the
      picker dialog it opens) moves focus off the field, so insertion
      explicitly calls `requestFocus()` +
      `setSelection(insertAt + text.length)` afterward - without it the
      cursor was left stranded wherever it happened to be instead of
      right after what was just inserted. Saves having to hand-type
      e.g. `{currency}` and getting the spelling wrong.
      `homeCurrency` gets the dropdown end icon too
      (`RuleEditFragment.showCurrencyPicker`) - a plain list of the
      user-maintained `CurrenciesDb` codes (e.g. "EUR (€)"). Saves a
      typo turning into a currency `normalizeCurrency` never
      recognizes.
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

- **Real incident, data lost**: every `SQLiteOpenHelper.onUpgrade` in
  this app (`RulesDb`/`MessageHistoryDb`/`CurrenciesDb`) used to just
  `DROP TABLE` and recreate on *any* `DB_VERSION` bump - "fine for a
  personal, not-yet-relied-upon rules list" reasoning that was true
  early on but silently went stale as real rules accumulated over many
  sessions. The `RulesDb` v5->v6 bump (adding
  `source_account_id`/`destination_account_id`) wiped the user's actual
  hand-built rules with no backup and no warning. `RulesDb.onUpgrade`
  is now a real additive migration (`ALTER TABLE ... ADD COLUMN` per
  version, never drop+recreate again - see its KDoc);
  `MessageHistoryDb`/`CurrenciesDb` still have the old destructive
  `onUpgrade` and need the same treatment *before*, not after, their
  next schema change - both now carry a comment pointing at this
  incident so it isn't repeated there either. Lesson: "disposable test
  data" stops being an accurate justification the moment a user starts
  relying on what's stored, and that transition has no clear signal -
  bumping a `DB_VERSION` deserves friction (confirm with the user, or
  just write the migration) from here on, not habit.
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
- The settings area and the rules flow have both had extensive
  real-device verification since - see the many rounds of user-reported
  UI/UX fixes throughout this file's history. Current gap: the
  Firefly III connection itself (Test connection confirmed working
  on-device; `listAssetAccounts()`/`createTransaction()` not yet
  exercised against a real instance, since nothing calls
  `createTransaction()` yet - see TODO.md).
