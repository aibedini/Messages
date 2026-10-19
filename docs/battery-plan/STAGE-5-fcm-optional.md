# STAGE 5 — `FcmWakeSource` — BLOCKED: conflicts with ADR-003

**Read [`README.md`](README.md) first.**

**Branch:** `perf/battery-stage-5`
**Status:** **DO NOT IMPLEMENT** in its current state. See §0.

---

## 0. STOP — the blocker, stated plainly

[ADR-003](../adr/ADR-003-android-availability-doze-slo.md), status **Accepted**,
dated 2026-08-31, says:

> Without FCM (**a hard project rule — zero Google infrastructure dependency**)
> a hard sub-3-second guarantee for ALL device states is not honest…

FCM is not merely unused in this project. It is **excluded by an accepted
architecture decision**, and that exclusion is the stated premise of the entire
availability SLO in that ADR. The project owner has independently confirmed the
same constraint.

Therefore:

> **An implementing agent must not write FCM code from this document.**
> If you were handed this file as a task, your correct action is to report it
> as not done with blocker type `ADR-conflict`, quoting this section.

The only route to implementation is:

1. The project owner decides the trade-off is now worth it.
2. A new ADR — `ADR-009-fcm-optional-wake-transport.md` — **supersedes** the
   relevant part of ADR-003, stating what changed and why, and what the SLO now
   claims in each device state.
3. ADR-003 is edited to `Status: Partially superseded by ADR-009`, per the
   ADR hygiene the `docs/adr/` directory already follows.
4. Only then does this stage become implementable, and its first task is to
   update this section.

Everything below §0 exists so that **if** that decision is ever made, the work
is already specified and the owner knows exactly what it costs. It is a
contingency, not a backlog item.

---

## 1. The factual question the owner asked: does FCM require Google Play?

Two different things are routinely confused. The answer matters for this
project, so it is recorded here.

| Requirement | Needed? | Detail |
|---|---|---|
| Publishing the app on the Google Play **Store** | **No** | An APK distributed from GitHub Releases — which is how this project ships — can use FCM normally. There is no Play Store listing requirement. |
| A Firebase **project** + `google-services.json` in the app | **Yes** | Free tier is sufficient for wake pings. Console steps: [`MANUAL-STEPS.md`](MANUAL-STEPS.md) §4. |
| Google Play **Services** installed on the end user's device | **Yes** | This is the real constraint. No GMS → no FCM. De-Googled ROMs, and devices on networks that cannot reach Google's servers, are excluded. |
| The Google Play Console at all | **No** | Firebase Console only. |

So the obstacle for this project is **not** a store listing. It is (a) the
accepted ADR, and (b) the GMS-on-device requirement, which in the owner's
deployment context is not reliably satisfiable. Both point the same way: Stage 4
is the better answer here, and Stage 1 is the better answer *right now*.

---

## 2. If ADR-009 is ever accepted — specification

### 2.1 Product flavours, so the FOSS build stays Google-free

This is non-negotiable if the project keeps any part of its self-hosted
promise: the default artefact must contain **zero** Google code.

```kotlin
// app/build.gradle.kts, inside android { }
flavorDimensions += "distribution"
productFlavors {
    create("foss") {
        dimension = "distribution"
        // No Firebase dependency, no google-services plugin, no Google code.
        buildConfigField("boolean", "FCM_AVAILABLE", "false")
    }
    create("gms") {
        dimension = "distribution"
        buildConfigField("boolean", "FCM_AVAILABLE", "true")
    }
}
```

- the `com.google.gms.google-services` plugin and the
  `firebase-messaging` dependency are applied to the **`gms` flavour only**
  (`gmsImplementation(...)`);
- `google-services.json` lives at `app/src/gms/google-services.json`;
- **`foss` is the default and the one published to GitHub Releases**, unless the
  owner decides otherwise in writing;
- `FcmWakeSource` lives in `app/src/gms/java/...`; the `foss` source set gets a
  no-op stub so `WakeSourceSelector` compiles unchanged in both flavours;
- CI: `.github/workflows/build-debug.yml` and `release.yml` currently call
  `assembleDebug` / `assembleRelease` with no flavour. Those become
  `assembleFossDebug` etc. **Updating both workflows is part of this stage** —
  a flavour split that silently stops building one variant is worse than no
  split.

### 2.2 The payload rule — identical to Stage 4

Server → device, **data-only**, high priority, and **empty of meaning**:

```json
{ "data": { "t": "wake" }, "android": { "priority": "high" } }
```

- **No `notification` block.** A `notification` payload is rendered by the
  system and does not reach the app's handler when backgrounded, which is not
  what is wanted.
- **No content, ever.** No recipient, no body, no ids, no counts. Google's
  infrastructure must see a meaningless ping and nothing more. This is both the
  privacy argument and the thing that keeps the security model from §2 of
  Stage 4 unchanged.
- On receipt, the service does exactly one thing: `onWake()` →
  `OutboxPoller.retryNow()`. Same single line as Stage 4.
- High priority is what permits a Doze wake-up; it is also rate-limited by
  Google and can be throttled for apps that overuse it. Another reason the
  payload must be a genuine work signal, not a heartbeat.

### 2.3 Token lifecycle

- `FirebaseMessaging.getInstance().token` on first enable, and
  `onNewToken` for rotation;
- register the token with GMweb at
  `POST /api/v1/agent/push-token`, authenticated with the **existing**
  `X-Agent-Auth` / `X-Agent-TS` signature — no new credential type (contract in
  [`MANUAL-STEPS.md`](MANUAL-STEPS.md) §2.3);
- **the token is a device identifier.** It must never appear in
  `DiagnosticLog`, in a diagnostics export, or in any health counter. Tokenise
  or omit, consistent with the `PhoneToken.of(...)` rule the codebase already
  applies to recipients;
- on gateway disable or consent revocation, delete the token server-side **and**
  call `FirebaseMessaging.deleteToken()`. A revoked device that still receives
  pings is the consent bug `SecureCommandPoller`'s KDoc already describes having
  been fixed once (`docs/gateway-replication-audit.md`, Blocker 16). Do not
  reintroduce it in a new channel.

### 2.4 Selector integration

`WakeSourceSelector` (Stage 2) already has the ladder. This stage only flips the
capability input:

```kotlin
fcmAvailable = BuildConfig.FCM_AVAILABLE &&
               GoogleApiAvailability.getInstance()
                   .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS &&
               prefs.pushTokenRegistered
```

AUTO then resolves FCM → SOCKET → POLLING. A device without GMS lands on SOCKET
or POLLING with no user action and no error, which is the whole point of having
built the seam first.

### 2.5 Consent and disclosure

The app has a gateway consent flow (`hasGatewayConsent`,
`GatewayAccessPolicy.canTransmit`) and a published
[`PRIVACY.md`](../../PRIVACY.md). Enabling FCM means a third party (Google)
observes connection metadata for this device.

- it must be **opt-in**, never the AUTO default on a `foss` install;
- `PRIVACY.md` must be updated **in the same PR** to say that in the `gms`
  flavour, with push enabled, Google receives delivery metadata (not content);
- the settings UI states it plainly in the mode picker, not in a help page.

---

## 3. Tests, if implemented

| Test | Asserts |
|---|---|
| `FossFlavourHasNoGoogleCodeTest` | no `com.google.*` class is reachable in the `foss` variant |
| `FcmPayloadIsContentFreeTest` | the handler reads only `t`, and ignores any other field even when present |
| `FcmWakeTriggersRetryNowTest` | a wake message results in exactly one `retryNow()` |
| `FcmTokenNeverLoggedTest` | the token does not appear in `DiagnosticLog` output or a diagnostics export |
| `FcmTokenDeletedOnConsentRevokeTest` | revoking consent deletes the token locally and calls the server |
| `SelectorFallsBackWithoutPlayServicesTest` | `fcmAvailable = false` → SOCKET or POLLING, no error state |

---

## 4. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| Shipping this at all violates an accepted ADR | **blocking** | §0 |
| `foss` build acquires a Google dependency transitively | high | `FossFlavourHasNoGoogleCodeTest`; `gmsImplementation` only |
| CI stops building a variant after the flavour split | high | update both workflows in the same PR |
| Push token leaks into a diagnostics export | high | dedicated test; treat as an identifier |
| Revoked device keeps receiving pings | high | token deletion on revoke, with a test |
| High-priority FCM throttled by Google | medium | wake pings only on real work; never a heartbeat |
| Users read "FCM support" as "Google required" | low | mode picker wording; `foss` stays default |

---

## 5. Acceptance checklist

- [ ] **ADR-009 written and accepted, ADR-003 marked partially superseded** —
      nothing below may start before this
- [ ] `foss` / `gms` flavours; `foss` default and provably Google-free
- [ ] Both CI workflows updated for the flavour split
- [ ] Data-only, content-free payload; no `notification` block
- [ ] Token registered over the existing signed agent auth; never logged
- [ ] Token deleted on consent revoke, server and client
- [ ] Opt-in only; `PRIVACY.md` updated in the same PR
- [ ] Selector falls back cleanly with no Play Services
- [ ] Gate passes for **both** flavours
- [ ] Report appended per [`README.md`](README.md) §5

---

<!-- The implementing agent appends the report from README.md §5 below.
     If this stage was handed to you while §0 still stands, the report is a
     single section B row: blocker type ADR-conflict. -->
