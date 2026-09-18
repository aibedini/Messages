# Provider reconciliation

Scope: how Room is kept in step with the Android Telephony provider, and what a
failed read is allowed to mean. Branch `fix/realtime-room-ssot`.

## Truth boundaries

| Layer | Owner | Notes |
|---|---|---|
| Android Telephony provider | EXTERNAL source of truth | The only place a message really exists. |
| Room (`messages`, `conversations`) | LOCAL read model, and the UI's only source | Written ONLY by TelephonySyncCoordinator. |
| UI | Reader of Room | Optimistic overlays may render ahead of Room; they never replace it. |

## The one rule

> A provider read failure is UNKNOWN. It is never evidence of absence.

`ProviderRead<T>` enforces this in the type system:

    Success(value)  -> the provider positively answered
    Failure(reason) -> UNKNOWN

A null Cursor is a `Failure`, not "zero rows". `provesAbsence` is true only for
`Success(null)` (and, for MMS, `Success(ProviderExistence.Absent)`).

Only a successful read may produce a `MessageMutation.Delete`. Nothing else:
not a null cursor, not a `SecurityException`, not a secondary-table failure, not
a timeout.

## Strict reads

| Reader | Question it answers | Failure means |
|---|---|---|
| `readSmsExactStrict(id)` | is this SMS there, and what is it? | keep Room, retry |
| `readMmsExistenceStrict(id)` | is this MMS there? (ONE query) | keep Room, retry |
| `readMmsMaterializedStrict(id)` | what is this MMS? (base + addr + part) | keep Room, retry |
| `querySmsThreadStrict(t, n)` | bounded window of SMS in a thread | no conclusion for SMS |
| `queryMmsThreadStrict(t, n)` | bounded window of MMS in a thread | no conclusion for MMS |
| `querySmsRaw` / `queryMmsRaw` | rendering only (forgiving) | empty list, NON-destructive callers only |

### Why MMS is split in two

An MMS body lives in `content://mms/part` and its address in
`content://mms/addr`. A single "read the whole row" therefore has three failure
points, and only the first says anything about existence. The old reader
collapsed a failed Part lookup into an empty map, which the mapper rendered as the
literal `[MMS]` (and a failed Addr lookup as `Unknown`) - and the sync layer then
wrote that placeholder OVER the good Room row.

Now: existence is decided by one query against `content://mms`; materialization
requires BOTH secondary reads to succeed; `mmsSecondaryFailure` is the pure guard
that makes a failed secondary read impossible to materialize (pinned by
`StrictMmsReadTest`).

## Coverage boundaries

A repair reads a BOUNDED window. It may therefore only conclude something about
the rows inside that window. `THREAD_REPAIR_LIMIT` (200) documents this in code.
The repair deletes NOTHING per row; the one destructive conclusion it can draw is
the empty-thread proof:

    SMS Success(empty) AND MMS Success(empty) -> the provider thread is empty
                                                 -> remove the conversation (SQL)

If either source failed, the thread is UNKNOWN and nothing is removed.

## Durable exact repair (v14)

Failed exact reads become rows in `provider_repair_queue`
(see `ProviderRepairQueue`, `ProviderRepairEntity`):

- composite key `(source, providerId)`: SMS 123 and MMS 123 are different work;
- `generation` bumped by every new provider event for the identity, so an older
  in-flight read can neither ACK nor act on newer work;
- `claim` / `ack` / `nack` are generation-scoped; `due()` only observes;
- `leaseUntil` + startup reclaim, so process death cannot strand work IN_FLIGHT;
- capped exponential backoff (1s .. 5min) and NO permanent abandonment;
- NO capacity limit and NO eviction.

It is driven by its own timer (earliest retry, else earliest lease expiry, with a
30s safety poll). A provider burst is only an early nudge - that was the old
in-process map's fatal defect, where a read that failed during a quiet period was
never retried.

## Known limitations (not fixed)

1. **Old unidentified mutations.** TailDelta only finds rows newer than the
   durable watermark. An OLD row that changes or is deleted with no identifiable
   provider event is not detected by the realtime path.
2. **No periodic integrity audit.** PHASE 4.5 (low-priority, keyset, durable
   cursor, resumable audit) is not implemented. Until it is, limitation 1 stands.
3. **No bounded-overlap delete.** Room rows absent from a bounded provider page
   are NOT confirmed and NOT deleted (a strict exact existence read would be
   required first). This is deliberately conservative.
4. **Residual drain race.** A generation that changes between the ownership check
   and the mutation can apply one slightly stale non-destructive write, which the
   newer generation immediately converges. A fully atomic read+mutate+ack would
   need the mutation inside the queue's own transaction.

## Repair intent (v15)

Identity alone does not say what an ABSENCE means, so intent is persisted with the
queued row and is never inferred from the caller at claim time:

| Intent | Enqueued by | A proven absence may delete? |
|---|---|---|
| `RECONCILE_EXACT` | a mature exact ContentObserver identity | YES |
| `EXPECT_EXISTS` | `providerRowChanged` right after an outgoing insert | **NO** - retry |
| `REFRESH_STATUS` | a delivery/status callback | **NO** - retry |
| `VERIFY_DELETE_CANDIDATE` | bounded overlap, integrity audit | YES |

Without this, making the *caller* durable only moved the race one layer deeper:
enqueue -> generic repair -> first strict `Success(null)` -> Delete.

**Maturity policy.** `EXPECT_EXISTS` and `REFRESH_STATUS` cannot retry forever, and
must not delete on the first absence. An absence is only "mature" after
`VISIBILITY_GRACE_MS` (30s) AND `ABSENCE_MIN_ATTEMPTS` (3) successful reads. A
mature absence then:

- if Room has no such row -> resolve (nothing to remove);
- if Room still has the row -> `rearm` into a NEW generation with
  `VERIFY_DELETE_CANDIDATE`, which must prove absence independently before any
  delete.

**Migration safety.** v14 rows have no recorded origin, so v15 backfills them to
`EXPECT_EXISTS` - the non-destructive intent. A pre-existing row can never become
delete-capable by accident.

**Generation ownership.** Every enqueue bumps the generation and rewrites the
intent together. An older in-flight worker fails `stillOwned()` and can then
neither delete, ACK, NACK, nor modify the newer generation. In particular a stale
`VERIFY_DELETE_CANDIDATE` worker can never delete a row that a newer
`EXPECT_EXISTS` generation has re-claimed.

## Still not implemented

The two-sided periodic integrity audit (PHASE P0-7) is STILL ABSENT, so
`integrity_audit_state` was deliberately NOT added to v15 rather than shipping an
unused table. Consequence: an old provider row that never entered Room, or an old
unidentified provider mutation, is still not detected.
