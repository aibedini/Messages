# Release v2.6.27 — Android Linked Devices (ADR-007 primary trust device flow)

**versionCode 69 · no schema change (v7)**

## What ships

The Android side of ADR-007 — this phone is now the PRIMARY TRUST DEVICE for
web pairing:

### Settings → Linked devices

* Entry row added to Settings ("Pair the web app by scanning its QR code").
* `[+ Link new device]` → camera permission → live **QR scanner**
  (ML Kit barcode, fully on-device).
* QR payload parsed + validated locally: it must be a canonical Messages
  pairing transcript (pairingSessionId/webDeviceId/origin/…).
* **Origin check (P0-5)**: the scanned origin must match the configured
  GMweb origin — a QR from any other site is rejected as phishing before any
  confirmation UI.
* Session metadata is fetched from GMweb **with the agent's X-Agent-Auth
  signature**; the server transcript hash must match the QR hash
  (**P0-8** substitution check).
* Confirmation screen shows exactly what is being linked (origin,
  requested capabilities, history grant, and the sensitive-messages
  guarantee that OTP/bank codes never leave this phone).
* **BiometricPrompt / device credential BEFORE approval** (§ user presence):
  the certificate is signed only after the local user confirms.
* On approve: a DeviceCertificate (WEB_PWA, capabilities, historyGrant,
  trustSequence, pairingTranscriptHash) is signed with the Trust Root
  operational key and POSTed to GMweb with an X-Agent-Auth-signed request
  (P0-2 — the only identity that may approve).
* GMweb stays a relay: the browser completes pairing by polling and
  verifying the certificate binding on its side (web P0-7, already shipped).

### Dependencies

* ML Kit barcode-scanning 17.2.0 + CameraX 1.3.4 (on-device scanning; no
  cloud). CAMERA permission added.

## Verification

* `assembleDebug` + `testDebugUnitTest` — green, **221/221**.
* Full loop (device + browser): /web shows QR → phone scans → confirm →
  biometric → browser shows "Device linked ✓".

## versionCode

Bumped to **69** (`2.6.27`).
