# Release v2.0.1 — no more spurious "Reading multimedia" scan

## Fixed

Opening **any** conversation (even one with no MMS at all) showed a full-screen
"Reading multimedia… 86/86" spinner.

### Root cause
When a conversation was opened by phone (threadId unknown yet), the MMS query
ran with **no filter** — scanning every MMS row on the device — and only then
filtered the results in memory. The progress callback reported that device-wide
scan, which is what you saw spinning.

### Fixes
- When a thread id is known, MMS rows are now filtered **at the provider
  level** (`THREAD_ID = ?`) — one indexed query instead of a full-table scan.
- The unfiltered fallback path (rare: brand-new chats before any send) now
  scans **silently** — SMS loading remains the visible progress.
- Loading labels are localized too ("خواندن پیام‌ها / Reading messages").

No functional changes otherwise — v2.0.0 features are intact.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.0.0...v2.0.1
