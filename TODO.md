# TODO.md

Deferred/known-outstanding work. Add an entry when something is
identified but deliberately not done now (out of scope, needs a
decision, etc.); remove the entry in the same commit that finishes the
work.

- Rule engine (match captured notifications against user rules) and
  the Firefly III API client/transaction posting - out of scope for
  this milestone, which is capture+dedup only.
- Real on-device verification of the notification listener - no
  `adb devices` target was available in the dev environment when this
  landed. Needs a manual pass on a real phone (grant notification
  access, trigger a test notification, confirm it shows up
  deduplicated) before trusting it.
- No cap on `MessageStore`'s in-memory list size - for personal,
  low-volume bank notifications this is unlikely to matter, but if it
  ever runs for a long time without being restarted it will grow
  unbounded. Revisit if that turns out to matter in practice.
- No foreground service - chosen deliberately (no persistent
  notification icon) at the cost of the captured-messages list being
  more likely to be lost if Android kills the backgrounded app under
  memory pressure, not just on reboot.
- `ic_launcher` is a placeholder vector icon, not a real app icon.
