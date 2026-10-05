# v3.4.30 — raw SMS sender identity in encrypted events, thread id in the contract

`versionCode 137` · `versionName "3.4.30"` · minSdk 26 · targetSdk 36

## P0 — the sender identity was being thrown away before it left the device

`MessageEntity` carries both `rawAddress` (the provider's exact string) and `normalizedAddress` (a
phone-matching helper), and every cloud event was built from the **helper**:

```kotlin
address = entity.normalizedAddress      // BEFORE
```

For any non-numeric sender the helper is empty:

```text
provider sender        rawAddress        normalizedAddress     address that reached GMweb
PARSIANBANK            PARSIANBANK       ""                    ""            ← unrecoverable
Ssh3-652               Ssh3-652          ""                    ""
3000                   3000              "3000"                "3000"        (accidentally fine)
```

GMweb therefore stored conversations with no address at all, which is exactly the "Unknown number /
Unknown conversation" it renders. No web-side fallback can recover a string the phone never sent, so the
producer is fixed.

### The rule

```text
rawAddress         = presentation / sender identity truth   → what leaves the device
normalizedAddress  = phone matching helper ONLY             → never a display identity
```

`SenderIdentity.eventAddress(raw, normalized)` now produces every `address`/`sender` in every producer:
the raw value byte for byte, with the helper used **only** when the provider genuinely gave nothing. No
lowercasing, no letter removal, no `-` stripping, no digit conversion — `PARSIANBANK`, `ResalatBank`,
`Google`, `Ssh3-652`, `3000`, `09121234567`, `+989121234567` and Persian-digit senders all survive.

Producers fixed (all in `TelephonySyncCoordinator`): message created, message updated (content change),
message status/read changed, conversation upserted (both the realtime and the projection paths), the
message-deleted and empty-thread paths, the history/backfill producer, and the mirror-reconciliation
producer — whose row type gained `rawAddress` (`MirrorReconcileDao`) so reconciliation cannot re-emit an
empty identity either.

### Sender classification, and phone-only contact lookup

`SenderIdentity.classify()` answers one question — `PHONE`, `SHORT_CODE`, `ALPHANUMERIC`, `UNKNOWN` —
derived from the two addresses every time (never persisted, so it cannot go stale):

* any letter ⇒ `ALPHANUMERIC` (branded sender);
* digits only, ≥ 7 ⇒ `PHONE`; fewer ⇒ `SHORT_CODE`;
* nothing usable ⇒ `UNKNOWN`. **A branded sender is never `UNKNOWN`.**

`contactNameFor()` now resolves a contact only for `PHONE` senders: looking up `PARSIANBANK` or `Google`
in the address book is meaningless, and the helper being empty for them is how a known sender ended up
with no name *and* no address.

## P0 — the Android thread id is now inside the encrypted contract

`androidThreadId` and the `source`/`providerId` pair are emitted in the logical payload of
`MESSAGE_CREATED`, `MESSAGE_UPDATED`, `MESSAGE_STATUS_CHANGED` and `CONVERSATION_UPSERTED`, taken from
the row's own `threadId` — never derived from a phone number:

```json
{ "messageId": "…", "source": "sms", "providerId": 348201, "androidThreadId": 552,
  "direction": "in", "body": "…", "dateMs": 1791180000000, "status": 0,
  "address": "PARSIANBANK", "contactName": null, "read": false }
```

A thread id of `0` is **omitted** rather than sent as zero. These fields live inside the ciphertext: the
outer envelope (`eventUuid`/`eventType`/`aggregateId`/`messageId`) carries neither the sender nor the
thread id, which a test asserts. Adding payload keys does not touch event identity — `eventUuidFor` is
derived from type/source/provider row/date.

Payload construction was extracted into `messageCreatedPayload` / `messageStatusChangedPayload` /
`conversationUpsertedPayload` so the contract GMweb decrypts is asserted directly instead of being
trusted to a builder.

## `FETCH_THREAD_HISTORY` — unchanged and complete

The bounded/keyset implementation from v3.4.29 is untouched: exact thread query, `(date, _id)` keyset,
`LIMIT limit + 1`, default 20, clamp 50, half cursor ⇒ `CURSOR_INVALID`, unknown/zero thread ⇒
`THREAD_NOT_FOUND`, canonical `HISTORY_BACKFILL` ingest, and `COMPLETED` only when the rows are durably
in the encrypted replication path or the phone positively proved `END_OF_THREAD_HISTORY`.

**SMS-only, deliberately and explicitly:** the executor passes `source = MessageEntity.SOURCE_SMS`. The
lower-level query has an MMS branch, but this release does not claim mixed SMS/MMS history — merged
thread paging with one stable cursor is a separate piece of work, and it is not implied by a helper
branch existing.

Capabilities remain one SSOT (`CommandRouting.ADVERTISED_COMMAND_TYPES`, derived executable set) with
the drift test enforcing `advertised == executable`:
`SEND_SMS · MARK_THREAD_READ · REFRESH_DEVICE_TELEMETRY · FETCH_THREAD_HISTORY`.

## NOT IMPLEMENTED IN THIS ROUND (honest scope)

The **metadata repair migration for pre-existing conversations** (bounded, resumable,
`metadata_contract_version` checkpointed, one projection event per thread) is **not implemented here**.
Old conversations whose remote projection still holds `address = ""` will therefore keep it until they
receive a new message — the producer fix applies to everything emitted from now on. The design
(contract version in durable prefs, batched enumeration of `conversations` by `threadId` cursor, a
checkpoint written only after a durable enqueue, never re-uploading message bodies) is written up in the
mission, not in the code.

## Verification

```text
targeted: SenderIdentityTest (16) · GatewayEventContractTest (9)      PASS
gradlew.bat testDebugUnitTest   PASS   (full suite, no failures)
gradlew.bat assembleDebug       PASS
gradlew.bat lintDebug           PASS   (0 errors)
```

## REAL-PHONE VERIFIED: NO

No handset was used. Still to confirm on the Samsung device: that a branded sender (`PARSIANBANK`,
`Ssh3-652`) arrives at GMweb with its original string and an `androidThreadId`; that a >100-message
thread pages through `FETCH_THREAD_HISTORY` with no duplicates, gaps or foreign rows; and that a
pre-existing "Unknown conversation" is repairable (which currently requires a new message, since the
metadata migration is not implemented).
