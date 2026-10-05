# v3.4.27 — carrier delivery receipts: durable, multipart-safe, correlated

`versionCode 134` · `versionName "3.4.27"` · minSdk 26 · targetSdk 36

Carrier delivery for a GMweb-requested SMS now has one durable path, and the submission path can no
longer be mistaken for it:

```text
Carrier → DELIVERY PendingIntent → SmsStatusReceiver
        → durable local record (SharedPreferences, committed synchronously)
        → OutboxPoller drain → POST /gateway/delivery-report → 2xx → acknowledged
```

**`SENT` is not delivery.** A SENT callback (RESULT_OK), an accepted `sendTextMessage` and GMweb
accepting the submission all still mean *submitted*. Only a DELIVERY callback, with definitive carrier
evidence, produces `delivered`.

## What was already there (extended, not replaced)

The pipeline existed: SENT and DELIVERY PendingIntents are both built by `SmsSender`, the receiver
folds per-part evidence through `SmsStatusPolicy`, the per-segment ledger is `send_segments`, and
`GatewayDeliveryReports` bridges a carrier verdict to the GMweb task and uploads it with
`OutboxPoller`. This release fixes and completes it.

## Fixes

**1. A gateway send always asks the carrier for a delivery report.** The DELIVERY PendingIntent was
built only when the *local* preference `deliveryReportsEnabled` was on. A user who turned that off
silently removed GMweb's only source of carrier evidence for the sends GMweb requested. The
preference now governs the user's own messages; a row that belongs to a gateway task (the durable
rowId→requestId map written before submission) always gets a DLR.

**2. The durable report carries part, aggregate and SIM metadata** (additive, backward-safe):

```json
{
  "eventId": "dlr_…", "requestId": "…", "status": "delivered|failed",
  "occurredAt": …, "eventType": "carrier_delivery",
  "segmentIndex": 2, "segmentCount": 3,
  "allSegmentsDelivered": true,
  "receivedAtDevice": …,
  "subscriptionId": 2, "carrierResultCode": 0
}
```

`allSegmentsDelivered` is true only for a `delivered` aggregate — a `failed` verdict can be one refused
part of a multipart message, and a message must never be reported as covered when it is not. The
subscription is the SEND-TIME one carried by the callback, never a fresh lookup of the current default.
A report persisted by an older build lacks these fields and decodes to safe defaults (nothing is lost,
nothing is migrated).

**3. PendingIntent identity is explicit and tested.** The requestCode (action × row × part) was inlined
in the builder; it is now the pure `SmsSender.statusRequestCode`, so uniqueness is asserted instead of
trusted. `PendingIntent` equality ignores extras, so a reused code would silently rewrite another
callback's extras and correlate a carrier verdict to the wrong part.

**4. Observability.** Durable diagnostic lines (`GM_DELIVERY`): one per callback (phase, part x/y,
evidence, result code, subscription, aggregate), one when the verdict is persisted, and on upload:
`upload_ack` after a 2xx or `upload_retry` with the HTTP status. No body, no number, no credential.

**5. No Room change.** No entity, no schema version, no migration — the delivery record already lives
in its own committed SharedPreferences file, so the SMS database (and a 370k-row history) is untouched
and there is no destructive-migration risk.

## Multipart and idempotency

* Evidence is stored per part (`segment_index`/`segment_count` + the `send_segments` ledger), so
  callbacks arriving out of order, duplicated, or after a process restart converge on the same state.
* The aggregate verdict is decided by ONE rule (`SmsStatusPolicy.nextStatus`, counts of part evidence —
  never an arrival order): all expected parts delivered → `delivered`; a refused part → `failed`; a
  missing DLR → neither. One delivered part never delivers the whole message.
* `eventId = sha256(requestId|status)`, computed once and reused by every retry, so GMweb can
  deduplicate. A duplicate callback cannot create a second semantic event, and after a final verdict is
  recorded the rowId→requestId map is dropped, so no weaker or older callback can overwrite it
  (delivered cannot regress to pending).

## Tests added — **not executed** (this task forbids running Gradle)

* `GatewayDeliveryReportContractTest` — event id stability across retries, distinct verdicts/jobs,
  part/aggregate/SIM fields, `failed` never claiming full delivery, absent metadata never fabricated,
  legacy 4-field reports decoding safely, malformed rows skipped, unknown-request quarantine rule.
* `SmsStatusRequestCodeTest` — SENT vs DELIVERY never collide, distinct rows/parts never collide,
  determinism, a 1200-callback burst is collision-free, and the aggregate policy for the mission's
  multipart example (2,1,3 + duplicate → delivered only after all three; one refused part → failed; one
  missing DLR → not delivered).
* `CarrierDeliveryPipelineGuardTest` — source guards: the receiver persists via
  `GatewayDeliveryReports.recordFinal` and contains no network primitives; both send branches receive
  the delivery intents; a gateway send forces the DLR; exactly one main source uploads to
  `/gateway/delivery-report` and it acknowledges only after a 2xx; the status receiver is not exported.

## REAL DEVICE + REAL SIM VALIDATION REQUIRED

```text
NOT PROVEN. Carrier delivery cannot be proven from source, from unit tests, or from this repository:
it requires a real handset, a real SIM and a carrier that returns DLRs, and it must be observed as
DELIVERY callback → durable record → upload_ack after 2xx. Nothing in this release claims that.
The multipart behaviour additionally depends on which parts the carrier actually reports on.
```
