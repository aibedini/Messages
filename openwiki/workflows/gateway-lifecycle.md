---
type: workflow
title: "Gateway Startup, Reboot Recovery, and Self-Heal"
description: "How the SMS gateway goes from a user toggle (or a boot/START_STICKY recovery) to a fully reconciled running state, and how network loss, a stale DHCP bind, a bind failure, and component death are each healed by the single ConnectionSupervisor reconcile loop."
tags: [gateway, lifecycle, self-heal, connection-supervisor, reconcile, boot-recovery, dhcp-rebind, foreground-service, consent]
verified:
  - by: openwiki/0.4.3
    at: 2026-08-30T16:24:36.837Z
sources:
  - id: openwiki-source-186e96b8d6739f3745947903
    resource: repo://app/src/main/AndroidManifest.xml
  - id: openwiki-source-b394c571401a67cd53a9d162
    resource: repo://app/src/main/java/com/autonomousone/messages/data/TelephonySyncCoordinator.kt
  - id: openwiki-source-1188ef94bbd10bf1710668b7
    resource: repo://app/src/main/java/com/autonomousone/messages/gateway/ConnectionSupervisor.kt
  - id: openwiki-source-5b64d9fe16083515732d7fa1
    resource: repo://app/src/main/java/com/autonomousone/messages/gateway/GatewayAccessPolicy.kt
  - id: openwiki-source-29e9264a39b70125a964bdc9
    resource: repo://app/src/main/java/com/autonomousone/messages/gateway/GatewayPreferences.kt
  - id: openwiki-source-4c55b07448cb165f971fcb2f
    resource: repo://app/src/main/java/com/autonomousone/messages/gateway/GatewayServer.kt
  - id: openwiki-source-4ad02c444ebadf27339b8cbb
    resource: repo://app/src/main/java/com/autonomousone/messages/gateway/GatewayService.kt
  - id: openwiki-source-6ab27fc85c22eab7ffed6e67
    resource: repo://app/src/main/java/com/autonomousone/messages/gateway/HeartbeatManager.kt
  - id: openwiki-source-ab295a33d81af35971ddfe3a
    resource: repo://app/src/main/java/com/autonomousone/messages/gateway/NetworkMonitor.kt
  - id: openwiki-source-754f516c2fdb40e657ff023b
    resource: repo://app/src/main/java/com/autonomousone/messages/gateway/OutboxPoller.kt
  - id: openwiki-source-8234b1c40928ccc75e3a6a70
    resource: repo://app/src/main/java/com/autonomousone/messages/gateway/WebhookEngine.kt
  - id: openwiki-source-f47a2668cd817415f8991735
    resource: repo://app/src/main/java/com/autonomousone/messages/receiver/BootGatewayReceiver.kt
  - id: openwiki-source-118a7a1d805522e96275e615
    resource: repo://app/src/main/java/com/autonomousone/messages/viewmodel/GatewayViewModel.kt
generated: { by: "openwiki/0.4.3", at: "2026-08-30T16:24:36.837Z" }
---

# Gateway Startup, Reboot Recovery, and Self-Heal

The SMS gateway is an Android foreground service (`GatewayService`) that exposes a local REST API, heartbeats an optional cloud backend, and runs the outbound-only GMweb pull bridge. Its behavior over time is driven by exactly one component — `ConnectionSupervisor` — which treats the whole gateway as a declarative target and **reconciles** the live components toward it. This page walks that flow from a human action to a running, self-healing state, then through each failure mode and how the supervisor repairs it. The static component reference (what each class is and does) lives on [Gateway service](/openwiki/architecture/gateway-service.md); the cloud and GMweb transports are detailed on [Cloud Relay Backend](/openwiki/integrations/cloud-relay.md) and [GMweb pull bridge](/openwiki/integrations/gmweb-pull.md), and the operator-facing setup flow on [On-Device Operations](/openwiki/operations/device-operations.md).

The whole design rests on one idea, and understanding it makes the rest of the lifecycle obvious: the gateway's truth is a function of its inputs, recomputed on demand, never accumulated by a pile of one-shot event handlers.

```
state = f(desiredEnabled, hasConsent, online, serverIsUp, boundIp == nowIp)
```

Any input change — the user flips the toggle, consent is granted or revoked, a network callback fires, the LAN IP changes — merely *nudges* the supervisor; the loop then re-derives the entire truth and acts. There is no per-component "retry" state machine, and no event can be missed mid-action because the nudge channel is conflated (the next pass always sees the newest facts).

## The three flags: what makes recovery possible

Before the flow, the state model. The supervisor splits the gateway's "on-ness" into three flags with strictly different owners, and this split is what makes every recovery path in this page possible:

- **`gatewayDesiredEnabled`** (SharedPreferences key `gateway_desired_enabled`) — the *user's intent* that the gateway should run. It is persisted, survives reboot and process death, and is **never** touched by runtime teardown (network loss, a stopped server, a dead service). Only `ConnectionSupervisor.start()`/`stop()` write it. This is the value boot recovery replays.
- **`isEnabled`** (key `gateway_enabled`) — the *runtime mirror*: true while the components are actually transmitting. It is derived by the supervisor and written by the supervisor **only**: set `false` *before* stopping components on the disabled/offline paths, and set `true` only at the very end of a fully successful reconcile. The components themselves never write it.
- **`hasGatewayConsent`** — versioned privacy consent (`storedVersion >= CURRENT_CONSENT_VERSION`, currently `1`), so a material data-use change can force a fresh opt-in.

Every transmission boundary gates on the first two through `GatewayAccessPolicy.canTransmit(hasConsent, isEnabled)` — checked by `HeartbeatManager`, `OutboxPoller`, and `WebhookEngine`. So a supervisor-stopped gateway stops *sending* even if its service process is still alive, and every entry point gates on consent through `GatewayAccessPolicy.canStart(consent)`. Because the runtime mirror is always re-derived and the user intent always survives, the gateway can be torn down at any moment and re-derive itself from `gatewayDesiredEnabled` alone.

## Entry points: who starts the gateway

`GatewayService` is `android:exported="false"` with `foregroundServiceType="dataSync|specialUse"`, and it understands three intent actions (`ACTION_START`, `ACTION_STOP`, `ACTION_RETRY_NOW`) plus a `null` intent. Everything that turns the gateway on funnels into `ConnectionSupervisor.start()`:

| Entry point | Path | Result code |
|---|---|---|
| **User toggle** | `GatewayViewModel.toggleServer(true)` → `GatewayService.startGateway(context)` → `startForegroundService(ACTION_START)` → `onStartCommand` → `supervisor.start()` | `START_STICKY` |
| **Boot recovery** | `BootGatewayReceiver.onReceive` → `GatewayService.startGateway(context)` → same as above | `START_STICKY` |
| **Process death (START_STICKY)** | system restarts the service with a **`null` intent** → falls into the `ACTION_START, null` branch → `supervisor.start()` | `START_STICKY` |
| **Doze watchdog** | `onDestroy` → `scheduleRestartWatchdog()` → `AlarmManager` fires `ACTION_START` after 15 s | `START_STICKY` |
| **Manual self-heal** | `GatewayViewModel.reconnectNow()` → `GatewayService.reconnectNow(context)` → live `supervisor.retryNow()`, else `startForegroundService(ACTION_RETRY_NOW)` | `START_STICKY` |

`startGateway(context)` is the single choke point for the "on" actions: it re-checks `GatewayAccessPolicy.canStart(consent)` before `startForegroundService`, so a phone whose consent was revoked silently drops the start. `onStartCommand` re-checks consent on the `ACTION_START, null` branch too and calls `stopSelf()` if it is missing. Every successful start returns `START_STICKY`; only the `ACTION_STOP` path and a consent-blocked start return `START_NOT_STICKY`.

## The reconcile loop

`ConnectionSupervisor` is a process-wide singleton (`get(...)`/`peek()`). Its `ensureLoop()` (idempotent) launches the loop into the service scope, and that loop owns three sub-coroutines:

1. a collector on `networkMonitor.onlineFlow()` — when the network flips **online** and the gateway is desired, it calls `retryNow()` (the immediate-retry path, below); any other transition calls `reconcileNow()`. `NetworkMonitor` is the single source of truth for "validated internet": it requires `NET_CAPABILITY_VALIDATED`, not just an up interface, so a captive-portal or airplaned network is correctly *offline*.
2. a 10-second timer that nudges `reconcileNow()`. This is what a LAN IPv4 change looks like locally: the loop re-derives and the `boundIp != currentIp` comparison inside `reconcile()` *is* the DHCP-rebind detection.
3. the main `for (nudge in reconciles)` loop that calls `reconcile()`. On any exception it records the error, sets `ERROR`, and applies its **own** exponential backoff (5 s doubling, capped at 300 s) — "the loop is the backoff," which is what allows `GatewayServer.start()` to stay non-blocking and non-throwing.

`reconcile()` itself is a short decision tree. The flowchart below is grounded directly in `ConnectionSupervisor.reconcile()`:

<!-- openwiki: mermaid parse failed and this diagram was converted to a text fence so it does not break rendering. Fix the diagram source and restore the mermaid fence. Parser error: Heuristic: a semicolon inside a label breaks rendering; rephrase the label. -->
```text
flowchart TD
    N["reconcileNow() nudge"] --> G1{"desiredEnabled and hasConsent?"}
    G1 -- "no" --> DISABLE["set isEnabled=false first, then stop poller, heartbeat, server"] --> S_DIS["State.DISABLED"]
    G1 -- "yes" --> G2{"networkMonitor.isOnline()?"}
    G2 -- "no" --> WAIT["set isEnabled=false, stop poller and heartbeat; LAN server stays up"] --> S_WAIT["State.WAITING_FOR_NETWORK"]
    G2 -- "yes" --> DEG{"state was RECONNECTING, ERROR or WAITING_FOR_NETWORK?"}
    DEG -- "yes" --> S_REC["State.RECONNECTING"]
    DEG -- "no" --> BIND
    S_REC --> BIND{"server not running, or boundIp differs from current LAN IP?"}
    BIND -- "yes" --> RBL["stop old server, build fresh via newServer, start; throw if not running"]
    RBL --> COMP
    BIND -- "no" --> COMP["startHeartbeat, startPoller if gmwebUrl set, startSync"]
    COMP --> OK["reset backoff to 5s, set isEnabled=true"] --> S_CON["State.CONNECTED"]
    RBL -. "threw (e.g. port taken)" .-> ERR["catch: set State.ERROR and log"] --> BO["delay backoffMs, then double backoffMs up to 300s"]
    BO -. "next nudge" .-> N
```

*Caption: the `ConnectionSupervisor.reconcile()` decision — disabled/no-consent, offline, and the online rebind-then-start path, with the bind-failure backoff handled on the loop itself.*

Three behaviors in this tree are worth calling out because they are counter-intuitive:

- **Offline is not an error.** The offline branch stops the poller and heartbeat (so the gateway makes **zero** HTTP requests against a dead radio) and sets `WAITING_FOR_NETWORK`. It deliberately does *not* stop the LAN server — local clients do not need the internet — and it does *not* report `ERROR`, because a lost network is an expected condition, not a fault.
- **A fresh start skips `CONNECTING`.** The `CONNECTING` state is declared in the enum and consumed by `GatewayService.isServiceRunning`, the notification, and the UI, but `reconcile()` never emits it: a brand-new start goes straight `DISABLED → CONNECTED`. `RECONNECTING` is the state used whenever the gateway was already known-degraded (`RECONNECTING`/`ERROR`/`WAITING_FOR_NETWORK`) before the pass.
- **`isEnabled` flips last on the happy path.** It is set `true` only after the server is up and all components have been (re)started, and set `false` *before* teardown on the other paths — ordering that guarantees components stop transmitting before they are stopped, and that no component ever transmits while the runtime flag is off.

## The happy path: toggle to CONNECTED

Putting the pieces together, turning the gateway on for the first time is:

1. **Consent + toggle.** `GatewayViewModel.toggleServer(true)` sees no consent and shows the consent dialog instead of starting. Accepting it calls `acceptGatewayConsent()` (records the version + timestamp), then `startGateway(context)`.
2. **Supervisor start.** `onStartCommand` posts the foreground notification and calls `supervisor.start()`, which sets `desiredEnabled = true`, persists `gatewayDesiredEnabled = true`, ensures the loop is running, and nudges a reconcile. Nothing is blocking — `start()` returns immediately and the loop does the work.
3. **Server bind.** `reconcile()` sees desired + consent + online. If the LAN server is down it stops any old instance, builds a **fresh** `GatewayServer` via the `newServer` factory, and calls `start()`. If the fresh instance is not `isRunning()` (e.g. the port is taken), `reconcile()` throws `IllegalStateException("bind failed on port …")`.
4. **Components up.** With the server bound, `reconcile()` idempotently starts the heartbeat, starts the outbox poller (only when `gmwebUrl` is non-blank), and starts shadow sync (below).
5. **CONNECTED.** It resets its backoff to 5 s, sets `prefs.isEnabled = true`, and publishes `State.CONNECTED`. `GatewayService` collects the state and updates the persistent notification to "SMS Gateway Active".

If step 3 threw, the loop instead lands in `ERROR`, backs off, and retries the same pass — the gateway keeps self-healing until the port frees, and the UI shows the `ERROR`/"Retrying" state rather than failing silently.

## Failure cases and how the supervisor heals them

### Network loss → `WAITING_FOR_NETWORK`, then immediate retry on return

When `onlineFlow()` flips offline, `reconcile()` stops the poller and heartbeat and parks in `WAITING_FOR_NETWORK`. When the network flips **online** again, the supervisor's collector calls `retryNow()` (not a plain reconcile), which is the crux of the fix: it guards on desired + consent + online, resets the loop backoff to 5 s, calls `ensureLoop()` (reviving the loop if it died), and calls `components.retryHeartbeat()`.

`HeartbeatManager.retryNow()` matters more than a plain `start()`: it resets the backoff ladder to its 1 s base **and** pokes the heartbeat's conflated *wake* channel to cancel whatever backoff sleep (up to 5 minutes) it is sitting on. A plain `start()` would no-op while the job is still alive and silently keep the old ladder in force. The net effect is that a 5-second WiFi re-association retries **immediately** instead of waiting out a 5-minute ladder. `OutboxPoller` does the same thing on its own: while offline it suspends on `onlineFlow().first { online -> online }` and resumes the instant the network is validated, rather than burning 40 s long-poll timeouts against a dead radio.

### DHCP rebind → server *instance replacement*

When the phone moves networks and gets a new LAN IPv4, the old server is still bound to the stale address and is unreachable. The 10-second nudge re-runs `reconcile()`, which detects the mismatch: the rebind condition is *server not running, or* (bound address is not `0.0.0.0`, current IP is not loopback, and `boundIp != currentIp`). When it fires, the supervisor logs "LAN address changed (old → new) — rebinding server" and **replaces** the server.

This is replacement, not restart, by design: `GatewayServer.stop()` closes the socket and `shutdownNow()`s both the single-thread acceptor and the 8-thread handler pool, so a stopped instance cannot be started again. `reconcile()` therefore always builds a **fresh** `GatewayServer` from the `newServer` factory whenever the server is down or stale. Binding to `0.0.0.0` (the `bindAllInterfaces` option) exempts the address comparison, which is why that mode never rebinds.

### Component death → idempotent restart

Because `reconcile()` re-runs on a 10-second cadence (and on every network transition) and every action is idempotent, a component that dies is simply restored on the next pass. `HeartbeatManager.start()` returns immediately if its job is still active but relaunches if it is not; `OutboxPoller.start()` is guarded by its `running` flag; and the server check is against `isRunning()`, so a socket that closed on its own is rebound. There is no separate "watch the components and restart them" machinery — the reconcile loop *is* that machinery, and idempotence is what makes re-running it safe.

### Bind failure → backoff on the loop

A failed bind (most commonly the port is already taken) is not retried inside `GatewayServer` — `start()` is non-throwing and just logs. `reconcile()` is the one that throws when the fresh server is not running, and the loop's `catch` turns that into `State.ERROR`, waits `backoffMs` (starting at 5 s), and doubles it up to a 300 s cap before the next pass. `retryNow()` resets this ladder, and so does any successful reconcile (it restores `backoffMs` to 5 s), so the moment the port frees or the user hits reconnect the gateway recovers quickly. The loop being the backoff is precisely what lets `GatewayServer.start()` remain simple and non-blocking.

### Manual self-heal → `ACTION_RETRY_NOW`

`GatewayViewModel.reconnectNow()` is the transport-agnostic "Reconnect now" button. It calls `GatewayService.reconnectNow(context)`, which prefers the **live** supervisor — `ConnectionSupervisor.peek()?.retryNow()` — and only falls back to `startForegroundService(ACTION_RETRY_NOW)` when the service (and thus the singleton) is gone; that start path then rebuilds the supervisor in `onCreate` and `onStartCommand` calls `supervisor.retryNow()`. Because `reconnectNow()` routes through `retryNow()` for every mode, a pure android-pull (GMweb) gateway heals through exactly the same door as a cloud one — the ViewModel's separate cloud `register()` call is a fast path only for phones that actually configure a cloud backend, so a GMweb-only device never reports a spurious "Reconnect failed".

## Reboot and process-death recovery

Three complementary paths make "it was on, now it's on again" automatic — all of them rely on the fact that `gatewayDesiredEnabled` survives while runtime state is re-derived:

1. **Reboot.** `BootGatewayReceiver` is declared in the manifest for both `BOOT_COMPLETED` and `LOCKED_BOOT_COMPLETED`. It proceeds only when `gatewayDesiredEnabled && hasGatewayConsent`, then calls `GatewayService.startGateway(context)` — replaying `ACTION_START` so the supervisor reconciles from scratch with no manual step. Note the asymmetry: although the manifest filters on both boot actions, the receiver's own guard currently lets **only** `ACTION_BOOT_COMPLETED` through (`if (intent.action != Intent.ACTION_BOOT_COMPLETED) return`), so on most devices recovery is effectively driven by `BOOT_COMPLETED`. Consent is re-checked inside `startGateway`, so a revoked consent means the start is silently dropped.
2. **Process death.** `GatewayService` returns `START_STICKY` for the on-actions, so after the process is killed the system restarts it with a **`null` intent**. That lands in the `ACTION_START, null` branch, where `supervisor.start()` re-reads `gatewayDesiredEnabled` from prefs and reconciles from scratch — no intent payload needed.
3. **Delayed revival under Doze.** `START_STICKY` revival can be slow or suppressed while the device is in Doze, and while the service is dead the GMweb pull bridge is dark (sends fail with `503 android_gateway_unreachable`). To close that gap, `onDestroy` calls `scheduleRestartWatchdog()`: if consent is present **and** the runtime `isEnabled` is still true (i.e. the death was not user-initiated — `ACTION_STOP` already cleared it), it arms an `AlarmManager` alarm (`setExactAndAllowWhileIdle`, with an inexact `setAndAllowWhileIdle` fallback when exact alarms are disallowed) that fires `ACTION_START` after 15 s, even from Doze. The watchdog is never explicitly cancelled; the redundant `ACTION_START` it may deliver is harmless because `supervisor.start()` is idempotent.

### The shadow-sync nudge

Once the gateway is reconciled online, `reconcile()` also calls the shadow-sync component: `TelephonySyncCoordinator.get(context).ensureLoopRunning()`. This is wired into the supervisor's `ManagedComponents.startSync` and is distinct from the other starts — `ensureLoopRunning()` starts the coordinator's mutation/reconcile coroutines **without forcing a full reconcile**, so it is cheap and idempotent to call on every pass. It is the point at which the Room shadow-sync loop is guaranteed to be running as a consequence of the gateway coming online.

## Invariants to preserve when changing the flow

- **One writer for `isEnabled`.** Only `ConnectionSupervisor` sets the runtime mirror; components gate on it but never write it. Adding any other writer reintroduces the stale-flag problem the supervisor exists to remove.
- **`gatewayDesiredEnabled` is never cleared by teardown.** Any code path that resets user intent on a transient failure (network loss, bind failure, process death) silently breaks reboot and Doze recovery.
- **Offline ≠ error.** Keep the offline path out of `State.ERROR` and keep the LAN server up while offline; the local API must keep working without the internet.
- **Rebind by replacement.** Do not try to "restart" a stopped `GatewayServer` — its executors are `shutdownNow()`'d. Always go through the `newServer` factory.
- **Conflated nudges, idempotent actions.** The correctness of the loop depends on `reconciles` being conflated and every `reconcile()` action being safe to re-run; making an action non-idempotent or making the channel buffered/dropping breaks the "no missed event" guarantee.
