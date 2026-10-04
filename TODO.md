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
    until it fully exists (see next item). Phased, in progress:
    - **Done**: connection settings screen (`FireflySettingsFragment`,
      `FireflyPrefs`, `KeystoreCipher` - see ARCHITECTURE.md) - base
      URL, token, Test connection, the approximate-conversion tag field
      (used below), auto-create toggle, dedup toggle +
      include-description toggle + window (minutes). Also done:
      `FireflyApiClient.listAssetAccounts()`/`createTransaction()` and
      their request/response plumbing (`FireflyAccount`,
      `FireflyTransactionRequest`, `FireflyJson` - see
      ARCHITECTURE.md), verified against Firefly's OpenAPI spec, not
      guessed. Also done: the rule editor's asset-side account field
      (source for WITHDRAWAL, destination for DEPOSIT, both for
      TRANSFER - `RuleEditFragment.updateAccountFieldModes`) now picks
      from `listAssetAccounts()` instead of staying a free-text macro
      template, since Firefly's API only auto-creates the
      *counterparty* side (expense/revenue) by name - the asset side
      must already exist. Falls back to a Toast + stays free-typeable
      when Firefly isn't configured, rather than blocking the rule
      editor on it. Also done, raised by the user: picking an account
      also captures its Firefly ID (`ParseRule.sourceAccountId`/
      `destinationAccountId`, `RulesDb` v6), since an ID survives an
      account rename in Firefly and a name alone doesn't - posting
      prefers the ID when present (`FireflyJson.
      buildTransactionRequestBody` sends `source_id`/`destination_id`
      instead of the `_name` variant), falls back to name-only exactly
      like before for any rule saved before this existed. The picker
      also now pre-highlights whichever account already matches the
      field's current text when reopened (`setSingleChoiceItems`, also
      user-raised).
    - **Done**: `createTransaction()` finally has a caller - the
      "Создать" neutral button on the Messages screen's test-match
      dialog (`MessagesFragment.startCreate`/`postTransaction`), shown
      whenever a rule matched, a Firefly connection is configured, and
      the draft's amount is resolved (reads the *current* draft at
      click time, so it still works after the async exchange-rate fetch
      above swaps a resolved amount in). On success: records the
      transaction in a new local log
      (`rules.CreatedTransactionsDb` - hash + the transaction's own
      date + the returned Firefly transaction id, one row per
      successful `createTransaction()` call - plain SQLite, same
      reasoning as `MessageHistoryDb`/`RulesDb`) and shows an OK dialog
      with the Firefly transaction id. On failure: shows the real
      failure reason, same pattern as every other Firefly-call failure
      in this app (`firefly_test_connection_failure`,
      `rule_firefly_accounts_failed`, ...).
      `TransactionDraft` gained `sourceAccountId`/`destinationAccountId`
      (from `ParseRule`, populated in `RuleEngine.buildDraft`) so
      posting can prefer the Firefly account id the same way
      `FireflyJson.buildTransactionRequestBody` already does.
    - **Done**, prompted by the user directly after confirming dedup
      works on a real device: the created transaction's id is now
      linked back to the *notification* it came from, not just logged
      in `CreatedTransactionsDb` by hash. `data.CapturedMessage` gained
      `fireflyTransactionId` (nullable, deliberately excluded from what
      identifies a message for dedup/removal/`MessageHistoryDb`
      lookups, since it's exactly the field being updated in place -
      see `MessageStore.setTransactionId`'s KDoc), persisted through
      `MessageHistoryDb` same as everything else there (`DB_VERSION`
      1->2, a real additive `ALTER TABLE` migration - the first actual
      test of the promise that table's `onUpgrade` comment had been
      making since the `RulesDb` incident, see ARCHITECTURE.md's Known
      bugs/gotchas). The Messages list shows "#<id>" next to the
      timestamp once a message has one (`MessagesAdapter`); the
      test-match dialog's buttons change once it does too - "Open"
      (links to Firefly's own page for it) replaces "Create Transaction"
      in the neutral slot, "Create Again" appears in the negative slot
      (re-runs the normal create flow, including the dedup check - no
      separate skip-dedup path, simpler and the user didn't ask for
      one; overwrites the message's linked id with whichever transaction
      was created most recently). `CreatedTransactionsDb.
      existsWithinWindow` became `findWithinWindow`, returning the
      matched row's Firefly id (most recent match,
      `ORDER BY date_millis DESC`) instead of just `Boolean` - the
      duplicate-confirm dialog gained a third option beyond "create
      anyway"/"cancel": "Already Created" links *that* found id to the
      message via `MessageStore.setTransactionId` instead of posting a
      new one, per the user's own answer on this exact question.
      **Still not done**: the auto-create path (WorkManager retry on
      failure, per AGENTS.md's stack note) - `FireflyPrefs.
      isAutoCreateEnabled()` exists as a setting but nothing reads it
      yet. Decided (asked the user directly, not assumed) for when this
      gets built: manual creation (the button above) asks for an
      explicit confirmation on a likely duplicate before creating it
      anyway; automatic creation *skips* a likely duplicate instead and
      surfaces that as a system notification, and a *successful*
      automatic creation also gets a system notification (manual
      creation's success feedback stays the in-app OK dialog it has
      now, not a notification - the user is already looking at the
      screen).
    - Transaction-level dedup (**done**, manual-creation path only -
      see above): `rules.transactionDedupHash` (pure, unit tested -
      `TransactionDedupTest`) hashes source/destination
      (account id when present, else the rendered name)
      + amount (canonical 2-decimal string, so `12.5`/`12.50` hash the
      same) + currency + description (only when
      `FireflyPrefs.isDedupIncludeDescription()`), and
      `CreatedTransactionsDb.findWithinWindow` checks it against
      `FireflyPrefs.getDedupWindowMinutes()` centered on the new
      transaction's own date (not "now"), so two apps notifying about
      the same payment seconds apart both land in the same window
      regardless of processing order. This is a different problem from
      `MessageStore`'s existing notification-level dedup
      (`(packageName, title, text)` within a window - see its
      ARCHITECTURE.md entry):
        - A bank can re-post the same notification a few times - already
          handled by that same-app dedup, but it's worth actually
          confirming against a real repeat-notification case now that
          posting makes a duplicate actually cost something (an extra
          transaction in Firefly), rather than trusting it untested.
        - Rules can legitimately exist for *two different apps* that
          both notify about the *same real-world transaction* - a
          bank's own app and Google/Apple Wallet both firing for one
          card tap, for example. `MessageStore`'s dedup never catches
          this (different `packageName`, so it's two distinct captured
          messages by design) - this is exactly what the hash-based
          check above catches instead, as long as both land within the
          configured window.
        - Open question, not resolved: how to tell "the same payment,
          reported twice by two apps" apart from "the same payment,
          genuinely happening twice in a row" (e.g. tapping a phone
          twice for two separate NFC turnstile entries) - a naive
          same-amount-same-account-within-a-few-seconds rule would
          collapse the second real transaction into a false dedup. The
          manual path's "create anyway" confirmation is the safety net
          for this today (the user sees the warning and decides); the
          auto-create path above has no equivalent human-in-the-loop
          moment, which is part of why it's still deferred.
    - `LOCAL_AMOUNT_MISSING` (currency captured by the pattern differs
      from the rule's `homeCurrency`, no explicit `foreignAmount`/
      `foreignCurrency` groups to tell us the converted amount - e.g. a
      CHF card tap on a EUR account). Looking up a real exchange rate
      and computing an *approximate* `amount` is better than refusing
      to post at all - verified against Firefly's own API/docs before
      deciding this (see "What *is* safe" below for why this app
      computes the amount itself rather than trusting Firefly's own
      exchange-rate table).
        - Firefly III has its own Currency Exchange Rates API
          (`GET /v1/exchange-rates/{from}/{to}/{date}`, plus `POST`
          endpoints to store rates) - confirmed from its OpenAPI spec
          and docs, not assumed. But its own rates only come from
          manual entry or an auto-download that - per Firefly's own
          docs - "only works for the default system provided
          currencies," runs roughly weekly via cron, and never
          backfills historical dates. **If Firefly has no rate for a
          pair, it silently uses 1.0** - a correct-looking but
          wildly-wrong "conversion" for anything but near-identical
          currencies, with nothing in the response flagging it as a
          fallback rather than a real rate. Relying on Firefly's own
          calculation (just posting `foreign_amount` and letting
          Firefly derive `amount`) is therefore not safe for an
          arbitrary/less-common currency pair.
        - What *is* safe, per the same docs: Firefly treats an
          explicitly-supplied `foreign_amount` as authoritative over
          anything it would calculate itself ("if you set a foreign
          amount in your primary currency, that amount will be used
          instead of any calculated amount").
        - **Done**: `network.ExchangeRateClient`/`ExchangeRateUrl` -
          fetches a rate from Frankfurter (`api.frankfurter.dev/v2`), a
          free, keyless, ECB-based API, independent of Firefly's own
          table - `GET /rate/{base}/{quote}?date=YYYY-MM-DD`, verified
          directly against the live API (not assumed): response shape
          `{"date","base","quote","rate"}`, no API key, no quota, and a
          real HTTP 422 for an unsupported currency code (unlike
          Firefly's silent 1.0 fallback above) - the date is the
          notification's own date, not "today", and a weekend/holiday
          date doesn't error (confirmed live). `rules.
          applyApproxConversion` (pure, no `network` dependency -
          `rules` still doesn't depend on `network`, same layering as
          the reverse) computes `amount = foreignAmount * rate`
          (`HALF_UP`, 2 decimals) and appends the approx-conversion tag
          (`FireflyPrefs.getApproxConversionTag`) to the draft's
          existing tags, skipped if blank or already present. Wired
          into the Messages screen's "test match" dialog only so far
          (`MessagesFragment.fetchApproxConversionIfNeeded`) - the
          dialog shows "no amount" immediately, same as before, then
          swaps in the computed amount (or an explicit "lookup failed:
          <reason>" line) once the async fetch resolves; this is a
          preview-only convenience, not a posting path - no Firefly
          call happens here.
        - Still not done: posting `amount`/`foreign_amount`/
          `foreign_currency_code` together to Firefly for real (needs
          the "Создать" button / full posting pipeline below to exist
          first - this item doesn't create that pipeline, it just makes
          the preview dialog show a real number instead of "missing").
          Also still not done, nice-to-have: `POST`ing the fetched rate
          back into Firefly's own exchange-rate table as a side effect.
    - **Done**: a rule can opt into reading the transaction's *own*
      date from the notification text instead of always using when the
      notification arrived - prompted by a real case (Bank DE: "Die
      Abbuchung über €99,99 für 00000 findet am 01.02.30 statt", a
      charge-date announcement sent days before the actual debit).
      `Groups.DATE` (`"date"`) is a new optional named group;
      `ParseRule.dateFormat` (blank by default = old behavior) is a
      `java.time.format.DateTimeFormatter` pattern the user types in,
      e.g. `dd.MM.yy` - deliberately *not* auto-detected, unlike
      `parseAmount`'s decimal-separator heuristic: a date like
      `01.02.30` is genuinely ambiguous (day-first or month-first)
      without knowing the bank's own convention, and guessing wrong
      would silently post the wrong date. `parser.parseDate(raw,
      pattern)` (pure, `DateParserTest`) resolves to noon UTC on the
      parsed date (no time of day - banks announcing a future charge
      never give one) - `RuleEngine.buildDraft` uses it instead of
      `message.timestampMillis` when both the group and the format are
      present and it actually parses; a captured-but-unparsable date
      (typo'd format, or text that doesn't match) flags the new
      `DraftIssue.DATE_UNPARSABLE` and falls back to the notification's
      own time, rather than either blocking the match or silently
      posting a wrong date with no warning - the same "flag it, don't
      guess" precedent `LOCAL_AMOUNT_MISSING` set. `validateRule` checks
      a non-blank `dateFormat` is a legal formatter pattern at save
      time. The rule editor's new "Date format" field has a preset
      picker (`dd.MM.yy`/`dd.MM.yyyy`/`dd/MM/yyyy`/`yyyy-MM-dd`/
      `MM/dd/yyyy`) that fills the field with editable text, same
      "picker sets it, typing still works" shape as the currency
      picker - not a closed dropdown, since a bank's actual wording
      won't always match one of the presets exactly. The test-match
      dialog gained a "Date:" field (there wasn't one at all before -
      otherwise this whole feature would be invisible until a
      transaction posted with the wrong date). `RulesDb` v6->v7,
      additive `date_format TEXT NOT NULL DEFAULT ''`; `RulesJson`
      carries `dateFormat` through export/import (portable config, not
      a device/Firefly-instance-specific id - unlike
      `sourceAccountId`/`destinationAccountId`, which stay excluded).
  - A way to reorder rules other than one-step-at-a-time up/down
    buttons (fine for a handful of rules; revisit - e.g. drag handles
    - if the list grows long enough to make that tedious).
  - **Done**: Export/import buttons on the Rules screen (`backup.
    RulesJson`/`RulesListFragment`) - JSON, as planned here (`org.json`,
    no new dependency, natural fit for Storage Access Framework
    save-to/open-from). Import always *appends* as new rows
    (`RulesDb.add`, fresh ids) - sharing a rule (with yourself across
    devices, or with someone else) is "add these too", never "replace
    what I have". Deliberately excludes `id`/`sourceAccountId`/
    `destinationAccountId` - both are this device's/this Firefly
    instance's internal identity, meaningless elsewhere; the asset-side
    account needs re-picking after import regardless.
    **Also done, prompted by the `RulesDb` data-loss incident** (see
    ARCHITECTURE.md's Known bugs) - a *full* device backup/restore, a
    different thing from rules-sharing: a new Settings row
    (`BackupFragment`/`backup.BackupManager`/`FullBackupJson`) covering
    monitored apps, currencies, rules, and message history in one JSON
    file. Deliberately excludes the Firefly connection (base URL/
    token) - the one real secret in this app, `KeystoreCipher`-encrypted
    against a key that wouldn't even decrypt on a different device, and
    a backup file is exactly the kind of thing that ends up in a cloud
    folder - re-entered after a restore instead. Restore *replaces*
    (clears each store first, per `BackupManager.restore`'s KDoc), with
    an explicit confirm dialog first - this is a backup/restore tool
    built directly in response to a destructive action taking real data
    with no warning, so it does not repeat that pattern.
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
  device. Also needs real-device verification now that it exists: the
  "Создать" button against a real Firefly instance (both a clean create
  and the duplicate-confirmation path - create the same message twice
  within the dedup window and confirm the warning actually fires, plus
  the new "Already Created" option, "Create Again"/"Open" once a
  message has a linked id, and the "#<id>" badge actually showing up in
  the Messages list), and `CreatedTransactionsDb`'s and
  `MessageHistoryDb`'s schema/upgrade paths (no SQLite-level test
  coverage exists for either, same as every other `SQLiteOpenHelper` in
  this app - `MessageHistoryDb`'s v1->v2 `ALTER TABLE` migration in
  particular has never run against a real on-disk v1 database yet, only
  against a fresh `onCreate`). Also needs real-device verification: the
  date-override feature against the actual Bank DE notification that
  prompted it (a real `dd.MM.yy`-style future charge date, confirming
  the posted Firefly transaction lands on the right day, not the
  notification's arrival day) and `RulesDb`'s v6->v7 migration against
  a real on-disk v6 database with actual saved rules.
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
- Template fields (description/notes/category/tags/account names) have
  no conditional logic - only flat `{macro}` substitution. Raised by
  the user: sometimes only *one* field should vary depending on what a
  group captured (e.g. a tag that says "foreign" only when `currency`
  isn't the home currency), and duplicating the whole rule just to
  change that one field is wasteful when everything else (pattern,
  accounts, description, ...) would be identical between the two
  copies - unlike varying by *app* or *kind*, which already has a
  natural home as separate rules (list-order priority already handles
  "try rule A, then B" - this is a different, smaller-grained need:
  one field, one rule, conditional content). A conditional block was
  proposed. Not implemented - this is a real jump in complexity, not a
  small addition, and needs these decided first:
  - Syntax (decided, per the user - Jinja2-style, since they'd already
    seen it elsewhere and it reads unambiguously against both plain
    `{macroName}` substitution and the `{{escaped}}` literal-brace
    proposal in the entry above): `{% if <expr> %}`...`{% else %}`...
    `{% endif %}`, operators/operands all living inside the `{% %}`
    delimiters rather than bare `{word}` - a parser can tell "this is a
    control token" apart from "this is a macro name" without guessing,
    since `%` never appears in a plain macro reference.
  - Condition scope (decided, per the user): real comparisons, at least
    `==`/`!=` between a bare macro name and a quoted string literal -
    e.g. `{% if currency == "EUR" %}` - not just a bare presence/
    truthiness check. Still open: quoting rules for the literal
    (escaped quotes inside it?), whitespace tolerance around the
    operator, whether the left-hand side can also be a quoted literal
    or only a macro name, and whether comparing two macro names
    (`{% if currency == homeCurrency %}`-style, though `homeCurrency`
    isn't itself a macro today - see the currency-field discussion)
    is ever needed. Numeric/ordering comparisons (`<`/`>`, e.g. an
    amount threshold) weren't asked for - leave out of v1, revisit if
    a real use case shows up.
  - This stops being a single regex pass (`MacroTemplate.kt` today) and
    becomes an actual small block parser - unmatched/nested
    `if`/`endif`, whether `{else}` is required, whether nesting is
    allowed at all for v1 - each needs an explicit answer and its own
    test coverage, not just "whatever the regex happens to do".
  - `validateRule`/`templateMacroNames` need to parse `{% %}` tokens
    too: the macro name on the condition's left-hand side (`currency`
    in the example above) should still be validated as a real macro
    reference (so a typo there is caught the same way a typo in a bare
    `{macro}` already is), while `if`/`else`/`endif`/the quoted literal
    are not macro references and shouldn't be flagged as "unknown
    macro".
  - The rule editor's insert-macro picker (the "<>" button) only knows
    how to insert a bare `{name}` today - inserting a whole `{% if %}`
    ...`{% else %}`...`{% endif %}` skeleton would need its own UI
    entry point, not just another row in that same list.
- Only English and Russian are localized
  (`values/strings.xml` and `values-ru/strings.xml`). Default/base is
  English; add more `values-<lang>/strings.xml` as needed, keep
  `strings.xml` in sync across locales when adding new strings.
