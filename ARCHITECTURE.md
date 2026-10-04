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
  - `CapturedMessage` (app/title/text/timestamp, plus a nullable
    `fireflyTransactionId` - set once a transaction has been created
    from this message, see `ui.MessagesFragment`/`MessageStore.
    setTransactionId` below). Deliberately *not* part of what
    identifies a message anywhere else in this file (dedup, removal,
    `MessageHistoryDb` lookups all key on the other five fields) -
    it's exactly the field those call sites want to change in place,
    so including it in identity would make "find this message and
    update its id" impossible to express as a lookup.
  - `MessageStore` - the in-memory, time-windowed dedup store
    (`MessageStoreTest`). `MessageStore.shared` is the one instance
    used by the listener service and the UI. `add` dedups, notifies
    listeners, and returns the recorded `CapturedMessage` (or null if
    dropped as a duplicate) - changed from a plain `Boolean` specifically
    for `posting.AutoCreateScheduler` (see the `posting` section below):
    it needs the *exact* `timestampMillis` `add` actually stored, and
    re-deriving "now" a second time at the call site could race against
    a second notification arriving in between and land on a different
    value than what was stored internally - returning the already-
    computed message avoids that race entirely.
    **Fixed a real ordering bug, caught directly by the user right
    after the `postTime` fix above**: the list is kept sorted
    newest-first, which `add` used to maintain simply by always
    inserting at index 0 - safe only as long as every new
    `timestampMillis` is guaranteed larger than every previous one,
    true back when it was always `clock()` ("now", monotonic). Once
    `timestampMillis` became `sbn.postTime` (see `notifications`
    below), that guarantee broke: a notification replayed on listener
    reconnect can be *captured* after a genuinely newer one but carry
    an *older* `postTime`, and inserting it at the front put it above
    entries it's actually older than. `add` now finds the correct
    sorted position (`indexOfFirst { it.timestampMillis <= now }`) and
    inserts there instead of unconditionally at the front - `restore`
    (below) is unaffected, it already relied on its caller pre-sorting
    `historical`, not on this. `restore` bulk-seeds
    already-deduplicated entries
    (loaded from disk at startup) without re-running the dedup check;
    `remove` deletes one entry (matched by value equality - there's no
    separate id) for the Messages screen's delete action.
    `setTransactionId(message, id)` replaces the entry matching
    [message]'s other five fields with a copy carrying `id`, in place
    (same list position) - used instead of full value-equality matching
    specifically because the field being changed can't be part of the
    match. The optional `onNewMessage`/`onMessageRemoved`/
    `onMessageUpdated` hooks are how persistence attaches itself
    without `MessageStore` knowing persistence exists. Dedup key is
    `(packageName, title, text)` - *notification*-level, same app
    only. It says nothing about two *different* apps notifying about
    the same real-world transaction (e.g. a bank's own app plus
    Google/Apple Wallet both firing for one card tap) - that's the
    separate, now-built transaction-level dedup, see `rules.
    transactionDedupHash`/`CreatedTransactionsDb` below and TODO.md.
  - `MessageHistoryDb` - the on-disk mirror, plain `SQLiteOpenHelper`
    (no ORM), one `messages` table. Only ever written to while
    retention is enabled. `MessageHistoryDb.getInstance(context)` is
    the one shared instance (a `SQLiteOpenHelper` per call would leak
    connections). `DB_VERSION` 1->2 added
    `firefly_transaction_id` via a real `ALTER TABLE ... ADD COLUMN`
    migration - this table's schema had never actually changed before,
    so this was the first real test of the promise its `onUpgrade`
    comment had been making since the `RulesDb` data-loss incident (see
    Known bugs/gotchas below): additive only, never drop+recreate.
    `updateTransactionId(message)` mirrors `MessageStore.
    setTransactionId` on disk, matching the row the same "every column
    except the one being changed" way.
  - `RetentionController` (`RetentionControllerTest` for the pure
    cutoff-date math) - wires `MessageStore` and `MessageHistoryDb`
    together based on the user's retention setting.
    `applyOnStartup` (called once, from `FfNotifyImporterApp`) may
    call `MessageStore.restore`; `applyOnSettingChanged` (called from
    the settings UI when the user changes the value mid-session) never
    does, since `restore` has no dedup against what's already in
    memory and calling it twice would duplicate entries already shown.
    Wires all three `MessageStore` hooks (`onNewMessage`/
    `onMessageRemoved`/`onMessageUpdated`) to the matching
    `MessageHistoryDb` call, and clears all three (not just the first
    two) when retention is turned off.
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
  - `parseDate(raw, pattern)` (`DateParserTest`) - the opposite call on
    auto-detection from `parseAmount`: a date-only string like
    `01.02.30` genuinely can't be disambiguated (day-first vs.
    month-first) without knowing the bank's own convention, so this
    takes an explicit `DateTimeFormatter` pattern (`ParseRule.
    dateFormat`, a `rules`-layer concept - `parser` itself has no
    opinion on it) rather than guessing. Null on a malformed pattern or
    a non-matching `raw`, never throws - the caller (`RuleEngine`)
    decides what that means. Resolves to noon UTC on the parsed date
    (no time of day - a bank announcing a future charge never gives
    one), a fixed but arbitrary choice that sidesteps any
    timezone-driven day-rollover surprise a midnight value could cause.
- `rules` - the Firefly-*flavored* domain, still Firefly-API-free
  (string placeholders - see Overview):
  - `ParseRule` - one parsing template scoped to one app
    (`sourceAccountName`/`destinationAccountName`/`homeCurrency` are
    placeholders; `pattern` matched against `parser`'s combined-input
    convention; `descriptionTemplate`/`notesTemplate` are macro
    templates, blank by default - no silently pre-filled text).
    `dateFormat` is blank by default (= the old, still-default
    behavior: the transaction's date is when the notification arrived)
    - a `java.time.format.DateTimeFormatter` pattern the user types in
      (e.g. `dd.MM.yy`), for banks that announce a *future* charge date
      separately from when the notification itself arrives (real case:
      Bank DE - "Die Abbuchung über €99,99 für 00000 findet am
      01.02.30 statt"). Deliberately user-specified, not auto-detected
      like `parseAmount`'s decimal separator - a date like `01.02.30`
      is genuinely ambiguous (day-first or month-first) without the
      bank's own convention, and guessing wrong risks silently posting
      the wrong date; see TODO.md.
  - `Groups` - the named-group names `rules` knows to look for
    (`amount`, `currency`, `foreignAmount`, `foreignCurrency`,
    `counterparty`, `date`) - a convention `parser` has no opinion on.
    `date` only does anything when `ParseRule.dateFormat` is also set
    (see `RuleEngine.buildDraft` below); otherwise it's just another
    name available to `{macro}` templates, same as `counterparty`.
  - `renderTemplate`/`templateMacroNames`/`FIXED_MACROS`
    (`MacroTemplateTest`) - `{x}` macro substitution for
    description/notes/account names/etc, using the match's captured
    groups plus always-available `{title}`/`{body}`. `{{x}}` (doubled
    braces) escapes a literal `{x}` that isn't meant as a macro at all
    (e.g. a real Firefly tag named `{User}`) - the user's own request:
    before this existed, `x` colliding with a real declared group or
    `FIXED_MACROS` got silently substituted with no way to say "I mean
    the literal text". `renderTemplate`'s regex tries the escaped-pair
    alternative first at each position (standard leftmost-alternative-
    wins behavior for `\{\{(\w+)\}\}|\{(\w+)\}`), and `templateMacroNames`
    only ever counts the plain-macro capture group, never the escaped
    one. `RuleEditFragment`'s "available macros" hint gained a second,
    static line pointing at the `{{word}}` syntax.
  - `TransactionDraft`/`DraftIssue`/`ParseOutcome`/`NoMatchReason` -
    what a match produced (or why it didn't match) - postable now
    (`ui.MessagesFragment`'s "Создать" button, see its entry above),
    via a plain mapping onto `network.FireflyTransactionRequest`, not a
    `rules` -> `network` dependency (the mapping lives in the UI layer
    that already depends on both). `sourceAccountId`/
    `destinationAccountId` still exist on this class, but `RuleEngine.
    buildDraft` always leaves them null now - `ParseRule` no longer
    caches a Firefly account id at all (see its own KDoc for the real
    cross-rule dedup bug that caused). Only `ui.CreateTransactionFragment`'s
    own one-shot account picker ever sets one, for posting precision on
    that single transaction; `transactionDedupHash` deliberately ignores
    both fields regardless (name only, see its own KDoc).
  - `applyApproxConversion` (`ApproxConversionTest`) - pure
    `amount = foreignAmount * rate` (`HALF_UP`, 2 decimals) plus
    appending the approx-conversion tag, given a rate the caller
    already fetched (`network.ExchangeRateClient`, see the `network`
    section above) - `rules` still has no dependency on `network`.
  - `transactionDedupHash`/`CreatedTransactionsDb`
    (`TransactionDedupTest`, `FullBackupJsonTest`) - the
    transaction-level dedup check, a different problem from
    `MessageStore`'s notification-level dedup (see TODO.md's dedup
    history for why both exist). The hash is pure - canonical-scale
    amount, currency, description only when `includeDescription` is
    true, and only the account side(s) `RuleValidation.validateRule`
    actually requires a name for *this draft's kind* (by name only, not
    id-or-name - see the third fix below for why): source only for
    `WITHDRAWAL`, destination only for `DEPOSIT`, both for `TRANSFER`.
    **Fixed a second real bug, found right after the
    first one below**: it used to always hash both sides regardless of
    kind - harmless comparing two local hashes against each other
    (the kind-irrelevant side is always equally blank for the same
    rule), but once a comparison involves data from *outside* this
    app (see `remoteCandidateMatches` below) the irrelevant side can
    have a real value sitting there that this app never asserted
    anything about, and the hash rejected an otherwise-exact match
    over it - caught directly by the user testing a `WITHDRAWAL` rule
    with no destination name configured, matched against Firefly's own
    stored transaction (whose destination was a real account, not
    blank). The user's own direct call, deliberately not routed around
    with a smaller, more defensive patch: "не мудри, меняй
    transactionDedupHash" - a kind-aware workaround at every call site
    would have been the "мудри" version. **Breaking change, accepted
    deliberately**: a `CreatedTransactionsDb` row written under the old
    always-both-sides formula, for the uncommon case where the
    kind-irrelevant side was actually non-blank, won't be found by a
    lookup computed under this one - no migration for this, same as
    the two-hash fix below couldn't recover data the old single-hash
    schema never kept either.
    **Fixed a third real bug, caught directly on-device from two
    different rules auto-creating separate transactions for the exact
    same real payment**: account side(s) used to hash `accountId ?:
    accountName` (preferring a cached Firefly account id, when
    `ParseRule` had one, over the plain name) - the actual root cause
    once diagnosed: two *different* rules for the same real account
    (a bank app's own rule and a wallet app's rule, say) don't
    necessarily agree on whether an id got cached for it - one picked
    via `RuleEditFragment`'s account picker (had one), the other
    free-typed or saved before the picker existed (didn't) - so the two
    rules' drafts hashed to two *different* values for the identical
    real account, and genuine cross-app duplicates went uncaught
    despite everything else (amount, currency, description, window)
    matching exactly. The user's own call once this was traced: stop
    hashing by id at all, and go further - `ParseRule` doesn't cache a
    Firefly account id anywhere anymore (see its own KDoc), so there's
    no stale/inconsistent cache left to reintroduce this. **Breaking
    change, accepted deliberately, same as the other two above**: a row
    written while id-preference was still in effect, for a draft that
    happened to have an id, hashes differently now than it did then.
    `CreatedTransactionsDb` is the usual
    plain-SQLite log (one row per successful `createTransaction()`
    call), queried by
    `findWithinWindow(hash, includeDescription, date, windowMinutes): String?`
    centered on the new transaction's own date, not "now", so two apps
    notifying about the same payment seconds apart land in the same
    window regardless of which one gets processed first - returns the
    matched row's Firefly id (most recent match if more than one,
    `ORDER BY date_millis DESC`), not just whether one exists, so the
    caller can offer "this is already created, link it" as a real
    answer (see `MessagesFragment` below) instead of only "create
    anyway"/"don't".
    **Fixed a real bug, caught by the user asking directly "what
    happens if I untoggle 'Also compare description'?"**: with a single
    hash column, the answer was "every row already written instantly
    stops matching anything" - a SHA-256 digest has no partial/fuzzy
    comparison, so changing *any* input (here, whether description was
    folded in) changes the whole digest, for every row, immediately,
    regardless of whether that row's own description actually differs
    from anything. Fix (the user's own call, not a hedge): the table now
    always stores *both* hash variants
    (`hash_with_description`/`hash_without_description`) on every write,
    computed unconditionally via two `transactionDedupHash` calls
    (`true`/`false`) regardless of the current setting - `recordCreated`
    takes both, `findWithinWindow` picks which column to compare against
    based on the *lookup's* `includeDescription` flag. A lookup under
    either setting can now match a row written under the other. Rows
    written before this fix only have one variant (migrated in place to
    `hash_with_description`, the longstanding default setting - see
    `CreatedTransactionsDb.onUpgrade`'s KDoc) - `hash_without_description`
    stays `NULL` for those, an honest "unknown" rather than a
    recomputed-from-nothing guess (there's no way to get the original
    description text back out of a one-way hash). `DB_VERSION` 1->2,
    additive `ALTER TABLE ... ADD COLUMN` - deliberately *not*
    `RENAME COLUMN` for the pre-existing `hash` column even though it's
    now semantically "with description", since SQLite only gained
    `RENAME COLUMN` in 3.25, newer than what ships on this app's minSdk
    26 devices; `COL_HASH_WITH_DESCRIPTION`'s Kotlin name maps to the
    old physical `hash` column instead.
  - `RemoteTransactionCandidate`/`remoteCandidateMatches`
    (`TransactionDedupTest`) - the *remote* half of dedup, the user's
    own design: the local check above only ever catches a transaction
    *this app* created, never one a second device (or a different app's
    direct Firefly integration) posted for the same real payment.
    Needed as its own function rather than just calling
    `transactionDedupHash` on `candidate` directly for two reasons:
    `candidate` isn't a `TransactionDraft` (different shape - a
    `groupId`, no notes/category/tags/foreignAmount - see
    `network.FireflyTransactionCandidate`, which `MessagesFragment`
    maps into this plain, `network`-free form), and
    `transactionDedupHash` deliberately doesn't cover date at all (the
    window check here is its own explicit thing, same reason
    `CreatedTransactionsDb.findWithinWindow` does its own SQL `BETWEEN`
    rather than folding date into the hash).
    `remoteCandidateMatches(draft, candidate, includeDescription,
    windowMinutes)` reshapes `candidate` into a throwaway
    `TransactionDraft` and compares `transactionDedupHash` output
    directly rather than re-deriving the same amount/currency/
    description/account comparison a second way - this is also why
    making `transactionDedupHash` itself kind-aware (above) was enough
    on its own to fix a real miss the user caught, with nothing extra
    needed here: whichever side doesn't matter for `draft.kind` never
    enters the comparison, however it's filled in on either side. The
    one remaining wrinkle: Firefly assigns every account (even a
    counterparty this app only ever captured by name) a real id, so the
    throwaway draft only keeps the candidate's id for a side `draft`
    *also* has one for - otherwise it's compared by name instead, same
    as `draft` itself is, so a real counterparty match isn't rejected
    just because this app never had an id for it.
    **Fixed a third real bug, a foreign-currency false negative the
    user caught on a HKD Amex notification**: when `draft` has
    `foreignAmount`/`foreignCurrencyCode` set, its own `amount`/
    `currencyCode` are only this app's *own* approximate conversion
    (`applyApproxConversion`, a Frankfurter-fetched exchange rate this
    app picked independently) - not a value Firefly's own stored amount
    is likely to match exactly. `remoteCandidateMatches` now compares
    the foreign amount/currency directly in that case instead (both
    sides' one precise, bank-reported value), and builds the throwaway
    draft with `draft`'s own (already-validated-identical) amount/
    currency rather than `candidate`'s, so `transactionDedupHash`'s own
    unconditional amount/currency contribution can't independently
    re-reject what the foreign-aware comparison already confirmed. Same
    reasoning applies to `network.dedupSearchQuery` (see below) - the
    remote search itself now queries by `foreign_amount_is`/
    `foreign_currency_is` instead of `amount_is`/`currency_is` whenever
    both are known, for the same "approx conversion isn't a reliable
    match key" reason.
  - `RuleEngine.match(message)` (`RuleEngineTest`) - the
    first-rule-in-order-that-matches algorithm described above. Takes
    an optional `currencySymbols: Map<String,String>` (symbol -> code,
    from `CurrenciesDb.symbolToCodeMap()`) and runs captured
    `currency`/`foreignCurrency` values through
    `normalizeCurrency`/`CurrencyNormalizationTest` before comparing
    against `homeCurrency` - banks print symbols ("€50.00") far more
    often than ISO codes, and without this every symbol-only
    notification would look "foreign" even when it's actually the
    home currency. `buildDraft`'s `date` defaults to
    `Instant.ofEpochMilli(message.timestampMillis)`; when the pattern
    captured `Groups.DATE` *and* `rule.dateFormat` is non-blank, it
    tries `parser.parseDate(captured, rule.dateFormat)` instead - a
    successful parse replaces the date outright, a failed one (typo'd
    format, or the text just doesn't match it) flags
    `DraftIssue.DATE_UNPARSABLE` and keeps the notification's own time,
    same "flag it, don't guess, don't block the match" shape
    `LOCAL_AMOUNT_MISSING` already uses.
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
    would reference them are optional. A non-blank `dateFormat` is
    checked against `DateTimeFormatter.ofPattern` - catches a typo'd
    format at save time rather than only as a `DATE_UNPARSABLE`
    `DraftIssue` every single time the rule matches afterward.
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
    instance and not just got a 200 from something else on that URL),
    `transactionUrl` (the *web UI* page for a created transaction -
    `{baseUrl}/transactions/show/{id}`, a different URL shape from
    every other helper here, which all build `/api/v1/...` paths - the
    route itself was checked against Firefly's current `routes/web.php`
    on GitHub, not assumed; see `MessagesFragment`'s "Open Transaction"
    button below). `dedupSearchDateRange(centerDate, windowMinutes)` -
    the `date_after`/`date_before` values (plain `yyyy-MM-dd`) that
    generously cover a ±window around a transaction's date, for
    `dedupSearchQuery` below; the precise minute-level check happens
    client-side afterwards (`rules.remoteCandidateMatches`), this just
    avoids pulling unrelated history over the network.
    `dedupSearchQuery(amount, currencyCode, foreignAmount,
    foreignCurrencyCode, centerDate, windowMinutes, sourceName,
    destinationName, description, includeDescription)` -
    builds the query string for `FireflyApiClient.
    searchTransactionsByQuery` (`FireflyUrlTest`): Firefly's own
    operator-trigger search syntax (`field:value`/`field:"quoted
    value"`, built on the `gdbots/query-parser` composer dependency -
    confirmed both the operator names
    (`amount_is`/`currency_is`/`foreign_amount_is`/`foreign_currency_is`/
    `date_after`/`date_before`/`source_account_is`/
    `destination_account_is`/`description_is`)
    against `OperatorQuerySearch`'s current source and the quoting
    syntax against `gdbots/query-parser`'s own docs, not guessed).
    Deliberately scoped by *name*, not a Firefly account id - see
    `FireflyApiClient.searchTransactionsByQuery`'s KDoc below for why
    that's a deliberate fix, not the original design. Null (skip the
    remote check entirely) when the amount is unresolved or neither
    account side has a name to search on - searching on amount+date
    alone could coincidentally match an unrelated transaction.
    When `foreignAmount`/`foreignCurrencyCode` are both known,
    `foreign_amount_is`/`foreign_currency_is` are used *instead of*
    `amount_is`/`currency_is` - a real false negative the user caught on
    a foreign-currency (HKD) notification, same "approx conversion isn't
    a reliable match key" reasoning as `rules.remoteCandidateMatches`
    above; see its KDoc for the full rationale, shared between both
    sides of this search.
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
    `searchTransactionsByQuery(query)` - `GET
    /v1/search/transactions?query=...` (the free-text operator-search
    endpoint, `query` built by `dedupSearchQuery` above and
    URL-encoded), paginated the same way `listAssetAccounts` is.
    **Replaced an earlier account-id-scoped version** (`GET
    /v1/accounts/{id}/transactions?start=...&end=...`) that required a
    Firefly account id on at least one side of the draft to even run -
    a real on-device bug the user caught directly: this app frequently
    only has an account *name* (a rule's source/destination typed as
    free text rather than picked from `listAssetAccounts`), and posting
    a transaction by name alone is already fully supported
    (`FireflyTransactionRequest`), so the remote dedup check silently
    never ran at all for exactly the rules most likely to need it,
    while creation itself worked fine. The free-text endpoint's query
    syntax was deliberately avoided the first time this was built
    (not verified, judged not worth the risk) - properly verified this
    time against `OperatorQuerySearch`'s current source (the operator
    names) and `gdbots/query-parser`'s own docs (the quoting syntax),
    see `dedupSearchQuery`'s KDoc. Response parsing
    (`FireflyJson.parseTransactionList`) is unchanged - both endpoints
    return the identical shape (same `TransactionGroupTransformer`
    server-side) - flattens every returned group's splits into one
    list, each keeping its own group's id - this app's own transactions
    never split, but a real one in Firefly might; field names (`date`
    as Carbon's `toAtomString()`, `amount`, `currency_code`,
    `description`,
    `source_id`/`source_name`/`destination_id`/`destination_name`)
    confirmed against `TransactionGroupTransformer`'s current source.
    Used by `MessagesFragment`'s remote duplicate check below once the
    local dedup log already came up empty.
    `getTransaction(id)` - `GET /v1/transactions/{id}`, the full fields
    of one already-known transaction (confirmed `ShowController::show()`
    wraps a single Fractal `Item`, not a `Collection`, against its
    current source - `data` is one object, not an array, hence a
    separate `FireflyJson.parseSingleTransaction` rather than reusing
    `parseTransactionList`). The user's own request: a *locally* found
    duplicate (`CreatedTransactionsDb.findWithinWindow` only ever
    returns a bare id, no fields at all) used to show nothing but that
    id in the duplicate-confirm dialog, unlike a remote match (which
    already carries full fields from the search response) - this fetches
    the missing fields on demand so the dialog always shows the same
    level of detail (date/amount/description/accounts) regardless of
    which path found the match. `null` on success with no usable data
    (malformed/empty response), same "missing data isn't a failed call"
    distinction `searchTransactionsByQuery` already draws via
    `DuplicateCheckResult`. **Fixed a real "stale cache" bug, the user's
    own catch**: a `404` is now also mapped to this same `null`-on-success
    shape, confirmed against `ShowController::show()`'s current source
    (Laravel route-model-binding on a nonexistent id never reaches the
    method body - `ModelNotFoundException` -> 404 - and an
    existing-but-inaccessible one hits an explicit
    `throw new NotFoundHttpException()`, both unambiguous "definitely
    doesn't exist"). This is what lets `posting.findDuplicate` (below)
    tell a confirmed-gone cached id apart from a real error.
    `FireflyTransactionCandidate`/`RemoteTransactionCandidate` also now
    carry `notes`/`category`/`tags` (parsed from the split's own
    `notes`/`category_name`/`tags` fields, confirmed against
    `TransactionGroupTransformer`'s current source, not assumed) -
    previously missing entirely, so any dialog built from a verified
    candidate could only ever show a subset of what `buildMatchedBody`
    (the rule-derived version) showed, forcing a choice between "real
    data, incomplete" and "complete, possibly wrong". The user's own
    catch: a transaction's description/notes/category/tags can be
    edited by hand directly in Firefly after this app posted it, and the
    rule-derived draft has no way to know that happened.
  - `withNetworkRetry` (`network/NetworkRetry.kt`) - up to 3 attempts,
    exponential backoff (500ms/1000ms), retrying only on `IOException`
    (a genuine network-level failure - no connection, timeout, DNS) and
    never on an HTTP error response (4xx/5xx reached the server and got
    an answer, retrying blindly won't change that). `internal suspend
    inline fun` - has to be `inline` so a `return@withContext` inside the
    retried block (the existing "bail out of the whole paginated fetch on
    a non-2xx page" pattern already used throughout this file) still
    compiles; `internal` because a public inline function can't access a
    non-public top-level property (`RETRY_BASE_DELAY_MILLIS`), and there
    was no reason for either to be public anyway. Wraps every read-only
    call in this class (`testConnection`/`listAssetAccounts`/
    `listCategories`/`searchTransactionsByQuery`/`getTransaction`) and
    `ExchangeRateClient.fetchRate` - a repeated `GET` is always safe.
    **Deliberately does not wrap `createTransaction`** - confirmed against
    Firefly's `StoreController::store()` source: there's no
    `Idempotency-Key` mechanism, only a server-side content-based
    duplicate check (`DuplicateTransactionException`) that's a heuristic
    on Firefly's own terms, not a transport-level guarantee. The failure
    mode that matters is a timeout *after* the server already processed
    the write - retrying that risks silently creating a second real
    transaction, exactly what this app's dedup machinery exists to
    prevent. The user's own explicit call after asking "is this
    idempotent?" rather than assuming either way.
    `listCategories()` - `GET /v1/categories` (`ShowController@index`,
    confirmed against Firefly's current `routes/api.php`, not assumed),
    same paginated shape and `FireflyJson.parseCategoryList` parsing as
    `listAssetAccounts`/`parseAccountList` (`CategoryTransformer`'s
    current source confirms the `attributes.name` field, not guessed).
    `ui.CreateTransactionFragment`'s category picker source - see the
    `ui` section below for why this exists (a free-typed category can
    be posted misspelled with nothing flagging it, unlike the
    asset-account side which already had a picker).
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
    themselves. Deliberately excludes `id`: a row id is this device's
    internal identity, meaningless elsewhere. (Used to also exclude
    `sourceAccountId`/`destinationAccountId` - moot now, `ParseRule`
    doesn't carry a Firefly account id at all anymore, see its own
    KDoc.)
  - `FullBackupJson.kt` (`FullBackupData`, `fullBackupToJson`/
    `fullBackupFromJson`, `FullBackupJsonTest`) + `BackupManager`
    (Android-dependent orchestration, not unit tested, same reason
    `RulesDb` isn't) - the new "Backup & restore" Settings row
    (`BackupFragment`), covering monitored apps, currencies, rules,
    message history, *and* the `CreatedTransactionsDb` dedup log in one
    file. Has its own per-rule shape (`toBackupJson`/
    `toBackupRuleOrNull`) now, *not* a reuse of `RulesJson`'s - the two
    formats are for genuinely different jobs and shouldn't share one
    shape just because a rule is a rule (and, as of the account-id
    removal above, happen to be textually identical now - kept separate
    anyway in case a future field needs the per-format split again). A
    full backup is a same-device safety net, unlike `RulesJson`'s
    cross-device/cross-person sharing format - it doesn't even touch
    the Firefly connection's base URL, so restoring onto a materially
    different instance was never really its job - and `RulesDb.add`
    always assigns a fresh row id on restore regardless of what's in
    the file, so `id` still has nothing to round-trip for either way.
    **Fixed a real data-loss bug, caught directly by the user testing a
    just-made backup**: an earlier version of this file dropped
    `messages`' `fireflyTransactionId` on the "Firefly-instance-specific
    derived id, excluded even here" theory, and a restored message
    silently lost its "#1234" link even though the transaction still
    existed in Firefly. `fullBackupToJson`/`fullBackupFromJson` carry
    `fireflyTransactionId` straight through now. `createdTransactions`
    (`rules.CreatedTransactionRecord`, `CreatedTransactionsDb.loadAll`/
    `clearAll`) exists for the same underlying reason: without backing
    it up too, the dedup log would come back empty after a restore onto
    a fresh install, silently disabling duplicate detection for
    everything created before the backup. Deliberately excludes
    the Firefly connection (base URL/
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
    `onUpgrade` pattern that prompted this whole feature. Its "Create
    Backup…" button label (renamed from "Export backup…") distinguishes
    it from the rules-only shortcuts below it on the same screen.
    `BackupFragment` also hosts a second "Export Rules…"/"Import
    Rules…" entry point onto `RulesListFragment`'s own export/import -
    literally the same `rulesToJson`/`rulesFromJson`/`RulesDb` calls,
    not a reimplementation, just reachable from here too so a user
    looking for "Backup & restore" doesn't have to already know rules
    export/import lives on a different screen. No `reload()` call after
    import here (unlike `RulesListFragment`'s) - this screen has no
    rule list to refresh.
  - Both read/write via Storage Access Framework
    (`ActivityResultContracts.CreateDocument`/`OpenDocument`,
    `application/json`) - no storage permission needed, no app-private
    file path to explain to the user, the system file picker is the
    whole UI for "where does this go"/"where do I get this from". Every
    completion toast (backup export/restore, rules export/import, both
    screens) includes the file name when it's known
    (`ui.displayNameOf(context, uri)`, `OpenableColumns.DISPLAY_NAME`
    via `ContentResolver.query`) - SAF deliberately doesn't expose a
    real filesystem *path* for a `content://` Uri, only a display name,
    and even that isn't guaranteed (a `DocumentsProvider` can simply not
    report one); each toast string has a `_named` variant
    (`backup_export_success_named`, etc.) used only when a name actually
    came back, falling back to the plain no-name message otherwise -
    "if we know it", not a guess.
- `posting` - the shared machinery behind actually posting a
  transaction, used by both the manual "Создать" button
  (`ui.MessagesFragment`) and automatic creation (below); depends on
  both `rules` and `network`, same layering `ui.MessagesFragment`
  itself already had - this package exists so that dependency doesn't
  have to live in `ui` alone anymore:
  - `DuplicateChecker.kt` (`DuplicateCheckResult`, `checkForDuplicate`,
    `findDuplicate`, `FireflyTransactionCandidate.toRemoteCandidate`) -
    the local-then-remote dedup lookup, pulled out of
    `ui.MessagesFragment` (where it used to live as private members)
    specifically so `AutoCreateWorker` could call the exact same logic
    a manual create already goes through, rather than re-deriving the
    same comparison a second way and risking the two paths drifting
    apart over time. Pure extraction, no behavior change -
    `MessagesFragment` now imports these as top-level functions.
    **Fixed a real "desync" bug, the user's own catch and report**: a
    local-log (`CreatedTransactionsDb`) hit used to be trusted blindly -
    returned as `Found` straight from the cached id, with nothing ever
    confirming Firefly's side still actually has it. Deleting a
    transaction by hand in Firefly (e.g. because it was wrong) left this
    app's own log none the wiser - "Check if created"/the preemptive
    create-flow check kept presenting a dead reference ("Transaction #X
    matches", with "Open" pointing nowhere). `findDuplicate` now runs a
    live check before trusting a local hit: still there -> `Found` (with
    real fields from that same call, skipping the separate fetch
    `MessagesFragment` used to need); confirmed gone -> the cached id is
    stale, falls through to the remote search exactly as if the local
    log had found nothing; couldn't tell (network/server error) ->
    `SearchFailed` immediately.
    `LinkedTransactionCheck`/`verifyLinkedTransaction(prefs, id)` - **the
    one shared method the user explicitly asked for** after catching
    this same "does a known id still exist" check implemented three
    separate times that didn't even agree with each other
    (`findDuplicate`'s own inline version above, plus two independent
    copies in `ui.MessagesFragment` - `verifyLinkedThenShow`'s inline
    check and a `staleLinkedIdOrNull` helper, the latter of which threw
    away a found candidate's fields entirely even though it had just
    fetched them). `findDuplicate`'s local-hit check above,
    `MessagesFragment.verifyLinkedThenShow` (tapping an already-linked
    message), `MessagesFragment.checkIfCreated` (checking a known linked
    id before ever searching), and `MessagesFragment.fetchCandidateInfo`
    (fetching a known id's fields for the duplicate-confirm dialog) all
    go through this one function now - a 404 means the same thing, a
    found candidate carries the same fields, everywhere. Returns
    `Found(candidate)`/`Gone`/`CheckFailed(message)` - `Gone` is only
    ever a confirmed `404` (verified against `ShowController::show()`'s
    source, not guessed), never collapsed into a generic failure.
    `DuplicateCheckResult.SearchFailed` changed meaning for *every*
    caller, not just `checkIfCreated` - the user's own explicit
    instruction: it used to be treated the same as `None` everywhere
    else ("we don't know, so assume no duplicate and proceed"), but now
    that a `SearchFailed` can mean "a local hit exists but couldn't be
    verified", that's backwards - a duplicate is *more* likely to be
    real, not less, so every caller now blocks and surfaces the error
    instead: `MessagesFragment.checkThenOpenCreateOrShowFound`/
    `startCreate` show the existing `check_search_failed_title` dialog
    instead of opening the review screen; `CreateTransactionFragment.
    onCreateClicked` shows the same dialog and re-enables "Create
    transaction" instead of posting; `AutoCreateWorker` reuses its
    existing `Result.retry()`/`MAX_ATTEMPTS`/`notifyFailed` machinery
    (already built for a failed `createTransaction` call) rather than
    silently posting on an unverified dedup check.
  - `AutoCreateScheduler`/`AutoCreateWorker`/`AutoCreateNotifications` -
    automatic transaction creation, gated by `FireflyPrefs.
    isAutoCreateEnabled()` (a long-standing setting that previously had
    no code reading it at all - see TODO.md). Design decided with the
    user directly before building: a likely duplicate is *skipped*, not
    posted and not auto-resolved (no human in the loop here to make
    that call, unlike the manual path's "create anyway" confirmation),
    surfaced as a system notification so the user can still act on it
    by hand; a genuine success also gets a system notification, since
    there's no in-app dialog to show it in when the app isn't even
    open.
    `AutoCreateScheduler.scheduleIfEnabled(context, message)` is the one
    entry point - called right after a genuinely new message is
    recorded, from both `notifications.BankNotificationListenerService`
    (a real notification) and `ui.AddTestMessageFragment` (a manually-
    injected test message, "exactly like a real notification would" per
    its own KDoc, deliberately includes this too).
    **The user's own explicit requirement, and the reason this needed a
    real scheduler/worker rather than just launching a coroutine per
    message**: two notifications can arrive essentially simultaneously,
    and auto-create must never process them in parallel - a race
    between two concurrent duplicate checks could let both
    independently decide "not a duplicate" and both post the same real
    payment, exactly the failure mode dedup exists to prevent in the
    first place. Every message is enqueued into one single WorkManager
    unique work chain (`WorkManager.enqueueUniqueWork`,
    `ExistingWorkPolicy.APPEND`, `AutoCreateScheduler.UNIQUE_WORK_NAME`)
    regardless of origin - `APPEND` makes each new request a dependency
    of whatever's already queued/running in that chain, so WorkManager
    itself guarantees strict one-at-a-time, in-arrival-order execution
    with no manual locking needed on this app's side, and it survives
    process death (WorkManager persists its own queue), so a burst that
    arrives right before the app is killed still finishes in order once
    it restarts.
    `AutoCreateWorker` (`CoroutineWorker`) re-checks `isAutoCreateEnabled`/
    `isConfigured` itself (not just trusting the scheduler's check at
    enqueue time, since the setting or connection could have changed
    while a message sat behind an earlier one in the chain), re-parses
    the message fresh via `RuleEngine.match` (input data only carries
    the plain packageName/appLabel/title/text/timestampMillis fields,
    `WorkManager.Data` only holds primitives - the `CapturedMessage` is
    reconstructed from those), resolves `DraftIssue.LOCAL_AMOUNT_MISSING`
    the same way `MessagesFragment.resolveAmountQuietly` does, then
    calls `posting.checkForDuplicate`. A failed Firefly call retries
    (`Result.retry()`, exponential backoff) up to `MAX_ATTEMPTS` (5) -
    the actual "WorkManager for retrying failed API calls" use
    AGENTS.md's own stack note was written for. Capped rather than left
    to WorkManager's default indefinite retry: since every message
    shares one `APPEND` chain, a stuck-retrying item would otherwise
    block every later notification's auto-create attempt forever (a
    dead/revoked token would silently stall the entire feature, not
    just one message) - exceeding the cap gives up (`Result.failure()`)
    instead, unblocking the chain for whatever's queued behind it, and
    notifies the user once via `AutoCreateNotifications.notifyFailed`
    rather than retrying forever in silence. A `Constraints.
    setRequiredNetworkType(NetworkType.CONNECTED)` constraint means a
    request sitting in the chain without connectivity just waits
    instead of burning through those attempts on a guaranteed-to-fail
    call - once connectivity returns, the whole backlog drains in
    order.
    `AutoCreateNotifications` posts to one `NotificationChannel`
    (`IMPORTANCE_DEFAULT` - not `_HIGH`, no heads-up warranted for a
    background bookkeeping result; not `_LOW` either, a skipped-
    duplicate or a failure deserves more than a silent badge), created
    lazily on first use. The whole-notification tap always opens the app
    (`MainActivity`) on every outcome - the user's own call, after
    trying tap-opens-Firefly first and preferring the app as the
    consistent, predictable destination instead. The success and
    skipped-duplicate notifications separately also carry an explicit
    "Open transaction" action button pointing straight at Firefly's own
    page for the relevant transaction - the created one for a success,
    the *existing* one the skip was against for a skipped duplicate
    (confirming whether it's really the same payment means looking at
    that transaction, not the app's own message list) - so getting
    there doesn't need going through the app first, without changing
    what a plain tap on the body does.
    **Fixed a real "can't tell what happened without opening Firefly"
    gap, caught directly on-device**: two notifications from the same
    app (Google Wallet), worded differently enough that `posting.
    DuplicateChecker`'s hash didn't match them to each other, each
    silently auto-created its own Firefly transaction for what was the
    same real payment - the notification text alone (just the app name
    and a bare `#id`) gave no way to tell *why*, short of comparing both
    transactions by hand in Firefly's own UI. `auto_create_success_text`/
    `auto_create_skipped_text` now include the parsed amount/currency/
    description (`"%appLabel% · %amount% %currency% · \"%description%\"
    → #%id%"`, one compact line, the user's own format pick) - for
    `notifySkippedDuplicate` specifically, the *incoming* (skipped)
    draft's own fields, not the existing transaction's, since that's the
    data this worker actually parsed and is what needs comparing against
    what "Open transaction" shows. Requires `POST_NOTIFICATIONS` on
    API 33+ (declared in the manifest; `posting.
    isNotificationPermissionGranted(context)` is the one shared check,
    always true below API 33 where the permission doesn't exist) - a
    declined/missing permission is caught as a `SecurityException`
    around `NotificationManagerCompat.notify` and silently skipped, same
    "doesn't block the feature from working, just from being visible"
    shape `BankNotificationListenerService`'s own notification-listener
    access already has. Requested from three places, not just one -
    **fixed a real "the user has no idea this is silently broken" gap**:
    requesting only when `FireflySettingsFragment`'s switch is actually
    toggled on missed the case where it was already on (enabled in a
    build before this permission check existed, or the permission later
    revoked by hand in system settings) - that user would never see the
    permission dialog again and would never know auto-create's own
    notifications had gone silent. Now requested (a) from
    `FireflySettingsFragment` when the switch is toggled on, (b) from
    that same screen's `onViewCreated` if it's already on when the
    screen opens, and (c) from `MainActivity.onCreate`, every app start,
    for a user who never opens Firefly III settings again at all.
    **Needs real-device verification** (not yet done, see TODO.md):
    nothing here can be exercised by a JVM unit test (WorkManager,
    system notifications, and the notification-listener path itself all
    need a real device/emulator) - in particular, confirming two
    notifications posted back-to-back are still handled strictly one at
    a time, which is the entire reason this was built as a WorkManager
    chain instead of a plain per-message coroutine launch.
  - `RuleMatching.kt` - `capturedMessageInputData`/`capturedMessageFrom`/
    `matchRule`/`resolveAmountQuietly`, top-level functions extracted
    from `AutoCreateWorker` so `ReviewNotificationWorker` (below) could
    call the exact same rule-match + amount-resolution logic rather than
    duplicating it a second way. Pure extraction, no behavior change -
    `AutoCreateWorker` now calls these instead of its own private
    copies.
  - `ReviewNotificationScheduler`/`ReviewNotificationWorker`/
    `ReviewNotifications` - the counterpart to `AutoCreateScheduler`/
    `AutoCreateWorker`/`AutoCreateNotifications` for when auto-create is
    *off*. Raised by the user: a rule match used to surface nowhere
    outside the app itself when auto-create was disabled - the user had
    to open Messages and tap in to notice it. `ReviewNotificationScheduler.
    scheduleIfNeeded(context, message)` is called from the exact same two
    places as `AutoCreateScheduler.scheduleIfEnabled`, right alongside
    it - exactly one of the two ever does anything for a given message,
    since they gate on opposite states of `isAutoCreateEnabled()`.
    `ReviewNotificationWorker` re-parses the message via `RuleMatching.
    matchRule`/`resolveAmountQuietly` (same as `AutoCreateWorker`), and
    on a match with a resolved amount, posts a system notification
    (`ReviewNotifications`, its own `review_match` channel, separate from
    `auto_create`'s so the two are independently mutable/configurable)
    with an app/amount/currency/description summary and a "Create
    transaction" action (and whole-notification tap) that deep-links
    into `ui.CreateTransactionActivity.intent(context, message)` - the
    same review/edit screen the manual flow uses, picking a category
    right from the notification's own flow rather than a bare "open the
    app and find it yourself" prompt. Deliberately does **not** run a
    duplicate check first and does **not** join `AutoCreateScheduler`'s
    unique `APPEND` work chain: there's no shared mutable state here (no
    Firefly `POST`, no dedup-log write) for two in-flight instances to
    race over, so suppressing a likely-duplicate match before notifying
    isn't worth the extra complexity - that judgment is left to the user
    once they open `CreateTransactionActivity`, which already runs its
    own duplicate check on tapping "Create transaction" there, so the
    worst case is one extra tap before finding out, not a silently
    wrong post.
- `notifications` - `BankNotificationListenerService`
  (`NotificationListenerService` subclass): for every posted
  notification, checks `MonitoredAppsPrefs`, pulls `EXTRA_TITLE`/
  `EXTRA_TEXT` from the notification extras, and if either is
  non-empty, resolves the app's current label via `AppLabelResolver`
  and calls `MessageStore.shared.add(...)` - a non-null (genuinely new,
  not a notification-level duplicate) result is handed straight to
  `posting.AutoCreateScheduler.scheduleIfEnabled` and `posting.
  ReviewNotificationScheduler.scheduleIfNeeded` right after.
  **Fixed a real duplicate-capture bug, caught directly on-device**:
  `onNotificationPosted` isn't only called for a genuinely new
  notification - the system also replays every notification still
  sitting unswiped in the shade whenever this listener (re)connects,
  which happens on its own whenever Android kills and restarts the
  service (no foreground service, see TODO.md). `add(...)` is now
  timestamped with `sbn.postTime` (the system's own record of when the
  notification was actually posted, unchanged across a replay) instead
  of `System.currentTimeMillis()` ("now", different every time) - an
  hour-old unswiped notification replayed on reconnect used to look
  brand new and get captured (and auto-created) a second time, well
  outside any dedup window; same `postTime` on both captures now
  collapses it correctly regardless of how much wall-clock time passed
  in between. This also makes `TransactionDraft.date`'s own default
  (`RuleEngine.buildDraft`, from `message.timestampMillis`) and
  `CreatedTransactionsDb`'s dedup-window check more correct as a side
  effect - both now reflect when the payment notification actually
  happened, not when this app's listener happened to (re)process it.
  Requires the user to grant notification-listener access manually in
  system settings - there is no runtime permission dialog for this,
  only `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`.
- `ui`:
  - `MainActivity` - home screen, hosts `MessagesFragment` plus a
    toolbar with a settings action - *unless* notification-listener
    access isn't granted yet (`ui.isNotificationListenerAccessGranted`),
    in which case `NotificationAccessConsentFragment` (see its own
    entry under `AppsFragment` below) takes that spot instead, on
    *every* launch this remains true, not just a true first run (no
    separate "has seen onboarding" flag - the permission's own granted
    state already is that signal). The user's own call, after a first
    version only surfaced this disclosure behind a banner in Settings ->
    Monitored apps: the app does nothing useful without this
    permission, so it belongs front and center on a fresh install, not
    something to stumble onto. `MainActivity.showMessages()` is how
    that fragment swaps itself out once dismissed/granted, when it's
    sitting here as the activity's root fragment rather than pushed on
    `SettingsActivity`'s own back stack (see
    `NotificationAccessConsentFragment`'s own KDoc for how it tells the
    two apart). `MessagesFragment` subscribes to
    `MessageStore.shared` while visible (`onStart`/`onStop`) and
    renders the current snapshot, newest first.
    `MessagesAdapter` shows "#<id>" (`messageTransactionId`, bold,
    `colorPrimary`) next to the received time once a row's
    `fireflyTransactionId` is set, `View.GONE` otherwise - the one
    place in the UI that surfaces the link without having to reopen the
    test-match dialog. Each row also has an overflow (⋮) `PopupMenu`
    now, same shape as `RulesListFragment`'s (see its own notes below
    for why that's the reference pattern) - "Check if created" and
    "Delete", the latter asking for confirmation (same strings/dialog
    shape as the multi-select delete below, just one message instead of
    a selected set). Hidden (`MessagesAdapter.setSelectionMode`) while a
    multi-select is in progress, since the two interaction modes would
    otherwise compete for the same tap.
    - "Check if created" (`MessagesFragment.checkIfCreated`) - an
      explicit, user-initiated search, distinct from the preemptive
      check `showTestMatch`/`startCreate` already run before posting.
      **Fixed a real "ignores what it already knows" bug, the user's own
      catch/report**: when `message` already has a linked id, that's a
      direct answer on its own - the old code searched by content
      anyway, which can genuinely disagree with the known-good id (the
      user's own example: editing a transaction's description/notes by
      hand in Firefly can make it stop matching this app's own
      hash/query), so it would find and offer a *different* transaction
      instead, ignoring the one it already knew was right. Now checks
      the linked id first via `posting.verifyLinkedTransaction` (see the
      `posting` section above): still there -> shown directly, no search
      at all; confirmed gone -> falls through to the search below,
      carrying the gone id so a replacement match says so explicitly
      (same `staleLinkedId` warning `verifyLinkedThenShow` uses); a
      check failure stops and shows the error immediately. Only once
      there's no linked id (or it's confirmed gone) does this re-parse
      the message fresh (`RuleEngine.match`, same as `showTestMatch`)
      and call `findDuplicate` directly - the same local-then-remote
      logic `checkForDuplicate` uses, but *not* gated by `FireflyPrefs.
      isDedupEnabled()`, since that toggle is about whether *creating*
      pauses for a dedup check, a different question from whether this
      separate "just tell me" action should run at all. Not found:
      `showSearchNotFoundDialog` shows the exact
      query string that was searched (`network.dedupSearchQuery` -
      useful for debugging on its own, prompted by a real session where
      the search's actual behavior needed to be inspected by hand) and
      offers to open the same search, pre-filled, in Firefly's own web
      UI (`network.searchUrl`, confirmed against `routes/web.php`'s
      `search.index`/`SearchController::index` reading a `search` GET
      parameter, not assumed). Each listed raw candidate's field dump
      (below) is labeled (`id=`/`date=`/`amount=`/`desc=`/`src=`/`dst=`)
      rather than bare positional values, the user's own fix after
      finding some fields unlabeled; the amount is also rounded to 2
      decimal places for display (`setScale`), not the raw scale
      Firefly's JSON happened to carry. **User's own call**: when
      exactly one raw candidate came back, a "Use anyway" button (between
      "Open search in Firefly" and Cancel, which stays last per the
      house convention) links the message to it directly, same as "Use
      Created" does elsewhere - one candidate makes the client-side
      rejection more likely a field-comparison quirk than a genuinely
      different transaction, so skip the round trip through Firefly's
      own web search. **Fixed a real native-AlertDialog quirk, caught
      directly from a screenshot**: adding "Use anyway" made this a
      3-button dialog, and native `setNegativeButton`/`setNeutralButton`/
      `setPositiveButton` slots stack vertically (each right-aligned)
      once the combined label text doesn't fit one row - the exact same
      problem `dialog_duplicate_confirm_3.xml` was built to avoid.
      `dialog_search_not_found.xml` replaces the native buttons with the
      same one-row `LinearLayout`+`layout_weight` pattern;
      `useAnywayButton` is `View.GONE` (not just text-less) when there's
      no single candidate, so the remaining two buttons fill the row via
      weight the same way any 2-button case does. Found: the same
      `showDuplicateConfirmDialog` the create flow uses, with
      `showCreateAnyway = false` - inflates `dialog_duplicate_confirm_3.xml`
      (three actions in one row) instead of `dialog_duplicate_confirm_4.xml`
      (the 2x2 grid, used when `showCreateAnyway` is true - see its own
      entry below) rather than toggling a fourth button's visibility, and
      swaps in check-specific title/message strings, since "post one
      anyway" isn't a meaningful option for a pure lookup.
      **Fixed a real "the app is lying to me" bug**: `DuplicateCheckResult`
      used to collapse "genuinely zero matches" and "the remote search's
      HTTP call itself failed" into the same `None` case (deliberate for
      the create-flow callers, where a failed best-effort check should
      just fall through to posting normally either way) - but
      `checkIfCreated` reused that same collapsed result, so an API
      failure silently showed "Not found in Firefly" too, indistinguishable
      from a real miss. Caught directly by the user: pasting the exact
      query this app had just searched with into Firefly's own web
      search box found the transaction immediately, proving the search
      itself wasn't the problem. A new `SearchFailed(message)` case
      carries the real HTTP failure reason - `checkIfCreated` shows that
      verbatim (`check_search_failed_title`) instead of presenting it as
      a non-match; the create-flow callers originally treated `SearchFailed`
      exactly like `None` (same best-effort fallback) - since changed,
      see `DuplicateChecker.kt`'s entry above for why.
      Follow-up test showed it wasn't actually `SearchFailed` though -
      the search succeeded and returned exactly one raw candidate, which
      `remoteCandidateMatches` then rejected. So `DuplicateCheckResult.
      None` carries the actual `rawCandidates`
      (`List<RemoteTransactionCandidate>`, not just a count) returned
      before filtering, and the not-found dialog lists each one's real
      field values (date, amount, currency, description, source/
      destination id-or-name) right next to the query that was searched
      with - seeing the two side by side is what actually shows *why* a
      match failed (case, whitespace, a stale date, ...) instead of
      needing another debug round-trip. Root cause of *this* particular
      rejection turned out to be the kind-aware `transactionDedupHash`
      bug documented above (the user's own `WITHDRAWAL` rule, a real
      destination name sitting on Firefly's side that this hash wasn't
      supposed to care about at all) - fixed there, not here.
      Also simplified around the same time, per the user's own
      observation: `dedupSearchQuery` used to always emit both
      `date_after`/`date_before`, even when they were the identical
      date (the common case, default 5-minute window never crosses
      midnight) - now emits a single `date_on` instead in that case,
      falling back to the `date_after`/`date_before` pair only when the
      window actually spans two different days.
      **Manual-only retry without description, the user's own call**:
      if `FireflyPrefs.isDedupIncludeDescription()` is on and the first
      `findDuplicate` call comes back `None`, `checkIfCreated` retries
      once more with `includeDescription = false` before giving up. A
      `Found`/`FoundMultiple` on the retry is shown with
      `descriptionMismatch = true`, which prepends an explicit warning
      (`check_found_description_mismatch_warning`) to the duplicate-
      confirm dialog - deliberately *not* silently treated as "good
      enough", since a weaker match still needs a human looking at it
      to judge whether it's really the same payment. The create-flow
      callers (`checkForDuplicate`, feeding `startCreate`/
      `showTestMatch`) deliberately do *not* retry this way - posting on
      top of a transaction that only matched once description was
      dropped, with no human in the loop to catch a wrong guess, would
      risk linking the wrong transaction; a manual lookup always has a
      human looking at the result either way, so showing the weaker
      match (with a clear warning) is the right trade-off only there.
      **Always show found-transaction info, the user's own request** -
      "как ты сделал для ошибки, когда найденная не совпадает по
      счету": the not-found dialog's per-candidate field dump (above)
      already showed real field values for a *rejected* candidate;
      `showDuplicateConfirmDialog` (used by all three callers, not just
      `checkIfCreated`) now always appends the same level of detail
      (date/amount(+foreign)/description/source/destination,
      `buildFoundTransactionInfo`, labeled with `showTestMatch`'s own
      `test_match_label_*` strings rather than a new, denser format) for
      the transaction it's actually offering as *the* match - a bare
      `#<id>` on its own gave the user no way to independently judge the
      match without leaving the app.
      `DuplicateCheckResult.Found`/`FoundMultiple` now carry an optional
      `candidate: RemoteTransactionCandidate?` - already populated for
      free for a remote-search hit, `null` for a local-log hit (which
      only ever has a bare id). `showDuplicateConfirmDialog` is now
      `suspend`: when `knownCandidate` is null it calls the new
      `FireflyApiClient.getTransaction(id)` itself (see the `network`
      section above) to fetch the missing fields - a locally-recorded
      duplicate gets the same display as a remote one, just one extra
      API round-trip first. Best-effort: a failed fetch (or a genuinely
      missing transaction) just shows the dialog without the info block,
      same as before this existed, rather than failing to show anything.
      Long-press starts
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
    runs `RuleEngine.match` against the tapped message - a *fresh* match
    (nothing linked yet, Firefly configured) runs the same preemptive
    `checkForDuplicate` every other create path does *before* deciding
    what to show: found -> the duplicate-confirm dialog; not found (or
    the amount couldn't be resolved to check with, or the remote check
    failed) -> `CreateTransactionActivity` opens directly (see its own
    entry below). **Fixed a real "the screen doesn't even try" gap,
    caught directly on-device right after `CreateTransactionActivity`
    shipped**: the first version of this just opened the review screen
    unconditionally for a fresh match, skipping the check entirely - a
    transaction that genuinely already existed (confirmed separately by
    "Check if created" finding it) went unnoticed until the user tapped
    "Create transaction" on the review screen itself. Everything else
    (no match, an already-linked message, or Firefly not configured)
    still shows the result (source/destination account, amounts/
    currencies, rendered description/notes, issues) in a dialog - "test
    match". The dialog's
    *title* is the outcome itself: the matched rule's own label, or
    "No match" - not a generic "Test match" every time regardless of
    what happened, which made the matched-vs-not distinction easy to
    miss at a glance. The body is a `SpannableStringBuilder` with each
    field's label (`Source:`, `Amount:`, ...) in bold - plain
    `getString(R.string.x, value)` concatenation read as one
    same-weight wall of text; this needed splitting the old combined
    "Label: %1$s" string resources into label-only strings so code can
    bold just the label span. `Date:` (`formatDraftDate`, system
    date+time format, same as `MessagesAdapter`) shows for every match,
    not only when `ParseRule.dateFormat` overrides it - there was no
    date field in this dialog at all before that feature existed, so a
    wrong override would've been invisible until a transaction actually
    posted with it. When the draft has `LOCAL_AMOUNT_MISSING`
    (foreign-currency message, no home-currency amount),
    `resolveApproxConversion` (suspend, not fire-and-forget - see below
    for why) fetches a real rate from `network.ExchangeRateClient` for
    the message's own date and swaps the already-shown dialog's message
    in place (`AlertDialog.setMessage`, works after `.show()`) once it
    resolves - a computed amount + the approx tag on success
    (`rules.applyApproxConversion`), or the original body plus an
    explicit "lookup failed: <reason>" line on failure.

    The dialog's buttons depend on whether *this message*
    (`message.fireflyTransactionId`, not the draft) already has a
    linked transaction - only added when the match produced a draft
    *and* `FireflyPrefs.isConfigured()`, not shown at all for a no-match
    or an unconfigured connection:
    - Already linked: **fixed a real "pretends everything is fine" bug,
      the user's own catch/report (screenshotted)** - reaching this
      dialog used to trust `message.fireflyTransactionId` blindly, with
      zero verification, so a transaction deleted by hand in Firefly
      left "Open Transaction" pointing at a dead link with nothing on
      screen explaining why. `showTestMatch` now routes the already-
      linked case through `verifyLinkedThenShow` first, which checks via
      the one shared `posting.verifyLinkedTransaction` (`LinkedTransactionCheck`
      - `Found(candidate)`/`Gone`/`CheckFailed`, see the `posting` section
      below): still there -> this dialog, now built from the live
      `candidate`'s own real fields via `buildCandidateBody` (see its own
      note just below) instead of the rule-re-parsed draft; confirmed
      gone -> control falls through to `checkThenOpenCreateOrShowFound`
      (the same fresh-match search-then-show/create flow below), now
      carrying a `staleLinkedId` parameter so a replacement match (if
      search finds one) shows `check_linked_transaction_gone_warning`
      ("⚠ Linked transaction #X no longer exists in Firefly") instead of
      presenting the new match as if nothing had ever been linked;
      nothing found falls through to `CreateTransactionActivity`, same
      as any other fresh match. **Fixed a real "changes state before the
      user agreed to anything" bug, the user's own catch, same day**: the
      stale link used to be cleared (`MessageStore.
      setTransactionId(message, null)`) the instant the `404` was
      confirmed, before any dialog even showed - a plain Cancel on
      whatever came next would've silently unlinked the message with no
      action behind it. Nothing clears it proactively now; the link only
      ever changes as a side effect of the user's own choice - "Use
      Created" overwrites it with the newly found id, a successful
      create (fresh or "Create anyway") overwrites it with the new
      transaction's id, and a Cancel leaves the stale id exactly as it
      was, re-verified the same way next time rather than silently
      forgotten. A verification failure (network/server error, *not*
      confirmed gone) shows the error and stops - same "don't guess"
      rule every dedup check in this file follows.
      **Fixed two more real bugs, the same catch/report**: the dialog's
      body used to show `buildMatchedBody(draft)` - fields freshly
      re-parsed from the rule - even once a live candidate was on hand,
      which can disagree with reality if the transaction was edited by
      hand in Firefly since this app posted it (the user's own example:
      changed description/notes), and never showed the transaction id at
      all. `buildCandidateBody` fixes both - built from the verified
      `candidate`'s own current fields, id included, same bold-label
      style as `buildMatchedBody`, now with `notes`/`category`/`tags`
      also available on `RemoteTransactionCandidate`/
      `FireflyTransactionCandidate` (parsed from Firefly's own
      `notes`/`tags`/`category_name` response fields, confirmed against
      `TransactionGroupTransformer`'s current source) where they weren't
      before. `MessagesFragment.checkIfCreated` got the equivalent fix
      too - and a further one, the user's own catch: it used to always
      run a full content search regardless of whether `message` already
      had a linked id, so an edited transaction whose content no longer
      hashed/queried the same could make it report a *different* id as
      the match, ignoring the known-good one entirely. It now checks the
      linked id first via the same `verifyLinkedTransaction`, same as
      `verifyLinkedThenShow`: still there -> shown directly
      (`showDuplicateConfirmDialog` with the live candidate), the search
      below skipped entirely; confirmed gone -> falls through to the
      search, now carrying the gone id as `staleLinkedId` so a
      replacement match says so explicitly, same warning as above.
      Neutral = "Open Transaction" (same
      `network.transactionUrl`/`ACTION_VIEW` as the success dialog's own
      button, factored into one `openTransaction(context, id)` helper
      both call, wired immediately - no async dependency), negative =
      "Create Again" - kept out of the neutral slot on purpose, so the
      single most relevant secondary action always sits there (create
      when nothing's linked, open when something is) and "do it again"
      stays a deliberate second choice, not the default. **Fixed a real
      "no confirmation at all" gap, the user's own catch**: "Create
      Again" used to jump straight to `startCreate` on tap, reasoning
      that re-creating from an already-linked message was already an
      explicit "yes, another one anyway" decision - but `startCreate`'s
      own `checkForDuplicate` safety net is best-effort only (silently
      skipped entirely when `FireflyPrefs.isDedupEnabled()` is off, or
      when the dedup window has since lapsed), so in practice the tap
      itself had no guaranteed confirmation at all. `confirmCreateAgain`
      now shows an explicit "Create again? This will post a second
      transaction" dialog first, every time, regardless of dedup
      settings - `startCreate` only runs after that's accepted, keeping
      its own `checkForDuplicate` call as an additional (not a
      replacement) safety net. **Fixed a second real gap, the user's
      own catch, same day**: `startCreate` used to post straight to
      Firefly from the draft exactly as originally matched, with no
      review/edit step at all - the user's explicit rule is that
      bypassing the review screen is only ever allowed for
      `posting.AutoCreateWorker`'s background auto-create-by-notification
      path, never for a manual action. `startCreate` now opens
      `CreateTransactionActivity` on a clean/failed duplicate check
      instead of posting (see that class's own entry below); a found
      duplicate still shows `showDuplicateConfirmDialog` first, whose
      own "Create anyway" (below) also opens that same screen rather
      than posting from here.
    - Nothing linked yet (and Firefly configured): this dialog shows up
      as its *found* variant (`showDuplicateConfirmDialog`, same as
      every other create path's duplicate hit) if the preemptive check
      above finds one, and doesn't show at all otherwise -
      `checkThenOpenCreateOrShowFound` redirects straight to
      `CreateTransactionActivity` instead (see above, and that class's
      own entry below). **History, superseded but kept for context**:
      this dialog used to offer a neutral "Создать" button here,
      starting disabled ("Checking…") until a preemptive dedup check
      resolved. **Fixed a real user complaint at the time**: the
      original design only ran the dedup check *after* the tap, so the
      exact same button could silently post straight through, silently
      reuse an existing transaction, or pop up a second "possible
      duplicate" dialog - no way to tell which in advance, a "blind
      click" by the user's own description, demonstrated with a real
      duplicate it created by mistake. That whole concern is moot now:
      the new screen is itself the "don't act blind" step - the user
      reviews/edits every field before ever tapping its own always-
      enabled "Create transaction" button, which then runs the same
      `checkForDuplicate` check synchronously on that tap (see
      `CreateTransactionFragment`'s own entry), not preemptively on
      open.

    `checkForDuplicate(context, draft)` (shared by both paths above, and
    by `posting.AutoCreateWorker` - see the `posting` package section
    above for why it now lives there rather than as a private member
    here) is the dedup logic itself, returning a `DuplicateCheckResult`
    (`None`/`Found(id)`/`FoundMultiple(mostRecentId, count)`) instead of
    directly branching to a dialog - local log first
    (`rules.CreatedTransactionsDb.findWithinWindow`, skipped entirely
    when `FireflyPrefs.isDedupEnabled()` is off) using
    `rules.transactionDedupHash` (source/destination id-or-name +
    canonical-scale amount + currency + description-if-the-toggle-says-so,
    see its own KDoc and TODO.md's dedup history) and the current
    `isDedupIncludeDescription()` flag, so the lookup compares against
    whichever of the two stored hash columns matches the setting right
    now (see `CreatedTransactionsDb`'s own KDoc for why both are always
    written). Only if *that* comes up empty does it also ask Firefly
    itself (`FireflyApiClient.searchTransactionsByQuery`, see the
    `network` section above) - the local log alone can't catch a
    duplicate a second device or app's own integration created, since
    it never touches this app's log at all. Scoped by whichever of
    `draft`'s source/destination *name* is known - deliberately not a
    Firefly account id (see `FireflyApiClient.searchTransactionsByQuery`'s
    KDoc for the real on-device gap this fixed: a rule whose account was
    typed as free text rather than picked from `showAccountPicker`, see
    the `RuleEditFragment` notes below, has no id at all, so an
    id-scoped search used to skip it entirely even though posting by
    name works fine). Filtered client-side via
    `rules.remoteCandidateMatches` (see the `rules` section above).
    Zero remote matches -> `None`; exactly one -> `Found`; more than one
    -> `FoundMultiple`, which both call sites turn into an *additional*
    Toast warning (`create_remote_duplicates_found`) on top of the same
    duplicate-confirm dialog a single match gets (using the most recent
    match as the dialog's "found" id) rather than replacing it - the
    user's own call, since more matches is a stronger signal, not a
    reason to short-circuit the usual flow. A failed remote call maps to
    `None`, same "flag it, don't guess, but don't block on a
    best-effort extra check" reasoning as everywhere else in this app -
    the local check already ran and found nothing.

    A likely duplicate (local or remote)
    shows the found transaction's id in the message itself
    (`create_duplicate_confirm_message`, `#%1$s`) and offers *four*
    actions, not the two an "are you sure?" confirm would have - a
    deliberate answer the user gave directly when asked, not assumed.
    Four is more than `AlertDialog.Builder`'s positive/negative/neutral
    slots can hold, so `showDuplicateConfirmDialog` builds it as a
    plain custom view (`dialog_duplicate_confirm_4.xml`, a 2x2 grid of
    borderless buttons - same "native combo doesn't do what's needed"
    reasoning as the rule editor's "Test results" dialog; a stack of
    four full-width rows, tried first, left too much dead vertical
    space for such short labels) instead of the native button bar:
    "Use Created" (links the *found*
    id - `findWithinWindow`'s return value, not a fresh post - to this
    message via `MessageStore.setTransactionId`, since the dedup check
    already did the work of locating it), "Open" (right after Use
    Created - `network.transactionUrl` on the found id, same helper the
    success dialog uses - deliberately does *not* dismiss the dialog,
    since opening Firefly to go check is "let me verify first", not a
    decision yet), "Create anyway", and "Cancel" - always last, by
    explicit request, regardless of which of the other three ends up
    next to it as this evolves.
    (Auto-create, see the `posting` package section above, does
    skip-and-notify on a likely duplicate instead of asking anything -
    there's no human in the loop there to ask.) **Fixed a real "create
    anyway bypasses review" gap, the user's own catch**: "Create anyway"
    used to call a private `postTransaction` straight from here, posting
    the draft exactly as matched/found with no review/edit step -
    `postTransaction` is gone now (along with the `FireflyTransactionRequest`/
    `CreatedTransactionsDb`/`transactionDedupHash` imports it alone
    needed), and "Create anyway" instead opens `CreateTransactionActivity`
    (see its own entry below) for the user to review before it actually
    posts, same as every other manual create path - the user's explicit
    rule is that bypassing this screen is only ever allowed for
    `posting.AutoCreateWorker`'s background path, never for a manual tap,
    even on an already-confirmed duplicate. `showDuplicateConfirmDialog`
    itself no longer takes a `draft` parameter at all as a result -
    nothing left in it needs one.
  - `CreateTransactionActivity`/`CreateTransactionFragment` - the
    pre-create review/edit screen (TODO.md's dedup history). Originally
    opened only by `MessagesFragment.showTestMatch`/
    `checkThenOpenCreateOrShowFound` for a fresh match (nothing linked
    yet, Firefly configured); now also the destination of "Create
    Again" (`startCreate`) and "Create anyway" (`showDuplicateConfirmDialog`)
    - every manual create path ends up here, per the user's explicit
    rule that only `posting.AutoCreateWorker`'s background path is
    allowed to post without review. `CreateTransactionFragment` doesn't
    treat an already-linked `message` (a "Create Again"/"Create anyway"
    re-launch) specially - it just re-matches and posts, overwriting the
    previous link, same "latest wins" reasoning used everywhere else a
    message gets re-created.
    A single-fragment-host `Activity` (its own class, not reusing
    `SettingsActivity` despite the identical shape - that class is
    named/documented as the settings area specifically, and this screen
    has no multi-level back-stack navigation to need `openScreen` for,
    just one screen). `CreateTransactionActivity.intent(context,
    message)`/`.messageFrom(intent)` carry the message across the
    Activity boundary as its own plain fields (packageName/appLabel/
    title/text/timestampMillis), not a serialized `TransactionDraft` -
    `CreateTransactionFragment` re-parses it fresh via `RuleEngine.match`
    once it's running, the same "reconstruct from primitives, don't try
    to serialize a `BigDecimal`/`Instant`-carrying data class across a
    process/Activity boundary" approach `posting.AutoCreateWorker`
    already uses for the identical reason.
    Every `TransactionDraft` field is editable - the user's own explicit
    scope call, not just category: amount, currency, foreign amount/
    currency, date/time (chained `DatePickerDialog`+`TimePickerDialog`,
    same pattern `AddTestMessageFragment` already has), source/
    destination account, description, notes, category, tags. `kind`
    itself stays fixed/read-only (shown as plain text, not a field) -
    it's a property of which rule matched, not something this screen
    lets you reassign. Visual style and the asset-account picker itself
    are deliberately copied from `RuleEditFragment` (the user's own
    suggested starting point): an end-icon `TextInputLayout` opening an
    `AlertDialog.setSingleChoiceItems` picker, `FireflyApiClient.
    listAssetAccounts()` cached once per screen session - only the
    asset side of source/destination gets it (`updateAccountFieldModes`,
    same per-kind which-side-is-asset mapping `RuleEditFragment` has),
    the counterparty side stays plain free text since Firefly
    auto-creates it by name regardless. The category field gets the
    same picker shape fed by the new `FireflyApiClient.listCategories()`
    instead (see the `network` section above) - unlike the account
    picker, no id-staleness tracking needed on pick, since Firefly's
    create-transaction endpoint only ever takes a category *name*, no
    id (confirmed via `FireflyJson.buildTransactionRequestBody`).
    `PickedAccount(id, name)` + `pick?.takeIf { it.name == currentText }
    ?.id` at build time (not a live `TextWatcher`) is the exact same
    stale-id-after-manual-edit handling `RuleEditFragment.buildDraft`
    already uses.
    If the draft has `DraftIssue.LOCAL_AMOUNT_MISSING`, the same
    exchange-rate fetch `MessagesFragment.resolveApproxConversion` used
    to run kicks off on open and fills the amount field once it
    resolves - but only if that field is still blank at that point, not
    unconditionally, since the user may have already typed an amount by
    hand while the async fetch was in flight and that shouldn't get
    silently overwritten.
    "Create transaction" is always enabled (no preemptive "Checking…"
    dance the way the old dialog's neutral button had - see
    `MainActivity`'s own entry above for why that's no longer needed):
    tapping it validates the form (amount parses, description non-blank,
    source/destination present where the kind requires them - same
    per-kind requirement `rules.RuleValidation.validateRule` already
    encodes, just against a built `TransactionDraft` instead of a
    `ParseRule`) then runs the same `posting.checkForDuplicate` every
    other create path shares. A clean/best-effort-failed check posts
    directly; a likely duplicate shows a plain two-button confirm
    dialog (`create_duplicate_confirm_title`/`_message`, reusing the
    existing strings - Cancel rightmost/last per the house convention,
    since this genuinely is a dialog, unlike the rest of this screen) -
    deliberately simpler than `MessagesFragment.showDuplicateConfirmDialog`'s
    four-action grid (no "Use Created"/"Open" here - the whole point of
    this screen already *is* "look before you post", so "create
    anyway or don't" is the only decision left to make). A successful
    post records both `CreatedTransactionsDb` hash variants, links the
    id to the message (`MessageStore.setTransactionId`, same "latest
    wins" reasoning as everywhere else that call happens), and finishes
    the activity; a failure re-enables the button and shows the real
    failure reason in a plain dialog.
  - `SettingsActivity` - everything else, as a stack of fragments
    pushed via plain `FragmentManager` back-stack transactions (no
    Navigation Component - the flow is small and linear enough not to
    need it). Toolbar title: `openScreen(fragment, titleRes)` sets it
    once at push time, but that alone isn't enough - popping back to a
    screen doesn't re-run it, which used to leave the *departed*
    screen's title showing (a real user-reported bug) instead of
    resetting to whichever screen is now actually visible; tracking this
    via `addOnBackStackChangedListener` (reset to "Settings" only when
    the back stack emptied out completely) covered the single-level
    case but not returning to `RulesListFragment` one level down from
    `RuleEditFragment`, and was one more moving part than needed anyway.
    Fixed by having each fragment that can be a "return to" target
    re-assert its own title itself, from its own `onResume`
    (`SettingsActivity.setScreenTitle(titleRes)`, a small helper
    `openScreen` now also calls internally) - correct regardless of how
    the fragment became visible again (back-pop, or an activity
    recreation restoring it from the back stack without going through
    `openScreen` at all), not dependent on back-stack bookkeeping.
    `setBackGuard(guard)` lets whichever fragment is on top
    intercept *both* the toolbar's up arrow (`onSupportNavigateUp`) and
    system back (an `OnBackPressedCallback`, toggled enabled/disabled
    by the same call) with its own logic instead of the default
    pop-or-finish - same two-triggers-one-handler shape as
    `MainActivity`'s `onSelectionCancel`, just generalized into a
    reusable setter here since more than one fragment hosted by this
    activity can plausibly need it (so far: `RuleEditFragment`'s
    unsaved-changes confirm, see its own entry below). `null` restores
    the default. `SettingsRootFragment` is the menu (Firefly III
    settings, monitored apps, currencies, rules, message history, add
    test message, backup & restore, about, added one row
    at a time as each is built - Firefly settings sits first since
    it's the gateway to actually doing anything with a rule match now
    that posting is underway; currencies sits above rules since rules
    depend on it, same reasoning as monitored apps coming first; about
    sits last, after backup & restore - nothing to configure, just name/
    version/GitHub link, so it belongs at the very bottom of the list).
    **Renamed from "Firefly III connection", the user's own call**: the
    screen covers far more than the connection itself (approx-conversion
    tag, auto-create, all three dedup settings) - "Firefly III settings"
    doesn't imply it's only URL/token. The row's own subtitle (below)
    still only reflects the connection half, unchanged.
    `rowAboutSubtitle` now shows "Version x.yy" right on this list
    (`updateAboutSubtitle`, same `PackageManager.getPackageInfo`
    `versionName` lookup `AboutFragment` already did, reusing its
    `about_version` string) - the user's own request, so the running
    build's version is visible without opening About first.
    Linking to:
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
      opened, not eagerly). Its "grant access" banner button (shown
      when `isNotificationListenerAccessGranted` is false - the one
      shared check `MainActivity`'s own gate above, this banner, and
      `NotificationAccessConsentFragment`'s auto-dismiss all use) opens
      `NotificationAccessConsentFragment` instead of jumping straight to
      `Settings.
      ACTION_NOTIFICATION_LISTENER_SETTINGS` the way it used to - a
      Google Play review requirement for any app requesting notification-
      listener access: an explicit in-app disclosure of what the app
      does with notification content (receives from every installed
      app, analyzes only the monitored ones, extracts amount/currency/
      date/description on-device, never sends raw notification text
      off-device, sends only the recognized transaction to the user's
      own configured Firefly instance) shown *before* the system grant
      screen, not left to that screen's own generic permission wording.
      Declining (`consentNotNowButton`) just pops back to the same
      banner via `parentFragmentManager.popBackStack()` - `AppsFragment`
      is still right behind it on `SettingsActivity`'s back stack,
      nothing lost.
    - `MessageHistoryFragment` (the retention radio group, writes to
      `RetentionPrefs` and calls `RetentionController
      .applyOnSettingChanged`).
    - `AddTestMessageFragment` - a debug tool: picks from the
      monitored apps list and calls `MessageStore.shared.add(...)`
      directly with user-entered title/text, exactly like
      `BankNotificationListenerService` would for a real notification
      (same dedup, same persistence-if-enabled, same
      `posting.AutoCreateScheduler.scheduleIfEnabled` call on a
      genuinely new result) - lets rule processing be tested without
      waiting for a real bank notification.
      `dateTimeButton` (defaults to "now" when the screen opens, chained
      `DatePickerDialog`+`TimePickerDialog` to edit) lets the injected
      message carry a chosen timestamp instead of always being "just
      now" - the user's own request, for reproducing a rule bug tied to
      a specific real notification's time. `MessageStore.add` gained an
      optional `timestampMillis` parameter (defaults to `clock()`, every
      other call site unaffected) for this; its dedup-window comparison
      switched from `now - existing.timestampMillis` to
      `Math.abs(...)` of the same, since a deliberately backdated
      timestamp can now be *older* than an existing entry, which the
      original always-monotonic "now" assumption never had to handle.
    - `AboutFragment` - app name, `versionName` (read from
      `PackageManager.getPackageInfo`, not hardcoded, so it can't drift
      from `app/build.gradle.kts`), and a GitHub link
      (`about_github_url`, opened via `ACTION_VIEW`) - static, no
      prefs/db involved.
    - `RulesListFragment` - the ordered rule list (`RulesAdapter`);
      tap opens `RuleEditFragment` to edit, the overflow (⋮) menu per
      row has move up/down, duplicate, delete - all straight
      `RulesDb` calls followed by a reload. **This row's overflow-menu
      shape is the reference pattern for every list in the app** - the
      user's own explicit call, prompted by `CurrenciesFragment` having
      grown a bespoke standalone trash-icon button instead: same
      `ic_more_vert` `ImageButton` (48dp, borderless, `onOverflowClick:
      (T, View) -> Unit` from the adapter), same `PopupMenu` shown from
      it, even where a list only ever needs one menu item
      (`CurrenciesFragment`, below) - visual/interaction consistency
      across lists matters more than how many items happen to be in the
      menu. Add/Export/Import
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
      and edit, via `RuleEditFragment.newInstance(ruleId)`. Leaving the
      screen with unsaved edits (toolbar's up arrow *or* system
      back/gesture - both, not just one) asks for confirmation first:
      `initialDraft` snapshots `buildDraft()` right after the fields
      are first populated, `hasUnsavedChanges()` just compares it
      against a fresh `buildDraft()` (`ParseRule` is a data class, one
      `!=` covers every field), and `SettingsActivity.setBackGuard`
      (see its own entry above) is how both back triggers get routed
      through that check instead of the activity's default
      pop-or-finish - registered in `onViewCreated` right after
      `initialDraft` is captured, cleared in `onDestroyView` so it
      can't linger once the fragment is gone (a real save/delete pops
      the back stack directly, bypassing this check entirely - there's
      nothing to discard). App and
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
      **Used to also capture the picked account's Firefly ID
      alongside the name** (`RuleEditFragment.PickedAccount`, held in
      `sourceAccountPick`/`destinationAccountPick`, carried into
      `ParseRule.sourceAccountId`/`destinationAccountId` at save time) -
      removed entirely, a deliberate, breaking fix for a real cross-app
      dedup bug the user caught directly on-device: two different rules
      for the same real Firefly account don't necessarily agree on
      whether an ID got cached for it (one rule's account field picked
      via this picker, another free-typed or saved before the picker
      existed), so `rules.transactionDedupHash` hashing `id ?: name`
      made the two rules' drafts hash *differently* for the identical
      real account, and a genuine duplicate between them went uncaught.
      `ParseRule` doesn't carry a Firefly account ID at all anymore (see
      its own KDoc) - this picker now only ever fills in the account's
      *name* (`target.setText(account.name)`, no `PickedAccount`/id
      tracking left to do), purely to avoid a typo against the real
      account list, not for posting precision. `ui.
      CreateTransactionFragment`'s own, separate one-shot account picker
      (see its own entry below) is the one remaining place an ID can
      still reach `FireflyJson.buildTransactionRequestBody`'s
      `source_id`/`destination_id` preference, for that single
      transaction being created right then - not something a *rule*
      carries forward across multiple future notifications.
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
      recognizes. `dateFormat` (`showDateFormatPicker`, same end-icon
      dropdown shape, right below `homeCurrency` in field order - both
      are "how to interpret a captured value" fields) offers five
      preset patterns (`dd.MM.yy`/`dd.MM.yyyy`/`dd/MM/yyyy`/
      `yyyy-MM-dd`/`MM/dd/yyyy`) that *fill* the field rather than lock
      it - unlike the currency picker's codes, a bank's actual date
      wording won't always match one of these exactly, so the text
      stays freely editable afterward.
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
      `RuleEditFragment` - two fields doesn't warrant one. Delete is
      behind the same overflow (⋮) `PopupMenu` `RulesListFragment`
      uses, not its own standalone trash-icon button (an earlier,
      bespoke design) - a one-item menu is still the uniform pattern,
      per the user's own call on list-interface consistency; delete
      asks for confirmation the same way it always did
      (`confirmDelete`, unchanged).
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
- **Real incident, data lost, twice, same root cause**: `FullBackupJson`
  originally reused `RulesJson`'s rule shape wholesale and hand-picked
  `CapturedMessage` fields for messages - both choices copied
  `RulesJson`'s *deliberate* exclusion of `ParseRule.sourceAccountId`/
  `destinationAccountId` (correct there - it's a sharing format, a
  device-specific Firefly account id is meaningless once it travels
  elsewhere) onto a full backup, which is a same-device safety net,
  not a sharing format, with no independent argument for dropping
  either id. The user caught the message-side half directly: restored
  a backup made minutes earlier and every message that had shown
  "#1234" came back with no link, even though the transaction still
  existed in Firefly - then asked for the actual argument behind the
  account-id exclusion too, which didn't hold up on inspection either.
  `CreatedTransactionsDb` (the dedup log) had a related gap from a
  different angle - `BackupManager` never touched it at all, so a
  restore onto a fresh install would've silently come back with an
  empty dedup log. All three fixed now: `fullBackupToJson`/
  `fullBackupFromJson` carry `fireflyTransactionId` and both account
  ids through, and `FullBackupData.createdTransactions` backs up the
  dedup log too (see the `backup` package section above for the full
  reasoning on each). Lesson: when copying a design decision from one
  place to another ("exclude this kind of id"), check *why* the
  original exclusion was correct *there*, not just that it looks like
  the same shape of field - the same mistake was made twice, in two
  different fields, from the same unexamined copy.
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
