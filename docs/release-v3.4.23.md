# v3.4.23 — live runtime advertised independently of telemetry, and on-device telemetry diagnostics

`versionCode 130` · `versionName "3.4.23"` · minSdk 26 · targetSdk 36

Three different facts are now permanently separated, because conflating them is what made a working
phone look dead and a dead telemetry path look healthy.

## 1. Live runtime (this build) — independent of the telemetry reporter

The signed command claim now carries the **installed APK's own identity**, advertised on every claim:

```json
{
  "agentId": "<device id>",
  "limit": 25,
  "runtime": {
    "protocolVersion": 1,
    "appVersionName": "<PackageInfo.versionName of the RUNNING apk>",
    "appVersionCode": <PackageInfo.longVersionCode>,
    "commandTypes": ["SEND_SMS", "MARK_THREAD_READ", "REFRESH_DEVICE_TELEMETRY"]
  }
}
```

* The version comes from `PackageManager` at claim time — never from a constant, never from a cached
  telemetry report. A build that has never successfully sent telemetry still tells GMweb exactly which
  APK is running.
* The command list comes from this build's single supported-command definition, so an advertised
  capability cannot drift away from what the executor can actually run.
* **The bytes signed are the bytes transmitted**: the same `ByteArray` that `AgentAuth.sign` covers is
  the one written to the socket; the runtime block is inside it, so it is authenticated as well.

## 2. Phone telemetry diagnostics on the device

The gateway screen gained a **Phone telemetry** card with two clearly separated sections:

```text
Live agent runtime        Installed app, command poller, runtime advertised,
                          command types, last claim HTTP
Device telemetry          Reporter, eligible + reason, destination host,
                          last trigger / attempt / success / HTTP / failure code,
                          attempts · successes · failures · skipped,
                          SIM discovery + active count + reason,
                          remote refresh support / last result / count
```

* **Test telemetry now** forces exactly ONE attempt through the **production reporter**
  (`DeviceTelemetry.requestImmediateAndAwait(MANUAL_DIAGNOSTIC_REFRESH, 10s)`) — no HTTP is built in
  the UI, no client is called directly, no second reporter or loop is created, and the attempt
  participates in the same report mutex as the heartbeat.
* The button runs through `IDLE → RUNNING → SUCCESS/FAILED`, is disabled while a report is in flight
  (no double taps), and shows the precise outcome: `HTTP 200`, or the stable code
  (`SIGNING_FAILED`, `HTTP_ERROR · HTTP 401`, `NOT_RUNNING`, `TRANSPORT_ERROR`, `TIMEOUT`, …).
* **Success is only ever a real 2xx.** A queued trigger, an existing reporter, a successful signature
  or an opened socket are never reported as success.
* SIM state stays separate from transport state: a missing `READ_PHONE_STATE` or zero active
  subscriptions is shown as a SIM fact, never as a failed POST and never as "phone offline".
* Nothing sensitive is displayed: host only (no path, no token, no signature), no phone numbers, no
  IMSI/ICCID, no payloads.

## 3. Command-runtime health (why the advertisement is provable)

The poller records, per claim: attempt time, HTTP status, last success, the version name/code it
advertised and the advertised command-type count. That is what lets Android diagnostics answer *"did
this running build actually advertise 3.4.23 to GMweb?"* without a second network request and without
storing a response body, ciphertext, plaintext or credential.

## 4. What was NOT added

No new endpoint, no WebSocket, no Firebase, no local HTTP callback, no second polling loop, no second
`DeviceTelemetry` instance, no second runtime reporter. The runtime block rides the claim request that
already existed, and the manual diagnostic rides the reporter that already existed.

---

## Verification

```text
testDebugUnitTest  2150 tests · 0 failures · 0 errors · 0 skipped
assembleDebug      PASS
lintDebug          PASS — 0 errors (warnings unchanged from baseline)
APK metadata       applicationId com.autonomousone.messages · versionName 3.4.23 · versionCode 130
```

## PHYSICAL VERIFICATION STATUS: NOT PHYSICALLY VERIFIED

```text
No physical device was used for this release. The on-device flow that must be observed:
  - Live agent runtime shows the installed build and "Runtime advertised: yes"
  - Test telemetry now → TELEMETRY_REPORT_BEGIN → SIGN_RESULT success → HTTP_RESULT 2xx →
    TELEMETRY_REPORT_END result=success
  - the card then shows Reporter running / Eligible yes / HTTP 2xx / Last success just now

The zero-telemetry ROOT CAUSE from the previous mission is still UNPROVEN. This release makes the
running build's identity independent of telemetry and puts the failure stage on screen; it does not
prove the telemetry POST succeeds until a device run shows a 2xx.

Also unchanged and still open: 359,656 outbox DEAD_LETTER rows (unclassified, not retried), the
history/backfill accounting wording, and the deployment question of whether GMweb routes
/api/v1/agent/device-telemetry at all.
```

## GMWEB_DEPENDENCIES

1. GMweb must ingest the `runtime` block of the claim (store + display it) — Android advertising it is
   not the same as GMweb showing it.
2. Confirm the deployed version routes `POST /api/v1/agent/device-telemetry`.
3. `REFRESH_DEVICE_TELEMETRY` (reason `SIM_REFRESH`) is implemented on Android and advertised in
   `runtime.commandTypes`; the server must send it and read the `TELEMETRY_*` result codes.
