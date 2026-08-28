# Release v2.6.1 — scale-hardening pass: projection P0s, no-drop mutations, FTS search, schema parity

v2.6.0 shipped the dual-channel sync architecture (exact mutations vs. conflated
reconciles). This release closes the P0 correctness gaps that remained on the
realtime path, makes fresh installs and upgrades converge to the SAME schema,
and replaces the in-memory full-scan search with a real full-text index.

## Fixed — P0 projection correctness (realtime path)

### A brand-new conversation could stay invisible until the next full rebuild

The exact-mutation write path upserted the conversation projection through a
plain `UPDATE`. For a thread Home had never seen before there was no row to
update — the conversation was missing from the local shadow until a full
rebuild ran. `ConversationDao.upsertPreservingFlags` is now a TRUE upsert
(`INSERT … ON CONFLICT(threadId) DO UPDATE`): a first message lands the row in
one transaction, and `pinned`/`archived` are still never clobbered.

### Deleting the last message left a stale conversation row on Home

`rebuildConversationProjection` returned early when the thread had no messages
left, keeping the deleted thread's snippet/date on Home forever. It now deletes
the conversation row when the last message goes away.

### Incoming path still triggered a full provider scan

`HomeViewModel` called `silentRefresh()` → `syncNow()` (a full SMS+MMS provider
reconcile) on every incoming SMS — turning the O(1) targeted mutation back into
an O(N) scan on the hot path. The incoming path is now Room-Flow driven: the
mutation commits → invalidation repaints Home. The provider scan survives only
on user-initiated refresh.

### Conversation projections now exist right after the first sync

On a fresh install the initial-window sync inserted message rows but never
built the conversation table (it only ever filled through mutations). The
coordinator now runs one `fullRebuildConversations()` pass after the initial
window completes — startup/repair only, never on the realtime path.

## Fixed — mutation delivery & threading

### Mutations could be dropped under a burst

The exact-mutation channel was bounded (`capacity = 64`) with `trySend` — 100
SMS arriving in one second could silently drop events, leaving stale shadow
rows until the next reconcile. The channel is now unbounded: each event is a
small immutable value, the consumer drains continuously, and no event is ever
lost.

### Provider I/O on the main thread

`ChangeRouter` ran its exact-row read synchronously inside the ContentObserver
callback (main looper). The read is now dispatched onto `Dispatchers.IO`; the
mutation is then queued to the coordinator's background loop.

## Fixed — identity & scale

### SMS/MMS UI identity collision

`MessageEntity.toSms()` returned a positive `id` for both sources, so a
provider `_id` that exists in both tables (SMS 52 and MMS 52) collided under
`distinctBy { it.id }` and Compose keys. UI identity now mirrors the provider
reader convention — SMS positive, MMS NEGATED (`id > 0` == SMS, `id < 0` == MMS).

### Fresh installs and upgrades now have the same indexes

The v2→v3 migration created a hand-rolled PARTIAL index
(`idx_messages_thread_unread`) that could not be declared in Room — fresh v3
installs never got it, so unread-count performance differed between the two
install paths. v4 declares the standard `(threadId, read, type)` index in the
entity, the migration drops the partial/legacy indexes, heals any v2→v3 gap,
and a schema test (`MigrationV3V4SqlTest`) locks the migration to the
KSP-generated `4.json`.

### Search no longer full-scans 360K messages

`searchAllMessages` loaded every SMS row into memory and filtered in Kotlin per
keystroke. It now runs on a Room **FTS4** index over message bodies
(`messages_fts`, content-synced by Room-generated triggers) and returns
thread-level hits with exact per-thread match counts.

## Tests

115 unit tests green (26 added): unread-delta rules, SMS/MMS identity at 100K
scale, FTS query escaping, ChangeRouter URI parsing, migration-vs-schema drift
guard, MMS `toSms` negation.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.6.0...v2.6.1
