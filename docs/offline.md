# Using Butterfly III without a connection to your server

Your Firefly III server does not have to be reachable when a payment
notification arrives. Butterfly III saves the notification on your phone first.
You can create the transaction later, when the server is available again.

This is useful when your server is at home and you are:

- on a trip, with no way to reach your home network;
- on mobile data, while the server is only open inside your home network;
- somewhere with a bad connection.

## Set it up

1. Open **Settings** and turn on **Save history to local storage**.
   Without it, saved notifications are kept in memory only.
   They are lost if Android stops the app or the phone restarts.
2. Choose how long to keep the history. The default is short.
   For a long trip, raise it, for example to 30 days.
3. If you will be away from your server for a long time, turn off
   **Create transactions automatically**.
   Otherwise the app tries to create each transaction as soon as the
   notification arrives, and fails while the server cannot be reached.
   With automatic creation off, you get a notification for each payment,
   and nothing is sent anywhere.

## Create the transactions later

When your phone can reach the server again (for example, when you are back in
your home network), use either of these ways:

- Tap the notification that Butterfly III showed for the payment.
- If you swiped the notification away, open Butterfly III. Every saved
  notification is in the list. Tap a message to create its transaction.

Before it creates anything, the app checks whether the transaction already
exists in Firefly III. If you entered it by hand in the meantime, or another
app reported the same payment, you will not get a duplicate.

## What if automatic creation was on?

Then the app tries when the notification arrives. If the server cannot be
reached, it retries later on its own. The message stays in the list in the
meantime. You can also open it and create the transaction yourself.

## Good to know

- The saved history is only on your phone. Nothing is sent anywhere.
- Messages older than the history period are deleted.
  Create the transactions before that, or raise the period.
- The saved notification text is stored on the phone without encryption beyond
  the normal protection of Android's app storage.
  Do not keep it longer than you need.

## Keep the app running in the background

Butterfly III can only save a notification while Android lets it run. If the
phone stops the app after weeks without use, payments are missed. To avoid
this:

- In **Settings → Notifications**, scroll to *Keeping the monitoring running*.
  If the app is battery-optimized or may lose its permissions, a warning with
  a button is shown there. Follow it.
- On Samsung phones, make sure Butterfly III is not in *Sleeping apps* or
  *Deep sleeping apps* (Settings → Battery → Background usage limits). The app
  cannot check this for you.
- Do not use *Force stop* on the app. After that, Android does not deliver
  notifications to it until you open it again.
