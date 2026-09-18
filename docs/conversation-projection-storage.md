# Conversation projection storage contract

Scope: how `conversations` gets written, and the NOT NULL invariant that any
write must satisfy. Fix commit: the `pinnedOnInsert`/`archivedOnInsert` change
on `fix/realtime-room-ssot`.

## The shipped schema

`app/schemas/com.autonomousone.messages.data.AppDatabase/13.json`:

```
"pinned"   INTEGER NOT NULL   (no default)
"archived" INTEGER NOT NULL   (no default)
```

SQLite requires a value for every `NOT NULL` column with no `DEFAULT`. There is
no "leave it alone" for a column you simply do not name: the INSERT is rejected
outright with

```
NOT NULL constraint failed: conversations.pinned
```

and nothing is written.

## The defect

Both projection writers named only
`threadId, normalizedAddress, rawAddress, snippet, lastMessageDate, unreadCount,
lastMessageType`. The statement was therefore unable to INSERT any row,
new thread or not. The `ON CONFLICT` branch was reachable (an existing row) and
worked, which is why the failure looked like "conversations sometimes do not
update" rather than a hard storage error: updates landed, first inserts did not.

The realtime path is exactly the one that must create a row. An incoming SMS for
a thread the projection has never seen could not materialize that conversation,
so Home kept rendering whatever state it already had.

## The contract now

1. `ConversationDao.upsertPreservingFlags(...)` (realtime fast path) and
   `ConversationDao.replaceProjectionPreservingFlags(...)` (authoritative
   rebuild) both take `pinnedOnInsert: Boolean` and `archivedOnInsert: Boolean`
   and name both columns in the INSERT column list.
2. The `ON CONFLICT` branch of both statements never mentions `pinned` or
   `archived`. The parameters are INSERT-only, so pin/archive can never be
   cleared by a message arriving or by a full rebuild — only by an explicit
   pin/unpin/archive/unarchive write.
3. The insert value is resolved the same way at both call sites:
   the existing row wins (`existing?.pinned`); the pin/archive repositories are
   consulted only when the row is genuinely absent (`?: (threadId in …)`).
   Kotlin's `?:` short-circuits, so the common path performs no repository read.
4. `upsertPreservingFlags` stays MONOTONIC
   (`lastMessageDate = MAX(excluded, existing)`) because a new message may only
   advance a conversation. Rolling backwards after a delete is the rebuild's job
   via `replaceProjectionPreservingFlags`.

## Distinguished from the monotonicity bug

These are two separate defects with the same blast radius:

| | effect |
|---|---|
| missing NOT NULL columns | cannot INSERT at all; brand-new conversations never appear |
| monotonic upsert on a rebuild | after deleting the newest message, the stale snippet/date survives |

The second was addressed earlier by introducing the authoritative replace. Fixing
the first is what makes brand-new conversations appear without waiting for a later
rebuild.

## Verification

`ConversationProjectionReplaceTest` carries the SQL verbatim, against a real
SQLite engine:

- the test DDL is the shipped schema, `NOT NULL` with **no** `DEFAULT`. An
  earlier revision of this test added `DEFAULT 0`, which is precisely what hid
  the defect: with a default present, the omitted column silently became `0`
  and the test passed against SQL that production rejected.
- `omitting pinned and archived fails to insert a new conversation` is the
  negative control against the pre-fix statement, asserting the
  `NOT NULL constraint failed` message names `pinned` and that no partial row
  is written.
- `a brand new thread is inserted with the caller supplied flags`,
  `an incoming message never clears a pinned or archived conversation`, and
  `a rebuild never clears a pinned or archived conversation` pin the
  insert-only behaviour.

```
GATE-VERIFIED: NO
TESTS EXECUTED: 0
COMPILE STATUS: UNVERIFIED
```

Gradle is frozen for this branch (`:app:testDebugUnitTest` was not run). The
tests are written, not executed. The claim that this is the production root cause
of "incoming SMS does not update the conversation immediately" is NOT established:
the statement provably cannot insert, but the observed field behaviour has not yet
been measured against a device.
