<!--
Sync Impact Report
==================
Version change: unfilled Spec Kit 1.0.6 template scaffold -> 1.0.0 (initial ratification)
Modified principles: none (first ratification; the template carried no project principles)
Added sections:
  - Core Principles I-XV
  - Cross-Repository Contract: EVE -> GMweb -> Messages
  - Development Workflow & Quality Gates
  - Governance
Removed sections: none
Follow-up TODOs: none. Every placeholder is filled; no deferred tokens remain.
Note: this report is scratch material for review of the amendment. Remove this
comment block before the constitution is committed.
-->

# Messages Android Constitution

## Core Principles

### I. Physical Submission Is the Irreversible Boundary

Code immediately before the modem submit is safety-critical and MUST be treated
as such. Lifecycle-sensitive tasks MUST pass every required validation
immediately before this boundary, not merely when the work was originally pulled.

The boundary lives in `app/src/main/java/com/autonomousone/messages/sms/SmsSender.kt`:
`dispatch()` (`:213`) issues `sendMultipartTextMessage` (`:266`) and
`sendTextMessage` (`:274`). `SmsSender.kt:81` records the rule as policy —
this is the only place `SmsManager` is ever touched for a send.
`sms/GatewayOutgoingPipeline.kt:12` repeats it: nothing calls `SmsManager`
outside that pipeline.

**Rationale**: once the radio accepts the submit the message is irreversible and
carrier-billable. No later check, retry or reconciliation can undo it.

### II. Final Validation

When a gateway task requires validation, the device MUST call the gateway
validation endpoint immediately before physical submission.

`valid=false` / superseded MUST mean zero modem calls. The task MUST NOT
continue because it was valid when originally pulled.

- Gate: `eve/EveSmsQueue.kt:497` inside `drainOne()` (`:492`), implemented by
  `finalValidationPassed()` (`:548`); the native sender is invoked only after
  the gate at `:513-515`.
- Validator: `gateway/GmwebTaskValidator.kt:80-128` against
  `PATH = "/gateway/validate"` (`:58`). Every non-2xx, timeout, blank or
  unparsable response MUST resolve to `Unavailable`, never to "valid".
- `Unavailable` MUST fail closed: `Status.DEFERRED` with bounded backoff
  (`EveSmsQueue.kt:595-620`).
- The pull-time check (`gateway/OutboxPoller.kt:264-292`) is an optimisation
  only. It MUST NOT be treated as the correctness barrier.

**Scope limit (binding)**: tasks with `requiresValidation=false`, or with no
`meta` object at all, are ungated by contract
(`EveSmsQueue.kt:549`; `docs/gmweb-presend-validation.md:66-68`). Any claim
that "a superseded task cannot be sent" MUST be scoped to
`requiresValidation=true`.

### III. Stable Request Identity

The gateway request ID is the durable logical identity for outbound gateway
work. Retry, process restart, reconnect and re-pull MUST NOT create another
physical SMS for an already settled logical request.

- Field: `EveSmsQueue.Record.gatewayRequestId` (`eve/EveSmsQueue.kt:132`),
  distinct from the local `requestId` (`:115`).
- Dedupe on enqueue: `EveSmsQueue.kt:391-395`.
- Supersession is scoped by `requestId` / `serviceKey` / `generation`.
  The recipient phone number MUST NEVER be used as identity for supersession
  (`docs/gmweb-presend-validation.md:69-71`).

### IV. Durable Local Dedupe

Deduplication that must survive a restart MUST NOT exist only in RAM. The
minimum durable state necessary to prevent repeated submission MUST be
persisted, with a database-level uniqueness constraint rather than
check-then-act.

Durable anchors that MUST remain the dedupe authority:

- `gateway_event_outbox`: `Index(value = ["eventUuid"], unique = true)`
  (`data/GatewaySync.kt:73`) with `insertOrIgnore` (`:136-140`).
- `remote_commands`: `Index(value = ["idempotencyKey"], unique = true)`
  (`data/GatewaySync.kt:276`) with `insertOrIgnore` + `getByIdempotencyKey`
  (`:314-315, 332-333`).
- `trust_statement_outbox`: `Index(value = ["trustSequence"], unique = true)`
  (`data/TrustedDevices.kt:56`).

**Known violation to close (MUST NOT be extended)**: the outbound gateway queue
persists to SharedPreferences and trims to the last `MAX_PERSISTED_RECORDS =
300` records and `MAX_IDEMPOTENCY_KEYS = 500` keys
(`eve/EveSmsQueue.kt:44-45, 238, 697-703`), so a sufficiently old redelivery
falls through `enqueue()` and can produce a second physical SMS. The genuinely
durable Room path is currently bypassed because
`GatewayOutgoingPipeline.ENQUEUE_ALL_SENDS` defaults `false`
(`sms/GatewayOutgoingPipeline.kt:30`). New work MUST NOT add reliance on the
trimmed store, and any "never duplicated" claim MUST name which store backs it.

### V. ACK Semantics

ACK MUST distinguish at least `sent`, `failed` and `superseded`.
`superseded` MUST NOT be mapped to an ordinary failure when that would trigger
a retry. Repeated ACKs MUST converge without double counting or resending.

- Canonical outcomes: `eve/EveSmsQueue.kt:61-63`.
- Status to outcome mapping: `EveSmsQueue.kt:170-177`.
- `SUPERSEDED` is terminal and deliberately not retryable
  (`EveSmsQueue.kt:152-159`); it is never re-offered (`:325`) and
  `sweepDeferred()` touches only `DEFERRED` (`:637`).
- Reason mapping: `gateway/GatewayAckTracker.kt:34-42`; payload construction:
  `gateway/OutboxPoller.kt:130-145` (`sentAt` only when the outcome is
  `sent`) — never report a message as delivered when it was not.

**Known violation to close**: `OutboxPoller.kt:326-333` ACKs
`failed`/`device_send_failed` after `DRAIN_TIMEOUT_MS = 120_000` while the
local record may still be `QUEUED` and can later be sent. The "no second ACK"
guarantee is process-scoped by construction, because
`GatewayAckTracker.acked` is a RAM-only `LinkedHashSet`
(`GatewayAckTracker.kt:46-49, 105-111`). Any convergence claim MUST be scoped
to a process lifetime until this is made durable.

### VI. Unknown Send Outcome

If the application cannot prove whether Android or the modem accepted a message,
it MUST NOT blindly retry a potentially submitted SMS. Uncertain outcomes MUST
be represented explicitly where the contract requires it.

- `sealed interface SendOutcome { Accepted, Rejected }` —
  `sms/SmsSender.kt:190-196`.
- `enum class SegmentCallbackState { PENDING, CONFIRMED, AMBIGUOUS, FAILED }` —
  `data/SendSegment.kt:21`; `AMBIGUOUS` is the accepted-but-unconfirmed
  submit (`:17-19`).
- `enum class DeliveryEvidence { DELIVERED, TEMPORARY, FAILED, UNKNOWN }` —
  `sms/SmsStatusPolicy.kt:27`; `UNKNOWN` MUST never be materialised as
  `STATUS_FAILED` (`sms/SmsStatusReceiver.kt:227, 271`).

An ambiguous modem verdict MUST never be recorded as a definitive failure, and
MUST never invite a duplicate resend.

### VII. Process Death

Worker and process death MUST be part of normal state-machine design, not an
exception path. Restart MUST resume safely without resending settled work.

- `bootstrap()` reloads the persisted queue (`eve/EveSmsQueue.kt:304-355`).
- `DEFERRED` records inside their backoff window are held, not offered
  (`:328-329`), and released by `sweepDeferred()` (`:635-655`).
- WorkManager workers: `gateway/GatewayScheduler.kt:178-211` and
  `sms/ScheduledSms.kt:84-103`.

**Known violation to close**: `EveSmsQueue.kt:321-323` re-queues an
interrupted `ACTIVE` record. Because `ACTIVE` is set (`:499-511`) *before*
`senderFn` is invoked (`:513-515`), a record whose native submit already
reached the radio can be re-validated and submitted a second time.
`submittedOnce` (`:125`) and `nativeSubmitStartedAt` (`:150`) are recorded
but never consulted before re-offering. Any crash-safety or exactly-once claim
MUST address this path explicitly.

### VIII. Local Message Data

Large Telephony datasets MUST be processed incrementally. A new, changed or
deleted SMS SHOULD update only the relevant local records and projections where
feasible. A full rescan of a hundreds-of-thousands-message history MUST NOT be
required for an ordinary incremental change.

- Keyset crawl on `(date, _id)`, never OFFSET —
  `data/TelephonySyncCoordinator.kt:998-1049`; the durable cursor is re-read
  and advanced inside each batch (`:1036, :1042`).
- Batches: `FIRST_BATCH = 500` (`:114`), `BACKFILL_BATCH = 500` (`:115`).
- Dual independent watermarks in `sync_state` —
  `data/Entities.kt:117-138`; monotonic guarded updates —
  `data/Daos.kt:248-260`.
- An unidentifiable change notification MUST degrade to a bounded targeted
  repair, never to a full scan — `data/ChangeRouter.kt:45-73`.
- Concurrent reconciliations MUST be serialized by an explicit single-flight
  boundary (`TelephonySyncCoordinator.kt:108, 216-219`).

### IX. UI Read Model

Room is the read-side source of truth where the architecture defines it. UI
refresh MUST consume incremental local state rather than repeatedly rebuilding
entire message history.

- `viewmodel/HomeViewModel.kt:649-684` observes
  `ConversationDao.observeAll(): Flow<List<ConversationEntity>>`
  (`data/Daos.kt:158-159`).
- An incoming SMS MUST commit an exact mutation and let Room invalidation
  repaint; a full provider scan on every incoming message is prohibited
  (`HomeViewModel.kt:612-621`).

**Known violation to close**: four `SmsEventBus` flows still mutate the same
Compose lists alongside the Room Flow
(`HomeViewModel.kt:622-638, 686-700`). A read model MUST have one owner; new
work MUST NOT add a third writer.

### X. Room Migrations

Destructive migration MUST NOT be used for production user message state as a
convenience. Room schema exports and migration tests MUST stay aligned with
released versions.

- Database version 12: `data/MessagesDatabase.kt:52`, `exportSchema = true`
  (`:53`); schemas exported to `app/schemas/` via
  `app/build.gradle.kts:101-103`.
- `fallbackToDestructiveMigration(dropAllTables = true)` occurs exactly once
  and is guarded by `BuildConfig.DEBUG` (`MessagesDatabase.kt:403-405`).
  This guard MUST NOT be removed or widened.
- Every schema change MUST bump the version and add a real `Migration`;
  exported schema JSON MUST NOT be hand-edited.
- Migration tests: JVM statement-pinning tests in `app/src/test`
  (`MigrationToV*SqlTest.kt`) and `ConversationKeyMigrationTest.kt` in
  `app/src/androidTest`.

`docs/room-migration-strategy.md` is **stale**: it documents only v1 to v2 and
still endorses the destructive fallback. It MUST NOT be cited as the current
policy; this constitution and the code are authoritative.

### XI. Background Work

WorkManager and Android lifecycle constraints MUST be respected. Background
reliability MUST NOT rely on an Activity remaining alive.

- `docs/adr/ADR-003-android-availability-doze-slo.md` is the governing text:
  "WorkManager is itself deferred in Doze — it is not a real-time wakeup
  mechanism."
- The gateway anchor is the foreground service
  (`gateway/GatewayService.kt:127-347`); `EveSmsQueue` is an in-process
  daemon thread started only from `gateway/GatewayServer.kt:115-119`.
- A liveness promise to a server MUST be backed by an OS-level mechanism
  (partial wake lock, restart watchdog, honest FGS types), and "gateway
  enabled" MUST be observable separately from "bridge live".

### XII. Security

Gateway credentials, device identity and cryptographic material MUST be
protected. Secrets, plaintext keys and unnecessary message content MUST NOT be
logged.

- Keystore-backed secret envelope: `utils/SecureStore.kt:22-57`.
- Device identity, non-exportable EC P-256 keys, fail-closed:
  `gateway/DeviceIdentity.kt:33-89`.
- Conversation key epochs at rest: `security/ConversationKeyVault.kt:13-34`.
- A crypto failure MUST NOT be downgraded into a plaintext storage fallback —
  `gateway/GatewayPreferences.kt:88-122` fails closed.
- `utils/DiagnosticLog.kt:11-46, 80, 94-95` sanitises phone numbers and
  excludes SMS bodies. New logging MUST go through it rather than raw
  `android.util.Log`, which currently still prints full phone numbers in
  `sms/SmsSender.kt:285-289, 293, 445, 467`.
- Files under `protocol/` MUST remain test-only and MUST NOT receive
  production key material (`protocol/message-crypto-v1.json:35`).

### XIII. Contract Compatibility

The app is a consumer of the GMweb gateway contract. It MUST NOT independently
invent new gateway state names or semantics. Protocol changes MUST be
coordinated with GMweb contract and version changes.

- `protocol/pairing-protocol-v1.json` MUST stay byte-identical to
  `GMweb-API/shared/pairing-protocol-v1.json`; both
  `.github/workflows/pairing-contract.yml` and GMweb's `ci.yml` compare the
  bytes and fail on drift.
- `security/PairingCapabilityContract.kt` MUST cover every capability in the
  schema.
- `docs/api/openapi.yaml` MUST be updated in the same change as any endpoint
  change.
- Canonical gateway state vocabulary lives in GMweb; the Android side MUST NOT
  introduce a parallel vocabulary.

### XIV. Testing

The safety-critical outbound flow requires tests for: pull, dedupe, pre-send
validation, superseded result, actual submit boundary, ACK, retry, restart,
reconnect, duplicate task and late revocation.

- JVM tests live in `app/src/test`; Room and gateway durability tests live in
  `app/src/androidTest`.
- Where behavior depends on the Android framework or `SmsManager`,
  instrumentation or device-level acceptance MUST be included **in addition to**
  JVM tests. JVM tests alone are not acceptance for those paths.
- `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` run in CI
  (`.github/workflows/build-debug.yml`). `connectedDebugAndroidTest` does
  **not** run in CI today, so device evidence MUST be recorded explicitly in the
  change report rather than assumed.
- Every v3.x release note records "NOT RUN — PHYSICAL DEVICE REQUIRED" for
  device-dependent claims. That statement MUST NOT be silently dropped.

### XV. Performance

Design and test against realistic message counts and long-lived installations,
not only empty emulators.

- No unbounded history operation may run on the user-visible path; the backfill
  crawl runs detached on a single MIN_PRIORITY lane with one projection rebuild
  (`TelephonySyncCoordinator.kt:728-772`).
- Complexity targets and the before/after table live in
  `docs/architecture-v2-sync.md`.
- A performance claim MUST cite a measurement at a realistic row count, not a
  reasoning argument.

## Cross-Repository Contract: EVE -> GMweb -> Messages

The three repositories are parts of one messaging system. Responsibilities:

- **EVE** decides *why* a notification should exist and owns service identity,
  lifecycle generation and business state.
- **GMweb** decides whether that logical notification is currently deliverable
  and owns the durable delivery/revocation state plus the gateway contract.
- **Messages Android** performs the irreversible physical SMS submission and
  owns final local dedupe, final validation and the modem boundary.

The system invariant for SMS lifecycle work is:

A notification from lifecycle generation N MUST NOT be physically submitted
after the service has durably advanced to generation N+1, unless physical
submission irreversibly completed before the invalidation barrier won the race.

Every implementation in this repository MUST map its mechanism to that same
invariant, and MUST NOT restate it in different words.

For a change touching more than one repository:

1. assign one shared feature ID (for example `stale-sms-revocation-v4`);
2. use the same identifier in specs, plans, ADR references and acceptance reports;
3. define the system invariant before modifying any participant;
4. implement the provider contract before the consumers;
5. preserve backward compatibility during staggered deployment;
6. test old/new combinations where rollout ordering creates risk;
7. converge each repository individually; and
8. run system-level acceptance before declaring the work complete.

EVE MUST NOT call a field/state that GMweb does not implement, and Android MUST
NOT assume a third vocabulary. Any mismatch requires a documented compatibility
adapter or version, not an implicit assumption.

## Development Workflow & Quality Gates

Spec Kit is the default engineering workflow for this repository.

- Trivial changes (spelling, comments, formatting, labels, version bumps) do not
  require the full workflow; every rule in this constitution still applies.
- Non-trivial bugs: `/speckit-bug-assess` then `/speckit-bug-fix` then
  `/speckit-bug-test`. Reproduce or establish root cause before patching; a
  green unit test is not proof that the production symptom is fixed.
- Features, refactors, schema, protocol and outbound-send changes:
  `/speckit-specify` then `/speckit-clarify` when ambiguous, then
  `/speckit-plan`, `/speckit-tasks`, `/speckit-analyze`, `/speckit-implement`
  and `/speckit-converge`. Do not implement before analyze reports the
  artifacts are coherent.
- Uncertain ideas: `/speckit-assess-intake` through `/speckit-assess-decide`.
  Only a GO decision becomes a specification.

**Definition of Done.** A change MUST NOT be reported as done because code
compiles or unit tests are green. The smallest appropriate combination of unit,
integration, contract, concurrency, migration, restart/process-death and
device-level evidence MUST be attached, and claims such as "exactly once",
"never duplicated", "race safe" or "durable" MUST be backed by an explicit
mechanism plus the test that exercises it.

**Documentation discipline.** Existing ADRs and architecture documents are
inputs to the specification process, not disposable legacy text. When a document
contradicts the code, the code and this constitution win, and the stale document
MUST be corrected or explicitly marked stale in the same change.

## Governance

This constitution supersedes other development practices in this repository. A
repository `AGENTS.md` rule that conflicts with a MUST in this document is a
defect in that file, not a licence to bypass the principle.

- **Amendments** require a written rationale, the affected principle, a
  migration note when behavior changes, and an update to this document's version
  and amendment date. Amendment is done through `/speckit-constitution`.
- **Versioning** is semantic: MAJOR for a backward-incompatible governance
  change or principle removal/redefinition; MINOR for a new principle or
  materially expanded guidance; PATCH for clarifications and wording.
- **Compliance review** expects every non-trivial change to state which
  principles it touches and how it satisfies them, and to record any principle
  it knowingly violates as a known gap rather than silently.
- **Known violations** recorded above are pre-existing defects. They MUST be
  closed deliberately or explicitly re-ratified; they MUST NOT be used as
  precedent for new work.

**Version**: 1.0.0 | **Ratified**: 2026-09-15 | **Last Amended**: 2026-09-15
