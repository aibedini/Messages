# Release v2.5.0 — the local shadow becomes a read source (read-cutover)

## Changed

### Home list and chat open now read from Room when the shadow is ready

Phase 2's read-cutover is wired. The gate is structural, not a setting: reads
cut over to the local database only once BOTH sync sources report
`backfillComplete` (`TelephonySyncCoordinator.isShadowReady()`). Until then —
and on any failure — every path behaves exactly as before.

- **Home cold start**: one cheap incremental sync cycle (bounded window reads,
  not a full scan) followed by a single indexed query over the `conversations`
  projection replaces the whole-device Threads scan.
- **Home warm refresh** (resume / pull-to-refresh / observer): same
  sync-then-read-locally path; the legacy provider rebuild remains as fallback.
- **Chat open with no in-memory cache** (fresh process): paints instantly from
  `pageForThread` / `newestForAddress` instead of showing a spinner — including
  the phone-only route before a thread id is resolved.

### The shadow mirrors app-initiated operations

A read cache that disagrees with the provider is worse than no cache, so the
operations the app itself performs are now applied to both stores:

- `deleteThreadFromShadow` — conversation delete (messages + projection).
- `markThreadReadInShadow` — read state, so unread badges cannot resurrect on
  re-open after a fresh process start.
- `repairThreadInShadow` — re-reads one thread from the provider; covers
  in-place UPDATEs (PENDING → SENT/DELIVERED/FAILED) that date-window syncs
  structurally cannot observe.

## Added

- Schema **v2**: `conversations.rawAddress` (display + matching without a
  provider lookup), `messages.dateSent` (delivery timestamp survives the
  shadow). Room schemas are now actually exported (`room.schemaLocation` →
  `app/schemas/`) so future versions can ship real Migrations; the v1→v2
  destructive wipe is intentional and documented in
  `docs/room-migration-strategy.md`.
- `MessageEntity.toSms()` mapper covered by 4 unit tests (field mapping,
  unread inversion, raw-address fallback, status/dateSent passthrough).

## Verification

- `assembleDebug` ✓ · `testDebugUnitTest` ✓ (90 tests, green)
- Upgrade path v1→v2 and end-to-end cutover verified on device install;
  CI green on the feature commit (73fabea).

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.4.0...v2.5.0
