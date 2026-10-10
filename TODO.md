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
      **Done**: the auto-create path (`posting.AutoCreateWorker`/
      `AutoCreateScheduler`/`AutoCreateNotifications`), per the design
      decided earlier (asked the user directly, not assumed): manual
      creation (the button above) asks for an explicit confirmation on a
      likely duplicate before creating it anyway; automatic creation
      *skips* a likely duplicate instead and surfaces that as a system
      notification, and a *successful* automatic creation also gets a
      system notification (manual creation's success feedback stays the
      in-app OK dialog it has now, not a notification - the user is
      already looking at the screen).
      `posting.DuplicateChecker` (`DuplicateCheckResult`/
      `checkForDuplicate`/`findDuplicate`) is the same local-then-remote
      dedup lookup `MessagesFragment` already had, pulled out of that
      Fragment into its own Context-based (not Fragment-based) file so
      `AutoCreateWorker` can call the identical logic instead of
      re-deriving it a second way and risking drift - `MessagesFragment`
      now just imports these as top-level functions, no behavior change
      there.
      `AutoCreateScheduler.scheduleIfEnabled(context, message)` is the
      one entry point, called right after a genuinely new message is
      recorded - from both `BankNotificationListenerService` (a real
      notification) and `AddTestMessageFragment` (a manually-injected
      test message, "exactly like a real notification would" per its
      own KDoc - includes this too, deliberately). A no-op unless
      `FireflyPrefs.isAutoCreateEnabled()`.
      **The user's own explicit requirement, the reason this needed its
      own scheduler rather than just launching a coroutine per
      message**: two notifications can arrive essentially
      simultaneously, and auto-create must never process them in
      parallel - a race between two concurrent duplicate checks could
      let both independently decide "not a duplicate" and both post the
      same real payment, exactly the failure mode dedup exists to
      prevent. Every message is enqueued into one single WorkManager
      unique work chain (`ExistingWorkPolicy.APPEND`,
      `AutoCreateScheduler.UNIQUE_WORK_NAME`) regardless of origin -
      `APPEND` makes each new request a dependency of whatever's already
      queued/running in that chain, so WorkManager itself guarantees
      strict one-at-a-time, in-arrival-order execution with no manual
      locking, and it survives process death (WorkManager persists its
      queue) - a burst that arrives right before the app is killed still
      finishes in order once it restarts.
      A failed Firefly call retries (`Result.retry()`, exponential
      backoff) up to `AutoCreateWorker.MAX_ATTEMPTS` (5) - this is the
      actual "WorkManager for retrying failed API calls" use AGENTS.md's
      stack note was written for. Capped rather than left to
      WorkManager's own default indefinite retry: since every message
      shares one `APPEND` chain, a stuck-retrying item would otherwise
      block every later notification's auto-create attempt forever (a
      dead/revoked token would silently stall the entire feature, not
      just one message) - exceeding the cap gives up (`Result.failure()`)
      instead, unblocking the chain, and notifies the user once rather
      than retrying forever in silence. A `Constraints.
      setRequiredNetworkType(CONNECTED)` constraint means a request
      sitting in the chain without connectivity just waits instead of
      burning through those attempts on a guaranteed-to-fail call.
      Can't auto-post without a resolved amount
      (`DraftIssue.LOCAL_AMOUNT_MISSING` still unresolved after the
      exchange-rate fetch) - same "flag it, don't guess" precedent as
      everywhere else that issue shows up; treated as a quiet no-op
      (the message stays in the Messages list for the user to handle by
      hand), not a failure worth retrying or notifying about.
      `MessageStore.add` changed its return type from `Boolean` to
      `CapturedMessage?` (the recorded message, or null if dropped as a
      duplicate) specifically to support this: `AutoCreateScheduler`
      needs the *exact* `timestampMillis` that was actually stored (for
      `MessageStore.setTransactionId`'s later exact-identity lookup once
      a transaction posts), and re-deriving "now" a second time at the
      call site could race against a second notification arriving in
      between and land on a different value than what `add` itself used
      internally - returning the already-computed message avoids that
      race entirely rather than reading it back via a separate lookup.
      **Needs real-device verification** (not yet done): auto-create
      actually firing from a real notification with the app closed/
      backgrounded, the skip-duplicate and success notifications both
      appearing and tapping through correctly, a forced API failure
      actually retrying and eventually giving up after `MAX_ATTEMPTS`,
      and - the one this was specifically built for - two notifications
      posted back-to-back in a way that previously could have raced,
      confirming they're still handled strictly one at a time.
      **Fixed a real cross-app auto-create dedup miss, caught directly
      on-device**: two different rules (a bank app's own rule and a
      wallet app's rule) for the exact same real payment - same
      amount, same description, same source account by name, dedup
      enabled, well within the window - each still auto-created its
      own transaction instead of the second being caught as a
      duplicate of the first. Root cause, traced with the user:
      `transactionDedupHash` used to hash `accountId ?: accountName`,
      preferring a Firefly account ID cached on `ParseRule` when one
      was present. Two *different* rules for the same real account
      don't necessarily agree on whether an ID got cached for it - one
      picked via `RuleEditFragment`'s account picker (had one), the
      other free-typed or saved before the picker existed (didn't) -
      so the two rules' drafts hashed to two genuinely *different*
      values for the identical real account. The user's own call once
      this was diagnosed, more radical than just fixing the hash: drop
      `sourceAccountId`/`destinationAccountId` from `ParseRule`
      entirely (see its own KDoc) rather than try to keep every rule's
      cached ID consistently in sync going forward - a rule always
      posts and hashes by name now. `transactionDedupHash` itself also
      changed to compare by name only, ignoring any account ID
      regardless of source, so the same class of mismatch can't
      resurface via `ui.CreateTransactionFragment`'s own one-shot
      account picker (which still can set one, for posting precision
      on that single transaction - just never fed into the hash).
      **Needs real-device re-verification**: the exact two-rules/
      same-real-payment scenario that exposed this, confirming it's
      now caught as a duplicate.
    - Transaction-level dedup (**done**, manual-creation path only -
      see above): `rules.transactionDedupHash` (pure, unit tested -
      `TransactionDedupTest`) hashes source/destination (by name only -
      **no longer** account id when present, see the real cross-app
      auto-create dedup miss documented further down this history)
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
      **Done, per the user's own design**: the local check above only
      ever catches a transaction *this app* created - a second device
      (or a different app's own direct integration) notifying about the
      same real payment never touches `CreatedTransactionsDb` at all.
      `checkForDuplicate` (shared by the fresh-create and "Create Again"
      paths - see the preemptive-dialog item below) now also asks
      Firefly itself (`FireflyApiClient.searchTransactionsByQuery`, `GET
      /v1/search/transactions?query=...` - Firefly's own operator-search
      syntax, `network.dedupSearchQuery` builds the query string) once
      the local lookup comes up empty, scoped by whichever side of the
      draft has a name (**not** a Firefly account id - a first version
      of this used the account-id-scoped `GET
      /v1/accounts/{id}/transactions?start=...&end=...` instead, and the
      user caught a real bug directly: a rule whose account was typed as
      free text rather than picked via `RuleEditFragment.
      showAccountPicker` has no id, so that version's remote check never
      ran for it at all - confirmed against the user's own real rule via
      a debug build dump (`sourceAccountId=null destinationAccountId=null`)
      - even though posting a transaction by name alone works fine and
      always has. Fixed by switching to the free-text search endpoint,
      scoped by name instead - its query syntax was deliberately left
      unverified/avoided the first time this was built; properly
      verified this time against `OperatorQuerySearch`'s current source
      for the operator names (`amount_is`/`currency_is`/`date_after`/
      `date_before`/`source_account_is`/`destination_account_is`/
      `description_is`) and the `gdbots/query-parser` composer
      dependency's own docs for the quoting syntax, not guessed).
      `rules.remoteCandidateMatches` (pure, unit tested -
      `TransactionDedupTest`) does the exact-match check client-side
      (date window, amount, currency, description-if-the-toggle-says-so,
      account by id-or-name) by reshaping the candidate into a throwaway
      `TransactionDraft` and reusing `transactionDedupHash` directly,
      rather than re-deriving the same comparison a second way - the one
      wrinkle handled there: Firefly assigns a real account id to *every*
      account, including a counterparty this app only ever captured by
      name, so the throwaway draft only keeps the candidate's id for a
      side the draft *also* has one for. Zero matches -> post normally;
      exactly one -> the existing duplicate-confirm dialog; more than
      one -> an *additional* Toast warning
      (`create_remote_duplicates_found`) on top of that same dialog (the
      user's own call - more matches is a stronger signal, not a reason
      to short-circuit the usual flow), using the most recent match as
      the dialog's "found" id. A failed remote call (network error, bad
      connection) falls through to posting normally rather than
      blocking - it's an extra safety net on top of the local log, not
      the primary mechanism.
      **Design decision, not a bug - closed**: `OperatorQuerySearch::
      searchAccount` (backing `source_account_is`/`destination_account_is`)
      first resolves the given value against a *real* Firefly account by
      that exact name, and if it finds none, calls the collector's
      `findNothing()` - forcing the *entire* query to return zero
      results, not just "this term matched nothing" - so a wording
      mismatch against free-typed `sourceName`/`destinationName` can
      zero out an otherwise-correct search. Two code-level fixes were
      proposed and both rejected early on (drop the account terms
      server-side, or resolve to a real account id first) - the user's
      own final call once this was revisited: searching (and matching)
      by *name*, never by Firefly's internal account id, is the correct
      design on purpose, not a gap to patch around. An id is this
      device's/this Firefly instance's own internal identifier - the
      user never sees or types it, and it doesn't survive a restore onto
      a different Firefly instance (see `ParseRule`'s own KDoc for the
      related, already-fixed cross-rule dedup bug id-based *hashing*
      caused) - name is the one thing that's actually portable and
      meaningful on the user's side, so staying name-only here, with its
      `findNothing()` risk accepted, is consistent with that choice
      everywhere else in this app, not an isolated compromise. Partial
      visibility landed anyway as a side effect of the "Check if
      created" `SearchFailed`/`rawCandidates` fix below - a real API
      failure and a real zero/partial match are at least distinguishable
      now. Prompted by a real on-device case: a "Not found" result
      for a transaction that the exact same search query *did* find
      when pasted into Firefly's own web search box. Follow-up test
      ruled out `SearchFailed` - the search actually succeeded and
      returned exactly one raw candidate, which `remoteCandidateMatches`
      then rejected. **Root cause found and fixed - a genuine bug, this
      time**: the per-candidate field dump the "Check if created" fix
      below added showed the candidate's real `destination_name` was a
      Firefly-side placeholder
      ("(no name)") while the draft's own `destinationName` was null (a
      `WITHDRAWAL` rule only ever requires a *source* name -
      `RuleValidation.validateRule` - so destination here was never
      configured at all) - `transactionDedupHash` used to hash both
      sides regardless of kind, so "" (draft) vs. "(no name)" (Firefly)
      never matched. Fixed by making the hash itself kind-aware (see
      `transactionDedupHash`'s own entry above) rather than patching
      around it here - the user's own direct call on *how* to fix it,
      not just that it needed fixing.
      Also simplified separately, per the user's own observation:
      `dedupSearchQuery` used to always emit both `date_after` and
      `date_before` even when they're the identical date (the common
      case - a 5-minute window never crosses midnight) - now a single
      `date_on` instead, falling back to the `date_after`/`date_before`
      pair only when the window genuinely spans two different days.
      **Done, a real false negative the user caught on a foreign-currency
      (HKD Amex) notification**: `amount`/`currencyCode` are only
      this app's own approximate conversion
      (`rules.applyApproxConversion`) whenever a draft has
      `foreignAmount`/`foreignCurrencyCode` set - not a value Firefly's
      own stored amount reliably matches. Both halves of dedup now
      prefer the foreign side whenever it's known: `dedupSearchQuery`
      emits `foreign_amount_is`/`foreign_currency_is` instead of
      `amount_is`/`currency_is`, and `remoteCandidateMatches` compares
      the foreign amount/currency directly instead of the home-currency
      ones (see both functions' own entries above for the exact
      mechanics).
      **Done, the user's own call ("как ты сделал для ошибки, когда
      найденная не совпадает по счету")**: the duplicate-confirm dialog
      used to show only a bare `#<id>` for the transaction it found -
      now always appends the same per-field info
      (date/amount(+foreign)/description/source/destination) the
      not-found dialog already showed for a *rejected* candidate. A
      local-log hit (bare id only) fetches the missing fields itself via
      the new `FireflyApiClient.getTransaction(id)`
      (`GET /v1/transactions/{id}`); a remote-search hit already has
      them for free. See `showDuplicateConfirmDialog`'s entry above.
      **Done, manual-only, the user's own call**: "Check if created"
      (not the create-flow's preemptive check) now retries once without
      description if the first search (with description included, per
      `FireflyPrefs.isDedupIncludeDescription()`) finds nothing - a
      match found only that way is shown with an explicit "description
      didn't match" warning rather than silently treated as certain.
      Deliberately *not* applied to the create-flow callers - there's no
      human in the loop there to judge a weaker match, so silently
      accepting one on retry would risk linking the wrong transaction.
      **Done, prompted directly by the user after hitting a real
      unwanted duplicate**: the fresh-create "Создать" button used to
      run the whole check above only *after* being tapped, so the same
      tap could silently post straight through, silently reuse a found
      transaction, or pop up a second "possible duplicate" dialog - no
      way to tell which in advance, a "blind click" by the user's own
      description. `showTestMatch` now runs `resolveApproxConversion`
      (if needed) then `checkForDuplicate` right after showing the
      match dialog, with the "Создать" button added disabled
      ("Checking…") until that resolves - it only ever becomes tappable
      once its own outcome is already decided, so there's never a
      second dialog popping up as a surprise afterward. "Create Again"
      (already-linked messages) deliberately keeps the old
      click-first/instant behavior, per the user's own call - it's
      already an explicit "yes, another one anyway" decision with
      nothing ambiguous left to resolve.
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
  branches. Hasn't come up for a *user-authored* rule yet (a given bank
  is consistent). **Decided** (the user's own call, for the built-in
  fallback rules below, where it genuinely does come up - "amount
  before the symbol" vs "after" can't be known in advance for a
  generic catch-all): separate rules per position, not named-group
  aliases - simpler, and consistent with how every other format
  difference in this app already gets its own rule rather than one
  clever combined pattern.
- **Done** - built-in fallback rules (`rules.BuiltInRules`,
  `BuiltInRulesTest`): a genuinely blank Rules list (or one where
  nothing matched) used to mean the user had to hand-author a regex
  themselves, which the user judged "practically impossible without
  Googling/AI" for a typical user. Design grounded in a real rules
  export the user supplied (14 rules, 6+ apps) rather than guesswork -
  two things it confirmed directly: the currency/amount capture
  (`[A-Z]{1,3}\$|[A-Z]{3}|[€£$¥]` / `[\d.,]+`) is the user's own,
  reused verbatim, already battle-tested across Amex/C24/Google
  Wallet/Miles & More/N26/Revolut; and direction (withdrawal/deposit)
  keyword detection would **not** have worked - none of the 14 real
  rules use an English/German keyword a generic rule could plausibly
  guess from, which killed an earlier 4-pattern "withdrawal rules +
  deposit rules by keyword" design.
  Shipped as:
  - **2** hardcoded `ParseRule` constants (not 4) -
    `CURRENCY_BEFORE_AMOUNT`/`CURRENCY_AFTER_AMOUNT` ("Default:
    Currency before amount"/"Default: Currency after amount" - named
    "Default: ...", the user's own call, not "Catch-all") - covering
    only the currency-position axis (Java can't express "symbol
    before or after" in one pattern, see the item above), both
    `kind = WITHDRAWAL`, `homeCurrency = ""` (leans on "Default
    account currency" below), blank `sourceAccountName`/
    `destinationAccountName` (expected, not a bug - a built-in rule
    can't know which real Firefly account any app's money should land
    in; the create screen's own required-field validation makes the
    user fill that in by hand), `descriptionTemplate = "{title}"`/
    `notesTemplate = "{body}"` (no `counterparty` capture group at
    all, same shape several of the user's own real Google Wallet
    rules already use).
  - Defined as Kotlin constants, not `RulesDb` rows - can't be
    edited/duplicated/deleted/reordered *structurally*. Only
    persisted bit: one shared `FireflyPrefs.isBuiltInRulesEnabled()`
    flag (default **on**), not per-rule - the two always move
    together. `List<ParseRule>.withBuiltIns(enabled)` appends them
    after every real rule at all 4 real match call sites
    (`ui.MessagesFragment` x2, `ui.CreateTransactionFragment.
    loadForCreate`, `posting.RuleMatching.matchRule`) -
    `RuleEngine`'s own "first match wins" ordering already gives
    "only used if nothing user-defined matched" for free.
    `ui.BackupFragment`/`backup.BackupManager` deliberately don't call
    this - backup/export is user-authored rules only.
  - Shown at the bottom of `RulesListFragment`'s list, always (even
    when disabled - dimmed instead, same convention a disabled real
    rule already gets), visually distinct (light background tint),
    not selectable (long-press is swallowed) - `RulesAdapter.bind`
    branches on `rule.id in BuiltInRules.IDS`. **Revised after a real
    "tap silently flips a setting" bug, the user's own catch on-device**:
    a tap used to toggle the shared enabled flag directly, no
    confirmation - easy to trigger by accident while just browsing
    (which is exactly what happened, and broke auto-create for a
    message that should have hit a built-in rule, with no visible
    cause). A tap now opens a read-only details view instead (pattern/
    templates disabled, no Save/Delete - the one `builtInEnabledSwitch`
    saves immediately on toggle, no Save button needed); the overflow
    menu offers only "Duplicate" (clones the built-in's fields into a
    real, editable `RulesDb` row via the ordinary `RulesDb.add`, then
    opens it in the editor) - move/enable/disable/delete don't apply
    and aren't shown at all, rather than shown disabled.
  - No forced-review restriction - considered, then dropped as the
    user's own follow-up call once `kind` became correctable on the
    create screen made the main risk recoverable anyway. A built-in
    match flows through the exact same auto-create/review dispatch
    every other rule already uses.
  - `kind` is correctable on `CreateTransactionFragment`'s create
    screen, for *any* match, not just a built-in one - but **not** via
    a tap-to-pick `kindText` any more. That shipped first
    (`showKindPicker`), then the user tried it on a real device and
    called it out directly: "нифига пользователю не уперлось.
    неинтуитивно" - reverted in favor of inferring `kind` from the
    account fields instead (`inferKindFromAccounts`): both account
    fields are always pickers in create mode, and whichever one(s)
    the user fills in with a real Firefly asset account determines
    `kind` live, with `kindText` back to a plain label. Refuses to
    save at all if neither side resolves to a real asset account by
    submit time. Edit mode's `kindText` stays exactly as before either
    way - fixed, never recomputed. This is still what makes a single
    `WITHDRAWAL`-only default safe: the user corrects it just by
    putting the real account on the correct side when it's actually a
    deposit, instead of this app trying (and likely failing, per the
    evidence above) to guess direction from text.
  - "Default account currency" on `TransactionCreationFragment`
    (`FireflyPrefs.getDefaultAccountCurrency`/`setDefaultAccountCurrency`) -
    a general `RuleEngine` fallback (`defaultAccountCurrency: String`
    constructor parameter), substituted for `ParseRule.homeCurrency`
    whenever a rule leaves its own blank - not special-cased to just
    the 2 built-in rules, any rule can lean on it. `RuleValidation`'s
    old "Currency can't be empty" check is gone. Picker sourced from
    `FireflyApiClient.listCurrencies()`'s own `enabled` currencies,
    not this app's own locally-maintained `CurrenciesDb` table.
  - **Fixed a real auto-create bug, the user's own catch on-device**: a
    built-in match's draft always has both account sides blank (by
    design, above) - `posting.AutoCreateWorker` used to try posting it
    anyway, which Firefly's API rejects every time, burning through
    every retry (exponential backoff) before a failure notification
    finally showed - from the user's side, adding a debug test message
    that hit a built-in rule looked like total silence for a long
    stretch, with auto-create apparently just not working. Root-caused
    to this, not a deeper bug, once the user noticed on their own
    ("может из-за того что в default счета не заданы нихрена не
    реагирует?"). Fixed with a new `rules.DraftIssue.
    ACCOUNT_SELECTION_REQUIRED` flag (set by `RuleEngine.buildDraft`
    whenever `kind` requires an account side that rendered blank, not
    special-cased to built-ins specifically) that `AutoCreateWorker`
    now checks before ever attempting to post, falling back to the
    same review notification `ReviewNotificationWorker` shows when
    auto-create is off instead.
  - **Still needs real-device verification** - nothing in this
    feature (the list's built-in rows, the kind picker, the actual
    end-to-end match -> create flow for a built-in-matched message) is
    exercised by the JVM unit test suite.
  - Separately raised, not decided: whether to also let *user-defined*
    rules match "any app" (not just the built-in ones, which get this
    for free since they're not edited through the UI at all).
    `RuleEngine` already supports it (`packageNames.isEmpty()`); what's
    missing is UI/validation - `RuleEditFragment` only offers a
    single-app spinner today (not a multi-select), and
    `RuleValidation` currently requires picking one ("Pick an app").
    Would need either a multi-select or an explicit "Any app" option,
    plus relaxing that validation to allow it as a deliberate choice,
    not just "forgot to pick" - a real, if small, UI task, not a
    one-line change. Worth doing as its own follow-up if wanted,
    independent of the built-in rules above.
- **Done**: no way to write a literal `{word}` in a template field
  (description/notes/category/tags/account names) when it isn't meant
  as a macro - e.g. a real Firefly tag named `{User}`. (The save-time
  "Unknown macro(s)" rejection this entry originally also described had
  already been removed separately before this - `validateRule` doesn't
  call `templateMacroNames` at all anymore, see its own comment - so
  the one real remaining problem was `word` silently getting substituted
  when it happened to collide with an actual declared group or fixed
  macro, with no way to say "I mean the literal text, not the macro".)
  `{{User}}` (doubled braces) in the field now renders to literal
  `{User}`, never macro-resolved regardless of whether `User` collides
  with a real group/`FIXED_MACROS` - `renderTemplate`'s regex tries the
  escaped-pair alternative first at each position
  (`\{\{(\w+)\}\}|\{(\w+)\}`, standard leftmost-alternative-wins regex
  semantics), and `templateMacroNames` only ever counts the plain-macro
  capture group, never the escaped one (`MacroTemplateTest`). The rule
  editor's "available macros" hint (`updateAvailableMacros`) gained a
  second, static line (`rule_escape_hint`) pointing at the `{{word}}`
  syntax - the insert-macro picker itself stays as-is, it's about
  inserting real macros, not escaping literals.
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
  a real on-disk v6 database with actual saved rules. Also needs
  re-verification now that it's fixed (see ARCHITECTURE.md's Known
  bugs/gotchas): full backup/restore actually preserving a message's
  linked `#<id>` (moot now for a rule's account id specifically -
  `ParseRule` doesn't carry one at all anymore, see its own KDoc, so
  every restored rule needs the asset account re-picked regardless -
  that's by design now, not something to verify as "preserved"), and
  the dedup log across a real export-then-restore,
  including the "restore onto a fresh install" case (dedup must still
  catch a duplicate whose original transaction predates the restore).
- **Done, needs real-device verification**: `RulesListFragment`'s new
  long-press multi-select (move up/move down/enable/disable/delete
  from the toolbar, see ARCHITECTURE.md's own entry for the full
  design). **Fixed one real bug already found on-device, the user's
  own catch**: bulk delete's confirm callback called
  `clearSelectionAndExit()` but never `reload()` - unlike every other
  selection action here (move/enable/disable/duplicate all call
  `reload()` themselves) - so the list kept showing the just-deleted
  rows until something else happened to trigger a reload (leaving the
  screen and coming back). Still needs checking: the `MenuProvider`
  swap between the normal and selection toolbars via `invalidateMenu()`,
  long-press actually entering selection mode on a real `RecyclerView`
  (not just the adapter logic), and the move-up/move-down ordering
  with a real spread-out *and* adjacent multi-row selection (hand-
  traced on paper, not run on device yet).
- **Decided** (explicit user instruction, not a "revisit if it matters"
  judgment call this time): no table or in-memory store in this app
  should grow unbounded - this is a notification handler, not an
  archive. Retention window capped at one month, user-configurable,
  same idea as `MessageHistoryDb`'s existing `RetentionPrefs` (off/1/3/
  5/7/30 days) - never "forever" by default anywhere.
  - `CreatedTransactionsDb` (the dedup log) is the real gap right now:
    `recordCreated` never purges anything, and `findWithinWindow`'s own
    date-range scoping only affects what a lookup *finds* (the dedup
    window, minutes), not what's *kept* - every successfully created
    transaction's row stays forever. Needs its own retention setting
    and a `deleteOlderThan`-equivalent, called the same places
    `MessageHistoryDb.deleteOlderThan` already is (right after
    `recordCreated`, and when the setting changes) - capped at 30 days
    max per the decision above. **Decided** (the user's own follow-up,
    not started): share one retention setting with message history,
    not a separate one - same `RetentionPrefs` window governs both
    `MessageHistoryDb` and `CreatedTransactionsDb` (and any other
    append-only operational log/structure this app grows later, not
    just these two named now - the user's own framing was "all tables
    and structures", scoped to data *this app itself appends over
    time*, explicitly **not** `RulesDb`/`CurrenciesDb`/`MonitoredAppsPrefs`/
    `FireflyPrefs`/`NotificationPrefs`, which stay config, unaffected -
    see the `RulesDb`/`CurrenciesDb` item further below).
    Also decided, same follow-up - a UI rework of
    `MessageHistoryFragment` itself, not just the scope above
    (**confirmed with the user** - the inversion flagged in an earlier
    pass of this note was real, fixed below):
    - The `RadioGroup` (off/1/3/5/7/30 days) loses its "Don't save"
      option; a separate switch, **"Keep history only in-memory"**,
      takes over that meaning instead - checked means ephemeral
      (today's "Don't save"/`retentionDays = 0`), unchecked means
      persisted for whichever day-count is currently selected (the
      day-count control presumably only enabled/meaningful while
      unchecked - not fully specified yet). Subtitle text under the
      switch, the user's own wording: explains that while in-memory,
      notification history is lost on an app or device restart, but
      that this never affects transactions already created in Firefly
      - i.e. reassures that toggling this is about losing the
      *notification log*, not anything already posted.
    - New-install default: switch **checked** (in-memory only) - this
      is just today's actual default (`retentionDays = 0`) carried
      over unchanged, not a behavior change - an earlier pass of this
      note said "3 days, unchecked" for new installs; the user walked
      that back in favor of keeping the existing default as-is.
    - Migration for an existing install, confirmed with the user:
      switch **unchecked** (still persisted) if the old setting *was*
      "Keep for 1+ day" - i.e. preserves whatever the install already
      had, in either direction. (An earlier pass of this note had this
      backwards and flagged it rather than guessing; the user confirmed
      the preserve-behavior mapping above is the correct one.)
  - `MessageHistoryDb` already satisfies this (max 30 days via
    `RetentionPrefs`) - no change needed there.
  - `MessageStore`'s in-memory list still has no cap - lower priority
    than the two on-disk logs (it's already lost on process death by
    design, see its own KDoc), but a long-running session with high
    notification volume could still grow it unboundedly within one
    process lifetime; worth capping too, not just the disk-backed
    stores.
  - `RulesDb`/`CurrenciesDb` are user-authored config (rules, the
    currency table), not append-only logs - bounded by how many the
    user deliberately creates, a different class of problem, no
    retention needed there.
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
- **Done**: manual transaction creation (`MessagesFragment`'s tap
  handler) used to have no review/edit step - the test-match dialog
  showed the rule's output read-only and posted exactly that, with no
  way to override a field by hand before it went to Firefly.
  `ui.CreateTransactionActivity`/`CreateTransactionFragment` is a new,
  dedicated pre-create screen (visual style started from the rule
  editor's existing account-picker UI, the user's own suggested
  template) where *every* field is editable before posting - the
  user's own call on scope, not just category: amount, currency,
  foreign amount/currency, date/time, source/destination account,
  description, notes, category, tags. Category is picked from
  Firefly's own real category list (`FireflyApiClient.listCategories`,
  `GET /v1/categories`, confirmed against `CategoryTransformer`'s
  current source and `routes/api.php`, not guessed) - previously only
  ever free-text via `ParseRule.categoryTemplate`, and unlike the
  asset-account side (already picker-backed) a typo'd/misspelled
  category went unnoticed since Firefly happily creates one by any
  name. The asset side of source/destination still gets the same
  account picker the rule editor has (`listAssetAccounts`); the
  counterparty side stays free text (Firefly auto-creates it by name,
  same as everywhere else in this app).
  The user's own decision on scope: this screen *replaces* the old
  test-match dialog outright for a fresh match (nothing linked yet,
  Firefly configured) - `MessagesFragment.showTestMatch` now opens it
  directly instead of showing a dialog in that case, *but only after*
  a preemptive `checkForDuplicate` comes up empty first (**fixed
  separately, right after this screen first shipped**: the very first
  version skipped that check entirely and always opened a blank
  screen, so a transaction that genuinely already existed went
  unnoticed - caught directly on-device). A hit shows the usual
  duplicate-confirm dialog instead. The already-linked case (Open/
  Create Again) and the no-match/not-configured cases are unchanged,
  still the original dialog - there's nothing to review/edit in
  either of those.
- **Fixed a real bypass, the user's own catch**: "Create Again" and the
  duplicate-confirm dialog's "Create anyway" both used to post straight
  to Firefly from the matched/found draft with no review/edit step at
  all (`MessagesFragment.startCreate`/`postTransaction`) - the only
  manual paths left that didn't go through `CreateTransactionActivity`
  once it existed. The user's explicit rule, stated directly: bypassing
  the review screen is only ever allowed for `posting.AutoCreateWorker`'s
  background auto-create-by-notification path, never for any manual
  action. Both now open `CreateTransactionActivity` instead of posting
  directly; `postTransaction` itself is gone. Audited the rest of the
  codebase for other direct `FireflyApiClient.createTransaction` callers
  while fixing this - only two remain: `CreateTransactionFragment.post`
  (the review screen itself) and `posting.AutoCreateWorker` (the one
  allowed exception).
- **Done**: when `FireflyPrefs.isAutoCreateEnabled()` is *off*, a rule
  match now surfaces a system notification (`posting.
  ReviewNotifications`, its own `review_match` channel) with an
  app/amount/currency/description summary and a "Create transaction"
  action (and whole-notification tap) that deep-links straight into
  `ui.CreateTransactionActivity`. Scheduled via `posting.
  ReviewNotificationScheduler.scheduleIfNeeded`, called from both
  `BankNotificationListenerService.onNotificationPosted` and
  `AddTestMessageFragment.addTestMessage` right alongside the existing
  `AutoCreateScheduler.scheduleIfEnabled` call - exactly one of the two
  ever does anything for a given message, since they gate on opposite
  states of `isAutoCreateEnabled()`. Resolved the two open questions
  this entry used to raise: it reuses the exact same rule-match +
  amount-resolution logic as `posting.AutoCreateWorker` (extracted into
  shared top-level functions in the new `posting.RuleMatching.kt` -
  `capturedMessageInputData`/`capturedMessageFrom`/`matchRule`/
  `resolveAmountQuietly` - and `AutoCreateWorker` itself now calls these
  instead of its own private copies); and it deliberately does *not*
  run a duplicate check before notifying - that judgment is left to the
  user once they open `CreateTransactionActivity`, which already runs
  its own duplicate check on tapping "Create transaction" there, so the
  worst case is one extra tap before finding out, not a suppressed
  notification for something that turns out not to be a duplicate.
  `ReviewNotificationWorker` is not part of `AutoCreateScheduler`'s
  unique `APPEND` work chain - it never posts to Firefly or writes the
  dedup log, so there's no shared mutable state for two in-flight
  instances to race over.
- **Fixed a real "desync" bug, the user's own catch and report**
  (screenshot: "Check if created" showing "Transaction #X matches" with
  a dead "Open" button): `posting.findDuplicate`'s local-log hit used to
  be trusted blindly - returned as `Found` straight from the cached id,
  with nothing ever confirming Firefly's side still has it. Deleting a
  transaction by hand in Firefly (wrong amount, duplicate, whatever)
  left this app's log none the wiser. A local hit now runs a live
  `FireflyApiClient.getTransaction(id)` first: still there -> `Found`
  (with real fields from that same call, for free); a confirmed `404`
  -> the cached id is stale, falls through to the remote search exactly
  as if the local log had found nothing (`getTransaction`'s `404` is a
  clean, unambiguous "doesn't exist", confirmed against
  `ShowController::show()`'s current source, not guessed); a genuine
  network/server error -> `DuplicateCheckResult.SearchFailed`.
  `SearchFailed`'s meaning changed for *every* caller, the user's own
  explicit instruction: it used to be treated the same as `None`
  everywhere except `checkIfCreated` ("we don't know, so assume no
  duplicate and proceed") - now that it can mean "a local hit exists
  but couldn't be verified", that assumption is backwards, so every
  caller blocks and surfaces the error instead of guessing.
  Also added, same request: `network.withNetworkRetry` (3 attempts,
  exponential backoff, `IOException` only - never on an HTTP error
  response) wraps every read-only Firefly/Frankfurter call
  (`testConnection`/`listAssetAccounts`/`listCategories`/
  `searchTransactionsByQuery`/`getTransaction`/`ExchangeRateClient.
  fetchRate`). Deliberately **not** wrapped around `createTransaction` -
  asked directly rather than assumed: confirmed against Firefly's
  `StoreController::store()` source that there's no `Idempotency-Key`
  mechanism, only a server-side content-based duplicate check that's a
  heuristic on Firefly's own terms, not a transport guarantee - a
  retried POST after a lost response risks silently creating a second
  real transaction, exactly what this app's own dedup exists to
  prevent.
  **Needs real-device verification** (not yet done): deleting a
  just-created transaction by hand in Firefly, then tapping "Check if
  created" on its message, to confirm the stale-id path actually falls
  through to a fresh search instead of showing the dead match again.
- **Fixed a second real instance of the same bug, the user's own
  catch/report (screenshotted)**: the fix above covered `findDuplicate`
  (used by "Check if created" and every create-flow preemptive check),
  but tapping an *already-linked* message never went through
  `findDuplicate` at all - `showTestMatch` branched straight to
  `showMatchedDialog` off `message.fireflyTransactionId` with zero
  verification, so "Open transaction" on a message whose linked
  transaction had been deleted by hand in Firefly just pointed at a
  dead link, with nothing on screen explaining why. Fixed with
  `verifyLinkedThenShow`: live-checks the linked id first, falls
  through to the normal search-then-show/create flow on a confirmed
  `404` (clearing the stale link), stops and shows the error on a real
  verification failure. Added a visible warning for this specific
  case too, the user's own requested wording: `check_linked_
  transaction_gone_warning` ("⚠ Linked transaction #%1$s no longer
  exists in Firefly.", matching the existing `⚠` style of
  `check_found_description_mismatch_warning` rather than the `*` first
  floated, for consistency) - shown whenever a dialog presents a match
  found via search *and* the message had a different linked id
  confirmed gone, via a new `staleLinkedId` parameter on
  `showDuplicateConfirmDialog`, computed by a shared `staleLinkedIdOrNull`
  helper used by both `verifyLinkedThenShow` and `checkIfCreated`.
  **Needs real-device verification** (not yet done): tap an
  already-linked message after deleting that transaction by hand in
  Firefly, both when a replacement match exists (warning + found-dialog
  expected) and when it doesn't (warning-free create screen expected).
- **Fixed two real bugs on first real-device try, the user's own
  catch/report (screenshotted)** - confirming the fix above basically
  works, but caught these on the first live run:
  - `verifyLinkedThenShow` used to clear the stale link
    (`MessageStore.setTransactionId(message, null)`) the instant the
    `404` was confirmed, before any dialog even showed - a plain Cancel
    on whatever came next silently unlinked the message with no action
    behind it. Nothing clears it proactively now; the link only changes
    as a side effect of the user's own choice ("Use Created"/a
    successful create both already overwrite it on their own) - a
    Cancel leaves the stale id as-is, re-verified the same way next
    time.
  - `create_duplicate_confirm_message` ("...Create this one too?")
    didn't match any actual button - the real action is labeled
    "Create anyway". Reworded to "...Create it anyway?" (EN only - the
    Russian string already said the equivalent, "Всё равно создать
    эту?").
- **Fixed three more real bugs on the second real-device round, the
  user's own catch/report (screenshotted)**:
  - `checkIfCreated` ran a full content search even when `message`
    already had a linked id, which can genuinely disagree with the
    known-good one (editing description/notes by hand in Firefly can
    stop it matching this app's own hash/query) - it would then offer a
    *different* transaction as "found", ignoring the id it already knew
    was right. Now checks the linked id first and, if still there, shows
    it directly with no search at all.
  - The tap-path's already-linked dialog (`showMatchedDialog`) showed
    fields re-parsed from the rule instead of the transaction's real
    current data, and never showed the transaction id at all. Fixed
    with `buildCandidateBody`, built from the now live-verified
    candidate - which required adding `notes`/`category`/`tags` to
    `FireflyTransactionCandidate`/`RemoteTransactionCandidate` (parsed
    from Firefly's own fields, confirmed against
    `TransactionGroupTransformer`'s current source), since they weren't
    captured before at all.
  - **The user's own explicit instruction, same report**: "the same
    operation (verifying an id is valid) is implemented in two
    different places that don't even behave the same - make it one
    method." There were actually three: `findDuplicate`'s own inline
    check, `verifyLinkedThenShow`'s separate inline check, and a
    `staleLinkedIdOrNull` helper that threw away a found candidate's
    fields even after fetching them. Consolidated into one
    `posting.verifyLinkedTransaction(prefs, id)` (`LinkedTransactionCheck`
    - `Found(candidate)`/`Gone`/`CheckFailed`), used by all of
    `findDuplicate`, `verifyLinkedThenShow`, the new linked-id-first
    check in `checkIfCreated`, and `fetchCandidateInfo`.
  **Real-device verification done** - ran exactly this (checked a
  message with a still-genuinely-linked transaction), the live
  candidate's real fields showed as expected. Three more bugs caught on
  that same run, the user's own report (screenshotted):
  - `buildFoundTransactionInfo` (the `showDuplicateConfirmDialog` body)
    was still plain text - the one field-summary dialog in this
    fragment not using the bold-label style `buildMatchedBody`/
    `buildCandidateBody` already had. Fixed via a shared `boldField`
    helper all three now use.
  - **The user's own explicit instruction**: "Use Created" shouldn't
    appear when the found transaction already *is* the cached id - at
    that point there's nothing to "use", it's already linked. Now
    hidden whenever `foundId == message.fireflyTransactionId`.
  - A category-less transaction showed "Category: null" (literal text)
    instead of being omitted - an `org.json` quirk (`optString` returns
    the *string* `"null"` for an explicit JSON `null`, not a real
    Kotlin `null`). Fixed with a proper `isNull`-checking
    `optStringOrNull`, applied to every genuinely-nullable split field,
    not just `category_name` - `source_name`/`destination_name`/
    `foreign_currency_code`/`notes` had the identical latent bug, just
    not yet observed in practice (checked: `foreign_currency_code`'s
    string-"null" case couldn't actually surface as visible "null null"
    text, since `foreign_amount` parsing already produced a real `null`
    by accident - `remoteCandidateMatches` only reads
    `candidate.foreignCurrencyCode` at all when the *draft* side already
    has a genuine foreign currency, a narrower edge case than "every
    domestic transaction").
- **Fixed a real "pointless extra step" complaint, the user's own
  catch**: "Create Again" showed its own confirmation dialog
  (`confirmCreateAgain`, "this will post a second transaction, are you
  sure?") *before* the review screen even opened - redundant with that
  screen's own on-submit duplicate check, which finds the exact same
  linked transaction again and asks the real "are you sure" right there
  (`confirmCreateAnyway`), at the point that actually matters. User's
  own suggested fix: drop the separate dialog, move the warning into
  the review screen itself as a banner instead. `confirmCreateAgain` is
  gone; "Create Again" opens `CreateTransactionActivity` directly, which
  now shows `create_tx_already_linked_warning` ("⚠ This message is
  already linked to transaction #X...", red/`?attr/colorError`, bold) at
  the top of the form whenever the message has a linked id - purely
  informational, not a gate, since the real gate already exists further
  down the same flow. Required carrying `message.fireflyTransactionId`
  across the Activity boundary for the first time
  (`CreateTransactionActivity.intent`'s new
  `EXTRA_EXISTING_TRANSACTION_ID`) - previously dropped entirely when
  crossing into this screen, since nothing on that side needed it before
  now.
- **Done**, the user's own request: `showDuplicateConfirmDialog`'s
  "Open" button ("Check if created"/the duplicate-confirm dialogs) is
  now labeled "Open transaction" - same text `showMatchedDialog`'s own
  neutral button already used, for consistency between the two dialogs.
  `create_duplicate_open` (the old, now-unused string) removed from
  both locales.
- **Done**, the user's own request/catch: the per-row overflow menu's
  "Delete" renamed to "Delete notification" - plain "Delete" risked
  reading as deleting the *Firefly transaction*, not this app's own
  captured-notification entry. New `messages_action_delete` string
  replaces the shared `action_delete` on every message-delete surface
  (the menu item, both delete-confirm dialogs, `menu_selection.xml`'s
  multi-select title) - `action_delete` itself untouched, still shared
  by Currencies/Rules where the ambiguity doesn't exist.
  Also added, same request, same menu: "Open in Firefly III" - a direct
  shortcut to `openTransaction` without going through the tap dialog
  first, disabled (not hidden) when the row has no `fireflyTransactionId`
  yet.
- **Fixed two more real bugs, the user's own first real end-to-end
  round** (created a test notification, let auto-create post it, then
  deleted the transaction by hand in Firefly to see what every "it's
  linked" surface does with a confirmed-dead id):
  - "Open in Firefly III"/"Open"/"Open transaction" used to fire
    `ACTION_VIEW` straight at the transaction URL with no existence
    check - Firefly's own web UI doesn't show a 404 for a deleted
    transaction, it silently redirects to the home page, leaving no
    sign from this app's side that anything was wrong. Every "open"
    button now goes through a new `verifyThenOpenTransaction`, which
    runs the same shared `posting.verifyLinkedTransaction` first:
    confirmed gone -> an explicit `open_transaction_gone_*` dialog
    instead of the ambiguous silent redirect.
  - Tapping an already-linked message whose transaction had just been
    confirmed gone (`verifyLinkedThenShow`'s `Gone` branch, falling
    through to a fresh search) still opened `CreateTransactionActivity`
    with the *original* message object - which still carried the
    now-disproven `fireflyTransactionId`, so the review screen's new
    warning banner (v0.103) claimed the message was "already linked to
    transaction #X" for an X this exact flow had just finished proving
    doesn't exist. `checkThenOpenCreateOrShowFound` now builds a
    `cleanMessage` (`fireflyTransactionId = null`) once at the top and
    uses it for every path onward - every call site reachable there
    already knows there's no *currently valid* link by construction
    (either never had one, or just disproved it), so this is always
    safe, not a per-branch judgment call.
- **Done** - the third issue from that same round-trip test, option B
  of the three proposed: "Check if created" could confirm a cached id
  was gone but had no way to actually act on that (its own not-found
  dialog has nothing to clear *to*). New overflow-menu item "Forget
  linked transaction" (`forgetLinkedTransactionIfGone`), enabled
  whenever the row has a `fireflyTransactionId` - independent of
  running "Check if created" first, the user's own pick over a
  dialog-button variant scoped to that one flow. Verifies via
  `posting.verifyLinkedTransaction` before touching anything: confirmed
  gone -> clears the link with a Toast saying so; still there -> leaves
  it alone and says *that* instead, so this can never clear a link
  that's actually still valid.
- **Done**, the user's own catch/requests on the delete confirmation
  dialog: it said "Delete message(s)?" while the menu item/button that
  opens it says "Delete notification" - inconsistent terms for the
  same thing. Renamed to `delete_notification_confirm_title`/
  `delete_notifications_confirm_title` (single vs. multi). Single
  delete now shows which notification is about to go right under the
  title (app label bold, then title/text - same fields the row already
  shows); multi-select's title now carries the actual count instead of
  a generic "(s)".
- **Done**, the user's own follow-up request: tapping an already-linked
  message whose transaction had just been confirmed gone fell through
  to `CreateTransactionActivity` with no warning at all once `cleanMessage`
  (v0.106) correctly stopped it claiming a false "already linked" -
  the real state ("there *was* a link, it's gone now") went unreported
  entirely instead. New `staleLinkedId` parameter on
  `CreateTransactionActivity.intent` (a separate extra, not folded into
  `message.fireflyTransactionId` - that's exactly what was disproven)
  shows the same warning banner with different wording -
  `create_tx_stale_linked_warning`, "⚠ Previously linked transaction #X
  was not found in Firefly" - instead of "already linked".
- **Done**, the user's own request: tapping a message with a *live*
  linked transaction now opens straight into editing that real
  transaction (`CreateTransactionActivity.editIntent`,
  `CreateTransactionFragment.loadForEdit` - see ARCHITECTURE.md's `ui`
  section for the full flow) instead of the old read-only "Open
  Transaction"/"Create Again" info dialog. Fields load from the real
  transaction's current data (`posting.verifyLinkedTransaction`, which
  re-verifies the id itself rather than trusting the caller's earlier
  check - closes a race with the transaction getting deleted in
  between), not a rule-derived guess; saving calls the new
  `FireflyApiClient.updateTransaction` (`PUT /v1/transactions/{id}`,
  confirmed against `UpdateController@update`'s current source - a
  genuine partial update, every other field on the transaction is left
  untouched) instead of `createTransaction`, and skips the duplicate
  check entirely - editing an already-confirmed-existing transaction
  isn't a duplicate question. "Create Again" is gone entirely as a
  result (the user's own call: fix the wrong transaction via edit
  instead of creating a second one and leaving the original behind) -
  `buildCandidateBody`, `startCreate`, `confirmCreateAgain`, and the
  `create_tx_already_linked_warning` string are all deleted. The
  no-link and stale-link tap paths (create-mode review screen, or the
  "Use Created" duplicate-confirm dialog on a fresh match) are
  unchanged, as is "Create anyway" on that same dialog - both were
  explicitly confirmed out of scope for this change. Firefly's own
  `preventUpdateReconciled` validator (rejects source/destination/
  amount changes on a reconciled transaction) isn't pre-detected
  client-side - the user's own choice, asked directly: let the API
  reject it and show the real error rather than guessing which fields
  are locked. `network.FireflyTransactionCandidate`/
  `rules.RemoteTransactionCandidate` also now carry the transaction's
  own `type` (raw string)/`kind` (`RuleKind`, mapped by
  `posting.DuplicateChecker.ruleKindFrom`) - needed so the editor knows
  which account side is the asset side for an existing transaction, not
  just a freshly-matched draft.
- **Fixed two more real bugs, the user's own catch/report**: tapping
  "Create transaction" on a `ReviewNotifications` system notification
  (shown when auto-create is off) created the transaction successfully
  but left the notification sitting in the shade; a second such
  notification for what was really the same payment (seen twice,
  worded differently by two different source apps) walked into the
  create screen with no warning and only found out it was a duplicate
  when Firefly's own server-side `error_if_duplicate_hash` check
  rejected the `POST` outright - a bare error, not this app's own
  "Use Created" choice. See ARCHITECTURE.md's `posting` section
  (`ReviewNotifications`) for the fix: the notification is now
  dismissed explicitly once a transaction actually gets linked
  (`ReviewNotifications.cancel`), not via `setAutoCancel` (which never
  fired for an action-button tap); the "Create transaction" action now
  runs the exact same preemptive duplicate check a list tap already
  runs (`ui.MainActivity.checkMessageIntent`/`MessagesFragment.
  consumePendingMessageCheck`) before deciding whether to show the
  duplicate-confirm dialog or open the create screen, instead of
  skipping straight to the create screen with no check at all.
  **Open question, not yet resolved**: on the user's own second
  real-world test, this app's own preemptive check (local log + remote
  search by account name/amount/date(+description)) still missed a
  duplicate that Firefly's own server-side hash check caught - the two
  checks use different criteria (this app's local/remote search is
  scoped by account *name*, which can genuinely differ between two
  apps' notifications for the same real payment; Firefly's own hash is
  computed server-side on different grounds entirely) and aren't
  guaranteed to agree. Worth deciding: parse Firefly's own duplicate-
  rejection error (does it name the existing transaction's id?) and
  offer the same link-to-existing recovery there too, instead of just
  surfacing it as a bare creation failure.
- **Fixed two more real bugs, the user's own catch on the same
  two-notification round-trip**:
  - `ReviewNotifications.createTransactionIntent` built every message's
    `PendingIntent` with a hardcoded `requestCode = 0` - `PendingIntent`
    identity/caching is `(component, requestCode, action/data/
    categories)`, extras are never part of it, and the target
    (`MainActivity.checkMessageIntent`) has no distinguishing action/
    data of its own, just extras. A *second* review notification's
    action silently reused the *first* one's cached `PendingIntent`,
    extras included - tapping the second notification's "Create
    transaction" opened straight into editing the *first* message's
    already-linked transaction (saved there), while the second
    notification's own entry was never touched (stayed in the shade,
    never linked). Fixed by using the already-unique-per-message
    `notificationId(message)` as the request code too - see
    ARCHITECTURE.md's `ReviewNotifications` entry.
  - "Forget linked transaction" refused to clear a link whenever the id
    was confirmed still *live* in Firefly, with no way out at all - but
    a live link can still be the *wrong* one (a false-positive dedup
    match), and once linked, a tap only opens the editor for that
    transaction, never the create screen. A still-valid link now shows
    an explicit "still linked, forget anyway?" confirmation instead of
    silently refusing - unlinking here never touches Firefly itself,
    only this app's own record.
- Raised by the user, not yet decided/built: dedup at the *notification*
  level, before any transaction exists at all - catch a message,
  always add it to history (unchanged), but if "show notifications" is
  on: match rules first; no match -> no notification at all; a match ->
  compute `rules.transactionDedupHash` the same way `CreatedTransactionsDb`
  already does, check it against a new small "already notified" window
  table (same shape/window as the existing dedup tables, just for
  messages that have been *shown*, not yet *created*) - already shown
  recently -> skip showing a second notification/action for it (the
  message still lands in history either way); not shown yet -> record
  the hash and show the notification (auto-create or the review
  action, whichever's configured) as normal. Confirmed with the user:
  counterparty-name normalization already happens before the hash is
  computed (`RuleEngine.renderOptional`, see its own `whitespaceRun`
  fix above), so this reuses the same, already-normalized hash, not a
  new one; a same-amount/same-counterparty coincidence within the
  window suppressing a second, genuinely separate notification was
  explicitly accepted as a fine trade-off (the message still reaches
  history regardless). Doesn't fix the deeper issue two items above
  (this app's own hash/search can still miss what Firefly's own
  hash catches) - purely a "don't spam two notifications for what's
  probably the same payment" UX layer on top.
- **Done** - the dedicated "Notifications" settings screen, the user's
  own P1 request (see ARCHITECTURE.md's `ui`/`posting` sections for the
  full design). `NotificationSettingsFragment` (new row on the Settings
  list, right after Firefly III settings), backed by a new
  `prefs.NotificationPrefs`:
  - "Show notifications" (`isNotificationsEnabled`, default **on** -
    these notifications already existed and always fired before this
    screen did) gates all four of this app's own notification kinds
    uniformly - `AutoCreateNotifications.notifySuccess`/
    `notifySkippedDuplicate`/`notifyFailed` and
    `ReviewNotifications.notifyMatch` - checked once inside each
    object's own `show`/`notifyMatch`, not at every call site.
  - "Show financial information in notification"
    (`isShowFinancialInfoEnabled`, default **off** - the user's own
    explicit choice, for Google Play's financial-app content
    guidelines) swaps the amount/currency/description-carrying text on
    `notifySuccess`/`notifySkippedDuplicate`/`notifyMatch` for a generic
    one (app label only) when off; disabled (not hidden) in the UI
    while "Show notifications" itself is off, since it has nothing to
    apply to then. `notifyFailed` never carried financial info to begin
    with (just an app label and an error string), so it's untouched by
    this toggle.
  - A button opens this app's system notification settings page
    directly (`Settings.ACTION_APP_NOTIFICATION_SETTINGS` +
    `Settings.EXTRA_APP_PACKAGE`) for channel-level mute/importance/
    sound controls this screen doesn't duplicate.
- **Done** - an AI-Review security finding, the user's own follow-up:
  the Firefly settings screen's token field had a `password_toggle`
  reveal icon that let anyone looking at the phone read the *saved*
  token back out just by opening the screen. Fixed with two layered
  changes (see ARCHITECTURE.md's `ui` section, `FireflyConnectionFragment`'s
  entry, for the full design):
  - What used to be one `FireflySettingsFragment` is now two screens -
    `FireflyConnectionFragment` (base URL, token, "Test connection")
    and `TransactionCreationFragment` (approx-conversion tag,
    auto-create, dedup) - splitting the genuinely secret fields from
    the non-secret ones that have no reason to carry the same
    protection.
  - The token field is now write-once: the real saved value is never
    loaded back into the field at all (a "Token saved - leave blank to
    keep it" hint shows instead), so there's nothing left to reveal by
    opening the screen regardless of the icon - which stays, for
    checking a freshly-*typed* replacement before saving. Leaving the
    field blank on save keeps the existing token untouched; "Test
    connection" falls back to it the same way.
  - A "Protect from screenshots" switch drives `FLAG_SECURE` on that
    one screen - default **on** every time it opens, deliberately *not*
    a persisted setting, so there's no way to leave it off by accident.
    The user's own follow-up request, after asking how to still take a
    screenshot of this screen for documentation purposes: toggle it off
    briefly, it's back on next time the screen opens.
  Also moved in the same pass: "Add test message" is no longer its own
  top-level Settings row - a button on `MessageHistoryFragment` now
  opens it instead, grouped with the one screen it most directly feeds.
- **Fixed four more real gaps on the first real-device look, the
  user's own catch**:
  - The empty token field, on its own, looked indistinguishable from
    "nothing saved yet" - no visible sign of write-once protection at
    all, just emptiness. `FireflyConnectionFragment.updateTokenHint`
    now sets the field's own inline hint to a masked placeholder
    (literal •••• characters + "saved - leave blank to keep it") when a
    token already exists, instead of a separate caption below the
    field that was easy to miss.
  - "Protect from screenshots" sat at the *top* of the connection
    screen, ahead of the fields someone actually opens it to edit -
    moved to the bottom, below Save and behind its own divider.
  - `settings_transaction_creation_title` ("Transaction creation")
    shortened to "Transactions".
  - `settings_add_test_message_subtitle` ("Manually add a message, for
    testing rules later") shortened to "Manually add a message for
    testing purposes" - also the text now shown above the button on
    `MessageHistoryFragment`.
- **Done**, the user's own request: an "Add tags for all transactions
  (comma separated)" field on `TransactionCreationFragment` (new
  `FireflyPrefs.getAllTransactionsTags`/`setAllTransactionsTags`),
  merged into *every* created transaction's tags regardless of which
  rule matched - so tags wanted everywhere don't have to be repeated
  inside every single rule's own tags template, unlike the existing
  "Approximate-conversion tag" (only ever applied to an amount this app
  computed from a fetched exchange rate). See ARCHITECTURE.md's `rules`
  section (`RuleEngine`'s new `allTransactionsTags` constructor
  parameter) for the full design - kept as a plain `String`, not a
  `FireflyPrefs`/`Context` reference, same shape `currencySymbols`
  already uses, so `rules` stays free of any Android/Firefly
  dependency. Placed after the dedup-window field's own divider,
  grouped with "Approximate-conversion tag" (moved there from the top
  of the screen) - the user's own layout call. Both tag fields' own
  explanatory text was moved from the `TextInputEditText`'s own inline
  hint to a plain caption *below* the field, the user's own follow-up
  catch - a long inline hint visibly grew the field's own height for no
  benefit over a caption that only takes space when actually shown.
