# Startup and backfill state machine

Scope: what the app does on first install, on a normal process start, and in
recovery. Branch `fix/realtime-room-ssot`.

## States (durable, in `sync_state`, per source)

| State | Durable flag | Meaning |
|---|---|---|
| BOOTSTRAP | `initialWindowReady = false` | No local window yet. Home cannot be served from Room. |
| READY | `initialWindowReady = true` | The newest window is mirrored and the projection is valid. |
| BACKFILL | `historyBackfillComplete = false` | Older history is still being crawled. Independent of READY. |
| RECOVERY | explicit FullSync | Corruption suspicion, projection recovery, manual repair. |

`initialWindowReady` is set ONLY after the conversation projection is valid, so
Home never renders a half-built window as if it were complete.

## Normal process start

The process restarting is NOT a reason to re-crawl. `startGatewaySync()` reads
`sync_state`:

    durable initial window present -> reconcile(TailDelta)   [no global reload]
    otherwise                       -> reconcile(FullSync)    [bootstrap]

FullSync therefore remains reachable for genuine bootstrap and for explicit
recovery callers, and is no longer the automatic answer to "the app was
reopened". A normal Room-ready resume performs ZERO provider-wide conversation
scans; the Home list is served from `ConversationDao.observeAll()`.

## Crash recovery

- The exact-repair queue survives process death (Room table). Expired leases are
  reclaimed at scheduler start and the work becomes due immediately.
- Watermarks are durable, so a killed backfill resumes from its keyset cursor
  rather than from the top.
- No background Job identity is treated as durable.

## Backfill

Historical crawl is a separate lane from the realtime path:

- keyset (never OFFSET), newest-first, durable `oldestDate/oldestId`;
- batch `BACKFILL_BATCH` = 500, yielding between batches;
- the UI never waits for history: Home renders the bootstrap window first.

Priority order the architecture intends:

    incoming exact mutation > mark read > conversation open/tail >
    exact repair > TailDelta > thread repair > integrity audit > history backfill

## Known limitations (not fixed)

1. **No dedicated low-priority dispatcher for backfill.** PHASE 7 asks for one
   executor whose capacity interactive work never competes for. Today the backfill
   runs on the shared IO dispatcher with explicit `yield()` between batches.
2. **No `BACKFILL_RESUME` state distinct from BACKFILL**: resume is implicit in
   the durable keyset cursor rather than an explicit enumerated state.
3. **Full projection rebuild is still N+1** (`countUnread` per thread in
   `fullRebuildConversations`). It is a bootstrap/recovery-only path, which is why
   it was not converted to a single aggregate query in this pass.
