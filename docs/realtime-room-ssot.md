# Realtime Room SSOT

Branch `fix/realtime-room-ssot`. This is the index document: what the architecture
is, where each invariant lives, and what is NOT done.

## Data flow

                    ANDROID TELEPHONY PROVIDER  (external source of truth)
                              |
        +---------------------+---------------------+
        |                     |                     |
   exact row id        bounded delta         keyset history
   (observer)          (newest watermark)    (oldest watermark)
        |                     |                     |
        +----------+----------+                     |
                   |                                |
            Canonical Ingest                        |
        (TelephonySyncCoordinator.applyMutation)    |
                   |                                |
            Room transaction  <---------------------+
                   |
        +----------+-----------+
        |                      |
   messages table        conversations table
        |                      |
   MessageDao.observe     ConversationDao.observeAll()
        |                      |
   Conversation UI         Home UI
        |                      |
        +--- optimistic overlays (UI acceleration only) ---+

## Source-of-truth boundaries

- The Telephony provider is the ONLY place a message really exists.
- Room is the LOCAL read model and the ONLY source the UI reads.
- Optimistic overlays may render AHEAD of Room; they never REPLACE it.

## The invariants, and where each one lives

| Invariant | Implemented in | Doc |
|---|---|---|
| A failed provider read is UNKNOWN, never absence | `ProviderRead`, strict readers | provider-reconciliation.md |
| Only a successful existence read can delete | `ChangeRouter.applyExactMms/Sms` | provider-reconciliation.md |
| MMS existence and materialization are different questions | `readMmsExistenceStrict`, `readMmsMaterializedStrict` | provider-reconciliation.md |
| Failed exact reads are durable, self-retried, never evicted | `provider_repair_queue`, `ProviderRepairQueue` | provider-reconciliation.md |
| A newer generation can never be consumed by an older ACK | queue `generation` + claim/ack/nack | provider-reconciliation.md |
| The conversation projection INSERT satisfies the shipped NOT NULL schema | `pinnedOnInsert`/`archivedOnInsert` | conversation-projection-storage.md |
| Pin/archive cannot be cleared by sync | INSERT-only flags | conversation-projection-storage.md |
| A rebuild can move the projection BACKWARDS | `replaceProjectionPreservingFlags` | conversation-projection-storage.md |
| Delete is ONE atomic transition (message + projection + outbox) | `applyMutation(Delete)` | this file |
| An incoming message for the OPEN conversation never flashes unread | `VisibleConversationTracker` | this file |
| A process restart is not a reason to re-crawl | `startGatewaySync` | startup-sync-state-machine.md |
| Home has one durable owner | `HomeViewModel` + `HomeConversationOverlay` | this file |
| Mark-read is local-first and atomic | `markThreadReadInShadow`, `MarkConversationReadUseCase` | this file |
| A conversation opens in O(page) | `ConversationWindow`, `ThreadPager` | this file |
| One thread's change does not invalidate every cached thread | `ThreadMessageCache` revisions | this file |
| ANRs and stalls are explainable | `diagnostics/` | anr-diagnostics (see below) |

## Hot path: an incoming SMS

    Telephony provider INSERT
      -> SmsReceiver / IncomingMessageDispatcher
         (optimistic SmsEventBus event: UI acceleration only)
      -> TelephonySyncCoordinator.mutate(Upsert)
      -> ONE Room transaction:
           message upsert
           + conversation projection upsert (monotonic, pin/archive INSERT-only,
             flags satisfied because the shipped schema requires them)
           + unread count = 0 when the conversation is VISIBLE
           + deterministic cloud outbox event
      -> ConversationDao.observeAll() emits
      -> Home renders from Room

No FullSync. No global projection rebuild. No full thread scan. No blocking
main-thread provider read.

## Home

After the Room bootstrap window is ready, `ConversationDao.observeAll()` is
authoritative and the rendered list is always

    RoomConversations + OptimisticOverrides

Provider conversation lists are reachable only while the Room gate is closed
(bootstrap, or a Room open/migration failure) and emit a typed `HOME_STATE`
diagnostic. A Room-ready resume performs zero provider-wide scans.

## Read state

`MarkConversationReadUseCase` is the single entry point for every path
(Home, search, deep link, notification, cache hit, provider fallback, an
already-open conversation receiving a message, explicit action). Order:

1. Room transaction marks the thread read locally (`markThreadReadInShadow`:
   message flags AND the conversation counter, in ONE transaction);
2. the UI observes that through Room / the overlay;
3. the provider READ/SEEN write happens asynchronously and is only eventual
   persistence - the UI never waits on a ContentResolver write;
4. a provider write failure produces a typed diagnostic and a repair request, and
   never reverts the local read state and never freezes the UI.

An older provider or Room emission cannot resurrect unread, because the local read
is written first and the ingest path zeroes the counter for a visible thread.

## Conversation

Opening a conversation uses the RAM cache if present, otherwise a bounded Room
window, otherwise a bounded provider query - never a whole-thread load. Older
scroll is keyset paging. A Room tail emission updates recent rows/read/status
without dropping pages the user already scrolled to. Identity is the composite
`(source, providerId)`; SMS 52 and MMS 52 can never collide. Ordering is
`date, source, providerId` everywhere so Home and the conversation view can never
disagree at an equal timestamp.

## Tests

Gradle was frozen for this pass. Every test below is WRITTEN and NOT EXECUTED:

- ConversationProjectionReplaceTest, ProviderRepairQueueTest, StrictMmsReadTest,
  ProjectionAggregateQueryTest, ProviderReadTest
- HomeConversationStateTest, ConversationWindowTest,
  ThreadMessageCacheRevisionTest, MarkConversationReadUseCaseTest
- ExitReasonSanitizerTest, MainThreadStallWatchdogTest, PerfTelemetryTest

    GATE-VERIFIED: NO
    TESTS EXECUTED: 0
    COMPILE STATUS: UNVERIFIED

## NOT DONE (explicit)

1. PHASE 2 canonical batch ingest (`ingestProviderRows`/`DiscoveryMode`): the
   single-row primitive exists and is canonical, but `syncSource` and the backfill
   still call `upsertAll` directly, so they do not compute the unread delta or
   emit per-row cloud events the way the exact path does.
2. PHASE 4.2 bounded-overlap delete confirmation, 4.4 generic-unknown
   TailDelta+overlap, 4.5 periodic integrity audit: not implemented. Old,
   unidentifiable provider mutations are therefore NOT detected by the realtime
   path (documented in provider-reconciliation.md).
3. PHASE 7 dedicated low-priority backfill dispatcher: the backfill still shares
   the IO dispatcher with `yield()` between batches.
4. Full projection rebuild writes are chunked and its reads are constant, but it
   is still a bootstrap/recovery-only path.
5. Instrumentation producers are wired for TailDelta and ForThread only; the
   remaining Trace sections/perf boundaries have no call sites yet.
6. `app/schemas/14.json` is produced by the Room processor and does not exist yet;
   the first Gradle run must generate it.
7. No device or emulator evidence exists for anything in this branch.
8. Not merged, not tagged, not released, versionCode unchanged.
