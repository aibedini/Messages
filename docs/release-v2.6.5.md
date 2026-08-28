# Release v2.6.5 — Conversation opens at the newest message, instantly

**versionCode 47 · 2026-08-28**

P0 UX fix: opening any conversation must show the LATEST messages immediately,
regardless of how deep its history or how busy the background sync is.
Older history is fetched only after the user genuinely scrolls up.

## The four defects (and their fixes)

### 1. Two orderings in one UI — Room painted DESC, list is ASC
The Room shadow DAO (`pageForThread` / `newestForAddress`) returns
`ORDER BY date DESC` rows and the instant-open path fed them straight into
`messages` — index 0 = newest. The provider path, however, sorts ASC.
So on a cold open the list content was reversed for one frame, and the
bottom-anchor math landed on the OLDEST row of the window.

**Fix:** the Room paint path now ends in `.sortedBy { it.date }`.
Invariant: every list that reaches the UI is oldest → newest, one way, always.

### 2. Anchor keyed only on `chatItems.size`
Jumping to `lastIndex` was triggered by `LaunchedEffect(chatItems.size)`.
When the provider refresh replaced the Room window with an EQUAL-SIZED list
(the common case — both windows are capped), size never changed, the effect
never re-fired, and the user stayed parked wherever the stale window left
them. Also `anchoredToBottom` survived conversation switches inside the same
composition, so a second opened chat skipped anchoring entirely.

**Fix:**
- `anchoredToBottom` / `forceScrollToBottom` are `remember(threadId, phone)` —
  one anchor lifecycle per opened conversation.
- The effect is keyed on `(threadId, phone, chatItems.size, newestMessageKey)`
  where `newestMessageKey = "id:date"` of the tail row. A same-size replace
  changes the key → the anchor fires exactly once → and never yanks the user
  back down after they scroll up (guard is `!anchoredToBottom`).

### 3. First page read 80 rows (40 SMS + 40 MMS)
`ThreadPager.PAGE = 40` applied per SOURCE on the very first page, so opening
a conversation paid for 80 provider rows in the worst case.

**Fix:** split page sizes:
- `INITIAL_PER_SOURCE = 12` → open reads ≤24 provider rows (≈ latest window).
- `OLDER_PAGE = 40` → scroll-up pages, user-initiated only.
- Room instant-open window: 20 merged rows (`ROOM_WINDOW`).

`hasMore` is now decided PER SOURCE (`smsExhausted`/`mmsExhausted`: the
returned page came back under quota) instead of the old heuristic
`page.size >= PAGE/2` on the merged list, which could wrongly end paging.

### 4. `canScrollBackward` triggered history on open
With a small window a freshly opened chat may be SHORTER than the viewport —
not scrollable at all — so `canScrollBackward == false` read as "top reached"
and fired `loadOlderMessages()` during startup.

**Fix:** the trigger is now a `snapshotFlow` over
`(firstVisibleItemIndex, isScrollInProgress)`: older pages load only when the
user DRAGGED upward (index decreased while a scroll was in progress) and that
drag arrived at index 0. Initial render ≠ request. `hasMoreOlder()` on the VM
keeps cheap no-ops from re-arming the flow.

## Why backfill can no longer delay this path
The open path is now: Room `SELECT … WHERE threadId=? ORDER BY date DESC
LIMIT 20` (hits the `(threadId, date, providerId)` index — O(log n)
regardless of table size), or a ≤24-row keyset provider query. Room runs in
WAL mode: a crawling backfill writer never blocks these readers. A
conversation that is ten years old opens with the same bounded cost as one
with two messages.

```
Tap conversation
 ├─ 0–50 ms:  Room/cache latest window → PAINT → anchor newest (once)
 ├─ provider validates the ≤24-row recent page (keyset, indexed)
 └─ history untouched until a real upward drag reaches the top
```

## Files
- `repository/ThreadPager.kt` — INITIAL_PER_SOURCE/OLDER_PAGE split, per-source
  exhaustion flags, `loadPage(limit)`.
- `viewmodel/ConversationViewModel.kt` — Room paint `sortedBy(date)`,
  `ROOM_WINDOW = 20`, `hasMoreOlder()`, stale ≤80-row comments updated.
- `ui/screens/ConversationScreen.kt` — anchor keyed on threadId/phone +
  newestMessageKey, per-conversation remembered state, drag-verified
  scroll-up trigger via `snapshotFlow`.

## Verification
- `compileDebugKotlin compileDebugUnitTestKotlin testDebugUnitTest assembleDebug` — BUILD SUCCESSFUL.
- Behavior matrix reviewed: cold open (Room), warm open (cache), same-size
  provider replace, mid-session conversation switch, short-thread startup,
  fling-to-top paging, send-while-scrolled-up.
