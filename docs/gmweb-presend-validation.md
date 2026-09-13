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

    {"requestId":"...","ok":true, "outcome":"sent","sentAt":<epoch-ms>}

    {"requestId":"...","ok":false,"outcome":"superseded","reason":"renewed","sentAt":<epoch-ms>}

    {"requestId":"...","ok":false,"outcome":"device_send_failed","reason":"provider_error",...}

    {"requestId":"...","ok":false,"outcome":"cancelled","reason":"cancelled_locally",...}

* `ok` is true only for `outcome = sent` (unchanged for older servers).
* A superseded task is **never** reported as `device_send_failed`.
* `sentAt` is always present (the ack timestamp) for compatibility.

While a task is locally `DEFERRED`, **no ack is sent**. The task stays open on
GMweb and is redelivered; the local record is deduplicated by `requestId`, so a
redelivery can never cause a second physical SMS.

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
