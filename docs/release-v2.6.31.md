# Release v2.6.31 — pairing first-transition fix (root cause: empty gmwebUrl)

**versionCode 73 · no schema change (v7)**

## Root cause (found by review)

The scan flow gated on `GatewayPreferences.gmwebUrl`, which is **blank by
default** — so on any device without manual Gateway configuration:

```text
QR decoded ✓ → parsed ✓ → gmwebUrl="" → originMatches=false → silent return
```

`CONFIRM` never appeared. Pairing must never require manual setup first.

## Fix

* **`PairingEndpointResolver`** (new): trusted URL = user setting **?:**
  `BuildConfig.GATEWAY_BACKEND_URL` (production default). Pairing works
  out of the box.
* **Canonical origin comparison**: scheme+host+effective-port, lowercase,
  HTTPS mandatory. Path/trailing slash/case can't break it; different
  scheme/host/port always rejects. Error message shows BOTH origins.
* **Scanner contract**: `onQr: (String) -> Boolean` — accepted payload
  clears the analyzer (no double delivery); invalid payloads keep the
  scanner live. The `handled` latch is gone.
* **Visible errors**: failures render in an errorContainer card directly
  under the camera (was a small line at the bottom).

## Tests

5 new `PairingEndpointResolverTest` cases (canonicalization, HTTPS
mandatory, host/scheme mismatch, blank fallback). Suite: **229/229**.

## versionCode

Bumped to **73** (`2.6.31`).
