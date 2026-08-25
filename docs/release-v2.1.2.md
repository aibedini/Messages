# Release v2.1.2 — list state always reconciles after app restart

## Fixed

The "ghost unread" bug: reply to someone (or read a chat), close the app,
reopen — and the conversation STILL showed as unread / with the old snippet,
even though the notification had shown everything live. The two screenshots
(09:16 vs 09:22) show the same inbox disagreeing with itself across restarts.

### Root cause
On resume the Home list only re-synced when `hasProviderChangedSince(newest)`
said there were NEWER rows. Read-state changes (mark-as-read) don't create
newer rows — so after an app restart the resume refresh was skipped entirely
and the stale disk-cache snapshot stayed on screen, contradicting what the
user had actually done minutes earlier (and what the notification had shown).

### Fix
Resume now ALWAYS runs `silentRefresh` — a single cheap threads-table query
followed by an atomic in-place swap. The cached list still paints instantly;
it just gets corrected within one frame of resume instead of never.
`hasProviderChangedSince` remains for the ContentObserver path where it makes
sense.

Result: what you did before closing the app is what you see after opening it.
No ghost unread dots, no stale snippets, no confusion.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.1.1...v2.1.2
