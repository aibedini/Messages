# Android Messages-for-Web replication v3

Android is the source of truth and encrypts before `gateway_event_outbox`. Full history uses the existing single History Master Key; no per-conversation grant is introduced.

Each event carries opaque `messageId`/`conversationId`, `revision`, `sortKey` and `REALTIME` or `BACKFILL` priority outside ciphertext. Sensitive message and conversation fields remain inside crypto v3. Realtime claims precede history. Historical production traverses `(date, providerId)` newest-first and stops when 2,000 backfill rows are pending. Its cursor is durable across process death/reboot; deterministic event IDs make a repeated scan idempotent.

Android also publishes an encrypted `CONVERSATION_UPSERTED` snapshot so GMweb never needs to decrypt a message to build the conversation list. Status events carry the full encrypted current-state payload because an opaque server cannot merge a partial plaintext-free update.

The shared wire example is `protocol/messages-web-replication-v3.json` and is mirrored by GMweb under `shared/`.

Physical throughput, battery, carrier send, reboot and revoke verification remain: **NOT RUN — PHYSICAL DEVICE REQUIRED**.
