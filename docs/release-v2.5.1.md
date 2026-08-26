# Release v2.5.1 — cutover hotfixes: lost same-ms sent rows, pinned order

Two regressions found in the v2.5.0 read-cutover, both fixed at the root.

## Fixed

### A just-sent message could stay invisible on Home until restart

The incremental shadow sync re-fetched rows with `DATE >= watermark` but wrote
them with `insertOrIgnore`. An app-sent SMS is persisted in the SAME
millisecond the watermark was set, and when its provider id was not yet in the
shadow the insert was silently ignored — so the Room-first Home list missed
the message while the conversation screen (provider read) showed it: "the
message looks unsent in the list but it's there when I open the chat."

Steady-state sync now **upserts**: known rows are rewritten identically
(harmless), and a previously-missed same-millisecond row is admitted.

### Pinned conversations dropped below unpinned ones

`markThreadReadInShadow` and `repairThreadInShadow` rebuilt the single
conversation projection row via full upsert, resetting `pinned`/`archived` to
false — the first mark-read or repair after pinning pushed the thread off the
top of the list.

Repair paths now write through `ConversationDao.upsertPreservingFlags`
(snippet/date/unread only); full rebuilds keep refreshing flags from the
repositories via `upsertFull`.

## Verification

- `assembleDebug` ✓ · `testDebugUnitTest` ✓ (90 tests, green)
- CI green on the fix commit (5692c6a).

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.5.0...v2.5.1
