# Release v1.9.1 — instant read receipts in conversation list

## Fixed

- **Stale unread badge**: opening a chat marked messages read in the provider,
  but the Home list kept the unread dot until the next full reload.
- Root cause: mark-as-read only UPDATEs rows (no new date), so the
  change-detection shortcut never fired and the list never re-synced.
- Fix: a `ThreadRead` event on the shared bus — the chat screen publishes it,
  Home drops the badge from memory instantly. Zero re-scan, zero delay.

Also in this build:
- README rewritten for the current feature set (API table, cloud backend
  explanation, remote-access options).
- Swipe-to-archive now shows a "Release to Archive" confirmation state.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v1.9.0...v1.9.1
