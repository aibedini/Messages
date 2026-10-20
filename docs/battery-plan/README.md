# Battery reduction programme — index and shared contract

**Target:** cut idle battery and thermal load of the gateway stack **without**
weakening the availability contract in
[ADR-003](../adr/ADR-003-android-availability-doze-slo.md).

**Reported symptom (device owner, Nothing Phone):** battery drains fast and the
phone runs hot while the gateway is enabled.

**Status of this programme:** IN PROGRESS. Stages 1 and 2 are merged. Stage 4's
client is implemented on `perf/battery-stage-4` (PR #15) with the GMweb endpoint
still pending (AUTO therefore still resolves to POLLING). Stage 3 is CLOSED with
no code change. Stage 5 remains BLOCKED. **No on-device measurement has been
taken.**

---

## 0. How the diagnosis was produced (read this first)

Everything below was derived by **reading source**, on commit reachable from
`main` at the time of writing. **No device run, no `batterystats` capture, no
profiler trace was performed.**

Consequences, and they are binding:

- Every cost figure in these plans is an **arithmetic consequence of a constant
  in the code** (e.g. "a 2000 ms cadence is 43 200 wake-ups/day"), never a
  measured drain.
- No plan may claim a battery improvement **percentage**. Only request counts,
  wake-up counts and held-lock duration may be stated, because those are
  derivable from code.
- The ordering of stages by impact is a **hypothesis**. `MANUAL-STEPS.md` §3
  defines the measurement that confirms or refutes it. Running that measurement
  before Stage 1 is strongly recommended so the programme has a real baseline.
- A claim about what is **in the APK** — what code runs, what classes
  exist, what a dependency is used for — requires inspecting the
  binary (`app/libs/*.aar`, `./gradlew dependencies`, the built APK).
  Greps over `app/src` answer a different question. Stage 3's premise
  was refuted this way after the plan asserted the opposite; see
  STAGE-3-okhttp.md §0.

This mirrors the house rule already set in
[`docs/performance-contract.md`](../performance-contract.md): no invented
numbers, ever.

---

## 1. What is actually wrong

The **upstream** direction is already correct and must not be touched:

```kotlin
// EventUploader.kt:80 — event-driven, zero polling
private val outboxObserver = object : InvalidationTracker.Observer("gateway_event_outbox") {
    override fun onInvalidated(tables: Set<String>) { wake.trySend(Unit) }
}
```

SMS arrives → `GatewayWakeOnSms` starts the service on `SMS_DELIVER` → the row
and its outbox event land in one Room transaction → the observer wakes the
uploader. Nothing polls. The *"chats over the web"* product requirement is
served by this path plus a web client that reads GMweb; **the phone is never
polled by the web client, so the web feature adds no battery cost and appears
in no stage of this programme.**

The cost is entirely in the **downstream** direction (GMweb → phone), where
seven independent loops run concurrently once `ConnectionSupervisor` reaches
CONNECTED:

| # | Source | Cadence when idle | Derived cost / 24 h | Stage |
|---|---|---|---|---|
| 1 | `SecureCommandPoller.kt:70` `QUIET_MS = 2_000L` | 2 s | **43 200 authenticated HTTPS claims** | 1.1 |
| 2 | `OutboxPoller` cycle + `PARTIAL_WAKE_LOCK` | continuous, no idle gap | wake lock held ≈ 100 % of wall-clock | 1.2 |
| 3 | `MainThreadStallWatchdog` `POLL_INTERVAL_MS = 250L` | 250 ms | **345 600 main-looper probes** | 1.3 |
| 4 | `EveSmsQueue.kt:451` `Thread.sleep(400)` | 400 ms | **216 000 thread wake-ups** | 1.4 |
| 5 | `HeartbeatManager` 60 s | 60 s | 1 440 requests | 1.5 |
| 6 | `DeviceTelemetry` 60 s | 60 s | 1 440 requests | 1.5 |
| 7 | `EventUploader` lease sweep 60 s · `ConnectionSupervisor` reconcile 60 s · `TrustStatementPublisher` position refresh 60 s | 60 s each | 4 320 wake-ups, unbatched | 1.5 |

Rows 5–7 are each defensible in isolation; the defect is that **five separate
timers fire every minute and none of them share a wake-up**, so the radio is
pulled out of idle up to five times a minute for work that could travel
together.

### 1.1 The correction that matters most

`QUIET_MS = 2_000L` is **not carelessness**. ADR-003 sets
`command pickup P95 < 3s` for `AGENT_AVAILABLE`, and a 2-second claim cadence is
a literal, if expensive, way to meet it.

**Therefore: no stage in this programme may simply lengthen that interval.**
Doing so trades a documented, accepted SLO for battery without saying so. The
correct fix (Stage 1.1) converts the command channel to the **long-poll** shape
the outbox channel already uses, which *improves* pickup latency while cutting
requests by about 92 %.

ADR-003 explicitly permits this:

> Transport may later be optimized (long-poll → other transports) without
> changing command semantics — the SLO boundary is measured at the durable
> queue, not the wire.

That sentence is the licence for Stages 2 and 4 as well.

---

## 2. Stage order and dependencies

```
STAGE-1  quick wins, no architecture change ........... START HERE
   │        (1.1 has a GMweb server prerequisite — see MANUAL-STEPS §2)
   ▼
STAGE-2  WakeSource abstraction (pure refactor, no behaviour change)
   │
   ├──► STAGE-3  OkHttp cleanup  (independent; required only by Stage 4)
   │        │
   │        ▼
   └──► STAGE-4  SocketWakeSource (client implemented; GMweb endpoint pending)
            │
            ▼
        STAGE-5  FcmWakeSource — BLOCKED by ADR-003, see that file
```

| Stage | File | Risk | Needs server work | Needs user action |
|---|---|---|---|---|
| 1 | [`STAGE-1-quick-wins.md`](STAGE-1-quick-wins.md) | MERGED (#12) | yes, for 1.1 only | decision in §1.1 |
| 2 | [`STAGE-2-wake-source.md`](STAGE-2-wake-source.md) | MERGED (#14) | no | no |
| 3 | [`STAGE-3-okhttp.md`](STAGE-3-okhttp.md) | CLOSED — no action (§0) | no | no |
| 4 | [`STAGE-4-socket-wake-source.md`](STAGE-4-socket-wake-source.md) | MERGED (#15) — client only, server pending | yes — not deployed yet | no |
| 5 | [`STAGE-5-fcm-optional.md`](STAGE-5-fcm-optional.md) | medium | yes | yes — Firebase console |

**Stage 4 status: client implemented (PR #15), server pending.**
`SocketWakeSource` ships on `perf/battery-stage-4`; GMweb does not implement
`/gateway/wake` yet, so
`SERVER_WAKE_DEPLOYED` is `false` and `wakeMode = AUTO` still resolves to
`POLLING` — default behaviour is unchanged. An explicit `SOCKET` choice tries
the socket, falls back to polling and reports the reason on the diagnostic
card. No request-count or battery reduction is claimed for Stage 4: the
polling floor still runs while the socket is open (see the stage report §C).
Stage 5 remains **BLOCKED** and was not touched.

Stage 3 is closed with no code change. Its finding — that the vendored
MMS AAR needs OkHttp 2.5.0 — is recorded in STAGE-3-okhttp.md §0 and
must not be re-litigated from greps.

Everything a human must do, in Persian, is in
[`MANUAL-STEPS.md`](MANUAL-STEPS.md).

**Stage 1 is expected to recover most of the regression on its own.** Stage 4's
socket was expected to buy a further reduction, but its client currently runs
alongside the polling floor and the GMweb endpoint is pending, so no such
reduction is claimed. Stage 5 remains BLOCKED.

---

## 3. Hard constraints — a stage that breaks one of these has failed

1. **ADR-003 SLO.** `command pickup P95 < 3s` while `AGENT_AVAILABLE`. A change
   that lengthens idle pickup latency must either preserve this or be recorded
   as an explicit, named SLO deviation in the stage report.
2. **Correctness triad, from ADR-003.** `durable command + eventual pickup + no
   duplicate execution`. Battery work touches **wake-up timing only**. Never
   `EveSmsQueue` at-most-once semantics, never the ACK ledger, never
   `GatewayAckTracker`, never idempotency keys.
3. **Zero Google infrastructure dependency** (ADR-003, stated as a hard project
   rule). Stage 5 therefore cannot ship without formally superseding that ADR.
   See Stage 5 §0.
4. **No content on a wake channel.** Every transport in Stages 2/4/5 carries
   the single bit *"there is work"*. Message bodies and recipient numbers travel
   only over the existing authenticated pull. This keeps the security model
   unchanged no matter which transport is active.
5. **No invented measurements.** See §0.
6. **One stage per branch and PR.** Do not combine stages. A stage that is
   partially done is reported as partially done, not merged into the next.
7. **Recipient privacy in logs.** The codebase logs recipients as
   `PhoneToken.of(...)`, never dialable numbers. Any new log line in any stage
   follows that rule.
8. **No source-only claim about binary contents.** See §0. A stage that
   needs to know what a dependency is used for inspects the artifact,
   and a plan that states such a conclusion without binary evidence is
   to be treated as an open question, not a finding — whatever its
   wording.

---

## 4. Validation command — the project gate

Every stage is validated with the repository's own gate, taken from
`.github/workflows/final-realtime-gate.yml`:

```bash
chmod +x gradlew
./gradlew testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin lintDebug
```

Java 21 (temurin) is what CI uses. There are currently **242 test source files**
under `app/src/*test*`; a stage must not reduce that count, and each stage
specifies the tests it adds.

Room schema guard, also from the gate:

```bash
test -f app/schemas/com.autonomousone.messages.data.MessagesDatabase/15.json
git diff --check
```

No stage in this programme changes the Room schema. If a stage finds itself
editing an `@Entity`, it has left its scope — stop and report.

---

## 5. MANDATORY report format

> **To the implementing agent (DeepSeek or any other):**
> Appending this block, filled in honestly, is **part of the stage**. A stage
> whose code is written but whose report is missing or blank is **not complete**
> and must be reported as incomplete.

Append it to the bottom of the stage file you worked on, under
`## Implementation report`. Reproduce the headers exactly.

```markdown
## Implementation report

Agent: <model/name>            Date: <YYYY-MM-DD>
Branch: <branch>               Base commit: <sha>

GATE-VERIFIED: NO            # YES only if §4 ran to completion on your machine
TESTS EXECUTED: 0            # the number testDebugUnitTest actually reported
COMPILE STATUS: UNVERIFIED   # PASS | FAIL | UNVERIFIED

### A. Completed
| Item | What was changed (file:line) | Commit | Test proving it |
|---|---|---|---|

### B. Not completed
| Item | Why not | Blocker type | What would unblock it |
|---|---|---|---|
<!-- Blocker type ∈ { server-dependency, user-decision, missing-credential,
     test-failure, ADR-conflict, out-of-scope, ran-out-of-context,
     could-not-reproduce, other } -->

### C. Deviations from the plan
<!-- Anything built differently from what this document specifies, and why the
     plan was wrong or incomplete. A deviation is not a failure; a silent
     deviation is. -->

### D. Constraint check (§3)
| Constraint | Upheld? | Evidence |
|---|---|---|
| ADR-003 SLO | | |
| Correctness triad untouched | | |
| No Google dependency added | | |
| No content on wake channel | | |
| No invented measurements | | |
| Room schema unchanged | | |
| Recipients tokenised in logs | | |

### E. Verbatim command output
<!-- Paste the full tail of the §4 gate run, failures included and unedited.
     If it was not run, write exactly: NOT RUN — <reason>. -->

### F. Residual risk / what the next agent must know
```

### Reporting rules

1. **`GATE-VERIFIED: YES` requires section E to contain real output.** No
   output, no YES.
2. **Never guess a test count.** If the gate did not run, `TESTS EXECUTED: 0`
   and `COMPILE STATUS: UNVERIFIED`.
3. **Partial work is reported, not hidden.** Section B exists for exactly this.
   Four of five items done and honestly reported is a good outcome; five
   claimed and three real is a bad one.
4. **No battery percentages.** Report what changed in counts and held time.
5. **Do not edit the plan body to match what you built.** Record the difference
   in section C and leave the original specification intact.
6. **`openwiki/` is generated.** Per [`AGENTS.md`](../../AGENTS.md), do not
   hand-edit generated OpenWiki pages; change source and docs and let the
   workflow regenerate.
