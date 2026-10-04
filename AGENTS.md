# AGENTS.md

Guidance for an agent working in this repository.

## Project

FF-Notify-Importer - an Android app that listens to notifications from
other apps (banks) via `NotificationListenerService`, matches them
against user-defined rules, and creates transactions in a Firefly III
instance (a personal finance server the user already runs elsewhere -
not part of this repo) via its REST API.

Stack: Kotlin + Gradle, standard Jetpack components as needed
(`NotificationListenerService`, WorkManager for retrying failed API
calls, Room if rule/log storage grows past SharedPreferences). Pure
on-device client - no server component, no Docker, nothing deployed
from this repo.

- Full architecture, module layout, parsing-rule design, and real bugs
  found while building it: **`ARCHITECTURE.md`**. Keep it up to date -
  update it whenever a module, data flow, or rule format changes, in
  the same commit as the code change.
- Deferred/known-outstanding work: **`TODO.md`**. Add an entry when
  work is identified but deliberately not done now (out of scope,
  needs a decision, etc.); remove it in the same commit that finishes
  the work.

Read both before making non-trivial changes, once they exist.

## Workflow

- `./gradlew test` (unit tests) must pass before any change is
  considered complete. Run `./gradlew connectedAndroidTest` too when
  the change touches the notification listener, parsing pipeline, or
  anything else that behaves differently on a real device vs. the JVM
  unit-test sandbox.
- Notification-parsing rules are the core risk area: test against
  real-ish captured notification text (varying wording/formatting
  across bank apps and locales) before trusting a couple of hand-picked
  fixtures - real bank notifications tend to have more format variety
  than a curated sample covers.
- For a UI or notification-listener change, don't just trust unit
  tests - install the app on a real device (or emulator with a way to
  post test notifications) and confirm it actually fires on a live
  notification and creates the expected Firefly III transaction.

## Git conventions

- Commit author for this repo is set locally to `Claude <ConEmu.Maximus5@gmail.com>`
  (`git config user.name`/`user.email`, repo-scoped).
- Atomic commits: one distinct concern per commit (a module + its tests,
  or one bug fix + its regression test), not batched turns.
- Once a remote/collaborators exist, treat history as immutable - no
  rewriting published commits.
- Do not add a `Claude-Session:` (or similar session-URL) trailer to
  commit messages or PR descriptions - whether that link is safe to
  expose isn't established, and this repo's history can end up public.
  `Co-Authored-By: Claude ...` alone is fine.

## Secrets

- The Firefly III base URL and personal access token are entered by the
  user at runtime in the app's own settings screen and stored on-device
  (e.g. `EncryptedSharedPreferences`). They never live in source, never
  get committed, and are never hardcoded as a default/fallback value
  anywhere in the code.
- Never hardcode credentials/tokens/real server URLs in source,
  comments, commit messages, or test fixtures. Use obviously generic
  placeholders (e.g. `https://firefly.example.com`, `<token>`) in code,
  docs, and commit messages alike.
- If a signing keystore or `local.properties`-style file is ever needed
  for release builds, it stays gitignored and out of this tree's
  history - same rule as any other secret.
