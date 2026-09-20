# Messages v3.4.5 — Conversation opens on the Home snapshot

`versionCode` 111 → **112** · `versionName` 3.4.4 → **3.4.5**
No database change: Room stays on **v17**. No migration, no backfill.

> **Opening a conversation could show no history at all — or only one bubble at
> the bottom. Closing and reopening the app "fixed" it.**

That signature — correct after a restart — is the signature of an ordering race,
not of missing data. The single bubble is Home's one-frame launch snapshot
(`ConversationLaunchStore` → `LaunchPreview`): Home said the conversation has a
message, and the loader said it had none.

---

## Root cause — three defects in one guard

v3.4.3 introduced `ThreadMessageCache.authorityRevision` so a slow cache read
could not overwrite a newer authoritative Room window. The invariant was right:

```text
Room  emits [A, B, C]     (authoritative, newest = C)
cache publishes [A, B]    (slower, older)
messages.clear(); addAll([A, B])   ← C disappears
```

Any fix for it has to keep that protection. The v3.4.3 implementation, however,
broke in the opposite direction.

### A — the authority clock was global

One `var authorityRevision: Long` lived on the `ThreadMessageCache` **singleton**.
The cache's own invalidation revisions are per-thread — the file even documents
*"activity in conversation A must not invalidate B/C/D"* — but the authority
clock was not. A conversation still alive in the navigation back stack that
painted an authoritative window made an **unrelated** open believe its cache was
obsolete.

### B — an empty Room tail claimed authority

```text
startRoomTail() {
    val tail = entities.map { it.toSms() }
    ...
    messages.clear(); messages.addAll(merged)
    ThreadMessageCache.markAuthoritativePaint()   ← even when tail.isEmpty()
}
```

Room emits `[]` routinely: the shadow has not caught up, the sync for a
Home-visible thread has not landed, the DB was just opened. Treating that as
truth is what discarded the only usable source.

### C — a discarded cache could still finish the load

```text
val authorityMoved = cache.authorityRevision != cacheReadRevision
if (!authorityMoved) { paint cache }
...
if (!stale.second) {          ← "the cache was FRESH"
    pager = ThreadPager(...)
    return@launch             ← …but nothing was painted
}
```

"Nothing was painted" and "everything that could be painted is on screen" were
the same state as far as the loader was concerned. With A and B both firing, the
sequence was:

```text
startRoomTail()                     Room emits []
markAuthoritativePaint()            ← authority moves
cache read returns FRESH [A, B]
authorityMoved == true              → cache DISCARDED
!stale.second                       → early return
messages == []                      → LaunchPreview forever
```

`ConversationScreen` renders `MessageList` only when `chatItems` is non-empty, so
from that point on the launch snapshot was the **entire** conversation.

---

## Fix

### A — authority is per conversation

`ThreadMessageCache` now keys the authority clock exactly like its cache
revisions — thread id when known, phone key for a `threadId == 0` open:

```kotlin
fun authorityRevision(threadKey: Long, phoneKey: String): Long
fun markAuthoritativePaint(threadKey: Long, phoneKey: String = "")
```

`ConversationOpenLoad` captures `capturedRevision` for **its own** conversation
at open time. A paint in conversation A can no longer move conversation B's
clock.

### B — only a non-empty Room window is authoritative

```kotlin
fun onRoomTail(rows: Int): Boolean {
    if (rows <= 0) return false      // neither publishes nor claims authority
    claimAuthority(rows)
    return true
}
```

An empty emission is not proof that a Home-visible conversation has no messages.
Explicit deletions are unaffected — **Trash, individual delete and conversation
delete write their own state and mutate the visible window directly**; they never
depended on an empty tail meaning "empty".

### C — a discarded cache never ends the load

```kotlin
fun mayFinishInitialLoad(cacheFresh: Boolean): Boolean =
    roomPainted || (cachePainted && cacheFresh)
```

`cachePainted` is set only inside the main-thread publish itself. The fast path
now requires a cache that was **actually painted** and fresh — or a non-empty
authoritative Room window. Anything else falls through to the bounded provider
page.

The guard check and the guard write both run on the main dispatcher with no
suspension between them, so the Room publish (also main) cannot slip in between
them. That closes the last interleaving: previously the decision was taken on
`Dispatchers.IO` and the write happened later on main.

### D — the provider page may not undo a Room window either

The bounded provider page used to **replace** the visible list:

```kotlin
messages.clear(); messages.addAll(readMessages)
```

A row Room mirrors but Telephony has not exposed yet is exactly the newest
message that used to vanish. When a non-empty authoritative Room window already
owns this open, the provider page is now **merged** into it
(`ConversationWindow.mergeRoomTail` — the same invariant as the reactive tail:
an identity the incoming list does not mention is never dropped). Checked on the
main thread, immediately before the write.

### E — the launch snapshot can no longer hide a failure

`errorMessage` could not carry a persistent affordance: the screen's snackbar
consumes it moments after it is set. The open now has an explicit, durable
state, and the body is resolved by one tested function:

```kotlin
resolveConversationEmptyContent(hasMessages, hasLaunchSnapshot, isLoading, initialLoadFailed)
```

`FAILED` outranks `LAUNCH_PREVIEW`. The snapshot may stay on screen underneath
(the bottom anchor stays put, so the geometry does not jump), but a card with a
localized **Try again** sits on top of it and re-runs the bounded load in place —
no force-close, no Activity restart.

### F — the generation guard is testable

`conversationGeneration` moved into `ConversationOpenGeneration`, so "rapidly
open A → B → C, no stale load paints another conversation" is verified without
an Android ViewModel.

---

## Changed files

| File | Change |
| --- | --- |
| `repository/ThreadMessageCache.kt` | authority clock is per conversation |
| `repository/ConversationOpenLoad.kt` | **new** — pure open-authority state machine + open generation |
| `ui/conversation/ConversationEmptyContent.kt` | **new** — pure body-state resolver |
| `viewmodel/ConversationViewModel.kt` | empty tail refused · no early return after a discard · merge instead of replace · `initialLoadFailed` + `retryInitialLoad()` · open diagnostics |
| `ui/screens/ConversationScreen.kt` | body state from the resolver · `ConversationLoadFailure` with Retry |
| `res/values/strings.xml`, `res/values-fa/strings.xml` | `conv_load_error_title`, `conv_load_error_body` |

## Diagnostics

```text
CONVERSATION_OPEN thread=… phone=<token> generation=… authority=…
CACHE_READ        thread=… exists=… fresh=… rows=… authorityBefore=…
CACHE_READ        thread=… rows=… authorityAfter=… painted=… authorityMoved=… discarded=…
ROOM_TAIL         rows=… thread=… generation=… markedAuthoritative=true|false
PROVIDER_PAGE     thread=… rows=… generation=… mergedWithRoom=…
INITIAL_LOAD      thread=… source=CACHE|ROOM|PROVIDER|ERROR rows=… generation=… fastPath=…
```

Counts, ids and hashed phone tokens only — **no message bodies and no full phone
numbers**.

## Tests

`1194` JVM tests, `0` failures. New and updated:

* `ConversationOpenLoadTest` (11) — the empty-Room-emission case, Room-newer-wins,
  the discarded-cache fallback, empty-tail authority, painted-vs-fresh cache,
  cross-conversation authority isolation, the A→B→C generation guard, process
  recreation without an in-memory cache.
* `ThreadMessageCacheAuthorityTest` (11) — the per-conversation clock, including
  `authorityFromThreadA_doesNotDiscardThreadB`.
* `ConversationEmptyContentTest` (5) — failure outranks the launch snapshot, the
  snapshot is never the source of truth, Retry wiring, screen wiring.

Gate: `testDebugUnitTest` (126 suites) · `assembleDebug` ·
`compileDebugAndroidTestKotlin` · `lintDebug` (`0 errors`) — all pass.

## Not changed

Composer send routing and the v3.4.4 double-send fix, optimistic reconciliation,
RTL/LTR content direction, the gateway, OTP detection and retention, smart
categories, custom categories (still on `feat/custom-categories-v3.5.0`), and the
Room schema — **still v17**, no migration.

## Device acceptance before release

Open at least 20 conversations rapidly. For every Home row, the real history must
replace the launch snapshot within the normal bounded load time — with no
force-close, and no single-bubble conversation:

1. the most recently received conversation;
2. an old conversation;
3. a contact sender;
4. an alphanumeric sender;
5. a conversation whose newest message is outgoing;
6. a conversation whose newest message is incoming.

Repeat after a normal cold start, and again after a process kill. Then open a
conversation with airplane mode on and confirm the **Couldn't load messages** card
with a working **Try again** appears instead of a silently empty screen.
