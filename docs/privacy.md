# Privacy policy

*Butterfly III, an unofficial Android companion for Firefly III. Last updated: 12 October 2026.*

Butterfly III reads payment notifications on your phone and creates transactions
in your own Firefly III server. The developer does not run any server for it and
does not receive any of your data.

## What the app reads

- **Notifications of the apps you select** (for example Google Wallet or your
  bank's app). Android only lets the app read notifications after you grant
  *notification access*. Notifications of all other apps are ignored.
- **The Firefly III address and personal access token** that you type in. The
  token is stored encrypted on the phone.

## What stays on your phone

Captured notifications, your rules, the list of selected apps, currencies and the
settings are stored on your phone only. Saving the message history to storage is
optional. Old entries are deleted automatically after the number of days you
choose. Uninstalling the app deletes all of it.

## What leaves your phone

- **The finished transaction** (amount, currency, date, description, accounts,
  category, tags) is sent to **your own Firefly III server**, at the address you
  entered. The app connects to nothing else for this. The server is run and
  controlled by you, so how it handles the data is up to you.
- **Exchange rates.** For a payment in a foreign currency the app can ask the
  public service `api.frankfurter.dev` for a rate. The request contains only the
  two currency codes and the date. It never contains notification text,
  amounts, descriptions or account names.

The app has no accounts, no advertising, no analytics and no crash reporting,
and it shares nothing with third parties.

## Backups

- **Android Auto Backup** may copy your rules, the list of selected apps and the
  notification, retention and checklist settings to your Google account, as for
  most apps. The message history, the Firefly III address and the token are not
  included.
- **Backup & restore** in the app writes a file only when you ask for it, to the
  place you choose. The file contains your notification history, including the
  text of notifications. Keep it somewhere safe.

## Diagnostic log

It is off by default. When you turn it on, the app keeps short technical notes
on the phone: rule names, times, Firefly III transaction numbers and a short
fingerprint of each amount. It does not write amounts, descriptions or account
names. The log leaves the phone only if you copy it and send it yourself.
Turning the switch off deletes it.

## Your choices

You can withdraw the notification access in the Android settings at any time,
delete the history in the app, and uninstall the app. Transactions that are
already in Firefly III are not deleted by this; remove them in Firefly III.

## Children

The app is not directed at children.

## Changes and contact

If this policy changes, the new version appears on this page with a new date.
Questions: please open an issue on
[GitHub](https://github.com/Maximus5/butterfly-iii/issues), and do not post real
bank details or access tokens there.
