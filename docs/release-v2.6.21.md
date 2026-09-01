# Release v2.6.21 — PR-11: identity enrollment on the ADR-004 control plane

**versionCode 63 · no Room schema change (stays v7)**

## Field report

v2.6.20 shipped the durable platform (PR-01/02/05/08b/10) but the device still
knocked on the retired v1 door: `RegistrationManager` POSTed
`/api/gateways/register` and `HeartbeatManager` POSTed `/api/gateways/heartbeat`
— endpoints that **do not exist** on the deployed GMweb (v0.12.1). The phone
could therefore never enroll; `EventUploader` further authenticated with
`Authorization: Bearer <token>` which the control plane never issued and the
shared-key fallback would have dead-lettered every batch as a permanent 401.

## Changes (Android only — GMweb untouched)

### RegistrationManager — identity enrollment (PR-11)

* Endpoint → `POST /api/v1/agent/identity` (ADR-004 control plane, PR-08b
  device-key bootstrap).
* Payload keeps the PR-05 `publicKeys` block (signing / encryption /
  trustRoot, base64 uncompressed EC points) keyed on the SSOT
  `GatewayPreferences.stableDeviceId()` (ANDROID_ID, else persisted random
  hex); descriptive `deviceModel` / `appVersion` / `androidVersion` fields
  ride along for diagnostics.
* Response `{ok:true}` carries **no** server-issued gatewayId/token. On
  success: `gatewayId := stableDeviceId`, `identityRegistered := true`,
  `isRegistered := true`, `gatewayToken := identity-enrolled-v2` (sentinel for
  legacy bookkeeping — heartbeat tolerates either marker).
* `getDeviceId()` collapses onto the SSOT helper (single definition).

### HeartbeatManager — control-plane liveness ping

* Endpoint → `POST /api/v1/agent/events/batch` with an **empty** `events`
  array + `sourceDeviceId`: a pure liveness probe —
  `eventStore.ingestBatch` returns `{accepted:[],duplicates:0}` without
  consuming a sequence.
* Auth identical to every other agent call: `X-API-Key` (bootstrap) +
  `X-Agent-Id`, and a real `X-Agent-Auth` ECDSA signature over the exact body
  (required once the device has enrolled).

### BackendClient — signed requests

* `post(...)` gained an optional `signer` callback receiving the open
  connection and the EXACT body bytes; a `false` return aborts the request
  before anything is sent (fail closed, ADR-001).

### EventUploader — per-device signatures

* Every batch POST now carries `X-Agent-Auth` bound to
  `prefs.agentDeviceId()` — the same identity enrollment keyed on. This
  prevents the 401→DEAD_LETTER path that unsigned batches would have hit.

### Build

* `GATEWAY_BACKEND_URL` default → `https://gmweb.46.31.76.103.nip.io`
  (deployed GMweb; still overridable via `gradle.properties`).
* `versionCode 63`, `versionName "2.6.21"`.

## Verification

* `./gradlew assembleDebug` — green.
* `./gradlew testDebugUnitTest` — 196/196 green.
* Server-side contract cross-checked against GMweb v0.12.1 source
  (`src/agentIdentityRoutes.js`, `src/agentAuth.js`, `src/server.js`
  requireToken hook, `src/controlPlaneRoutes.js`): 99/99 GMweb tests green,
  no server changes required.

## Post-install (device)

Install over the top of v2.6.20 (data preserved). First launch of the gateway
service enrolls the device; then in the app set `gmwebUrl` to
`https://gmweb.46.31.76.103.nip.io` so the pull bridge and command poller run
against the same control plane.
