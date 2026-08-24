# Release v1.8.1 — hotfix: crash on launch

## What broke

v1.7.0 introduced `AppCompatActivity` (required by the biometric app lock),
but the manifest theme stayed a **platform Material theme**. AppCompatActivity
refuses to start without an AppCompat theme, so the app crashed instantly at
launch on every device — a cold "open → close" loop.

```
IllegalStateException:
You need to use a Theme.AppCompat theme (or descendant) with this activity.
```

## Fixes

- `Theme.Messages` now descends from `Theme.AppCompat.DayNight.NoActionBar`
  (keeps dark/light following the system).
- Defensive guard in `MainActivity.onCreate`: even if the theme ever drifts
  again, the activity logs and recovers instead of crashing.

No functional changes — everything from v1.8.0 (drafts) is intact.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v1.8.0...v1.8.1
