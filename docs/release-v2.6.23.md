# Release v2.6.23 — carrier-accurate segment counter (optimistic ledger)

**versionCode 65 · no Room schema change (stays v7)**

## Field report

The Home "N پیامک امروز" chip reads the `send_segments` ledger — one row per
carrier-billable part (GSM-7 160/153, UCS-2 70/67, extended chars cost 2 —
exactly how the operator bills). Rows were written ONLY from the modem's SENT
callback. Any gap in that broadcast chain (Doze, process death, a provider
insert falling back to a synthetic id, a receiver not yet exported at boot)
left the counter at 0 while messages were actually leaving the phone.

## Changes

### SmsSender — optimistic per-segment ledger (PR-11.2)

* At dispatch time, immediately after `divideMessage`, one ledger row per part
  is written from the SAME `parts` the modem will send (success=false).
* After the synchronous send returns without exception — the radio accepted
  the submit — every part row is flipped to `success=true`. The segments are
  billable at that moment; counting no longer depends on the callback.
* Writes are REPLACE on `(rowId, partIndex)`: the later SENT callback simply
  overwrites the optimistic row with the authoritative per-part verdict —
  idempotent, no double counting.

### SmsStatusReceiver — no more ledger loss on synthetic row ids

* `processStatusIntent` used to return early when `rowId <= 0` (the
  `persistToSent` fallback id path), dropping the ledger row entirely.
  Now only the provider STATUS mutation is skipped; the SEND-side ledger row
  is still recorded under a synthetic negative id (`-now`), so the callback's
  billable evidence always lands.

### Unchanged

* `SmsSegmentCounter` billing math (already standards-compliant, unit-tested).
* Room schema v7, delivery-report policy, GMweb/Eve (untouched).

## Verification

* `./gradlew assembleDebug testDebugUnitTest` — green, 196/196.
* Expected on device after install: send one Persian SMS of 80 chars → the
  chip shows 2 (two UCS-2 segments) immediately, without waiting for the
  SENT callback; a callback arriving later overwrites the same rows, the
  count stays 2.

## versionCode

Bumped to **65** in `app/build.gradle.kts` (name `2.6.23`).
