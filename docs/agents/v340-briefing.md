# v3.4.0 implementation briefing (shared context for parallel workstreams)

**Branch:** `feat/must-have-ux-3.4.0` (already checked out). **Base:** `db47edf` (`master` = v3.3.6).
**Never** merge, tag, release, or bump `versionCode`/`versionName` — that happens once at the very end.

## What already exists on this branch (DO NOT REWRITE)

| Commit | Delivered |
| --- | --- |
| `555abfd` | Room **v16**: `UxEntities.kt` (6 new tables), `UxDaos.kt` (6 DAOs), `MessagesDatabase.MIGRATION_15_16`, `TrashRepository`, `ConversationPreferenceRepository`, `MessageUserStateRepository`, `16.json`, `MessageCutoffTest`, `MigrationToV16SqlTest` |
| `b3f4686` | `messaging/OtpDetector.kt` (unified detector), `messaging/ConversationNotificationChannels.kt`, mute gate + channel selection in `utils/NotificationHelper.kt`, `OtpDetectorTest`, `ConversationNotificationChannelsTest`, `ConversationPreferenceEntityTest` |

`MessageFtsDao` already has `searchThread()` and `countThreadMatches()` returning `ConversationSearchHit`.

Reuse all of it. There must never be a second FTS index, a second notification action receiver, a second OTP detector, a second scheduler, a second blocklist or a second message database.

## Non-negotiable architectural invariants

1. Telephony provider = external durable truth. Room = UI read-SSOT.
2. **Never** run a full Telephony scan for a normal UX operation.
3. **Never** put `ContentResolver` / SQLite / heavy work on the Compose main thread.
4. Message identity is ALWAYS the composite `(source, providerId)`. SMS `_id` 100 and MMS `_id` 100 are **different messages**.
5. Never identify a persisted message by `body + timestamp`.
6. User-owned metadata must **not** be overwritten by provider sync → new UX state lives in the v16 user-state tables, never in `MessageEntity` columns that a provider Upsert overwrites.
7. All DB migrations are non-destructive. `fallbackToDestructiveMigration` stays DEBUG-only.
8. Assume 360,000 messages. No `O(total_messages)` Kotlin filtering, no `SELECT * FROM messages` then `.filter`, no deep `OFFSET` paging where keyset is feasible, no `getAllMessages()` for UX.
9. Classification is **local only**. Never upload message/OTP content. Never log message body, OTP code or full phone number (use `DiagnosticLog.phoneToken()`). `SensitiveMessageFirewall` stays authoritative for LOCAL_ONLY / sync restrictions — new features must not make an OTP/security message sync to cloud.
10. OTP auto-delete is **OFF by default** and means **move to Trash**, never immediate permanent delete. Starred or `keepFromOtpCleanup` messages are never auto-removed.

### ACTIVE-UI query contract (already encoded in `MessageCutoff`)

* **RAW MIRROR** — `messages` untouched; used by sync/integrity/repair. Never filter it by user state.
* **ACTIVE UI** — raw row minus individually-trashed state minus rows hidden by a thread tombstone. Compose the predicate from `MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL` / `NOT_INDIVIDUALLY_TRASHED_SQL` / `NOT_HIDDEN_BY_TOMBSTONE_SQL`; never re-type it.
* **TRASH** — only trashed state.
* A provider reconciliation must **never** "restore" a row the user trashed. Provider rows intentionally still exist until purge.

### UI quality rules

Loading / Content / Empty / Error for every new screen or list (never a blank screen). Material 3, dark+light, RTL-safe, `contentDescription` on actionable icons, minimum touch targets, no fake buttons, no clickable no-ops. **Every new user-facing string needs a Persian entry in `app/src/main/res/values-fa/`** (add to `values/` too, using the existing `strings.xml` conventions). Snackbar for reversible actions; Dialog (explicit confirmation) for permanent destructive ones.

## Definition of done for every workstream

1. Real, complete implementation — **no placeholders, no TODO stubs, no no-op clickables**.
2. JVM unit tests for the new invariants (the listed test cases in your brief are the minimum, not the maximum).
3. `./gradlew compileDebugKotlin` is clean.
4. `./gradlew testDebugUnitTest` is clean — **no existing test may regress**.
5. Focused commit(s) with a `feat(scope): …` / `test(scope): …` message matching the commit plan.
6. Do **not** run `./gradlew assembleDebug`, `lintDebug` or `compileDebugAndroidTestKotlin` (the final gate runs those once, serially).
7. Do **not** edit `navigation/AppNavigation.kt` — the coordinator wires routes centrally to avoid parallel conflicts. If you add a `Screen` object, add it in `navigation/Screen.kt` and report the composable signature you need wired.
8. Report back: files changed, commit SHA(s), tests added, and anything you could not finish.

## Three hard-won constraints on this branch (READ BEFORE EDITING)

1. **Never use a string TEMPLATE as a Room annotation argument.** `@Query("$SOME_CONST")`
   and `@Query("${Obj.CONST}")` make KSP fail the whole build with
   `No property named value was found in annotation Query` — there is then NO Room
   codegen at all. A `const val` used *directly* (`@Query(SOME_CONST)`) is fine, and a
   triple-quoted `@Query(""" … ${MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL} … """)` is
   fine. For anything else, inline the SQL literal and pin it with
   `app/src/test/java/com/autonomousone/messages/UxSqlLiteralDriftTest.kt`.
2. **Never leave a bind variable without a matching function parameter.** Room rejects a
   `:name` no parameter supplies ("Each bind variable in the query must have a matching
   function parameter"). If a value is part of a state machine's fixed vocabulary, write
   it as a SQL literal in BOTH the SET and the WHERE clause so the halves cannot disagree.
3. **Do not run Gradle at the same time as another workstream.** Parallel Gradle
   invocations corrupt `app/build/generated/ksp` (`Check failed.` / daemon killed). Run
   one task at a time, and retry once on that error before investigating.

## Identity helper

`repository/ConversationWindow.kt` → `MessageIdentity.Key(source, providerId)`, `MessageIdentity.keyOf(id)` (model id: SMS positive, MMS negated). `data/MessageMutation.kt` → `data class MessageKey(val source: String, val providerId: Long)`. Selection keys must be one of these, never a raw `Long`.
