# Gateway replication audit — before any architectural change

Audit of the Android replication path in `aibedini/Messages` as it exists today, produced
before Phase 1 is implemented, as required by the mission ("Do NOT rewrite working
subsystems before understanding them").

Method: read-only. Every claim below carries a `file:line` citation. Where a fact was
checked by hand rather than taken from the deep-dive passes, it is marked **[verified]**.
Nothing was modified to produce this document.

Two corrections to the mission's own framing, established while reading:

- `TelephonySyncCoordinator.kt` is **2213 lines**, not the ~2084 implied by an earlier count.
- The mission says "the current EventUploader has prerequisites such as gateway enabled,
  GMweb URL exists, identity registered. A connected Gateway/control channel does NOT mean
  message replication is operational." That is **correct and confirmed** — see Blocker 1 and
  the `UploadGate` section. The conflation is real.

---

## CURRENT ARCHITECTURE

The mission's target diagram is *approximately* already built. What exists:

```text
Telephony Provider
   ├── ContentObserver  (SmsRepository.registerObserver — registered ONLY by ViewModels)
   └── Receivers        (SmsReceiver / MmsReceiver / SmsStatusReceiver — default-SMS-app only)
        ↓
ChangeRouter.route            (Main looper; no network, no provider read)
        ↓
ProviderRepairQueue           (durable exact-row repair intents)
        ↓
TelephonySyncCoordinator      (mutations Channel.UNLIMITED + serialized ingest transaction)
        ↓
Local Mirror (Room message table)
        ↓
Canonical-ish mapping → GatewayEventFactory (deterministic-ish UUIDs)
        ↓
MessageCrypto / ConversationKeyRepository (v1/v2/v3 envelopes, HPKE-wrapped DEKs)
        ↓
gateway_event_outbox          (durable; committed in the SAME transaction as the mirror row)
        ↓
EventUploader (foreground-service coroutine loop, NOT WorkManager)
        ↓
POST /api/v1/agent/events/batch   (X-Agent-Auth signed)
        ↓
accepted[] → ACKED + serverSequence
```

The command direction:

```text
OutboxPoller / SecureCommandPoller  (pull from GMweb)
        ↓
remote_commands (durable inbox, unique idempotencyKey)  +  remote_command_executions
        ↓
CommandCrypto (inbound decrypt/verify only)
        ↓
executor → SmsManager / MmsSender
        ↓
result/status events → same outbox
```

Provider↔mirror reconciliation also already exists: `TelephonySyncCoordinator.runIntegrityAudit()`
(`:1895-1900`) pages the mirror and the provider 100 rows at a time in both directions
(`auditRoomToProvider` `:1931-1984`, `auditProviderToRoom` `:1986-2010`), self-rearms every 24 h
(`:1925`, `:1883`), and keeps a resumable cursor in `integrity_audit_state` (`Entities.kt:252-268`).

So the durable-outbox spine the mission asks for **already exists and is genuinely durable**.
The defects are in the edges: who feeds it, what stops it, what a failure means, and identity.

---

## CURRENT BLOCKERS

Ordered by how badly each breaks the mission's Definition of Done.

### Blocker 1 — Realtime replication stops when the UI stops **[verified]**

The SMS/MMS `ContentObserver` is registered **only from ViewModels**:
`HomeViewModel.kt:281` and `ConversationViewModel.kt:483`. No service and no receiver
registers it (`SmsRepository.registerObserver` call sites are exactly those two).

`ChangeRouter.route` has exactly one production caller: `HomeViewModel.kt:179`.

Consequence: with no ViewModel alive — app swiped away, screen off after the process is
reclaimed, or any headless start — a newly arriving SMS produces **no provider→cloud
replication at all**. The mission's central promise ("existing SMS... new incoming SMS/MMS...
must reliably replicate") therefore currently holds only while the UI is alive, plus the
narrow default-SMS-app receiver path (`SmsReceiver.kt:107-132` → `IncomingMessageDispatcher.kt:68-74`).

This is the single highest-value defect found, and it is invisible: nothing is logged, no
outbox row exists, and diagnostics show a healthy gateway.

### Blocker 2 — The upload gate is 4 conditions, RAM-only, and absent from diagnostics

`EventUploader.kt:40-46`:

```kotlin
internal fun reason(enabled: Boolean, urlBlank: Boolean, registered: Boolean): UploadGate =
    when {
        !enabled -> GATEWAY_DISABLED
        urlBlank -> URL_NOT_CONFIGURED
        !registered -> DEVICE_NOT_ENROLLED
        else -> ENABLED
    }
```

- Only four gates. No auth-expired, no revoked, no missing-key, no missing-history-grant.
- The blocked state is logged only on **change** (`:125-129`) and held in a RAM field
  (`private var lastGate: UploadGate?`, `:91`) — lost on process death, never in
  `GatewayHealthRecorder`, never in the diagnostic report.
- `_running` is set `true` at `:96` **before** the gate is ever consulted, so
  `EventUploader.running` (read by `ConnectionDiagnostics.kt:58`) reports *running* while
  every upload is being refused. This is the "CONNECTED does not mean SYNCED" ambiguity the
  mission wants removed, still present.

The same conditions are re-derived independently in at least eight places with different
combinations — `ContactsSyncPublisher.kt:82,101`, `DeviceTelemetry.kt:44`,
`HeartbeatManager.kt:115-116`, `OutboxPoller.kt:242`, `WebhookEngine.kt:30`,
`RegistrationManager.kt:72,76`, `BootGatewayReceiver.kt:23`, `MessagesApp.kt:91`. There is no
single source of truth, which is exactly what `ReplicationPrerequisiteEvaluator` must fix.

### Blocker 3 — No lease, and no stale-in-flight timeout

The outbox has no `lease_id` and no `in_flight_since` **[verified]**. In-flight is expressed
only as `state='SENDING'`.

Recovery is `resetSendingToPending()` — `UPDATE ... SET state='PENDING' WHERE state='SENDING'`
(`GatewaySync.kt:223`) — with **no age predicate**, called from exactly one place:
`EventUploader.start()` (`:101`), i.e. once per uploader start. And `start()` returns
immediately when the loop is already alive (`:94`), so a reconnect does **not** re-run recovery.

A row left `SENDING` by a mid-batch cancellation therefore stays `SENDING` for the lifetime of
the process loop. The mission's §12 ("IN_FLIGHT older than lease timeout → READY", 5 min,
configurable) is absent.

Related trap: the claim query selects `state IN ('PENDING','SENDING')` (`GatewaySync.kt:181`),
so `SENDING` does **not** exclude a row from being claimed again; and `markSending`'s return
value is discarded (`GatewaySyncRepository.kt:73`), so a "claimed" row may never have
transitioned. Today the only thing preventing a double upload is that there is exactly one
sequential uploader loop.

### Blocker 4 — Any 4xx except 429 dead-letters the ENTIRE batch

`EventUploader.kt:389-403`:

```kotlin
if (status != null && status in 400..499 && status != 429) {
    val failure = GatewayFailureKind.classify(httpStatus = status)
    submitted.forEach { repo.onDeadLetter(...) }
    Outcome.FATAL
}
```

So a single `401` dead-letters every event in the batch. The mission requires the opposite:
401/403 → `BLOCKED_AUTH` and wait for auth recovery; 413 → split the batch; only the affected
event dies for a schema error.

There is a partial mitigation — `RegistrationManager.kt:257-259` resets **the whole
DEAD_LETTER table** back to PENDING after a successful enrollment. But that reset does **not**
restore `nextAttemptAt` or `attemptCount` (`GatewaySync.kt:233-238`, unlike `:241-247`), so
rescued rows keep a stale due time and an inflated attempt count, and it also resurrects rows
that were dead-lettered for genuinely permanent reasons (413, unknown event type).

Also a contract mismatch: `GatewayFailureKind` classifies `409` as `HTTP_CONFLICT` and lists it
as **transient** (`:112`, `:202`), while the uploader dead-letters it.

### Blocker 5 — `DUPLICATE` is never acknowledged, so Test F cannot converge

`EventUploader.kt:305-315` parses only a flat `accepted` array, and requires
`eventId.isNotEmpty() && sequence > 0`. `duplicates` is read as a scalar purely for logging
(`:325`) and never ACKs anything.

There is no per-item `results` array with `ACCEPTED`/`DUPLICATE`/`REJECTED` (mission §15).

Consequences, both directly contradicting the mission:

- An event the server has already persisted but echoes without a positive `serverSequence`
  is re-uploaded **forever**.
- Mission Test F ("server persisted the event but the HTTP response was lost; retry must
  converge to DUPLICATE → ACKED") **cannot pass today**: the retry gets classified as
  not-accepted and re-queued indefinitely.

### Blocker 6 — The missing-key case is an infinite invisible requeue

`ControlPlaneClient.kt:44-46` rejects a non-HTTPS origin, and `:55-57` aborts when the signer
returns false — and `AgentAuth.sign` returns false on *any* exception, including an
unavailable Keystore key. Both produce `Result.Failure` with `httpStatus = null`, which
`EventUploader.kt:404-406` treats as a transport failure: requeue and retry forever, with
**no reason recorded on any row** and no gate.

This is precisely the mission's `BLOCKED_KEY` / `MISSING_CRYPTO_KEY` condition, currently
indistinguishable from a network blip.

Related: `TelephonySyncCoordinator.kt:1214` encrypts before insert with no guard — if key
unwrap throws, the event row is never inserted and nothing durable records that it was lost.

### Blocker 7 — Realtime and history do not share an event identity **[verified]**

`GatewayEventFactory.kt:139-141`:

```kotlin
val eventId = if (priority == PRIORITY_BACKFILL)
    UUID.nameUUIDFromBytes("evt:replica-v4:$source:$providerId:$dateMs".toByteArray()).toString()
else eventUuidFor(Types.MESSAGE_CREATED, source, providerId, dateMs)
```

and `eventUuidFor` is `"evt:v3:$eventType:$source:$providerId:$dateMs"` (`:57-58`).

The producer is chosen by `DiscoveryMode` (`TelephonySyncCoordinator.kt:928-930`): a sweep
(`HISTORY_BACKFILL` / `STARTUP_DELTA`) calls `enqueueHistorical` → `evt:replica-v4:…`; realtime
falls to the `else` branch → `evt:v3:MESSAGE_CREATED:…`.

So **one logical message has two possible deterministic identities**, and the outbox unique
index on `eventUuid` (`GatewaySync.kt:73`) cannot dedupe across them. `enqueueHistorical`'s
own guard (`idOf(eventId) != null`, `:1367-1370`) is blind to the other namespace. Both rows
would carry the **same** `payload.messageId` (`messageIdFor` is shared, `GatewayEventFactory.kt:53-54`),
so the mission's §33 race ("history scanner reads message X; observer detects message X almost
simultaneously → same canonical identity, no duplicate Web bubble") is not satisfied by
construction. Today the race is mostly narrowed by the `UNCHANGED` short-circuit inside the
serialized ingest transaction (`:916-917`) — i.e. by accident of serialisation, not by identity.

This is the one place the mission explicitly permits touching deterministic identity: "Do not
replace existing deterministic event-ID logic **unless a demonstrated bug exists**." A
demonstrated bug exists.

Also non-deterministic, and defeating the same index: `conversationUpserted` and
`conversationDeleted` derive their UUID from `revision` which defaults to
`System.currentTimeMillis()` (`GatewayEventFactory.kt:232`, `:253`), and their callers pass no
revision (`TelephonySyncCoordinator.kt:747`, `:777-781`, `:838-847`). Two upserts in the same
millisecond collide and the second is **silently dropped** by `insertOrIgnore`.

### Blocker 8 — Missing telephony permission fails silently and forever

There is no pre-flight permission check on the sync path (`MessagesApp.kt:162` and
`MainActivity.kt:346` are the only checks in the app). A missing `READ_SMS` grant surfaces as
`ProviderRead.Failure(SECURITY)` (`SmsRepository.kt:294-302`) → an `IllegalStateException`
(`TelephonySyncCoordinator.kt:1540`, `:1555`) → caught and requeued with backoff, log-only
(`:474-478`). The history crawl throws at `:1577` and is caught with `Log.e` only
(`:1339-1340`), so `historyBackfillComplete` stays `false` **forever** and the crawl is
retried on every launch with no user-visible explanation.

Mission §40 requires a loud `WAITING_FOR_HISTORY_GRANT` state and a
`FULL_HISTORY_GRANT_MISSING` diagnostic. Absent.

### Blocker 9 — SCAN_COMPLETE and SYNC_COMPLETE both exist but are conflated in the only observable

Both facts are modelled:

- SCAN_COMPLETE: `sync_state.historyBackfillComplete` (`Entities.kt:297`, set at
  `TelephonySyncCoordinator.kt:1592`).
- DELIVERY COMPLETE: `sourceExhausted && ackedContiguousOrdinal == nextOrdinal - 1 &&
  historyDeadLetters == 0` in `GatewaySyncRepository.isHistoryDeliveryComplete` (`:110-115`).

But `isHistoryDeliveryComplete` has **no caller in `app/src/main`** **[verified]**, while the
one surfaced signal — `ConnectionDiagnostics.mirrorCheck` — reports "complete" from
`historyBackfillComplete` alone (`ConnectionDiagnostics.kt:171-174`).

So the mission's mandatory §26 distinction exists in the data layer and is lost at the only
place a human looks. This is the exact conflation §26 forbids.

### Blocker 10 — The outbox is append-only with no retention **[verified]**

There is no `DELETE` against `gateway_event_outbox` anywhere, no `@Delete`, no purge query, no
trigger, no TTL. `GatewayEventOutboxDao` declares none (`GatewaySync.kt:171-345`). ACKED and
DEAD_LETTER rows accumulate for the life of the install.

Mission §19 asks for 24–72 h ACKED retention then cleanup. Currently: retention is infinite
(safer than deleting, but unbounded — a 360k-message history plus steady traffic grows this
table without limit, and `pendingBytes()` is measured but never enforced).

### Blocker 11 — BACKFILL can be starved indefinitely

`GatewaySync.kt:179-184` orders `REALTIME` before everything else with a strict
`CASE priority WHEN 'REALTIME' THEN 0 ELSE 1 END, id`, and the candidate fetch is capped at
`MAX_BATCH_EVENTS * 2 = 200` (`GatewaySyncRepository.kt:71`). With ≥100 due REALTIME rows,
`selectBatch` fills every slot with realtime (`:50-61`) and no BACKFILL row is ever selected;
with >200 due realtime rows, no BACKFILL row even enters the candidate list.

Mission §13 asks for weighted/fair batching (e.g. 70/30) so history is not permanently
starved. The only counter-pressure today is on the ingest side
(`available = 2_000 - pendingBackfillDepth()`, `TelephonySyncCoordinator.kt:1419`), not the
claim side.

### Blocker 12 — Nothing reconciles the outbox

Reconciliation exists for provider↔mirror (Blocker-free, 24 h, durable cursors). It is
**absent** for outbox vs provider/mirror: no component re-creates events that were never
enqueued. `ChangeRouter.kt:126-133` documents the gap explicitly ("the periodic two-sided
integrity audit (NOT implemented yet)"). Combined with Blocker 1 this is how a message can be
known to Android and never reach GMweb with nothing recording the loss.

### Blocker 13 — Stale ownership comment, but single-owner intake is genuinely enforced **[verified]**

> **Self-correction.** An earlier pass of this audit (and one deep-dive) claimed both send
> transports run concurrently and can double-send. **That is wrong**, and it was caught by
> reading the start path rather than the capability wiring. Corrected below.

`SecureCommandPoller.kt:208-216` carries an ownership comment:

```kotlin
// Intake ownership (P0, no-dual-execution): SEND_SMS is owned by
// exactly ONE transport. Until the strategic command path passes
// real-device E2E, the legacy pull bridge owns delivery — strategic
// SEND_SMS commands are ingested + ACKed but NOT executed here …
if (cmd.type == "SEND_SMS" && !prefs.controlPlaneSendsEnabled) { ...defer...; return }
```

Superficially alarming, because `GatewayPreferences.kt:47` is
`DEFAULT_CONTROL_PLANE_SENDS = true` (`:418` returns it), so `!controlPlaneSendsEnabled` is
false and the deferral never fires — the strategic path *would* execute SEND_SMS.

**But the strategic poller is never started**, so this is unreachable:

- `ConnectionSupervisor.kt:117`: `val deliveryIntake: DeliveryIntake = DeliveryIntake.LEGACY_PULL`.
- `:373-382`: the switch explicitly stops the other consumer. `LEGACY_PULL → stopCommandPoller()`
  then `startPoller()`; `CONTROL_PLANE_COMMANDS → stopPoller()` then `startCommandPoller()`.
- `startCommandPoller()` has exactly one call site, `:380`, inside the
  `CONTROL_PLANE_COMMANDS` branch.
- `deliveryIntake` appears **nowhere else** in `app/src` — not in main, not in tests — so the
  default always applies and `CONTROL_PLANE_COMMANDS` is never selected.

So the design is sound: one intake owner, chosen by an explicit switch, with the loser stopped.
There is **no dual execution and no cross-transport duplicate send** in the shipped
configuration. The only real defect here is documentation: the comment at
`SecureCommandPoller.kt:208-211` describes a behaviour ("ingested + ACKed but NOT executed")
that the default flag contradicts, so it will mislead the next reader. It should be corrected
when the intake switch is next touched.

Consequences for the two findings that depended on it: Blockers 15 and 16 below are **latent**,
not live — they activate only when `deliveryIntake` is switched to `CONTROL_PLANE_COMMANDS`.

### Blocker 14 — The legacy queue re-sends an interrupted send on restart **[verified]**

`EveSmsQueue.drainOne` marks the record `ACTIVE` and sets `submittedOnce = true`
(`EveSmsQueue.kt:566-578`), persists **asynchronously** (`:772-779`), then invokes the sender
(`:580-586`). On restart, `bootstrap` requeues unconditionally (`:322-324`):

```kotlin
if (!rec.terminal && rec.status == Status.ACTIVE) rec = rec.copy(status = Status.QUEUED)
```

`submittedOnce` is written (`:571`), persisted (`:864`) and restored (`:833`) — and **never
read as a guard**; its only other use is display (`GatewayServer.kt:692`) **[verified]**.

Interleaving: pull → `drainOne` → `ACTIVE` persisted → `sendTextMessage` accepted by the radio
→ process killed before `status = SENT` is persisted → restart → requeued → sender invoked
again → **second physical SMS**.

The repo already has the correct pattern and does not use it here: `DelayedSendExecutor.kt:104-111`
does an at-most-once claim and `:182-185` marks a stranded record FAILED instead of resending.

### Blocker 15 — Strategic commands would be lost, not retried (LATENT — poller not started)

The strategic path cannot double-send a command — `commandId` PK + UNIQUE `idempotencyKey`
+ `INSERT OR IGNORE` + `markAcceptedIfReceived`, with `execute` reachable only when `ingest`
returned true (`SecureCommandPoller.kt:109-119`). Mission §47's first half holds there.

Its failure mode is the opposite, and it breaks §46/§48:

- No lease, no `claimed_at`, no `lease_expires_at`, no `attempt_count` (columns absent).
- `expiresAt` exists but nothing enforces it: `expireStale` (`GatewaySync.kt:450`) has
  **zero callers** **[verified]**.
- There is no startup or periodic drain of non-terminal commands — no query for
  `state IN ('RECEIVED','ACCEPTED','EXECUTING')` anywhere.
- A process death after ingest but before execution therefore strands the row forever, and a
  redelivery hits `insertOrIgnore → false` → `ackIfTerminal` → `else -> Unit`
  (`SecureCommandPoller.kt:290-294`): **no execution, no ACK**, so GMweb's ledger hangs
  indefinitely.
- `remote_command_executions` — the declared "one row per execution attempt" exactly-once
  ledger — is **never written**: `RemoteCommandExecutionDao.insert` has no caller
  **[verified]**. The audit trail the entity exists for does not exist.

### Blocker 16 — The strategic command poller would ignore consent **[verified]** (LATENT — poller not started)

`SecureCommandPoller.kt:92` gates only on `!prefs.isEnabled || prefs.gmwebUrl.isBlank()`.
It does **not** check `hasGatewayConsent`, unlike the legacy poller which uses
`GatewayAccessPolicy.canTransmit(prefs.hasGatewayConsent, prefs.isEnabled)`
(`OutboxPoller.kt:242`, `GatewayAccessPolicy.kt:5`).

Consequence: revoking gateway consent stops the legacy bridge but **not** the strategic
command channel — a consent-revoked device still polls for and executes remote commands.
Mission §73/§76 require that revocation blocks sync; this is the command-side of that gap.

### Blocker 17 — Consent and the gateway switch do not gate ENQUEUEING, only uploading **[verified]**

`TelephonySyncCoordinator.kt:99` declares `internal var syncAllowed: Boolean = true`, and
`:1142` guards event creation with `if (!syncAllowed) return`.

`syncAllowed` is **never assigned in production** — the single assignment in the whole repo is
`app/src/androidTest/.../EncryptedHistoryDeviceTest.kt:32` **[verified]**. So the gate is
permanently open: events are built, encrypted and committed to `gateway_event_outbox` even when
gateway consent is revoked or the gateway is switched off.

The only real gate is downstream in `EventUploader.kt:117-121`. Practically that means
revoking consent leaves rows accumulating in the outbox that nothing will ever upload, and the
firewall/eligibility decision is made on a path that does not consult consent at all.
Mission §41 and §73 want the crypto/consent boundary to hold **before** anything is persisted.

### Blocker 18 — The documented "single choke point" is dead code **[verified]**

`SyncEligibility.kt:3-19` describes itself as "The single choke point through which every cloud
event must pass ([TelephonySyncCoordinator.enqueueCloudEvent])" and states the invariant
"LOCAL_ONLY ⇒ zero rows in gateway_event_outbox, ever, even transiently".

`SyncEligibility.decide` has **zero call sites in `app/src/main`** — the only references are its
own file, its own unit test, and a comment at `TelephonySyncCoordinator.kt:93` **[verified]**.

The real gate in production is `SensitiveMessageFirewall` (`TelephonySyncCoordinator.kt:1147-1208`).
So a security boundary that documents itself as the mandatory choke point is not on the path at
all. Either it should be wired in or the claim removed; today the code asserts an invariant it
does not enforce.

### Blocker 19 — Crypto-key readiness is never pre-checked **[verified]**

`DeviceIdentity.isEnrolled()` (`:61`) and `keystoreHealthy()` (`:92`) have **no call sites in
`app/src/main`** — the only reference is an instrumented test **[verified]**.

So a missing/rotated Keystore key is discovered only when signing fails at
`AgentAuth.kt:55-60`, which returns `false`, which `ControlPlaneClient.kt:55-57` turns into a
`Failure` with `httpStatus = null`, which `EventUploader.kt:404-406` retries forever with no
durable reason and no gate. This is the mechanical shape of Blocker 6, and it is why
`MissingCryptoKey` has to become a first-class blocker in Phase 1 rather than a retry.

### Blocker 20 — Sensitive values reach logs **[verified]**

Confirmed by reading each line:

| Site | Leak |
|---|---|
| `OutboxPoller.kt:307` | `onLog("📨 Pulled " + task.requestId + " → " + task.to)` — **full recipient phone number** into the in-app log flow (`GatewayService.kt:146` → `GatewayViewModel.kt:312-317`) |
| `GatewayScheduler.kt:196` | `Log.i(TAG, "Scheduled SMS $scheduleId -> $phone sent")` — full phone number to logcat |
| `GatewayViewModel.kt:433`, `:449` | logs 7 leading + 4 trailing characters of the **gateway API key** |
| `EventUploader.kt:368` | raw server response body via `result.error` (`BackendClient.kt:110`) into logcat, unbounded |

Mission §43 forbids exactly these ("Never log: message body, full phone number, private keys,
authentication token"; phone number, if required, as `***1234`).

Mitigating context, stated fairly: the in-app `logs` list is not currently rendered by any
screen and `shareLogs()` (`GatewayViewModel.kt:600-616`) has no call site, so the phone number
at `OutboxPoller.kt:307` is captured in memory but not yet exported. **No message body or
ciphertext logging was found anywhere** in the gateway or sync code, and `OutboxPoller.trace`
deliberately excludes `to`/`text` (`:663-681`). So this is a real but bounded leak set.

### Blocker 21 — A dark bridge still reports CONNECTED **[verified]**

`ConnectionSupervisor.kt:376` starts the delivery poller **only if**
`prefs.gmwebServerOrigin.isNotBlank()`, but the state assignment afterwards (`:389-396`) sets
`CONNECTED` regardless. So a blank server origin yields no poller and a green CONNECTED state —
a silently dark bridge. Combined with Blocker 2 (`EventUploader.running` true while gated) this
is the concrete form of the mission's "do not assume CONNECTED means SYNCED".

The health model already separates these correctly (`GatewayHealthRules.overall` requires a
fresh bridge poll before HEALTHY, `GatewayHealth.kt:367-377`). The *supervisor state*, the
persistent notification (`GatewayService.kt:230-241`, `POLLING -> "GMweb bridge: live"`) and the
legacy heartbeat card (`GatewayViewModel.kt:329-335`, `GatewayScreen.kt:1079`) do not.

---

## CURRENT SILENT RETURNS

Every guard that returns without an observable, durable reason.

| # | Site | Condition | What is observable |
|---|---|---|---|
| 1 | `EventUploader.kt:94` | `job != null` | nothing — no log, no state |
| 2 | `EventUploader.kt:117-132` | gate not ENABLED | log only on **change**, RAM-only, 30 s loop; `running` still true |
| 3 | `EventUploader.kt:142-147` | `drainHistoryGrants()` throws | `Log.e` only (deliberate) |
| 4 | `EventUploader.kt:148-154` | claim throws | `Log.e` only, no reason on any row |
| 5 | `EventUploader.kt:283` | `submitted.isEmpty()` | none — mapped to `ALL_ACKED`, and may then trigger `requestCloudBackfillForLinkedDevice("outbox-drain")` (`:165-168`) on a batch that transmitted nothing |
| 6 | `ControlPlaneClient.kt:44-46` | non-HTTPS origin | infinite requeue, `httpStatus=null`, no row reason |
| 7 | `ControlPlaneClient.kt:55-57` | signer false (incl. Keystore unavailable) | infinite requeue, no row reason — **the missing-key case** |
| 8 | `ControlPlaneClient.kt:71-74` | DNS/connect/TLS/timeout | infinite requeue, no row reason; classified `NONE` because `classify(httpStatus=null)` has no throwable/phase (`EventUploader.kt:370`) |
| 9 | `GatewaySync.kt:193-197` | `markAcked` on a non-SENDING row | 0 rows changed, silently; return ignored except for a counter |
| 10 | `GatewaySync.kt:200-220` | `markRetry`/`markDead` on a non-SENDING row | same |
| 11 | `TelephonySyncCoordinator.kt:1142` | `!syncAllowed` | **no log at all** |
| 12 | `TelephonySyncCoordinator.kt:1211` | event already queued | silent dedupe (`idOf` pre-check) |
| 13 | `TelephonySyncCoordinator.kt:1207` | `LOCAL_ONLY` policy | logged (`:1197-1206`) — acceptable, this is intentional skip |
| 14 | `TelephonySyncCoordinator.kt:1214` | key unwrap throws | **event never inserted, nothing durable** |
| 15 | `ContactsSyncPublisher.kt:101` | disabled / not registered / no permission | completely silent |
| 16 | `TelephonySyncCoordinator.kt:1339-1349` | history crawl throws (incl. SECURITY) | `Log.e` only; `historyBackfillComplete` stays false forever |
| 17 | `TelephonySyncCoordinator.kt:474-478` | sync source failure | capped backoff + log only; `PendingReconciles.fullSyncState/tailState/threadState` have **zero callers** |

Rows 5, 6, 7, 8, 9, 10, 11, 14 are the ones the mission's §8 explicitly forbids ("Remove silent
logic such as `if (!identityRegistered) return`... Every blocked state must be observable").

---

## CURRENT DATABASE STATES

Room version **18** (`MessagesDatabase.kt:83`), 24 entities. Replication-relevant:

### `gateway_event_outbox` — the durable event log

Present (`GatewaySync.kt:82-116`): `id`, `eventUuid` (UNIQUE), `eventType`, `aggregateId`,
`messageId`, `revision`, `sortKey`, `priority`, `historySource`, `historyGeneration`,
`historyOrdinal`, `historyDate`, `historyProviderId`, `sequenceLocal`, `ciphertext`, `encoding`,
`schemaVersion`, `cryptoVersion`, `createdAt`, `attemptCount`, `nextAttemptAt`, `state`,
`serverSequence`, `ackedAt`, `failureCategory?`, `failureHttpStatus?`, `lastAttemptAt?`,
`deadLetteredAt?`, `failureAppVersion?`.

Missing vs mission §9: **`source`** (event origin is never stored, only logged),
**`key_ref`**, **`batch_id`**, **`last_error_code`**, **`last_error_message_safe`**,
**`in_flight_since`**, **`lease_id`**. (`last_attempt_at` exists; `last_http_status` exists
only as `failureHttpStatus`, written only when dead-lettering, never on a retry.)

States: `PENDING`, `SENDING`, `ACKED`, `DEAD_LETTER` — 4. Mission §11 wants 5 with
`RETRY_WAIT` distinct from `READY`; here retry-wait is `PENDING` + `nextAttemptAt`.

Indices: `UNIQUE(eventUuid)`, `(state,nextAttemptAt)`, `(state,priority,nextAttemptAt)`,
`(historySource,historyGeneration,historyOrdinal)`, `(aggregateId)`.

### `remote_commands` — the durable command inbox

`GatewaySync.kt:378-412`. `commandId` (PK), `type`, `ciphertext`, `encoding`, `schemaVersion`,
`cryptoVersion`, `signature`, `senderDeviceId`, `issuedAt`, `receivedAt`, `expiresAt`, `nonce`,
`idempotencyKey` (**UNIQUE index**), `state`.
States: `RECEIVED`, `ACCEPTED`, `EXECUTING`, `COMPLETED`, `FAILED`, `EXPIRED`.

Missing vs mission §45: **`client_message_id`**, **`attempt_count`**, **`claimed_at`**,
**`lease_id`**, **`lease_expires_at`**, **`executed_at`**, **`completed_at`**,
**`result_event_id`**, **`last_error_code`**.

`remote_command_executions` (`:456-478`): `id`, `commandId`, `attempt`, `startedAt`,
`finishedAt`, `result` — the exactly-once ledger, uniqueness enforced by callers.

### `cloud_history_checkpoint`

`GatewaySync.kt:347-359`. `source` (PK), `generation`, `producerCursorDate`,
`producerCursorProviderId`, `nextOrdinal`, `ackedContiguousOrdinal`, `ackedCursorDate`,
`ackedCursorProviderId`, `sourceExhausted`, `updatedAt`. This is the compound cursor §27 asks
for, and it exists.

### `sync_state`

`Entities.kt:282-297`. Per source: `oldestDate`, `oldestId` (compound keyset watermark,
sentinels `Long.MAX_VALUE`), `historyBackfillComplete`, `initialWindowReady`.

### `trusted_devices` / `trust_statement_outbox` / `device_telemetry`

`TrustedDevices.kt`. Note this table describes **linked web devices**, not this phone.
`historyGrant` is per-web-device (`FULL_HISTORY` | `FROM_NOW_ON`) and governs which browsers
receive the History Master Key (`ConversationKeyRepository.kt:59-64`, `:131`). `status` includes
`REVOKED`, and `revokedAt` is recorded.

### `integrity_audit_state`

`Entities.kt:252-268`. Durable, resumable audit cursor per (source, direction).

---

## CURRENT HISTORY FLOW

`TelephonySyncCoordinator.backfillOlderKeyset(source)` (`:1566-1595`), launched detached by
`scheduleBackfill()` (`:1331-1362`) on a dedicated `MIN_PRIORITY` single thread (`:1314-1323`).

**Algorithm** — a durable downward keyset crawl, not ordinal-based:

```text
read 500 rows strictly OLDER than (oldestDate, oldestId)
  → ingest (own db.withTransaction)
  → advanceOldest          (separate statement)
  → repeat
break on empty/short page → markHistoryComplete
```

Query (`:1633-1639`), SMS:

```kotlin
selection = "(${Telephony.Sms.DATE} < ?) OR (${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} < ?)"
sortOrder = "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC"
```

`LIMIT` rides the provider URI (`SmsRepository.kt:327-330`), not SQL. MMS is the twin
(`:1641-1646`) with `DATE/1000L`. Page sizes: `FIRST_BATCH = 500`, `BACKFILL_BATCH = 500`,
thread repair 200, integrity audit 100, exact reads 1. **This satisfies §27 and §29** — the
compound `(date, providerId)` cursor and bounded paging are already right.

**Memory** — worst case is one page of ≤500 `Sms` objects (`SmsRepository.kt:337-339`); 360k
messages ≈ 720 SMS pages with page-sized residency. §29's `loadAllMessages()` prohibition holds
on the sync path. The one unbounded provider load is `getSmsWithFilters(limit = null)`
(`SmsRepository.kt:592-600`) reached from `GatewayServer.kt:420` — a UI/REST path, **not** the
scanner, but a real OOM risk at 360k and worth fixing under §71.

**Resume** — correct. `historyBackfillComplete` short-circuits (`:1569`), the next page uses the
persisted `(oldestDate, oldestId)`, and a crash between ingest and `advanceOldest` only
re-covers one batch, absorbed by the `UNCHANGED` idempotent skip. §31 holds.

**Checkpoint transactionality (§30)** — *not* satisfied for the provider crawl: ingest commits
in its own transaction (`:901-1074`) and `advanceOldest` (`:1587`) / `markHistoryComplete`
(`:1592`) are separate statements. The direction of the race is safe (cursor can lag, never
lead), so nothing is lost — but it is not the same transaction the mission asks for. The
**cloud** side is correct: `enqueueHistorical` runs inside the page transaction with the cursor
upsert (`:1478-1506`).

**SMS vs MMS (§28)** — independent. Per-source `sync_state`, per-source
`cloud_history_checkpoint`, per-source in-flight guards, sequential with separate try/catch
(`:1336-1353`). An MMS failure cannot restart SMS.

**One start-up race** — `backfillOlderKeyset` marks history complete on an *empty first page*
(`:1583`, `:1592`). If the provider is transiently readable-as-empty the source is declared
complete. Permissions failures throw, so this needs a genuinely empty provider, but it is worth
guarding.

**Realtime coexistence (§32)** — separated by thread, but they contend for the Room writer: one
history batch wraps up to 500 rows in one transaction, and observer thread repairs / `TailDelta`
share `reconcileMutex` with `FullSync` (`:539-542`), so a startup `FullSync` or
`fullRebuildConversations()` (`:2122-2179`) delays realtime thread repairs. Exact-row repairs
still proceed on `ChangeRouter`'s own scope. The **upload** side does prioritise realtime
(Blocker 11 notwithstanding).

---

## CURRENT COMMAND FLOW

Inbound: `OutboxPoller` / `SecureCommandPoller` pull from GMweb → verified → **committed to
`remote_commands` before any ACK** (`GatewaySync.kt:374-376`) → `CommandCrypto` decrypts
(inbound only; it never encrypts outbound) → executor → `SmsManager` / `MmsSender` → result
events into the same outbox.

**Idempotency at ingest is real**: `Index(value = ["idempotencyKey"], unique = true)`
(`:383`) plus `commandId` as PK, so a redelivered command is a no-op insert. This is §47's
first half, and it exists. A duplicate delivery takes `ingest → false` →
`ackIfTerminal`, and never reaches `execute` (`SecureCommandPoller.kt:109-119`).

### Two web-command transports exist; one is active, and they do not share an identity

| | Strategic | Legacy |
|---|---|---|
| Pull | `POST /api/v1/agent/commands/claim` (`SecureCommandPoller`) | `GET /gateway/pull` (`OutboxPoller`) |
| Durable store | `remote_commands` (Room) | `EveSmsQueue` → **SharedPreferences** (`:212-257`) |
| Dedupe key | `commandId` + `idempotencyKey` | `gatewayRequestId` (in-memory maps `:387-396`) |
| Dedupe durability | durable (unique index) | **memory only**; disk keeps the last 300 records (`:46`, `:239`) |
| Consent gate | none (`:92`) — but see next row | `GatewayAccessPolicy.canTransmit` (`OutboxPoller.kt:242`) |
| **Started in the shipped build?** | **NO** — `deliveryIntake` defaults to `LEGACY_PULL` and the command poller is explicitly stopped | **YES** — the single active intake |

Only one intake owner runs at a time, selected by the `deliveryIntake` switch
(`ConnectionSupervisor.kt:117`, `:373-382`). See Blocker 13 — the strategic column is
**latent**: those behaviours become live only when the switch is flipped.

### Command states and what is missing

States: `RECEIVED`, `ACCEPTED`, `EXECUTING`, `COMPLETED`, `FAILED`, `EXPIRED`
(`GatewaySync.kt:406-411`). `EXPIRED` is **never written** — `expireStale` has no caller
(Blocker 15).

Missing versus §45: `client_message_id`, `attempt_count`, `claimed_at`, `lease_id`,
`lease_expires_at`, `executed_at`, `completed_at`, `result_event_id`, `last_error_code`.
Present: `command_id`, `command_type`, `encrypted_payload`, `state`, `received_at`.

`clientMessageId` threads through correctly **on the strategic path**:
`GatewayOutgoingPipeline.kt:149` → `SmsSender.kt:142-144` → `:96` → `MessageMutation.kt:20` →
`TelephonySyncCoordinator.kt:265,657,951` → `GatewayEventFactory.kt:138`. §49 works there.

It is **dropped on the legacy path** (the default-active one): `OutboxPoller.Task` carries no
such field (`:699-705`) and `EveSmsQueue.enqueue` has no such parameter (`:378-385`), so
`GatewayServer.kt:132` calls the 2-arg `sendForResult` and `clientMessageId` is always null.
It is also absent from `MESSAGE_STATUS_CHANGED` entirely
(`GatewayEventFactory.kt:155-186` has no such parameter) — so even on the strategic path a
status change cannot be tied back to the web bubble. §49 is therefore **half-implemented**:
created events can merge, status events cannot.

The web command's declared `messageId`/`threadId` (`GatewayOutgoingPipeline.kt:70`) are never
read back (`:128-132` reads only phone/body/subscriptionId/clientMessageId), so the declared
message identity is discarded.

> **RESOLVED (Phase 5/6).** The `clientMessageId` half is fixed — see "§49: making the
> optimistic-bubble key survive the whole round trip". The strategic path now captures the key at
> intake and carries it on `MESSAGE_STATUS_CHANGED`, not only on creation. The legacy pull path
> still carries none (`OutboxPoller.Task` has no such field), and the declared `messageId`/`threadId`
> are still not read back: as noted in the resolution, the phone's authoritative identity for a sent
> message is its own provider row, and consuming a client-supplied id as a local identity is the
> kind of trust the mission forbids.

### Multi-SIM

Strategic path: supported (`GatewayOutgoingPipeline.kt:130-132`, honoured `:145`).
Legacy path: **absent** — no `subscriptionId` anywhere in the task model, so it silently falls
back to `prefs.sendSubscriptionId` then the platform default (`SmsSender.kt:272`, `:456-460`).
`requestedSubscriptionId` / `actualSubscriptionId` do not exist at all. `SIM_NOT_AVAILABLE` is
declared (`SendState.kt:94`) and **never constructed**. Worse, on API < 31 the requested SIM is
ignored outright (`SmsSender.kt:461-467` uses `SmsManager.getDefault()`) while the segment
ledger still records the requested id as if it had been used. Mission §50 ("do not silently
send using another SIM") is violated on both the legacy and the pre-31 paths.

> **RESOLVED (Phase 6).** Both halves are fixed: the pre-31 branch selects the requested SIM, the ledger
> records the read-back rather than the request, and `SmsSendFailure.SimUnavailable` is now constructed
> (the Android `SIM_NOT_AVAILABLE` equivalent). See "§50: the selected line was ignored". The legacy
> path's task model still carries no `subscriptionId`, so a web request cannot *name* a SIM — that
> remains open, and it is a missing feature rather than a silent wrong-SIM send.

### MMS

`MmsSender.sendImage/sendAudio/sendGroupText` exist (`:35`, `:55`, `:81`) and are reachable
only from the device-local REST endpoint `POST /api/v1/mms/send` (`GatewayServer.kt:395-417`) —
**not** from any web command: the strategic type gate is
`setOf("SEND_SMS", "MARK_THREAD_READ")` (`SecureCommandPoller.kt:207`), and every other type is
dropped silently after being durably ingested (no ack, no drain — see Blocker 15).

`triggerSend` passes a **null callback** to `sendMultimediaMessage` (`:215`), so no local send
outcome is ever recorded; attachments are never replicated through the event pipeline; and the
MMS path produces no outbox row, no command row, no lease and no `send_segments` entry. Silent
drops: a null `openOutputStream` still returns success and triggers the send (`:153`, `:125`,
`:178-180`), and `compressImage` returns `ByteArray(0)` on decode failure (`:235-237`).
This is exactly the §52 case that "must be documented clearly instead of silently dropping it".

> **PARTLY RESOLVED (Phase 6).** The null callback is fixed — the send result now has a receiver, a
> classified outcome and a published status change, so an MMS that never left the device is no longer
> indistinguishable from one that did. The silent payload drops are fixed too: the payload is built and
> judged **before** any provider row exists, and an incomplete row this app created is rolled back. See
> "MMS: the send result had nowhere to arrive" and "a `?.` that turned a failed write into a success".
> Still open: MMS is not reachable from a web command (the strategic type gate is `SEND_SMS` /
> `MARK_THREAD_READ`), and attachments are not replicated as assets.

### Internal send state that is dead code, not tracking **[RESOLVED]**

`SendState` (QUEUED/DISPATCHING/…/FAILED) was neither persisted nor used: its only consumer
`SmsStatusPolicy.aggregateSendState` had **no caller in `app/src/main`** (a unit test was the only
reference), so it must never have been reported as tracked send state.

**Resolved in the Production Validation goal, Workstream H: removed rather than wired.** Wiring was
rejected because no consumer exists to wire it *to* — a scan of `ui/` finds no reader of
`SEND_UNCONFIRMED` / `SENT_CONFIRMED` / `DISPATCHED`, nothing persisted a `SendState`, and the
GMweb ACK contract (`sent | failed | superseded`) cannot carry the distinction without a
cross-repository protocol change. Storing a value nothing reads would have recreated the same
unwired abstraction one layer down. The durable per-part evidence it claimed to add already exists
as `send_segments.callbackState`, and `SmsStatusPolicy.nextStatus` is the single derivation from it.
`theCallbackEvidenceToStatusDerivationHasExactlyOneDefinition` now pins that there is exactly one,
and `docs/sms-delivery-architecture.md` — which described the removed machine as implemented — was
corrected.

### A separate, concrete duplicate-send risk: `GatewayScheduler` **[verified]**

Not the command inbox — this is the gateway REST scheduled-send feature
(`POST /api/v1/sms/schedule`), which is exactly "messages requested from GMweb/Web".

Two problems in `GatewayScheduler.kt`:

1. **Plaintext at rest.** It persists `phone` and `message` in cleartext SharedPreferences
   (`PREFS = "gateway_schedule_prefs"`, keys `phone`/`message`, `:31`, `:34-35`, `:82-94`).
   The repo already has `SecureStore.encrypt` used elsewhere in `GatewayPreferences`, so an
   encrypted store is available and simply not used here. Mission §41/§43/§58 forbid plaintext
   message content outside the encrypted path and forbid storing phone numbers/body in
   diagnostics-adjacent state.
2. **Retry can double-send.** `SendWorker.doWork` (`:181-210`) calls
   `SmsSender.sendForResult(phone, message)` and, if it returns `null`, returns
   `Result.retry()` up to 3 times. The only guard against a second send is
   `current.status != "scheduled"` (`:188`). If the platform actually accepted the SMS but the
   send call failed to return an id — or the process died after the platform accepted it and
   before the status update — the retry re-sends. Mission §47/§78: "No remotely requested
   SEND_SMS command may cause the same SMS to be sent twice because of retries."

---

## CURRENT RETRY POLICY

`EventUploader.kt:366-408` plus `GatewaySyncRepository.Policy` (`:38-45`).

| Response | Current behaviour | Mission §17 asks |
|---|---|---|
| 2xx | parse `accepted[]`; ACK those with `serverSequence > 0`; retry the rest | per-item results |
| 200 with `duplicates` | counted for logging only; duplicate rows **re-uploaded forever** | DUPLICATE → ACKED |
| 200, item lacks `serverSequence`/0 | treated as not accepted → requeued forever | ACCEPTED → ACKED |
| 401 / 403 | **whole batch DEAD_LETTER** | `BLOCKED_AUTH`, wait for recovery, do not dead-letter |
| 409 | **whole batch DEAD_LETTER** (classifier calls it transient) | treat as duplicate where compatible |
| 413 | **whole batch DEAD_LETTER**, classified `HTTP_BAD_REQUEST` | split batch; single oversized → DEAD_LETTER `PAYLOAD_TOO_LARGE` |
| 429 | requeued; **`Retry-After` never read** | honour `Retry-After` |
| 5xx | requeued, backoff | exponential backoff + jitter |
| timeout / connectivity | requeued; classified `NONE` (no throwable/phase passed) | retryable, attributed |
| permanent schema error | per-row DEAD_LETTER, excluded from batch (`:247-258`) | only affected event — **already correct** |
| transport exhaustion | retries forever; **no attempt cap, no dead-letter** | capped |

Backoff (`GatewaySyncRepository.kt:38-45`): base 2000 ms, cap 300 000 ms,
`ceiling = min(cap, base shl attempt.coerceIn(0,20))`, delay = `random.nextLong(0, ceiling)`
— **full jitter**, no persisted seed, no decorrelation. Two independent delay sites: the loop
(`EventUploader.kt:171-174`) and the per-row `nextAttemptAt` (`:286`, `:322` →
`GatewaySyncRepository.kt:117-119`).

Batch limits exist for **both** count and bytes (`MAX_BATCH_EVENTS = 100`,
`MAX_BATCH_BYTES = 512 KiB`, `:35-36`, enforced `:50-61`) — §14 is partly satisfied. Two
caveats: bytes are measured on the opaque envelope, not the base64 wire form (~+33 %), and a
single event larger than the cap is still admitted when it is the first candidate
(`if (events.isNotEmpty() && ...)`), so one oversized event can reach the wire and trigger the
413 path above. Limits are hardcoded; there is no capability endpoint (§62), and
`EventCryptoPolicy.kt:9-10` declares a **second, unused** copy of the same two constants.

Per-event caps that effectively tighten the byte budget: `MAX_CONTENT_BYTES = 64 KiB`,
`MAX_CONTROL_BYTES = 128 KiB` (`EventCryptoPolicy.kt:11-12`), applied at `:50-51`.

---

## SECURITY POSTURE (verified against the mission's prohibitions)

**Holds:**

- **No plaintext fallback for content.** `EventCryptoPolicy.kt:14-25` maps content types to
  cryptoVersion {1,2,3}; `:44-46` requires it. A cryptoVersion 0 content row fails
  `validateForUpload` at `EventUploader.kt:247` and is dead-lettered (`:250-256`) — never
  transmitted. Fail-closed, structurally, not by convention. §41 satisfied.
- **No destructive migration** for this data: `MessagesDatabase` registers explicit migrations
  and the destructive fallback is debug-only. New columns arrived via `MIGRATION_17_18` as
  five nullable `ADD COLUMN`s.
- **Key material never leaves** unencrypted; DEKs are HPKE-wrapped
  (`MessageCrypto.kt:115-124`) and the CKE is Keystore-protected (`ConversationKeyVault.kt`).
- **No trust-all TLS**: the only `TrustManager`/`HostnameVerifier` mentions in main source are
  the two comments documenting the prohibition.

**Gaps:**

- `validateEncryptedEnvelope` (`EventCryptoPolicy.kt:55-68`) checks envelope *labels* and field
  sizes only. That is arguably correct for an upload path (the client cannot MAC-verify without
  decrypting), but it means a mis-encrypted payload is discovered by the server, not locally.
- `GatewayScheduler` plaintext at rest (see above).
- Missing-key and permission failures are indistinguishable from network failures in every
  durable record (Blockers 6 and 8).

---

## WHAT THE MISSION ASKS FOR THAT IS ABSENT

**Entirely absent — confirmed by repo-wide search:**
`ReplicationPrerequisiteEvaluator`, `ReplicationBlocker`, `ReplicationState`,
`HistorySyncState`, `CommandSyncState`, `ControlChannelState`, `SyncErrorCode`.

**Workers (§36):** the five named unique workers are absent. WorkManager is used for
`scheduled-send`, `classification-backfill`, `asset-backfill`, `otp_cleanup`, `trash_purge`
only. Replication runs in coroutine loops inside the `GatewayService` foreground service.

**Boot recovery (§39)** — `BootGatewayReceiver.kt` exists but only re-checks
`gatewayDesiredEnabled && hasGatewayConsent` and starts the service (`:23`). It does not
recover stale leases (none exist), evaluate prerequisites, resume a history session
explicitly, or schedule reconciliation — those happen incidentally inside
`startGatewaySync()`.

**Capability negotiation (§62)** — absent; limits are hardcoded.

**Diagnostics V2 (§56-§58)** — the existing
`GatewayDiagnosticReport` covers application/gateway/replication basics (gateway desired,
queue depths, dead-letter breakdown). Absent: identity (registered/account/authorized/revoked),
per-state outbox counts (READY/IN_FLIGHT/RETRY_WAIT), oldest pending event age, batch
accepted/duplicate/rejected counters, history session + per-source scanned/enqueued/acked/
pending/skipped/failed + checkpoint, command counters, a single actionable machine-readable
blocker, and sanitized JSON export.

---

## PROPOSED PHASE 1 (observability only — no behaviour change to the data path)

This is what I intend to build next, in this order. It adds types and reporting; it does not
re-route a single event.

1. `sync/SyncErrorCode.kt` — the mission's §44 taxonomy as an enum; replaces ad-hoc strings as
   business state.
2. `sync/ReplicationBlocker.kt` + `sync/ReplicationPrerequisites.kt` — sealed blocker set and
   the `canUploadRealtime` / `canUploadHistory` / `canReceiveCommands` / `blockers` result.
3. `sync/` state enums — `ControlChannelState`, `ReplicationState`, `HistorySyncState`,
   `CommandSyncState`.
4. `sync/ReplicationPrerequisiteEvaluator.kt` — **pure function over an input snapshot**
   (prefs-derived flags, permission state, TrustedDevices grant, key availability, network).
   Pure so it is JVM-testable and so every caller answers the question identically.
5. Rewire the *readers* to it — `EventUploader`'s `UploadGate`, diagnostics, supervisor — while
   keeping today's control flow identical, so a regression in Phase 1 shows up as a changed
   *report*, never a changed *send*.
6. `sync/diagnostics/SyncDiagnostics*.kt` — the §56 model, one actionable blocker (§57), and
   sanitized JSON export (§58), surfaced through the existing diagnostic report.
7. Tests: every prerequisite combination from §67, plus a test proving each currently-silent
   return now yields a named blocker.

Phase 2 (Outbox V2) is deliberately **not** started until Phase 1 reports what actually happens
on the device, per the mission's own ordering and the instruction not to jump to Phase 2.

---

## QUESTIONS THAT NEED AN ANSWER BEFORE PHASE 1 IS FINAL

These are genuine ambiguities in the mission text against this codebase. I have a defensible
default for each, but guessing wrong would bake a wrong blocker into the taxonomy.

1. **`MissingHistoryGrant` semantics.** In this repo `historyGrant` is a per-**linked-web-device**
   permission (`FULL_HISTORY` | `FROM_NOW_ON`) controlling who receives the History Master Key
   — not an Android permission. So "no web device has requested FULL_HISTORY" is the natural
   reading. The mission's §40 wording ("History backfill blocked: FULL_HISTORY_GRANT_MISSING",
   alongside `TELEPHONY_PERMISSION_MISSING`) could instead mean the Android side lacks
   full-history access. **Default I will use unless told otherwise:** blocker =
   "no active linked device holds `FULL_HISTORY`", with Android permission failure reported
   separately as `TelephonyPermissionMissing`.
2. **`DeviceRevoked`.** `trusted_devices.status = REVOKED` refers to a linked **web** device,
   not this phone. There is no local model of "this Android device was revoked by the server".
   **Default:** `DeviceRevoked` fires when the control plane reports this device's credential
   rejected/identity unknown, and the linked-device table is reported separately as
   diagnostic detail.
3. **Per-item ACK and `Retry-After` (§15/§17) require a server contract that does not exist
   yet.** `EventUploader` parses `accepted[]`; the mission's `results[]` with
   `ACCEPTED`/`DUPLICATE`/`REJECTED` and a capabilities endpoint are GMweb V2. Until GMweb V2
   is available, Phase 2 must keep the legacy parse working (§62 "do not break the current
   production server") and negotiate. **I need to know whether the V2 server contract is
   available to build against**, or whether I should implement the client side against the
   mission's example schema and keep legacy fallback.
4. **Workers vs the existing foreground service (§36).** The service already runs durable
   coroutine loops with an invalidation-driven wake channel. Adding five WorkManager workers
   alongside risks two uploaders. **Default:** migrate responsibilities into workers behind the
   existing single-instance guards rather than running both — but this is a Phase 2/3 decision
   and I will not touch it in Phase 1.

---

## SUMMARY — the live safety violations, ranked

The mission's two hard invariants (§78) are:

> No SMS/MMS already known to the device may be silently lost…
> No remotely requested SEND_SMS command may cause the same SMS to be sent twice…

Current state against them, with each finding's status:

| # | Finding | Live? | Severity |
|---|---|---|---|
| 1 | Realtime detection only while a ViewModel is alive → no background replication | **LIVE** | Critical — silent loss |
| 14 | `EveSmsQueue` requeues an interrupted `ACTIVE` send on restart → **second physical SMS** | **LIVE** | Critical — §78 double-send |
| 17 | Consent/disable does not gate enqueueing (`syncAllowed` never set) | **LIVE** | High — §41/§73 |
| 12 | Nothing reconciles the outbox → lost events are never re-created | **LIVE** | High — silent loss |
| 5 | `DUPLICATE` never ACKed → infinite re-upload; mission Test F cannot pass | **LIVE** | High |
| 4 | Any 4xx≠429 dead-letters the whole batch; 401/403 mass dead-letter | **LIVE** | High |
| 3 | No lease / no stale-in-flight timeout | **LIVE** | High |
| 6 | Missing key = infinite invisible requeue | **LIVE** | High |
| 19 | Crypto-key readiness never pre-checked (`isEnrolled` uncalled) | **LIVE** | High |
| 7 | Realtime vs history identity divergence (§33 race) | **LIVE** | High |
| 8 | Missing telephony permission fails silently forever | **LIVE** | High |
| 11 | BACKFILL can be starved indefinitely | **LIVE** | Medium |
| 10 | No outbox retention (unbounded growth) | **LIVE** | Medium |
| 9 | SCAN_COMPLETE conflated with SYNC_COMPLETE in the only observable | **LIVE** | Medium |
| 20 | Phone numbers / API-key fragments in logs | **LIVE** | Medium — §43 |
| 21 | Dark bridge still reports CONNECTED | **LIVE** | Medium |
| 18 | `SyncEligibility` documented choke point is dead code | **LIVE** | Medium |
| — | `GatewayScheduler` plaintext phone+body, retry can re-send | **LIVE** | Medium |
| 15 | Strategic commands lost (no lease, no drain, `expireStale` uncalled) | latent | High when intake switches |
| 16 | Strategic poller ignores consent | latent | High when intake switches |
| 13 | Stale ownership comment vs `DEFAULT_CONTROL_PLANE_SENDS` | latent | docs only |

Two findings from the deep-dive passes were **rejected on verification** and are recorded here
so they are not repeated:

- "Both send transports run concurrently → cross-transport duplicate send." **False.**
  `deliveryIntake` defaults to `LEGACY_PULL` and the command poller is explicitly stopped
  (`ConnectionSupervisor.kt:375`); `CONTROL_PLANE_COMMANDS` is selected nowhere. See Blocker 13.
- "The outbox `markDead` state guard is a live production risk." It is real but **narrow**:
  both production call sites act on claimed (SENDING) rows, so only a direct DAO call on a
  non-SENDING row no-ops (as `HistoryAckCheckpointDeviceTest` now silently does).

### What I would fix first, and why it is not Phase 1

Blocker 14 (restart re-send) and Blocker 1 (no background replication) are the two live
violations of §78, and both are **behavioural**, not observational — exactly the kind of change
the mission says to make only after the audit and per-phase. Phase 1 will *name* them
(`DuplicateSendRisk`, `RealtimeDetached`) so they are visible and measurable, but I do not
intend to change the send path or the observer registration until Phase 1's diagnostics can
prove the fix on a device.

If you want either of those two fixed **before** Phase 1 rather than surfaced by it, say so —
Blocker 14 in particular is a small, self-contained change (`submittedOnce` is already
persisted and simply is not read as a guard; the correct pattern already exists at
`DelayedSendExecutor.kt:104-111`).

### Scope note

All four deep-dive passes are complete and their load-bearing claims were independently
re-verified against the source before being written here; two claims did not survive that
check and are listed above as rejected. This document is the Phase 1 input, not a plan of
record for later phases.

---

## FINDINGS ADDED WHILE IMPLEMENTING PHASE 1

Two more defects surfaced only when wiring the evaluator to real state. Both were fixed in the
same change, and both are the same *shape* of bug the mission is about: a signal that means one
thing being read as though it meant another.

### Blocker 22 — The health registry never learns the device went offline **[FIXED]**

`ConnectionSupervisor.reconcile` publishes its health context only at the end of the ONLINE path
(`ConnectionSupervisor.kt:396`). The offline branch returns early at `:333`, so
`GatewayHealthRecorder.onNetwork(validated = false, …)` is never called and the registry keeps
the last online reading.

Consequence: while offline, the diagnostic report could print `Internet: validated`, and any
reader deriving "can we reach GMweb?" from `network.validatedInternet` got a stale `true`.

Fixed by publishing the health context on the offline path too, before the early return.

### Blocker 23 — `prefs.isEnabled` is a DERIVED gate, not the user's switch **[FIXED]**

`:389` writes `prefs.isEnabled = true` with the comment "runtime state — now DERIVED by the
supervisor, never clobbered elsewhere", and `:326` clears it whenever the device is offline. The
USER's intent lives in `prefs.gatewayDesiredEnabled` (`:168`, `:186`).

These are therefore different facts, and reading the first as the second produces a false cause:
an offline device with the gateway switched ON would be reported as
`GATEWAY_DISABLED` / "Turn the gateway on to resume replication" — telling the user to switch on
something that is already on. This is the v3.4.6 "rejected key" mistake in a new place.

Fixed: the readiness reader takes user intent from `gatewayDesiredEnabled`, and treats offline as
the supervisor's own declaration (`ConnectionSupervisor.State.WAITING_FOR_NETWORK`) rather than
the stale registry value.

Note the upload LOOP is deliberately unaffected by this distinction: it must still stop while
offline (the supervisor leaves the uploader component running and relies on a transmission gate
to keep the radio quiet), so `BlockerCategory.NETWORK` remains in the upload gate's holding set.

---

## PHASE 1 PROGRESS

| Item | State |
|---|---|
| Audit (§5) | **done** — this document |
| `SyncErrorCode` taxonomy (§44) | **done** — 21 codes, transport→outcome mapping |
| `ReplicationBlocker` + `ReplicationPrerequisites` (§8) | **done** — 10 blockers, priority + category |
| `ReplicationPrerequisiteEvaluator` (§8) | **done** — pure, every input required |
| Four state models + machine (§7) | **done** — incl. SCAN_COMPLETE ≠ CAUGHT_UP |
| `SyncDiagnostics` model + sanitized export (§56/§58) | **done** — populated from Room + prefs + health |
| One actionable blocker in the report (§57) | **done** — `Replication blocked: <CODE>` |
| `EventUploader` consumes the evaluator (§8/§64) | **done** — private `UploadGate` deleted; blocker now durable |
| History + command diagnostic sections (§56) | **done** — per-source history, checkpoint, command counts |
| `SyncDiagnostics` surfaced in the UI (§58) | **done** — "JSON" action copies the sanitized export |

**Phase 1 is complete.** Two independent guarantees now hold that did not before:

1. There is exactly ONE place that answers "may this device replicate, and if not why" —
   `ReplicationPrerequisiteEvaluator` — and the upload loop's decision is *proved* to agree with
   it for all 64 input combinations, rather than re-derived locally.
2. Every blocked state is observable and NAMED. The report leads with
   `Replication blocked: <CODE>` plus an action, and the upload loop writes its hold reason to the
   durable diagnostic log, so the cause survives the process that observed it.

### A redaction interaction worth knowing

The report's final redaction sweep tokenizes any 7–15 digit run, because it cannot distinguish an
epoch-millisecond value from a phone number. A history checkpoint printed as raw millis therefore
rendered as `id#…`. It is now printed as an ISO instant (matching the dead-letter section), which
is both readable and survives the sweep. Anything else added to this report that carries a
long digit run needs the same treatment.

### What Phase 2 needs, and what it does not

Phase 2 (Outbox V2) can start without further input, because mission §62 requires the client to
keep working against the deployed server: the ACK parser should accept the legacy
`accepted[]` shape AND the V2 `results[]` shape, choosing per response. The open questions only
affect which path is *preferred*, not whether the work can begin.

The items below are deliberately NOT done and are Phase 2's scope:

- **`BlockerCategory.KEY` does not hold the upload loop.** Reading it means a Keystore probe on
  every iteration, and it is nearly moot because encryption happens at ENQUEUE — a missing key
  usually means the outbox is empty, not that uploads are refused. The real damage there is
  silent event loss at enqueue (Blocker 6), which Phase 2 must address.
- **PERMISSION and GRANT do not hold realtime upload.** They gate history only; realtime upload
  never reads the Telephony Provider.
- **`deviceRevoked` has no producer.** No local model of this phone being revoked exists; the
  blocker is defined and tested but nothing sets it. A rejected credential reports
  `AuthenticationRequired` instead. Needs a control-plane signal.
- **`TrustedDevicePolicy` duplicates `ConversationKeyRepository`'s predicate.** Unifying them
  requires first adding a JVM test for the original, which has instrumented coverage only.
- **Batch counters are reported as "not recorded".** `accepted`/`duplicates`/`failed` are computed
  per upload and only logged, never stored, so the report says so rather than printing zeroes.

---

## PHASE 2 PROGRESS (Outbox V2)

| Item | State |
|---|---|
| Room migration v18 → v19 (§9) | **done** — 8 nullable columns, additive only, real migration test |
| Lease + `in_flight_since` (§12) | **done** — claimed under a lease, released on ACK/retry/dead |
| Age-bounded stale recovery (§12) | **done** — startup AND periodic (60 s), 5-minute configurable timeout |
| `RETRY_WAIT` as a real state (§11) | **done** — a failed-and-waiting row is now queryable |
| Per-item ACK, both shapes (§15/§16) | **done** — `results[]` (V2) and `accepted[]` (legacy) |
| `DUPLICATE` ⇒ ACKED (§16) | **done** — the loop that could not converge is closed |
| Only the affected event dies (§17/§18) | **done** for per-item REJECTED; batch-level contract failures still kill the batch |
| 401/403 ⇒ retry, not dead-letter (§17) | **done** |
| 413 ⇒ retry so the next batch is smaller (§17) | **done** |
| Attempt cap (§17) | **done** — 25 attempts, then visible as DEAD_LETTER (retained, rescuable) |
| Failure reason stored on the row (§9) | **done** — `lastHttpStatus`/`lastErrorCode`/`lastErrorMessageSafe` |
| Fair/weighted batching (§13) | **done** — 70/30 foreground/background, candidates fetched per group |
| Five priority levels (§13) | **done** — REALTIME 100, COMMAND_RESULT 90, STATUS_UPDATE 80, BACKFILL(=HISTORY) 20, RECONCILIATION 10 |
| `Retry-After` (§17) | **done** — parsed from the response and honoured verbatim, capped at 1 h |
| ACKED retention + cleanup worker (§19) | **done** — 48 h window, `gmweb-outbox-cleanup` periodic worker |
| `source` population (§10) | **done** — derived in the pure factory: command result > history > realtime |
| `batchId` population | **done** — written on claim, one per upload batch |
| `keyRef` population (§42) | **done** — stamped by the encryptor, with a rotation query that matches exactly |
| Trust predicate duplicated in the crypto path | **fixed** — one definition, and the fragile copy is gone |
| Agent signature contract (§57) | **pinned** — the canonical string is asserted literally; the KDoc had claimed a test that did not exist |

**Phase 2 (Outbox V2) is complete.** The last gap was `keyRef`, which is now populated — see below.

### §42: `keyRef`, a column that was written by nothing and read by nothing

It had the entity field, the schema-19 migration column, the KDoc stating its purpose ("so rotation can
be handled without re-reading the envelope") — and no writer and no reader. Exactly the shape this work
keeps finding: the *decision* was made and the *act* never happened.

**Two facts had to be settled before it could be populated honestly.**

*Which key?* A v3 message is encrypted under **two** — `historyKeyId` and `liveKeyId` — so a bare key
id would be a half-truth precisely where a rotation needs the whole answer. The stored form is
canonical and delimiter-separated, live first because it always exists:

```text
<liveKeyId>                    v2, and v3 rows with no history wrap
<liveKeyId>|<historyKeyId>     v3 message events
```

*Who writes it?* The encryptor, not the caller. `ConversationKeyRepository.encrypt` already holds both
epoch ids when it calls `MessageCrypto`, so a private `encrypted(...)` helper stamps `keyRef` from the
same arguments that produced the envelope. That makes the column and the ciphertext agree **by
construction** — and a test asserts the stronger version, that the stored value equals what parsing the
ciphertext back yields. A copy of a fact that *disagrees* with its source is worse than no copy: it
would send a rotation after the wrong rows.

The two key-grant sites are deliberately **not** stamped. A key grant is signed by
`PrimaryTrustRoot`, not encrypted, so there is no "key that encrypted it"; the key it *concerns* is
named inside its payload. Putting that id in the same column would give one column two meanings — the
`enqueued` mistake from the §70 session arithmetic, in a new place — so those rows keep `keyRef` null,
and the NULL group is reported rather than hidden.

**The query was the part with a real hazard.** A key id sits *inside* a `live|history` value, so the
obvious `LIKE :keyId || '%'` also matches a different key whose id merely starts the same way. Two
UUIDs sharing a prefix is unlikely; an invisible over-count that has a rotation re-encrypt rows it never
claimed to touch is not a risk worth taking to save a delimiter. The predicate is shared as a constant
so the SQL test runs **the shipped text** rather than a retyped copy:

```sql
:keyId <> '' AND (keyRef = :keyId OR keyRef LIKE :keyId || '|%' OR keyRef LIKE '%|' || :keyId)
```

The `:keyId <> ''` guard came out of the test rather than the design: an empty argument matched a row
whose `keyRef` was the empty string, which the test caught. `EventKeyRef.encode` cannot produce one and
`parse` refuses one, so the guard exists purely so the query is safe if some future writer does.

**Reported, not merely stored.** The diagnostic prints the key groups **only when the answer is not
uniform**, because a single key is the healthy state and a line that always appears is a line nobody
reads. More than one distinct key among OUTSTANDING rows is what a half-finished rotation looks like
from this device — and it is invisible in every other number in that section, since the counts are
identical whichever key is in use.

**Verified:** 12 pure tests (envelope parsing for v1/v2/v3, canonical encode/parse, the derived-copy
invariant) + 5 real-SQL tests + 4 report tests. One falsification: replacing the delimiter match with
`LIKE :keyId || '%'` failed exactly `aKeyIdThatIsAPrefixOfAnotherDoesNotMatchIt`. 1757 tests total,
0 failures; `lintDebug` 0 errors; `assembleDebug` green; no schema change (the column already existed,
so nothing to migrate).

Two notes on `Retry-After`, because the header is easy to get wrong:

- Only the **delta-seconds** form is honoured. The HTTP-date form would require the device clock
  to agree with the server's, and a wrong clock turning a short wait into a negative or enormous
  one is worse than ignoring the hint.
- The value is **capped at one hour**. A hostile or merely broken value must not park the outbox
  for a day; 30 minutes still passes through unclipped.

`source` is derived rather than passed by every caller, with a deliberate precedence: a send that
originated from a web command is `COMMAND_RESULT` even though it also looks like ordinary realtime
activity, because that is the fact an operator looks for when a web send misbehaves.

---

## PHASE 3 PROGRESS (History Backfill V2)

| Item | State |
|---|---|
| **Blocker 1 — UI-independent detection** | **fixed** — `GatewayChangeRelay` owned by `GatewayService` |
| Relay observable in diagnostics | **done** — `Telephony relay (UI-independent detection): running/stopped/not measured` |
| Realtime/history identity convergence (§33) | **fixed** — one `eventUuid`, and the sweep merges instead of skipping |
| `CAUGHT_UP` vs `SCAN_COMPLETE` (§26) | **fixed** — the real delivery rule, reported as a separate fact |
| History session entity (§25) | **done** — `history_sync_sessions` (schema 21), written and reported |
| Per-source checkpoints + resume (§27/§28/§31) | **already correct** — verified in the audit |
| Per-scan eligibility arithmetic (§70) | **done** — `Eligible = Enqueued + Skipped + Failed`, persisted |

### §26: the conflation was in my own Phase 1 code

Blocker 9 was "SCAN_COMPLETE and SYNC_COMPLETE both exist but are conflated in the only observable".
The second half of that was mine: `SyncDiagnosticsCollector` derived "delivered" as
`sourceExhausted && ackedContiguousOrdinal == nextOrdinal - 1`, **dropping the dead-letter clause**
from `GatewaySyncRepository.isHistoryDeliveryComplete`. A source with permanently-failed rows would
therefore have been reported `CAUGHT_UP` — the exact conflation the mission forbids, in the
component written to fix it.

The collector now calls the repository method instead of re-deriving it. That is the general lesson
worth keeping: **re-deriving a rule is how a rule loses a clause.** The per-source `delivered` fact
is a separate field from `scanComplete`, and the report prints both plus an explicit note when they
differ:

```text
    checkpoint=2020-09-13T12:26:40Z/4242 · scan complete=yes · delivered=NO
  NOTE: scan complete but NOT delivered for sms. The Provider has been read to the end;
  GMweb does not yet have all of it.
```

The second conflation site — `ConnectionDiagnostics.mirrorCheck`, the other diagnostics surface —
reported a bare `"complete"` from `historyBackfillComplete` alone. It now says
`"scan complete (delivery not checked here)"`, so it no longer claims something it did not check.

---

## PHASE 4 PROGRESS (Reconciliation)

| Item | State |
|---|---|
| Mirror → outbox reconciliation (§34) | **done** — `reconcileMissingEvents`, wired to app start |
| Bounded / incremental (§35) | **done** — 48 h window, 500 rows, indexed on `date` |
| Provider ↔ mirror reconciliation | **already existed** — `runIntegrityAudit`, 24 h, durable cursors |
| Reconcile on reconnect / manual Re-check (§34) | **done** — post-reconnect (throttled to 10 min) and "Run diagnostics" |
| Deeper consistency sweep than the window (§35) | **done** — a resumable full-mirror walk, schema 23 |

**Phase 4 is complete** apart from a deeper sweep than the 48 h window, which is the integrity
audit's territory (provider↔mirror, 24 h, durable cursors).

Mission §34 lists five triggers — app start, after reboot, after reconnect, manual Re-check, and
periodically. All are now covered: app start and reboot via `MessagesApp` (which `BootGatewayReceiver`
starts), reconnect via the supervisor's throttled hook, manual via "Run diagnostics", and the
periodic deeper pass via the existing 24 h integrity audit. The throttles matter because both the
supervisor and the diagnostics action are cheap to invoke and expensive to run repeatedly.

---

## PHASE 5 PROGRESS (Command V2)

| Item | State |
|---|---|
| **Blocker 14 — interrupted send re-sent on restart** | **fixed** — at-most-once; see below |
| Consent enforced on the strategic poller (§73/§76) | **fixed** — `GatewayAccessPolicy` is now the single rule |
| **Scheduled send re-sent on retry** | **fixed** — `ScheduledSendGate`; marker committed before the send |
| Sensitive values in logs (§43) | **fixed** — phone tokens, `***1234`, and no API-key fragments |
| Command lease + reclaim (§46/§48) | **done** — schema 20, lease on claim, expired-lease reclaim, and a caller |
| Startup drain of non-terminal commands (§46) | **fixed** — `CommandDrainPolicy` + the poller's drain pass; see below |
| `remote_command_executions` written (§45) | **fixed** — opened before the side effect, closed after |
| `client_message_id` end to end (§49) | **fixed** — captured at intake, and now carried by status changes too; see below |
| `deviceRevoked` producer | **fixed** — schema 22 + a 401/403 distinction with no destructive act |
| Legacy ACK: a lost outcome report | **fixed** — retried until GMweb accepts, and durable |
| Report backlog visible in diagnostics (§58) | **fixed** — with an explicit *not measured* state |
| Inbound write failure visible (§16/§52) | **fixed** — retried, then reported distinctly |
| Durable hold + retry for an unwritable inbound SMS (§16) | **done** — schema 24, drained by a worker with a periodic sweep |
| Local REST send: no idempotency (§78) | **fixed** — an Idempotency-Key dedupes through the durable ledger |
| Command drain vs locally-queued rows | **fixed** — the origin is recorded and honoured |

### §46/§47/§48: the command drain, and the one case where it must refuse to work

A command became `RECEIVED` the moment it was ingested, and only the poll loop that ingested it ever
executed it. Both crash windows were unrecoverable and **silent**:

| Crash window | Row left as | What GMweb saw |
|---|---|---|
| between ingest and execute | `RECEIVED` forever | nothing, on every redelivery |
| between claim and outcome | `ACCEPTED`/`EXECUTING` forever | nothing, on every redelivery |

`ackIfTerminal` answers a redelivery only for a *terminal* row (`else -> Unit`), so both windows
produced total silence and the web's ledger never resolved. Three primitives already existed —
`nonTerminal`, `reclaimExpiredLeases`, `expireStale` — and **nothing called any of them**; the
missing piece was the driver, which is not a wiring detail but the whole feature.

The driver itself is small. The hard part is that §46 ("nothing stays non-terminal") and §78 ("no
remote send may ever happen twice") **conflict** for a command that was claimed and interrupted:
after the hand-off the row is indistinguishable, *by state*, from one that never ran. Two facts
resolve it, and both are already persisted:

- `attemptCount`, incremented on every claim. So `attemptCount == 0` is the only sound proof that a
  command was never started — and it is the reason `expireStale`'s "provably never executed" claim
  is restricted to unclaimed rows too.
- the command TYPE. `MARK_THREAD_READ` is idempotent; `SEND_SMS` is not, and an unknown or future
  type cannot claim idempotence, so the default is **never re-run**.

So `CommandDrainPolicy` has exactly four outcomes, and the two that matter are refusals:

```text
RECEIVED, attemptCount == 0                 → Drive               (real crash-window recovery)
claimed + dead lease, MARK_THREAD_READ      → ReclaimAndDrive      (idempotent by nature)
claimed + dead lease, anything else         → FAILED / COMMAND_INTERRUPTED_AFTER_SUBMIT
past expiry, attemptCount == 0              → EXPIRED / COMMAND_EXPIRED_BEFORE_CLAIM
live lease                                  → Leave                (never race a live owner)
```

Two new `SyncErrorCode`s exist because no minimum code tells the truth here: `SMS_SEND_FAILED` would
assert the platform *refused* a send whose outcome is unknown, and both new codes are deliberately
**non-transient**, because a transient code would license exactly the automatic retry that §78
forbids. The retry a human performs becomes a NEW command id, so no duplicate is reachable.

**A trap the drain exposed.** `reclaimExpiredLeases` returns an abandoned `SEND_SMS` row to
`RECEIVED` — which, to any drain that keys off state alone, is an invitation to send it. That is a
duplicate send built into the recovery path itself, and it is why `attemptCount` and not `state` is
the discriminator.

**A fourth defect found while wiring it.** `execute()` answered a `SEND_SMS` it would not own with
`ack(FAILED)` while leaving the durable row `RECEIVED` — telling the server one thing and recording
another, and leaving the row non-terminal forever (so the new drain would re-drive and re-ACK it on
every pass). The refusal is now recorded as the terminal outcome it was already being reported as.

**`remote_command_executions` is now written** (§45). It had existed in the schema from the start
with no writer, so "a command is never executed twice" rested entirely on the unique `idempotencyKey`
index and not at all on a record of what ran. The attempt row is opened **before** the side effect,
which is what makes a process death during execution leave evidence rather than nothing.

**Verified:** 19 new JVM tests. The §78 guard was falsified by planting `SEND_SMS` into the
re-drivable set — 5 tests failed, including a property test over every combination of state ×
lease × expiry asserting a claimed `SEND_SMS` is never driven; the revert was confirmed by reading
the file back. 1652 tests total, 0 failures; `lintDebug` 0 errors; `assembleDebug` green.

Two source-level guards were added for the two regression shapes this round is about — a widened
re-drive set, and recovery primitives that quietly lose their last caller. The second one was
**wrong when first written and the falsification caught it**: it counted raw occurrences of
`expireUnclaimedCommands(`, which matches the declaration itself, so deleting the only call site
still left the guard green. It now ignores declaration lines, and deleting the call makes it fail.
A guard that cannot fail is worse than no guard, because it is believed.

**Still not done:** the honest limit that this drain runs inside the connected poll loop, so a device
that is offline resolves rows locally only once the loop runs again.

### §49: making the optimistic-bubble key survive the whole round trip

The audit recorded §49 as "half-implemented" in a specific direction: `MESSAGE_CREATED` carried
`originCommandId` and `clientMessageId` from the start, so a web-requested send could be *merged* —
but `MESSAGE_STATUS_CHANGED` carried neither, so when that send later turned DELIVERED or FAILED the
server could not find the bubble it had already drawn. Three separate gaps, all of them the same
shape — a field declared where it is used but never populated where it originates:

| Gap | The defect | The fix |
|---|---|---|
| intake | `clientMessageId` was on `RemoteCommandEntity`, read back by the executor, and **never populated from the claim response** — always null on the strategic path | `commandFromEnvelope` now maps it, with blank normalised to absent |
| status | the value was in scope at the `STATUS_CHANGED`/`READ_CHANGED` branch and simply not forwarded | `messageStatusChanged` takes and emits it |
| precedence | two possible sources (the row column and the payload), i.e. two rules that can disagree | the row is the authority, the payload is the legacy fallback |

The command-mapping logic moved out of `claim()` into a pure `commandFromEnvelope` for the usual
reason: an inlined mapping is exactly where a field quietly stops being carried, and one already had
stopped. The signature decoder is injected because it is the only Android-specific step, so the rest
of the mapping is exercised for real rather than through `returnDefaultValues`.

**The constraint that makes this delicate is §33, not §49.** A status event's identity is derived
from (type, source, provider row, date); if it started depending on *which* send caused the change,
one logical transition would acquire two ids and the outbox's unique index could no longer dedupe
it. That is pinned directly: the same transition with and without a correlation must produce the
same `eventUuid`. The falsification confirmed it — threading `clientMessageId` into the identity
derivation fails the test, and nothing else does.

**A field with no producer is a lie, including a new one.** `messageDeleted` was given the same two
parameters for symmetry and they were then **removed**: `MessageMutation.Delete` carries neither, so
no call site could ever populate them, and a parameter that is always null is precisely the defect
being fixed one function over. The audit's own §49 finding was "declared but never populated" — the
correct response was not to add a second instance of it. A test asserts the absence, so it stays a
decision.

`clientMessageId` is deliberately **not** added to the command ACK. The ACK is already keyed by
`commandId` in its URL, so a second correlation key there would be a redundant path to maintain; the
web learns the mapping from the events, which is where §49 asks for it.

**Verified:** 15 new tests (12 mapping/identity + 3 status payload), 2 falsifications. 1667 tests
total, 0 failures; `lintDebug` 0 errors; `assembleDebug` green; no schema change (the column already
existed — only a DAO query was added), so no migration.


### A second live §78 violation, on the scheduled-send path

`GatewayScheduler.SendWorker` is a *remotely requested* send (`POST /api/v1/sms/schedule`), so the
mission's absolute invariant applies. Its only guard was `status != "scheduled"`, and it retried up
to three times — so two interleavings produced a duplicate SMS:

1. `sendForResult` physically sent but returned null (an exception after dispatch), or
2. the process died after the platform accepted the SMS but before `status = "sent"` was written.

Both leave the entry reading `scheduled`, and the next attempt sent it again. The fix is the same
shape as the `EveSmsQueue` one: an entry-level `submittedOnce` marker, written **and committed**
before the send, with the decision extracted into `ScheduledSendGate` so it is unit-tested rather
than reasoned about.

**`commit()` rather than `apply()`, deliberately.** `apply()` returns before the write reaches disk,
so the marker could be lost in exactly the crash window it exists to cover — the marker would be
useless precisely when needed. Lint's `ApplySharedPref` rule exists because a blocking write on the
main thread can ANR; this runs in a WorkManager worker, so the rule does not apply and the
suppression carries that reasoning. And if the marker cannot be committed, the worker **declines to
send**: an unrecorded submit is indistinguishable from no submit on the next attempt.

### §43: three live log leaks closed

- `OutboxPoller` logged the **full recipient number** into the in-app log for every pulled task. Now
  a `PhoneToken` digest — deterministic, so a line can still be correlated.
- `GatewayServer` logged the recipient's last four digits bare; now `***1234`, the mission's own
  sanctioned form.
- `GatewayViewModel` logged 7 leading + 4 trailing characters of the **gateway API key**. The
  comment above it already said "never log the full key value" — but a prefix and suffix is enough
  to confirm a guessed key or correlate one across logs, so the intent was not met by the
  implementation. Nothing about the key is logged now; the value is on screen, where the owner
  needs it. (The screen label that shows the owner a key prefix remains: that is the key's owner
  identifying their own credential, not a durable log.)

---

## PHASE 6 PROGRESS (Hardening)

| Item | State |
|---|---|
| §73 security invariants as enforced guards | **done** — 21 source-level guards, each verified to fail on a real violation |
| §70 missed-message check surfaced in diagnostics | **done** — examined/recovered, reported not just logged |
| Scheduled-send plaintext at rest (§41/§43) | **fixed** — recipient and body encrypted; no plaintext fallback |
| Sensitive values in logs (§43) | **fixed** — tokens and `***1234`; no API-key fragments |
| `GatewayScheduler` retry duplicate send (§78) | **fixed** (round 16) |
| §70 full eligibility arithmetic (X = Y + Z + F) | **done** — schema 21, persisted per session |
| §50 pre-31 SIM selection + ledger attribution | **fixed** — see below |
| §49 correlation on the legacy pull path | **fixed** — the queue now hands the key to the send |
| `client_message_id` end to end (§49) | **fixed** — Phase 5 |
| Command drain driver (§46) | **done** — Phase 5 |
| `remote_command_executions` written (§45) | **done** — Phase 5 |
| Device validation (§69/§71) | **cannot be done here** |

### §70: from "we believe nothing was lost" to a number

Mission §70 wants a history scan's arithmetic to balance — `Eligible = Enqueued + Skipped + Failed`
with every discrepancy explainable. This is the formal statement of the work's core invariant, and
until now the app did not measure it. Worse, it *claimed* to:

```text
SYNC_REPORT eligible=1204 queued=1204        ← the same number twice, by construction
```

`queued` was incremented for every row **offered** to the enqueue path, including every row the
ADR-006 firewall refused, so it could not differ from `eligible`. It was a log line, so nothing ever
failed; it simply asserted on every scan that nothing had been dropped. A measurement that cannot
come out wrong is not a measurement, and this one was believed.

**The fix is a durable session row, not a better log line.** Schema 21 adds
`history_sync_sessions` — additive, `CREATE TABLE` only, and an upgrading install gets **zero** rows,
because no scan has been accounted for yet. Backfilling a synthetic "everything was fine" row would
have been a fabricated measurement, which is the exact failure being fixed.

**The narrower §70 work that preceded this.** The mirror → outbox reconciliation already detects the
failure that matters right now — a message the phone knows about with **no durable event** — and its
finding is published rather than logged:

```text
Missed-message check: examined 500 message(s) in the last 48h, recovered 12 (2m ago)
  NOTE: 12 message(s) the phone already knew about had NO durable event and were re-enqueued.
  That is a replication gap that was detected and repaired, not merely a statistic.
```

`recovered` there is the closest thing this system has to a measured answer for "did we drop
anything?". `null` (never run) is reported as `not run yet`, deliberately distinct from
`recovered 0` — turning an unmeasured state into a reassuring one is how a diagnostic starts lying.
The session arithmetic below answers the different question of whether a *scan* accounted for
everything it read.

**Where the accounting lives, and why exactly there.** `enqueueHistorical` is the one function both
history producers go through — the provider crawl and the mirror sweep — so the tally is applied on
its single exit path: no branch can return an outcome the arithmetic never sees, and a row is counted
exactly once whichever producer attributes it first. The two calls in `backfillCloudHistory` are
deliberately *not* counted locally; counting there as well would double-count every row the provider
crawl had already attributed. That is also why the old line was wrong in a second way: it counted
pages read, not rows replicated.

Counting a row exactly once needs one distinction that the old code did not make. The same
provider row can be seen twice — the provider crawl attributes it, then the mirror sweep reaches it
— and a cursor rewind re-reads ground already covered. `stampHistoryMetadata` already reports
whether it stamped anything, so:

| Sight of the row | Outcome | Counts |
|---|---|---|
| no event existed, insert succeeded | `ENQUEUED` | eligible + enqueued |
| an event existed with no history metadata (realtime won) | `ENQUEUED` (adopted) | eligible + enqueued |
| an event existed WITH metadata | `ALREADY_ACCOUNTED` | **nothing** |
| firewall refused / awaiting answer / no direction / sync off | `SKIPPED_*` | eligible + skipped |
| eligible, not skipped, no event afterwards | `FAILED` | eligible + failed |

So the identity holds by construction, and `FAILED` is the one number that must be zero.

**The classification is the part that had to be pure.** `enqueueCloudEvent` returned `Unit`, so a
caller could not tell "the event was written" from "the firewall refused" — which is *how* the scan
came to count refusals as queued. It now returns what it did, and `HistoryScanAccounting` maps that
onto the mission's vocabulary in one place. The decisive mapping is that a LOCAL_ONLY refusal is a
**skip, not a failure**: counting the user's own privacy policy as loss would fill the one number
that matters with correct behaviour. `SKIPPED_ASK_PENDING` is kept separate from
`SKIPPED_LOCAL_ONLY` because one is provisional — it may become eligible when the user answers — and
the other is final for that message.

**Reported, not just logged.** The diagnostic prints the arithmetic with its identity intact, so a
reader can check the sum themselves, and distinguishes three states that must never be merged:

```text
    scan accounting (session 4f2a9c1e): eligible=1204 = enqueued=1180 + skipped=24 + failed=0
      · balanced=yes · scan exhausted=yes · finished

    scan accounting: not recorded yet — no history session has been accounted for,
      so whether this source dropped anything is UNMEASURED
```

A balanced arithmetic over a scan that stopped halfway is `fullyAccounted = false`: that is
"the part we looked at was replicated", not "history is replicated", and conflating those is
Blocker 9's `SCAN_COMPLETE`-as-`CAUGHT_UP` in a new place. An unbalanced row is reported as the
accounting's own bug — *"its other numbers cannot be trusted"* — and deliberately **not** as loss,
because concluding that messages were dropped from books that do not add up would be a fabricated
finding.

**The earlier, narrower §70 work stands**: the mirror → outbox reconciliation reports
`examined`/`recovered`, which remains the measured answer for "did we drop anything *right now*".
This session arithmetic answers the different question of whether a *scan* accounted for everything
it read.

**Verified:** 18 accounting tests + 8 migration tests + 7 report tests; one falsification (mapping a
firewall refusal to `FAILED` fails the classification test and the report's
skips-are-not-loss test). The decisive migration assertion is that real `PRAGMA table_info` output
after `MIGRATION_20_21` equals the schema Room generates from the entities — a mismatch would make
Room refuse to open every existing user's database. 1700 tests total, 0 failures; `lintDebug`
0 errors; `assembleDebug` green.

**Still not done:** the legacy pull path (`OutboxPoller.Task`) still carries no `clientMessageId`, and
`deviceRevoked` still has no producer — there is no server signal to read.

### §57: the cross-repo signature contract had no test, and the comment said it did

`AgentAuth` signs a canonical string that GMweb's `agentAuth.js` reconstructs independently:

```text
METHOD\n<path>\n<sha256-hex-of-body>\nX-AGENT-TS:<ts>\n
```

Its KDoc claimed *"Both sides must stay byte-identical — pinned by `DeviceIdentityFormatTest` here and
agentAuth.test.js on GMweb."* That test pins the uncompressed public point (`0x04‖X‖Y`, 65 bytes) and an
ES256 sign/verify round-trip. It pins **nothing** about this string. So the one artefact whose silent drift
breaks every authenticated call to GMweb had no test at all — and the comment asserting otherwise is worse
than silence, because it is the reason nobody looked.

**The failure mode is worse than a failed request.** It is a **401**. Since the control plane's auth handling
was corrected in this work, a 401 makes the device replace its credential and re-enroll — so a refactor
here would not merely stop replication, it would **destroy a working enrollment** against a server that
revoked nothing, and the diagnostic would report a rejected key that was never rejected.

The string is now built by a pure `canonicalString(method, path, body, timestamp)`, split out of `sign` so
it can be pinned without an Android `HttpURLConnection`, and `AgentAuthTest` pins it literally. The digests
are golden values (SHA-256 of the empty string and of `hello`, verified independently before being written
down) because a golden value is the only assertion that catches a change to the *hash* as well as to the
framing.

Nine properties, each one something a tidy-up would remove or alter:

| property | why it is not cosmetic |
|---|---|
| exact order: method, path, body hash, timestamp | any reordering is a different string |
| single `\n` separators, ending with one | the trailing newline looks like noise; a space looks like normalisation |
| body covered by its lowercase-hex SHA-256 | a body change invalidates the signature without the body being in the string |
| a null body hashes the *empty* bytes | not the word `null`, and not an omitted field |
| the timestamp is **inside** signed material | outside it, anyone could rewrite it and replay for ever |
| header spelled `X-AGENT-TS` | GMweb rebuilds that literal |
| signature verifies the canonical bytes and not a bumped timestamp | the replay attempt, demonstrated |
| `freshTimestamp` never repeats inside one millisecond | two requests in one ms must not collide or a server sees a replay |
| `freshTimestamp` never goes backwards when the clock does | NTP/clock-step must not re-emit a seen timestamp |

**Verified:** 10 tests. One falsification: changing the method/path separator from `\n` to a space failed
exactly the three tests that pin the framing, and nothing else. 1903 tests total, 0 failures; `lintDebug`
0 errors; `assembleDebug` green; no schema change.

### §73: one trust predicate, and the fragility that hid behind the duplication

The trust predicate — which linked devices may receive key material and history — existed in two places.
The recorded follow-up said the copies were "the same expression", and that unifying them safely would first
require a JVM test for the original. Comparing them line by line found something better than a duplication:

| | `TrustedDevicePolicy` | `ConversationKeyRepository` (old copy) |
|---|---|---|
| status / certificate / expiry / revocation | same | same |
| unreadable `capabilitiesJson` | empty set — **fails closed** | `JSONArray("not json")` **threw** |

A throw there is not a local failure. `capabilities` is called from `eligibleForAccountKey` **inside
`encrypt`**, and `encrypt` runs on the enqueue path with no guard around it — so **one** linked device with
an unparseable capability list aborted the encryption of the message being enqueued, and a message that
cannot be encrypted cannot be replicated. The unification is therefore not cosmetic: it removes a live path
from "one bad device row" to "this message never replicates", and it removes it in the fail-closed
direction.

The condition for unifying was "add a JVM test for the original first". The answer turned out to be
simpler: the original *is* the tested implementation now, so the JVM tests that cover the policy cover the
crypto path too. A test also pins the *demonstration* — that `JSONArray("not json")` throws — so the claim
about what the old code did is evidence rather than assertion.

**The guard found a third copy.** Writing it as "the repository must not re-derive the rule" rather than as
one spelling of the code turned up two more re-typed `"FULL_HISTORY"` literals, including a complete third
copy of the eligibility expression inside `hasAuthorizedHistoryReader`. Two of the three were invisible to
the eyes that had just read the file; the rule-shaped assertion caught them on the first run. Both now
delegate, and the guard checks for the *re-typed grant name* along with the status rule and the raw parse,
so the next copy has to be a deliberate act.

**Verified:** 1 new JVM test (the throw demonstration) + 1 guard, falsified by planting the unguarded
`JSONArray(device.capabilitiesJson)` back — it failed that guard and nothing else. 1893 tests total,
0 failures; `lintDebug` 0 errors; `assembleDebug` green; no schema change.

### §16: a durable home for an inbound message that could not be written

Last round made the failure visible. Visible is not the same as survived, and the gap was named then:

> A persistent write failure still loses the message. Closing that needs a durable pending-inbound store
> plus a worker that re-attempts the insert.

This is that store. Schema 24 adds `pending_inbound_sms`: `(pduFingerprint, address, body, dateMs,
threadId, createdAt, attempts, lastError, state)`. When the receiver's bounded retry is exhausted it now
**holds** the message instead of dropping it, and a failure to hold it is reported separately
(`INCOMING_PERSIST_LOST`) because at that point the message really is gone and that must not look like the
ordinary path.

**The unique index is the design.** `pduFingerprint` identifies the broadcast, so a redelivered intent
cannot hold the same message twice — which matters because the retry *inserts into the provider*, and a
second held row would be a second message.

**Look before writing, every time.** `ContentResolver.insert` can **commit** and still fail — throw, or
return a URI with no usable id — so a row held as PENDING may already be in the provider. The retry
therefore asks the provider whether the message is there and inserts only when it is not. This is what
makes the retry safe where the receiver's own path could not be: the receiver has no row to look for (it
is creating one), while the retry is looking for a row it may already have created. The decision is pure
and pinned in both directions — inserting when it is already there duplicates the message, giving up when
it is not loses it:

| attempts | found in provider | provider readable | action |
|---|---|---|---|
| any | yes | yes | `AlreadyThere` — record it and stop |
| < cap | no | yes | `Insert` |
| ≥ cap | no | yes | `GiveUp(insert_attempts_exhausted)` — row **kept** |
| any | any | **no** | `GiveUp(provider_unreadable)` — left for the next pass |

The last row is the subtle one: if the provider could not be queried at all, we do not know whether the
message is there, and neither an insert (the duplicate) nor a give-up (the loss) is justified by
ignorance.

**A FAILED row is kept, never deleted.** It is the durable record that a message was lost — the
difference between a loss that is known and one that is silent — and the migration test says so, so its
shape does not tempt anyone into treating FAILED as garbage.

**Stored in the clear, and this is the reasoning.** `body` and `address` are the same bytes the mirror's
`messages` table holds moments later for every message that *does* arrive, so this is not a new class of
at-rest data. The difference from `PendingDelayedSendEntity` — which stores a token rather than the number
— is instructive rather than accidental: a delayed send can carry its recipient in the WorkManager job,
and this cannot, because §41 forbids a recipient or a body in input data and this retry's job carries no
payload at all.

**The trap fired again.** The first migration declared `DEFAULT 'PENDING'` on `state` while the entity had
only a *Kotlin* default — so Room's generated schema and the migration disagreed by one `dflt_value` and
would have refused to open the database. Caught by the migration test, fixed by declaring
`@ColumnInfo(defaultValue = STATE_PENDING)`, which is also better: an insert that forgets the state now
lands in a valid one instead of an empty string that no `WHERE state = 'PENDING'` would ever match.

**Verified:** 7 migration tests (including that the fingerprint index is genuinely unique) + 13 pure
decision tests + the wiring guard. One falsification: removing the `foundInProvider` branch failed
`aMessageAlreadyInTheProviderIsNotInsertedAgain` and nothing else. 1885 tests total, 0 failures;
`lintDebug` 0 errors; `assembleDebug` green.

**The other half, now built.** `PendingInboundWorker` drains the hold, and it is scheduled twice over on
purpose: the receiver asks for a pass the moment it holds a message (a provider that was momentarily
unavailable is very likely to answer a minute later), and `MessagesApp` also schedules a 6-hourly sweep.
An enqueue can fail, and a message held by a receiver is exactly the case where relying on a single
opportunistic call would be worst.

Each row gets one decision and one durable outcome before the next row is touched: `AlreadyThere` or a
successful insert → `DONE`; a failed insert → the attempt is recorded and the row stays `PENDING`; the cap
reached → `FAILED` **and kept**. A provider that cannot be read writes nothing and is retried next pass —
returning `retry()` for exhausted rows instead would spin a worker on work that can no longer succeed,
which is why the pass distinguishes the two.

**Two mistakes of my own, and what caught them.** The first draft of the worker's summary counted a
*failed* insert as a store, because it branched on the action type rather than the outcome — that would
have reported losses as recoveries, the same "a number that cannot be wrong" shape this work keeps
removing. It branches on a dedicated `PassResult` now. And the first version of the guard asserting that
the sweep is scheduled matched the string `PendingInboundWorker.ensureScheduled(` — which the *helper's own
body* contains — so deleting the call from `onCreate` left the guard green while nothing scheduled the
sweep. That is the declaration-versus-call-site false pass for the third time in this file; the assertion
now counts call sites and ignores the definition, and it was falsified by deleting the call.

### The inbound path: a lost SMS that looked routine

`SmsReceiver` writes an incoming message to the provider itself (this app is the default SMS app) and
then reads it back. Both outcomes arrived at the same place:

```kotlin
val inserted = insertIntoInbox(...)   // (-1, null) on ANY failure
if (sms == null) { log("decision=defer-to-provider-observer"); return }
```

A **write that failed** and a **write that succeeded but is not readable yet** are not the same fact, and
the difference is the whole message:

- not visible yet — a provider row exists; the ContentObserver will ingest it, and the "defer" line is
  accurate;
- write failed — nothing else wrote the row, because no other app is the default SMS app. The observer
  that line defers to has nothing to find, and an inbound message is gone with a log line that reads like
  normal operation.

`insertIntoInbox` now returns an `InboxWriteAttempt` (`Wrote` / `Threw`) instead of a bare id, and the two
facts are reported differently. Three things changed:

- **A bounded retry inside the broadcast window.** A momentarily unavailable or locked provider is the
  likely cause, and the receiver already holds a `goAsync` window. `InboxWriteRetryPolicy` is pure over
  its write/sleep lambdas, so the attempt count, the waits and the surviving failure reason are asserted
  directly: 3 attempts, 50 ms then 150 ms, total well under a second.
- **A provider that answers without a row id counts as a failure.** There is nothing to read back, so the
  caller must not proceed as though a row exists.
- **A persistent failure gets its own code** (`INCOMING_PERSIST_FAILED`, `decision=message-not-persisted`)
  rather than borrowing the visibility-delay line.

**A trap I walked into and backed out of.** The first version of this fix dispatched the broadcast payload
as a synthetic row when the write failed, so the user would still be notified. That is the CASE C
duplicate this file already documents: `insert` can **commit** and still fail to return the URI, in which
case a real provider row exists, the observer ingests it, and the synthetic bubble would show the message
twice. The failure path deliberately dispatches nothing now, and a guard asserts it stays that way.

**What is still NOT fixed, stated plainly.** A persistent write failure still loses the message. Closing
that needs a durable pending-inbound store plus a worker that re-attempts the insert — a new table and a
new subsystem, which is a larger change than this round and is recorded here rather than approximated.
What changed is that the loss is now *visible and diagnosable* instead of indistinguishable from normal
operation, and the common transient cause is repaired.

**Verified:** 8 pure retry tests + 1 guard. One falsification: removing the distinct
`INCOMING_PERSIST_FAILED` code failed `aFailedInboxWriteIsNeverReportedAsAVisibilityDelay` and nothing
else. I also had to correct that guard mid-round: its third assertion looked for a literal string that
could never appear in the source, so it passed vacuously. It now asserts the *shape* — that a failed write
cannot flow into the read-back as though a row existed. 1870 tests total, 0 failures; `lintDebug`
0 errors; `assembleDebug` green; no schema change.

### §58: the durable report backlog, and the null that keeps it honest

Last round made an outcome report survive a restart. A fact nobody can see is not much use, so the
count is now in the diagnostic — and the interesting part is that it has **three** states, not two:

```text
  ACK failures this session: 0
  ACK consecutive failures: 0
  Outcomes GMweb has not accepted: 3 — re-reported on every poll until the server accepts them
    (a report, never a second send)
```

The counters above it are in-memory and reset with the process; this one is read from the durable queue,
so it is the only figure there that can survive a restart — and it is the one that answers "is GMweb
missing an outcome at all?".

**The third state is the point.** Before the queue has read its durable store, the in-memory map is empty
because *nothing has been loaded*, not because everything is reported. `unreportedGatewayBacklog()`
therefore returns `null` until `bootstrap` has run, and the report says `not measured` rather than `none`.
Rendering "we have not looked" as a clean bill of health is the same mistake the reconciliation and
verification sections already had to be corrected for — and it is the mistake that makes a diagnostic
trusted until the day it matters.

**Verified:** 2 queue tests (an unloaded queue is not zero; the backlog counts only terminal unreported
gateway tasks) + 4 report tests. One falsification: removing the `durableStateLoaded` guard failed
`anUnloadedQueueReportsNoMeasurementRatherThanZero` and nothing else. One of my own new report tests
also failed first time — `assertFalse(text.contains("not measured"))` over the whole document — because
the report legitimately says "not measured" about several other facts; it is now scoped to the specific
line, which is what it meant. 1861 tests total, 0 failures; `lintDebug` 0 errors; `assembleDebug` green;
no schema change.

### §43: the same rule escaped a narrower guard three times

The EVE send handler logged the recipient raw:

```kotlin
onRequestLog?.invoke("📨 POST /send -> $to (${result.record.priority}, ${result.record.status})")
```

That is the third occurrence of one rule, and the shape of each escape is the interesting part:

| attempt | what the guard checked | what got past it |
|---|---|---|
| 1 | the literal `"-> \$phone @"` | the MMS handler's `"-> $phone (success=…)"` |
| 2 | the variable name `$phone` | the EVE handler's `$to` |
| 3 | **the rule**: any recipient-named variable in a string template | — |

The guard is now: in `GatewayServer.kt` and `OutboxPoller.kt`, any line interpolating one of
`to / phone / rawPhone / address / recipient / sender` must tokenise it with `PhoneToken.of(...)` or use
the mission's own `***1234` form. It is stated as a rule because two spellings of it have already been
tried and both times a real number shipped in a log that the diagnostic report can carry.

**A false positive is possible in principle** — a variable named `address` that is not a phone number —
and the guard says so at its definition: if one ever appears, it should be narrowed deliberately rather
than deleted, because it is the only thing between a shareable log and a dialable number.

**Verified:** the rule was falsified by restoring the raw `$to` line, which failed
`noRecipientReachesALogUnderAnyName` and nothing else. 1855 tests total, 0 failures; `lintDebug`
0 errors; `assembleDebug` green; no schema change.

### The local REST send endpoint had no idempotency at all

`POST /api/v1/sms/send` is remotely callable — GMweb on the same LAN uses it — and it called
`smsSender.sendWithOutcome(...)` directly. That path persists a Sent row and dispatches; it has **no**
dedupe of any kind. A caller whose connection dropped after the phone accepted the message (the 202 was
lost) had no way to retry: a retry was a second physical SMS. Mission §78 covers *every* remotely
requested send, not only the control-plane one.

The convention was already in the codebase and this endpoint was the exception: the EVE handler next
door takes an `Idempotency-Key` header. This one now does too (plus an `idempotencyKey` body field, so a
caller that cannot set headers still has a safe option), and with a key it routes through the SAME
unique `idempotencyKey` index every durable send uses:

```text
INSERT OR IGNORE (unique idempotencyKey)  →  claim RECEIVED→ACCEPTED (guarded UPDATE)
  winner: hand to telephony, report 202 accepted
  loser:  AlreadySent — nothing was sent now, and the response says so
```

No new table and no new index: the row is the same audit record every other send creates.

Two things are deliberately explicit rather than implied:

- **A duplicate is not a failure.** It keeps the 202 the first call received and adds
  `"duplicate": true`. Reporting it as `failed` would tell a correct caller that its message did not go.
- **A request with no key is reported as not retry-safe** (`"retrySafe": false`). It *cannot* be deduped
  — there is nothing to identify a retry by — and deriving a key from the phone and body would suppress a
  legitimately repeated message ("ok" sent twice). Saying so is the only honest option; pretending
  otherwise would be a §78 hole with a reassuring response.

The response shape is otherwise unchanged (`status` + HTTP code), so a legacy caller is unaffected.

**`IdempotentSendOutcome` is a separate type on purpose.** "Already sent" is not "accepted now" and it is
certainly not "failed", but widening `SendOutcome` would change the meaning of every existing `when` over
it — on the send path — for one caller's benefit. The safety-critical paths keep their two-valued model.

**A dead parameter had to be fixed first.** `enqueueSendSms` accepted `sourceDeviceId` and then dropped
it, so a row this device queued for itself was indistinguishable from one the control plane sent. That
matters because the same table is the durable ledger for *every* send: without the marker, the command
drain would execute, fail or ACK a locally-queued row as though GMweb had asked for it — and a row left
by a crashed local send is indistinguishable by state from a fresh remote one, so a future executor that
accepted it would hand the same SMS to the radio twice. The origin is now recorded, and
`CommandDrainPolicy` leaves local rows alone.

**Verified:** 4 new drain-policy tests + 2 architecture guards. Two falsifications: neutralising the
local-origin check (`if (false && ...)`) failed the two behavioural tests, and removing
`"duplicate": true` failed the endpoint guard. Worth noting the division of labour honestly — the source
guard only proves the marker is *compared*, so it catches deletion while the behavioural tests catch
neutralisation; neither alone is sufficient. 1854 tests total, 0 failures; `lintDebug` 0 errors;
`assembleDebug` green; no schema change.

### The live transport: an outcome report that could be lost, and was

The legacy pull bridge exists to tell GMweb what happened to a task. The *send* was made at-most-once
long ago; the **report** was not durable at all, and its single attempt was treated as its success:

```kotlin
iterator.remove()
markAcked(entry.key)                              // remembered as "already acknowledged"…
sendAck(rec, rec.outcome, reasonFor(rec))         // …BEFORE the transmission was attempted
```

`GatewayAckTracker` is in memory, `markAcked` refuses to re-track anything in the `acked` set, and
`outstandingGatewayRecords()` returns **only non-terminal** records — so a report that GMweb refused, or
that died in transit, was unrecoverable three times over:

1. the tracker believed it had been delivered;
2. the ACK ledger does not survive a restart; and
3. the durable queue could not even *offer* the record for re-seeding, because it was terminal.

The code was explicit that this was accepted — *"A lost ack must NOT re-send locally; the server times
the task out."* The first half is right and important; losing the report is not. A task that **sent** and
whose report was lost leaves GMweb showing an unresolved message for ever, which is the opposite of the
bridge's purpose, and it contradicts `GatewayAckTracker`'s own KDoc, which describes the `acked` set as a
guard against a *second* ACK rather than against a failed one.

**The fix is in three places, and the safety argument is the same for all of them: a report is not a
send.** Retrying a report cannot produce a second SMS — the server keys it by `requestId` and the same
terminal state twice is the same state — so every mechanism below is free.

| change | what it buys |
|---|---|
| `sendAck` returns whether GMweb accepted it; the task is un-tracked and marked acked **only** on acceptance | a refused or lost report is retried on the next cycle instead of being recorded as delivered |
| `Record.gatewayAckedAt`, written **only after a 2xx** | the report survives a process death, unlike the in-memory ledger |
| `EveSmsQueue.unreportedGatewayRecords()`, re-seeded alongside the outstanding ones | a terminal-but-unreported task is offered for re-reporting on the next start |

`ackForTask` deliberately keeps ignoring the return value, and now says why: its two callers are a task
superseded at pull time (never queued) and a drain timeout (whose record is still **non-terminal**, so the
next cycle re-seeds it and eventually reports the real outcome). Retrying *those* reports would be
redundant, not wrong.

**A test-level detail worth recording.** The fake ACK transport in `GatewayDeferredAckTest` ended its
lambda with `acks.add(...)`, which returns `Boolean` — so once `sendAck` became a `Boolean`, the fake
silently reported *acceptance* on every call. The lambda now states its answer explicitly, and the
falsification is what surfaced this: restoring the old ordering failed exactly
`aRefusedReportStaysQueuedAndIsRetried`.

**Verified:** 4 new tracker tests + 2 codec tests. 1848 tests total, 0 failures; `lintDebug` 0 errors
(314 warnings, unchanged); `assembleDebug` green; no schema change (the field is in the SharedPreferences
codec, which is versioned and tolerant — a record from a build without the marker reads as *unreported*,
which is the honest reading and the one that makes it eligible for re-reporting).

### The contiguous ACK frontier, finally testable off-device

`advanceHistoryAckWatermarks` is the most fragile thing in this subsystem and the cause of **three**
separate defects during this work — each found by reasoning about it, never by a failing test. It walks
the history rows forward from the last acknowledged ordinal and stops at the first gap, and nothing in
the schema declares that dependency: anything that removes, re-orders or re-stamps a row freezes history
at `CATCHING_UP` for ever.

It was untestable here for a structural reason: the rule lived inside a suspend function whose only
coverage was an **instrumented** test, so it never ran in this environment. Nothing about the rule is
Android-specific — it is a fold over a list — so it now lives in `HistoryAckWalk`, pure, with the
repository delegating to it.

| case | why it must behave that way |
|---|---|
| contiguous ACKED run | advances to its last row, and carries the `(date, providerId)` cursor with it |
| first gap | **stops** — a frontier that steps over a hole claims delivery nobody received |
| gap later closed | resumes past it on the next read; a walk that trusted its stored frontier would freeze for ever |
| `PENDING` / `SENDING` / `RETRY_WAIT` | stops: retrying is not delivering |
| `DEAD_LETTER` | stops: the honest observable is `scanComplete` with `delivered = false`, not a closed hole |
| out-of-order or duplicate ordinal | stops: contiguity cannot be argued from an unordered list |
| nothing contiguous | returns **null**, so the caller does not write a checkpoint row on every unrelated ack |

`isDelivered` moved here too, with its dead-letter clause intact — the clause that gets dropped whenever
the rule is re-derived at a call site, which is how a source with permanently failed rows was once
reported `CAUGHT_UP`.

**The query is tested against real SQLite, using the shipped constant.** `HISTORY_AFTER_SQL` is now
extracted so the test runs the same text Room runs: the `ORDER BY historyOrdinal` and the
`historyOrdinal > :afterOrdinal` bound *are* the walk's correctness, and a test that retyped them would
keep passing while the DAO changed underneath it. The centrepiece is the end-to-end trap over real SQL:
produce five rows, ack 1, 2, 4 and 5, walk to 2 — then ack 3 and walk again, and it reaches 5.

This replaces coverage that existed **only** on a device, which is the closest this environment can come
to the mission's §69 validation for this particular invariant.

**Verified:** 16 pure rule tests + 8 real-SQL tests. One falsification: deleting the contiguity clause
`ordinal != expected + 1` failed five tests across both suites, including the end-to-end gap test.
1842 tests total, 0 failures; `lintDebug` 0 errors; `assembleDebug` green; no schema change.

### MMS: a `?.` that turned a failed write into a success

Every MMS part was written like this, and then the function returned a valid message id regardless:

```kotlin
cr.openOutputStream(partUri)?.use { out -> out.write(imageBytes) }
return mmsId
```

The `?.` is the entire defect. If the stream will not open, **the write never happens**, the part stays
empty, and the caller reports success. `compressImage` had the same shape: it returns `ByteArray(0)`
when the bitmap will not decode, and that zero-length array was written into the part as if it were a
picture.

Two consequences, bad in different ways. The recipient gets nothing (or a broken attachment) while the
sender believes a photo was delivered — and the empty row is a **real message in the provider**, so the
mirror replicates it and GMweb shows a message that never existed. Mission §52 says such a case "must be
documented clearly instead of silently dropping it"; this makes it a named refusal.

**Order was as much the fix as the check.** The payload is now built and judged **before any provider
row is created**, so a refusal leaves nothing behind for the mirror to pick up — and the check cannot be
forgotten at the end of a long insert sequence. If the write fails *after* the row exists, the row is
rolled back: a delete of the app's own seconds-old, never-sent, payload-less insert, not of anything the
user owns. Leaving it would be worse than removing it, for the phantom-message reason above.

`MmsPayloadPolicy` is pure, so the boundary conditions are testable without a provider: empty, exactly
at the 900 kB cap, one byte over, and a null stream (named `Unavailable` rather than `Empty`, because an
unreadable source and a bad decode need different diagnoses). Audio is read through `loadBounded`, which
stops at `cap + 1` bytes — enough to tell "too large" from "fine" without buffering a tens-of-megabytes
recording on the send path. Images still go through `compressImage` exactly as before, so a normal
camera photo is compressed and sent; only a result that cannot fit or cannot be produced is refused.

**A bare `Boolean` was half the problem.** All three send functions returned one and **every** caller
discarded it — the conversation screen went further and added an optimistic bubble plus an
`emitOutgoingSent` event unconditionally, so a refused photo was displayed as sent. They now return
`MmsSendResult` (`Queued` is deliberately not called "sent": whether the message left the device is only
known at `MmsStatusReceiver`), the composer drops the optimistic bubble on a rejection, and the REST
endpoint keeps its exact legacy `status`/HTTP-code shape while adding `reason` and `code`.

**A leak the §43 guard missed, found while editing that endpoint.** The MMS log line interpolated the
recipient raw: `"… -> $phone (success=$success)"`. The guard was green because it pinned one exact
string (`"-> \$phone @"`) rather than the rule, so a second raw-number log in the same file survived
indefinitely. The line now uses `PhoneToken.of(phone)`, and the guard asserts that **every** `$phone`
interpolation in the file is tokenised — a rule, not a literal.

**Verified:** 10 payload-policy tests + 2 architecture guards, and **three falsifications** (the
`?.use` idiom restored, a raw `$phone` interpolation restored, and the MMS null callback) each failed
exactly the guard meant to catch it. 1818 tests total, 0 failures; `lintDebug` 0 errors and **two
warnings fewer** than before, because the three duplicated part-URI constructions collapsed into one
helper; `assembleDebug` green; no schema change.

### MMS: the send result had nowhere to arrive

```kotlin
smsManager.sendMultimediaMessage(context, mmsUri, null, null, null)
//                                                          ^^^^ the send-result PendingIntent
```

A `null` result callback is not "no callback needed" — it is **silence**. The platform's outcome had
nowhere to go, so a failed MMS stayed in `MESSAGE_BOX_OUTBOX` for ever, looking to the user like a
message that was still being sent, while nothing in the app or the gateway could tell a picture that
left the device from one that never did. That is the "silently lost" shape the mission's first
invariant forbids, on the one message type whose payload is a photo or a voice note.

**The fix is the same shape the SMS path already uses**: an explicit manifest receiver plus a
`PendingIntent`, so the result still arrives after the sending process is gone. The receiver classifies
the result, records it, and **publishes it as a gateway status change**, so GMweb can show an MMS as
sent or failed instead of showing nothing.

`MmsSendResultPolicy` is pure and classifies every `MMS_ERROR_*` the SDK defines into three named
verdicts — and an unrecognised code into `UNKNOWN`, never into `SENT`. That asymmetry is the point: an
outcome the app cannot interpret must not be reported as delivered, which is exactly what
`resultCode == RESULT_OK` with a permissive default would do.

| verdict | codes | what it means for the user |
|---|---|---|
| `SENT` | `RESULT_OK` | handed off |
| `RETRYABLE` | no data network, data disabled, IO, HTTP failure, cannot connect, retry | "try again when you have data" |
| `PERMANENT` | invalid APN, configuration error, invalid/inactive subscription, disabled by carrier | waiting will not help |
| `UNKNOWN` | anything else, including `MMS_ERROR_UNSPECIFIED` | the app does not know |

**Two things it deliberately does NOT do**, both learned from the SMS path's history:

- It does **not** rewrite the shared MMS provider row's box or status. Writing vendor SENT failures
  into `Telephony.Sms.STATUS` made every SMS app on the device claim "Not delivered"; a
  half-understood provider write from an MMS callback could corrupt a row other apps read.
- It does **not** touch the per-SMS `send_segments` ledger. That ledger is keyed by an SMS provider row
  id, and an MMS row id is a different key space (`content://mms/<id>`) whose numbers overlap — so
  reusing it would attribute a picture message to an unrelated SMS row and move the daily counter. MMS
  carries its own vocabulary for exactly this reason, and a guard asserts the receiver never imports
  the ledger.

**Verified:** 8 classifier tests + one architecture guard covering the null callback, the manifest
declaration and non-exportedness, and the ledger-isolation rule. The falsification — restoring
`sendMultimediaMessage(..., null)` — failed exactly that guard. 1806 tests total, 0 failures;
`lintDebug` 0 errors; `assembleDebug` green; no schema change.

**Still open, and unchanged by this:** MMS is reachable only from the device-local
`POST /api/v1/mms/send`, not from a web command (the strategic type gate is `SEND_SMS` /
`MARK_THREAD_READ`); attachments are not replicated as assets; and the silent
`openOutputStream`/`compressImage` drops recorded in the findings above are exactly as they were.

### §35: the question both existing passes declined to ask

The recent-window check examines 48 hours. The history scan produces events for the whole mirror and
then **finishes**. So a message whose event was lost three weeks ago is inside no window the first pass
looks at, and the second pass never revisits those rows again either. The app's answer to "is the whole
mirror replicated?" therefore rested on two passes that both decline to ask it — one by construction,
one by being done.

**A resumable walk, not a bigger window.** Widening the window only moves the boundary. The sweep walks
the mirror backwards in bounded pages with a durable keyset cursor, so it covers everything eventually,
survives repeated process deaths, and reports itself as *unfinished* until it is done:

```text
Full-mirror verification (mission §35):
  sms: examined=360000 alreadyReplicated=359998 recovered=2 noProviderId=0 · finished · balanced=yes
    NOTE: 2 message(s) had NO durable event and were re-enqueued. The history scan had already
    finished, so nothing else was going to find them.
```

Three states that must never merge, and the report keeps them apart: never run (**UNMEASURED**, not
clean), still running (*"not yet a verified claim"*), and finished. This is the honest limit §70 asks
for — until the sweep completes, "no messages lost" is a belief.

**The paging predicate is where the bug would have been.** Rows routinely share a date — multi-part
SMS, message bursts — so a date-only cursor either skips the rest of a date group (silent loss) or
re-reads it forever (a sweep that never finishes and *looks* like work). The cursor is the composite
`(date, providerId)`, matching the table's own primary key and the DAO's ORDER BY. Two tests hold it:
one walks a table whose date groups straddle the page boundary and asserts every row is seen exactly
once, and one is a **negative control** that runs the naive date-only cursor on the same data and
asserts it finds nothing — so the first test cannot be vacuous.

**Gated on the history scan being finished.** Verifying a mirror that is still being filled would walk
rows the producer is about to produce; every one would look like a recovered gap, and that noise would
hide a real one. The gate is per source, like the checkpoint.

**A per-row lookup would have made it useless.** The existing window check asked the outbox once per
row. Correct for 500 rows, ruinous for 360,000 — so the sweep adds a batched
`existingEventIds(ids)` lookup and the window check now uses it too.

**Two things the tests forced, both worth recording.** The page arithmetic first folded rows with no
provider id into `alreadyReplicated` — a claim about rows that were *never looked up*, since
`eventUuidFor` is meaningless without a real provider row. They now have their own named counter and
the arithmetic has four parts. And the alarm precedence is now **imbalance first** in both the sweep and
the §70 session arithmetic: `recovered`/`failed` are counted by the same tally, so announcing loss from
books that do not add up would be a fabricated conclusion. The two functions previously disagreed on
this; they no longer do.

**`theVerificationSweepIsActuallyWiredToTheSupervisor`** exists because `ManagedComponents` fields have
no-op defaults: a sweep declared but never passed in compiles, builds, and silently never runs. That is
the same regression-by-omission shape as the command drain's uncalled primitives, and it is now pinned
for both this and the window check.

**Verified:** 17 pure walk tests + 6 real-SQL paging tests (including the negative control) + 8
migration tests + 7 report tests + the wiring guard. One falsification: replacing the keyset comparison
with `<=` — a compilable off-by-one — failed the source guard that pins the shipped query. A first
attempt at that plant removed the parameter entirely, which **broke KSP compilation**, so the guard was
never exercised; that is the same false-negative trap recorded earlier in this document, and the plant
was replaced with one that compiles. 1797 tests total, 0 failures; `lintDebug` 0 errors;
`assembleDebug` green.

**Schema 23** adds `mirror_verify_state` (one row per source, `CREATE TABLE` only) and one index on
`messages` — `(source, date, providerId)` — which is what turns each page into an indexed range scan
instead of a `date` scan with a source filter, and speeds up the history producer's own page query. No
existing row is touched; an upgrading install gets zero progress rows, because a seeded row would claim
a verification that never ran.

### A 403 used to delete the device's own credentials

`ReplicationBlocker.DeviceRevoked` was declared, ordered, mapped to a category, covered by tests and
**unreachable**: `ReplicationInputsReader` hard-wired `deviceRevoked = false`, on the recorded
reasoning that a 403 on some unrelated route is not proof of revocation. That reasoning was right, and
the conclusion was still wrong — a 403 from an **authenticated agent endpoint** is the control plane
refusing this device, and the app was acting on that same answer in a way nobody had noticed:

```kotlin
is BackendClient.Result.Failure -> {
    if (result.isAuthError) {                     // isAuthError == (401 || 403)
        prefs.clearCloudCredentials()             // deletes device id + token + registered marker
        registrationManager.register()            // and re-enrolls
    }
```

So a device the server had *revoked* wiped its own enrollment and re-registered, and then reported
`IDENTITY_NOT_REGISTERED` — telling the user to set the gateway up again, when the actionable fact was
that the server had refused it and a human needed to un-revoke it. The distinction was never missing
from the codebase: `SyncErrorCode.fromHttpStatus` maps 401 → `AUTH_REQUIRED` and 403 → `DEVICE_REVOKED`,
and `GatewayFailureKind` separates `HTTP_AUTH` from `HTTP_FORBIDDEN`. It was collapsed at the one place
that *acts* on it — the same "a rule restated at a call site loses clauses" pattern as Blocker 2, this
time with a destructive side effect.

**The rule, now pure and tested** (`ControlPlaneAuthPolicy`):

| signal | credential | consecutive 403s | revocation |
|---|---|---|---|
| 200 ACCEPTED | kept | reset | **CLEARED** |
| 401 UNAUTHORIZED | replaced + re-enroll | reset to 0 | unchanged |
| 403 FORBIDDEN | **kept** | +1 | set at the threshold |
| 400/5xx/timeout | kept | unchanged | unchanged |

Three asymmetries, each deliberate:

- **A 403 never replaces the credential.** The device holds a good credential; the enrollment is not
  the problem and is what must survive until the server's owner acts.
- **A 401 never lifts a revocation**, and **a 401 resets the 403 counter** — because `403, 401, 403` is
  not two consecutive refusals, and a flapping proxy must not accumulate its way to one.
- **The threshold is two consecutive 403s.** A single one can come from a WAF, an intercepting proxy or
  a misrouted host, and the errors are not symmetric: a false "revoked" holds all uploads and summons a
  human, while a missed one is settled by the next heartbeat seconds later.

**Durable, and that is the point.** `control_plane_auth_state` (schema 22, additive `CREATE TABLE`,
`PRIMARY KEY(id)` because this is a property of the device and not a log) keeps the verdict across a
process death. `GatewayHealthRecorder` is deliberately in-memory, so an `AuthVerification.REJECTED`
is forgotten on restart — fine for a transient fault, useless for a standing verdict, which would
otherwise come back as "no auth blocker", re-upload everything and re-offer enrollment.

**No deadlock, by construction.** The obvious trap is that a revocation holds uploads, and if uploads
are the only thing that can prove the credential works, nothing can ever clear it. It does not hold
here: the heartbeat is an authenticated, side-effect-free liveness ping that `ConnectionSupervisor`
starts independently of the upload gate, so it keeps running while uploads are held, and **a 200 on it
is the one thing that clears the revocation.** Recovery needs no new endpoint, no manual step and no
timer.

Both reads fail safe in the direction that matters: an unreadable verdict row yields *not revoked*
(a diagnostic row must never be able to freeze every upload), and a failure to read the previous state
yields *no change and no action* (a corrupt row must not fabricate a re-enrollment either).

**Verified:** 13 policy tests (including a property test that no prior state lets a 403 reach the
credential replacement) + 8 migration tests. One falsification: forcing `replaceCredentialAndReEnroll
= true` for FORBIDDEN failed exactly the two tests that exist to prevent it, and the revert was
confirmed by reading the assignment back. The migration test asserts real `PRAGMA table_info` output
after `MIGRATION_21_22` equals Room's generated v22 schema, that only `CREATE TABLE` appears, and that
an upgrading install gets **zero** verdict rows rather than a fabricated one. 1736 tests total,
0 failures; `lintDebug` 0 errors; `assembleDebug` green.

### §49 on the live transport: the key stopped one function short of the send

The strategic path is the *strategic* one; the legacy pull bridge is the **default-active** transport,
so §49 only actually worked anywhere else. On this path the web's key was already parsed, stored,
persisted, and echoed back on the ACK — it was simply never attached to the message event.

```text
GMweb /gateway/pull → OutboxPoller.Task{requestId, to, text, priority, meta{correlationId, …}}
  → EveSmsQueue.enqueue(…, meta)          correlationId lands on the Record
  → drainOne() → validator → senderFn     ← the seam took `(to, text)`; the key had nowhere to go
  → SmsSender.directSend(null, null)      so the event reached GMweb uncorrelated
```

The blocker was a signature, not a decision. `senderFn: (String, String) -> Boolean` cannot carry a
third fact, so `Record.correlationId` was reachable in the queue and unreachable at the send. The
seam now takes the **whole record**:

```kotlin
sender: (Record) -> Boolean
```

That is deliberately not "add one more parameter": the next field a send needs — a SIM the web
chooses, say — would otherwise be another arity change at six call sites, and the same class of
omission is exactly what this round repaired. One parameter that carries the job cannot lose a field.

`GatewayServer` then maps the key to the field the event pipeline already understands:

```kotlin
smsSender.sendForResult(record.to, record.text, subscriptionIdOverride = null,
                        clientMessageId = record.correlationId)
```

**Deliberately `clientMessageId` and not `originCommandId`.** That second field means "a durable
`remote_commands` row caused this message", and on the pull path no such row exists. Passing the same
value as `originCommandId` would make every legacy web send claim a command that cannot be looked up —
and would additionally move the row's `source` to `COMMAND_RESULT`, damaging the accounting. The event
carries the web's key; it does not invent a command.

**Verified:** 3 queue tests (the key reaches the sender; an absent key travels as `null`, not `""`; and
it survives the persistence round trip, which matters because the send may happen after a reboot) plus
one source guard, falsified by setting `clientMessageId = null` at the call site — the guard failed,
and the revert was confirmed by grep. 1715 tests total, 0 failures; `lintDebug` 0 errors;
`assembleDebug` green.

**Still open on this path:** the task model carries no `subscriptionId`, so a web request cannot *name*
a SIM (the user's own selection is now honoured correctly by `SmsSender`; what is missing is the web's
ability to override it).

### §50: the selected line was ignored, and the ledger said otherwise

Two defects in one branch of `SmsSender`, and the second is the worse one.

```kotlin
} else {
    // Pre-Android 12: the per-subscription manager API is no longer
    // exposed by current SDK stubs, so sending falls back to the
    // platform-default subscription.
    @Suppress("DEPRECATION")
    SmsManager.getDefault()
}
```

**The comment was false, and the plan builder's note is the proof.** `SmsManager
.getSmsManagerForSubscriptionId(int)` is public since API 22 and present in the SDK this app compiles
against — `javap` on the platform `android.jar` lists it. `minSdk` here is 26, so it is available on
**every** supported device. It is deprecated at API 31, not removed, which is why the fix carries a
suppression rather than a comment. `SimManager.readSmsc` already calls the same API on the same
pre-31 branch, so the project had been relying on it all along — in a different file, which is how a
false premise survives: nothing contradicted it where it was written.

So on every supported device below Android 12, a user who chose a line was silently sent from
whatever SIM the platform considered default. Mission §50 forbids exactly that.

**The attribution defect.** `effectiveSubId` — the *requested* id — was passed to
`recordSegmentSubmissions` and into the SENT/DELIVERED `PendingIntent`s. Both write the durable
per-SIM segment ledger, so the app attributed messages to a line that never carried them, and the
per-SIM "SMS today" counter was wrong for exactly the users most likely to check it. A fabricated
attribution is worse than a missing one, because it looks like evidence.

**The rule.** The id the user asked for and the id the manager reports it is bound to are two
different facts, and only the second may be recorded. `SendSimPolicy` decides, purely:

| requested | what the app knows | outcome |
|---|---|---|
| none (`-1` sentinel, "no selection") | — | send, recording what the manager reported |
| a SIM, provably **not** in the active list | device's own subscription list | **refuse** |
| a SIM, manager reports the same id | confirmed | send, recording that id |
| a SIM, manager reports a **different** valid id | contradiction | **refuse** |
| a SIM, manager reports nothing usable | platform withheld it | send, recording **unknown** — never the request |

Refusal sets the provider row `STATUS_FAILED` and records `SIM_UNAVAILABLE` on the ledger with a NULL
`submittedAt`, so the message cannot be counted by the daily submission counter. That also
**constructs `SmsSendFailure.SimUnavailable`**, which the audit found declared and never used.

The `null` row of that table is the one that matters most in the other direction: an unreadable
manager or an unavailable SIM list must **not** refuse. Refusing on ignorance would stop sending on
any device that withholds the list, which is a far worse failure than the one being repaired —
`READ_PHONE_STATE` is optional here, and `getActiveSims()` returns empty both when there are no SIMs
and when it simply cannot see them. The policy therefore takes all three states (`true` / `false` /
`null`), and a property test asserts over every combination that a recorded subscription equals the
request **only** when the manager confirmed that same id.

**Verified:** 9 policy tests + 2 source guards; one falsification, and it earned its keep — the first
version of the guard read the whole file, so it was satisfied by the KDoc that *names* the API it
forbids and **passed with the defect reintroduced**. It now scans executable text only. That is the
second time this session a naive `contains()` guard has matched its own documentation; the pattern is
worth stating plainly: a source guard must scan code, never prose. 1711 tests total, 0 failures;
`lintDebug` 0 errors; `assembleDebug` green.

**Still open:** the legacy pull path's task model carries no `subscriptionId`, so a send requested
through it cannot name a SIM at all; and MMS has no SIM selection anywhere (its `triggerSend` takes
no subscription), so §50 is unaddressed for MMS by construction rather than by bug.


### The security invariants are guarded, and the guards were verified to bite

Twenty-one source-level guards live in `GatewayArchitectureGuardTest`, in the same style as the
existing host guards and for the same reason: their failure mode is a regression by **addition** — a
new helper or convenience path that no behavioural test happens to exercise.

| Guard | Protects |
|---|---|
| `noSourceRelaxesCertificateOrHostnameValidation` | §73/§41 — no trust-all or hostname bypass |
| `theScheduleRegistryStoresNoPlaintextRecipientOrBody` | §41/§43 — no plaintext recipient, body, or WorkManager payload |
| `recipientsAreOnlyEverLoggedAsTokens` | §43 — the three concrete leaks that were fixed cannot return |
| `bothSendPathsPersistAnAtMostOnceMarker` | §78 — both remote send paths keep their marker |
| `theSchedulerRefusesToPersistRatherThanDowngradeToPlaintext` | §41 — no plaintext fallback on Keystore failure |
| `theCommandRecoveryPrimitivesAllHaveACaller` | §46 — recovery code cannot go back to having no caller |
| `theDrainNeverMakesASendReRunnable` | §78 — the re-runnable command set stays free of `SEND_SMS` |
| `theSelectedLineIsNeverSilentlyIgnored` | §50 — the pre-31 branch keeps selecting the chosen SIM |
| `theSendLedgerNeverRecordsTheRequestedSimAsIfItWereUsed` | §50 — the ledger records the read-back |
| `theLegacyPullPathCarriesTheWebsCorrelationKeyIntoTheSend` | §49 — the key reaches the send on the live transport |
| `theVerificationSweepIsActuallyWiredToTheSupervisor` | §35 — the sweep is not a declared no-op |
| `theMmsSendResultHasAPlaceToArrive` | the MMS outcome is observed, declared and never written into the SMS ledger |
| `theLocalSendEndpointIsRetrySafe` | §78 — the LAN send endpoint dedupes and says when it cannot |
| `theQueueRecordsWhichDeviceQueuedACommand` | the drain never touches a locally-queued row |
| `noRecipientReachesALogUnderAnyName` | §43 — the rule, not one spelling of it |
| `aFailedInboxWriteIsNeverReportedAsAVisibilityDelay` | §16 — a lost inbound message cannot look routine |
| `aHeldInboundMessageIsActuallyRetried` | §16 — a durable hold that nothing drains is worse than none |
| `theTrustPredicateHasExactlyOneDefinition` | §73 — key material and history go to one set of devices, decided once |
| `noMmsPayloadIsSilentlyDropped` | §52 — a null part stream is not a successful write |
| `everyCallerOfAnMmsSendHonoursTheResult` | a refusal cannot be discarded or shown as sent |

**A guard that never fails is decoration**, so each was verified by temporarily reintroducing the
defect it protects against:

- writing `put("phone", …)` back into the scheduler made **exactly** `theScheduleRegistryStoresNoPlaintextRecipientOrBody` fail;
- adding a real `conn.setHostnameVerifier { _, _ -> true }` made **exactly** `noSourceRelaxesCertificateOrHostnameValidation` fail — which also proves the guard reads code and not comments, since the two files that *mention* that API in KDoc did not trip it.

Both injections were reverted and confirmed byte-clean (`git diff --name-only` reports nothing for the
reverted file). Worth recording: one injection silently failed to compile, so the guard was not
actually exercised and the "pass" meant nothing — the second attempt used compilable code. A guard
test verified against a violation that never compiled is a false negative, and only running the
check twice caught it.

### Plaintext at rest, in two places rather than one

The audit found that `GatewayScheduler` persisted the recipient and the message body in cleartext
SharedPreferences. Fixing that exposed a **second** copy in the same feature: the WorkManager
request carried `KEY_PHONE` and `KEY_MESSAGE` in its `inputData`, and WorkManager persists its input
data in its own database. Encrypting the registry alone would have left the body in plaintext
elsewhere.

Both are closed:

- The registry stores `phoneC`/`messageC` — AES-256-GCM, Keystore-backed, through the existing
  `SecureStore`. Legacy plaintext keys are still **read**, so entries written by an older build keep
  working, but they are never written again: every save rewrites the whole index, so the first
  schedule, cancel, update or send after the upgrade replaces them.
- The WorkManager request now carries **only the schedule id**. The worker reads the recipient and
  body from the encrypted registry by id.

**No plaintext fallback (mission §41).** If `SecureStore.encrypt` fails, `saveIndex` writes nothing
and returns false; `schedule` then throws `secure_storage_unavailable` instead of downgrading. A
Keystore failure produces a refused request, never a plaintext row.

One consequence worth stating: a ciphertext that cannot be **decrypted** yields an entry with an
empty recipient. The worker refuses to send it, marks it `content_unreadable`, and reports it —
rather than sending to a guessed or empty address.

### Schema 20: the command lease

`remote_commands` gained the nine fields mission §45 lists as missing: `attemptCount`, `leaseId`,
`leaseExpiresAt`, `claimedAt`, `executedAt`, `completedAt`, `resultEventId`, `lastErrorCode`,
`clientMessageId`. Additive, and every one nullable except `attemptCount`.

**A Kotlin default is not a SQL default**, and the migration test caught the difference: the entity
declared `val attemptCount: Int = 0`, Room generated `attemptCount INTEGER NOT NULL` with **no**
DEFAULT, and my `ALTER ... DEFAULT 0` therefore disagreed with the entity set. That is not cosmetic
— `ALTER TABLE ... ADD COLUMN ... NOT NULL` with no default is rejected by SQLite outright, so every
existing user's command rows would have failed to migrate. The fix is `@ColumnInfo(defaultValue = "0")`,
which makes the entity and the migration agree *by construction* rather than by a reviewer noticing.

**Lease semantics, and what reclaim deliberately does NOT do.** A claim now records who holds the
command and until when, and increments `attemptCount`. `reclaimExpiredLeases` returns a command whose
lease has expired to RECEIVED — without it, a process death mid-execution left the row claimed
forever and every redelivery was answered with silence, so GMweb's ledger never resolved
(Blocker 15).

Reclaim only makes the row *claimable again*. It does **not** decide that the send did not happen.
Duplicate protection belongs to execution (mission §47), not to lease recovery — conflating the two
is how you get a lease sweep that re-sends an SMS. The v20 migration test pins that a pre-lease row
(no `leaseExpiresAt`) is deliberately *not* matched by the sweep, so recovery for those is the
drain's job rather than a timestamp comparison against NULL.

**Also fixed, in passing:** `MigrationToV19SqlTest` asserted the *global* `CURRENT_SCHEMA_VERSION`,
so every new migration broke the previous migration's test. Each per-migration test now asserts only
its own boundary, and the global constants are pinned in exactly one place — the newest test.

### Blocker 14: the live §78 violation, and how the ambiguity is resolved

This was the one **live** violation of mission §78 — *"No remotely requested SEND_SMS command may
cause the same SMS to be sent twice"* — on the transport that actually runs (`LEGACY_PULL`), not on
the dormant strategic path.

`EveSmsQueue.drainOne` persists `status = ACTIVE, submittedOnce = true` and **then** invokes the
native sender (`:566-586`). `bootstrap` re-queued every interrupted `ACTIVE` record regardless
(`:322-324`). So a process death between those two points sent the SMS a second time on the next
start. The marker that existed to detect exactly this — `submittedOnce` — was written, persisted and
restored, and never read as a guard.

**The hard part is that the marker is written BEFORE the side effect**, so from the next start
"about to send" and "already sent" are indistinguishable. There is no way to tell them apart
locally, so the tie must be broken deliberately:

- **Requeue** (the old behaviour) risks an irreversible real-world action.
- **Do not requeue** risks a message that never sends, reported honestly.

The mission makes the choice: a duplicate SMS is irreversible, and §74 forbids solving duplicates in
the UI. So the record is marked terminal `FAILED` with `REASON_INTERRUPTED_AFTER_SUBMIT` and
`manual_review_required`, which surfaces to GMweb through the normal ACK path. **Nothing is silent**
— it is visibly unresolved rather than silently repeated *or* silently dropped. An interrupt *before*
any submit is still safely retried, because nothing left the device.

The verdict is also persisted immediately rather than waiting for an unrelated mutation to trigger a
write, so the stored record cannot keep saying `ACTIVE` after the run has decided otherwise.

A future improvement, noted rather than done: the record carries `to`, `text` and
`nativeSubmitStartedAt`, so the Sent folder could be consulted to distinguish the two cases. That is
a better answer than either branch above, and it is not attempted here because guessing wrong in
either direction is worse than an honest "manual review".

### The consent gap was real, and it was the same bug shape again

`SecureCommandPoller` gated on `!prefs.isEnabled || prefs.gmwebUrl.isBlank()` while the legacy
`OutboxPoller` used `GatewayAccessPolicy.canTransmit(hasConsent, isEnabled)`. So revoking gateway
consent stopped the pull bridge but **not** the strategic command channel: a consent-revoked device
would still claim and execute remote commands (Blocker 16). The poller now calls the same policy
function, and — as with the prerequisite evaluator — the fix is to *call* the shared rule rather
than restate it. This is the fourth instance of the same root cause in this work: a rule restated at
a call site loses a clause.

### What was missing, and why it mattered

The audit found reconciliation for provider↔mirror (24 h, both directions, durable cursors) but
**nothing that compares the mirror against the outbox**. So a message the phone knew about but whose
realtime notification was missed left no trace at all: no outbox row, nothing logged, green
gateway. `ChangeRouter.kt:126-133` even documented the gap as not implemented. That is the silent
sibling of Blocker 1 — fixing the relay makes detection run in the background, and reconciliation is
what catches anything that slipped through before it did.

### A time window, not a row cursor — for a reason specific to this table

The obvious incremental design is a "rows after id N" keyset cursor. It cannot work here: `messages`
has a **composite primary key `(source, providerId)` and no autoincrement id**, and rows are inserted
in arbitrary date order — the history backfill writes OLD messages long after they were sent. A
row-id cursor would step over newly-backfilled older rows entirely, so the reconciler would be blind
to exactly the rows most likely to have been missed.

A window that moves with the clock cannot have that failure. It is backed by the existing
`Index("date")`, and mission §35 explicitly endorses "recent time window". It also needs no new
cursor storage, so no schema change: the durability that a cursor would provide is already provided
by the window moving forward on its own.

### The lookup cannot be an anti-join

The canonical event id is **computed** (a UUID over source/providerId/date), not a column, so SQL
cannot join the mirror to the outbox on it. Reconciliation therefore does one indexed lookup per
candidate row — which is why the window must be bounded, and why the decision logic
(`MirrorReconcilePolicy`) is pure and takes the already-resolved set of existing ids rather than a
lookup predicate. That keeps every decision testable without Room or a coroutine, and keeps the
identity rule in one place: the test asserts `MirrorReconcilePolicy.canonicalId` equals what
`messageCreated` produces for the same row, because if those ever disagreed reconciliation would
enqueue duplicates of messages that are already queued.

A reconciled event is `RECONCILIATION`-sourced at weight 10, and deliberately carries **no history
ordinal** — so it cannot disturb the ack watermark. It still goes through the ADR-006 firewall, so
reconciliation can never become a route around a LOCAL_ONLY decision.

### Blocker 1 is fixed, and why it was the most serious finding

The SMS/MMS `ContentObserver` was registered ONLY by `HomeViewModel.init` and
`ConversationViewModel.init`, and `ChangeRouter.route` had exactly one caller. With no ViewModel
alive — app swiped away, screen off after the process was reclaimed, any headless start — a newly
arriving SMS produced **no replication at all**: no outbox row, no log line, and a green gateway.
The mission's central promise held only while a UI happened to be on screen.

`GatewayChangeRelay` is owned by `GatewayService`, so its lifetime is exactly "the gateway is
running", which is what replication being on means. It is registered **even while offline**, on
purpose: an event must become durable locally whether or not it can be uploaded yet.

Two things were checked rather than assumed:

- **Running alongside the UI's observer is safe.** Both may fire for one change, so `route` can be
  called twice. `ProviderRepairQueue.enqueue` is documented "idempotent for repeated notifications
  of the SAME change" — it bumps a generation rather than inserting a duplicate — and the worker
  then re-reads the same provider row and finds it UNCHANGED. The cost is a redundant read, not a
  duplicate event. Making the relay the *only* router would have meant touching the UI's change
  handling for no correctness gain.
- **The relay does not touch `ThreadMessageCache`.** That is UI cache invalidation and is
  meaningless without a UI; the ViewModel's observer still owns it.

It is also the first fix in this work that a user can verify on-device without a debugger: the
report now prints whether UI-independent detection is running. That mattered because this defect's
whole danger was that it was silent.

### Identity convergence: solved, and why "same formula" was not enough

`MESSAGE_CREATED` had two namespaces — realtime produced
`evt:v3:MESSAGE_CREATED:<source>:<providerId>:<dateMs>` and the history sweep produced
`evt:replica-v4:<source>:<providerId>:<dateMs>` (Blocker 7). The outbox unique index cannot dedupe
across them, so the §33 race could put the same message in the outbox twice with two different
`eventUuid`s and one shared `payload.messageId`.

Both paths now compute **one** identity, so the unique index dedupes them.

But converging the formula alone would have introduced a *worse* bug, and this is the part worth
remembering. `enqueueHistorical` began with `if (idOf(eventId) != null) return`. With one shared
identity, "the row already exists" is now the COMMON case — realtime got there first — so the sweep
would skip every such message. And `advanceHistoryAckWatermarks` walks ACKED history rows forward
from `ackedContiguousOrdinal` and **stops at the first gap**: a row with no `historyOrdinal` is
exactly such a gap, so the watermark could never advance again. History would have reported
`CATCHING_UP` forever while every event was in fact acknowledged — a silent permanent stall,
introduced by the fix for a different silent failure.

So the sweep now **merges** rather than skips: `stampHistoryMetadata` attaches
`historySource`/`historyGeneration`/`historyOrdinal` to the realtime row, and the checkpoint
advances only if the stamp actually landed.

The stamp carries `AND historyGeneration = 0`, which makes ordinals write-once. That guard matters
for the same walk: re-stamping a row with a new ordinal would leave the *old* ordinal missing from
the sequence, stalling the watermark in a different way. A replay or a second sweep therefore
leaves an already-stamped row untouched and does not consume an ordinal. `EventIdentityConvergenceTest`
executes the real `STAMP_HISTORY_SQL` against SQLite to prove an already-stamped row is never
rewritten.

Converging identity is also only *safe* because of the Phase 2 fix: a replayed history sweep now
recomputes ids it may have already sent, and the server answers DUPLICATE — which the uploader
acknowledges (mission §16) instead of retrying forever. The two changes depend on each other.

**This is the third place the watermark walk has bitten**, after the retention DELETE and the
`markDead` state guard. All three share one root cause: the walk depends on row *presence* and on a
contiguous ordinal sequence, and nothing in the schema declares that dependency. A future change to
this table should look for it first.

### Retention, and the trap it had to avoid

The outbox was append-only with no `DELETE` anywhere (Blocker 10), so ACKED rows accumulated for
the life of the install — a 360k-message history plus steady traffic grows that table forever.

The obvious implementation (`DELETE ... WHERE state='ACKED' AND ackedAt < cutoff`) is **wrong
here**, and subtly so. `advanceHistoryAckWatermarks` walks ACKED history rows forward from
`ackedContiguousOrdinal` and **stops at the first gap**. Delete a row beyond the watermark and the
walk breaks immediately, so the watermark can never advance again: history would report
`CATCHING_UP` forever while every event was in fact acknowledged — a permanent, silent stall
introduced by the cleanup itself.

So a history row is removable only when `historyOrdinal <= ackedContiguousOrdinal` — the watermark
has already moved past it, and nothing will read it again. Non-history rows
(`historyGeneration = 0`) have no watermark to worry about. Rows with `ackedAt = 0` are kept
because their age cannot be established; DEAD_LETTER rows are never touched, since they are the
record that something failed.

`OutboxRetentionSqlTest` runs the **real** `PURGE_ACKED_SQL` (the same constant the DAO's `@Query`
uses) against a real SQLite database and asserts, among others, that a history row *beyond* the
watermark survives however old it is. A test that only checked the happy path would have missed
exactly the case that matters.

The cleanup runs as the mission's `gmweb-outbox-cleanup` periodic worker — unique, `KEEP` policy,
no network constraint (mission §37), scheduled from app start so a reboot cannot lose it.

### Starvation, and how it was actually fixed

The claim used one global `ORDER BY CASE priority WHEN 'REALTIME' THEN 0 ELSE 1 END, id LIMIT 200`.
With 200+ due realtime rows, **no background row entered the candidate window at all**, so a
360k-message history backfill could stall forever behind ordinary traffic.

Two changes were needed, and neither alone was sufficient:

1. **Fetch per group.** `claimableForeground` / `claimableBackground` each cap at
   `MAX_BATCH_EVENTS`, so the total read is the same 200 rows as before but a realtime flood can
   no longer hide the background group. Merging cannot wrap an `IN (...)` list generated at
   runtime, so the two SQL forms are mirrored from the Kotlin priority lists and a test asserts
   every value appears in both — that is what stops the SQL and the model drifting.
2. **Reserve a share.** `selectFair` gives the background group 30 of every 100 slots when both
   groups have work, and returns unused quota to the other side so a quiet foreground does not
   send half-empty batches.

Two bugs of my own were caught by these tests before they could ship, both worth recording
because both were silent:

- the accumulator compared the *running total* against a *per-call* limit, so the background
  phase saw `70 >= 30` and contributed **nothing** — the exact starvation the change was meant to
  remove, reintroduced one layer down;
- the top-up pass restarted at index 0 and re-added every row, so a batch built from 20
  candidates contained all 20 **twice** — the same event uploaded twice in one request. There is
  now an explicit test that a row appears at most once.

### The three defects this round actually closes

1. **A `401` dead-lettered the entire batch** (audit Blocker 4). A rejected credential now keeps
   every event, records `AUTH_REQUIRED` on each row, and lets the upload gate pause on
   `AuthorizationRequired`. The old rule is pinned by a test that asserts the old boolean *would*
   have killed the batch.
2. **`DUPLICATE` was never acknowledged** (audit Blocker 5), so mission Test F could not converge —
   the retry looped forever. `ACCEPTED` and `DUPLICATE` are both acknowledgements now, and a
   missing `serverSequence` no longer withholds the ACK.
3. **Recovery was unbounded** (audit Blocker 3): `resetSendingToPending` requeued EVERY `SENDING`
   row regardless of age, which would steal a row a live uploader was mid-upload. Recovery is now
   bounded by a configurable lease timeout, and a row left `SENDING` by a pre-lease build (no
   `inFlightSince`) is still recovered rather than stranded.

### A latent test defect fixed in passing

`HistoryAckCheckpointDeviceTest` inserted a `PENDING` outbox row and then called `markDead`, which
only moves a row in `SENDING`. That call had been a silent no-op, so the fixture asserted nothing
about dead letters. The fixture now inserts the row as `SENDING`, which is what that test always
meant (this is the same trap recorded in the v3.4.8 notes).

- **`BlockerCategory.KEY` does not hold the upload loop.** Reading it means a Keystore probe on
  every iteration, and it is nearly moot because encryption happens at ENQUEUE — a missing key
  usually means the outbox is empty, not that uploads are refused. The real damage there is
  silent event loss at enqueue (Blocker 6), which Phase 2 must address.
- **PERMISSION and GRANT do not hold realtime upload.** They gate history only; realtime upload
  never reads the Telephony Provider.
- **`deviceRevoked` has no producer.** No local model of this phone being revoked exists; the
  blocker is defined and tested but nothing sets it. A rejected credential reports
  `AuthenticationRequired` instead. Needs a control-plane signal.
- **`TrustedDevicePolicy` duplicates `ConversationKeyRepository`'s predicate.** Unifying them
  requires first adding a JVM test for the original, which has instrumented coverage only.

---

## The enrollment rescue: a hotfix that could not do its job, in two independent ways

`RegistrationManager.registerInternal` calls `recoverDeadLetter()` after a successful enrollment,
documented as the PR-11 hotfix: "a batch signed BEFORE `/identity` completed … is a 401 the server
would accept on retry". The DAO's KDoc claimed it "resets only the current dead-letter cohort" with
"`attemptCount` untouched". Neither claim was true, nothing executed the statement outside Room, and
no test covered it — so both claims had been prose since the day they were written.

### Defect 1: the predicate was `WHERE state = 'DEAD_LETTER'`

Not "the enrollment cohort" — **every dead letter ever recorded**, on every successful enrollment.
The stakes changed the moment a `401` began to wipe credentials and re-enroll
(`ControlPlaneAuthPolicy`): the rescue now runs on every auth-recovery cycle.

What it resurrected is the point. `OutboxRetryPolicy` never dead-letters on 401/403 — those are
`RETRY` — so a row reaches `DEAD_LETTER` with an auth-shaped reason only by **exhaustion** (25
attempts, hours of a real outage). Everything else at `DEAD_LETTER` got there because the server
**judged the event**: `400`/`404`/`405`/`422` contract failures, a permanent per-item rejection. A
fresh credential cannot change a verdict about the payload. Three costs, all real:

1. **Doomed rows displace live ones.** A batch is bounded (100 events / 512 KB), so each guaranteed
   failure occupies a claim slot a live event needed.
2. **The reason was erased.** The reset cleared `lastErrorCode`, `failureHttpStatus`,
   `deadLetteredAt`, `failureAppVersion` and `lastErrorMessageSafe` — the Phase 2 columns whose
   entire purpose is answering "why is this event stuck?" *on the row*. A rescue that erases the
   reason converts a permanent, explained failure into an unexplained one, and it did so on every
   re-enrollment.
3. It is not a one-off: it recurs on every auth-recovery cycle.

### Defect 2: `attemptCount` untouched meant the rescue was a no-op

`EventUploader` tests `exhausted(it.attemptCount)` **before** `markRetry`/`markDead` increment it, so
a dead letter is written at 26 or more. Leaving the count alone — documented as deliberate — meant a
rescued row was **already past `MAX_ATTEMPTS`**: its next batch failure, *any* failure including a
transient network blip, dead-lettered it again with **zero retries**. For precisely the case the
hotfix was written for, the row came back for one attempt and died.

### The fix

Both defects live in the predicate, so the statement moved to a shared constant
(`GatewayEventOutboxEntity.RESCUE_ENROLLMENT_SQL`) that a test can execute — the same treatment
`PURGE_ACKED_SQL` and `KEY_REF_MATCH_SQL` already had.

- **Narrowed to codes a credential repairs:** `AUTH_REQUIRED`, `AUTH_EXPIRED`,
  `IDENTITY_NOT_REGISTERED`, `CRYPTO_KEY_UNAVAILABLE` (the Keystore case the original comment
  named). `DEVICE_REVOKED` is deliberately **excluded** — a 403 is the server withdrawing
  authorization, not a stale credential, and the code's own rule is "replication must stop, not
  retry". Contract failures are excluded because a new credential does not change a verdict about
  the event.
- **Legacy rows** dead-lettered before `lastErrorCode` existed are still recognised, by
  `failureHttpStatus = 401` or `failureCategory IN ('HTTP_AUTH','TLS')` — the same mapping the
  current classifier produces, so old and new rows behave alike.
- **`attemptCount = 0`** grants the full budget the row is owed under a credential that
  demonstrably just worked, and **`nextAttemptAt = 0`** makes it immediately claimable instead of
  waiting out a stale backoff of up to five minutes.
- **Rows the predicate does not match keep their state and their reason.**

Why the re-budget cannot loop without bound: the rescue runs only after a *successful* enrollment —
evidence the credential was accepted — and only for codes a fresh credential repairs. A row that
exhausts again under a still-broken credential dead-letters normally. The tradeoff is that the
rescue resets the attempt counter and clears the reason for the rows it revives, so "how many times
did this row fail in total before it last succeeded" is not recoverable. Preserving it needs a
dedicated column and a schema migration; recorded here as a deliberate, bounded tradeoff rather than
a silent one.

### The same defect had a second home, and that is the real lesson

Tracing callers of the rescue primitives turned up `recoverCryptoDeadLetter`, the one-time
v3-rollout recovery called from `EventUploader.start()` behind `prefs.cryptoV3DeadLettersRecovered`
(so it runs once — no hot loop, which was worth confirming rather than assuming). Its statement,
`resetCryptoDeadLetterToPending`, had **defect 2 exactly**: `attemptCount` untouched, next to
`nextAttemptAt = 0`, while its KDoc promised the good behaviour the enrollment rescue only claimed.

For the crypto rescue this is worse than wasteful, because it consumes a **one-time preference
flag**: the rescue resets the rows to `PENDING`, they are claimed under a budget they have already
exhausted, the first failure dead-letters them again, and `cryptoV3DeadLettersRecovered` now says
"already recovered" — so that cohort can never be rescued again. A no-op that also burns its own
retry switch is a permanent loss of the rows it existed to save.

Its **predicate is left version-shaped**, and that difference is deliberate rather than an
oversight: this cohort is "events emitted by a protocol whose server contract was not live yet", so
the crypto version is the useful selector and the server's reason was a symptom of the missing
contract. What keeps a version-shaped predicate from becoming a standing rule is the one-time flag,
which is now stated where the predicate is defined.

Because the defect appeared twice, the guard was generalised instead of extended. `EventUploader`
consults `exhausted()` *before* the increment, so **no** dead-letter reset may leave `attemptCount`
alone; the rule is now asserted over every statement in the main sources that writes dead letters
back to service — found through the write, so the read-only diagnostics that legitimately end on a
bare `state = 'DEAD_LETTER'` are not swept in. A rescue that does not restore the budget is not a
rescue, and a third reset added later is covered by construction. All three clauses were falsified
individually: dropping `attemptCount = 0` from the crypto rescue (caught at `GatewaySync.kt:364`),
replacing its `nextAttemptAt = 0` with a self-assignment, and planting a filtered-less reset.

### Verification

`EnrollmentRescueSqlTest` (14 tests) runs the **shipped** statements against real SQLite — neither
has an unbound parameter beyond the crypto cohort's version, which is substituted as the DAO's
`@Query` binding would be. It pins the code list as an explicit literal *and* then feeds every code
in the shipped list back through the real statement, so neither a stray addition to the list nor a
query that stopped using the constant can pass. It pins the interaction that makes defect 2 a defect
(the same `OutboxRetryPolicy.exhausted` call the uploader makes), that the crypto rescue restores
the budget, that it touches only the cohort it names (`cryptoVersion >= 2`, leaving v1 and v0
alone), and that the two rescues are independent — an auth failure on an old protocol version
belongs to the enrollment rescue, not the contract upgrade.

The guard (`theEnrollmentRescueStaysNarrowAndReBudgeted` plus the generalised
`everyDeadLetterRescueRestoresTheRetryBudget`) pins what the SQL tests cannot, because a statement
can always be replaced by an inline copy at the annotation: that both DAOs execute the shared
constants, that no production source spells an unfiltered reset again, and that every reset
re-budgets and re-schedules.

One finding deserves recording because it is the reason the first test run of the previous round
aborted: the test helper's positional `setString` bindings had **one missing parameter**, shifting
`lastErrorCode` into `failureAppVersion` and the message into `lastErrorCode`. Every row therefore
looked like it had no stored code, so the rescue matched nothing and the test failed in a way that
looked like a product bug. A positional-binding shift makes a test assert confidently against the
wrong column. Adding `cryptoVersion` to the same helper renumbered every binding, so the hazard is
now called out at the binding site and the existing `lastErrorCode` assertions are what prove the
numbering.

- Suite: **1919 tests, 0 failures** (1903 at the start of this work, 1915 after the enrollment fix);
  `:app:lintDebug` 0 errors; `:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin` succeed;
  `:app:kspReleaseKotlin` regenerated, and Room's generated **release** DAO carries both budgeted
  rescues.
- Everything above is JVM tests, compilation and static verification. No device or emulator is
  available in this environment, so §69/§71 validation remains unrun.
