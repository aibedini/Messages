# STAGE 2 — `WakeSource` abstraction (pure refactor, zero behaviour change)

**Read [`README.md`](README.md) first.**

**Branch:** `perf/battery-stage-2`
**Depends on:** Stage 1 merged (not strictly required, but Stage 1 edits the
same two pollers and doing this first guarantees conflicts).
**Behaviour change:** **none.** This stage must be a no-op on the wire. If a
packet capture before and after differs, the stage is wrong.

---

## 0. Why this stage exists

The project owner wants three selectable wake mechanisms (polling, self-hosted
socket, FCM) with the user able to choose. Building that by adding `if (mode ==
…)` branches inside `OutboxPoller` and `SecureCommandPoller` would spread
transport logic through two classes that already own hard correctness rules
(at-most-once delivery, the ACK ledger, the zombie-loop guard in `PollLoop`).

ADR-003 already anticipated and permitted this:

> Transport may later be optimized (long-poll → other transports) without
> changing command semantics — **the SLO boundary is measured at the durable
> queue, not the wire.**

That sentence is the architectural licence for this stage and for Stage 4.

This stage therefore delivers **only the seam**, with polling as its single
implementation. Stage 4 adds the second implementation; Stage 5 would add a
third. Nothing user-visible changes here, which is exactly what makes it safe
to merge.

---

## 1. The central design rule

> **A `WakeSource` delivers one bit of information: "there may be work".
> It never delivers the work.**

Everything that fetches, validates, sends, acks or persists stays exactly where
it is today: `OutboxPoller.cycle()` remains the one and only data path, reached
through the existing authenticated `GET /gateway/pull` + `POST /gateway/ack`.

Three consequences, all of them load-bearing:

1. **Security does not change when the transport changes.** A socket or an FCM
   ping that carries no content cannot leak content. Stage 4's threat model is
   then "can an attacker make the phone call its own server unnecessarily",
   which is a denial-of-service nuisance, not a disclosure.
2. **ADR-006 (sensitive-message local-only firewall) is unaffected**, because no
   new path carries message data.
3. **Each new transport is small.** `SocketWakeSource` is ~150 lines because it
   only has to decide *when* to say "wake".

---

## 2. The interface

New package: `app/src/main/java/com/autonomousone/messages/gateway/wake/`

> **Naming note.** Do not call this `Transport`. Two `Transport` types already
> exist (`GmwebTaskValidator.Transport:44`,
> `TelemetryReporter.TelemetryTransport:101`) and a third would be read as
> related to them. `WakeSource` is deliberately distinct.

### `WakeSource.kt`

```kotlin
package com.autonomousone.messages.gateway.wake

/** Which mechanism tells this device that downstream work is waiting. */
enum class WakeSourceId { POLLING, SOCKET, FCM }

/** Why a WakeSource is not usable right now. Reported, never inferred. */
enum class WakeUnavailable {
    NOT_CONFIGURED,     // no server origin, no token
    NO_PLATFORM_SUPPORT,// e.g. FCM selected on a device without Play Services
    AUTH_REJECTED,      // server refused our credentials
    TRANSPORT_FAILED,   // connect/upgrade failed
    DISABLED_BY_USER
}

data class WakeHealth(
    val id: WakeSourceId,
    val active: Boolean,
    val unavailable: WakeUnavailable? = null,
    val lastWakeAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val consecutiveFailures: Int = 0,
    /** Wake-ups delivered since start. Counts only — no ids, no recipients. */
    val wakeCount: Long = 0L
)

/**
 * A mechanism that says "there may be downstream work", nothing more.
 *
 * Implementations MUST NOT carry task payloads, recipients or message bodies.
 * See STAGE-2 §1.
 */
interface WakeSource {
    val id: WakeSourceId

    /** True when this source can run on this device, with this configuration, now. */
    fun isAvailable(): Boolean

    /**
     * Begin delivering wake-ups. Idempotent against an already-started source.
     * [onWake] may be called from any thread and must be cheap: it is expected
     * to poke a conflated channel, not to do work.
     */
    fun start(onWake: () -> Unit)

    fun stop()

    val health: StateFlow<WakeHealth>
}
```

### `PollingWakeSource.kt`

Wraps today's behaviour and **owns no new logic**. It delegates to the existing
`OutboxPoller`, whose `PollLoop`-based lifecycle already answers "is it running"
honestly:

```kotlin
internal class PollingWakeSource(
    private val poller: OutboxPoller,
    private val commandPoller: SecureCommandPoller
) : WakeSource {
    override val id = WakeSourceId.POLLING
    override fun isAvailable() = true          // always the floor
    override fun start(onWake: () -> Unit) { poller.start(); commandPoller.start() }
    override fun stop() { poller.stop(); commandPoller.stop() }
    // health derives from poller.isRunning / pollLoopGeneration / pollLoopStartedAt
}
```

Note the asymmetry and keep it: in POLLING mode the pollers *are* both the wake
mechanism and the fetch mechanism, so `onWake` is unused. In SOCKET or FCM mode
the wake source fires `onWake`, which calls `OutboxPoller.retryNow()` — the
method that already exists at `OutboxPoller.kt:362` and already interrupts the
backoff via the conflated `wake` channel. **No new plumbing is needed on the
fetch side.** This is the single most important thing to get right in this
stage.

### `WakeSourceSelector.kt` — pure, testable, Android-free

```kotlin
/**
 * Resolves the configured mode to the source that will actually run.
 *
 * Pure function so the ladder is pinned by a test instead of being spread
 * across runtime branches. Mirrors the style of PollStatePolicy.
 */
object WakeSourceSelector {
    data class Inputs(
        val configured: WakeMode,            // AUTO | POLLING | SOCKET | FCM
        val fcmAvailable: Boolean,
        val socketAvailable: Boolean
    )

    /** AUTO ladder: FCM → SOCKET → POLLING. Explicit modes fall back to POLLING
     *  when unavailable, and the fallback is REPORTED, never silent. */
    fun resolve(inputs: Inputs): Resolution
    data class Resolution(val chosen: WakeSourceId, val fellBackFrom: WakeSourceId?)
}
```

**In this stage `fcmAvailable` and `socketAvailable` are hard-wired `false`**, so
`resolve` always returns POLLING. The ladder is still written and still tested,
so Stages 4 and 5 only flip a capability flag.

### 2.5 `GatewayTicker.requestEarlierTick` — carried over from Stage 1.5

Stage 1.5 built `GatewayTicker` and deliberately left it **unwired**. That call
was right and the Stage 1 plan was wrong. Stage 1.5 rule 3 said "a subscriber in
backoff returns immediately from `onTick`" — which is not sufficient.
`HeartbeatManager` runs a 1 s → 5 min ladder (`INITIAL_BACKOFF_MS = 1_000L`); a
subscriber that is only ever called on a fixed 60 s tick can never retry at 1 s.
Wiring it blindly would move error recovery from seconds to minutes, which is a
reliability regression dressed as a battery fix.

The ticker therefore needs one primitive before any laddered subscriber can use
it:

```kotlin
/**
 * Ask for an out-of-band tick for ONE subscriber, earlier than the shared
 * 60 s cadence. This is what lets a subscriber keep its own fast backoff
 * ladder while still sharing the common wake-up for its steady-state work.
 */
fun requestEarlierTick(subscriber: String, delayMs: Long)
```

Rules:

1. **Per subscriber, not global.** An earlier tick requested by the heartbeat
   must not drag telemetry and the trust publisher along with it — that would
   re-create the burst of five simultaneous requests this stage exists to remove.
2. **Coalescing.** The earliest outstanding request wins; repeated requests do
   not stack, and one subscriber cannot queue a backlog of ticks.
3. **Floor.** Clamp `delayMs` to at least 1 s, so a subscriber whose ladder is
   buggy cannot turn the shared ticker into a hot loop.
4. **Additive.** The shared 60 s tick keeps running unchanged; an earlier tick
   is extra, never a replacement, and never resets the shared cadence.
5. **The ladder stays in the subscriber.** A subscriber in backoff returns
   immediately from `onTick` *and* calls `requestEarlierTick` with its own next
   delay. The ticker owns scheduling; it never owns a retry policy.

Then wire the subscribers, **one commit each**, in this order:

| Order | Subscriber | Note |
|---|---|---|
| 1 | `EventUploader` lease sweep | no ladder — safest first, proves the fan-out |
| 2 | `DeviceTelemetry` | keep its existing trigger path working |
| 3 | `HeartbeatManager` | only after `requestEarlierTick` is implemented and tested |
| 4 | `ConnectionSupervisor` reconcile | must stay **last** in tick order: it can start and stop the others, so it must observe their post-tick state |
| — | `TrustStatementPublisher` | **do not put on the ticker.** Its `QUIET_MS = 5_000L` loop is a 5-second publish cadence, not a 60-second maintenance tick; it belongs to the `WakeSource` work, not here. Record the decision rather than forcing it onto a 60 s tick. |

Acceptance for 2.5: the five independent 60 s timers listed in Stage 1.5 §A
become one shared tick, `GatewayTicker` is no longer dead code in the tree, and
`HeartbeatManager`'s retry ladder still begins at ~1 s rather than ~60 s.

Tests for 2.5:

| Test | Asserts |
|---|---|
| `GatewayTickerEarlierTickTest` | per-subscriber isolation; coalescing (earliest wins, no stacking); the 1 s floor is enforced |
| `GatewayTickerEarlierTickAdditiveTest` | an earlier tick does not reset or skip the shared 60 s tick |
| `HeartbeatLadderPreservedTest` | with `HeartbeatManager` on the ticker, its first retry is still ~1 s, not ~60 s |

---

## 3. Preference

Add to `GatewayPreferences.kt`, following the existing `KEY_…` + `var` style
(see `KEY_ENABLED`, `KEY_PORT` at lines 23–24):

```kotlin
private const val KEY_WAKE_MODE = "gateway_wake_mode"   // AUTO|POLLING|SOCKET|FCM
```

```kotlin
var wakeMode: WakeMode   // default WakeMode.AUTO
```

With only POLLING implemented, `AUTO` resolves to POLLING, so **the default
behaviour is byte-identical to today**. That is the acceptance criterion.

No UI in this stage. The settings control arrives in Stage 4, when there is
something to choose between. Writing the UI now would ship a picker with one
option.

---

## 4. Wiring

`ConnectionSupervisor.ManagedComponents` (`ConnectionSupervisor.kt:96`) already
carries `startPoller` / `stopPoller` / `isPollerRunning` / `isPollerStalled` /
`wakePoller` and `startCommandPoller` / `stopCommandPoller`. **Do not delete
those hooks in this stage** — that is a Stage 4 concern. Add alongside them:

```kotlin
val startWakeSource: () -> Unit = {},
val stopWakeSource: () -> Unit = {},
val activeWakeSource: () -> WakeSourceId = { WakeSourceId.POLLING },
```

In `GatewayService.onCreate` (around `GatewayService.kt:290`), construct the
registry, hand it the already-built `outboxPoller` and `commandPoller`, and make
`startPoller` route through it. The existing calls at
`ConnectionSupervisor.kt:443–501` (`startHeartbeat`, `startEventUploader`,
`startTrustPublisher`, `startCommandPoller`, the `PollStatePolicy.Recovery`
branches) must keep working unchanged.

**Explicitly out of scope here:** `ContactsSyncPublisher`'s `delay(5_000)` gate
loop (`ContactsSyncPublisher.kt:82`) and `TrustStatementPublisher.QUIET_MS`
(5 s). Stage 1.5 flagged them; they become `WakeSource` subscribers in Stage 4,
once there is a non-polling source to subscribe to. Note them again in your
report.

---

## 5. Health reporting

`GatewayHealthRecorder` already distinguishes "never polled" from "polling fine"
from "polling and being rejected" — `OutboxPoller.pull()` was instrumented
specifically so a 401 is not indistinguishable from a timeout
(`OutboxPoller.kt:635–680`). Extend that, do not duplicate it:

- record the active `WakeSourceId`;
- record `fellBackFrom` when the selector fell back, with the
  `WakeUnavailable` reason;
- surface both on the existing gateway diagnostic card.

A user whose chosen mode silently degraded to 2-second polling must be able to
see it. That visibility is the main defence against this programme's own
regression.

---

## 6. ADR

Add `docs/adr/ADR-008-wake-source-abstraction.md`, following the shape of the
existing ADRs (Status / Date / Scope / Context / Decision / Consequences). It
must state:

- the one-bit rule from §1 and that payloads are forbidden on wake channels;
- that the ADR-003 SLO boundary stays measured at the durable queue;
- the AUTO ladder and that fallback is always reported, never silent;
- that POLLING is the permanent floor and is never removed, because it is the
  only source that works with no platform and no server support.

---

## 7. Tests to add

| Test | Asserts |
|---|---|
| `WakeSourceSelectorLadderTest` | AUTO with nothing available → POLLING; AUTO with socket available → SOCKET; AUTO with both → FCM; explicit SOCKET unavailable → POLLING **with `fellBackFrom = SOCKET`** |
| `WakeSourceSelectorPurityTest` | `resolve` is total — every `Inputs` combination returns a resolution, none throws |
| `PollingWakeSourceLifecycleTest` | `start()` twice does not double-start; `stop()` then `start()` restarts (mirrors the `PollLoop` idempotency contract) |
| `WakeModePreferenceDefaultTest` | an unset preference reads `AUTO`, and `AUTO` resolves to POLLING with only polling implemented |
| `WakeSourceNoBehaviourChangeTest` | with `AUTO`, the sequence of start calls on `OutboxPoller`/`SecureCommandPoller` is identical to the pre-refactor sequence (use a recording fake) |

The last test **is** the acceptance criterion for this stage.

---

## 8. Risks and rollback

| Risk | Mitigation |
|---|---|
| Refactor accidentally changes poller start order and breaks the zombie-loop repair path | `WakeSourceNoBehaviourChangeTest`; leave `isPollerRunning`/`isPollerStalled`/`wakePoller` untouched |
| Abstraction grows a payload field in review "because it is convenient" | §1 rule written into ADR-008; reviewer rejects any payload on a wake channel |
| Scope creep into Stage 4 | capability flags hard-wired `false`; no UI |

Rollback: this stage adds a seam and changes no behaviour, so reverting is a
plain `git revert` with no data or preference migration.

---

## 9. Acceptance checklist

- [x] `WakeSource`, `WakeSourceId`, `WakeHealth`, `WakeUnavailable` exist in
      `gateway/wake/`
- [x] `PollingWakeSource` delegates to the existing pollers and adds no logic
- [x] `WakeSourceSelector` is pure, Android-free, and fully tested
- [x] `wakeMode` preference exists, defaults to `AUTO`, resolves to POLLING
- [x] `ManagedComponents` extended without removing existing hooks
- [x] Active source and any fallback are visible on the diagnostic card
- [x] ADR-008 written
- [x] `WakeSourceNoBehaviourChangeTest` passes
- [x] `GatewayTicker.requestEarlierTick` implemented per §2.5, with per-subscriber
      coalescing and a 1 s floor
- [ ] Stage 1.5's `GatewayTicker` subscribers wired in the §2.5 order; the trust
      publisher deliberately left off, with the reason recorded
- [x] Gate passes; test-file count ≥ 242 + new tests
- [x] Report appended per [`README.md`](README.md) §5

---

<!-- The implementing agent appends the report from README.md §5 below. -->

## Implementation report

Agent: deepseek-flash (DeepSeek Harness)   Date: 2026-10-19
Branch: perf/battery-stage-2               Base commit: 995dafd

GATE-VERIFIED: NO
TESTS EXECUTED: 2693
COMPILE STATUS: PASS

`GATE-VERIFIED: NO` is the same pre-existing red test Stage 1 reported: `SpamRepositoryTest`
(`SpamRepositoryTest.kt:256`) fails on this base, touches no file in this branch, and stops the §4
command at the test task. The stage's own 20 tests all pass, and `assembleDebug`,
`compileDebugAndroidTestKotlin` and `lintDebug` were run separately and succeeded.

### A. Completed

| Item | What was changed (file:line) | Commit | Test proving it |
|---|---|---|---|
| `WakeSource` / `WakeSourceId` / `WakeMode` / `WakeUnavailable` / `WakeHealth` | `gateway/wake/WakeSource.kt:78` (interface), enums and health model above it; the one-bit rule is stated in the type's KDoc | 334183b | used by all three wake test classes |
| `PollingWakeSource` | `gateway/wake/PollingWakeSource.kt:23` — delegates to the two existing pollers, no transport logic; start guard is `started && pollerRunning()` so a dead loop stays replaceable | 334183b | `PollingWakeSourceTest` (6) — double start, stop/start, dead-poller restart, idempotent stop, health, unused `onWake` |
| `WakeSourceSelector` | `gateway/wake/WakeSourceSelector.kt:13` — pure, total AUTO ladder; explicit unavailable modes fall back with `fellBackFrom` + `unavailable` | 334183b | `WakeSourceSelectorTest` (11) — ladder, both explicit fallbacks, totality over 16 combinations, preference parsing |
| `wakeMode` preference | `gateway/GatewayPreferences.kt:111` (`KEY_WAKE_MODE`, default AUTO via `WakeMode.parse`) | 15ef74a | `WakeMode.parse(null/""/"CARRIER_PIGEON") == AUTO` |
| `ManagedComponents` seam | `gateway/ConnectionSupervisor.kt:170` (three hooks, defaults kept), `:491` (`startWakeSource` in reconcile), `:331,:404` (`stopWakeSource` on shutdown/disable); existing hooks untouched | fce8bf7 | `WakeSourceNoBehaviourChangeTest` (3) |
| Health + card | `gateway/health/GatewayHealthRecorder.kt:143,151` (`setWakeSourceActive`, `setWakeFallback`); `GatewayHealth.kt` snapshot fields; `ui/gateway/GatewayHealthCard.kt` "Wake source" row | fce8bf7 | covered by `GatewayHealthCard` snapshot defaults; behaviour exercised by the recorder setters |
| ADR-008 | `docs/adr/ADR-008-wake-source-abstraction.md` — one-bit rule, permanent POLLING floor, AUTO ladder + reported fallback, SLO boundary unchanged | 599b21b | — |

Test-file count: 248 → **251** (three new wake test files), never below 242.

### B. Not completed

| Item | Why not | Blocker type | What would unblock it |
|---|---|---|---|
| `WakeModePreferenceDefaultTest` as written (assert on an unset `SharedPreferences` value) | The JVM test source set has no Robolectric and no `Context`, so `GatewayPreferences` cannot be constructed. The rule it protects — unset ⇒ AUTO ⇒ POLLING — is covered by `WakeMode.parse(null)` plus the selector test; the preference getter is a two-line delegation to those. | other | Robolectric in `testImplementation`, or an instrumentation test |
| A second wake source to exercise the seam in production | Out of scope by design: `fcmAvailable`/`socketAvailable` are hard-wired `false`, so `AUTO` and every fallback resolve to POLLING. Stage 4 adds `SocketWakeSource`. | out-of-scope | Stage 4 |
| Gate green | The pre-existing `SpamRepositoryTest` failure (see above). | test-failure | A separate fix deciding whether the test or `BlocklistRepository.normalize` is wrong |

### C. Deviations from the plan

1. **`startPoller` was NOT routed through the wake source.** §4 says to "make `startPoller` route
   through it". That would be a behaviour change: `ConnectionSupervisor`'s stall-recovery branch
   calls `stopPoller()`/`startPoller()` to replace a dead PULL loop, and a source that starts both
   pollers would restart the command channel as a side effect. Instead a new `startWakeSource` hook
   runs before the recovery decision; `startPoller` stays pull-only, so `RESTART` still touches only
   the pull loop. `WakeSourceNoBehaviourChangeTest` pins exactly this.
2. **`Resolution` carries `unavailable`.** §2 defines `Resolution(chosen, fellBackFrom)` only, but
   §5 asks for the `WakeUnavailable` reason. The third field is optional and defaults to null.
3. **`WakeMode` lives in `WakeSource.kt`.** §2 references it without naming a home; keeping it beside
   `WakeSourceId` avoids a second file whose only content is four enum values.
4. **Health carries plain strings, not the wake enums.** `GatewayHealthSnapshot` is a UI/report model;
   importing `gateway.wake` into it would invert the dependency for four characters. The recorder is
   fed `.name` values by the service.
5. **`PollingWakeSource` takes function seams, with a production constructor.** The plan shows a
   constructor taking the pollers; the tests need to run without `Context`/`Room`, so the primary
   constructor takes `startPollers`/`stopPollers`/`pollerRunning`/`clock` and a secondary constructor
   binds the real pollers. Production wiring is unchanged (`GatewayService.kt:268`).
6. **Base is the Stage 1 tip, not merged `master`.** Stage 1 has not been merged; branching there was
   the only way to avoid the conflicts §"Depends on" warns about. `GATE-VERIFIED` therefore runs on
   995dafd, not on master.

### D. Constraint check (§3)

| Constraint | Upheld? | Evidence |
|---|---|---|
| ADR-003 SLO | Yes | No cadence changed. POLLING still runs the Stage 1 timings (2 s fallback / 25 s hold); the seam only chooses which mechanism wakes the same fetch path. |
| Correctness triad untouched | Yes | No change to `OutboxPoller.cycle()` data path, `EveSmsQueue` semantics, the ACK ledger or idempotency. The diff touches start/stop wiring, a preference, health and UI only. |
| No Google dependency added | Yes | No dependency change; FCM remains a future source behind a hard-wired `false` flag. |
| No content on wake channel | Yes | `WakeSource` has no payload parameter at all; `onWake` is `() -> Unit`. Polling carries no wake channel by definition. |
| No invented measurements | Yes | No new numbers are claimed. This stage removes/relocates no work by design. |
| Room schema unchanged | Yes | No entity or schema edit. |
| Recipients tokenised in logs | Yes | No new logging of addresses; the new health fields are enum/mechanism names. |

### E. Verbatim command output

§4 gate, run as `.\gradlew.bat --offline testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin lintDebug`:

```text
> Task :app:testDebugUnitTest

SpamRepositoryTest > normalize is exact enough for provenance and still folds the country code FAILED
    org.junit.ComparisonFailure at SpamRepositoryTest.kt:256

2693 tests completed, 1 failed

> Task :app:testDebugUnitTest FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at:
file:///C:/Users/Mahna/sms%20center/Messages/app/build/reports/tests/testDebugUnitTest/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 6m 51s
```

The remaining three tasks, run separately:

```text
> Task :app:assembleDebug
> Task :app:compileDebugAndroidTestKotlin
> Task :app:lintDebug

BUILD SUCCESSFUL in 3m 5s
57 actionable tasks: 14 executed, 43 up-to-date
```

### F. Residual risk / what the next agent must know

1. **The seam is proven by tests, not by a second transport.** With both capability flags `false`,
   production resolves to POLLING unconditionally; the first real exercise of `onWake` is Stage 4.
   The wiring that matters — `onWake` → `OutboxPoller.retryNow()` — is deliberately the existing
   method, so Stage 4 adds no fetch-side plumbing.
2. **The reconcile path now starts the pull loop via `startWakeSource` BEFORE the recovery decision.**
   Net effect is identical (the same poller starts, `recovery()` then sees it active), but the
   supervisor's internal branch label changes from `START` to `NONE` on first connect. If a future
   change makes `startWakeSource` do more than start the pollers, this ordering must be revisited.
3. **Fallback health is written once, at service creation.** A runtime mechanism change (the Stage 4
   picker) must call `setWakeFallback` again, or the card will show the startup resolution for ever.
4. **`ContactsSyncPublisher`'s 5 s gate loop and `TrustStatementPublisher.QUIET_MS` are still
   unbatched**, as §4 requires. They become wake-source subscribers in Stage 4, when there is a
   non-polling source to subscribe to.
5. **Stage 1's pre-existing red test still blocks `GATE-VERIFIED`.** Fixing `SpamRepositoryTest` (or
   `BlocklistRepository.normalize`) is a prerequisite for a green CI on any branch cut from this
   base, and it is unrelated to the battery programme.
6. **ADR-008 is accepted; ADR-003 still forbids Google infrastructure.** A future FCM source must
   supersede ADR-003 explicitly, not merely implement `WakeSource`.


---

## Addendum � �2.5 implementation report

Agent: deepseek-flash (DeepSeek Harness)   Date: 2026-10-19
Branch: perf/battery-stage-2               Base commit: 7f563d2 (rebased)

GATE-VERIFIED: YES
TESTS EXECUTED: 2702
COMPILE STATUS: PASS

### A. Completed

| Item | What changed | Commit | Test |
|---|---|---|---|
| `requestEarlierTick` | per-subscriber deadlines, earliest-wins coalescing, 1 s floor, additive to the shared tick | 52e80d3 | `GatewayTickerEarlierTickTest`, `GatewayTickerEarlierTickAdditiveTest`, `GatewayTickerOrderTest`, `GatewayTickerBackoffLadderTest` |
| reconcile on the ticker | 60 s loop removed; registered LAST | 5137100 | `GatewayTickerOrderTest` |
| tick lifetime (B0) | started in `ensureLoop`, stopped with `loopJob`; no longer tied to the wake source | 92a8c20 | none � `ConnectionSupervisor` needs Room/Context; evidence: `ConnectionSupervisor.kt:341` (`startMaintenanceTick` in `ensureLoop`), `:322` (stop in `shutdown`), `GatewayService` no longer starts/stops the ticker |
| B1 / B2 | test renamed to what it pins; ADR-008 �2.5 status | c8c49f5, 0f4065e | � |

### B. Not completed

| Item | Why not | Blocker type | What would unblock it |
|---|---|---|---|
| `EventUploader` lease sweep | deferred to "Stage 2b" by decision | user-decision | on-device measurement showing the loop matters |
| `DeviceTelemetry` periodic report | same | user-decision | same |
| `HeartbeatManager` | same; a 60 s tick under its 1 s?5 min ladder is a reliability risk without measurement | user-decision | same |

### C. Deviations

1. �2.5's acceptance text says five timers become one tick, but its own table excludes
   `TrustStatementPublisher` (5 s publish cadence) � so four would become subscribers, and only
   `ConnectionSupervisor.reconcile` is wired this round.
2. No JVM test covers B0's lifetime; the evidence is the file:line trace above.

### D. Constraint check (�3)

ADR-003 SLO unchanged; correctness triad untouched; no Google dependency; no content on the wake
channel; no invented measurements; Room schema unchanged; recipients stay tokenised.

### E. Verbatim gate output

```text
> Task :app:lintDebug

BUILD SUCCESSFUL in 8m 39s
63 actionable tasks: 21 executed, 42 up-to-date
```

JUnit XML: **2 702 tests, 0 failures** (`SpamRepositoryTest` passes now that master carries #11).

### F. Residual risk

- The tick is started by `ensureLoop`; no test enforces that call, so a refactor there could silently
  drop the self-healing tick.
- `requestEarlierTick` has no production caller by design until Stage 2b.
