# Release v2.6.37 — Android pairing identity bootstrap

**versionCode 79 · no schema change (v7)**

This patch fixes production QR pairing failures where GMweb rejected the
signed metadata lookup with `401 unknown_device`.

## Pairing fixes

* Android registers or refreshes its stable device identity before opening the
  QR scanner and before fetching pairing metadata.
* Identity enrollment must return HTTP 200 with `{ok:true}` before pairing can
  continue, and publishes the operational signing/encryption keys plus the
  primary trust-root public key.
* Pairing metadata and approval routes use only `X-Agent-Auth`; the shared
  `X-API-Key` remains restricted to identity bootstrap.
* `unknown_device` triggers one identity refresh and one retry with a fresh,
  monotonic timestamp and signature. Other authentication failures do not
  enter a retry loop.
* The approval JSON is signed and sent as the exact same byte array.
* Android records and displays local trust only after `/pairing/approve`
  returns HTTP 200. Failed approval never appears as Trusted or Linked.

## Diagnostics and verification

* Pairing logs contain the endpoint, stage, HTTP status, safe response reason,
  and shortened device/session identifiers without secrets or full signatures.
* Regression tests cover first enrollment, bounded `unknown_device` recovery,
  non-retryable signature/replay failures, byte-identical approval bodies, and
  the HTTP-200-only Linked transition.
* `testDebugUnitTest` and signed `assembleRelease` pass.

## versionCode

Bumped to **79** (`2.6.37`).
