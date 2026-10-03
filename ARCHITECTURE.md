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

## Module layout

Not yet established - no code exists in this repo yet. Fill this in as
modules/packages are created (e.g. notification listener, rule engine,
Firefly III API client, settings UI).

## Data flow

TBD once the notification -> rule -> API-call pipeline exists.

## Known bugs / gotchas

None yet - this section accumulates real issues discovered while
building the app (e.g. notification text quirks per bank, Android
version differences in listener behavior), not hypothetical ones.
