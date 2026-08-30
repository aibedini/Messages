# Release v2.6.13 — Delivery Report Semantics + Per-SIM SMSC

**versionCode 55 · Room schema v6 (unchanged) · No new features**

Fixes the "red ! but the message WAS delivered" regression for multi-part
Persian (UCS-2) SMS on delivery-reports-enabled SIMs.

## Root cause (found, not guessed)

A multi-part SMS gets one SENT callback **and** one DELIVERED callback per
part. Since v2.4.0, the status receiver treated ANY failed callback —
SENT or DELIVERED — as a sticky message-wide failure. On networks whose
SMSC loses/errs individual delivery reports (seen on IR-MCI with UCS-2
submits), a single errored DELIVERED part flipped a fully-sent message to
STATUS_FAILED even though the recipient received it. The status never
recovered because "a later success must never overwrite a failure".

The path was silent until now because delivery reports were OFF by default;
enabling them exposed the poison path.

## Fix — delivery can only upgrade, never downgrade

New pure policy `SmsStatusPolicy` (unit-tested, 9 tests):

1. SENT-part failure → FAILED (authoritative, sticky). The modem refused;
   the message never left the device.
2. All SENT parts OK → floor = SENT. Delivery reports may only upgrade.
3. DELIVERED-part failure → logged as a "delivery-report gap" (`adb logcat
   -s SMS_STATUS`), status stays SENT. Never FAILED.
4. All DELIVERED parts OK → Delivered.

Prefs keys renamed per phase (`<rowId>_sent_failed`, `<rowId>_dlv_failed`,
`<rowId>_sent_parts`, `<rowId>_dlv_parts`); no migration needed — old keys
were transient callback state.

## Per-SIM SMSC seeding

From on-device reference data (Google Messages, user's own SIMs):

- Irancell (MTN Irancell, 0935… SMSC block): `+9893500001400`
- IR-MCI (Hamrahe Aval, 0911 SMSC block):   `+9891100500`

When a send uses a SIM whose SMSC is not set, `SmscDirectory` seeds the
known-good per-SIM address (key `smsc_sim_<subId>`) and uses it. The user's
manual global SMSC preference still wins; unknown carriers fall back to the
network default.

## Verification
- compileDebugKotlin / testDebugUnitTest **162/162** / assembleDebug green.
