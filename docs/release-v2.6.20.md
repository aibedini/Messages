# Release v2.6.20 — Phase 1 Messaging Platform: durable gateway foundation

**versionCode 62 · Room schema v6 → v7 (additive)**

## Field report

The cloud path was fire-and-forget: `WebhookEngine` POSTed the SMS event from
a coroutine and admitted in its own comments that a failed upload was lost
forever ("in practice, SMS_DELIVER fires once so this just means the event is
lost"). Every send path (composer, notification reply, REST gateway, EVE,
scheduled) called `SmsManager` directly with five separate code paths, and the
cloud event UUID was derived from body+timestamp — violating the platform's
identity rules before the platform even existed.

## Changes

### ADRs (docs/adr/ — architecture is now authoritative here)

* **ADR-001** Device Trust Root & Signed Trust Registry — Android is the v1
  root of trust; GMweb can store/relay certificates but cannot forge them;
  two-phase revocation with honest "pending on Android" UI.
* **ADR-002** Command point-to-point encryption + Conversation Key Epoch
  history model (re-wrap, never re-encrypt); explicit non-claim of
  Signal/MLS/FS/PCS.
* **ADR-003** Availability SLO — `<3s` pickup ONLY for AGENT_AVAILABLE;
  dataSync FGS is not a reliability pillar; honest Doze contract.

### PR-01 — Room v7 durability tables (additive)

`remote_conversation_map` (threadId↔opaque UUID, Android-only), 
`gateway_event_outbox` (unique eventUuid, PENDING/SENDING/ACKED/DEAD_LETTER,
partial-ACK aware), `remote_commands` + `remote_command_executions` (unique
idempotencyKey = INSERT OR IGNORE exactly-once, guarded state transitions),
`sync_cursors`. Payload columns crypto-friendly from day one
(ciphertext+encoding+schemaVersion+cryptoVersion; 0 = JSON bytes, Phase 7
swaps AEAD bytes with zero schema change).

### PR-02 — Transactional cloud events (Rule 4)

The matching cloud event is committed INSIDE the same Room transaction as
every message mutation (new-row Upsert→MESSAGE_CREATED, Delete, RefreshStatus,
MarkThreadRead→THREAD_READ). Message and event live or die together; process
death re-mirrors both from the provider. The fire-and-forget `WebhookEngine`
cloud path is DELETED — only the user-configured local webhook remains
(best-effort by design). `EventUploader` drains the outbox: recoverSending()
first, transactional claim, POST /api/v1/agent/events/batch, per-eventUuid
partial ACK, full-jitter backoff, 4xx≠429 → DEAD_LETTER (never a silent drop).

### PR-03 — Unified send pipeline (§19)

`GatewayOutgoingPipeline.enqueueSendSms` → durable `remote_commands` row
(unique idempotencyKey, 24h expiry per §93, opaque conversation UUID per §12).
`sendWithOutcome` is now the single funnel; `send()`/`sendForResult()` and all
five call sites (composer, notification reply, REST gateway, EVE queue,
scheduled sends) inherit it. `directSend` remains the ONLY SmsManager
touchpoint. Rollout flag `ENQUEUE_ALL_SENDS=false` until device process-death
tests pass (ADR-003 test matrix) — default behaviour byte-identical.

### PR-04 — Exactly-once structural

Unique idempotencyKey + INSERT OR IGNORE (redelivery surfaces the existing
row) + per-command execution rows + guarded state transitions
RECEIVED→ACCEPTED→COMPLETED/FAILED.

### Identity fix (§13)

Event/message UUIDs are deterministic from the durable provider row identity
`source:providerId:dateMs` — the old body+timestamp-derived cloud event ID is
gone. eventUuid is per-event-kind so outbox rebuilds dedupe for free via the
unique index.

## Tests

195/195 green (`testDebugUnitTest`), `assembleDebug` green. New:
`GatewaySyncSchemaTest` (migration pinned against KSP 7.json — tables, indexes,
unique-index contract), `GatewaySyncPolicyTest` + factory tests (LOCK 13
boundary values: 100 events / 512 KiB, lone-oversized-event escape, full-jitter
bounds; envelope round-trip; cryptoVersion rejection; identity stability).

## Known limits (honest)

* Cloud payloads are plaintext JSON inside the envelope (cryptoVersion=0) —
  E2EE is Phase 7 behind the mandatory crypto review (ADR-002).
* Durable send-queue mode ships behind a feature flag; flip
  `GatewayOutgoingPipeline.ENQUEUE_ALL_SENDS` only after the ADR-003 device
  test matrix passes on real hardware.
* Instrumented process-death tests require a device/emulator run (ADR-003
  matrix) — not executable in CI JVM.

## Files

* `docs/adr/ADR-001..003` · `data/GatewaySync.kt` · `data/GatewayEventFactory.kt`
* `data/TelephonySyncCoordinator.kt` (transactional events) · `data/MessagesDatabase.kt` (v7)
* `repository/GatewaySyncRepository.kt` · `gateway/EventUploader.kt`
* `gateway/WebhookEngine.kt` (cloud path deleted) · `gateway/ConnectionSupervisor.kt`
* `gateway/GatewayService.kt` · `receiver/IncomingMessageDispatcher.kt`
* `sms/GatewayOutgoingPipeline.kt` · `sms/SmsSender.kt` (single funnel)
* `Holders.kt` · `app/schemas/.../7.json` · `app/build.gradle.kts` (+test org.json)
