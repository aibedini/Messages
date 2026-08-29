# Release v2.6.8 — Motion & Conversation Polish

**versionCode 50 · Room schema v6 (unchanged)**

No new features. v2.6.7 made the conversation *fast*; this release makes it
feel *coordinated*. The three symptoms — a new bubble popping in with no
enter animation, the ↓ button flashing right after Send, and a heavy 350 ms
full-screen slide on navigation — all came from motion states acting
separately. They now move as one sequence.

## 1. Sending is a single intent

Old flow: `setUserAtLatest → jumpToLatest() → sendMessage()` — the scroll
and the optimistic insertion were two dry, unconnected movements, and the
layout churn between them briefly showed the ↓ button.

New flow (`ConversationScreen` send handler + `ConversationViewModel`):

```
tap Send
  → send button press spring (0.9 → 1.0)
  → viewModel.beginOwnSend()        // latches ownSendFollowActive
  → optimistic bubble inserted      // animates into place
  → conversation glides to it       // scrollToItem(3) only if far, then animateScrollToItem(0)
  → finishOwnSendFollow()           // un-latches when settled
```

- `ownSendFollowActive` is a new ViewModel state (`beginOwnSend` /
  `finishOwnSendFollow`) that pins the FAB hidden for the whole gesture.
- Sending from deep history no longer animates through hundreds of rows:
  `wasFarFromLatest = firstVisibleItemIndex > 12` teleports near the edge
  first, then the glide covers the last few rows.
- If the window is still in OLDEST mode (send from `Go to first message`),
  the follow waits for the window to swap to LATEST (bounded poll,
  1.5 s timeout) instead of scrolling a stale list.

## 2. `atLatest` is tolerant, the FAB is debounced

- `atLatest` was `firstVisibleItemIndex == 0` — one anchor adjustment by
  Compose flipped it false. Now the newest row counts as visible if it is
  actually in `visibleItemsInfo` **or** `firstVisibleItemIndex <= 1`.
- FAB visibility is debounced by 180 ms and suppressed while
  `ownSendFollowActive`: a momentary two-state cannot make it flash.
- The button itself enters/exits with fade + scale
  (`fadeIn(140)+scaleIn(0.82→1, spring)` / `fadeOut(100)+scaleOut`), not a
  hard pop.

## 3. New bubbles animate into the list

- `ChatBubble` takes a `modifier` parameter; the message rows in
  `LazyColumn` pass `Modifier.animateItem(fadeIn = tween(140),
  placement = spring(damping 0.82, stiffness 420), fadeOut = tween(100))`.
  Stable `sms_${id}` keys mean only the genuinely new row animates —
  existing bubbles just get pushed apart smoothly.
- The dead `AnimatedVisibility(visible = true)` wrapper inside `ChatBubble`
  (never fired, captured the modifier) was removed.

## 4. Send button: one touch target with real press feedback

- Nested `IconButton` + outer `combinedClickable(onClick = {})` (two
  overlapping clickables, no visual response) replaced by a single
  `Surface` + `combinedClickable` sharing a `MutableInteractionSource`.
- `collectIsPressedAsState` drives a scale spring: pressed 0.9, ready 1.0,
  disabled 0.86. Ripple indication kept on this one target.

## 5. Navigation: shallow 210 ms transition

- Home → Conversation: no more 350 ms full-width slide. The new page
  slides in ~16% of its width over 210 ms (`FastOutSlowInEasing`) with a
  110 ms fade; Home fades out in 90 ms underneath.
- Back: Conversation slides right ~18% in 200 ms, Home fades in 140 ms.
- Other routes keep the existing slide+fade default.

## Invariants kept from v2.6.7

- `LazyColumn(reverseLayout = true)`, index 0 = newest; ViewModel order
  stays canonical oldest→newest.
- Incoming messages while reading history do not steal position (badge
  only); sending always re-arms latest-following.
- No `requestScrollToItem(lastIndex)` pattern, no O(N) scans, no backfill
  blocking conversation open.

## Verification

- `:app:compileDebugKotlin` / `:app:assembleDebug` — green
- `:app:testDebugUnitTest` — 153 tests, 0 failures, 0 errors
- Room schema unchanged (v6) — no migration in this release
