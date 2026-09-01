# Release v2.6.22 — PR-11 hotfix: enrollment race + dead-letter rescue

**versionCode 64**

> NOTE: versionCode stays in step with the build — bumped to 64.

## Field report (first real device run of v2.6.21)

```
08:16:28 ✅ Gateway server listening
08:16:28 ☁️ Enrolling device identity…
08:16:30 ⛔ 77 event(s) dead-lettered: HTTP 401
08:16:30 ✅ Device identity enrolled: 8238ea53dd4bb0f0
08:16:31 💓 Heartbeat OK
```

`EventUploader` started draining the durable outbox the moment the service
came up, signed its batch with the deviceId, and hit the server **before**
`/api/v1/agent/identity` had finished enrolling that very deviceId. GMweb
answered `401 unknown_device`; LOCK 13 treats a 4xx as a permanent reject →
77 real events went DEAD_LETTER. Two seconds later enrollment succeeded and
every subsequent signed call was green (heartbeat OK) — the exact signature
of a startup race, not an auth failure.

## Changes

* **EventUploader**: identity gate — the outbox loop holds (5 s cadence, zero
  HTTP) until `prefs.identityRegistered` is true. Signed uploads can no
  longer precede enrollment.
* **RegistrationManager**: on successful enrollment, one-shot
  `recoverDeadLetter()` — the current DEAD_LETTER cohort is flipped back to
  PENDING (attemptCount untouched) and the now-unblocked uploader redelivers
  it in normal order. The 77 events are rescued on first launch of this
  build.
* **GatewaySync DAO/Repository**: `resetDeadLetterToPending()` /
  `recoverDeadLetter()` (same reset pattern as `resetSendingToPending`).

## Not changed

* GMweb (server logs confirmed the 401s were `unknown_device` pre-enrollment;
  Eve pull path unaffected — the single 502 was a transient upstream blip
  already retried away).
* Heartbeat, command poller, Room schema (stays v7).

## Verification

* `./gradlew assembleDebug testDebugUnitTest` — green, 196/196.
* Post-install expectation: `♻️ 77 dead-lettered event(s) rescued
  post-enrollment` followed by batch ACKs.

## versionCode

Bumped to **64** in `app/build.gradle.kts` (name `2.6.22`).
