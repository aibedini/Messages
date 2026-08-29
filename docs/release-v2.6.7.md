# Release v2.6.7 — Conversations open on the newest message, Gateway self-heals, Home keeps "You:"

**versionCode 49 · Room schema v6**

Four user-visible promises, all born from the same complaint: a
360,000-message history must not touch how fast a conversation opens, what
the gateway does after a network drop, or whether the Home list lies about
who spoke last.

## 1. Conversation open is instant and always lands on the newest message

The old flow opened a canonical oldest→newest window and then
`requestScrollToItem(lastIndex)` as a side-effect — so a cold thread could
visibly slide up through hundreds of rows, and any layout pass that ran
before the scroll command left the user at the top of history.

- `ConversationScreen` now renders with **`LazyColumn(reverseLayout = true)`**
  and a single mapper (`ConversationListMapper.buildReverseChatItems`) that
  owns the flip: ViewModel messages stay canonical oldest→newest, the UI
  data order is newest→oldest, **index 0 is the newest message**. Opening a
  conversation needs no scroll command at all — the newest row is simply
  where a reverse list starts.
- `ThreadPager` was rewritten as a **bidirectional keyset pager**
  (`loadLatest` / `loadOlder` / `loadOldest` / `loadNewer` / `loadNewerSince`)
  with independent older/newer cursors per source (SMS + MMS). No OFFSET,
  no full-thread scans, no Room in the provider crawl — opening a
  conversation is one bounded 12-per-source query.
- **Old history loads only on real upward drag** (the screen distinguishes
  user drag from programmatic layout before firing `loadOlder`), and the
  mirror direction — pulling newer rows while parked at history — is new:
  `hasNewer` + `loadNewer()` feed the forward crawl.
- **Floating "Jump to latest" button** with an unread-new-messages badge
  appears when scrolled away from the newest row; a new message arriving
  while the user reads history never yanks the list — it increments the
  badge, and tapping it jumps and clears. Sending a message always re-arms
  latest-following.
- The conversation overflow menu gained **"Go to first message"**, which
  jumps via the pager's ASC keyset (`loadOldest`, provider-side
  `ORDER BY date ASC LIMIT 12`) — never a full-history scan — and arms the
  newer direction to crawl back.
- The ViewModel tracks `windowMode` (LATEST/OLDEST), `userAtLatest`, and
  pending counts; a `ConversationScrollCommand` SharedFlow drives the one
  legitimate scroll: jumping to a boundary on demand, not on open.

## 2. Gateway: Auto Reconnect is a first-class control, and "Retry now" actually retries

The Advanced drawer hid both, and the retry path had two real bugs:

- **`ConnectionSupervisor.retryNow()` called `startHeartbeat()`, which
  returns early when the heartbeat job is alive** — so during backoff, "Retry
  now" did nothing until the backoff timer fired on its own. It now calls
  `retryHeartbeat()`, which cancels the pending backoff and ticks
  immediately, **and** `ensureLoop()`, which revives a supervisor whose
  loop had died (a freshly-created service that only received the retry
  action used to retry the heartbeat but never restart its own reconcile
  loop).
- **`GatewayService.reconnectNow(context)`** is the new transport-agnostic
  entry point: it prefers the live supervisor (`retryNow()`) and only
  falls back to `ACTION_START` when none exists. No cloud-only
  `register()` call anywhere in the manual-reconnect path —
  `GatewayViewModel.reconnectNow()` wakes the supervisor for **every**
  transport (GMweb pull bridge, LAN server, cloud) and registers with the
  cloud only when a `backendUrl` is actually configured.
- **UI:** the "Auto reconnect" switch and a **"Reconnect now"** button moved
  out of the Advanced drawer into the main gateway status card, where the
  connection state is displayed.

## 3. Home list keeps saying "You:" — the regression fix (schema v6)

When the shadow-backed Home projection was wired, its rows carried no
message type, so `type = 1` was hardcoded and every conversation preview
said the other party had the last word — even after you sent ten messages.

- `ConversationEntity` gains **`lastMessageType`** (the type of the newest
  message: 1 incoming, 2 outgoing…). `Room` version 5 → 6, additive:
  `ALTER TABLE conversations ADD COLUMN lastMessageType INTEGER NOT NULL
  DEFAULT 1` plus a data-preserving backfill that picks each thread's
  newest row with the **same deterministic tie-break the pager uses**
  (`date DESC, source DESC, providerId DESC`). Threads with no messages
  keep the incoming default.
- `upsertPreservingFlags` now writes `lastMessageType` **and refuses to
  let an older or same-date replay overwrite a newer projection row** —
  a re-delivered old inbox row can no longer stomp "You:" back to the
  peer. Every call site (inbox append, shadow repair, backfill) passes
  the newest row's type.
- `HomeViewModel`'s two Room-backed paths read `type = c.lastMessageType`
  — **O(1) per row, no per-thread message probe**. The "You:" marker and
  the Draft label in `SmsItem` are unchanged and now receive truthful data.

## 4. What O(N) and backfill blocking mean concretely now

- Opening a conversation: one bounded provider query per source
  (≤ 12 rows each), never the whole thread. The Room shadow is only ever
  consulted for the visible thread by key, never scanned.
- History backfill (the 360K-row sync) runs in its own workers and does
  **not** gate conversation open — `loadOldest` deliberately bypasses the
  shadow because the shadow may still be catching up.
- The Home projection update is an upsert keyed by thread — adding a
  message is O(1) writes to one row, not a recount of the thread.

## Tests

New JVM coverage for everything above (153 tests, all green):

- `ThreadPagerTest` — 9 tests against an in-memory fake of the Telephony
  provider contract: bounded newest window, strictly-below keyset paging
  with no re-reads, **OFFSET is banned**, `loadOldest` arms the newer
  direction, SMS/MMS cursors independent, negative MMS ids handled,
  phone-only (threadId 0) threads never touch the MMS table.
- `ConversationListMapperTest` — index 0 is the newest message (the open
  promise), separator interleaving across Today/Yesterday/older days,
  identity-based keys, MMS id sign collisions impossible.
- `ThreadMergeTest` — +3 `appendNewer` tests (forward crawl merge, fresher
  copy replacement, empty no-op).
- `MigrationToV6SqlTest` — pins every `MIGRATION_5_6` statement against the
  generated `6.json` (drift = build failure), asserts the migration is
  additive-only, the column order/NOT NULL matches, and the backfill uses
  the deterministic tie-break with the `WHERE EXISTS` guard.

## Release gate

`./gradlew assembleDebug testDebugUnitTest` → green · isolated clean
build → green · `app/schemas/…/6.json` committed · JitPack still gone
(vendored `mmslib`).
