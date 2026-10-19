# ADR-008 — Wake source abstraction

**Status:** Accepted (Stage 2)
**Date:** 2026-10-19
**Scope:** `gateway/wake/`, `OutboxPoller`, `SecureCommandPoller`, `ConnectionSupervisor`, `GatewayService`

---

## Context

ADR-003 fixes an availability contract for `AGENT_AVAILABLE`: a durable command queue, eventual
pickup, no duplicate execution, and `command pickup P95 < 3s`. It explicitly permits the transport
underneath that contract to change:

> Transport may later be optimized (long-poll → other transports) without changing command
> semantics — **the SLO boundary is measured at the durable queue, not the wire.**

The project owner now wants three selectable wake mechanisms — polling, a self-hosted socket, and
FCM — with the user able to choose. Adding `if (mode == …)` branches inside `OutboxPoller` and
`SecureCommandPoller` would spread transport decisions through two classes that already own hard
correctness rules: at-most-once delivery, the ACK ledger, and the poll-loop zombie guard. Each new
transport would then have to be understood as a variation of those rules rather than as a caller of
them.

## Decision

### 1. A wake source delivers one bit, and never the work

> **A `WakeSource` says "there may be downstream work". It never delivers the work.**

Everything that fetches, validates, sends, ACKs or persists stays in one place: the authenticated
`GET /gateway/pull` + `POST /gateway/ack` path already used by `OutboxPoller`. A wake source's only
job is to decide *when* to say "wake".

Three consequences follow, and all of them are load-bearing:

1. **Security does not change with the transport.** A socket or an FCM ping that carries no content
   cannot leak content. The Stage 4/5 threat model is then "can an attacker make this phone call its
   own server unnecessarily" — a denial-of-service nuisance, not a disclosure.
2. **ADR-006 (sensitive-message local-only firewall) is unaffected**, because no new path carries
   message data.
3. **Each new transport stays small.** `SocketWakeSource` is ~150 lines because it only has to
   decide when to say "wake", not how to deliver.

Payload fields on a wake channel are therefore **forbidden**, even when a reviewer finds them
convenient. A payload on the wake channel would move the security boundary, which is precisely what
this ADR exists to prevent.

### 2. POLLING is the permanent floor

`PollingWakeSource` wraps the existing pollers and adds no logic of its own. It is never removed:
it is the only mechanism that needs neither platform support (Play Services) nor server support.
Every ladder ends there.

### 3. The AUTO ladder, and reported fallback

`WakeSourceSelector.resolve` is pure and total:

```
AUTO              → FCM if available, else SOCKET if available, else POLLING
explicit POLLING  → POLLING
explicit SOCKET   → SOCKET if available, else POLLING + fellBackFrom = SOCKET
explicit FCM      → FCM if available,    else POLLING + fellBackFrom = FCM
```

`AUTO` is a ladder, not a promise: a missing capability is the ladder working, so it is not reported
as a fallback. An **explicit** choice is a promise; when it cannot be kept the resolution carries
`fellBackFrom` and a `WakeUnavailable` reason, and the gateway diagnostic card shows both. A user
whose chosen mode silently degraded to polling is exactly the regression this programme must not
ship.

### 4. The SLO boundary is unchanged

The 3 s pickup measurement remains at the durable queue. A faster transport can improve observed
pickup; a slower or unavailable one falls back to polling, whose cadence is the existing 2 s claim /
25 s long-poll contract from Stage 1. No wake source may relax or reinterpret the SLO.

## HTTP stacks, and what does not ride the ticker

The APK contains **three** HTTP stacks, and that is a decision to record, not an accident to
discover later:

| Stack | Used by |
|---|---|
| `HttpURLConnection` | the app's own 15 signed REST call sites. `AgentAuth`'s canonical string is byte-pinned by a test and a silent client swap makes every request 401 → credential wipe, so a migration is not a battery task. |
| `okhttp 2.5.0` | the vendored `mmslib` AAR — the **live** MMS send/download path. Load-bearing; see `STAGE-3-okhttp.md` §0. |
| `okhttp3 4.12.0` | Coil (image loading), and a future WebSocket transport if Stage 4 ships. Arrives transitively; pin it explicitly if it is used. |

`TrustStatementPublisher` stays **off** the shared ticker. Its `QUIET_MS = 5_000L` loop is a publish
cadence, not a 60 s maintenance check, and its 60 s position refresh is a check *inside* that 5 s
loop. Moving it to a 60 s tick would change when trust statements are published — a behaviour change
this stage does not make.

## Status of §2.5

`GatewayTicker.requestEarlierTick` is implemented and tested (per-subscriber, coalescing, 1 s floor,
additive to the shared tick). `ConnectionSupervisor`'s 60 s reconcile is the one subscriber on the
ticker, and the tick now lives exactly as long as the supervisor loop (`ensureLoop` → `shutdown`),
not as long as the wake source.

**Deferred to a "Stage 2b"**: the `EventUploader` lease sweep, `DeviceTelemetry`'s periodic report
and `HeartbeatManager`. They are gated on an on-device measurement showing those loops matter; wiring
a 60 s tick under a 1 s→5 min heartbeat ladder without that measurement is a reliability risk, not a
battery win. `requestEarlierTick` therefore has **no production caller by design** for now.

## Consequences

- **Stage 2 ships the seam only.** `fcmAvailable` and `socketAvailable` are hard-wired `false`, so
  `AUTO` resolves to `POLLING` and the wire is byte-identical to before the refactor.
- **The fetch side needs no new plumbing.** A non-polling source calls `onWake`, which is wired to
  `OutboxPoller.retryNow()` — the method that already interrupts the backoff through a conflated
  channel.
- **Stage 4 adds `SocketWakeSource`; Stage 5 would add `FcmWakeSource`** (and must first supersede
  ADR-003's zero-Google-infrastructure rule). Each is a capability flag and a class, not a change to
  the data path.
- **Health gains a dimension, not a second source of truth:** the active source and any fallback are
  recorded in `GatewayHealthRecorder` and rendered on the existing card.
- **Rollback is a plain revert.** The stage adds a seam and a preference defaulting to the old
  behaviour; no data or preference migration is involved.
