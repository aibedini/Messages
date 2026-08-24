# Release v1.9.0 — SIM switcher, quick-reply editing & instant cold start

## ⏱️ Instant app start (the Google Messages way)
The conversation list is now snapshotted to disk after every load and
**hydrated instantly on launch** — no skeleton, no "Syncing…" flash when you
open the app. The provider scan runs silently behind the rendered list and
atomically swaps in anything new. First-ever run still shows a brief skeleton
(there is genuinely nothing to show yet).

## 📝 Quick replies: full edit support
- Tap any quick-reply card (or the pencil icon) to **edit both its shortcut
  and text** in a prefilled dialog.
- Renaming a shortcut cleanly replaces the old entry.

## 📶 In-chat SIM switcher
- When two or more SIMs are active, a compact chip above the message input
  shows which line will send ("SIM 1", "SIM 2", or "Default SIM").
- Tap it → pick the line for this and future sends (carrier + number shown).
- The choice persists globally — gateway sends honor it too.

## 📝 Drafts: truly live
- Root cause of drafts not appearing in the list: the app is single-activity,
  so navigating chat → home never fires `onResume`, and the old
  "reload drafts on resume" scheme never ran.
- `DraftRepository` is now a **process-wide reactive store (StateFlow)**:
  the conversation list re-renders the moment a draft changes anywhere —
  no refresh signals, no ordering races.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v1.8.2...v1.9.0
