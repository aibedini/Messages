# Release v2.6.4 — Real segment-based send counter + crash-safe conversations

versionCode 45 → 46 · DB schema v4 → v5 (additive) · Aug 2026

## 1. Home: practical stat instead of a greeting

The time-of-day greeting ("Good morning 👋" …) was removed — it occupied
the header and told you nothing. In its place, a live chip next to the
title:

> **Messages · 128 today** (`128 SMS today` / `128 پیامک امروز`)

### What counts — and what does not

128 is the number of **successfully delivered SMS segments** today, not
message rows and not `send()` calls:

| Send | Segments on the wire | Counted |
|---|---|---|
| `"Hello"` | 1 | +1 |
| long Persian text (3 parts) | 3 | +3 |
| very long English text (4 parts) | 4 | +4 |
| 3-part message where 2 parts got RESULT_OK | 2 of 3 | **+2** |

Counting happens ONLY inside `SmsStatusReceiver` when a part's callback
returns `RESULT_OK` — attempts are never counted. Gateway, manual, EVE
and scheduled sends all route through `SmsSender` → the same callbacks →
the same stats, automatically.

### The ledger (DB v5)

New table `send_segments`, PK `(rowId, partIndex)` so a duplicate
provider callback can never double-count one segment:

```
rowId            provider SMS row id
partIndex        0-based segment index within the message
partCount        how many segments this message was split into
sentAt           wall-clock millis (set on the row, not System time at query)
subscriptionId  SIM slot (per-SIM stats ready for the usage screen)
success          RESULT_OK == 1
```

`SmsSender` now passes `EXTRA_PART_INDEX / EXTRA_PART_COUNT /
EXTRA_SUBSCRIPTION_ID` through each `sendMultipartTextMessage` PendingIntent
so the receiver can attribute every callback precisely.

The Home chip reads a Room `Flow` (`observeSuccessSince(startOfToday)`),
so the number updates 128 → 129 the moment a part is confirmed — no
re-sync, no restart. The "today" window rolls over at local midnight via
a self-scheduling collector in `HomeViewModel`. Old rows are pruned by
`pruneBefore` on sync start (90-day horizon).

## 2. Conversations must never take the process down

Reported symptom: opening a chat while a large backfill ran could close
the whole app. Root causes fixed:

### 2.1 Exception boundary on every conversation job
`loadConversation()`, `refresh()` and `loadOlderMessages()` launched
`try { … } finally { … }` without `catch` — one provider `SQLiteException`
or cursor failure became an uncaught coroutine exception and killed the
process. All three now run under a `crashGuard` `CoroutineExceptionHandler`
that logs structured context (`CONV_VM … threadId=… phone=set msgCount=…`)
and degrades to a dismissible snackbar ("Couldn't load some messages…").
Cancellation still rethrows — lifecycle, not failure.

### 2.2 Mark-read does ONE provider pass
`markThreadAsRead` used to run a thread-scoped UPDATE **and then** an
`ADDRESS LIKE '%digits%'` sweep for the same flag: double writes, double
observer bursts, and a LIKE scan over the whole SMS table on big threads.
Now the address sweep is only the fallback when `threadId == 0`.

### 2.3 Reading messages no longer triggers a full sync
A bulk mark-read fires observer callbacks whose URIs carry no row id; the
`ChangeRouter` mapped every one of those to `FullSync` — so simply
OPENING a chat could start a dual-source window crawl racing the
backfill. New `LocalProviderWrites` registry (2-second window, exactly-once
claims): our own mark-read notes the thread id, and the router downgrades
the unknown-URI fallback to a targeted `ForThread` repair.

Also fixed: `content://sms/thread/123` was parsed as **row id 123** and
upserted a random unrelated message. Thread-scoped paths are now never
treated as row ids (`thread URIs are NOT row ids` test).

### 2.4 Backfill yields to the UI; one rebuild instead of two
The SMS and MMS history crawls previously ran as two concurrent jobs and
each called `fullRebuildConversations()` when done — doubled provider
load, doubled Home churn mid-sync. Now one sequential crawl owns both
sources (per-source durable keyset cursors unchanged) and the projection
rebuilds exactly once at the end. The crawl lives on its own single
thread at `MIN_PRIORITY` with the existing per-batch `yield()`, so page
loads and observer mutations share nothing with it. (Honest caveat in
the KDoc: readers still hop to `Dispatchers.IO` internally — the lane
guarantees serialization and off-UI bookkeeping; priority is best-effort.)

### 2.5 Last-resort crash logging
`MessagesApp` installs a default uncaught-exception handler that logs
thread + trace under `CRASH_GUARD` and delegates to the platform handler
— a dead process now at least leaves a readable trail.

## Verification

- `compileDebugKotlin compileDebugUnitTestKotlin` — BUILD SUCCESSFUL
- `testDebugUnitTest --rerun-tasks` — 21 classes green (7 new tests:
  `LocalProviderWritesTest` ×4, thread-URI case + existing router tests)
- `assembleDebug` — BUILD SUCCESSFUL
- Migration v4→v5 additive only; schema exported to
  `app/schemas/.../5.json`

## UI notes

- `MainTopBar` gained an optional `titleBadge` slot (used by Home only).
- Chip styling: `secondaryContainer` normally, quiet `surfaceVariant`
  at zero so it never shouts an empty stat; `1234+` compaction.
- Removed: 4 greeting strings × 2 locales + `Calendar` usage in Home.
