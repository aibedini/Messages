# Messages v3.3.3 — SMS send-state correctness and main-thread fixes

**versionCode 103 · Room schema v13 (additive) · Compose/Material 3**

A correctness release for outgoing SMS. The customer-visible bug it fixes:

```
send an SMS with no usable credit / no service
  -> SmsManager.sendTextMessage() does NOT throw
  -> the SENT callback returns a modem failure
  -> the UI showed a normal successful single tick and no error
```

---

## 1. sendTextMessage() is not carrier success

`SmsManager.sendTextMessage()` returning without throwing now means exactly one
thing: **`DISPATCHED`** — Android telephony accepted the API call. It is no longer
treated as delivery or as carrier acceptance anywhere in the send path.

A durable, explicit state machine was added (`sms/SendState.kt`):

```
QUEUED -> DISPATCHING -> DISPATCHED
                           |- RESULT_OK -----------------> SENT_CONFIRMED -> DELIVERED
                           |- definite transport failure -> FAILED
                           '- ambiguous / vendor result --> SEND_UNCONFIRMED -> DELIVERED
```

Transitions are monotonic: a `DELIVERED` message is never downgraded by a stale or
duplicate callback, and `FAILED` is sticky.

## 2. Hard failure vs ambiguous failure

The previous code deliberately never failed a message on a non-OK SENT callback,
because some RILs return `GENERIC_FAILURE` for submits the SMSC actually accepted.
That protection is preserved — but it was also swallowing real failures.

* **Hard / actionable** (`NO_SERVICE`, `RADIO_OFF`, `NULL_PDU`) -> `FAILED`, with a
  typed reason, and the provider status is written to `STATUS_FAILED` so the bubble
  shows the failure and offers Resend.
* **Ambiguous** (`GENERIC_FAILURE` and anything unclassified) ->
  `SEND_UNCONFIRMED`: the message stays `STATUS_PENDING` — *sending*, never a
  success tick — and the raw `resultCode` is kept for diagnosis.
* **All parts `RESULT_OK`** -> single tick, as before.

A multipart send is aggregated over its parts: one refused part fails the whole
message; one ambiguous part prevents a success tick; proven delivery outranks
older failure evidence.

## 3. Failure reasons are durable and typed

Stable codes (`NO_SERVICE`, `RADIO_OFF`, `NULL_PDU`, `DISPATCH_REJECTED`,
`MODEM_FAILURE`, ...) are persisted per part in the send ledger; no translated UI
string is ever stored as state. Schema v13 adds `send_segments.callbackFailureCode`
with an **additive, non-destructive** migration.

A synchronous dispatch rejection (the API call itself threw) is no longer only a
Logcat line: it sets `STATUS_FAILED` and records a typed `DISPATCH_REJECTED` ledger
row so the bubble stays Failed across restarts.

## 4. The "SMS today" ledger is untouched

Dispatch-rejection and callback-first rows carry `submittedAt = NULL`, so they are
**never counted** by the daily submission counter. The v3.3.2 invariant — a send
callback can never make submitted accounting jump backward or move a segment into
another day — is preserved and still covered by the v12 ledger tests.

## 5. Main thread / ANR

* Removed the blocking send bridge: `SmsSender.sendWithOutcome` launched on a
  throwaway `CoroutineScope` and then `CountDownLatch.await(10s)` on the caller's
  thread. The durable path is now the suspend `sendWithOutcomeSuspend`; the
  non-blocking entry point takes the direct path and never waits.
  (The removed branch was unreachable: `GatewayOutgoingPipeline.ENQUEUE_ALL_SENDS`
  is `false` in every shipped configuration.)
* The main-thread guard is now enforced in every build, not only DEBUG.
* `HomeViewModel.silentRefresh()` no longer calls
  `TelephonySyncCoordinator.syncNow()` — a foreground read no longer triggers
  provider-wide reconciliation.

## 6. Gateway / GMweb contract unchanged

`sent | failed | superseded`, durable dedupe, final pre-send validation, superseded
ACKs and process-death recovery are untouched. The ambiguous device state is not
smuggled into the external contract; see `docs/sms-delivery-architecture.md`.

## 7. Tests

`testDebugUnitTest`: **375 tests, 0 failures** (12 new).
SmsStatusPolicyTest grew from 14 to 26 tests and now covers the regression directly:
a definite refusal renders as Failed, an ambiguous modem result is never a success
tick, multipart aggregation, delivery outranking failure, and monotonic state
transitions.

## Notes / known limits

The visible conversation and Home list are **not yet Room-Flow driven**, and there
is still no bounded delta/watermark ingestion, no optimistic `clientMessageId`,
and no in-bubble reason copy or telemetry. `docs/sms-delivery-architecture.md` lists
these as explicit known gaps.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v3.3.2...v3.3.3