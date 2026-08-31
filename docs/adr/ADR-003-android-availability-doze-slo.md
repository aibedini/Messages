# ADR-003 — Android Availability / Doze / Command Pickup SLO

**Status:** Accepted (LOCK 11 of the Messaging Platform architecture session)
**Date:** 2026-08-31
**Scope:** Android Agent reliability, GMweb SLO definitions, Gateway device policy

## Context

The RFP set `command pickup P95 < 3s`. Without FCM (a hard project rule — zero Google infrastructure dependency) a hard sub-3-second guarantee for ALL device states is not honest: Android defers network and jobs in Doze, and from Android 15 the `dataSync` foreground-service type has a cumulative 6-hour-per-24h background cap, so a permanent dataSync FGS cannot be the reliability pillar. WorkManager is itself deferred in Doze — it is not a real-time wakeup mechanism. Correctness therefore must come from the durable command + eventual pickup + no-duplicate-execution triad (TechSpec Rules 3/4/8), never from a live connection.

## Decision

### SLO is defined only for AGENT_AVAILABLE

`command pickup P95 < 3s` applies exclusively when the agent is in state:

```text
AGENT_AVAILABLE =
    process/poller active
  + validated network available
  + OS permits network execution
  + authentication valid
```

For deep Doze / force-stop / restricted battery / process-unavailable states there is **no <3s hard guarantee**. The correctness contract stays:

```text
durable command  +  eventual pickup  +  no duplicate execution
```

Measured separately: `Agent Available → pickup P95 < 3s` and `Agent Suspended/Doze → eventual recovery` are distinct metrics with distinct targets.

### dataSync FGS is not a pillar

A permanent `dataSync` foreground service is not assumed. The FGS strategy is re-audited separately against the Android 15 6h/24h cap before Phase 2 ships it.

### Gateway Appliance Mode (dedicated gateway phone onboarding)

```text
battery optimization exemption guidance
unrestricted battery where OEM permits
device preferably charging
network health checks
Doze diagnostics
```

Exact alarms are a **recovery** mechanism only — never a sub-3-second polling mechanism.

### Test matrix (device states)

```text
screen on / screen off
Doze / deep Doze
battery optimized / battery unrestricted
Wi-Fi / mobile data / network switch
process kill / swipe away / reboot
24h unattended
```

## Consequences

- UI and docs communicate availability honestly: "Waiting for Android…" (TechSpec §93) with last-seen state; never promise instant pickup while the agent is suspended.
- Transport may later be optimized (long-poll → other transports) without changing command semantics — the SLO boundary is measured at the durable queue, not the wire.
- Reliability engineering focuses on: fast recovery from suspension, durable pickup after long gaps, and zero duplicate execution (Phase 1/2 PRs) rather than on defeating Doze.
