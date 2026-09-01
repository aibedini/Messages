# Release v2.6.28 — ADR-007 review closure (BLOCKER 2/3/4/5 Android side)

**versionCode 70 · no schema change (v7)**

## What ships

Closes the reviewer's blockers on the Android side, matching GMweb v0.13.2
(`e0c8ea0`):

### BLOCKER 2 — PrimaryTrustRoot is real now

* `PrimaryTrustRoot.kt`: dedicated EC-P256 Trust Root signing key in
  **Android Keystore, non-exportable, hardware-backed where available**
  (separate alias/purpose from the AgentAuth HTTP key).
* Web DeviceCertificates are signed by the Trust Root over the canonical
  certificate bytes — NOT by the operational HTTP-auth key.
* `canonicalCertificate()` implements the byte-for-byte shared contract
  (fixed key order, sorted capabilities, compact JSON) mirroring
  `web/src/lib/trustRoot.ts` and GMweb's `src/pairingCanonical.js`.

### BLOCKER 3 — this device declares its role

* Identity registration now posts `role: PRIMARY_TRUST_AGENT`. The GMweb
  approve gate enforces it server-side: a valid signature from any OTHER
  agent identity → 403. No implicit assumptions.

### BLOCKER 4 — transcript binding before approval

* `PairingClient.fetchSessionMetadata` (agent-signed GET) fetches the
  server transcript and rejects on hash mismatch vs the scanned QR
  (substitution attack → abort).
* The certificate binds the server-verified web public keys (never opaque
  IDs), and `trustRootPublicKey` is posted so the browser can verify the
  root signature itself.

### BLOCKER 5/6 — already live (v2.6.27)

BiometricPrompt before approval; sensitive-policy summary on the
confirmation screen; LOCAL_ONLY invariant unchanged (no outbox rows).

### Shared test vectors

* `PrimaryTrustRootCanonicalTest` (3 tests) pins the Android canonical
  serialization to the SAME fixture as GMweb's
  `test/pairingTranscriptVectors.test.js` (10 tests) — Kotlin ↔ TypeScript
  drift is now a build failure.

## Verification

* `assembleDebug` + `testDebugUnitTest` → **224/224**.
* GMweb full suite → **132/132** (`e0c8ea0`).

## versionCode

Bumped to **70** (`2.6.28`).
