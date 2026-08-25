# Release v2.1.4 — live consistency: notification, conversation and Home list agree

## Fixed

The app felt non-live and confusing:

- Opening a chat and coming back left the Home list stale ("what did it
  cache? what changed?") because chat → home never passes through
  `Activity.onResume` — the list had no guaranteed reconcile on return.
- Every open/close cycle showed a DIFFERENT set of messages inside a
  conversation: `refresh()` replaced the whole windowed list (newest 40)
  with a full provider read, so the visible content kept changing shape.
- Pull-to-refresh existed in the chat but its spinner never stopped, and
  the Home list had none at all.
- Messages arrived in the notification before the conversation screen,
  with no deterministic catch-up path.

### Root cause & fix

Updates are now MERGE-based instead of replace-based:

- New pure helper `ThreadMerge` (`mergeTail`, `prependOlder`, `tailWindow`)
  folds freshly-queried rows into the visible list — dedup by id AND by
  (body, time-proximity) so provider-confirmed copies of optimistic sends
  collapse into one bubble. History already on screen never disappears or
  reshuffles.
- `ConversationViewModel.refresh()` now runs a cheap tail query
  ("rows newer than the newest shown") via `ThreadPager.loadNewerSince`
  and merges — no more full-list replacement. The pull-to-refresh spinner
  binds to a real `isRefreshing` state.
- `loadOlderMessages()` prepends with overlap dedup, so scroll-up after a
  refresh can't duplicate rows.
- `ConversationViewModel.onCleared()` fires `SmsEventBus.notifyResume()`,
  guaranteeing the Home list reconciles the moment you leave a chat.
- Home list gained pull-to-refresh (silent atomic swap — never clears).
- An open chat now also receives outgoing-sent events instantly (e.g.
  quick-reply from a notification), not only after the observer debounce.

Unit tests: new `ThreadMergeTest` (5 cases) covers merge idempotency,
optimistic-row collapse, overlap dedup and tail windowing.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.1.3...v2.1.4
