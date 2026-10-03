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
- Optional fullscreen/edge-to-edge mode as a user setting. Default is
  now non-fullscreen (`WindowCompat.setDecorFitsSystemWindows(window,
  true)` in `MainActivity`, added because targetSdk 35+ forces
  edge-to-edge by default and that broke tab switching - see next
  item); if an edge-to-edge mode is ever added as an opt-in setting,
  it needs its own insets handling, not just removing that call.
- Tab switching (`ViewPager2`/`TabLayout` in `MainActivity`) was
  broken in edge-to-edge/fullscreen mode - the status bar and
  gesture-nav areas overlapped the tab strip and the swipe area and
  ate touches. Fixed for the current non-fullscreen default; needs a
  real fix (proper `WindowInsets` padding on the tab layout and
  view pager) before any future fullscreen setting re-enables
  edge-to-edge.
- Only English and Russian are localized
  (`values/strings.xml` and `values-ru/strings.xml`). Default/base is
  English; add more `values-<lang>/strings.xml` as needed, keep
  `strings.xml` in sync across locales when adding new strings.
