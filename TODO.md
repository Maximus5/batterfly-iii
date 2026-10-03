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
  now non-fullscreen, via two things together: the
  `android:windowOptOutEdgeToEdgeEnforcement` theme attribute
  (`values/themes.xml`) - the actual opt-out on Android 15+/targetSdk
  35+, where edge-to-edge is forced and the old
  `WindowCompat.setDecorFitsSystemWindows(window, true)` call alone
  (still in `MainActivity`, harmless, matters pre-35) is not enough on
  its own. Google documents `windowOptOutEdgeToEdgeEnforcement` as a
  *temporary* escape hatch that may stop working in a future Android
  release - if it does, this whole area needs a real fix (proper
  `WindowInsets`-based edge-to-edge support, not another opt-out), not
  just finding a new flag. If an edge-to-edge mode is ever added as an
  opt-in setting, it needs that same proper insets handling - see next
  item.
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
