# GMweb pre-send validation (stale depletion notifications)

Android is the **source of truth for delivery**, GMweb is the **source of truth
for whether a metadata-aware notification is still current**. This document is
the wire contract between the two for that second rule.

## The bug this closes

   11:20  GMweb gives the phone "volume ended"
   11:21  the task is queued locally
   11:26  the customer renews
   11:40  Android finally sends the old SMS          <- wrong, and irreversible

Validating only when the task is **pulled** does not fix this: the task can
become stale while it waits in the local queue. Validating only when it is
**enqueued** does not fix it either. The check has to happen at the last
possible moment.

## Two-phase validation

    GET /gateway/pull
          |
          +-- (1) pull-time validation   <- OPTIMISATION (may skip local work)
          |
       EveSmsQueue.enqueue  ->  QUEUED
          |
       worker dequeues ("ready for send")
          |
          +-- (2) FINAL validation       <- CORRECTNESS BARRIER
          |        valid       -> native SmsManager submission -> SENT
          |        superseded  -> SUPERSEDED (native sender NEVER called)
          |        unavailable -> DEFERRED   (backoff, retried; never sent)
          |
       POST /gateway/ack (sent | superseded | device_send_failed | cancelled)

Phase (2) runs inside the queue worker, immediately before the native sender
lambda, with no disk, network or UI work in between. Only phase (2) can prevent
a physical send.

## Task contract

`GET /gateway/pull` returns a task that may carry metadata:

    {
      "task": {
        "requestId": "gw-...",
        "to": "+98...",
        "text": "...",
        "priority": "critical",
        "meta": {
          "source": "eve",
          "serviceKey": "eve:<serverId>:<clientUuid>",
          "notificationKind": "volume_ended",
          "generation": 17,
          "correlationId": "<uuid>",
          "requiresValidation": true
        }
      }
    }

`notificationKind` is one of `near_expiry`, `low_volume`, `expired`,
`volume_ended`, `renew`, `created`.

Rules:

* **Only** `requiresValidation = true` tasks are gated. Everything else keeps
  the previous behaviour, byte for byte.
* A task with **no** `meta` object at all (older GMweb) is never gated.
* `requestId` is the identity. **Never** use the phone number as identity: one
  number can own several services. Supersession is scoped by
  `requestId` / `serviceKey` / `generation`, never by `to`.

## Required GMweb endpoint

    POST /gateway/validate
    X-API-Key: <same gateway API key as /gateway/pull>
    Content-Type: application/json

    {"requestId":"<GMweb gateway requestId>"}

Current task:

    {"valid":true,"status":"valid","reason":null}

Superseded task:

    {"valid":false,"status":"superseded","reason":"renewed"}

Any non-2xx, timeout, unreadable body or body without `valid:true` is treated as
**unavailable**, never as valid.

## Fail-closed policy

For depletion-style notifications (`near_expiry`, `low_volume`, `expired`,
`volume_ended`) whenever `requiresValidation = true`:

| GMweb answer | Local state | Physical SMS |
|---|---|---|
| `valid:true` | `SENT` | sent |
| `valid:false` | `SUPERSEDED` (terminal) | **never** |
| timeout / offline / 5xx / 429 / bad body | `DEFERRED` (non-terminal) | **never** |

`DEFERRED` records retry with a bounded backoff (15s, 30s, 60s, ... capped at
5 minutes) and only when the app's `NetworkMonitor` reports a validated
connection. An outage is never converted into `SENT` and never into a permanent
failure.

## Queue states

    QUEUED -> ACTIVE -> SENT | FAILED
    QUEUED --------------------------------------------------> CANCELLED
    QUEUED -> [gate] -> SUPERSEDED   terminal, successful=false, retryable=false,
                                     physical send never started
    QUEUED -> [gate] -> DEFERRED     non-terminal, retried after backoff

`SUPERSEDED` is deliberately **not** `FAILED`: the device did nothing wrong,
the business decision changed.

## ACK contract

Outcomes are the **canonical set** — GMweb distinguishes exactly these three:

    sent | failed | superseded

The detailed transport/provider/business cause always rides in `reason`.

    {"requestId":"...","ok":true, "outcome":"sent",       "sentAt":<epoch-ms>,"ackAt":<epoch-ms>}

    {"requestId":"...","ok":false,"outcome":"superseded", "reason":"renewed", "ackAt":<epoch-ms>}

    {"requestId":"...","ok":false,"outcome":"failed",     "reason":"provider_error",       "ackAt":<epoch-ms>}
    {"requestId":"...","ok":false,"outcome":"failed",     "reason":"device_send_failed",   "ackAt":<epoch-ms>}
    {"requestId":"...","ok":false,"outcome":"failed",     "reason":"cancelled_locally",    "ackAt":<epoch-ms>}

* `ok` is true only for `outcome = sent` (unchanged for older servers).
* `device_send_failed` is no longer an outcome value — it is the **reason** for a
  transport-level send failure. GMweb may keep accepting it as a temporary alias
  on input; Android always emits `failed`.
* A locally cancelled task is reported as `failed` with
  `reason=cancelled_locally` — cancellation is not a fourth outcome.
* A superseded task is **never** reported as a device failure.
* `sentAt` is populated **only** when a physical/native SMS submission actually
  produced a sent outcome. It is never overloaded for a task that did not send.
* `ackAt` is the generic terminal timestamp and is present on every outcome.

## DEFERRED tasks are acknowledged locally

While a task is locally `DEFERRED`, **no ack is sent** and the task is retried by
the queue's own backoff/sweep. The ACK is emitted from that **local** terminal
state — it does not wait for GMweb to redeliver the task:

    pull A -> validation timeout -> DEFERRED (no ack)
                    |
             local backoff expires (15s, 30s, ... )
                    |
             local worker re-validates A
                    |- valid      -> native send -> SENT  -> ack {outcome:"sent"}
                    '- superseded -> SUPERSEDED   -> ack {outcome:"superseded"}

The pending-ack ledger is re-seeded from the durable queue on every poller start,
so the same holds across process death and reboot.

Server redelivery of the same `requestId` is allowed and is treated as a
**duplicate**: the existing local record is reused, no second physical SMS is
created, and no second ACK is emitted for a task already acknowledged in this
process.

## Idempotency

The local queue stores the GMweb `requestId` on the persisted record. Pulling the
same `requestId` again (retry, redelivery, server restart, phone reboot)
returns the existing record and never enqueues a duplicate.

## Diagnostics

`GET /send/status/{localRequestId}` additionally exposes, for metadata-aware
tasks: `gatewayRequestId`, `serviceKey`, `notificationKind`, `generation`,
`correlationId`, `requiresValidation`, `validationResult`, `validationAttempts`,
`validatedAt`, `deferredUntil`, `supersededReason`, `outcome` and
`nativeSubmitStartedAt` — the exact instant the native sender was entered, which
bounds the (unavoidable) window between a successful validation and an
irreversible radio submission.

Structured `logcat` events, one per task, never containing the SMS body:
`PULL_RECEIVED`, `VALIDATION_VALID`, `VALIDATION_SUPERSEDED`,
`VALIDATION_UNAVAILABLE`, `LOCAL_DEFERRED`, `NATIVE_SUBMIT_STARTED`,
`NATIVE_SEND_CONFIRMED`, `ACK_SENT`.
