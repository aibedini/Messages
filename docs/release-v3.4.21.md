# v3.4.21 — telemetry is observable, and the phone decides the default line

`versionCode 128` · `versionName "3.4.21"` · minSdk 26 · targetSdk 36

Two changes, one of them forced by production evidence.

---

## 1. The telemetry P0: made observable, root cause NOT yet proven

Production fact: **zero** `POST /api/v1/agent/device-telemetry` requests for seven days, from a phone
whose event upload and pull bridge both return HTTP 200. The last telemetry row the server holds is
from an older install.

What the source can and cannot say:

* The reporter starts (`GatewayService.onCreate` → `DeviceTelemetry.start()`), reports immediately and
  then every 60 seconds, and has nine event triggers.
* Its destination is the single stored origin (`prefs.gmwebUrl`, an alias of `gmwebServerOrigin`) —
  the same host the working `events/batch` leg uses.
* Its gate was a bare Boolean over three prefs, so **"the gateway is off", "the device is not
  enrolled" and "no server configured" were indistinguishable** — and all three looked exactly like
  "healthy".
* `ControlPlaneClient` aborts before opening the socket when signing fails, and that abort left **no
  server-side trace and no durable record**.

So the app could not answer the question it was being asked. This release makes it answer:

* `TelemetryEligibility` returns `eligible` **plus the reason** (`GATEWAY_DISABLED`,
  `IDENTITY_NOT_REGISTERED`, `NO_GMWEB_ORIGIN`); the gate was made diagnosable, not removed.
* Boundary instrumentation in the **durable** diagnostic log (logcat dies with the process):
  `TELEMETRY_INSTANCE_CREATED`, `TELEMETRY_START`, `TELEMETRY_ELIGIBILITY` (on change, booleans +
  reason + host), `TELEMETRY_REPORT_BEGIN` (trigger, attempt, short agent id),
  `SIGN_BEGIN`/`SIGN_RESULT`, `HTTP_BEGIN`/`HTTP_RESULT`, `TELEMETRY_REPORT_END`.
* `ControlPlaneClient` takes an optional trace tag, so telemetry and the known-good event upload emit
  the **same shape** of begin/result lines and can be compared line by line; a signing abort now says
  so instead of being indistinguishable from silence.
* The shareable diagnostic gained a **Phone telemetry** section (running, eligible + reason,
  destination host, last trigger/attempt/success/HTTP/error, attempts/successes/failures/skipped) and
  an identity-match line (stable device vs agent device). The report can no longer say `HEALTHY`
  while the heartbeat is absent.

No secret, body, path or credential is logged — hosts and 8-character ids only.

**This does not mean telemetry works.** It means the next device run names the failing boundary
instead of leaving three candidates.

## 2. MODE A / MODE B SIM semantics at execution time

`MODE B` (a line was named) already refused rather than falling back. `MODE A` (nothing named) now
resolves the **current** system default SMS subscription at the moment of sending:

* `SendSimPolicy.resolveDefault` is pure and takes exactly one input — the platform's own answer — so
  "choose an active subscription because one exists" (the forbidden random SIM 1) is structurally
  impossible rather than a rule to remember.
* The platform answering *"there is no default"* fails closed with
  `SmsSendFailure.NoDefaultSubscription` / `NO_DEFAULT_SMS_SUBSCRIPTION`, recorded exactly like every
  other dispatch rejection.
* The platform *not answering* is a different state and keeps the legacy platform-default manager:
  refusing every default-line send because a listing permission is missing would be worse than the
  risk it guards.
* Web's cached telemetry is never consulted for the line — the phone's current default decides.

v3.4.20's exact-body/multiline/emoji preservation and explicit-subscription propagation are untouched.

---

## Verification

```text
testDebugUnitTest  2119 tests · 0 failures · 0 errors · 0 skipped   (201 test classes)
assembleDebug      PASS
lintDebug          PASS — 0 errors, 320 warnings, 7 hints (unchanged from baseline)
```

---

## NOT PHYSICALLY VERIFIED

```text
No physical device, SIM, carrier or GMweb server was used for this release.

- Zero-telemetry root cause: NOT PROVEN. The instrumented build will name it; until a device run
  records a successful POST, telemetry must be treated as broken.
- Incoming-SMS latency (screen on/off), Android→Web and Web→Android read, phone-default and explicit
  SIM sends, SIM-change and permission-grant telemetry, offline recovery and APK-update recovery are
  all UNVERIFIED on hardware.
- 359,656 outbox DEAD_LETTER rows were NOT classified, NOT retried and NOT recovered. Nothing was
  reset and no schema was changed. History replication remains incomplete.
- Diagnostic verdict rework (HEALTHY/DEGRADED/UNHEALTHY) and Samsung battery/standby diagnostics are
  not part of this release.
- REFRESH_TELEMETRY is not implemented on Android; it needs the GMweb command contract confirmed
  first, and no command type will be invented ahead of it.
```

## GMWEB_DEPENDENCIES

1. Confirm whether `POST /api/v1/agent/device-telemetry` is mounted in the deployed version (0.19.32)
   — a 404 and "never arrived" are indistinguishable from the device side.
2. The diagnostic AUTH probe posts the heartbeat's liveness body (`events: []`) and is rejected with
   `400 events_required`. Either that contract changed, or the probe needs a different signed route.
3. One transport per `SEND_SMS` (pull task XOR command).
4. A conversation-mapping repair handshake is still required for conversation ids this install never
   minted.
