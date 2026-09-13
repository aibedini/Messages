# Messages v3.3.1 — GMweb stale-notification protection

**versionCode 101 · Room schema v11 (unchanged) · Compose/Material 3**

A correctness/safety release for the GMweb pull bridge (GET /gateway/pull).
Data architecture, the LAN gateway, the direct/local SMS paths and every legacy
EVE /send behaviour are **unchanged**.

---

## 1. The bug

   11:20  GMweb hands the phone "volume ended"
   11:21  the task is queued locally
   11:26  the customer renews
   11:40  Android sends the old SMS anyway      <- wrong, and irreversible

A task can become stale *after* it has been pulled. Validating only at pull time
does not close that window, and local cancellation cannot either: an ACTIVE
record is not cancellable.

## 2. Mandatory final pre-send validation

Metadata-aware tasks (meta.requiresValidation = true) are now validated twice:

    GET /gateway/pull
          |
          +-- pull-time check      OPTIMISATION (avoids pointless local work)
          |
       EveSmsQueue.enqueue -> QUEUED
          |
       worker dequeues ("ready for send")
          |
          +-- FINAL /gateway/validate   CORRECTNESS BARRIER
          |        valid       -> native SmsManager submission -> SENT
          |        superseded  -> SUPERSEDED (native sender NEVER called)
          |        unavailable -> DEFERRED   (backoff; never sent)
          |
       POST /gateway/ack

The final gate lives in EveSmsQueue.drainOne(), immediately before the native
sender lambda, with no disk, network or UI work in between. The exact instant the
native funnel was entered is recorded as nativeSubmitStartedAt.

Only meta.requiresValidation = true tasks are gated. A task with **no** meta
object at all (older GMweb) keeps the previous behaviour byte for byte.

## 3. Fail-closed policy

| GMweb answer | Local state | Physical SMS |
|---|---|---|
| valid:true | SENT | sent |
| valid:false | SUPERSEDED (terminal) | **never** |
| timeout / offline / 5xx / 429 / bad body | DEFERRED (non-terminal) | **never** |

DEFERRED is a bounded backoff ladder (15s, 30s, 60s, ... capped at 5 minutes)
gated by the existing NetworkMonitor, so an outage can never become a SENT and
never becomes a permanent failure. Requests use bounded timeouts (connect 5s /
read 8s) and the existing gateway API key.

## 4. Canonical ACK contract

Outcomes are exactly **sent | failed | superseded**; the detailed cause rides in
the reason field:

    {"requestId":"...","ok":true, "outcome":"sent",       "sentAt":<epoch-ms>,"ackAt":<epoch-ms>}
    {"requestId":"...","ok":false,"outcome":"superseded", "reason":"renewed",        "ackAt":<epoch-ms>}
    {"requestId":"...","ok":false,"outcome":"failed",     "reason":"provider_error", "ackAt":<epoch-ms>}

- device_send_failed is no longer an outcome — it is the **reason** for a
  transport-level failure.
- sentAt is populated **only** when a physical/native submission actually
  resulted in a sent outcome; ackAt is the generic terminal timestamp.
- A superseded task is never reported as a device failure.

## 5. DEFERRED correctness does not depend on redelivery

A parked DEFERRED task is retried by the queue's own backoff/sweep, and its ACK
is emitted from that **local** terminal state — GMweb does not have to redeliver
it. The pending-ack ledger is re-seeded from the durable queue on every poller
start, so this also holds across process death and reboot. A redelivered
requestId is a duplicate: no second local record, no second physical SMS, no
second ACK in the same process.

Idempotency is keyed on the GMweb requestId, persisted on the queue record.

## 6. Observability

GET /send/status/{id} additionally exposes gatewayRequestId, serviceKey,
notificationKind, generation, correlationId, requiresValidation,
validationResult, validationAttempts, validatedAt, deferredUntil,
supersededReason, outcome and nativeSubmitStartedAt.

Structured logcat events (never the SMS body): PULL_RECEIVED, VALIDATION_VALID,
VALIDATION_SUPERSEDED, VALIDATION_UNAVAILABLE, LOCAL_DEFERRED,
NATIVE_SUBMIT_STARTED, NATIVE_SEND_CONFIRMED, ACK_SENT.

## 7. Tests

testDebugUnitTest **343 tests, 0 failures** (56 of them new), re-run twice to
confirm stability. assembleDebug and compileDebugAndroidTestKotlin green.

See docs/gmweb-presend-validation.md for the wire contract the GMweb side must
implement (POST /gateway/validate).

## Notes / known limits

- The window between a successful validation and an irreversible native
  submission is minimised but not eliminable; it is measured via
  nativeSubmitStartedAt.
- Instrumented (androidTest) tests compile but are **NOT RUN — physical device
  required**.
- A DEFERRED task has no expiry: it stays deferred until GMweb answers.
- GMweb must ship POST /gateway/validate before emitting
  requiresValidation=true tasks, otherwise those tasks fail closed forever.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v3.3.0...v3.3.1
