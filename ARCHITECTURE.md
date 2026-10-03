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

Current iteration (second milestone, landed): capture + dedup, a
settings area (monitored apps + opt-in local history), still no rule
matching and no Firefly III API calls yet (deliberately deferred, see
TODO.md) - single Gradle module `app`, Kotlin, classic Android Views
(no Compose), minSdk 26.

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
    the optional `onNewMessage` hook is how persistence attaches
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
    renders the current snapshot, newest first.
  - `SettingsActivity` - everything else, as a stack of fragments
    pushed via plain `FragmentManager` back-stack transactions (no
    Navigation Component - the flow is small and linear enough not to
    need it): `SettingsRootFragment` (the menu: monitored apps,
    message history, and later Firefly III connection / processing
    rules / appearance, added one row at a time as each is built) ->
    `AppsFragment` (reused as-is, just hosted here instead of a tab;
    lists every launchable installed app via `queryIntentActivities`
    on `ACTION_MAIN`/`CATEGORY_LAUNCHER`, which needs the `<queries>`
    entry in the manifest to see apps on API 30+; loads on a
    background dispatcher, only once the screen is actually opened,
    not eagerly) or `MessageHistoryFragment` (the retention radio
    group, writes to `RetentionPrefs` and calls
    `RetentionController.applyOnSettingChanged`).
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
`MessageHistoryDb` if retention was left enabled. Rule matching and
the Firefly III API call do not exist yet; they slot in after the
dedup step, consuming the same `CapturedMessage`.

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
- The settings area (this milestone) hasn't had a real-device pass
  yet - unit tests and `assembleDebug` pass, but opening each settings
  screen, toggling retention, and confirming messages actually survive
  an app restart with it on needs manual verification per AGENTS.md's
  workflow rule.
