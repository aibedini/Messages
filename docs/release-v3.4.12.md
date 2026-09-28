# v3.4.12 — GMweb learns what the carrier actually said

`versionCode 119` · `versionName "3.4.12"` · minSdk 26 · targetSdk 36

This release closes the last gap in the GMweb-requested send path: a message GMweb asked the device to
send could be *sent* and then reported to GMweb as **sent**, while the carrier's own delivery report —
the only evidence that the recipient's handset accepted it — was recorded on the device and then went
nowhere. GMweb now receives that verdict, and it still receives it if the app is killed first.

---

## The gap

The status callback chain already knew the truth. `SmsStatusReceiver` folds the modem's per-segment
`SENT`/`DELIVERED` callbacks through `SmsStatusPolicy`, and that fold already distinguishes:

```text
DELIVERED   a carrier report said the recipient accepted the message
FAILED      a carrier report said it will never arrive
TEMPORARY   a report that may still resolve — not a verdict
UNKNOWN     no carrier evidence at all
```

What did not exist was a **route back to the request that caused the send**. Earlier work had already
carried GMweb's own key as far as `EveSmsQueue.Record.correlationId` so the outgoing bubble could be
tied to the row; but the key stopped at the sender seam, and the carrier verdict that arrived later had
no key to travel with. So a GMweb-requested message could sit at *sent* for ever, with the delivery
report present on the device and absent on the server.

Worse, the two halves were not durable against each other. The carrier callback that carries the verdict
is a broadcast: the process can be killed between receiving it and anything reading it, and the send
identity lived only in memory.

---

## What changed

**The send identity is now durable, and it is written before the radio is touched.** A new
`GatewayDeliveryReports` object persists a `provider row id → GMweb gatewayRequestId` binding, with
`commit()` rather than `apply()`, and `SmsSender.directSend` writes it **before** `SmsManager` is
handed the message. A crash inside the radio-submit window can no longer erase which GMweb task a row
belonged to.

**The binding carries no content.** Only the request identity, the row id and a timestamp are stored —
never the message body, never the phone number. The same rule the rest of the gateway follows applies
here.

**Only definitive carrier evidence is reported.** `recordFinal` refuses to produce a report for
anything but `STATUS_COMPLETE` (→ `delivered`) and `STATUS_FAILED` (→ `failed`). `SENT`, temporary
network reports and unknown values produce nothing: a report that is not a verdict is not sent as one.

**The report is persisted before the provider and the UI are written.** `SmsStatusReceiver` now
`commit()`s the delivery sets when delivery evidence has arrived, and records the final report *before*
`updateProvider`, so an app death in that window cannot lose the carrier evidence. The non-delivery
path keeps the original `apply()` and its original cost.

**Reports survive restart and are drained by the poll loop.** `OutboxPoller` drains pending reports at
the top of each cycle, immediately after local terminal outcomes are acked and before the next
long-poll, so a report written by a process that then died is uploaded by the next one, and a deferred
report never waits for a redelivery.

**The drain is bounded, so a broken endpoint cannot starve SMS pulling.** At most **three** reports per
cycle, and the first non-2xx response, the first failed acknowledgement or the first exception ends the
drain for that cycle. Nothing is deleted on a failure: the report stays pending and is retried on the
next cycle.

**Acknowledgement requires persistence.** A report is only removed after a 2xx response **and** a
successful durable `commit()` of the removal — so an accepted-but-unrecorded acknowledgement cannot
lose the report silently.

**The event identity is content-addressed and stable.** `eventId` is `sha256(gatewayRequestId|status)`,
so the same verdict retried any number of times de-duplicates on the server side, and a later `failed`
can never collide with an earlier `delivered`.

---

## The wire contract

```text
POST  {gmweb origin}/gateway/delivery-report
Content-Type: application/json

{ "eventId": "dlr_<32 hex>", "requestId": "<GMweb request id>",
  "status": "delivered" | "failed", "occurredAt": 1750000000000 }
```

Binding is by `requestId`, the same `GatewayMeta.gatewayRequestId` GMweb already puts on the task it
pulls. The origin is the user-configured `gmweb_server_origin` — the same single source of truth every
other GMweb route derives from. If no origin is configured, the drain does nothing.

---

## Upgrading

No schema change, no migration, no data movement. Existing pending rows simply have no binding, so no
report is produced for them; the feature is forward-looking from the first send after upgrade, which is
the only honest behaviour — a verdict cannot be invented for a send the device never correlated.

Local retention of the binding map is 30 days, swept on write.

---

## Verification

```text
2,013 JVM unit tests, 0 failures, 0 errors, 0 skipped   (188 test classes)
```

including the two new `GatewayDeliveryReportsTest` cases, which pin the two properties the whole design
rests on: the identity is **stable across retries** (so a retried upload de-duplicates rather than
duplicating) and a **failure never shares an identity with a delivery** (so a later verdict cannot
silently overwrite an earlier one).

---

## Known limitations — unchanged and not softened

```text
DEVICE ACCEPTANCE PENDING

This release has NOT been validated on a physical device or emulator. No carrier delivery
report has been observed end-to-end on real hardware by this change.

GMWEB-SIDE ENDPOINT NOT VERIFIED FROM THIS REPOSITORY

POST {origin}/gateway/delivery-report is a new contract. Whether the deployed GMweb server
implements it cannot be checked from here. If it does not, the device retains every report
and retries it on each poll cycle, and the log shows the HTTP status — for example
"delivery-report HTTP 404". No report is lost, but none is delivered either.

THE PERSISTENCE PATH IS NOT COVERED BY A JVM TEST

GatewayDeliveryReports' SharedPreferences round trip
(remember -> recordFinal -> pending -> acknowledge) has no JVM test: it needs a real Context,
and this project's unit tests do not run under Robolectric. What IS tested is the event
identity derivation, which is the pure part. The persistence path is proven by compilation
and by reading, not by execution.

OEM battery behaviour is NOT validated. Whether a given manufacturer's battery manager
keeps the gateway alive has not been measured on any device.

The 360k-message benchmark is validated at the deterministic SQLite/replication layer ONLY,
not against a real 360k-message Telephony Provider.

MMS binary attachment replication still requires GMweb-side protocol support.

Exactly-once remote SMS transmission cannot be guaranteed across the radio-submit crash
window. This release narrows that window's consequences — the correlation survives it — but
does not close it.
```

## Still open from v3.4.11

Full-mirror verification on a device reported `examined=72000 · alreadyReplicated=1000 · recovered=71000`,
meaning history reported `CAUGHT_UP` while 71,000 messages had no durable event. That is **not**
addressed here and remains its own investigation.
