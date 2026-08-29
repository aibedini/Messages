# Release v2.6.9 — Conversation Fluidity + Notification Deep Link

**versionCode 51 · Room schema v6 (unchanged)**

No new features. v2.6.8 added motion; this release fixes *what the motion was
actually made of*. Two kinds of list change were being solved with one tool
(`animateItem`) — existing rows moving, and new bubbles appearing. They are
different events and now have different animations. And two long-standing
breakages — the blank→POP first frame when opening a conversation, and
notification taps that opened the app but never the message — are gone.

---

## 1. animateItem() is placement-only now

```kotlin
Modifier.animateItem(
    fadeInSpec = null,
    fadeOutSpec = null,
    placementSpec = spring(dampingRatio = 1f, stiffness = 550f)
)
```

Critical damping on purpose: messenger bubbles must never bounce. animateItem
now only does what its name says — move *existing* rows out of the way when a
new one lands among them. It no longer owns "making a bubble appear".

## 2. Real live-bubble entrance — `ui/conversation/MessageEntrance.kt`

A dedicated enter motion for genuinely new messages:

```text
new bubble
    ↑ 10dp rise (190ms, FastOutSlowIn)
    scale 0.965 → 1 (spring 0.95/520, anchored at the bubble's bottom corner:
    bottom-right for outgoing, bottom-left for incoming)
    alpha 0 → 1 (110ms)
```

Not "absent → pop → present". ~190ms of *appearing*, then it just sits there.

## 3. Only LIVE messages animate

The ViewModel keeps `liveEntryIds` (a 24-entry cap, oldest evicted):

- `markForEntryAnimation(id)` before every `messages.add()` for: own
  optimistic sends (SMS + MMS image + MMS audio), and incoming SMS **only when
  `userAtLatest`** (a bubble off-screen doesn't need a show).
- `shouldAnimateEntry(id)` gates `MessageEntrance`; `consumeEntryAnimation(id)`
  clears the flag once the entrance completes, so recomposition never replays it.

**The hydration rule:** Room/cache hydration = NO per-message animation.
Live incoming/send = bubble entrance. Mixing the two is exactly what made the
screen feel like a Christmas tree on open — 20 bubbles animating at once.

Because LazyColumn only composes visible rows, sending from deep history also
behaves correctly: the optimistic bubble is marked, composed only when the
scroll reaches it, and animates *as it arrives*, not before.

## 4. Composer collapse — `animateContentSize(160ms, FastOutSlowIn)`

Sending a multi-line message used to collapse the composer 110dp→50dp in one
frame while the bubble appeared in the same frame — two stacked layout shifts.
Now three motions read as one event: composer gently folds, bubble gently
rises, old rows gently make room.

## 5. First-paint: no more blank → POP

Opening a conversation used to be: first frame empty → LaunchedEffect → Room →
whole list pops in. `reverseLayout` never fixed this because it was never a
layout problem — it is a first-paint problem.

**Handoff:** `navigation/ConversationLaunchStore.kt` — a `ConcurrentHashMap`
of one-frame snapshots (threadId, phone, name, message, date, type). Home
puts the row it is literally showing before navigating (zero IO), and
Conversation's first frame renders it as a real `ChatBubble` pinned bottom —
exactly where the LazyColumn will place it. When the first Room page arrives,
the two states crossfade (100ms in / 70ms out); same anchor, same bubble, so
the swap is invisible and history just quietly fills above it.

No snapshot (cold start, notification, new thread)? A `QuietConversationSkeleton`
— three low-alpha placeholder bubbles, no shimmer — instead of a blank page.

## 6. Notification tap actually opens the conversation (real bug, fixed)

`NotificationHelper` set `extra_thread_id`/`extra_phone`/`extra_name` on the
tap intent; `MainActivity` parsed only `ACTION_SEND`/`ACTION_SENDTO`. Nobody
ever read those extras — tapping a notification just opened the app.

- `navigation/AppLaunchTarget.kt`: sealed target + `AppLaunchIntent.parse()`
  for action `OPEN_CONVERSATION` (guards: threadId ≤ 0 && phone blank → null).
- Tap intent now carries that action, `FLAG_ACTIVITY_SINGLE_TOP`, and a
  per-thread `data` URI (`messages://conversation/{threadId}`) so PendingIntent
  identity stays distinct per thread; requestCode derives from the threadId.
- `MainActivity`: one `handleInboundIntent()` feeds both pending states;
  `onNewIntent` now calls `setIntent()` too.
- `AppNavigation`: `pendingNavigation` waits for the back stack to reach
  Home (never navigates on top of splash), then `popUpTo(Home, inclusive=false)`
  + `launchSingleTop`, and consumes.

Verified behavior matrix (cold/warm/locked):

| state when tapping | result |
|---|---|
| app closed | splash → Home → that Conversation |
| app on Home | Conversation opens |
| app on conversation A, notif for B | A pops, B opens |
| app on conversation B, notif for B | no duplicate destination |
| app locked | unlock → pending target survives → B opens |

---

## Files

| file | change |
|---|---|
| `ui/conversation/MessageEntrance.kt` | **new** — live-only entrance |
| `navigation/ConversationLaunchStore.kt` | **new** — one-frame handoff |
| `navigation/AppLaunchTarget.kt` | **new** — OPEN_CONVERSATION parse |
| `viewmodel/ConversationViewModel.kt` | liveEntryIds + marks (opt/incoming), hydration untouched |
| `ui/screens/ConversationScreen.kt` | placement-only animateItem, MessageEntrance wrap, AnimatedContent first-paint, LaunchPreview + QuietConversationSkeleton, composer animateContentSize |
| `ui/screens/HomeScreen.kt` | snapshot put before both navigate paths |
| `utils/NotificationHelper.kt` | real deep-link tap intent |
| `MainActivity.kt` | pendingNavigationState, handleInboundIntent, setIntent |
| `navigation/AppNavigation.kt` | pendingNavigation consumption gated on Home |

## Gates

- `testDebugUnitTest`: **153/153** green
- `assembleDebug` + `compileReleaseKotlin`: green
- No Room schema change (v6), no new dependency

## Known limits (next)

- Notification reply via the inline RemoteInput path opens the thread on tap
  after replying, but the reply itself still lands through the receiver
  (unchanged behavior).
- `ConversationLaunchStore` is process-lifetime on purpose; a cold process
  from a notification gets the skeleton, not a guessed bubble.
