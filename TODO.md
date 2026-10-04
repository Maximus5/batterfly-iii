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
    - **Not done yet**: `createTransaction()` still has no caller - a
      local "created transactions" log (hash + source notification +
      Firefly transaction id, the latter from `createTransaction()`'s
      return value) for both the dedup check and an "already created"
      state; the "Создать" button itself on the Messages screen's
      test-match dialog, which is what actually calls
      `createTransaction()` for the first time; the auto-create path
      (WorkManager retry on failure, per AGENTS.md's stack note).
    - Once full posting exists, it needs an automatic-posting setting
      (**done** - see above) *and* a transaction-level dedup option
      (**done** - see above) - two different problems from
      `MessageStore`'s existing notification-level dedup
      (`(packageName, title, text)` within a window - see its
      ARCHITECTURE.md entry):
        - A bank can re-post the same notification a few times - already
          handled by that same-app dedup, but it's worth actually
          confirming against a real repeat-notification case once
          posting makes a duplicate actually cost something (an extra
          transaction in Firefly), rather than trusting it untested.
        - Rules can legitimately exist for *two different apps* that
          both notify about the *same real-world transaction* - a
          bank's own app and Google/Apple Wallet both firing for one
          card tap, for example. `MessageStore`'s dedup never catches
          this (different `packageName`, so it's two distinct captured
          messages by design) - each would independently match its own
          rule and, without something new, produce two Firefly
          transactions for one real payment.
        - Open question, not resolved: how to tell "the same payment,
          reported twice by two apps" apart from "the same payment,
          genuinely happening twice in a row" (e.g. tapping a phone
          twice for two separate NFC turnstile entries) - a naive
          same-amount-same-account-within-a-few-seconds rule would
          collapse the second real transaction into a false dedup.
          Likely a minor, low-probability case in practice (two people
          tapping in close succession would typically use two separate
          phones, not the same phone twice) - not blocking, but needs
          an explicit answer before building the dedup logic, not an
          assumption baked in silently.
    - `LOCAL_AMOUNT_MISSING` (currency captured by the pattern differs
      from the rule's `homeCurrency`, no explicit `foreignAmount`/
      `foreignCurrency` groups to tell us the converted amount - e.g. a
      CHF card tap on a EUR account) currently has no resolution story
      beyond "flag it, don't guess" (see the cross-currency chat this
      came out of). Revisited: looking up a real exchange rate and
      computing an *approximate* `amount` is better than refusing to
      post at all, verified against Firefly's own API/docs before
      deciding this:
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
          instead of any calculated amount"). Decided: fetch a rate
          from an independent, free external source *we* control - a
          free, keyless, ECB-based API like Frankfurter is the obvious
          starting candidate, not investigated in depth yet - compute
          `amount` ourselves, and post both `amount` (our computed
          guess) and `foreign_amount`/`foreign_currency_code` (the
          real captured values) explicitly, rather than leaning on
          Firefly's own exchange-rate table at all. Optionally also
          `POST` the fetched rate back into Firefly's table as a
          side-effect (harmless, and it means Firefly's own future
          reports/calculations benefit too) - a nice-to-have, not a
          dependency of the main flow.
        - Decided and **done**: mark a computed/approximated
          transaction with a tag (not a note or a Firefly-side flag) -
          the tag's *name* is user-configurable
          (`FireflyPrefs.getApproxConversionTag`/`FireflySettingsFragment`),
          so it's a plain, visible Firefly tag the user picks
          themselves rather than something baked into this app's
          wording. Still not done: actually applying it - on top of
          whatever the rule's own `tagsTemplate` already produces, not
          instead of it - which needs the exchange-rate lookup +
          computed-`amount` flow above to exist first.
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
