# Release v2.6.29 — pairing diagnostics + counter chip placement

**versionCode 71 · no schema change (v7)**

## Field-report fixes (first live pairing attempt)

### 1. Silent pairing failures made VISIBLE

First live attempt: user scanned the QR and "nothing happened". Two silent
paths existed:

* `fetchSessionMetadata` failure bounced straight back to LIST with the
  error only visible in the SCANNING state (never shown on CONFIRM/LIST).
  → Now every failure surfaces as a red error line wherever the user is
  ("Metadata fetch failed: …"), including on the confirmation screen.
* Scanner callbacks are now logged (`QR_SCAN` tag): payload received /
  parsed OK / did not parse / camera error — so `adb logcat -s QR_SCAN`
  shows exactly where a stuck attempt dies.

### 2. SMS counter chip

The "N پیامک امروز" chip lives on the HOME screen (conversation list),
not inside Settings/Gateway. Optimistic counting ships unchanged from
v2.6.23 (segment-accurate, updates at dispatch, no callback dependency).

## Verification

* `assembleDebug` + `testDebugUnitTest` → **224/224**.

## versionCode

Bumped to **71** (`2.6.29`).
