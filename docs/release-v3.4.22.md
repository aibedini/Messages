# v3.4.22 — one serialized telemetry reporter, and a web-requested refresh

`versionCode 129` · `versionName "3.4.22"` · minSdk 26 · targetSdk 36

Production still shows an old telemetry row (the last one GMweb holds is ~3 days stale) while commands,
event upload and trust are active. This release does two things about that: it makes every telemetry
attempt report **which stage failed** instead of a Boolean, and it adds the encrypted
`REFRESH_DEVICE_TELEMETRY` command so GMweb can demand a fresh report and get a truthful answer.

## 1. Telemetry: one serialized reporter with structured results

* Every attempt returns `Success(2xx, attemptedAt, completedAt, trigger)` or
  `Failure(code)` with the stable codes `NOT_RUNNING`, `GATEWAY_DISABLED`, `IDENTITY_NOT_REGISTERED`,
  `NO_GMWEB_ORIGIN`, `SIGNING_FAILED`, `HTTP_ERROR`, `TRANSPORT_ERROR`, `TIMEOUT`. **A trigger is not
  success, signing is not success, and opening a socket is not success.**
* **At most one telemetry POST is ever in flight.** The 60-second heartbeat, all nine lifecycle
  triggers and a web-requested refresh contend for one report mutex inside the single reporter. No
  second loop, no second scope, no second transport; a refresh arriving during a report waits for it
  and then performs exactly one fresh report.
* `ControlPlaneClient` failures now carry a `FailureKind` (`SIGNING`, `HTTP`, `TRANSPORT`,
  `INSECURE_URL`). Previously a request aborted before the socket and a server rejection both arrived
  as a String — and an aborted request leaves no server-side trace at all, which is exactly how
  "zero telemetry POSTs" stayed unexplained.
* `requestImmediateAndAwait` runs the report in the service scope and bounds only the **wait**, so a
  timeout cannot cancel a socket mid-flight and leave two POSTs overlapping.
* SIM discovery is performed **per report**, never from a cached list. A missing `READ_PHONE_STATE`
  is reported inside a successful payload (`available=false` + reason) — it does not make the phone
  look offline, and it does not become a transport failure.
* The payload advertises `capabilities.commandTypes` (`SEND_SMS`, `MARK_THREAD_READ`,
  `REFRESH_DEVICE_TELEMETRY`) as local build evidence, never derived from server input.
* Diagnostics expose running / eligible + reason / destination host / last trigger, attempt, success,
  HTTP, error / attempts, successes, failures, skipped / last SIM discovery / remote-refresh result
  and count / identity match.

## 2. `REFRESH_DEVICE_TELEMETRY` over the existing encrypted command channel

```json
{"type":"REFRESH_DEVICE_TELEMETRY","reason":"SIM_REFRESH","requestedAt":1700000000000}
```

* Delivered through `/api/v1/agent/commands/claim` — no new endpoint, no Firebase, no WebSocket, no
  local HTTP callback, and `/gateway/pull` is untouched.
* The plaintext `type` must match the envelope type; the only accepted `reason` is `SIM_REFRESH`; an
  absurd future `requestedAt` is refused; unknown **fields** are ignored (additive-safe) while an
  unknown **reason** is never guessed at.
* Execution follows the same durable lifecycle as every other command (`ACCEPTED` → `EXECUTING`), then
  awaits the report: **`COMPLETED` only on a real 2xx**, otherwise a durable `FAILED` carrying the
  matching `TELEMETRY_*` code, which is also what GMweb is ACKed. A queued wake-up is never reported
  as a completed refresh.
* The refresh never enters `GatewayOutgoingPipeline`: routing is a pure function whose only
  SMS-bound value is `SEND_SMS`.
* **Unknown command types are now terminal.** `execute()` used to return silently and leave a claimed
  row non-terminal for ever — GMweb's optimistic entry spun with nothing on the device working on it.
  Any unsupported type reaches a durable `FAILED` with `UNSUPPORTED_COMMAND_TYPE` and is ACKed.

---

## Verification

```text
testDebugUnitTest  2150 tests · 0 failures · 0 errors · 0 skipped   (204 test classes)
assembleDebug      PASS
lintDebug          PASS — 0 errors, 320 warnings, 7 hints (unchanged from baseline)
```

31 new test cases cover the reporter (success only on 2xx, every failure code, eligibility reasons,
single in-flight POST, serialized refresh bursts), the payload sections (permission missing, platform
failure, zero/one/dual SIM, no phone number/IMSI/ICCID, capabilities) and command routing
(`REFRESH_DEVICE_TELEMETRY` never reaches the SMS pipeline; unknown types are unsupported).

---

## NOT PHYSICALLY VERIFIED

```text
The zero-telemetry ROOT CAUSE is still UNPROVEN. This build diagnoses it; it does not prove it fixed.

No physical device, SIM, carrier or GMweb server was used. Unverified on hardware:
  - a real signed POST /api/v1/agent/device-telemetry returning 2xx and a server receivedAt that advances
  - the periodic heartbeat over three intervals
  - the remote refresh end to end (claim → REMOTE_REFRESH trigger → POST → COMPLETED)
  - refresh with READ_PHONE_STATE revoked (must still POST and must NOT report the phone offline)
  - permission re-grant, SIM change, network reconnect and process-death recovery
  - the awaitable timeout path against a real blocking socket: the timeout bounds only the wait, so a
    timed-out refresh may still complete in the background (it cannot overlap another POST)

Still open and NOT addressed here: 359,656 outbox DEAD_LETTER rows (unclassified, not retried), the
history/backfill accounting wording, and the diagnostic HEALTHY/DEGRADED/UNHEALTHY rework.
```

## GMWEB_DEPENDENCIES

1. Confirm the deployed version routes `POST /api/v1/agent/device-telemetry` at all — a 404 and
   "never arrived" are indistinguishable from Android.
2. Send `REFRESH_DEVICE_TELEMETRY` (reason `SIM_REFRESH`) over the existing claim channel and treat the
   `TELEMETRY_*` result codes as the command's outcome.
3. The diagnostic AUTH probe still posts the heartbeat liveness body (`events: []`) and is rejected by
   `400 events_required`; either that contract changed, or the probe needs a different signed route.
4. One transport per `SEND_SMS` (pull task XOR command).
