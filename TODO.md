# TODO.md

Deferred/known-outstanding work. Add an entry when something is
identified but deliberately not done now (out of scope, needs a
decision, etc.); remove the entry in the same commit that finishes the
work.

- Rule engine core (`parser`/`rules` packages - pattern matching,
  macro templates, `RuleEngine`, `validateRule`) is built and unit
  tested. Still missing, all deliberately out of scope for that step:
  - Persistence (`RulesDb` - plain SQLite, like `MessageHistoryDb`,
    plus an explicit order column since list order is match priority).
  - The Rules settings screen: list (reorder via up/down, not drag -
    see ARCHITECTURE.md), add/edit/delete, duplicate-existing-rule,
    and a "pick an existing pattern" picker sourced from patterns
    already used by other saved rules (no separate pattern-library
    screen/table - it's a derived view over `RulesDb`).
  - "Test match" on the Messages screen: short tap (outside of the
    existing long-press multi-select) opens a dialog running
    `RuleEngine.match` against the tapped message and showing the
    result.
  - The Firefly III API client/actual transaction posting - `rules`
    deliberately has no dependency on this; `ParseRule.accountName`/
    `homeCurrency` are free-text placeholders until it exists (see
    next item).
- Firefly III connection settings screen (base URL + personal access
  token, entered by the user, stored on-device per AGENTS.md's Secrets
  section - never hardcoded, never committed) - not built yet, just a
  future row in `SettingsRootFragment`. Once it exists, `ParseRule`'s
  `accountName`/`homeCurrency` placeholders need a migration path to
  real Firefly account/currency references.
- Real on-device verification of the settings area added in this
  milestone (monitored-apps screen moved under Settings, and
  especially message history: toggle retention on, capture a
  notification, restart the app, confirm it's still there; toggle back
  off, confirm the on-disk file is actually cleared) - see
  ARCHITECTURE.md's Known bugs/gotchas. The original notification
  capture + dedup + fullscreen fix are already confirmed working on a
  real device.
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
