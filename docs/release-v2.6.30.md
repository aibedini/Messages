# Release v2.6.30 — PAIRING-E2E-CLOSURE part 1 (Android side)

**versionCode 72 · no schema change (v7)**

Closes the six runtime blockers from the cross-repo review that made live
pairing fail silently:

## FIX 1 — network off the UI thread

`PairingClient.fetchSessionMetadata()` and `approve()` are now `suspend`
functions running entirely on `Dispatchers.IO`. The BiometricPrompt
success callback only launches a coroutine — `NetworkOnMainThreadException`
is structurally impossible.

## FIX 4 — public-key wire format = DER SPKI/Base64

Registration now converts the raw P-256 points (`0x04||X||Y`) to proper
**DER SPKI** before sending. The GMweb verifier accepts both formats
during transition and cross-language fixtures pin the contract.

## FIX 8/9 — real origin + transcript validation at scan time

* `originMatches()` is now ENFORCED in the scan flow — a QR from any other
  origin is rejected with a visible message (scanner stays live).
* Server metadata hash must match the QR transcript (substitution → abort).

## Scanner fix

An invalid QR no longer locks the scanner (`handled` latches only when the
caller accepts the payload); invalid frames show an error and scanning
continues. Camera/scanner/executor are cleaned up on dispose.

## UI

Every pairing failure now surfaces as a visible error message — no more
silent bounce back to the list.

## Verification

* `assembleDebug` + `testDebugUnitTest` → **224/224**.
* GMweb side (method-aware AgentAuth, global-hook delegation, auto-primary
  role, key format) shipped in v0.13.3 (`32c8109`+), 132/132.

## versionCode

Bumped to **72** (`2.6.30`).
