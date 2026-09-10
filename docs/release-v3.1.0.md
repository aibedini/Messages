# Messages v3.1.0 — Encrypted Messages-for-Web replication v3

**versionCode 98 · Room schema v10 · protocol `messages-web-replication-v3.json`**

Pairs with **GMweb API v0.17.0** (matching encrypted replication/control plane).
The phone stays the durable source of SMS truth; GMweb stores and relays
**ciphertext only**; the authorized browser decrypts locally.

---

## 1. Full-history crypto v3, origin-bound

- A single **History Master Key** (existing `HISTORY_KEY_V3` migration) covers
  the whole synchronized history — no per-conversation grant is introduced.
- Sensitive fields (body, address, contact name, direction, status, preview,
  read state) live **inside AEAD ciphertext**. The outer envelope exposes only
  opaque `eventId`/`conversationId`/`messageId`, `revision`, `sortKey`, event
  type and crypto framing.
- **No `cryptoVersion=0` for new synchronized SMS.** Canonical format is shared
  by historical, incoming, outgoing, status, and delete events — there is no
  "legacy history plaintext path".

## 2. Encrypted conversation snapshots

Android now emits encrypted `CONVERSATION_UPSERTED` snapshots so GMweb can build
the conversation list **without ever decrypting a message**. The outer event
carries only the opaque `conversationId` + revision + ordering metadata.

## 3. Bounded, resumable historical backfill

- Newest-first **keyset** traversal on `(date, providerId)` — never `OFFSET`,
  never an all-360k in-memory load.
- Historical pending backlog is **bounded (≤ 2,000 rows)**; live messages are
  never stuck behind deep history.
- Durable ACK checkpoint (`source`, `lastAckedDate`, `lastAckedProviderId`,
  generation, protocolVersion) — process death/reboot resumes from the ack,
  not from zero.

## 4. Realtime priority lane

- Outbox rows are labelled `REALTIME` or `BACKFILL`; claim order always prefers
  **REALTIME > BACKFILL**, so a newly received/sent message uploads immediately.
- `providerRowChanged()` — an exact O(1) provider-row nudge right after the
  outgoing insert, replacing a slow periodic full scan.

## 5. Web-send reconciliation (no duplicate bubbles)

- `originCommandId` / `clientMessageId` correlation ids flow into the encrypted
  resulting message event, so a web-sent pending bubble reconciles to the real
  provider message without matching on phone+text+timestamp heuristics.

## 6. Revision-aware server state

History uses `revision 1`; live mutations use a later monotonic device
timestamp. GMweb updates current state only for a **newer revision** (or same
revision with a later server sequence), so a stale backfill can never overwrite
a newer live update and tombstones cannot be resurrected by old history.

---

## Files

| file | change |
|---|---|
| `data/TelephonySyncCoordinator.kt` | cloudMessageDirection, providerRowChanged, CONVERSATION_UPSERTED emit, direction/read enrich, backfill pipeline |
| `data/Daos.kt` | `cloudHistoryPage` keyset `(date, providerId)` |
| `data/GatewayEventFactory.kt` | conversationUpserted + correlation ids |
| `data/MessagesDatabase.kt` | Room **schema v10** |
| `data/GatewaySync.kt`, `data/MessageMutation.kt` | revision/correlation plumbing |
| `gateway/EventUploader.kt`, `sms/GatewayOutgoingPipeline.kt`, `sms/SmsSender.kt` | realtime lane + web-send correlation |
| `repository/GatewaySyncRepository.kt` | snapshot emit |
| `app/build.gradle.kts` | versionCode 98 / versionName 3.1.0 |
| `docs/MESSAGES-WEB-REPLICATION-V3.md` | **new** — ADR for the encrypted replication architecture |
| `protocol/messages-web-replication-v3.json` | **new** — shared cross-language fixture |
| `app/schemas/…/10.json` | **new** — Room v10 schema |
| 3 new test files | MigrationToV10Sql, ReplicationFixture, MessageEventDirection |

## Gates

- `testDebugUnitTest`: **269/269** green (49 suites)
- `assembleDebug`: green
- Room schema v10 with migration test

## Compatibility

- **GMweb API v0.17.0** — must be deployed together (matching protocol builds).
- Existing `/api/v1/sync` delta mechanism preserved; new `/api/v1/web/*`
  bootstrap/pagination APIs on the server side.

## Known limits / NOT RUN — PHYSICAL DEVICE REQUIRED

- Physical pairing + decrypt matrix, live in/out, web-send, revoke.
- 360k **Android** benchmark (server-side 360k store benchmark already passes:
  ingest ~33k msg/s, message/conversation query P95 < 1.4 ms, COVERING INDEX
  query plans).
- Security canary + TLS capture on a deployed environment.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v3.0.1...v3.1.0
