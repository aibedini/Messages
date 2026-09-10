# PR-04 — Conversation Componentization

> Status: **applied (MessageList extracted). Device test deferred.**
> Repo: `aibedini/Messages` · after PR-03 (`d9d5510`)

## What changed

### Extracted `MessageList` (`ui/conversation/MessageList.kt`)
The conversation timeline was an inline ~200-line block inside
`ConversationScreen.kt` (a 1559-line monolithic screen). It is now a focused,
stateless `MessageList` composable that owns:
- the **reverse-layout LazyColumn** (RFP §15 preserves reverse behavior),
- **date separators** (stable `date_<dayKey>` keys, RFP §16),
- **message bubbles** via `ChatBubble` wrapped in `MessageEntrance` (live-only
  entrance, placement-only `animateItem` critical-damped spring — unchanged),
- `contentType` = "message" / "date",
- **loading spinners** at both crawl edges (`newer_messages_sync` /
  `older_messages_sync`),
- **Jump-to-latest** floating button + pending-count badge,
- **pull-to-refresh** (wired to the ViewModel's real refresh state).

All state remains hoisted in `ConversationScreen` / `ConversationViewModel`
(RFP §8): the screen passes `listState`, `chatItems`, loading/animation flags,
`isRefreshing`/`onRefresh`, and every callback. ThreadPager semantics,
windowed paging, and reverse-layout anchoring are **untouched**.

`ConversationScreen.kt` shrank by ~200 lines and now delegates its timeline to
`MessageList`.

### Added `MessageListTest` (Compose UI)
Verifies a date separator + incoming/outgoing bubbles render (RFP §34). Runs on
device.

## Invariants preserved (RFP §15/§16/§17/§43)
- reverseLayout + data order newest→oldest; index 0 = newest paints at visual
  bottom (opening lands on the latest message).
- Stable namespaced keys: `date_<dayKey>`, `msg_<id>_<date>_<type>`.
- Live-only `MessageEntrance`; hydration never animates.
- No full-thread load; windowed ThreadPager intact.
- No gateway / data-architecture change.

## Gates
- `testDebugUnitTest`: green
- `assembleDebug`: green
- `compileDebugAndroidTestKotlin`: green (MessageListTest + HomeFilterBarTest)

## Next
PR-05 (Conversation visual modernization — bubble spacing, composer polish) on
the now-smaller screen; PR-06 (motion tokens adoption).
