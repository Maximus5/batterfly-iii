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
    deliberately has no dependency on this; `ParseRule.accountName`/
    `homeCurrency` are free-text placeholders until it exists (see
    next item).
  - A way to reorder rules other than one-step-at-a-time up/down
    buttons (fine for a handful of rules; revisit - e.g. drag handles
    - if the list grows long enough to make that tedious).
- Firefly III connection settings screen (base URL + personal access
  token, entered by the user, stored on-device per AGENTS.md's Secrets
  section - never hardcoded, never committed) - not built yet, just a
  future row in `SettingsRootFragment`. Once it exists, `ParseRule`'s
  `accountName`/`homeCurrency` placeholders need a migration path to
  real Firefly account/currency references.
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
