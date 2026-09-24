# Production Validation & Remaining Android Gaps — plan, baseline and evidence log

This document replaces the architectural phase planning of
[`gateway-replication-audit.md`](gateway-replication-audit.md) for the **second** goal
(*Production Validation & Remaining Android Gaps*).

The architecture goal ran its full 40-round budget. Its output — the replication audit, the phase
implementations, the real-SQL tests and the falsified source guards — stands and is **not** being
revisited. This goal exists because passing JVM tests is not the same claim as surviving production
scale and Android lifecycle failures, and because a small number of Android-side gaps were left open
and named rather than quietly closed.

**The single honest summary of where this project is:** the failure *paths* that can be exercised
off-device are now genuinely tested; the behaviour that depends on a real Android runtime has never
been executed anywhere.

---

## 1. Rule 1 baseline (recorded before any change)

```text
BASELINE_SHA        = c5d43e53374ee6844b2f683bb14e5a898ad4e34e
BRANCH              = master
TAG_AT_HEAD         = v3.4.8
HEAD_COMMIT_DATE    = 2026-09-22 13:46:19 +0330
HEAD_COMMIT_SUBJECT = fix(gateway): stop reporting historical dead letters as a live sync failure
WORKTREE            = 43 tracked-modified, 0 staged, 43 untracked (87 entries)
CURRENT_TEST_COUNT  = 1919 JVM tests, 0 failures, 176 suites
```

No commit, push, merge, rebase, reset or discard has been performed, and none will be without an
explicit instruction. The worktree is the deliverable; the baseline SHA is the last released state
(`v3.4.8`), so **every change below is uncommitted work stacked on that release.**

### Current known unresolved items (the goal's backlog)

| # | Item | Class |
|---|------|-------|
| 1 | §69 / §71 real device or emulator validation never performed | `PHYSICAL DEVICE REQUIRED` |
| 2 | Large-history behaviour at 1k / 10k / 100k / ~360k never demonstrated | `PHYSICAL DEVICE REQUIRED` at scale |
| 3 | Screen-off, process death, reboot, upgrade unverified on real Android | `PHYSICAL DEVICE REQUIRED` |
| 4 | MMS attachments not replicated as full assets | Android prerequisites + **GMweb contract** |
| 5 | MMS completion needs GMweb protocol work — must not be invented Android-only | cross-repository |
| 6 | Worker vs Foreground Service for huge backfill: documented, unresolved | Android code |
| 7 | `SendState` / `aggregateSendState` unused / unwired | **RESOLVED — removed (see WS-H)** |
| 8 | Nothing committed | process |

---

## 2. Environment reality (measured, not assumed)

These are command outputs, not inferences:

```text
adb devices -l                    → "List of devices attached" (empty) — no physical device
avdmanager list avd                → "Available Android Virtual Devices:" (empty) — no AVD
<SDK>/emulator/emulator.exe        → does not exist
<SDK>/system-images                → does not exist
<SDK>/platform-tools/adb.exe       → exists
<SDK>/cmdline-tools/latest         → exists (sdkmanager + avdmanager usable)
<SDK>/platforms                    → android-36 (and an android-36.incomplete from an earlier run)
sdk.dir (local.properties)         → C:/Users/Mahna/tools/android-sdk
sdkmanager --list                  → SDK repository REACHABLE; emulator 37.1.11 and
                                     system-images;android-36;google_apis;x86_64 are available
Free disk on C:                    → 8.3 GB
Win32_ComputerSystem.HypervisorPresent → True
compileSdk / minSdk / targetSdk    → 36 / 26 / 36   (versionCode 115, versionName 3.4.8)
```

**Consequences, stated plainly:**

- Every scenario in this goal is currently **`PHYSICAL DEVICE REQUIRED`**. Nothing in section 1's
  items 1–3 can be closed by JVM tests, and no JVM result will be reported as if it closed them.
- An emulator is **obtainable** (repo reachable, ~2.5 GB for emulator + API-36 x86_64 image) but
  **not installed**, and only **8.3 GB** is free — with a 360k-message Room fixture also needing
  space. Whether to spend that disk is a user decision, recorded in section 6.
- `HypervisorPresent = True` means WHPX may be available to the emulator; this is a *precondition*,
  not a guarantee, and an emulator that boots is still **`EMULATOR VERIFIED`**, never
  `PHYSICAL DEVICE VERIFIED`.

---

## 3. Verification classification (used in every report from here on)

| Class | Meaning |
|-------|---------|
| `AUTOMATED VERIFIED` | Proven by a JVM test, real-SQL test, source guard or build/lint result in this repo. No Android runtime involved. |
| `EMULATOR VERIFIED` | Executed on an Android emulator. Proves runtime behaviour and lifecycle handling; does **not** prove real-modem, real-Provider, real-device-storage or OEM-battery behaviour. |
| `PHYSICAL DEVICE REQUIRED` | Cannot be established by anything available here: real SMS/MMS modalities, real Telephony Provider contents at scale, real thermal/battery/doze behaviour, OEM process-kill policy, carrier multi-SIM behaviour. |

A result is labelled with the **weakest** class that actually covers it. A repository-layer
simulation is never labelled device validation.

---

## 4. Workstreams

Status legend: `NOT STARTED` · `IN PROGRESS` · `BLOCKED (reason)` · `DONE (class)`.

| WS | Scope | Status |
|----|-------|--------|
| A | Device-test readiness; harness at 1k/10k/100k/360k; measurement matrix | IN PROGRESS — inventory done, 12 instrumented tests already exist |
| B | Reproducible deterministic large-history fixture at all four scales | NOT STARTED |
| C | Worker vs FGS backfill architecture — decide **and implement** | NOT STARTED |
| D | Realtime latency during 360k-equivalent backfill; no permanent starvation | NOT STARTED |
| E | Process-death windows 1–5, incl. `SEND_SMS` idempotency honesty | NOT STARTED |
| F | Reboot path audit: BootReceiver → … → command drain | NOT STARTED |
| G | Upgrade/migration from the released production schema | NOT STARTED |
| H | `SendState` / `aggregateSendState`: wire or remove | DONE (`AUTOMATED VERIFIED`) — **removed**, see H below |
| I | MMS attachment gap: Android status + GMweb handoff contract | NOT STARTED |
| J | Rescue-budget invariant, generalised guard (done in round 40) — keep and extend | DONE (`AUTOMATED VERIFIED`) |
| K | Silent/partial state transitions in the replication path | NOT STARTED |
| L | End-to-end accounting invariants incl. `CAUGHT_UP` requiring Pending = 0 | NOT STARTED |

### Existing device-test infrastructure (Workstream A inventory)

Already present in `app/src/androidTest` (12 files) — this goal extends these rather than starting
over: `MessageSyncE2EDeviceTest`, `GatewayDurabilityDeviceTest`, `HistoryAckCheckpointDeviceTest`,
`EncryptedHistoryDeviceTest`, `ConversationKeyMigrationTest`, `TrustPublicationDurabilityTest`,
`KeyGrantsOnApprovalDeviceTest`, `PairingProtocolRuntimeTest`,
`ExampleInstrumentedTest`, and three Compose UI tests. `testInstrumentationRunner` is
`androidx.test.runner.AndroidJUnitRunner`; `androidTest` already has the `protocol` and `schemas`
directories as assets; `unitTests.isReturnDefaultValues = true` is what keeps JVM tests cheap.

---

## 5. Required test matrix (every row must end with a class, never with silence)

1k history · 10k history · 100k history · 360k history · realtime during backfill · process kill ·
outbox lease recovery · history resume · server timeout · server 500 · server accepted / response
lost · duplicate ACK · device reboot scheduling · database upgrade · dead-letter rescue ·
auth blocked · key blocked · `SEND_SMS` duplicate command · multi-SIM unavailable · MMS text-only ·
MMS attachment gap.

Rows already covered by the architecture goal's JVM work keep their existing evidence; this goal
adds the scale, lifecycle and remaining-gap rows and must not restate the old ones as new.

---

## 6. Open decisions

### D1 — Emulator acquisition (needs the user)

The SDK repository is reachable, so an emulator can be installed: `emulator` (~0.4 GB) plus
`system-images;android-36;google_apis;x86_64` (~1.5–2 GB), then an AVD and its userdata on first
boot. This is the **only** route from `PHYSICAL DEVICE REQUIRED` to `EMULATOR VERIFIED`, and
`EMULATOR VERIFIED` is the strongest class reachable in this environment.

Against it: only **8.3 GB** is free on `C:`, the 360k fixture also needs space, and a boot may fail
or be unusably slow even where WHPX is present.

**Decision (user, this session): do NOT install an emulator — complete all feasible code work and
report `ANDROID CODE COMPLETE / DEVICE ACCEPTANCE PENDING`.**

Consequences, so nothing is overstated later:

- Every runtime row stays `PHYSICAL DEVICE REQUIRED`. No emulator run exists to cite, and none will
  be claimed.
- Workstreams are pursued at full strength where the layer below the device can carry the proof:
  the repository/SQL layer with a real SQLite database, pure policy objects, Room's generated DAO,
  the manifest and WorkManager configuration, and static guards.
- The gap between "the largest layer we can execute" and "a real device" is documented **per
  workstream** rather than summarised away. A fixture that exercises 360k rows through paging,
  checkpointing and outbox pressure proves the *application's* arithmetic and durability; it proves
  nothing about real Telephony Provider I/O, modem behaviour, thermal throttling or OEM process
  death, and will be labelled exactly that way.

### D2 — Worker vs Foreground Service

To be decided from observed constraints in Workstream C, not theoretically: the history worker's
actual structure, the expected wall-clock for 360k messages, Android's background execution limits,
expedited-work limits, whether foreground worker support is already used, notification
requirements, process-death recovery and battery cost. The decision and its reasoning are recorded
here when made, and the implementation follows it.

### D3 — `SendState` / `aggregateSendState` — DECIDED: removed

**Decision: removed. Not wired.** Recorded here with the evidence, because the honest question was
never "is this type dead?" but "is there anything it should talk to?".

What was true (measured):

- `SmsStatusPolicy.nextStatus` **is** on the real path — `SmsStatusReceiver` calls it and writes the
  provider `status` column.
- `SmsStatusPolicy.aggregateSendState` and the `SendState` enum had **no production caller at all**;
  a unit test was the only reference. `SendState.advance` / `fromName` were likewise uncalled.
- **No consumer exists to wire them to.** A scan of `app/src/main/.../ui` for `SEND_UNCONFIRMED`,
  `SENT_CONFIRMED` and `DISPATCHED` returns nothing; nothing persisted a `SendState`; and the GMweb
  ACK contract (`sent | failed | superseded`) cannot carry the distinction without a
  cross-repository protocol change, which is not an Android-only edit.
- The **durable evidence the KDoc claimed to provide already exists**: `send_segments.callbackState`
  (persisted by name, survives process death/reboot) is what `SmsStatusReceiver` reads to compute
  the counts both derivations took.
- The KDoc claimed the type existed "so the provider status, the durable state machine and the UI
  overlay can never disagree about what the device actually knows" — a guarantee about a state
  machine and a UI overlay that **do not exist**. That false guarantee is its own defect: it is the
  same class of prose-that-lies found repeatedly in the previous goal.

So the real defect was not dead weight but a **second derivation from the same callback evidence**,
where only one copy is on the real path: change one and the other silently disagrees. Wiring was
rejected because it would have required inventing a consumer, and persisting a value nothing reads
recreates exactly the unwired abstraction one layer down.

What changed:

- Removed the `SendState` enum and `SmsStatusPolicy.aggregateSendState`; the two unit tests covering
  them were removed with a note explaining where they went and why. `SendState.kt` keeps its live
  members (`SentPartVerdict`, `SmsSendPolicy`, `SmsSendFailure` — all used by `SmsStatusReceiver`,
  `SmsSender` and `DelayedSendExecutor`) and now carries a header explaining the removed type.
- Added the guard `theCallbackEvidenceToStatusDerivationHasExactlyOneDefinition`: the derivation's
  own signature parameter `sentConfirmedParts: Int` may appear exactly once in production. **It
  failed before the fix** (`expected:<1> but was:<2>`, both in `SmsStatusPolicy.kt`), which is the
  failing-test-for-the-intended-reason step, and it was **falsified after** by planting a second
  derivation and confirming it failed again before being reverted.
- Corrected `docs/sms-delivery-architecture.md`, which described the removed state machine as
  implemented, including its "Known gaps" entry that named `SEND_UNCONFIRMED`.

Condition for reintroduction: a real consumer that must distinguish "the API call was accepted"
from "the radio confirmed it" — a UI affordance or a GMweb protocol field. Either would need its own
design, and the replication case needs agreement with the other repository first.

---

## 7. Progress log

### Round 1 (baseline)

- Rule 1 baseline recorded (section 1). No commit, push, merge, rebase or reset performed.
- Environment measured: no device, no AVD, no emulator binary, no system images, SDK repo reachable,
  8.3 GB free. Emulator acquisition offered and **declined by the user** (section 6, D1), so the end
  state of this goal will be `ANDROID CODE COMPLETE / DEVICE ACCEPTANCE PENDING`.
- Device-test inventory: 12 instrumented test files already exist, so Workstream A extends them
  rather than starting over.
- **WS-H closed** (section 6, D3): dead `SendState` / `aggregateSendState` removed, single-definition
  guard added, falsified, and two documents corrected that described the removed machine as
  implemented.
- Suite after this round: **1918 tests, 0 failures** (1919 at baseline: +1 guard, −2 tests for the
  removed API). `:app:lintDebug` 0 errors; `:app:assembleDebug` succeeds.
- Not yet done: everything in WS-A/B/C/D/E/F/G/I/J-extended/K/L.
