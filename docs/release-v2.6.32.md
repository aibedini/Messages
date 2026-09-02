# Release v2.6.32 — pairing 401 root cause: X-API-Key bypassed signature gate

**versionCode 74 · no schema change (v7)**

## Root cause (device-verified)

Android sends `X-Agent-Auth` + `X-Agent-TS` **and** `X-API-Key` together.
GMweb's global pairing gate had:

```js
if (checkDeviceKey(request)) return done();   // X-API-Key shortcut
const auth = agentAuthService.verifyAgentHeader(...)  // never reached
```

A valid shared key therefore skipped signature verification entirely —
`authenticatedAgentId` was never set and the route's role check rejected
with **401**. Exactly the device error:
`pairing session lookup failed: HTTP 401`.

## Fix

* **GMweb** (`32c8109`→`e38be95` line): trust-sensitive pairing routes are
  now **signature-required** — the `checkDeviceKey → done()` shortcut is
  removed; `X-API-Key` is ignored if present and can never bypass the
  signature. Rejection bodies include the safe `reason`.
* **3 regression tests** in the real-app composition suite:
  key+signature → 200; key-only GET → 401; key-only approve → never 200.
* **Android diagnostic**: fetch failures now surface the server's safe
  reason (`reason=unknown_device | signature_mismatch |
  timestamp_out_of_window`) — body read capped, secrets never logged.

## Tests

GMweb: 141/141 (8 in E2E composition). Messages: **229/229**.

## versionCode

Bumped to **74** (`2.6.32`).
