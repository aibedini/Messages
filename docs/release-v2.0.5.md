# Release v2.0.5 — smooth list updates (atomic snapshot swap)

## Improved

Conversation-list updates were "jumpy": a refresh cleared and re-added rows in
separate frames, so items visually blinked/jumped instead of gliding into
their new positions.

### Fixes
- `applySwap` now runs inside a Compose **snapshot transaction**
  (`Snapshot.withMutableSnapshot`): the whole before→after change lands in ONE
  recomposition, so keyed rows keep identity and move smoothly.
- `LazyColumn` items got stable namespaced keys + explicit `contentType`,
  improving item reuse and diffing during swaps.

### On "why no Room database?"
Deliberate architecture: Android's SMS/MMS provider IS our database. Adding a
Room mirror would create two sources of truth to reconcile on every change.
Instead we use layered caching:
1. `ThreadMessageCache` (in-memory, per-thread, SWR) → instant chat opens
2. `ConversationCache` (disk snapshot) → instant cold-start list
3. Provider queries → background reconciliation

If list sizes grow very large, Room becomes worth its sync cost — tracked as
a future option, not needed at current scale.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.0.4...v2.0.5
