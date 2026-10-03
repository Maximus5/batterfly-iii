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
- Optional fullscreen/edge-to-edge mode as a user setting. The default
  is the classic non-overlapping look: `MainActivity` lets the window
  draw edge-to-edge (`WindowCompat.setDecorFitsSystemWindows(window,
  false)`, required on Android 15+/targetSdk 35+ where the platform
  forces this and the old pre-35 opt-out call doesn't work by itself)
  and then pads the root view by `WindowInsetsCompat.Type.systemBars()`
  itself, reserving the same space the status/nav bars would've taken
  pre-edge-to-edge. This is the real fix, not a reliance on
  `android:windowOptOutEdgeToEdgeEnforcement` (which Google documents
  as a temporary escape hatch that may be removed later) - it works the
  same regardless of what Android does with that flag. A future
  fullscreen *setting* just needs to make the padding conditional
  (skip it, or only reserve space for one bar) rather than redo this
  insets plumbing.
- Only English and Russian are localized
  (`values/strings.xml` and `values-ru/strings.xml`). Default/base is
  English; add more `values-<lang>/strings.xml` as needed, keep
  `strings.xml` in sync across locales when adding new strings.
