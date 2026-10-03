# v3.4.24 — telemetry failures now name their stage, and attempts are counted at last

`versionCode 131` · `versionName "3.4.24"` · minSdk 26 · targetSdk 36

A real SM-G998B running 3.4.23 proved the runtime half of the previous mission works — **Installed
Android app 3.4.23 (130) · Command poller running · Runtime advertised yes · Last claim HTTP 200** —
and that device telemetry does not: `Reporter running · Eligible yes · TRANSPORT_ERROR`, with the
contradiction `Attempts 0 / Successes 0 / Failures 10 · Last attempt never`.

## 1. The diagnostic contradiction is fixed (`attempts = 0`, `failures = 10`, `last attempt = never`)

The awaited path — the manual **Test telemetry now** button and the remote refresh — called
`performReport()` directly, and only the periodic loop recorded an attempt. So every failure from the
button was counted against zero attempts, and "last attempt" could stay "never" forever.

`TelemetryHealth.onAttempt(trigger)` is now called **once, inside `performReport()`, before gating,
payload construction, signing and HTTP** — the one place every report passes through. The loop no
longer records it, so the heartbeat cannot be double-counted. The invariant now holds:

```text
attempts == successes + failures      (for completed reports; an in-flight attempt is the only extra)
```

## 2. `PAYLOAD_BUILD_FAILED` is its own code — a local exception is no longer "the network"

`TRANSPORT_ERROR` was produced by two unrelated conditions: a genuine DNS/TCP/TLS/socket failure, and
**any exception while building the local payload**. Reporting the second as the first told the reader
"your internet is broken" about a phone whose command channel was answering HTTP 200 on the same host.

```text
NOT_RUNNING · GATEWAY_DISABLED · IDENTITY_NOT_REGISTERED · NO_GMWEB_ORIGIN
PAYLOAD_BUILD_FAILED · SIGNING_FAILED · HTTP_ERROR · TRANSPORT_ERROR · INSECURE_URL · TIMEOUT
```

Each code now carries a **stage** (`REPORTER`, `ELIGIBILITY`, `PAYLOAD`, `SIGNING`, `HTTP`, `NETWORK`,
`CONFIG`, `TIMEOUT`), and `INSECURE_URL` is no longer folded into `TRANSPORT_ERROR`.

## 3. The failure detail survives into diagnostics

`TelemetryReportResult.Failure.detail` used to be discarded. Retained now, sanitised and bounded:

```text
Failure stage      PAYLOAD
Failure code       PAYLOAD_BUILD_FAILED
Failure detail     payload_build_failed:SQLiteException
Failure exception  SQLiteException
```

or

```text
Failure stage      NETWORK
Failure code       TRANSPORT_ERROR
Failure detail     SocketTimeoutException: Read timed out
```

Host only, no token, no signature, no payload, no number: detail is stripped of phone-like sequences
and truncated to 160 characters. The manual button now shows the same detail in its result line.

## 4. A failing counter can no longer take the heartbeat down with it

Every NON-critical payload section (`battery`, `network`, `sync`, `trust`) is built inside an optional
block: if a diagnostic read throws, that section is omitted (`payload_section_failed name=… errorClass=…`
is traced) and **the report still goes out** with identity, version, capabilities, permissions and SIM
state intact. Identity, signing and the core fields remain mandatory — those failures are never
swallowed, because they are the ones that must be visible.

## 5. Boundary instrumentation (privacy-safe)

`TELEMETRY_REPORT_BEGIN` → `payload_build_begin` / `payload_build_result ok=true|false errorClass=…` →
`SIGN_BEGIN`/`SIGN_RESULT` → `HTTP_BEGIN`/`HTTP_RESULT` → `TRANSPORT_FAILURE errorClass=…` →
`TELEMETRY_REPORT_END result=… http=… code=… stage=…`. No payload, no credential, no number is logged.

## 6. Untouched on purpose

Pairing, identity, keys, the command claim and its `runtime` block (which the device proves works) are
unchanged. No new endpoint, loop, transport or `DeviceTelemetry` instance was added.

---

## Verification

```text
affected test classes (TelemetryReporterTest, TelemetryAttemptAccountingTest,
TelemetryPayloadSectionsTest, GatewayDiagnosticReportTest, CommandRoutingTest)  PASS
assembleDebug   PASS
APK metadata    com.autonomousone.messages · versionName 3.4.24 · versionCode 131
```

New tests pin the invariants: a failed report increments attempts *and* failures; ten failures cannot
coexist with zero attempts; a payload exception is `PAYLOAD_BUILD_FAILED` with stage `PAYLOAD`; signing
stays `SIGNING_FAILED`; a socket exception stays `TRANSPORT_ERROR` with its exception class; HTTP 401
keeps `HTTP_ERROR` + 401; detail is sanitised and bounded; a success clears the previous failure
stage; every code carries a stage.

## PHYSICALLY VERIFIED: NO

```text
The root cause of the production telemetry failure is still NOT PROVEN — this release makes the phone
name the failing stage and the exception class, which is the evidence the last round could not
produce. Press "Test telemetry now" on 3.4.24 and read: Failure stage / Failure code / Failure detail.

Unverified on hardware: the real POST result, a 2xx, GMweb's advancing receivedAt, SIM freshness, and
whether any payload section is the failing one.
```

## GMWEB_DEPENDENCIES (unchanged)

1. GMweb must ingest the claim's `runtime` block to display the running version.
2. Confirm the deployment routes `POST /api/v1/agent/device-telemetry`.
3. `REFRESH_DEVICE_TELEMETRY` exists on Android and is advertised in `runtime.commandTypes`.
