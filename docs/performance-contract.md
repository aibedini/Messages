# Performance contract

Scope: what this branch claims, and what it explicitly does NOT claim.
Branch `fix/realtime-room-ssot`.

## Measured

Nothing. Gradle was frozen for this pass and no device or emulator run was made:

    GATE-VERIFIED: NO
    TESTS EXECUTED: 0
    COMPILE STATUS: UNVERIFIED

There are therefore NO p50/p95 numbers anywhere in this branch, and adding
invented ones would be worse than having none.

## Engineering targets (targets, NOT results)

These are the numbers a future measured run must be compared against:

| Path | Target |
|---|---|
| memory-cache conversation paint | p95 <= 16 ms |
| Room-window first paint | p95 <= 50 ms |
| incoming persisted -> Home visible | p95 <= 50 ms |
| mark unread badge removal | <= one rendered frame |
| normal resume | 0 provider-wide conversation scans |
| incoming message | 0 FullSync, 0 global projection rebuild |
| conversation open | O(page size) provider rows |
| mark read (foreground) | no whole-thread provider read |
| startup after bootstrap | O(delta + resumed bounded maintenance) |

No `delay()` was inserted anywhere to make a path look faster.

## Asymptotic complexity (structural, not timings)

| Path | Complexity |
|---|---|
| incoming exact mutation | O(1) provider rows, O(1) Room rows, one transaction |
| exact delete transition | O(1) + one canonical newest query + one SQL COUNT |
| Home list | O(threads) from the Room Flow; no provider read when Room is ready |
| conversation open | O(page) from Room/cache; provider only as a bounded fallback |
| conversation older scroll | O(page) keyset |
| thread repair | O(THREAD_REPAIR_LIMIT=200) provider rows per source |
| exact repair retry | O(1) per identity, rate-bounded by the queue |
| history backfill batch | O(BACKFILL_BATCH=500) |
| full projection rebuild | O(threads) queries - see the N+1 limitation below |

## Instrumentation

Local, privacy-safe timing exists for: incoming persisted -> Room commit, Room
commit -> Home observed, conversation tap -> first bubbles, mark-read request ->
unread=0, TailDelta duration, ForThread duration, history batch duration. It is a
bounded ring buffer with real p50/p95 arithmetic; when nothing has been recorded
it reports NO SAMPLES rather than a fabricated zero.

`android.os.Trace` sections and a main-thread stall watchdog (warning ~2s,
critical ~5s, never kills the process) exist for future diagnosis.

## Known limitations

1. No measured evidence of any kind. Every claim above is structural.
2. `fullRebuildConversations` is N+1 in `countUnread` (bootstrap/recovery only).
3. No JankStats/FrameMetrics integration was added (it would have required a new
   dependency, which this pass forbids).
