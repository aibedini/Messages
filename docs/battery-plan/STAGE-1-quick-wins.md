# STAGE 1 — Quick wins: no architecture change

**Read [`README.md`](README.md) first.** Its §3 constraints and §5 report format
are part of this stage.

**Branch:** `perf/battery-stage-1`
**Prerequisite:** none for 1.2–1.5. Item **1.1 has a GMweb server prerequisite**
and a decision that belongs to the project owner — see 1.1 §D.
**Expected outcome:** the largest share of the reported drain, removed without
touching the gateway's architecture, its command semantics or the Room schema.

## Items in this stage

| # | Item | File | Risk |
|---|---|---|---|
| 1.1 | Command channel: 2 s polling → long-poll | `gateway/SecureCommandPoller.kt` | medium |
| 1.2 | Wake lock held across the idle long-poll | `gateway/OutboxPoller.kt` | medium |
| 1.3 | Stall watchdog running in release builds | `diagnostics/MainThreadStallWatchdog.kt`, `MessagesApp.kt` | low |
| 1.4 | `EveSmsQueue` 400 ms busy loop | `eve/EveSmsQueue.kt` | low |
| 1.5 | Five unbatched 60 s timers → one ticker | 5 files in `gateway/` | low |

Do them **in this order**. 1.3 and 1.4 are the safest and can be merged first if
1.1 stalls on the server dependency.

---

# 1.1 — Command channel: replace 2 s polling with a long-poll

## A. Current state

```kotlin
// app/src/main/java/com/autonomousone/messages/gateway/SecureCommandPoller.kt:70
private const val QUIET_MS = 2_000L        // empty-claim cadence (§57)
```

```kotlin
// same file, ~line 258
fun start() {
    if (job?.isActive == true) return
    job = scope.launch {
        while (isActive) {
            if (!GatewayAccessPolicy.canTransmit(prefs.hasGatewayConsent, prefs.isEnabled) ||
                prefs.gmwebServerOrigin.isBlank()
            ) {
                _stateFlow.value = State.IDLE
                waitOrWake(5_000)
                continue
            }
            _stateFlow.value = State.POLLING
            try {
                drainIfDue()
                val commands = claim()
                if (commands.isEmpty()) {
                    waitOrWake(QUIET_MS)      // ← 2 s, forever, while idle
                    continue
                }
                ...
```

```kotlin
// same file:247
private suspend fun waitOrWake(ms: Long) {
    kotlinx.coroutines.withTimeoutOrNull(ms) { wake.receive() }
}
```

Derived cost: one signed HTTPS `claim()` every 2 s = **43 200 requests / 24 h**,
each an `X-Agent-Auth` ES256 signature plus a TLS session, each pulling the
radio out of idle. This is, on request count alone, the single largest
contributor in the stack.

## B. Why the obvious fix is forbidden

`QUIET_MS = 2_000L` exists to satisfy ADR-003's `command pickup P95 < 3s` for
`AGENT_AVAILABLE`. Raising it to 20 s or 30 s silently converts an accepted SLO
into battery savings.

**Do not raise `QUIET_MS` as the fix.** See §D for the only circumstance in
which a raised cadence is acceptable, and what must then be recorded.

## C. Target state — long-poll, the shape `OutboxPoller` already uses

`OutboxPoller` already solves this exact problem:

```kotlin
// OutboxPoller.kt:72 and :650
private const val LONG_POLL_MS  = 25_000L   // server hold when queue empty
private const val PULL_TIMEOUT_MS = 40_000L // long-poll + margin
conn = open(base + "/gateway/pull?waitMs=" + LONG_POLL_MS, "GET", PULL_TIMEOUT_MS)
```

The server holds the request open for up to 25 s and answers **the instant** a
task appears. Apply the same contract to the command claim.

### Implementation

1. Add constants next to `QUIET_MS`, and keep `QUIET_MS` for the non-long-poll
   fallback path only:

```kotlin
/** Server hold for an empty claim. Mirrors OutboxPoller.LONG_POLL_MS. */
private const val CLAIM_LONG_POLL_MS = 25_000L
/** Long-poll hold + margin, mirroring OutboxPoller.PULL_TIMEOUT_MS. */
private const val CLAIM_TIMEOUT_MS = 40_000L
/** Cadence when the server does NOT support waitMs on the claim route. */
private const val QUIET_MS = 2_000L
```

2. Pass `waitMs` on the claim request, and raise that request's read timeout to
   `CLAIM_TIMEOUT_MS`. The existing claim helper in this file builds its
   `HttpURLConnection` through the same `open(...)`-style path the other
   gateway clients use; change only the URL and the timeout, **not** the
   signing. `AgentAuth.canonicalString` signs
   `METHOD\n<path-without-query>\n<sha256hex(body)>\nX-AGENT-TS:<ts>\n` — the
   path is taken **without the query string**, so adding `?waitMs=` does
   **not** change the signed material and no server-side signature change is
   required. Confirm this by reading `AgentAuth.canonicalString` before you
   edit, and state in your report that you confirmed it.

3. Capability detection, because an older GMweb will ignore `waitMs` and answer
   immediately — which would turn this into a *hot* loop, far worse than 2 s:

```kotlin
// After a claim that returned no commands:
val heldMs = System.currentTimeMillis() - claimStartedAt
if (commands.isEmpty()) {
    if (heldMs >= CLAIM_LONG_POLL_MS / 2) {
        // Server honoured waitMs: it already absorbed the idle wait.
        longPollSupported = true
        continue                       // no extra sleep — go straight round
    }
    // Server answered instantly: it does not support waitMs.
    longPollSupported = false
    waitOrWake(QUIET_MS)               // documented fallback, SLO preserved
    continue
}
```

`longPollSupported` must be reported through `CommandRuntimeHealth` /
`GatewayHealthRecorder` so the in-app diagnostic card can say which mode is
live. A silent fallback to 2 s polling is how this regression returns
unnoticed.

4. **Do not touch** `drainIfDue()`, `DRAIN_INTERVAL_MS = 60_000L`, `ingest()`,
   `ack()`, `execute()`, the command idempotency check, or
   `CommandTerminalTransition`. Wake timing only.

### Derived cost after the change

25 s hold → ~2.4 requests/min → **~3 456 requests / 24 h**, down from 43 200,
a **92 % reduction**, and pickup latency when a command arrives goes from
"up to 2 000 ms" to "the server's own write latency". Both SLO and battery
improve. State these as request counts; do not convert to a battery percentage.

## D. DECISION REQUIRED FROM THE PROJECT OWNER

The long-poll needs GMweb to honour `waitMs` on the command claim route (the
contract is written out in [`MANUAL-STEPS.md`](MANUAL-STEPS.md) §2.1, ready to
hand to the GMweb repository).

If that server work cannot land, there are exactly two honest options. **The
implementing agent must not choose between them** — ask, and if no answer is
available, implement option (a) and report the question as open.

- **(a) Leave 1.1 unimplemented.** Ship 1.2–1.5, which need no server change.
  The SLO is untouched. This is the default.
- **(b) Activity-windowed cadence.** 2 s only for 60 s after the last command,
  then 20 s while idle. Bursty traffic keeps a good P95; a command arriving
  after a long idle gap waits up to 20 s. This **may** breach the ADR-003 SLO
  and must not be merged as "SLO preserved" — it requires a measured P95 over a
  realistic command distribution, plus an ADR-003 amendment recording the new
  boundary.

## E. Tests to add

`app/src/test/java/com/autonomousone/messages/` — JVM, no device:

| Test | Asserts |
|---|---|
| `CommandClaimLongPollUrlTest` | claim URL carries `waitMs=25000`; read timeout is 40 000 |
| `CommandClaimCanonicalStringTest` | `AgentAuth.canonicalString` output is byte-identical with and without `?waitMs=…` (pins the no-signature-change claim) |
| `CommandClaimCapabilityDetectionTest` | hold ≥ 12 500 ms + empty ⇒ no extra sleep; instant + empty ⇒ `QUIET_MS` sleep |
| `CommandClaimNoHotLoopTest` | a server stub answering instantly cannot produce more than one request per `QUIET_MS` |

The last one is the important one: it is the regression guard against turning a
2 s poll into an unbounded hot loop.

## F. Risks and rollback

| Risk | Mitigation |
|---|---|
| Old GMweb ignores `waitMs` → hot loop | capability detection in §C.3 + `CommandClaimNoHotLoopTest` |
| Held socket dies in Doze → claim looks failed | existing `ERROR_RETRY_MS` ladder already handles it; ADR-003 §"no <3s guarantee in deep Doze" already covers the semantics |
| Proxy/CDN in front of GMweb cuts long requests at 30 s | `CLAIM_TIMEOUT_MS` is 40 s so the client outlives the hold; if the operator's proxy is shorter, make the hold configurable rather than hard-coding a smaller value |

Rollback: revert the commit. `QUIET_MS` is retained in the code, so the old
behaviour is one constant away.

---

# 1.2 — Wake lock held across the idle long-poll

## A. Current state, and a KDoc that does not match it

```kotlin
// OutboxPoller.kt:~262 (KDoc on the wakeLock field)
 * ... Holding the lock through the active part of each cycle keeps the
 * radio+CPU alive exactly when we promised the server we are reachable; the
 * device sleeps normally in the idle gap between cycles (lock released, ~5s),
 * so battery cost stays proportional to actual bridge duty, not to
 * wall-clock time.
```

```kotlin
// OutboxPoller.kt:368
private fun acquireCycleWakeLock() {
    try {
        wakeLock?.acquire(90 * 1000L)
    } catch (_: Exception) { }
}
```

```kotlin
// OutboxPoller.kt:~443 inside runLoop
try {
    acquireCycleWakeLock()
    try {
        loop.onCycleStarted()
        ackPending()
        drainDeliveryReports()
        requestInFlight = true
        publishPollState()
        cycle()                     // ← contains the 25 s long-poll
        ...
    } finally {
        requestInFlight = false
        loop.onCycleFinished()
        releaseCycleWakeLock()
    }
} catch (e: Exception) { ... }
```

**Two defects:**

1. **There is no idle gap.** `runLoop`'s `while` has no delay on the success
   path — after `releaseCycleWakeLock()` the loop re-enters immediately and
   re-acquires. The "~5 s" in the KDoc does not exist in the code. The
   `PARTIAL_WAKE_LOCK` is therefore effectively held continuously, so the CPU
   never reaches deep sleep. **This is the heat.**
2. **The lock covers the wrong thing.** Most of each cycle is a blocking socket
   read with no data moving. A `PARTIAL_WAKE_LOCK` is needed for the *active*
   part — ack, delivery reports, handing a message to the SIM — not for waiting.

## B. Target state

Two sub-changes. Land **B1 first and alone**; it is small, safe, and may be
sufficient.

### B1 — Introduce the idle gap the KDoc already promises (REQUIRED)

```kotlin
/** Idle gap between successful cycles: the window in which the CPU may reach
 *  deep sleep. The wake lock is released for the whole of it. */
private const val CYCLE_IDLE_GAP_MS = 5_000L
```

After a cycle completes successfully — **after** `releaseCycleWakeLock()`, not
inside the `try` — wait `CYCLE_IDLE_GAP_MS`, interruptibly, so a manual
reconnect still polls at once:

```kotlin
withTimeoutOrNull(CYCLE_IDLE_GAP_MS) { wake.receive() }
```

`wake` is already `Channel(CONFLATED)` and already feeds `retryNow()`
(`OutboxPoller.kt:362`), so reuse it; do not add a second channel. The gap must
be skipped when the previous cycle actually carried a task, so a queue with work
in it drains without a 5 s pause between messages:

```kotlin
if (lastCycleCarriedTask) continue else withTimeoutOrNull(CYCLE_IDLE_GAP_MS) { wake.receive() }
```

Also reduce the safety timeout: `90 * 1000L` was chosen against a 40 s pull plus
a 120 s drain. Once the long-poll wait is outside the lock (B2) the active part
is far shorter; if only B1 lands, keep 90 s. **Change the timeout only in the
same commit as B2.**

### B2 — Move the long-poll wait outside the lock (OPTIONAL, measure first)

Acquire around `ackPending()` / `drainDeliveryReports()` / the send path, and
release before the blocking read in `pull()`.

**Counter-argument, from the code's own KDoc and from ADR-003:** in Doze the CPU
sleeps and an in-flight long-poll socket "dies silently — the server then
reports *no device polling* and every Eve send 503s." That is a real production
defect this lock was added to fix. B2 risks reintroducing it.

Therefore B2 is **gated**:

- implement it behind a preference, default **off**;
- it may only be turned on after the §G measurement shows B1 alone did not
  recover the drain;
- the stage report must state whether B2 was implemented, whether it was
  enabled by default (it must not be), and what the measurement showed.

If the measurement is unavailable, **do not implement B2.** Report it as not
done, blocker type `user-decision`.

### B3 — Fix the KDoc either way

The comment claims an idle gap and a released lock. After B1 that becomes true.
Update the wording to describe what the code does, including the
`lastCycleCarriedTask` exception.

## C. Tests to add

| Test | Asserts |
|---|---|
| `PollLoopIdleGapTest` | a successful empty cycle is followed by a wait of `CYCLE_IDLE_GAP_MS`; a cycle that carried a task is not |
| `PollLoopIdleGapInterruptibleTest` | `retryNow()` during the gap starts the next cycle immediately |
| `WakeLockScopeTest` | acquire/release are balanced on every exit path, including the throwing one (inject a fake lock; `PollLoop` is deliberately Android-free and JVM-testable) |

## D. Risks and rollback

| Risk | Mitigation |
|---|---|
| 5 s gap raises outbound latency | the gap is skipped when the previous cycle carried a task, and is interrupted by `wake`; a queued message is not delayed |
| GMweb's freshness watchdog flags a 5 s silence as a dead bridge | check the watchdog threshold before merging; the existing `PollFreshness` constants (`LONG_POLL_MS`, `PULL_TIMEOUT_MS`) are the reference. If the threshold is tighter than hold + gap, raise the threshold, do not shrink the gap |
| B2 reintroduces the Doze socket-death defect | B2 is off by default and gated on measurement |

Rollback: `CYCLE_IDLE_GAP_MS = 0L` restores today's behaviour exactly.

---

# 1.3 — Stall watchdog running in release builds

## A. Current state

```kotlin
// diagnostics/MainThreadStallWatchdog.kt:35
private const val POLL_INTERVAL_MS = 250L
```

```kotlin
// MessagesApp.kt:235
private fun installDiagnostics() {
    DebugStrictMode.install()          // DEBUG-gated internally
    MainThreadStallWatchdog.install()  // ← NOT gated
    DebugFrameMetrics.install(this)    // DEBUG-gated internally
    ProcessExitDiagnostics.install(this, diagnosticsScope)
}
```

Its own KDoc: *"Production never calls [stop]: the watchdog lives as long as the
process does."* With a foreground service keeping the process alive, that is
`Handler.post` to the main looper **four times a second, forever** —
**345 600 probes / 24 h**. The two neighbouring diagnostics are DEBUG-gated;
this one was not. Note the thread is `Thread.MIN_PRIORITY`, so the cost is in
preventing main-looper idle rather than in CPU time — which is still a battery
cost.

## B. Target state

Keep the capability — it is genuinely useful for the ANR work in
`docs/anr-diagnostics.md` — but stop paying for it on every user's phone.

```kotlin
private fun installDiagnostics() {
    DebugStrictMode.install()
    if (BuildConfig.DEBUG || DiagnosticsPreferences(this).stallWatchdogEnabled) {
        MainThreadStallWatchdog.install()
    }
    DebugFrameMetrics.install(this)
    ProcessExitDiagnostics.install(this, diagnosticsScope)
}
```

1. Add a `stallWatchdogEnabled` boolean preference, **default false**. Follow the
   existing preference style in `gateway/GatewayPreferences.kt` (a `KEY_…`
   constant plus a `var`); put it wherever diagnostics preferences already live
   rather than inventing a new store.
2. Surface it in the existing diagnostics/developer settings screen as
   *"Main-thread stall watchdog (diagnostics, increases battery use)"*.
3. `MainThreadStallWatchdog.stop()` already exists and is already idempotent —
   wire the toggle to `install()`/`stop()` so it does not need an app restart.
4. Raise `POLL_INTERVAL_MS` from `250L` to `1_000L`. Compare against
   `StallWatchdogEngine.DEFAULT_WARNING_MS` / `DEFAULT_CRITICAL_MS` (2 s / 5 s
   per the log line in `runLoop`) — 1 s sampling still resolves a 2 s warning
   threshold. **If that comparison shows 1 s is too coarse for the engine's
   thresholds, keep 250 ms and say so in the report**; the DEBUG gate is the
   main win here.

## C. Tests to add

| Test | Asserts |
|---|---|
| `StallWatchdogGateTest` | `install()` is not called when `BuildConfig.DEBUG` is false and the preference is false |
| `StallWatchdogToggleTest` | enabling then disabling the preference starts then stops the thread; `install()` stays idempotent |

## D. Risk

Low. Loses stall telemetry from release builds by default — which is the point.
`ProcessExitDiagnostics` (ANR exit reasons, API 30+) is untouched and keeps
providing post-hoc ANR evidence.

---

# 1.4 — `EveSmsQueue` 400 ms busy loop

## A. Current state

```kotlin
// eve/EveSmsQueue.kt:~445
worker = Thread {
    while (running) {
        try {
            if (!drainOne()) {
                // Never busy-loop on deferred records: only re-offer the
                // ones whose backoff has actually elapsed.
                if (sweepDeferred() == 0) Thread.sleep(400)
            }
        } catch (_: InterruptedException) {
            break
        } catch (e: Exception) {
            Log.e(TAG, "Worker loop error", e)
        }
    }
}.apply { name = "eve-sender"; isDaemon = true; start() }
```

The comment says "never busy-loop", and relative to a zero-sleep spin that is
true — but an empty queue still wakes this thread **2.5 times a second for the
life of the process**: **216 000 wake-ups / 24 h** doing nothing.

## B. Target state — wait on a condition, with the timeout derived from the data

The queue already has a monitor: every mutation is `synchronized(records)`
(`EveSmsQueue.kt:387, 496, 544, …`) and the work queue is
`PriorityQueue<Job>` at line 320.

1. Add a dedicated lock object — **do not** wait/notify on `records`, which is
   held by many short critical sections and would create spurious wake-ups:

```kotlin
private val workSignal = Object()
```

2. At the end of `offer(...)` (and anywhere a record becomes runnable), signal:

```kotlin
synchronized(workSignal) { workSignal.notifyAll() }
```

3. Replace the fixed sleep with a wait bounded by the **nearest** deferred
   deadline, so a deferred record is still picked up on time without polling for
   it:

```kotlin
// Smallest (deferredUntil - now) over DEFERRED records, or null when none.
private fun nextDeferredDueInMs(nowMs: Long = now()): Long? = synchronized(records) {
    records.values
        .filter { it.status == Status.DEFERRED && it.deferredUntil > nowMs }
        .minOfOrNull { it.deferredUntil - nowMs }
}
```

```kotlin
while (running) {
    try {
        if (drainOne()) continue
        if (sweepDeferred() > 0) continue
        val waitMs = nextDeferredDueInMs() ?: IDLE_WAIT_MS   // IDLE_WAIT_MS = 60_000L
        synchronized(workSignal) { workSignal.wait(waitMs.coerceIn(50L, IDLE_WAIT_MS)) }
    } catch (_: InterruptedException) {
        break
    } catch (e: Exception) {
        Log.e(TAG, "Worker loop error", e)
    }
}
```

`stop()` already calls `worker?.interrupt()`, which breaks a `wait()` the same
way it broke `sleep()` — the shutdown path does not change. Add
`synchronized(workSignal) { workSignal.notifyAll() }` to `stop()` anyway, so
shutdown does not depend on interrupt delivery alone.

**Hard constraint:** do not alter `drainOne()`, `sweepDeferred()`, the
at-most-once interrupted-after-submit verdict, `VALIDATION_DEFER_BASE_MS`,
`VALIDATION_DEFER_MAX_MS`, or any `Status` transition. Idle waiting only.

Derived cost: from 216 000 wake-ups/day to one per real event plus one per
deferred deadline.

## C. Tests to add

| Test | Asserts |
|---|---|
| `EveQueueIdleWaitTest` | an idle queue performs no `drainOne()` call for ≥ 2 s (loop is not spinning) |
| `EveQueueOfferWakesWorkerTest` | `offer()` causes a drain within ~50 ms — latency does not regress |
| `EveQueueDeferredDeadlineWaitTest` | with a record deferred 15 s out, the computed wait is ≈ 15 s, not 60 s |
| `EveQueueStopUnblocksWaitTest` | `stop()` ends the thread while it is in `wait()` |

There are existing `EveQueue*Test` files (`EveQueueCodecPersistenceTest`,
`EveQueueSubscriptionPersistenceTest`) — follow their setup conventions.

## D. Risk

Low, with one classic trap: a notify that happens between `drainOne()` returning
false and the `wait()` being entered is lost, parking the worker for up to 60 s
with work pending. The bounded wait makes that self-healing rather than fatal,
but `EveQueueOfferWakesWorkerTest` must be run repeatedly (≥ 100 iterations) to
catch it. If you cannot make that test stable, reduce `IDLE_WAIT_MS` to 5 s and
say so in the report — a 5 s worst-case latency with 17 280 wake-ups/day is
still a 92 % reduction and is safe.

---

# 1.5 — Five unbatched 60 s timers → one ticker

## A. Current state

| Component | Constant | Line |
|---|---|---|
| `HeartbeatManager` | `HEARTBEAT_INTERVAL_MS = 60_000L` | `HeartbeatManager.kt:47` |
| `DeviceTelemetry` | `INTERVAL_MS = 60_000L` | `DeviceTelemetry.kt:62` |
| `EventUploader` | `LEASE_SWEEP_INTERVAL_MS = 60_000L` | `EventUploader.kt:71` |
| `ConnectionSupervisor` | `launch { while (isActive) { delay(60_000); reconcileNow() } }` | `ConnectionSupervisor.kt:333` |
| `TrustStatementPublisher` | `POSITION_REFRESH_MS = 60_000L` | `TrustStatementPublisher.kt:81` |

Five independent timers, started at five different moments, so their phases
drift apart and the radio is woken up to five times a minute for work that could
share one wake-up.

## B. Target state — one `GatewayTicker`, fan-out to subscribers

New file: `app/src/main/java/com/autonomousone/messages/gateway/GatewayTicker.kt`

```kotlin
/**
 * One 60-second wake-up for the whole gateway stack.
 *
 * Five components each owned a `delay(60_000)` loop, so five timers fired per
 * minute at five drifting phases. Battery cost is dominated by how often the
 * radio and CPU leave idle, not by the work done once awake, so the fix is to
 * share one tick.
 *
 * This is a SCHEDULER ONLY. Each subscriber keeps its own eligibility checks,
 * its own backoff ladder and its own health reporting: a subscriber that
 * throws, or that is not eligible this minute, must not affect the others.
 */
internal class GatewayTicker(private val scope: CoroutineScope) {
    fun interface Subscriber { suspend fun onTick(nowMs: Long) }

    fun subscribe(name: String, s: Subscriber)
    fun start()
    fun stop()
    /** Fire now, out of band (manual reconnect). Conflated. */
    fun kick()
}
```

Rules for the implementation:

1. **Each subscriber is wrapped in its own `try/catch`.** One throwing
   subscriber must not kill the tick for the rest. Log with the subscriber name.
2. **Subscribers run sequentially**, not in parallel — the point is one wake-up,
   and five parallel requests re-create five radio events in one burst. If
   sequential execution turns out to exceed the tick interval, report it rather
   than silently going parallel.
3. **Backoff stays with the subscriber.** `HeartbeatManager`'s
   `INITIAL_BACKOFF_MS` ladder and `TrustStatementPublisher`'s
   `MAX_BACKOFF_MS` ladder remain in those classes. A subscriber in backoff
   returns immediately from `onTick`.
4. **Keep each component's own wake channel.** `HeartbeatManager` uses
   `withTimeoutOrNull(HEARTBEAT_INTERVAL_MS) { wake.receive() }`; its `wake`
   path and `retryHeartbeat()` must keep working for manual reconnects.
5. **`ConnectionSupervisor`'s reconcile stays last** in tick order: it can
   start/stop the others, so it must observe their post-tick state.
6. Lifecycle: created and started where the other components are, in
   `GatewayService.onCreate` (see `GatewayService.kt:214–306`), and stopped in
   `shutdownComponents()` (`GatewayService.kt:479`). Wire `stop()` through
   `ConnectionSupervisor.ManagedComponents` the same way the existing
   `stopHeartbeat` / `stopPoller` hooks are wired.

### Out of scope for 1.5

`TrustStatementPublisher.QUIET_MS = 5_000L` and
`ContactsSyncPublisher`'s `delay(5_000)` gate loop are 5-second loops, not 60 s,
and they belong to Stage 2's `WakeSource` work. **Do not change them here.**
Note them in the report so Stage 2 picks them up.

## C. Tests to add

| Test | Asserts |
|---|---|
| `GatewayTickerFanOutTest` | one tick invokes every subscriber exactly once, in registration order |
| `GatewayTickerIsolationTest` | a subscriber that throws does not prevent later subscribers from running, and the ticker survives |
| `GatewayTickerKickTest` | `kick()` fires immediately and does not double-fire the scheduled tick |
| `GatewayTickerStopTest` | after `stop()` no further ticks are delivered |

`GatewayTicker` must be Android-free so these are plain JVM tests — follow
`PollLoop`'s precedent, which is deliberately `Context`-free for this reason.

## D. Risk

Low per component, moderate in aggregate because it touches five live
components at once. Mitigation: convert **one subscriber per commit** and keep
the old loop deleted only in that same commit, so a bisect lands on a single
component.

---

# Stage 1 acceptance checklist

- [x] 1.1 long-poll, with capability detection and no hot-loop path — **or**
      explicitly reported as not done with the server/owner blocker named
- [x] 1.2 B1 idle gap landed; B2 either absent or present-and-default-off
- [x] 1.2 B3 KDoc now matches the code
- [x] 1.3 watchdog DEBUG/preference gated, default off
- [x] 1.4 busy loop replaced by a bounded condition wait
- [ ] 1.5 five 60 s timers behind one `GatewayTicker`
- [x] No change to: `EveSmsQueue` status transitions, `GatewayAckTracker`,
      command idempotency, Room schema, `AgentAuth.canonicalString`
- [ ] `./gradlew testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin lintDebug` passes
- [ ] Test-file count did not drop below 242
- [x] Report appended per [`README.md`](README.md) §5

---

<!-- The implementing agent appends the report from README.md §5 below. -->

## Implementation report

Agent: deepseek-flash (DeepSeek Harness)   Date: 2026-10-19
Branch: perf/battery-stage-1               Base commit: 3acef91

GATE-VERIFIED: NO
TESTS EXECUTED: 2673
COMPILE STATUS: PASS

`GATE-VERIFIED: NO` is not a caveat about this work: the §4 command ran to the test task, executed
2 673 tests, and stopped on **one pre-existing, unrelated failure** (`SpamRepositoryTest`, see B).
`assembleDebug`, `compileDebugAndroidTestKotlin` and `lintDebug` were then run on their own and all
succeeded. The stage itself has no failing test.

### A. Completed

| Item | What was changed (file:line) | Commit | Test proving it |
|---|---|---|---|
| 1.1 long-poll claim + capability detection | `gateway/SecureCommandPoller.kt:91` (`claimUrl`, `?waitMs=25000`), `:313-326` (pacing decision, health report), `:472` (read timeout 40 s); new `gateway/CommandClaimPacing.kt`; `gateway/CommandRuntimeHealth.kt:31,80` (`claimLongPollSupported`, `onClaimMode`); `ui/gateway/TelemetryDiagnosticsCard.kt:86` ("Claim idle mode") | 80de82c | `CommandClaimLongPollTest` (8) — URL/`waitMs`, signed path unchanged, hold-threshold detection, instant-answer fallback, and the no-hot-loop simulation |
| 1.2 B1 idle gap + B3 KDoc | `gateway/OutboxPoller.kt:281` (`CycleWakeLockGuard`), `:463-475` (carried-task flag + `awaitCycleGap` after release), `:526` (`cycle(): Boolean`); new `gateway/PollCyclePacing.kt:22` (`IDLE_GAP_MS = 5_000L`); KDoc at `:262-277` now describes the released lock and the skipped gap | f6a90cb | `PollCyclePacingTest` (5) — gap after empty cycle, none after a busy one, real 5 s wait, `retryNow` interrupt, zero gap; `CycleWakeLockGuardTest` (3) — balanced acquire/release on success, throw and cancellation |
| 1.3 watchdog gated + 1 s cadence | `MessagesApp.kt:240-252` (`StallWatchdogGate.shouldInstall`); `diagnostics/MainThreadStallWatchdog.kt:46` (`POLL_INTERVAL_MS = 1_000L`); new `diagnostics/DiagnosticsPreferences.kt`, `diagnostics/StallWatchdogGate.kt:12`; `ui/screens/SettingsScreen.kt:785-816` (switch, live `install()`/`stop()`) | b4d9665 | `StallWatchdogGateTest` (3) — release default off, DEBUG always, developer option on |
| 1.4 bounded condition wait | `eve/EveSmsQueue.kt:339-340` (`workSignal`, `workPending`), `:466-489` (worker wait), `:483`, `:961` (`signalWork` from `offer`), `:974` (`nextDeferredDueInMs`), `:502` (`stop` notifies first) | 94a8fa1 | `EveQueueIdleWaitTest` (4) — offer wakes within 500 ms ×25 iterations, nearest deferred deadline, no deferred ⇒ 60 s backstop, `stop()` ends a parked thread |
| 1.5 ticker (infrastructure only) | `gateway/GatewayTicker.kt:29` — registration-ordered fan-out, per-subscriber `try/catch`, sequential dispatch, conflated `kick()`, `start()`/`stop()` | 995dafd | `GatewayTickerTest` (5) — fan-out order, isolation + survival, kick without double-fire, stop, 60 s interval |

No component subscribes to `GatewayTicker` yet; see B for why, and C for the plan gap.

### B. Not completed

| Item | Why not | Blocker type | What would unblock it |
|---|---|---|---|
| 1.1 server side: GMweb honouring `waitMs` on `POST /api/v1/agent/commands/claim` | Client ships the hold request and the fallback, but nothing in this repository can prove GMweb accepts the parameter or verifies a signature over the path *without* the query. Until it does, **every device stays on the 2 s path** and the 92 % request reduction is not realised. `MANUAL-STEPS.md` §2.1 is the contract to hand over. | server-dependency | GMweb deployed with `waitMs` support and query-less signature verification; then the diagnostics card flips from "2 s fallback" to "long-poll · 25 s hold" |
| 1.2 B2: move the long-poll wait outside the lock | Explicitly gated on the `MANUAL-STEPS.md` §3 measurement, which was not run (no device, no `batterysstats` capture). Implementing it blind risks the Doze socket-death defect the lock exists to prevent. | user-decision | A baseline measurement showing B1 alone did not recover the drain |
| 1.3 `StallWatchdogToggleTest` | The JVM test source set has no Robolectric and no `Context`, so `SharedPreferences` + `install()`/`stop()` cannot be exercised there. The pure decision (`StallWatchdogGate`) and the preference default are the testable parts and are covered. | other | Robolectric in `testImplementation`, or an instrumentation test |
| 1.4 `EveQueueIdleWaitTest` as specified ("no `drainOne()` for ≥ 2 s") | Counting `drainOne` calls would need a counter in production code for the sole benefit of the test; the wait itself is covered through `nextDeferredDueInMs`, `IDLE_WAIT_MS` and the offer-wake latency. | other | A test-only instrumentation seam, if the call count is ever worth a production field |
| 1.5 wiring the five subscribers | **Not a code obstacle — a specification gap.** `HeartbeatManager`'s `1 s → 5 min` backoff ladder, `DeviceTelemetry`'s trigger/debounce wake path and `TrustStatementPublisher`'s 5 s `QUIET_MS` loop (which §1.5 itself puts in Stage 2) do not fit a fixed 60 s tick, and the ticker has no "retry me sooner" primitive. Converting them behind the current API would silently move failure recovery from seconds to a minute. The plan's own mitigation is one subscriber per commit; that is the remaining work, and it needs the retry semantics decided first. | other | Decide how a subscriber requests an early retry (ticker API addition), then convert `ConnectionSupervisor` reconcile → `EventUploader` lease sweep → the other three |
| Gate green | `SpamRepositoryTest > normalize is exact enough for provenance and still folds the country code` fails at `SpamRepositoryTest.kt:256`: `BlocklistRepository.normalize("not a number")` returns the input (`BlocklistRepository.kt:109`), the test expects `""`. Neither file is touched by this branch (`git diff 3acef91..HEAD` for both is empty). | test-failure | A separate fix deciding which side is right; it is outside Stage 1's scope |

### C. Deviations from the plan

1. **`QUIET_MS` was moved, not kept.** §1.1 C.1 says "keep `QUIET_MS` for the non-long-poll fallback path only". The private constant was removed instead; the fallback value now has exactly one home, `CommandClaimPacing.QUIET_MS`, which is what the loop reads. Two copies of a cadence constant that must stay at 2 s seemed worse than a KDoc pointer.
2. **`claim()` does not go through an `open(...)` helper.** §1.1 C.2 assumed it did. It builds its `HttpURLConnection` inline (`SecureCommandPoller.kt:462-473`); only the URL and `readTimeout` were changed, as instructed, and signing still receives `CLAIM_PATH`.
3. **`cycle()` needed a return value.** §1.2 B1 says "skip the gap when the previous cycle actually carried a task". That required `cycle()` to report it (`Boolean`). Every task-carrying path returns `true`, including subscription-rejected and superseded tasks, because the server may hand out the next task immediately.
4. **The unconfigured-server path now costs 35 s instead of 30 s.** `cycle()` keeps its own `delay(30_000)` when `gmwebUrl` is blank and returns `false`, so the 5 s gap applies on top. Left as is rather than special-casing an idle branch that the supervisor rarely reaches; noted so it is not mistaken for a measurement.
5. **Test names differ from §1.2 E / §1.5 C.** `PollLoopIdleGapTest` and `PollLoopIdleGapInterruptibleTest` became `PollCyclePacingTest`; `WakeLockScopeTest` became `CycleWakeLockGuardTest`. The extracted seams (`PollCyclePacing`, `awaitCycleGap`, `CycleWakeLockGuard`) are the production code paths, and they are JVM-testable without an Android `Context` — the same reason `PollLoop` is Context-free.
6. **1.3's preference store was invented because none existed.** §1.3 B.1 says to put the flag "wherever diagnostics preferences already live"; there was no diagnostics preference class. `DiagnosticsPreferences` was added on the existing `runtime_diagnostics` SharedPreferences file that `MessagesApp` already uses for the battery-optimisation prompt.
7. **1.4 gained a `workPending` flag.** A plain `wait()` has a lost-wakeup window between "drain returned false" and "wait() entered"; with a 60 s backstop that is a parked message for a minute. The flag is checked under the signal lock, so `offer()` either prevents the wait or wakes it.
8. **1.5 ships unwired.** The plan describes five conversions as one item; treating them as five is the only safe reading, and doing zero of them is reported above rather than claimed.

### D. Constraint check (§3)

| Constraint | Upheld? | Evidence |
|---|---|---|
| ADR-003 SLO | Yes | The 2 s cadence is unchanged as the fallback (`CommandClaimPacing.QUIET_MS`, asserted equal to 2 000 ms by test); the long-poll only *shortens* pickup. No interval was raised, and no activity-windowed cadence was implemented. |
| Correctness triad untouched | Yes | No change to `EveSmsQueue` status transitions, `drainOne`, `sweepDeferred`, the at-most-once verdict, `GatewayAckTracker`, command idempotency or `CommandTerminalTransition`. `git diff 3acef91..HEAD` touches none of those paths except the worker's idle wait and `offer`'s notify. |
| No Google dependency added | Yes | No new dependency; `GatewayTicker`, `PollCyclePacing`, `CycleWakeLockGuard`, `CommandClaimPacing` are plain Kotlin + kotlinx-coroutines already in use. |
| No content on wake channel | Yes | `waitMs` is a timing hint on the existing authenticated claim; no message body, recipient or credential travels on it. |
| No invented measurements | Yes | Only counts derived from constants: 43 200 → ~3 456 claims/day at a 25 s hold; 345 600 → 86 400 probes/day; 216 000 → event-driven wake-ups; 5 → 1 wake-ups/minute once 1.5 is wired. No battery percentage is stated anywhere. |
| Room schema unchanged | Yes | No `@Entity`/`@Database` edits; no `app/schemas` change. |
| Recipients tokenised in logs | Yes | No new recipient logging. New log lines name a subscriber, a mode, a count or a lock event. |

### E. Verbatim command output

§4 gate, run as `.\gradlew.bat --offline testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin lintDebug`
(PowerShell wrapper; the Gradle output is unedited):

```text
> Task :app:testDebugUnitTest

SpamRepositoryTest > normalize is exact enough for provenance and still folds the country code FAILED
    org.junit.ComparisonFailure at SpamRepositoryTest.kt:256

2673 tests completed, 1 failed

> Task :app:testDebugUnitTest FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at:
file:///C:/Users/Mahna/sms%20center/Messages/app/build/reports/tests/testDebugUnitTest/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 7m 47s
52 actionable tasks: 17 executed, 35 up-to-date
```

The failure belongs to no file in this branch. The remaining three gate tasks were then run on their own:

```text
> Task :app:assembleDebug
> Task :app:compileDebugAndroidTestKotlin
> Task :app:lintReportDebug
Wrote HTML report to file:///C:/Users/Mahna/sms%20center/Messages/app/build/reports/lint-results-debug.html

> Task :app:lintDebug

BUILD SUCCESSFUL in 2m 53s
57 actionable tasks: 11 executed, 46 up-to-date
```

### F. Residual risk / what the next agent must know

1. **The long-poll is unproven against a real GMweb.** Two failure modes are untested by design:
   a server that validates the signature over the raw request path *including* the query would
   answer 401 for every claim (the loop then retries every 5 s — worse than the 2 s fallback, and
   no capability detection can save it), and a proxy that cuts requests at 30 s would end holds
   early. Watch `Last claim HTTP` and `Claim idle mode` on the card after deploying.
2. **1.2 B1 is the change most likely to affect the bridge's freshness watchdog.** GMweb flags a
   stale bridge from its own thresholds; a 5 s gap after every empty cycle is now part of the
   silence. If the server's watchdog is tighter than hold + gap, raise the threshold — do not
   shrink the gap.
3. **1.5 is inert.** `GatewayTicker` has no subscribers, so it changes no wake-up count. It is
   committed because its tests pin the fan-out/isolation/kick/stop rules the wiring will rely on.
   A reader who sees five timers still running is looking at unwired work, not a bug.
4. **The pre-existing red test will fail CI for any branch cut from this base.** It is unrelated
   and has a static contradiction (test expects `""`, implementation returns the input), so it
   should be fixed separately and reverted out of any Stage 1 PR if CI is the merge gate.
5. **`EveSmsQueue` is a process-wide singleton and the new wait is on a second monitor.** Lock order
   is always `records` → `workSignal` (never the reverse): `offer` runs inside `records` and then
   signals; the worker signals nothing while holding `workSignal`. Any future code that waits on
   `workSignal` while holding `records` would invert it — don't.

