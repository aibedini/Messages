# Release v2.0.2 — conversation list updates the moment you send

## Fixed

After sending an SMS inside a chat, the Home conversation list kept showing
the OLD snippet/date until some later refresh — you only saw the update after
leaving and re-entering, or sometimes never until restart.

### Root cause
The list refreshed via the ContentObserver (300 ms debounce) or on resume,
but the single-activity back stack means going back from a chat often doesn't
re-trigger resume — and the threads-table observer can miss our own writes.

### Fix
- New `OutgoingSent` event on the shared event bus: `SmsSender` fires it right
  after persisting a sent message; `HomeViewModel` moves that thread to the
  top with the new snippet/date instantly.
- Applies to manual sends **and** gateway/API sends (`sendForResult`),
  plus outgoing image messages ("🖼" placeholder snippet).
- The ContentObserver silent refresh still reconciles afterwards as before.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.0.1...v2.0.2
