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
| A | Device-test readiness; harness at 1k/10k/100k/360k; measurement matrix | IN PROGRESS — inventory done; 12 instrumented tests exist; JVM scale harness now measures all four scales |
| B | Reproducible deterministic large-history fixture at all four scales | **DONE (`AUTOMATED VERIFIED`)** — 1k/10k/100k/360k measured, boundary documented |
| C | Worker vs FGS backfill architecture — decide **and implement** | **DONE** — Option A decided and implemented; both platform-compliance defects **FIXED** (boot C.6, revival watchdog + Android 15 timeout C.7) |
| D | Realtime latency during 360k-equivalent backfill; no permanent starvation | **DONE** — all four classes asserted; a dead `STATUS_UPDATE` priority class **FIXED** (D.1); reconciliation's rank measured and recorded (D.3) |
| E | Process-death windows 1–5, incl. `SEND_SMS` idempotency honesty | **DONE** — 1–4 `AUTOMATED VERIFIED`; 5 documented as inherently unclosable |
| F | Reboot path audit: BootReceiver → … → command drain | PARTIAL — the boot→`dataSync`-FGS defect is **FIXED** with tests; the rest of the chain is not yet audited |
| G | Upgrade/migration from the released production schema | **DONE (`AUTOMATED VERIFIED`)** — released v3.4.8 is schema **18**; the composed 18→24 path is now tested |
| H | `SendState` / `aggregateSendState`: wire or remove | DONE (`AUTOMATED VERIFIED`) — **removed**, see H below |
| I | MMS attachment gap: Android status + GMweb handoff contract | **AUDIT + SPEC DONE**; prerequisite 1 (visible gap in diagnostics) **IMPLEMENTED** (see §16); `sizeBytes`, the completeness rule and the content hash remain |
| J | Rescue-budget invariant, generalised guard (done in round 40) — keep and extend | DONE (`AUTOMATED VERIFIED`) |
| K | Silent/partial state transitions in the replication path | **DONE** — audit complete; one class verified safe (K.1), the terminal-transition defect found and **fixed** with a callee-enforced rule (K.3) |
| L | End-to-end accounting invariants incl. `CAUGHT_UP` requiring Pending = 0 | **DONE** — invariants verified (L.1), two defects fixed (L.3, L.4), and `CAUGHT_UP` now reachable with documented failures (L.5) |

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

## 7. Workstreams B + C — measured evidence and the runtime decision

### B.1 The production call chain (traced, not inferred)

```text
ConnectionSupervisor (inside GatewayService, an FGS)
  → TelephonySyncCoordinator.startGatewaySync() / ensureLoopRunning()
ChangeRouter (provider observer) → reconcile(ForThread | TailDelta)
HomeViewModel → reconcile(TailDelta)
        ↓
TelephonySyncCoordinator.reconcile(request) → pendingReconciles.add + nudge → loop
        ↓
runReconcile → applyReconcile → FullSync → backfillCloudHistory()      [line 1414 / 651]
        ↓
for (source in [sms, mms])  while (syncAllowed):
    ONE db.withTransaction {                       ← one page per transaction
        cursor   = cloud_history_checkpoint
        limit    = min(100, 2000 − pendingBackfillDepth())
        page     = messageDao.cloudHistoryPage(source, cursorDate, cursorId, limit)
        page.forEach { enqueueHistorical(it) }
        checkpoint.upsert(producerCursor*, sourceExhausted)   ← SAME transaction
    }
    if (count < limit) break                       ← source exhausted
    yield()                                        ← cancellation boundary
        ↓
enqueueHistorical → resolveHistoryOutcome
    identity = GatewayEventFactory.eventUuidFor(MESSAGE_CREATED, source, providerId, date)
    existing row → outbox.stampHistoryMetadata(...)  (write-once ordinal, O(1))
    else → enqueueCloudEvent → firewall.classify → ConversationKeyRepository.encrypt
           → outbox.insertOrIgnore
```

**Execution model: neither a Worker nor a one-shot scan-until-complete.** There is **no history
Worker at all**. The loop is an in-process coroutine loop owned by the coordinator, and its pacing
is the outbox: it stops when 2000 `BACKFILL` events are outstanding. It is already
*chunked* (100 rows/transaction) and already *resumable* (the checkpoint is committed inside the
same transaction as the events it describes).

### B.2 Measured results (real SQLite, shipped SQL, real event factory)

Deterministic fixture, fixed seed. `maxPageRows` is the largest page ever held live.

| scale | drain elapsed | pages | rows | maxPageRows | checkpoint writes | outbox rows | accounting |
|---|---|---|---|---|---|---|---|
| 1k | **9 ms** | 11 | 1 000 | 100 | 11 | 1 000 | 1000 = 1000 + 0 |
| 10k | **197 ms** | 102 | 10 000 | 100 | 102 | 10 000 | 10000 = 10000 + 0 |
| 100k | **1 910 ms** | 1 002 | 100 000 | 100 | 1 002 | 100 000 | 100000 = 100000 + 0 |
| 360k | **6 647 ms** | 3 602 | 360 000 | 100 | 3 602 | 360 000 | 360000 = 360000 + 0 |
| 10k, uploader NOT draining | 372 ms | 20 | 2 000 | 100 | 20 | 2 000 | stops at the cap (`capHits=1`) |

- **Throughput is flat**: ≈51k rows/s at 10k, ≈52k at 100k, ≈54k at 360k. Linear, not degrading.
- **Page count is explainable exactly**: `ceil(rows/100) + 2` (3602 = 3600 + one exhausted-page
  terminator per source). 1k = 11 because the fixture splits 962 SMS + 38 MMS.
- **`maxPageRows = 100` at every scale**, including 360k: memory is O(page), not O(history). Nothing
  on this path collects the whole history (`page.forEach` over a ≤100-row list; no cross-page
  `toList()`/`groupBy()`/`sortedBy()`).
- **Eligible = enqueued + skipped + failed = rows**, with no unexplained remainder at any scale.
- **The cap is the real pacing mechanism**: with no uploader draining, the scan stops dead at 2000
  outstanding backfill events (20 pages). 360k therefore needs ~180 such rounds, each released by
  upload ACKs.

### B.3 Realtime under history pressure

With a >1000-row `BACKFILL` backlog pending and 5 realtime events inserted behind it through the
real builder and insert: the shipped global ordering
(`ORDER BY CASE priority WHEN 'REALTIME' THEN 0 ELSE 1 END, id`) returned **all 5 realtime rows
first**, and the fair-batching reads still returned a non-empty background window containing only
`BACKFILL`/`RECONCILIATION`. So there is neither realtime starvation nor permanent history
starvation. Asserted by `HistoryBackfillBenchmarkTest`.

### B.4 What this measurement is NOT

`ConversationKeyRepository.encrypt` (Android Keystore AES-GCM) and the real Telephony Provider are
**not** on this path in the benchmark, and no Android runtime is involved. Every timing above
therefore **excludes encryption** and is a **lower bound** on device cost. It is not device
validation; `DEVICE_VALIDATION = PHYSICAL DEVICE REQUIRED` is unchanged.

### C.1 DECISION

```text
DECISION = Option A — bounded, checkpointed chunked units as the durability model
           (Option D's shape, but with the foreground component kept OUT of the durability story)
```

Concretely: history backfill stays a **bounded, resumable unit of work** (one page per transaction,
checkpoint committed atomically with the events, continuation driven by unique work), and
**correctness never depends on one process surviving the import**. The existing `GatewayService`
foreground service remains for what it is actually for — the user-visible realtime/control-plane
bridge — and is **not** the mechanism that makes history durable.

### C.2 WHY (measured, not aesthetic)

- 360k of local processing is **6.6 seconds**, not hours. Extrapolating generously for real
  encryption and Room overhead (say ×10) it is still ~1 minute of work. The hours-long duration of a
  real 360k import is **waiting for the network**, because the scan cannot run more than 2000 events
  ahead of ACKs.
- Therefore "keep one process alive for hours" buys nothing: there is no multi-hour local workload
  to protect. What must survive is *position*, and position is already durable per 100 rows.
- Resumability is already proven, not aspirational: the cap test shows the loop stops cleanly at a
  page boundary with the checkpoint committed, and the page count arithmetic shows the next run
  resumes exactly after it.

### C.3 WHY NOT the other options

- **Option B (`CoroutineWorker` + `setForeground()`)** — rejected as the durability mechanism. It
  would improve survivability, but survivability is not the constraint (see C.2), and it adds a
  notification and a quota cost to buy something the checkpoint already provides. On Android 16
  long-running workers still consume JobScheduler quota, so it is not free.
- **Option C (dedicated `dataSync` FGS)** — rejected, and this is the strongest rejection: it is
  what the project *already does* for the realtime bridge, and it is the option with real platform
  hazards. Android 15+ caps backgrounded `dataSync` FGS at ~6 h/24 h and forbids starting one from
  `BOOT_COMPLETED`; Android 12+ restricts background FGS starts generally. A dedicated FGS may not
  be used to evade WorkManager, and here it would be — for work that is seconds long.
- **Option D (hybrid)** — its useful half *is* Option A. The "optional user-initiated foreground
  mode for the initial FULL_HISTORY import" half is not justified by the numbers: a full import is
  not CPU-bound, so a foreground mode would add user-visible ceremony for no measured gain. Kept as
  the shape of the decision (bounded units + separate realtime bridge) without adding the extra mode.

### C.4 User-initiated transfer model (investigated, not adopted)

A user-initiated data transfer job would fit *semantically* — FULL_HISTORY begins right after an
explicit pairing/history approval — but it is the wrong tool here: it exists to cover large
*foreground-visible* transfers of hours, whereas the local work is seconds and the long tail is
network wait that must survive reboots and doze. It also raises the API floor. Documented and not
adopted.

### C.5 FAILURE / REBOOT / USER-VISIBLE / BATTERY / MIGRATION

- **Failure:** kill at any page boundary → the committed checkpoint resumes after the last committed
  page; at worst one page (≤100 events) is re-read, and `insertOrIgnore` on the unique `eventUuid`
  makes the re-read idempotent rather than duplicating.
- **Reboot:** `BootGatewayReceiver` must re-arm the gateway **without** starting a `dataSync` FGS
  (see C.6). History itself needs no reboot-specific logic: position is in Room.
- **User-visible:** no new notification is required for history. The existing gateway notification
  continues to describe the bridge.
- **Battery:** history runs in bounded bursts paced by upload ACKs rather than holding a wakelock
  for hours; the screen-off cost profile is therefore dominated by the realtime bridge, not history.
- **Migration impact:** none. No schema change and no data movement is implied by this decision.

### C.6 DEFECT FIXED — the reboot re-arm was illegal on Android 15+

`BootGatewayReceiver` → `GatewayService.startGateway(context)` → `startForegroundService` with
`FOREGROUND_SERVICE_TYPE_DATA_SYNC or SPECIAL_USE` **in response to `BOOT_COMPLETED`**. Android 15
(API 35) forbids apps targeting 35+ from launching a `dataSync` foreground service from a boot
receiver; this app targets 36. Because `startGateway` had no try/catch, the refusal took the form of
a thrown `ForegroundServiceStartNotAllowedException` at boot — and since the gateway's only
scheduler is a one-time *send* worker (`GatewayScheduler` has no periodic job), nothing else revived
it. **The gateway stayed dead after every reboot on a modern device until the user opened the app**,
taking the realtime bridge and web-requested sends with it.

Why this outranks its apparent size: it is not a cosmetic platform warning. It silently disabled the
product's core promise on every current Android device, and no JVM test could have caught it because
the failure lives in the platform's start rules, not in our logic.

**Fix, in three parts.**

1. `GatewayForegroundStartPolicy` — the rule is now a pure function of `(apiLevel, startReason)`:
   a `BOOT` start on API ≥ 35 returns `START_SPECIAL_USE_ONLY`; every other combination keeps the
   honest `dataSync or specialUse` pair. `specialUse` is the documented escape hatch for a use case
   the other types do not describe, which is what the LAN-server half genuinely is — and it is not a
   trick to evade the restriction, because history durability does not depend on this service at all
   (§7). The reason travels as an intent extra and is read before `startForeground`.
2. `GatewayService.startGateway` — a refused start can no longer escape. It is caught, logged and
   handed to `GatewayStartDeferral`, which enqueues a **unique** one-time
   `GatewayReArmWorker`; the worker passes `deferOnFailure = false` because its own `Result.retry()`
   is the deferral (otherwise each would enqueue work for the other).
3. `BootGatewayReceiver` now identifies itself as `StartReason.BOOT`.

The policy is advisory, not load-bearing: the try/catch and the WorkManager deferral mean the app is
correct **even if a future Android version restricts more types than are documented here**. The
policy improves the common case; it is never what correctness rests on.

**Tests: 8 new** (`GatewayForegroundStartPolicyTest`) — a truth table (boot above/below API 35;
only boot is restricted; decision and type bitmask cannot disagree; an absent extra is treated as a
user start, because claiming `BOOT` would silently downgrade a foreground start while claiming
`USER_OR_APP` only risks a refusal the service now catches), plus four structural guards on the
shipped path. All four guards were falsified individually: an unconditional `dataSync` type, an
inverted policy decision, a direct `startForegroundService` in the receiver, and a dropped
`deferOnFailure = false`.

**One of those falsifications found a defect in the guard itself, not the code:** the
`deferOnFailure = false` clause first read the RAW file, and it *passed* while the argument was
gone — because the phrase survived in a comment. A guard that reads prose passes on a change to the
prose. Every structural assertion in that file now scans comment-stripped code, which is the same
lesson this project has had to relearn before.

**Still open, and deliberately not half-done:** on Android 15+ a `dataSync` foreground service is
also subject to a background time limit, so the *bridge* can be stopped even when legally started.
That is the remaining half of §C — re-arming the bridge through WorkManager when the platform
reclaims it — and it is the next item rather than something to bolt on here.

---

## 8. Workstream G — the upgrade a real device actually performs

**The released schema is 18.** `git show c5d43e5:MessagesDatabase.kt` reports `version = 18`, and
the schema files tracked at the v3.4.8 commit end at `18.json`. Every version above it (19–24) is in
this worktree and has never shipped. So the only upgrade path a user can take is **18 → 24 through
all six migrations at once** — not one boundary at a time. The existing `MigrationToV19..V24SqlTest`
files each prove their own boundary produces the shape Room generates for that step, which is
necessary but was not sufficient: nothing proved the *composed* path, and nothing checked the
retry-accounting hazard at the one moment it runs unattended on every upgrading device.

`MigrationV18ToV24UpgradeTest` builds a **real v18 database from 18.json's own DDL**, seeds the
durable state, and runs the real `UPGRADE_TO_V19_SQL … UPGRADE_TO_V24_SQL` statements in order.

Seeded and verified to survive: a healthy PENDING event; a `RETRY_WAIT` row at `attemptCount = 5`;
an in-flight `SENDING` row under a lease; an exhausted dead letter (`attemptCount = 26`) carrying its
reason (`VALIDATION_FAILED` / `422` / `deadLetteredAt`); an `ACKED` row with `serverSequence = 42`;
both history checkpoints including `ackedContiguousOrdinal` and `sourceExhausted`; a `CLAIMED`
command; a key epoch at `generation = 3`; and a trusted device with `FULL_HISTORY` / `ACTIVE`.

**The invariant asserted directly**, because it is the migration-shaped version of the rescue defect
fixed earlier in this project:

```text
no row may end up claimable (PENDING / RETRY_WAIT) with an already-exhausted retry budget,
and no migration may silently revive a dead letter
```

Four tests, and the two that carry the hazard were falsified by planting the historical bug shape
**into the real migration list** — `UPDATE gateway_event_outbox SET state = 'PENDING' WHERE state =
'DEAD_LETTER'` plus a watermark reset. Both failed for the intended reason (`expected:<DEAD_LETTER>
but was:<PENDING>`, and `expected:<9> but was:<0>`) before being reverted. The other two tests pin
that every v24 table exists with exactly the shape Room generates, and that the four accounting
tables arrive **empty** — a seeded row would claim a verification, or a held inbound message, that
never happened.

---

## 9. Workstream E — the five process-death windows

A kill between a read and a commit is, at the storage layer, a transaction that never commits.
That makes four of the five windows **deterministically testable**: open a real database, do the
production page work inside a transaction, then abandon the connection mid-transaction.
`ProcessDeathRecoverySqlTest` does exactly that, with the shipped statements.

| window | what was killed | proved |
|---|---|---|
| 1 | after the page is read, **before** the commit | an abandoned page leaves **no events and no checkpoint**; the checkpoint never advances past work that was never durably written |
| 1b | same, then a normal run | the next run completes **exactly once** — 250 messages → 250 rows, 250 distinct `eventUuid`s |
| 2 | **after** events + checkpoint committed | resume reads only the remaining rows (150 of 250), total still 250, every ordinal distinct |
| 3 | rows **IN_FLIGHT** | an expired lease is reclaimed with `attemptCount` untouched (reclaiming is not a failed attempt), a **live lease is never stolen**, and a pre-lease `SENDING` row with no timestamp is still recovered |
| 4 | server accepted, **response lost** | re-claim → re-send → `DUPLICATE` acks the row **once**; a **replayed ACK is a no-op** and cannot rewrite `serverSequence` |

Two supporting facts, both asserted: the shipped page statement has exactly **4** bound parameters
(it references `:beforeDate` twice — counting them is what caught a positional-binding bug in the
benchmark earlier), and a forced replay of an already-read page produces **0 duplicates** because
identity comes from the provider row and the unique index refuses the second insert. That is *why*
a kill at a page boundary is safe rather than merely survivable.

Falsified, so the tests are known to bite: inverting the lease age bound produced a **stolen live
lease** (`expected:<PENDING> but was:<SENDING>`), and dropping `AND state = 'SENDING'` from the
acknowledgement made a **replayed ACK rewrite the row** (`expected:<0> but was:<1>`). A third
attempt at falsification was refused by the compiler: removing `:staleBefore` from the constant made
KSP fail with `PROCESSING_ERROR` because Room rejects an unused query parameter — the guard is
partly enforced by the toolchain, not only by the test.

### Window 5 — `SEND_SMS` mid-handle: the window that cannot be closed, and what is done instead

**Proven, by reading the shipped path** (`SecureCommandPoller`, ~lines 387–436):

- the attempt record is written **before** the side effect — `beginCommandExecution(commandId,
  attemptCount + 1)`, with the code's own comment: *"Open the attempt record BEFORE the side effect
  (mission §45). If the process dies during execution, this row is the only evidence that an attempt
  was started."*
- the command is not re-driven: `SEND_SMS` is absent from `CommandDrainPolicy.RE_DRIVABLE_TYPES`
  (guarded by `theDrainNeverMakesASendReRunnable`), so an interrupted send cannot be resumed by the
  drain the way `MARK_THREAD_READ` can.
- the outcome is reported as **unknown, not failed**: `COMMAND_INTERRUPTED_AFTER_SUBMIT`, whose
  `isTransient` is `false` — asserted in `CommandDrainPolicyTest` and `SyncErrorCodeTest` — so
  nothing retries it automatically.

**Not provable, and not hidden:** between "the attempt record is durable" and "the radio accepted
the submit" there is no atomic step. Android exposes no way to ask whether an SMS was handed to the
radio after the fact, and there is no transactional coupling between a database row and a modem
call. So if the process dies inside that window the device genuinely **cannot know** whether the SMS
was sent. Exactly-once execution of a remote `SEND_SMS` is therefore **not achievable** by any
Android-only design; what is achievable — and implemented — is that the ambiguity is *durable*,
*visible*, *never silently retried*, and resolved by a human or by GMweb issuing a **new** command
id (which makes a duplicate impossible by construction, because the retry is a different command).

This is the honest boundary of mission §78 ("no remotely requested send may cause the same SMS to be
sent twice"): the guarantee is upheld by refusing to retry automatically, not by pretending the
window does not exist. **A device test cannot shrink this window either** — it can only confirm that
the ambiguity surfaces as `COMMAND_INTERRUPTED_AFTER_SUBMIT` rather than as a silent failure or a
duplicate.

---

## 11. Workstream I — MMS attachments: the verified gap and the handoff

Full specification: **[`gmweb-mms-attachment-handoff.md`](gmweb-mms-attachment-handoff.md)**. What
this round established, and why it is a documentation deliverable rather than a code one.

**Verified, from the shipped code:**

- An MMS replicates as an ordinary `MESSAGE_CREATED` whose payload has **no attachment field**
  (`GatewayEventFactory.messageCreated`, payload keys are messageId/direction/body/dateMs/status/
  address/read/contactName/originCommandId/clientMessageId). Text parts fold into `body`, and
  `text/plain` is explicitly classified as *not* an attachment.
- Attachment **identity and metadata already exist and are deterministic**:
  `MmsPartMetadata(partId, messageId, contentType, name, fileName, size)` is read from the provider
  (`MessageAssetIndexer.readMmsPartMetadata`), mapped by `MmsAssetMapper.toAssets`, and stored in
  `message_assets` with `assetKey = sha256("source|providerId|kind|value")`.
- **No bytes are ever read**: `MmsAssetMapper` states the local database must not become a media
  archive, and `value` is the device-local `content://mms/part/<n>`.
- **Nothing is ever uploaded.** Grepping every gateway source for asset usage returns exactly one
  hit — `TelephonySyncCoordinator.kt:735`, which *deletes* assets when a message is removed.
  `message_assets` is otherwise purely the UI/media index.
- **No completeness is claimed.** That is better than a false claim, but it means GMweb cannot
  distinguish a text-only MMS from one whose photos were silently left behind — which is the gap.

**The contract proposed** (11 fields, in the handoff): identity = the client-side `assetKey` as an
opaque idempotency key (never the `content://` URI); MIME; size (`0` = unknown); encryption reusing
the existing key hierarchy with **no plaintext fallback**; a **separate resumable binary channel** so
attachment bytes never enter the bounded event batch; a new `MMS_ATTACHMENT_ADDED` metadata event;
retry/resume idempotent by `assetKey` with `DUPLICATE` treated as success; a size policy anchored on
the existing 900 kB local part limit; and server storage, web decryption and deletion rules stated as
**questions requiring agreement** rather than assumed behaviour.

**Android prerequisites — enumerated, and deliberately NOT implemented yet:**

1. Diagnostics visibility of the gap (highest value; measured/not-measured, never `0` for "fine").
2. Persist `sizeBytes` on `message_assets` (schema addition; `0` = unknown).
3. A completeness rule **with a real caller** — not a floating policy.
4. A content hash computed at upload time.

They were not implemented in this round on purpose. Prerequisite 1 touches `SyncDiagnostics`, its
text renderer, `toSanitizedJson`, the collector and exact-output tests; doing that under a
documentation round's remaining budget would risk leaving a broken suite, and this project's
standard is that a claim must be verified rather than asserted. Prerequisite 2 adds a stored field
that **nothing reads yet** — which is precisely the unwired-abstraction defect removed in WS-H, so it
needs a reader (prerequisite 1) first. The ordering above is the dependency order.

**No code changed in this round.** The suite result below is from the unchanged tree.

---

## 12. Workstream L — the accounting invariants, and a defect they exposed

### L.1 What is already correct (verified, not assumed)

- `HistorySyncState.CAUGHT_UP` is **not** reachable from a finished scan. `SyncStateMachine.history`
  requires `activity.historyAcknowledgedAll`, which the collector derives from
  `repository.isHistoryDeliveryComplete(source)`; a finished scan (`historyScanComplete`) maps to
  `CATCHING_UP`. The "SCAN_COMPLETE is not SYNC_COMPLETE" rule (mission §26) is structurally honoured
  and asserted in `SyncStates` tests.
- The completion rule lives in exactly one place — `HistoryAckWalk.isDelivered` — and the repository
  delegates to it, with a comment recording that re-deriving it at a call site is how the dead-letter
  clause got dropped before.
- `Eligible = Enqueued + Intentionally skipped + Permanent failures` is asserted at every benchmark
  scale (§7) **and** by `HistoryScanAccounting`'s balance check, which is the invariant the mission
  asks for.
- `Enqueued = ACKed + Pending + Retryable + Permanent failures` for history rows is *implied more
  strongly* than required: the contiguity clause `ackedContiguousOrdinal == nextOrdinal - 1` means
  every produced ordinal is ACKed, so Pending = 0 and Retryable = 0 are consequences rather than
  separate checks.

### L.2 Defect FOUND and characterized — a frozen watermark defeats retention entirely

Two shipped predicates, each correct alone, combine badly:

1. `HistoryAckWalk.advance` **stops at the first non-ACKED row**, and its KDoc says a `DEAD_LETTER`
   must not be stepped over: that position "will never be acknowledged without human action", and the
   honest observable is `scanComplete` with `delivered=false`. Sound reasoning — for the frontier.
2. `GatewayEventOutboxEntity.PURGE_ACKED_SQL` removes a history row only when
   `historyOrdinal <= ackedContiguousOrdinal`.

Therefore **one permanently failed history event freezes the watermark and every ACKED history row
produced after it becomes permanently undeletable** — not held back one row, but for the entire
remaining history. Retention stops being retention.

**Measured, not argued** (`OutboxRetentionSqlTest`, round 45): with the watermark frozen at ordinal 2
and 4,001 acknowledged rows above it, all older than the retention window, `purge` removes **0**.

Consequence: on a device with a large history and a single permanent failure, every subsequent
history event is retained forever — on the order of 100 MB at 360k messages — and it never resolves
without human action, because that is precisely what a dead letter means.

**Also unreachable in that state:** `CAUGHT_UP`. `isDelivered` requires `deadLetters == 0` as well as
contiguity, so a source with any permanent failure reports `CATCHING_UP` forever. Mission §26 wants
`CAUGHT_UP` to mean "delivered or explicitly accounted for", so the strict clause is defensible as
*conservative* but makes the terminal state unreachable rather than honest.

**NOT fixed in this round, deliberately.** The obvious fix — let retention clean up beyond a frozen
watermark when the scan is exhausted — is unsafe as stated: the dead letter can later be *rescued*
(the enrollment and crypto rescues return rows to PENDING and then ACKED), and if the rows above it
were already deleted the walk could never advance again, converting "one failed event" into
"permanently stuck history" and silently undoing the frontier's whole purpose. The fix must decide,
explicitly and with tests, between:

- advancing the watermark past the frozen position at purge time (accepting that a later rescue
  cannot restore contiguity), or
- keeping the frontier honest and making retention eligibility depend on the **scan being exhausted**
  plus the blocking row being permanently dead (so nothing will ever ask for those rows again),

and it must also settle whether `CAUGHT_UP` tolerates documented dead letters (L.2's second half).
That is a design decision about the frontier mechanism, in the area the previous goal hardened most,
and it deserves test-first treatment rather than an edit at the end of a round.

### L.3 FIXED — the report invented thousands of pending events and hid the failures

While looking for every consumer of `ackedContiguousOrdinal` before changing the frontier, the
diagnostics turned out to be wrong **independently of the retention defect**, and in the more
damaging direction: `SyncDiagnosticsCollector` reported

```kotlin
acked   = cloud?.ackedContiguousOrdinal          // a frontier position
pending = (produced - acked).coerceAtLeast(0)    // …treated as a count of rows
failed  = null                                   // unmeasured, though the count existed
```

With the frozen watermark of L.2 that reads as **`acked = 2, pending = 4 000`** for a source with
4 000 rows delivered and exactly one permanent failure. So the old design did not merely fail to
report the failure — it *invented 4 000 pending events* and left `failed` unmeasured while
`historyDeadLetters(source, generation)` was already available to answer it. Failure visibility is
the point of §70 and §26: a permanent failure must appear as a permanent failure, not as work in
progress.

**The fix** (`HistorySourceCounts.kt`, a pure function the collector now calls):

- `acked` is a **COUNT of rows in state ACKED** for that source and generation, via the new
  `historyAckedCount` query — not the watermark.
- `failed` is the real `historyDeadLetters` count, so permanent failures are visible.
- `pending` is the **remainder**, so `produced = acked + pending + failed` holds by construction.
- Unmeasured stays `null`, never `0` (a partial read reports the parts it knows and leaves the rest
  null).

The old arithmetic was **falsified** by restoring it: `expected:<1> but was:<0>` for the failure
count and `expected:<0> but was:<1>` for pending work.

### L.4 The retention defect itself — FIXED

`HistoryAckWalk.advance` now crosses a `DEAD_LETTER` instead of stalling on it, so the watermark is
the last **terminal** position (delivered *or* permanently failed) rather than the last delivered one.
Retention therefore works again: with the watermark at 4003, a checkpoint at 4004 and 4,000
acknowledged rows behind it, `purge` removes **4,000** and the dead letter itself is retained as the
record. The test that pinned the defect now guards against it
(`aCrossedDeadLetterNoLongerPinsEveryLaterAcknowledgedRow`).

**What makes this safe, and what keeps it honest:**

- `PENDING`, `RETRY_WAIT` and `SENDING` are **still** not terminal, so the frontier continues to
  imply "no outstanding work below this point" — Pending = 0 and Retryable = 0 survive the change.
  Asserted in both the pure walk and the SQL walk, and the assertion is sharply shaped: with
  `DEAD_LETTER(1), RETRY_WAIT(2), ACKED(3)` the frontier is **1** — the dead letter is crossed
  *and* the outstanding row still stops it.
- `isDelivered` is **unchanged and still strict**: it requires `deadLetters == 0`. Crossing a dead
  position therefore cannot turn a failure into success; the frontier is not the failure signal and
  never was. The failure signal is the counted `historyDeadLetters` (now reported — L.3).
- **Safe under rescue:** the watermark advances *as* the dead position is crossed, so if that row is
  later rescued and acknowledged, nothing above it is needed. Retention may already have removed
  those rows and the walk never looks back — which is exactly why the reverse order (purge first,
  advance later) was rejected as unsafe in round 45.

Falsified by restoring `isTerminal` to `ACKED`-only: three tests failed for the intended reasons
(`expected:<3> but was:<1>` twice, and a `NullPointerException` where a crossed frontier was
expected), then were reverted.

**Still open from L:** `CAUGHT_UP` remains unreachable while any permanent failure exists, because
`isDelivered` requires zero dead letters. That is now a *deliberate reporting decision to settle*
rather than a hidden consequence: the numbers are correct (L.3), so the question is purely whether
the UI should show "caught up, N permanently failed" as its own state. Recorded for a decision, not
silently relaxed.

---

## 13. Workstream D — all four event classes, and a dead priority class

Round 2 verified only `MESSAGE_CREATED`. WS-D names four classes, so the benchmark now drives all
four through their **production builders** — a new SMS, a status update, a command result and a
reconciliation event — behind a >1,000-row backfill backlog, against the shipped ordering constants.

### D.1 FIXED — the `STATUS_UPDATE` priority class was unreachable

`GatewayEventFactory.messageStatusChanged` set `source = SOURCE_STATUS_UPDATE` explicitly and did
**not** set `priority`. `outboxRow` takes no priority argument, so the row silently inherited the
entity default `REALTIME`. Consequences:

- **No event anywhere in production ever carried `PRIORITY_STATUS_UPDATE`.** Grepping the whole main
  source set, `PRIORITY_STATUS_UPDATE` appeared only in its own declaration, the weight table and the
  ordering constants — never as a value assigned to a row. Its weight (80) and its
  `WHEN 'STATUS_UPDATE' THEN 2` ordering slot were dead.
- **A delivery report ranked equal to a new message.** The declared rank exists to stop a burst of
  status callbacks crowding out the new messages it sits below; that protection was not in effect.

Reproduced first: the assertion failed with `expected:<STATUS_UPDATE> but was:<REALTIME>`. The
existing test checked the *source* and not the *priority*, which is exactly how the omission
survived. Fixed by assigning the priority explicitly, and falsified by removing it again (both the
factory test and the new four-class test failed).

### D.2 Verified — the scheduling behaviour, class by class

| class | window | result |
|---|---|---|
| new SMS (`REALTIME`) | foreground | served first |
| command result (`COMMAND_RESULT`) | foreground | served second |
| status update (`STATUS_UPDATE`) | foreground | served third — and now actually carries that rank |
| reconciliation (`RECONCILIATION`) | background | **ordered behind backfill** (below) |

The foreground window returns exactly `[REALTIME, COMMAND_RESULT, STATUS_UPDATE]` while a
>1,000-row backfill backlog is pending, and the background window keeps serving history — so realtime
is never starved by history and history is never starved by realtime. That is the mission §13
fairness requirement, now asserted for all four classes rather than one.

### D.3 MEASURED, not changed — reconciliation waits behind backfill

`RECONCILIATION` shares the background window and ranks **below** `BACKFILL` (weight 10 vs 20), so
while any backfill row is due it is not selected at all: a repair for a message the mirror missed
waits for the bulk import to drain. Pinned by the test rather than left implicit.

**Not changed**, deliberately. The ordering is internally consistent (the weight table and the
ordering constant agree), it blocks nothing urgent because the two groups have separate windows, and
during a full history import the scan re-reads every row anyway — so reconciliation of *old* rows is
largely redundant with the backfill that is running in front of it. The case where it would matter
is a repair to a *recent* row during a long import, and that is a product decision about rank rather
than a defect I can demonstrate. Recorded so that changing the rank later is a decision.

---

## 14. Workstream K — every state-changing write, audited

All 17 `UPDATE` statements on the replication path were enumerated and checked against the rule:
*does this transition change the state together with the fields that make the state mean something?*

### K.1 VERIFIED SAFE — the reclaim → re-execute path cannot double-send

The most dangerous candidate, checked first because it is the §78 invariant itself:

- `markSending` sets `state`, `leaseId`, `inFlightSince`, `lastAttemptAt` and `batchId` together,
  guarded by `state IN (claimable)` ✓.
- `markAcked` / `markRetry` / `markDead` each clear the lease as they leave `SENDING` ✓, and
  `markRetry`/`markDead` increment `attemptCount` with the state ✓.
- `markExecuting` sets `state = 'EXECUTING'` and **keeps** the lease from the `ACCEPTED` claim. That
  is what makes the following path reachable, and it looked like a genuine double-send:
  `reclaimExpiredLeases` returns `state IN ('ACCEPTED','EXECUTING')` to **`RECEIVED`** with the lease
  cleared — i.e. back to the exact state `executeIngested`'s guard (`markCommandAcceptedIfReceived`)
  accepts.

**It is safe, and deliberately so.** `CommandDrainPolicy.decide` does not treat `RECEIVED` as
"untouched": it computes `abandoned` from **`attemptCount > 0`**, with the comment *"Reclaimed rows
land here without a lease, so state cannot answer 'was this ever started?' — attemptCount does."*
An abandoned `SEND_SMS` is not in `RE_DRIVABLE_TYPES`, so it resolves to
`COMMAND_INTERRUPTED_AFTER_SUBMIT` — **Resolve, never re-run (§78)**. Execution is reached only
through `CommandDrainPolicy.plan` (`SecureCommandPoller` reclaims, lists and plans before driving),
so there is no second entrance.

Recorded because it is the *negative* result that matters: the exactly-once guarantee here does not
live in the state column, and anyone "tidying" the reclaim to look at state alone would break it.

### K.2 DEFECT FOUND — five terminal transitions that do not record why, or when

`markCommandState` sets **only** `state`, guarded by `state IN (:fromStates)`. `finishCommand` sets
`state`, `completedAt`, `lastErrorCode` **and** clears the lease. Terminal command transitions are
supposed to use the latter, and five do not:

```text
SecureCommandPoller.kt:411, SecureCommandPoller.kt:429,
GatewayOutgoingPipeline.kt:144, SmsSender.kt:191, SmsSender.kt:296
```

Consequence: a command that ends this way has `completedAt = 0` (its duration is unknowable) and, for
a failure, an empty `lastErrorCode` — so a **web-requested send that the radio refused arrives at
GMweb as FAILED with no reason**, which is precisely what the structured codes exist to prevent, and
a stale lease is left on a terminal row.

**Why it is NOT fixed by swapping in `finishCommand`.** `markCommandState` is a compare-and-set;
`finishCommand`/`markFinished` has **no** from-state guard (`WHERE commandId = :commandId`). Swapping
would trade a missing reason for a lost concurrency guard — two drains could both finish the same
command. The correct fix is a **guarded terminal transition** that records the reason as well
(`markFinishedFrom(..., fromStates)`), then all five sites move to it.

**Pinned rather than left to be rediscovered**: the guard
`aTerminalCommandTransitionAlwaysRecordsItsReason` scans a *window* of lines (these calls wrap, and a
line-based scan would miss `GatewayOutgoingPipeline.kt:144` entirely) and asserts the exact known
offender list as a **ratchet** — it must shrink to empty and must never grow. Falsified by planting a
sixth site, which failed it as intended.

### K.3 FIXED — a guarded terminal transition, and the guard that false-passed

`RemoteCommandEntity.MARK_FINISHED_FROM_SQL` / `markFinishedFrom` / `finishCommandFrom` add the
transition that was missing: **state + `completedAt` + `lastErrorCode` + clears the lease, guarded by
`state IN (:fromStates)`**. `markFinished` recorded the reason but had no guard; `markState` had the
guard but recorded nothing. Terminal transitions need both, and offering only the halves is how the
sites came to be guarded-but-unexplained. All six sites (the five measured in round 49 plus one the
scan had **missed**) now use it.

**The scan false-passed, and that mattered.** The site it missed was the *normal completion of a
remote SEND_SMS* in `GatewayOutgoingPipeline`, which passed a local `terminal` variable — a
literal-name scan cannot see that shape. Three consequences were drawn:

1. The rule moved into the **callee**: `markCommandState` now refuses a terminal target state via the
   pure `refusalForStateOnlyTransition`. A rule the callee enforces cannot be dodged by a call site's
   shape, which makes it safe to keep the textual scan only as a cheap secondary check.
2. The ratchet became `emptyList()` and must *stay* empty, with a **separate** assertion pinning that
   the callee enforcement still exists — emptying the list only proves the literal shapes are gone.
3. Both were falsified independently: making the pure rule always return `null` failed
   `CommandTerminalTransitionTest`, and removing the callee call failed the guard.

**A mistake of mine worth recording**, because this family has now bitten three times: in
`GatewayArchitectureGuardTest` the helper `codeOnly` takes source **text**, not a path (the other
guard file has a path-taking overload). I passed a path, so the assertion read "the rule is missing"
regardless of the file — a **false failure** this time rather than a false pass, but produced by
exactly the helper-shape confusion behind the earlier one. The call site now says so.

## 15. Workstream C, continued — the revival watchdog

### C.7 The other half of WS-C — FIXED: the revival watchdog could not revive anything on API 31+

The service's restart watchdog armed an `AlarmManager` alarm with
`PendingIntent.getForegroundService`. From **API 31** that is a *background* foreground-service start
and is refused by the platform — and because the **system** performs that start, the refusal never
calls back into this process. No exception, no log, nothing: the watchdog emitted
*"⏱️ Watchdog scheduled: gateway restart in 15s"* and the bridge stayed dark. The old KDoc claimed it
"revives the service even from Doze", which describes precisely the thing Android 12+ restricts.

Android 15 makes it worse for `dataSync` specifically: the type is time-limited in the background
(and the budget is per-24h), so even a permitted restart is bounded — and there was **no** `onTimeout`
override, so a timeout was ignored entirely. Since the gateway's only scheduler is a one-time *send*
worker, nothing else brought it back: after the timeout the bridge stayed down until the user opened
the app. Same product-breaking shape as the boot defect in C.6.

**Fix, in three parts:**

1. `GatewayForegroundStartPolicy.restartMechanism(apiLevel)` — a pure function returning
   `WORKMANAGER_ONLY` from API 31 and `ALARM_AND_WORKMANAGER` below it, so the answer is inspectable
   for platforms this build has never run on.
2. `scheduleRestartWatchdog` now calls `GatewayStartDeferral.defer(this)` **first and on every
   platform** — WorkManager owns the retry, schedules inside the OS's own execution windows, and its
   worker catches a refusal and backs off. The alarm is armed only where a direct background start
   genuinely works, so older devices keep the fast 15-second path.
3. `onTimeout(startId, fgsType)` (API 35+) is implemented: record the timeout, defer the restart, and
   `stopSelf(startId)` promptly — lingering past a timeout is the documented route to an ANR.
   Signature verified with `javap` against `android-36/android.jar` rather than assumed (`onTimeout(int)`
   for API 34, `onTimeout(int, int)` for API 35).

**NOT a guarantee, and stated as such:** when the platform refuses background execution outright —
the `dataSync` budget spent, or the app force-stopped — nothing can start the gateway until the app is
foregrounded or the budget resets. The fix makes that *recorded and retried* instead of invisible; it
does not make it survivable, and no Android-only change could.

Four new tests, all falsified individually: inverting `restartMechanism`, removing the deferral from
the watchdog, and deleting `onTimeout` each failed exactly one test.

## 16. Workstream I, prerequisite 1 — the MMS attachment gap is now visible

The first prerequisite from §11 is implemented, because it is the only one with a reader today and
the one that upholds "never mark MMS complete while attachments are missing" from the *Android* side.

**The problem it closes:** the report said nothing about attachments at all, so a device holding
twelve photos the web will never see produced a diagnostic identical to one holding none. The
attachments were indexed for the local media tabs and none of them could be replicated.

**What was added:**

- `MessageAssetSql.COUNT_FOR_SOURCE_SQL` + `MessageAssetDao.countForSource` — a shared constant, so
  the count is testable and the DAO cannot quietly run something else.
- `SyncDiagnostics.MmsAttachmentSection(localAssets: Int?, replicationPathExists: Boolean)` — the
  count, and whether **any** attachment bytes can be replicated.
- The collector fills it from the DAO (null on failure, never a zero).
- `GatewayDiagnosticReport` renders:
  `MMS attachments: on device=12 · replication=NONE — no upload protocol exists (see the GMweb
  attachment handoff)`.

**Why `replicationPathExists` is a field and not an inference.** Reporting "replicated = 0" would read
as "nothing to replicate" or as a healthy zero. The honest statement is that the device holds N
attachments and there is no protocol to upload any of them — so the count of replicated attachments
is not zero, it is not a thing that exists yet. When the endpoint in the handoff spec is agreed, this
becomes a real count and the wording flips on its own.

**Verification.** Four report tests (measured with a gap, unmeasured → `not measured` and never a
number that reads as healthy, a measured zero still naming the missing path, and an absent section
rendered as `not measured` rather than omitted) falsified by making the report claim replication was
available. Plus three real-SQL tests for the shipped count — because the *collector* goes through
Room and cannot run in JVM, so the query would otherwise be verified only by having compiled. A
count that included SMS rows would overstate the gap; one that matched nothing would hide it.

**Still open from §11:** `sizeBytes` (needs a reader first — the same unwired-abstraction rule that
removed `SendState`), the completeness rule, and the content hash.

## 17. Workstream L, closed — `CAUGHT_UP` with documented failures

The mission's own wording settled the question I had been holding open: completion is required
*"subject only to explicitly documented permanent failures"*. So `CAUGHT_UP` is now reachable with
permanent failures, and the failures are documented in the same report that shows the state.

**`HistoryAckWalk.isResolved(sourceExhausted, nextOrdinal, ackedContiguousOrdinal)`** — the caught-up
rule, deliberately weaker than `isDelivered` by exactly **one** clause. It takes no `deadLetters`
parameter, so a caller cannot forget to pass it and no future reader can mistake it for the strict
rule. A permanent failure waits on a **person**, not on this device, so blocking the terminal state on
it made `CAUGHT_UP` unreachable for any source that had ever lost one event — a stuck indicator, not a
stricter guarantee.

**Nothing was weakened beyond that clause.** `sourceExhausted` and the contiguous frontier are both
still required, so Pending = 0 and Retryable = 0 continue to hold. `SyncActivity.historyAcknowledgedAll`
is renamed **`historyResolvedAll`**, because the old name said "everything was accepted by the server"
and that is no longer what it measures; the collector fills it from `repository.isHistoryCaughtUp`.
`isDelivered` is untouched and still returns `false` for the same source, so the strict claim remains
available and honest.

**A report claim that had to change with it.** The scan-complete note said *"GMweb does not yet have
all of it"* for both reasons — and for a permanent failure that is a promise the system cannot keep.
It now splits:

```text
scan complete with 2 permanently failed event(s) on sms. These will NOT arrive without action …
scan complete but NOT delivered for sms. The Provider has been read to the end; GMweb does not yet have all of it.
```

**Verification:** 5 new pure tests (a permanent failure does not block caught-up; the same state is
still not delivered; a running scan and an outstanding row are both still not caught-up; an empty
source is) and 2 report tests pinning both wordings, each falsified individually — dropping
`sourceExhausted` from `isResolved` failed the "not caught up" test, and restoring the single sentence
failed the permanent-failure note test.

## 18. FINAL REPORT — Production Validation & Remaining Android Gaps

```text
START_SHA            c5d43e53374ee6844b2f683bb14e5a898ad4e34e  (v3.4.8, the goal's baseline)
HEAD                 ce7b4b97932d9a9e21ad994464ec6367ed8c2954  (v3.4.9, tagged and released mid-goal)
WORKTREE             24 modified, 11 untracked, 0 staged — NOT committed, per instruction
FINAL_STATUS         ANDROID CODE COMPLETE / DEVICE ACCEPTANCE PENDING
DEVICE_VALIDATION    PHYSICAL DEVICE REQUIRED
```

**⚠ RELEASE COVERAGE:** `v3.4.9` contains the previous goal's work plus this goal's plan document.
**Every fix found in this goal is uncommitted**, including the two product-breaking platform defects
(C.6 boot, C.7 revival watchdog). Nothing in §7–§17 has been released.

### Workstreams

| | status |
|---|---|
| A device-test readiness | DONE — harness + measurement matrix, classes assigned per row |
| B large-history fixture 1k/10k/100k/360k | DONE — deterministic, real SQLite, shipped SQL |
| C Worker vs FGS | DONE — Option A decided **and** implemented; both platform defects fixed |
| D realtime under backfill (4 classes) | DONE — dead `STATUS_UPDATE` priority class found and fixed |
| E process death (5 windows) | DONE — 1–4 verified; 5 documented as inherently unclosable |
| F reboot path | DONE — boot defect fixed; watchdog + Android 15 timeout fixed |
| G upgrade 18→24 | DONE — released schema is 18; composed path verified |
| H `SendState` | DONE — removed, with the reasoning and a single-definition guard |
| I MMS attachments | SPEC DONE; prerequisite 1 implemented; 2–4 blocked on the GMweb endpoint |
| J rescue invariant | DONE — generalised, both rescues fixed |
| K split state transitions | DONE — audited; terminal-transition defect found and fixed |
| L accounting invariants | DONE — two defects fixed; `CAUGHT_UP` with documented failures |

### Defects found and fixed in this goal

1. **L.4** a frozen watermark defeated retention entirely (4,001 rows pinned) — frontier crosses dead letters.
2. **L.3** the report treated a frontier position as a count, inventing thousands of pending events and hiding failures.
3. **D.1** `PRIORITY_STATUS_UPDATE` was assigned nowhere; delivery reports ranked equal to new messages.
4. **C.6** `BOOT_COMPLETED` started a `dataSync` FGS — illegal on Android 15+, gateway dead after reboot.
5. **C.7** the revival watchdog used a background FGS start — refused from API 31 with no callback, so it never revived anything; no `onTimeout` for the Android 15 `dataSync` limit.
6. **K.2** five terminal command transitions left `completedAt = 0` and no `lastErrorCode` — a failed web send reached GMweb with no reason.
7. **L.5** the report promised "GMweb does not yet have all of it" for events that will never arrive.
8. **H** dead `SendState` / `aggregateSendState` (a second derivation from the same evidence).
9. Fixed in passing: a guard that matched prose instead of code; a guard that matched a path where
   text was expected; a guard that false-passed on a local-variable call shape.

### Evidence

```text
TESTS        1972 tests, 0 failures, 183 suites
LINT         :app:lintDebug — 0 errors
BUILD        :app:assembleDebug succeeds; :app:kspReleaseKotlin regenerated after every SQL change
BACKFILL     1k 9 ms · 10k 197 ms · 100k 1 910 ms · 360k 6 647 ms   (maxPageRows=100 at every scale)
             ~52k rows/s flat; page count = ceil(rows/100) + 2; accounting balanced at every scale
REALTIME     foreground window = [REALTIME, COMMAND_RESULT, STATUS_UPDATE] against a >1,000-row backlog
MIGRATION    18→24 composed: all durable state preserved; no row claimable with an exhausted budget
PROCESS      4 windows proven by real transaction rollback; window 5 documented as unprovable
EMULATOR     none executed — no emulator installed (offered, declined) and no device attached
DB CHANGES   none in this goal (three DAO queries added; no schema version change)
```

### What is proven vs inferred

- **Proven:** the SQL and transaction behaviour of paging, checkpointing, retention, rescue,
  migration and acknowledgement, at 360k scale, on real SQLite with the shipped statements; the
  command-state machine's exactly-once *policy*; the platform decisions as pure functions.
- **Inferred, not proven:** that Android actually kills the process at those points; that Keystore
  encryption performs as assumed (excluded from every timing — they are lower bounds); that the
  Telephony Provider behaves as the mirror assumes; that the FGS survives real OEM battery policy.
- **Not achievable by Android alone:** exactly-once remote `SEND_SMS` across the submit window; MMS
  attachment replication (needs the GMweb endpoint).

## 19. v3.4.10 RELEASE HARDENING — inventory and pre-commit report

### 19.1 Worktree recorded before any action

```text
HEAD          ce7b4b97932d9a9e21ad994464ec6367ed8c2954   (v3.4.9)
BASELINE      24 modified, 11 untracked, 0 staged  →  2,019 insertions, 119 deletions
                                                                     (24 files, tracked only)
AFTER BUMP    25 modified (24 + app/build.gradle.kts), 12 untracked
              (the 11 measured + docs/release-v3.4.10.md, written during hardening)

FINAL TOTALS  git diff --stat  →  25 files changed, 2116 insertions(+), 121 deletions(-)
              plus 12 untracked files; 0 staged; HEAD still ce7b4b9 (v3.4.9)

FULL DIFF     recorded out of tree at
              %TEMP%\v3.4.10-worktree.diff  (2792 lines, 60 hunks)
              — deliberately NOT inside the repo, so it cannot become a release artifact.
              Nothing was committed, so `git diff` reproduces it at any time.
```

No `reset`, `checkout`, `restore`, `clean`, `rebase` or `merge` was run, and no uncommitted file was
discarded. `v3.4.9` (ce7b4b9, 132 files) already contains the previous goal's work **and** the dead
`SendState` / `SmsStatusPolicy` removal — verified with `git show --stat ce7b4b9` — which is why
those are not claimed again in the v3.4.10 notes.

### 19.2 Release change inventory

Category: **A** production code · **B** required tests · **C** required documentation ·
**D** benchmark/validation-only · **E** temporary/debug artifact · **F** unrelated.

| FILE | CAT | WHY | IN |
|---|---|---|---|
| `app/build.gradle.kts` | A | versionName/versionCode → 3.4.10 / 117 | YES |
| `data/Daos.kt` | A | `CLOUD_HISTORY_PAGE_SQL` shared so tests run the shipped page statement | YES |
| `data/GatewayEventFactory.kt` | A | status changes carry their declared `STATUS_UPDATE` rank | YES |
| `data/GatewaySync.kt` | A | `MARK_ACKED_SQL`, `RECOVER_STALE_LEASES_SQL`, `MARK_FINISHED_FROM_SQL`, `markFinishedFrom`, `historyAckedCount`, terminal-transition refusal | YES |
| `data/MessageAssetSql.kt` | A | `COUNT_FOR_SOURCE_SQL` behind the MMS attachment gap diagnostic | YES |
| `data/UxDaos.kt` | A | `MessageAssetDao.countForSource` executing that constant | YES |
| `gateway/GatewayService.kt` | A | start reason → type; refusal caught and deferred; watchdog defers before the alarm; `onTimeout` | YES |
| `gateway/SecureCommandPoller.kt` | A | terminal transitions record their reason (2 sites) | YES |
| `gateway/health/GatewayDiagnosticReport.kt` | A | MMS attachment section; permanent-failure wording | YES |
| `receiver/BootGatewayReceiver.kt` | A | declares `StartReason.BOOT`; no direct FGS start | YES |
| `repository/GatewaySyncRepository.kt` | A | `finishCommandFrom`, callee enforcement, `isHistoryCaughtUp` | YES |
| `sms/GatewayOutgoingPipeline.kt` | A | terminal transitions record their reason (2 sites) | YES |
| `sms/SmsSender.kt` | A | terminal transitions record their reason (2 sites) | YES |
| `sync/HistoryAckWalk.kt` | A | dead letters are terminal for the frontier; `isResolved` | YES |
| `sync/SyncStates.kt` | A | `historyResolvedAll`; `CAUGHT_UP` documented as resolved | YES |
| `sync/diagnostics/SyncDiagnostics.kt` | A | `MmsAttachmentSection` | YES |
| `sync/diagnostics/SyncDiagnosticsCollector.kt` | A | counted `acked`/`failed`, remainder `pending`, resolved flag, MMS section | YES |
| `gateway/GatewayForegroundStartPolicy.kt` | A | new: what may be started, and how it may be revived | YES |
| `gateway/GatewayStartDeferral.kt` | A | new: WorkManager re-arm when a start is refused | YES |
| `sync/diagnostics/HistorySourceCounts.kt` | A | new: the honest per-source arithmetic | YES |
| `GatewayEventFactoryTest.kt` | B | pins the status-change priority | YES |
| `HistoryAckWatermarkSqlTest.kt` | B | dead-letter crossing in SQL | YES |
| `OutboxRetentionSqlTest.kt` | B | the retention defect, as a guard | YES |
| `gateway/GatewayArchitectureGuardTest.kt` | B | rescue-budget ratchet + terminal-transition guard and enforcement | YES |
| `gateway/health/GatewayDiagnosticReportTest.kt` | B | MMS section + permanent-failure wording | YES |
| `sync/HistoryAckWalkTest.kt` | B | crossing and resolved rules | YES |
| `sync/SyncStatesTest.kt` | B | renamed activity flag | YES |
| `CommandTerminalTransitionTest.kt` | B | new: the pure terminal-transition rule | YES |
| `MigrationV18ToV24UpgradeTest.kt` | B | new: the composed 18→24 upgrade | YES |
| `MmsAttachmentGapSqlTest.kt` | B | new: the shipped count statement | YES |
| `ProcessDeathRecoverySqlTest.kt` | B | new: crash windows 1–4 by real rollback | YES |
| `gateway/GatewayForegroundStartPolicyTest.kt` | B | new: platform decisions + service guards | YES |
| `sync/diagnostics/HistorySourceCountsTest.kt` | B | new: the accounting invariant | YES |
| `HistoryBackfillBenchmarkTest.kt` | **D** | the 360k harness. Its numbers are cited in the release notes, so excluding it would make the claim unverifiable | YES |
| `docs/production-validation-plan.md` | C | the release's verification evidence and known limitations | YES |
| `docs/gmweb-mms-attachment-handoff.md` | C | referenced **by** the shipped diagnostic text | YES |
| `docs/release-v3.4.10.md` | C | release notes, following `docs/release-v3.3.3.md` | YES |

**E: none.** Verified rather than assumed: benchmark output (`app/build/bench/`), the test databases
(`app/build/migration|procdeath|mmsgap/`) and both APKs are ignored by `app/.gitignore:1:/build`, and
no untracked path exists outside `app/src/` and `docs/`.

**F: none.** `v3.4.9` committed the tree clean, so every current modification dates from this
validation work rather than from pre-existing unrelated edits.

**Two scope notes, flagged rather than assumed into the release:**

1. **The MMS attachment-gap diagnostic is beyond your 12-item list.** It is verified, small, and the
   shipped diagnostic text points at the handoff doc — but it is an addition. Say the word and it is
   excluded (5 files: `MessageAssetSql`, `UxDaos`, `SyncDiagnostics`, `SyncDiagnosticsCollector`'s
   section, `GatewayDiagnosticReport` + its test, `MmsAttachmentGapSqlTest`).
2. **`HistoryBackfillBenchmarkTest` adds ~9 minutes to a full `testDebugUnitTest`** because it runs
   the real 360k fixture. Included because the release notes cite its numbers; if you would rather
   keep the suite fast, the alternative is gating the 360k case behind a system property and running
   it in a separate lane.

### 19.3 Platform re-verification (production path, not comments)

| path | traced evidence |
|---|---|
| Reboot | `BootGatewayReceiver` passes `StartReason.BOOT` and contains **no** `startForegroundService`; the type is chosen through `GatewayForegroundStartPolicy.decide`, which returns `START_SPECIAL_USE_ONLY` from API 35 |
| Watchdog | `scheduleRestartWatchdog` calls `GatewayStartDeferral.defer(this)` **first**, then returns early on API 31+ **before** the `PendingIntent.getForegroundService` is created — so the refused background start is never attempted, and no exception is relied on from outside the process |
| Timeout | `override fun onTimeout(startId: Int, fgsType: Int)` (verified via `javap` against android-36) records the timeout, defers the restart, and `stopSelf(startId)`; history position is durable per page, so a timeout costs work, not data |

### 19.4 Invariant and migration re-verification

Each suite run individually, all green: `EnrollmentRescueSqlTest` 14 · `ProcessDeathRecoverySqlTest`
7 · `MigrationV18ToV24UpgradeTest` 4 · `OutboxRetentionSqlTest` 12 · `HistoryAckWalkTest` 20 ·
`HistorySourceCountsTest` 5 · `CommandTerminalTransitionTest` 3 · `GatewayArchitectureGuardTest` 33 ·
`GatewayForegroundStartPolicyTest` 12 · `GatewayEventFactoryTest` 15.

---

## 20. Progress log

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

### Round 2 (Workstreams B + C)

- **WS-B DONE**: deterministic fixture (`HistoryBackfillBenchmarkTest`) at 1k / 10k / 100k / 360k
  against real SQLite with the shipped DDL, the shipped page statement and the shipped ordering
  constants. Measured: 9 ms / 197 ms / 1 910 ms / **6 647 ms**, flat ≈52k rows/s, `maxPageRows = 100`
  at every scale, accounting balanced at every scale, page count exactly `ceil(rows/100) + 2`.
- One small production change to make that honest: the page query moved to
  `MessageDao.CLOUD_HISTORY_PAGE_SQL` so the benchmark executes the shipped statement instead of a
  retyped copy (same pattern as `PURGE_ACKED_SQL`).
- **WS-C DECIDED**: Option A — bounded, checkpointed chunked units are the durability model; the
  existing `GatewayService` FGS stays as the realtime bridge and is explicitly NOT the durability
  mechanism. Reasoning and the rejection of B/C/D are in §8.
- **Realtime under pressure verified**: realtime selected ahead of a >1000-row backlog, background
  window still serving history.
- **Defect found, not yet fixed** (§C.6): `BootGatewayReceiver` starts a `dataSync` FGS from
  `BOOT_COMPLETED`, which Android 15+ forbids at targetSdk 36. Implementation + regression test is
  the first item of the next round.
- Suite after this round: **1924 tests, 0 failures** (1918 + 6 benchmark tests).
- Still open: WS-D remainder, E, F (beyond the boot defect), G, I, K, L.

### Round 3 (the boot defect fixed)

- **WS-F defect from round 2 FIXED** (§C.6): a `BOOT_COMPLETED` receiver may no longer launch a
  `dataSync` foreground service. The start reason now decides the type through the pure
  `GatewayForegroundStartPolicy`; a refused start is caught and deferred to a unique WorkManager
  re-arm worker instead of throwing at boot; the receiver identifies itself as a boot start.
- **8 new tests**, four of them structural guards, **all four falsified individually**.
- **A guard false pass was found and fixed while falsifying**: the `deferOnFailure = false` clause
  matched a *comment*, so it passed with the argument removed. That file now scans comment-stripped
  code. (Same class of false pass this project has hit before; the lesson keeps needing to be
  re-applied per guard.)
- Suite: **1932 tests, 0 failures**; `lintDebug` 0 errors; `assembleDebug` succeeds.
- Still open: the Android 15+ `dataSync` background time limit on the bridge (the other half of
  WS-C), and WS-D remainder, E, G, I, K, L.

### Round 4 (Workstream G)

- Established from git that the **released** v3.4.8 shipped **schema 18**, so the real upgrade is the
  composed 18→24 path — which no test covered, only the six individual boundaries.
- **WS-G DONE**: `MigrationV18ToV24UpgradeTest` (4 tests) builds a real v18 database from the
  released DDL, seeds the durable state the mission names, runs the real migration statements in
  order, and asserts preservation, exact v24 shape for every table, empty new accounting tables, and
  the invariant that **no row ends up claimable with an exhausted retry budget and no dead letter is
  revived**. The two hazard tests were falsified by planting that exact bug shape into the real
  migration list.
- Suite: **1936 tests, 0 failures**; `lintDebug` 0 errors; `assembleDebug` succeeds.
- Still open: WS-C bridge time-limit half, WS-D remainder, E, I, K, L.

### Round 5 (Workstream E — process death)

- **WS-E DONE for windows 1–4**, and window 5 documented rather than closed.
  `ProcessDeathRecoverySqlTest` (7 tests) simulates a real crash by abandoning a connection
  mid-transaction, then proves: an uncommitted page leaves no events and no advanced checkpoint; the
  next run completes exactly once; a committed page resumes after its checkpoint without redoing
  work; an expired lease is reclaimed while a **live lease is never stolen**; a lost response
  converges on `ACKED` exactly once with a **replayed ACK being a no-op**; and a re-read page cannot
  duplicate an event.
- Two more shipped statements extracted to shared constants so the tests execute the real SQL:
  `RECOVER_STALE_LEASES_SQL` and `MARK_ACKED_SQL` (the latter previously retyped in the test, which
  is itself a drift risk).
- Falsified: an inverted lease bound stole a live lease; dropping the ACK's in-flight guard made a
  replay rewrite the row. A third falsification was refused by Room/KSP for an unused parameter —
  the toolchain enforces part of that predicate.
- **Window 5 (`SEND_SMS` mid-handle) is not closable** and is documented as such: the attempt
  record is durable before the side effect and the outcome is reported as *unknown*
  (`COMMAND_INTERRUPTED_AFTER_SUBMIT`, non-transient, never re-driven), but Android offers no
  atomic step between "attempt recorded" and "radio accepted", so exactly-once remote send is not
  achievable by any Android-only design. The §78 guarantee is upheld by refusing to retry
  automatically — not by pretending the window does not exist.
- Suite: **1943 tests, 0 failures**; `lintDebug` 0 errors; `assembleDebug` succeeds.
- Still open: WS-C bridge time-limit half, WS-D remainder, I (MMS protocol gap + GMweb handoff),
  K, L.

### Round 6 (Workstream I — MMS attachments)

- **Verified the gap from the shipped code** rather than restating the audit: an MMS replicates as a
  plain `MESSAGE_CREATED` with no attachment field; identity and metadata exist locally and are
  deterministic (`assetKey = sha256("source|providerId|kind|value")`); no bytes are ever read; and
  grepping every gateway source for asset usage returns **exactly one** hit — the *delete* on message
  removal. Nothing is uploaded, and nothing claims MMS is attachment-complete.
- **Produced the handoff spec** (`docs/gmweb-mms-attachment-handoff.md`, 273 lines): the 11 required
  contract fields, with server storage / web decryption / deletion stated as questions that need
  agreement rather than assumed behaviour, and the three prohibitions honoured — no invented
  endpoint, no attachment bytes in the event batch, no completeness claim while attachments are
  missing.
- **Android prerequisites enumerated but deliberately not implemented**, with the dependency order
  and the reasons: the diagnostics prerequisite touches `SyncDiagnostics`, its renderer,
  `toSanitizedJson`, the collector and exact-output tests (too much to land safely in a docs round),
  and the `sizeBytes` field has no reader yet — which is the unwired-abstraction defect removed in
  WS-H, so it needs the reader first.
- **No code changed this round**; the suite result stands from the unchanged tree.
- Still open: WS-I prerequisites 1–4, WS-C bridge time-limit half, WS-D remainder, K, L.

### Round 7 (Workstream L — accounting invariants, and a defect they exposed)

- **Verified what is already correct**: `CAUGHT_UP` is unreachable from a finished scan
  (`historyAcknowledgedAll` ← `isHistoryDeliveryComplete`, while `historyScanComplete` maps to
  `CATCHING_UP`); the completion rule has exactly one definition; the accounting invariant
  `Eligible = Enqueued + skipped + failures` is asserted at every benchmark scale; and Pending = 0 /
  Retryable = 0 are *implied more strongly* by the contiguity clause than required.
- **Found and measured a real defect** (§L.2): `HistoryAckWalk.advance` stops at the first non-ACKED
  row (deliberately — a dead letter must not be stepped over), while `PURGE_ACKED_SQL` only deletes a
  history row at or below `ackedContiguousOrdinal`. One permanently failed history event therefore
  freezes the watermark and makes **every later ACKed history row permanently undeletable**. Pinned
  by a new characterization test: watermark frozen at 2 with 4,001 acknowledged rows above it →
  `purge` removes **0**. The same clause makes `CAUGHT_UP` unreachable whenever any permanent failure
  exists.
- **Deliberately not fixed in this round**: the naive fix (purge beyond a frozen watermark once the
  scan is exhausted) is unsafe because a dead letter can later be *rescued*, and deleting the rows
  above it would then strand history permanently — turning one failure into a stuck frontier. The
  round records the two candidate designs and requires test-first treatment in the area the previous
  goal hardened most. The characterization test must be **replaced** when the fix lands.
- Suite: **1944 tests, 0 failures** (1943 + 1 characterization test).
- Still open: the L.2 fix, WS-I prerequisites 1–4, WS-C bridge time-limit half, WS-D remainder, K.

### Round 8 (Workstream L — the diagnostic lie fixed first)

- Enumerated **every** consumer of `ackedContiguousOrdinal` before touching the frontier — which is
  how the bigger defect surfaced: the diagnostics treated a **frontier position as a row count**.
  With a frozen watermark the report claimed `acked = 2, pending = 4 000` for a source with 4 000
  rows delivered and one permanent failure, and reported `failed = null` while
  `historyDeadLetters(...)` existed to answer it. The old design did not just fail to report the
  failure; it invented thousands of pending events.
- **Fixed** (`HistorySourceCounts.kt` + new `historyAckedCount` query): `acked` is counted,
  `failed` is reported, `pending` is the remainder so `produced = acked + pending + failed` holds by
  construction, and unmeasured stays null. **Falsified** by restoring the old formula
  (`expected:<1> but was:<0>` failures; `expected:<0> but was:<1>` pending).
- This is the prerequisite for the L.2 frontier change — advancing the watermark past a dead
  position is only safe once nothing reads the watermark as an acknowledgement count.
- Suite: **1949 tests, 0 failures** (1944 + 5); `lintDebug` 0 errors; `assembleDebug` succeeds.
- Still open: the L.2 retention fix itself, WS-I prerequisites 1–4, WS-C bridge time-limit half,
  WS-D remainder, K.

### Round 9 (Workstream L — the retention defect fixed)

- **Reversed the frontier rule deliberately and with evidence**: `HistoryAckWalk.advance` now crosses
  a `DEAD_LETTER`, so the watermark is the last *terminal* position rather than the last delivered
  one. Retention works again — 4,000 acknowledged rows purged, the failure record retained.
- **Honesty preserved by construction**: `PENDING`/`RETRY_WAIT`/`SENDING` still stop the walk, so
  Pending = 0 and Retryable = 0 still hold below the frontier; `isDelivered` is unchanged and still
  requires zero dead letters, so a crossed failure can never be called delivered. The failure signal
  is the counted `historyDeadLetters`, not the frontier.
- **Safe under rescue** because the watermark advances *as* the dead position is crossed — the reason
  the opposite order was rejected as unsafe in round 45.
- Three tests that encoded the old contract were updated deliberately (two in the pure walk, one in
  the SQL walk), the characterization test became a guard, and the change was **falsified** by
  restoring `ACKED`-only termination (3 failures, `expected:<3> but was:<1>`).
- One of my own test assertions was wrong on first run — I asserted `null` where the walk correctly
  returns frontier **1**. The corrected assertion is strictly better: it proves the dead letter *is*
  crossed and that the outstanding row behind it still stops the walk.
- Suite: **1950 tests, 0 failures**; `lintDebug` 0 errors; `assembleDebug` succeeds;
  `kspReleaseKotlin` regenerated and the new counted query verified in the **release** DAO (WS-J).
- Still open: the `CAUGHT_UP`-with-documented-failures reporting decision, WS-I prerequisites 1–4,
  WS-C bridge time-limit half, WS-D remainder, K.

### Round 10 (Workstream D — all four classes, and a dead priority class)

- **Found and fixed a real defect while completing WS-D**: `messageStatusChanged` set its *source*
  explicitly but not its *priority*, so it inherited `REALTIME` — `PRIORITY_STATUS_UPDATE` was
  assigned nowhere in production, making its weight (80) and ordering slot dead, and letting
  delivery reports rank equal to new messages. Reproduced
  (`expected:<STATUS_UPDATE> but was:<REALTIME>`), fixed, falsified by removing it again.
- The existing test asserted the source and not the priority — the omission survived precisely
  because the adjacent fact was checked.
- **WS-D DONE**: all four classes (new SMS, status update, command result, reconciliation) are driven
  through their production builders behind a >1,000-row backlog. Foreground returns exactly
  `[REALTIME, COMMAND_RESULT, STATUS_UPDATE]`; history keeps being served in its own window.
- **Measured, not changed**: `RECONCILIATION` ranks below `BACKFILL` in the background window, so a
  repair waits for the bulk import. Pinned by a test and recorded with the reasoning for leaving it —
  it blocks nothing urgent and the running backfill largely covers the same rows.
- Suite: **1952 tests, 0 failures**; `lintDebug` 0 errors; `assembleDebug` succeeds.
- Still open: the `CAUGHT_UP` reporting decision, WS-I prerequisites 1–4, WS-C bridge time-limit
  half, K.

### Round 11 (Workstream K — every state-changing write audited)

- Enumerated all **17** `UPDATE` statements on the replication path and checked each for a split
  transition (state changed without its invariant fields).
- **Verified safe, deliberately**: the `reclaimExpiredLeases` → `RECEIVED` → re-claim path looked
  like the §78 double-send, because it restores the exact state `executeIngested`'s guard accepts.
  It is safe because `CommandDrainPolicy` decides "was this ever started?" from **`attemptCount`**,
  not state, and resolves an abandoned `SEND_SMS` as `COMMAND_INTERRUPTED_AFTER_SUBMIT` — Resolve,
  never re-run. Execution is only reachable through `CommandDrainPolicy.plan`.
- **Defect found (K.2)**: five terminal command transitions use the state-only `markCommandState`,
  so `completedAt` stays 0 and a failed web send reaches GMweb as FAILED with **no reason**.
  Not fixed by swapping in `finishCommand`, which has no from-state guard — the reason must become
  recordable *together with* the guard. Pinned as a **ratchet** guard (window scan, exact offender
  list, must shrink to empty), falsified by planting a sixth site.
- Suite: **1953 tests, 0 failures**; `lintDebug` 0 errors; `assembleDebug` succeeds.
- Still open: the K.2 fix, the `CAUGHT_UP` reporting decision, WS-I prerequisites 1–4, WS-C bridge
  time-limit half.

### Round 12 (Workstream K — the guarded terminal transition)

- **K.2 fixed**: added `finishCommandFrom` (state + `completedAt` + `lastErrorCode` + clears the
  lease, guarded by from-states) and moved **all six** sites to it — including the one round 49's
  scan **false-passed**, which was the normal completion of a remote `SEND_SMS` and passed a local
  `terminal` variable.
- Because the scan missed a shape, the rule moved into the **callee**: `markCommandState` refuses a
  terminal target state (pure `refusalForStateOnlyTransition`), so no call-site shape can reintroduce
  the defect. The ratchet is now `emptyList()`, with a separate assertion pinning that the
  enforcement still exists. Both falsified independently.
- Fixed a **false failure of my own**: `codeOnly` in that guard file takes text, not a path, and I
  passed a path, so the assertion read "the rule is missing" regardless of the file.
- Suite: **1956 tests, 0 failures**; `lintDebug` 0 errors; `assembleDebug` succeeds;
  `kspReleaseKotlin` regenerated and `markFinishedFrom` verified in the **release** DAO (WS-J).
- Still open: the `CAUGHT_UP` reporting decision, WS-I prerequisites 1–4, WS-C bridge time-limit
  half.

### Round 13 (WS-C's remaining half — the revival watchdog)

- **Found and fixed a second platform-compliance defect of the same shape as C.6**: the restart
  watchdog armed an alarm with `PendingIntent.getForegroundService`, which from API 31 is a
  *background* foreground-service start — refused by the platform, and because the **system**
  performs it the refusal never reaches this process. The watchdog logged "restart in 15s" and the
  bridge stayed dark.
- **Fixed**: `restartMechanism(apiLevel)` (pure, `WORKMANAGER_ONLY` from API 31) so the alarm is
  armed only where it works; the watchdog now defers to WorkManager **first, on every platform**;
  and `onTimeout(startId, fgsType)` (API 35+, signature verified with `javap`) records the `dataSync`
  timeout, defers the restart and stops promptly.
- **Stated limitation, not hidden**: when the platform refuses background execution outright — the
  `dataSync` budget spent, or the app force-stopped — nothing can start the gateway until the app is
  foregrounded. The fix makes that recorded and retried rather than invisible.
- Four new tests, each falsified individually (inverted mechanism rule, removed deferral, deleted
  `onTimeout`).
- Suite: **1960 tests, 0 failures**; `lintDebug` 0 errors; `assembleDebug` succeeds.
- Still open: the `CAUGHT_UP` reporting decision, WS-I prerequisites 1–4.

### Round 14 (WS-I prerequisite 1 — make the MMS gap visible)

- **Implemented the first Android prerequisite from §11**, the one with a real reader: the diagnostic
  now reports the MMS attachment gap. Before this, a device holding twelve un-replicable photos
  produced a report identical to one holding none.
- `MmsAttachmentSection(localAssets, replicationPathExists)` + `MessageAssetDao.countForSource`
  (shipped as a shared constant) + a report line naming the missing protocol and pointing at the
  handoff spec. `replicationPathExists` is a field rather than an inference so the report cannot read
  as a healthy zero.
- **Four report tests** (including an absent section → `not measured`, never omitted) falsified by
  making the report claim replication was available; plus **three real-SQL tests** for the shipped
  count, because the collector goes through Room and is not JVM-testable — the query would otherwise
  have been "verified" only by compiling.
- Suite: **1967 tests, 0 failures**; `lintDebug` 0 errors; `assembleDebug` succeeds;
  `kspReleaseKotlin` regenerated and `countForSource` verified in the **release** DAO (WS-J).
- Still open: `sizeBytes` (needs a reader first), the completeness rule, the content hash, and the
  `CAUGHT_UP` reporting decision.

### Round 15 (WS-L closed — `CAUGHT_UP` with documented failures)

- The mission's own wording settled what I had been holding open: completion is required *"subject
  only to explicitly documented permanent failures"*.
- Added `HistoryAckWalk.isResolved` — the caught-up rule, weaker than `isDelivered` by exactly one
  clause and taking **no** `deadLetters` parameter so it cannot be confused with the strict rule.
  `sourceExhausted` and contiguity are still required, so Pending = 0 / Retryable = 0 still hold.
- Renamed `SyncActivity.historyAcknowledgedAll` → `historyResolvedAll`, because the old name claimed
  "everything was accepted by the server" and that is no longer what it measures.
- **Fixed a report claim that could not be kept**: the scan-complete note said "GMweb does not yet
  have all of it" for permanent failures too. It now distinguishes "will NOT arrive without action"
  from "not yet".
- 7 new tests; two falsified individually (dropping `sourceExhausted`; restoring the single
  sentence).
- Suite: **1972 tests, 0 failures**; `lintDebug` 0 errors; `assembleDebug` succeeds.
- **All twelve workstreams A–L are now closed** except WS-I's remaining prerequisites (`sizeBytes`,
  the completeness rule, the content hash), each of which depends on the GMweb endpoint.
