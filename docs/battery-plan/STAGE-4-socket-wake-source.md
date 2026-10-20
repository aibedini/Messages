# STAGE 4 — `SocketWakeSource`: self-hosted push over WSS

**Read [`README.md`](README.md) first.** §3 constraint 4 (no content on a wake
channel) is the backbone of this stage's security model.

**Branch:** `perf/battery-stage-4`
**Depends on:** Stage 2 (the `WakeSource` seam) and Stage 3 Part B (OkHttp).
**Needs GMweb server work:** yes — contract in
[`MANUAL-STEPS.md`](MANUAL-STEPS.md) §2.2.
**Risk:** medium. This is the first stage that adds a long-lived network object.

---

## 0. What this buys, and what it does not

This is the answer to "push without Google": one connection the device owner
controls end to end, on infrastructure they already run.

It does **not** reach FCM's battery profile, and the plan must not pretend
otherwise. FCM rides a connection that Play Services already maintains for the
whole device, so an app pays nothing to keep it alive. A self-hosted socket is
*this app's own* connection and must pay its own keepalive. The honest claim:

- a 4-minute ping interval is **~360 keepalive frames / 24 h**, against
  **3 456 long-poll requests** after Stage 1.1 or **43 200** today;
- a keepalive frame is a few bytes with no TLS handshake and no ES256
  signature, so it is far cheaper than a request;
- wake latency becomes the server's write latency instead of up to 25 s.

State it in those terms. Do not convert to a battery percentage (README §0).

### The Android 15 constraint that must be checked first

ADR-003 records:

> from Android 15 the `dataSync` foreground-service type has a cumulative
> 6-hour-per-24h background cap, so a permanent dataSync FGS cannot be the
> reliability pillar … The FGS strategy is re-audited separately against the
> Android 15 6h/24h cap before Phase 2 ships it.

The manifest declares `android:foregroundServiceType="dataSync|specialUse"`
(`AndroidManifest.xml:228`). **That audit is a precondition of this stage**, not
a footnote: a socket held open by a service that the OS stops after 6 cumulative
hours is a bridge that dies every day. Before writing code:

1. Determine which declared type the service actually runs under at runtime on
   Android 15+, and whether the cap applies to it.
2. If the cap applies, decide between `specialUse` with a documented
   justification, `connectedDevice`, or accepting the cap with an
   `AlarmManager`-based recovery path. ADR-003 permits exact alarms as a
   **recovery** mechanism only, never as a polling mechanism.
3. Record the finding in the report. **If the audit cannot be completed, stop
   and report** — do not ship a socket whose lifetime is unknown.

---

## 1. Protocol

### Endpoint

```
GET  wss://<gmwebServerOrigin>/gateway/wake
```

`gmwebServerOrigin` is already user configuration
(`GatewayPreferences.KEY_SERVER_ORIGIN`, line 61), and
`app/build.gradle.kts:44–56` records the deliberate decision that there is **no
compiled-in production default**. Honour that: derive the WSS origin from the
stored HTTPS origin by scheme substitution, and never fall back to a hard-coded
host.

### Handshake authentication — reuse `AgentAuth`, invent nothing

The upgrade request carries exactly the headers every other agent call carries:

```
X-Agent-Auth: <deviceId>:<base64(derSignature)>
X-Agent-TS:   <epochMs>
```

signed over the existing canonical string:

```
GET\n/gateway/wake\n<sha256hex("")>\nX-AGENT-TS:<ts>\n
```

Body is empty, so the body hash is the SHA-256 of the empty string. The path is
taken **without the query**, which is already the contract.

Why this and not a bearer token: ES256 over the operational signing key in the
Keystore (`DeviceIdentity`, PR-05) is already the project's device
authentication, GMweb already verifies it in `src/agentAuth.js`, and
`AgentAuth.REPLAY_WINDOW_MS = 90_000L` already bounds replay. A second
credential type would be a second thing to rotate, revoke and get wrong.

`AgentAuth.freshTimestamp()` already guarantees strictly increasing timestamps
across concurrent callers; use it rather than `System.currentTimeMillis()`.

**Fail closed.** `AgentAuth`'s KDoc states §83: if the Keystore cannot sign, the
request is aborted. The socket follows the same rule — no unsigned upgrade
attempt, ever.

### Frames — the entire message vocabulary

Server → client, the **only** permitted frame:

```json
{"t":"wake"}
```

Optionally with a monotonic sequence for gap detection:

```json
{"t":"wake","seq":12345}
```

Client → server: nothing but protocol-level pongs.

**Forbidden in both directions, and a reviewer must reject it:** task ids,
request ids, recipients, phone numbers, message bodies, command payloads,
counts, previews. If the server starts sending `{"t":"wake","to":"+98…"}`, the
whole security argument in §2 collapses.

On `{"t":"wake"}` the client does exactly one thing:

```kotlin
onWake()   // → OutboxPoller.retryNow(), which already exists (OutboxPoller.kt:362)
```

`retryNow()` resets `errorRetryMs` and pokes the conflated `wake` channel, so a
burst of ten wake frames collapses into one pull cycle. That conflation is
already load-bearing for the "ten impatient taps" case and it is exactly the
property needed here.

Any unrecognised frame is **ignored and counted**, never parsed for meaning.
Log the count, not the content.

---

## 2. Security model

Because the channel carries one bit, the threat model is small and can be
stated completely:

| Threat | Control |
|---|---|
| Eavesdropping | WSS only. `ws://` is rejected unconditionally — not a setting, not a debug flag |
| MITM with a forged certificate | certificate pinning, §2.1 |
| Impersonating the device | ES256 handshake signature over the Keystore key; server verifies against `publicKeys.signing` from registration |
| Replaying a captured handshake | timestamp inside the signed material + existing 90 s replay window |
| Hostile server causing battery drain by spamming wakes | client-side wake rate limit, §2.2 |
| Content disclosure via the socket | **structurally impossible**: no content is ever sent on it |
| Socket used to inject commands | impossible: a wake frame triggers a *pull*; commands still arrive only over the authenticated REST path with their existing idempotency and `CommandTerminalTransition` checks |

That last row is the payoff of README §3 constraint 4. Even a fully
compromised socket endpoint can only make the phone call its own server.

### 2.1 Certificate pinning

The server is self-hosted, so the owner controls the certificate and pinning is
practical rather than fragile.

- Use OkHttp `CertificatePinner` on the WSS client.
- The pin must be **user-configurable** and stored in preferences alongside
  `gmwebServerOrigin` — a compiled-in pin would contradict the
  no-compiled-in-server decision in `build.gradle.kts:44–56`.
- Support **two pins** (current + next) so certificate rotation does not brick
  the bridge. This is not optional: a single pin plus a Let's Encrypt renewal is
  a guaranteed outage.
- A pin mismatch is `WakeUnavailable.TRANSPORT_FAILED`, surfaced on the
  diagnostic card with a distinct message, and it falls back to polling. It must
  **never** silently disable pinning.
- If the owner sets no pin, run without pinning and say so on the diagnostic
  card. Do not block the feature on it, and do not pretend it is pinned.

### 2.2 Wake rate limit

A server that sends 100 wakes/second would turn push into the worst polling
loop in the project. Bound it client-side:

- at most one `onWake()` per 1 000 ms (the conflated channel already collapses
  bursts; this caps the *pull* rate);
- if inbound frames exceed a sane ceiling (say 60/minute) for several minutes,
  treat the server as misbehaving: disconnect, back off, report
  `TRANSPORT_FAILED`, fall back to polling.

`GatewayServer.kt:64–65` already establishes the project's pattern for this kind
of defence (`AUTH_WINDOW_MS`, `LOCKOUT_MS` for brute force) — follow its shape.

---

## 3. The adaptive activity window — where battery and latency are traded

A socket held open 24/7 pays keepalive 24/7. Hold it open only when someone is
actually waiting for low latency.

```
ACTIVE   ⇐  a web client has a live session for this device
         ∨  the app is in the foreground
         ∨  an SMS was sent or received within the last 5 minutes
         ∨  the outbox is non-empty
   →  socket connected, ping interval 4 min, wake latency ≈ server write latency

IDLE     ⇐  none of the above
   →  socket closed; fall back to the Stage 1.1 long-poll at its 25 s hold
```

The first condition is the interesting one and it is what makes the product
feel right: **GMweb knows whether a web client is watching.** When the owner
opens the web panel, GMweb flips the device to ACTIVE and the phone connects;
when the tab closes, the phone goes back to sleep. Real-time exactly when a
human is looking, near-nothing otherwise.

Implementation notes:

- the ACTIVE/IDLE decision is a **pure function** of those inputs, in its own
  Android-free class, pinned by tests — follow `PollStatePolicy` and
  `GatewayAccessPolicy`, which the codebase already uses for exactly this
  purpose ("one pure decision per iteration, so the precedence … is asserted in
  a test rather than read out of two branches here", `OutboxPoller.kt:~408`);
- ACTIVE must be driven by a **server hint delivered on the wake channel's own
  connect response or by the existing telemetry/heartbeat reply** — not by a new
  poll asking "is anyone watching", which would reintroduce the problem;
- the 5-minute SMS window reuses existing timestamps; add no new tracking;
- transitions are debounced (reuse the 3 s pattern at
  `ConnectionSupervisor.kt:328`) so a flapping web session does not reconnect in
  a loop.

### Keepalive tuning

Start at **4 minutes** (`OkHttpClient.Builder().pingInterval(...)`). Rationale:
long enough to be far cheaper than any polling cadence, short enough to stay
inside common NAT and load-balancer idle timeouts (frequently 5 minutes).

Make it configurable, and record in the report what value shipped. If the
owner's reverse proxy has a shorter idle timeout, the symptom is a socket that
dies silently every N minutes — which the health counters in §5 will show.

---

## 4. Fallback ladder

```
SOCKET fails to connect, or the handshake is rejected, or a pin mismatch,
or the server misbehaves (§2.2), or the device goes IDLE
        │
        ▼
POLLING (Stage 1.1 long-poll, 25 s hold)        ← the permanent floor
```

Rules:

1. **Polling is never removed.** It is the only source that needs no platform
   support and no new server capability.
2. **Every fallback is reported**, with its `WakeUnavailable` reason, on the
   diagnostic card. Silent degradation is how a 2-second poll comes back
   unnoticed.
3. Reconnection uses full-jitter exponential backoff. `EventUploader` already
   uses `GatewaySyncRepository.Policy.backoffDelayMs(attempt, Random)` — reuse
   it rather than writing a second ladder.
4. `AUTH_REJECTED` (a 401 on the upgrade) must **not** trigger the
   credential-wipe-and-re-enroll path. `AgentAuth`'s KDoc warns that a 401 makes
   the device wipe its credentials via `ControlPlaneAuthPolicy`. A socket upgrade
   is not evidence that the whole identity is invalid — a server that simply has
   not deployed `/gateway/wake` may well answer 401 or 404. Treat an upgrade
   rejection as a transport failure, fall back to polling, and **leave the
   credentials alone.** Getting this wrong de-enrolls every device the day the
   feature ships.

---

## 5. Health and observability

Extend `WakeHealth` (Stage 2) and the existing `GatewayHealthRecorder`:

- socket state: connecting / open / closed, with the last transition time;
- `wakeCount`, `keepalivesSent`, `reconnectCount`, `consecutiveFailures`;
- last `WakeUnavailable` reason;
- ACTIVE/IDLE state and which input put it there;
- pinning: on / off / mismatch.

Counts and timings only — no ids, no recipients. `PollLoop`'s telemetry comment
("Counts and timings only: no request ids, no recipients, nothing that a
diagnostic export could leak") is the standard to match.

---

## 6. Tests to add

| Test | Asserts |
|---|---|
| `WakeHandshakeCanonicalStringTest` | the signed string is exactly `GET\n/gateway/wake\n<sha256hex("")>\nX-AGENT-TS:<ts>\n` — pins the cross-repo contract |
| `WakeFrameParsingTest` | `{"t":"wake"}` → one `onWake()`; unknown frames ignored and counted; a frame carrying extra fields is **not** inspected for them |
| `WakeRateLimitTest` | 100 frames in one second produce at most one `onWake()` per second |
| `WakeAbuseDisconnectTest` | sustained excess frames disconnect, back off and report `TRANSPORT_FAILED` |
| `ActivityWindowPolicyTest` | truth table for all four ACTIVE inputs plus debounce |
| `SocketFallbackLadderTest` | each failure mode lands on POLLING with the right `WakeUnavailable` |
| `SocketAuthRejectionDoesNotWipeCredentialsTest` | a 401/404 upgrade response leaves stored credentials intact — guards the §4.4 trap |
| `PlaintextWebSocketRejectedTest` | a `ws://` origin is refused |
| `CertificatePinRotationTest` | two configured pins both validate; a third certificate fails |

`SocketAuthRejectionDoesNotWipeCredentialsTest` is the one that prevents a
field incident. Do not skip it.

---

## 7. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| Android 15 `dataSync` 6 h/24 h cap kills the socket daily | high | §0 audit is a precondition; alarms as recovery only |
| Upgrade 401 triggers credential wipe across the fleet | high | §4.4 + dedicated test |
| Single cert pin + renewal = outage | high | two pins, §2.1 |
| NAT/proxy idle timeout shorter than ping interval | medium | configurable interval; reconnect counters make it visible |
| Socket open 24/7 erases the battery gain | medium | the ACTIVE/IDLE window in §3 |
| Scope creep into migrating all REST calls to OkHttp | medium | Stage 3 Part B §3 forbids it |
| Doze closing the socket looks like server failure | low | ADR-003 already defines Doze as outside the hard SLO; report it as suspension, not failure |

Rollback: set `wakeMode = POLLING`. The socket code becomes inert without a
rebuild, which is the reason Stage 2 exists.

---

## 8. Acceptance checklist

- [x] Android 15 FGS audit completed and recorded, or the stage stopped and
      reported
- [x] `SocketWakeSource` implements the Stage 2 interface; carries **no payload**
- [x] Handshake signed via `AgentAuth`; fails closed when the Keystore cannot sign
- [x] `ws://` rejected unconditionally
- [x] Two-pin certificate pinning, user-configurable, mismatch reported not bypassed
- [x] Wake rate limit and abuse disconnect implemented
- [x] ACTIVE/IDLE policy is a pure, tested function
- [x] Fallback ladder to POLLING, every fallback reported with a reason
- [x] Upgrade rejection does **not** wipe credentials, with a test proving it
- [x] Mode picker in settings (AUTO / POLLING / SOCKET), with the active source
      and any fallback shown on the diagnostic card
- [x] Health counters exposed; counts and timings only
- [x] Gate passes
- [x] Report appended per [`README.md`](README.md) §5

---

<!-- The implementing agent appends the report from README.md §5 below. -->

## Implementation report

Agent: deepseek-flash (DeepSeek Harness)   Date: 2026-10-19
Branch: perf/battery-stage-4               Base commit: 8bb088d

GATE-VERIFIED: YES
TESTS EXECUTED: 2735
COMPILE STATUS: PASS

### Step 0 — Android 15 foreground-service audit (precondition)

The `type` passed to `startForeground` is computed in
`GatewayService.kt:697` from `GatewayForegroundStartPolicy.decide(...)`:

```kotlin
val wantsDataSync = GatewayForegroundStartPolicy.includesDataSync(decision)
val type = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    wantsDataSync ->
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    else ->
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
}
startForeground(NOTIFICATION_ID, notification, type)
```

`decide(apiLevel, startReason)` (`GatewayForegroundStartPolicy.kt:40`) returns
`START_WITH_DATA_SYNC` below API 35 and `START_SPECIAL_USE_ONLY` from API 35.
The resulting flags per SDK branch are therefore:

| SDK branch | Flags | Why |
|---|---|---|
| API < 34 (`UPSIDE_DOWN_CAKE`) | `DATA_SYNC` only | pre-timeout behaviour |
| API 34 | `DATA_SYNC \| SPECIAL_USE` | `decide` still returns DATA_SYNC |
| API 35+ | `SPECIAL_USE` only | `decide` refuses the dataSync budget |

**DATA_SYNC on API 35+: no.** The manifest declares
`foregroundServiceType="dataSync|specialUse"` (`AndroidManifest.xml:228`), so the
platform would accept the bit, but including it would enrol this permanent
gateway in the Android 15 6-hour-per-24-hour `dataSync` cap — precisely the
failure the stage says must not ship. The policy already excludes it, so the
socket-holding service runs `specialUse`-only on API 35+ and the cap does **not**
apply. This satisfies §0's precondition. Residual risk: any future change that
re-adds `DATA_SYNC` at API 35+ silently reintroduces the daily cap.

### A. Completed

| Item | What was changed (file:line) | Commit | Test proving it |
|---|---|---|---|
| okhttp3 4.12.0 pinned as a direct dependency, legacy 2.5.0 untouched | `gradle/libs.versions.toml:22,44`, `app/build.gradle.kts:175` | 8477adc | `:app:dependencies --configuration debugRuntimeClasspath` resolves `+--- com.squareup.okhttp3:okhttp:4.12.0 (*)`; `com.squareup.okhttp:okhttp:2.5.0` and `okhttp-urlconnection:2.5.0` remain |
| WSS endpoint derived by scheme substitution; `ws://` impossible | `WakeSocketEndpoint.kt:12` | d1d0168 | `PlaintextWebSocketRejectedTest` (4 tests) |
| Upgrade signed with the existing `AgentAuth` canonical string and operational key, fail closed | `AgentAuth.kt:78`, `AgentAuthWakeSigner.kt:11`, `WakeHandshake.kt` | d1d0168 | `WakeHandshakeTest` (3 tests), including the on-the-wire `X-Agent-Auth`/`X-AGENT-TS` assertion |
| Two-pin, user-configurable certificate pinning; mismatch reported, never bypassed | `WakeCertificatePins.kt:17`, `GatewayPreferences.kt:126` | d1d0168 | `CertificatePinRotationTest` (4 tests) + live pin-mismatch case in `SocketFallbackLadderTest` |
| One-second wake floor and sustained-abuse disconnect | `WakeRateLimiter.kt:13` | d1d0168 | `WakeRateLimitTest` (2), `WakeAbuseDisconnectTest` (1) |
| Adaptive ACTIVE/IDLE window, pure and debounced, wired to the socket | `WakeActivityPolicy.kt:48`, `AppForegroundTracker.kt` | d1d0168 | `WakeActivityPolicyTest` (6), including open/close wiring |
| `SocketWakeSource`: handshake, frames, 4-minute ping, reconnect backoff, fallback | `SocketWakeSource.kt:68` | d1d0168 | `SocketKeepaliveTest` (2), `SocketFallbackLadderTest` (3) |
| 401/404 upgrade is a transport failure and never wipes credentials | `SocketWakeSource.kt:383` (no credential API exists in the class) | d1d0168 | `SocketAuthRejectionDoesNotWipeCredentialsTest` (2) |
| `wakeMode=SOCKET` selects the socket; AUTO still resolves to POLLING | `WakeSourceSelector.kt:28`, `GatewayService.kt:103` (`SERVER_WAKE_DEPLOYED=false`) | d1d0168 | `WakeSourceSelectorStage4Test` (3) |
| Settings mode picker (AUTO/POLLING/SOCKET) and two pin fields in the existing Gateway section | `SettingsScreen.kt:784` | d1d0168 | compiles under `compileDebugAndroidTestKotlin`; no screenshot/instrumentation test added |
| Health: socket state, ACTIVITY + reason, pinning, reconnects, suppressed frames | `GatewayHealth.kt`, `GatewayHealthRecorder.kt`, `GatewayHealthCard.kt:256` | d1d0168 | exercised by the socket tests' `WakeHealth` assertions |
| JVM test harness: in-process TLS WebSocket server | `app/src/test/.../FakeWakeServer.kt` | 8394d18 | 31 new tests across 10 classes; all green |

Test count: the full suite ran **2735 tests / 266 result classes, 0 failures,
0 skipped**. The Stage 4 additions are **31 tests**.

Release sanity (not part of the §4 gate): `:app:assembleRelease` passed
`minifyReleaseWithR8` with OkHttp's consumer rules at 30 371 307 bytes. The APK
is unsigned in this environment (no `keystore.properties`/`KEYSTORE_*`).

### B. Not completed

| Item | Why not | Blocker type | What would unblock it |
|---|---|---|---|
| GMweb `GET wss://…/gateway/wake` | The server does not implement it (§0 of the task and MANUAL-STEPS §2.2) | server-dependency | Deploy the endpoint with the signed upgrade + `{"t":"wake"}` frames, then set `SERVER_WAKE_DEPLOYED=true` |
| End-to-end socket test against GMweb | No server endpoint to connect to | server-dependency | The deployment above |
| On-device measurement of the socket's battery effect | No device run was performed; this programme forbids inventing one | server-dependency | MANUAL-STEPS §3 measurement with the endpoint deployed |
| Pausing the long-poll while the socket is ACTIVE | Deliberately not attempted: the pollers are also the only fetch path, and no on-demand single-cycle entry point exists. Stopping them would put the ACK ledger/SLO at risk | out-of-scope | A separate designed stage with on-demand claim support and measurement |
| Web-client ACTIVE hint from heartbeat/telemetry replies | `BackendClient.Result.Success` exposes no response headers; only the upgrade response header is read | out-of-scope | Extend the result type or add a hint field/server call |
| A distinct 401/404 status on the diagnostic card | OkHttp reports a non-101 upgrade as a failure without a public response; it is correctly classified `TRANSPORT_FAILED` and never wipes | out-of-scope | An interceptor/event listener if the status must be displayed |
| `keepalivesSent` counter | OkHttp exposes no ping callback; reporting a count would invent one | out-of-scope | A raw WebSocket implementation (rejected in Stage 3 Part B) |

### C. Deviations from the plan

1. **The AUTO ladder is gated.** ADR-008 says AUTO → SOCKET if available. The
   task requires default behaviour to stay POLLING, so `Inputs` gained
   `socketAutoEligible` and production passes `SERVER_WAKE_DEPLOYED=false`
   (`GatewayService.kt:103`). AUTO never chooses SOCKET; an explicit SOCKET
   choice still does. Flip that constant when GMweb deploys the endpoint.
2. **The polling floor is not paused in SOCKET mode.** The two pollers keep
   running because they are the data path; `onWake` calls the existing
   `retryNow()`. Consequently Stage 4 adds prompt wakes but does **not**
   deliver the "360 keepalives instead of 3 456 long-polls" reduction claimed in
   §0. No request-count or battery improvement is claimed. This is the main
   reason the stage is a client-only, default-off deliverable.
3. **`wakeMode` was not exposed anywhere before this stage.** Step 3 said
   "wherever it is already exposed"; it was preference-only. The picker was
   added to the existing Settings → Gateway `SectionCard` using the existing
   `FilterChip` pattern, with a note that the choice is read when the gateway
   service starts.
4. **The ACTIVITY server hint is a lease.** It is read only from the upgrade
   response (`X-Wake-Active: 1`) and expires after 10 minutes, because the
   one-bit channel carries no refresh frame and the heartbeat path cannot expose
   headers without an out-of-scope change. A long-lived web session therefore
   keeps the socket open only while another ACTIVE input (foreground, recent
   message, outbox) is also present; otherwise it falls back to polling.
5. **No `keepalivesSent` counter.** OkHttp owns the protocol ping and exposes no
   callback; the health model reports the configured interval instead
   (`pingIntervalMs=240_000`). `wakeCount`, `suppressedWakes`, `unknownFrames`
   and `reconnectCount` are real counters.
6. **`WakeHealth`/`GatewayHealthSnapshot` grew fields** beyond the Stage 2
   minimum (socket state, activity, pinning, counts). The old fields and
   defaults are unchanged, so Stage 2's tests still pass.
7. **Android 15 audit outcome is a finding, not a code change**: the existing
   policy already runs `specialUse`-only at API 35+; no `connectedDevice` or
   alarm-recovery work was needed.

### D. Constraint check (§3)

| Constraint | Upheld? | Evidence |
|---|---|---|
| ADR-003 SLO | Yes | SOCKET mode only adds `retryNow()` nudges to the existing loop; no cadence, claim or ACK change. AUTO = POLLING by default. The polling floor remains, so pickup cannot be slower than Stage 1.1. |
| Correctness triad untouched | Yes | No change to `EveSmsQueue`, the ACK ledger, `GatewayAckTracker` or idempotency keys; `git diff` touches only wake transport/wiring/health/UI. |
| No Google dependency added | Yes | Only OkHttp/OkHttp-TLS (Square, test-only TLS). No FCM/Play Services addition. |
| No content on wake channel | Yes | The only frame field read is `t` (`SocketWakeSource.kt:346`); no client payload is sent; `WakeFrameParsingTest` sends recipients/bodies/commands and observes only a wake or an ignored/counted frame. |
| No invented measurements | Yes | Counts are derived from test/run output; no battery percentages, no drain claims, and §C.2 explicitly withdraws the plan's request-count claim. |
| Room schema unchanged | Yes | `app/schemas/com.autonomousone.messages.data.MessagesDatabase/15.json` exists; no `@Entity`/DAO/schema file in the diff. |
| Recipients tokenised in logs | Yes | New logs contain only class names, state names and counts; test frames use fake numbers and are never logged. |
| No source-only claim about binary contents | Yes | The OkHttp version claim comes from `:app:dependencies`, and the release APK was built with R8. |

### E. Verbatim command output

Command: `./gradlew testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin lintDebug`
(Windows wrapper `gradlew.bat`; redirect to a file, tail reproduced verbatim).

```text
> Task :app:compileDebugUnitTestKotlin UP-TO-DATE
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:lintAnalyzeDebugAndroidTest
> Task :app:lintAnalyzeDebug
> Task :app:lintAnalyzeDebugUnitTest
> Task :app:testDebugUnitTest
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug

> Task :app:lintReportDebug
Wrote HTML report to file:///C:/Users/Mahna/sms%20center/Messages/app/build/reports/lint-results-debug.html

> Task :app:lintDebug

BUILD SUCCESSFUL in 7m 42s
63 actionable tasks: 16 executed, 47 up-to-date
```

Test totals were counted from
`app/build/test-results/testDebugUnitTest/TEST-*.xml` after that run:
**2735 tests, 0 failures, 0 skipped** across 266 result classes.

### F. Residual risk / what the next agent must know

- **The server half does not exist.** Every explicit SOCKET user currently gets
  one connection attempt, a reported `TRANSPORT_FAILED` fallback to polling, and
  backoff. That is the designed behaviour, not a failure of the client.
- **The battery premise of Stage 4 is not delivered.** Polling still runs while
  the socket is open. Do not claim the 360-vs-3 456 request reduction until the
  pollers can be safely paused and measured.
- **Android 15:** the gateway is `specialUse`-only at API 35+; adding `dataSync`
  back at that level re-enables the 6 h/24 h cap and would kill the socket daily.
- **Pinning:** an invalid pin fails closed as `NOT_CONFIGURED`; a mismatch is
  `MISMATCH` + `TRANSPORT_FAILED`. Empty pins mean unpinned and the card says
  so. The UI stores pins but the service reads them only on the next start.
- **Mode changes apply on the next gateway service start** (the picker says so).
  Toggling the gateway off/on is the expected way to apply one.
- **OkHttp 4.12.0 is pinned.** If a future Coil upgrade needs a newer 4.x, this
  pin will hold it back deliberately; revisit then.
- **Do not reuse the legacy `okhttp 2.5.0` line.** The vendored MMS AAR needs it
  live (STAGE-3 §0). The new dependency is the `com.squareup.okhttp3` coordinate.
- **Stage 5 remains BLOCKED** by ADR-003 and was not touched.
- **At handoff, PR #15's CI checks were still pending.** The §4 gate ran locally
  and passed; the branch is pushed, but no squash merge was performed while the
  checks were pending.
