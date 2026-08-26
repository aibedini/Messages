# Release v2.4.0 — durable SMS status callbacks, honest sync progress

## Added

### Manifest-declared `SmsStatusReceiver` — status survives process death

Outgoing SMS SENT/DELIVERED callbacks used to live in runtime receivers
registered by `SmsSender`. If the user closed the conversation (or Android killed
the process) between tapping Send and the modem callback, the broadcast had no
receiver and the bubble stayed on "Sending…" forever.

Callbacks are now delivered to a manifest-declared `SmsStatusReceiver` via an
explicit `PendingIntent` (`exported=false`), so results arrive no matter what
happened to the UI that initiated the send:

- **Multipart aggregation**: each part of a multi-part SMS carries its own
  PendingIntent tagged with `(rowId, partIndex, partCount)`; the receiver counts
  completed parts in `sms_status_callbacks` prefs and only leaves PENDING when
  the LAST part resolves.
- **Sticky failure**: once any part reports failure, later successes from other
  parts can never flip the row back to sent/delivered.
- **DELIVERED stamps `DATE_SENT`** so "Delivered HH:MM" shows the real delivery
  time.
- PendingIntents are now `FLAG_IMMUTABLE` (were MUTABLE) with a collision-free
  request code including the part index.

### A SENT PendingIntent is always attached

Previously the SENT callback was only registered when delivery reports were
enabled — with reports off there was no way to observe modem/SIM failures at
all. The row is now always written as STATUS_PENDING and always moved out of it:
SENT → STATUS_NONE, DELIVERED → STATUS_COMPLETE (opt-in), any part failed →
STATUS_FAILED. Delivery reports stay opt-in because carriers may charge for them.

### Visible sync progress on Home and in long chats

- The compact "syncing" banner now also runs over an already-visible cached list
  (with the same 250 ms anti-flash delay) instead of only on first-ever load,
  and the progress label reflects the real phase (threads / sms / mms).
- Scrolling up in a conversation shows a small top-of-list
  "Syncing older messages…" indicator while older pages load (new
  `conv_syncing_older` string, EN + FA).
- Home list items animate placement changes (spring, no bounce) and fade
  in/out, so undo/restore and pin/uninstall reorders read as motion instead of
  teleporting rows.

## Fixed

- **Status transitions were invisible to tail refresh**: `loadTail()` queried a
  strictly-newer-than-last-date window, but a status callback updates the row
  IN PLACE without touching its DATE — so PENDING → SENT/DELIVERED/FAILED never
  showed until a full reload. The pager gained `loadSmsRowsById()`, refresh now
  re-reads visible sent rows by provider id, and `ThreadMerge.mergeTail` adopts
  the provider copy whenever id AND date match instead of ignoring it.
- **Sent rows wrote a fake `DATE_SENT`** equal to DATE at insert time; it is now
  left empty until SENT/DELIVERED actually happens.
- The optimistic insert is created with `STATUS_PENDING` so it renders as
  "Sending…" immediately and is reconciled by the id-based refresh above.
- New unit test covers the merge rule: same id + date, status 32 → 0 must win.

## Verification

- `assembleDebug` ✓ · `testDebugUnitTest` ✓ (86 tests, green)
- Process-death delivery of manifest receivers needs a device test; unit level
  covers the merge/aggregation logic.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.3.0...v2.4.0
