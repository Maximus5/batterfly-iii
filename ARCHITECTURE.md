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

Nothing is persisted outside the device; Firefly III itself (not part
of this repo) is the system of record for the resulting transactions.

Current iteration (first milestone, landed): capture + dedup only -
listen to notifications from a user-picked set of apps, collapse
duplicates in memory, show them in a list. No rule matching, no
Firefly III API calls yet (deliberately deferred, see TODO.md), and no
persistence of any kind - single Gradle module `app`, Kotlin, classic
Android Views (no Compose), minSdk 26.

Package: `io.github.maximus5.ffnotifyimporter`.

## Module layout

Single `:app` module, packages under
`io.github.maximus5.ffnotifyimporter`:

- `data` - `CapturedMessage` (app/title/text/timestamp) and
  `MessageStore`, the in-memory, time-windowed dedup store. Pure
  Kotlin, no Android dependency, so it's unit-testable
  (`MessageStoreTest`) without instrumentation. `MessageStore.shared`
  is the one instance used by both the listener service and the UI.
- `prefs` - `MonitoredAppsPrefs`, a thin `SharedPreferences` wrapper
  holding the set of package names the user chose to monitor. This is
  configuration, not notification content, so (unlike captured
  messages) it is persisted across restarts/reboots on purpose.
- `notifications` - `BankNotificationListenerService`
  (`NotificationListenerService` subclass): for every posted
  notification, checks `MonitoredAppsPrefs`, pulls `EXTRA_TITLE`/
  `EXTRA_TEXT` from the notification extras, and if either is
  non-empty, resolves the app's current label via `AppLabelResolver`
  and calls `MessageStore.shared.add(...)`. Requires the user to grant
  notification-listener access manually in system settings - there is
  no runtime permission dialog for this, only
  `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`.
- `ui` - `MainActivity` hosting a `ViewPager2` with two tabs:
  - `AppsFragment` - lists every launchable installed app (via
    `queryIntentActivities` on `ACTION_MAIN`/`CATEGORY_LAUNCHER`, which
    needs the `<queries>` entry in the manifest to see apps on API
    30+), with a checkbox per app that writes straight to
    `MonitoredAppsPrefs`. Also shows a banner + button to jump to
    notification-listener settings when access hasn't been granted.
  - `MessagesFragment` - subscribes to `MessageStore.shared` while
    visible (`onStart`/`onStop`) and renders the current snapshot,
    newest first.

## Data flow

`NotificationListenerService.onNotificationPosted` (only for monitored
packages) -> extract title/text -> `MessageStore.add` (dedups
app+title+text within a 15-minute window, in-memory only) ->
`MessagesFragment` re-renders via the store's listener callback. Rule
matching and the Firefly III API call do not exist yet; they slot in
after the dedup step, consuming the same `CapturedMessage`.

## Known bugs / gotchas

- Not yet verified on a real device/emulator - this environment had no
  `adb devices` target when the first milestone landed. Unit tests and
  a full `assembleDebug` pass, but the notification listener itself
  needs a real-device check (per AGENTS.md's workflow rule) before
  trusting it.
- `NotificationListenerService` access, once granted, can get silently
  revoked by some OEM battery-optimization/"app hibernation" features.
  No detection for that beyond the existing "is access enabled" banner
  check on resume.
