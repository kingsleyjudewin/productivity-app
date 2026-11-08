# Screen-time monitoring: how it works and what it cannot do

## Method
- Usage comes from `UsageStatsManager.queryEvents` (activity resumed/paused, screen on/off, keyguard,
  shutdown). Only package name, class name, event type and timestamp are read.
- Totals are **recomputed from events**, never accumulated by a timer, so restarts, killed services and
  late workers cannot double-count or drift. See `UsageAggregator` and `ScreenTimeEngine`.
- The logical day starts at the user's configured reset time (`DayKeys`), the same as tasks and money.

## When it runs
- Foreground service (`MonitorService`): only while the screen is on, ~20 s between checks, shortening
  to land on a limit. Screen off means no work. Its notification is silent and disappears if usage
  access is revoked.
- Backup alarm armed for the predicted limit moment, in case the OS kills the service.
- WorkManager every 15 min reconciles with Android's own records and tries to restart the service.
- Boot / app update / time change restore the service.

## Limits (be honest with yourself about these)
- Vivo Funtouch OS may kill the service or block autostart. Set DAYZERO to "No restrictions" and allow
  Autostart (Setup screen). Without that, alerts can be late or missing. Not verified on a Vivo V30.
- Android 12+ can refuse to start a foreground service from the background; it then resumes the next
  time the app is opened.
- Exact alarms are off by default on Android 14+; the backup alarm then becomes inexact (minutes late).
- Interventions are heads-up notifications. Android 10+ stops background apps opening their own
  screens, and a full-screen overlay needs "Draw over other apps". No overlay is implemented.
- Usage is per app, not per screen inside an app.

## Instagram Reels: investigated, deliberately not implemented
`UsageStatsManager` cannot see inside an app. The only route is an `AccessibilityService` inspecting
Instagram's view tree (e.g. a view id for the Reels pager). That means reading window content of another
app, depends on Instagram's internal ids that change between releases, and cannot be guaranteed to work.
It is also the most privacy-sensitive feature in the spec. So it is not built; app-level monitoring is
the supported feature. If added later it must be opt-in, read only view ids (never text, passwords,
messages or keystrokes), take no screenshots, and be labelled experimental.
(This conclusion comes from how the platform works, not from testing on a device.)
