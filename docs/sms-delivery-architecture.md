# SMS delivery architecture: outgoing state, failure semantics, ANR constraints

Scope: how an outgoing SMS becomes a visible, durable state, and which blocking
patterns are forbidden. Incoming/live-UI ingestion is described only as far as it
exists in source today; see **Known gaps** at the end for what is *not* implemented.

## 1. Outgoing SMS state machine

Implemented in `sms/SendState.kt` (pure, JVM-testable) and driven by
`sms/SmsStatusReceiver.kt` + `sms/SmsSender.kt`.

```
QUEUED          persisted locally, no telephony call yet
  |
DISPATCHING     the send call is in flight on this device
  |
DISPATCHED      telephony ACCEPTED THE API CALL for every part
  |- RESULT_OK                    -> SENT_CONFIRMED
  |- definite transport failure    -> FAILED
  |- ambiguous / vendor result     -> SEND_UNCONFIRMED

SENT_CONFIRMED   -+-> valid positive delivery report -> DELIVERED
SEND_UNCONFIRMED-+
```

`SendState.advance(current, next)` is monotonic: `DELIVERED` is never downgraded,
`FAILED` is sticky, and a stale/duplicate callback can never weaken a stronger
verdict.

## 2. SENT vs DELIVERED semantics

**`SmsManager.sendTextMessage()` returning without throwing means only
`DISPATCHED`.** It means Android telephony accepted the API call. It is NOT
carrier success and it must never be rendered as one.

* `SENT_CONFIRMED` — the radio reported `RESULT_OK` for every part.
* `DELIVERED` — a parsed positive SMS-STATUS-REPORT for every part.

Provider status mapping (`SmsStatusPolicy.nextStatus`), which is what the message
bubble renders:

| evidence | provider status | bubble |
|---|---|---|
| all parts delivered | `STATUS_COMPLETE` | delivered |
| any definite per-part refusal | `STATUS_FAILED` | error + retry |
| some parts still outstanding | `STATUS_PENDING` | sending |
| all parts reported, at least one ambiguous | `STATUS_PENDING` | **sending, never a success tick** |
| all parts confirmed | `STATUS_NONE` | single tick |

## 3. Hard vs ambiguous failure

`SmsSendPolicy.classifySentResult(resultCode, errorCode)`:

| result code | verdict | why |
|---|---|---|
| `RESULT_OK` | CONFIRMED | the radio accepted this part |
| `RESULT_ERROR_NO_SERVICE`, `RESULT_ERROR_RADIO_OFF`, `RESULT_ERROR_NULL_PDU` | FAILED | definite, actionable refusal |
| anything else (e.g. `RESULT_ERROR_GENERIC_FAILURE`) | UNCONFIRMED | affected RILs return it for submits the SMSC accepted |

Deliberately **not** "non-OK == FAILED": that recreates the false-failure bug.
Deliberately **not** "returned, so it sent": that is the false-success bug this
change fixes (dead SIM / no credit rendering as a normal single tick).

## 4. Durable failure reasons

Only stable CODES are persisted; user-facing text is generated at render time, so
no translated string is ever stored as state (`SmsSendFailure.code`:
`NO_SERVICE`, `RADIO_OFF`, `NULL_PDU`, `SIM_UNAVAILABLE`, `INVALID_SMSC`,
`INVALID_DESTINATION`, `FDN_RESTRICTED`, `LIMIT_EXCEEDED`, `DISPATCH_REJECTED`,
`MODEM_FAILURE`).

Per-part verdicts and codes live on the send ledger
(`send_segments.callbackState` / `callbackFailureCode`, schema v13) and survive
process death, reboot and locale changes.

## 5. Dispatch rejection never disappears

A synchronous `SmsManager` exception used to be logged and forgotten. It now:

1. sets provider `STATUS_FAILED` (the visible failure state, persisted by the
   platform, so it survives restart), and
2. writes a typed `DISPATCH_REJECTED` row per part into the ledger with
   `submittedAt = NULL`.

`submittedAt = NULL` is load-bearing: the segment was never submitted, so it must
never be counted by the "SMS today" counter. That is the same NULL-submission
semantics the callback-first race uses, so the submission ledger (v12 work)
cannot regress.

## 6. Submission ledger vs callback verdict (do not regress)

`send_segments` keeps two independent halves:

* `submittedAt` — immutable; the only column the daily counter reads.
* `callbackAt` / `callbackResult` / `callbackState` / `callbackFailureCode` — the
  modem verdict, applied with a targeted UPDATE that never REPLACEs the row.

A send callback therefore can never make already-submitted daily accounting jump
backward, and can never move a segment into another calendar day.

## 7. Process death recovery

* Provider status (`STATUS_PENDING` / `STATUS_FAILED` / `STATUS_COMPLETE`) lives in
  the Telephony provider, not in memory.
* Per-part modem evidence lives in Room (`send_segments`).
* PendingIntents are explicit and carry `rowId` / `partIndex` / `partCount` /
  `subscriptionId` (`send_attempt_id` falls back to `rowId` when Telephony returns
  no row id), so a callback that arrives after the process that sent it is gone can
  still be attached to the right logical message.

## 8. ANR / main-thread constraints

Forbidden on Main: full SMS/MMS provider scans, Room bulk work, network, encryption,
file I/O, `CountDownLatch.await`, `Thread.sleep`, large history sorts, full
reconciliation.

* `SmsSender.sendWithOutcome` no longer launches on a throwaway `CoroutineScope`
  and waits on a `CountDownLatch` for up to 10 s. The blocking bridge is gone; the
  durable remote-commands path is the suspend `sendWithOutcomeSuspend`.
* The main-thread guard is enforced in **every** build, not only DEBUG.
* `HomeViewModel.silentRefresh()` no longer calls `TelephonySyncCoordinator.syncNow()`:
  a foreground read must not trigger provider-wide reconciliation.

## Known gaps (NOT implemented in this change)

These are real and must not be assumed done:

1. **The visible conversation and Home list are not yet Room-Flow driven.**
   `ConversationViewModel` merges a provider tail query and `HomeViewModel`
   replaces its whole list from a one-shot query (`conversationDao.all()`), so
   `ConversationDao.observeAll()` / `MessageDao.observeThread()` are not the UI
   source of truth yet. A new message still requires the observer/provider nudge.
2. **No bounded delta/watermark ingestion.** A ContentObserver event without an
   exact provider id still falls back to reconciliation rather than a bounded
   recent-delta query.
3. **No multipart observer coalescing** beyond the existing conflated reconcile
   channel.
4. **No optimistic-row `clientMessageId`.** Optimistic outgoing rows are still
   matched to provider rows by body + timestamp window.
5. **The generic transport result is not exposed in the GMweb ACK contract**;
   `sent | failed | superseded` is unchanged and `SEND_UNCONFIRMED` currently
   maps to "not failed" (no ACK is emitted while a task is not terminal).
6. **No in-bubble reason text / Retry button change.** The existing failure
   affordance (red error icon + "Resend" + "Not delivered" detail) is what
   surfaces the failure; the typed code is persisted but not yet localised into
   new copy.
7. **StrictMode (debug) and benchmark/instrumentation coverage were not added.**
8. **No perf telemetry** (`incoming_event_at`, `room_commit_at`, ...).
