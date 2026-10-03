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
- `description`/`notes` are macro templates (`{groupName}`/`{title}`/
  `{body}`), not a single implicit `description` regex group - lets a
  rule reference the raw notification text even for fields its pattern
  didn't capture.

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
  - `parseAmount(raw, decimalSeparator)` (`AmountParserTest`).
- `rules` - the Firefly-*flavored* domain, still Firefly-API-free
  (string placeholders - see Overview):
  - `ParseRule` - one parsing template scoped to one account/app
    combination (`accountName`/`homeCurrency`/
    `counterpartyAccountName` are placeholders; `pattern` matched
    against `parser`'s combined-input convention;
    `descriptionTemplate`/`notesTemplate` are macro templates).
  - `Groups` - the named-group names `rules` knows to look for
    (`amount`, `currency`, `foreignAmount`, `foreignCurrency`,
    `counterparty`) - a convention `parser` has no opinion on.
  - `renderTemplate`/`templateMacroNames` (`MacroTemplateTest`) - `{x}`
    macro substitution for description/notes, using the match's
    captured groups plus always-available `{title}`/`{body}`.
  - `TransactionDraft`/`DraftIssue`/`ParseOutcome`/`NoMatchReason` -
    what a match produced (or why it didn't match), not yet a Firefly
    API request.
  - `RuleEngine.match(message)` (`RuleEngineTest`) - the
    first-rule-in-order-that-matches algorithm described above.
  - `validateRule(rule)` (`RuleValidationTest`) - checks a pattern
    defines `amount`, transfer rules have a counterparty, and every
    macro referenced in the templates is either a declared group or
    `title`/`body`. The rule editor calls this before letting a save
    through.
  - `RulesDb` - plain SQLite (no ORM), one row per rule plus an
    explicit `sort_order` column - the only priority mechanism
    `RuleEngine` uses, so `moveUp`/`moveDown` just swap two rows'
    `sort_order`. `duplicate` shifts every later row's order up by one
    and inserts the copy right after the original, in a transaction.
    `distinctPatterns()` is the "pick an existing pattern" source for
    the rule editor - a derived query over this same table, newest
    first, one entry per distinct pattern; no separate library table.
    `RulesDb.getInstance(context)` is the one shared instance (same
    reasoning as `MessageHistoryDb.getInstance`). Not unit tested -
    like `MessageHistoryDb`, it's thin glue over real
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
    result (rule, account, amounts/currencies, counterparty,
    rendered description/notes, issues) in a dialog - "test match".
  - `SettingsActivity` - everything else, as a stack of fragments
    pushed via plain `FragmentManager` back-stack transactions (no
    Navigation Component - the flow is small and linear enough not to
    need it). `SettingsRootFragment` is the menu (monitored apps,
    rules, message history, add test message, and later Firefly III
    connection / appearance, added one row at a time as each is
    built), linking to:
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
      `RuleEditFragment` with no rule id, to create one.
    - `RuleEditFragment` - one form for both create (`ruleId == null`)
      and edit, via `RuleEditFragment.newInstance(ruleId)`. The apps
      picker is a multi-choice `AlertDialog` sourced from
      `MonitoredAppsPrefs` (not a full re-enumeration of installed
      apps - a rule can only ever match what the listener service
      already captures). The pattern field has a "pick existing
      pattern" button backed by `RulesDb.distinctPatterns()`, and a
      live "available macros" hint computed from
      `parser.namedGroupNames` on whatever's currently typed. Save
      runs `validateRule` and shows every error in one dialog before
      writing anything.
  - `SystemBarInsets.kt` - `useClassicSystemBars(root)`, the shared
    edge-to-edge-but-padded-back-to-classic logic both activities call
    (see Known bugs/gotchas below for why it's done this way).
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
