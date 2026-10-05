# v3.4.29 — cross-conversation live message contamination, on-demand thread history

`versionCode 136` · `versionName "3.4.29"` · minSdk 26 · targetSdk 36

## 1. P0 — a live SMS for another conversation appeared inside the open one

Physically reproduced: conversation A open, an SMS arrives for B, and **the message for B rendered
inside A** (plus every other thread's messages). Leaving and reopening A made the foreign rows vanish,
which is the signature of an in-memory projection bug rather than a durable one: Room and the provider
were never wrong.

**Exact bad branch** — `ConversationViewModel.observeIncomingSms()`:

```kotlin
val genuinelyVisible = if (incomingThread > 0L) {
    VisibleConversationTracker.isVisible(incomingThread)
} else {
    VisibleConversationTracker.isVisibleForAddress(incomingSms.sender)
}

if (genuinelyVisible) {
    appendLiveMessage(readIncoming, source = "incoming")
    …
} else {
    appendLiveMessage(incomingSms, source = "incoming-unread")   // ← ANY thread reached here
    …
}
```

The collector asks only *"is this conversation visible?"* — and when the answer was **no** it appended
the message anyway, on the reasoning that "the bubble should still appear". Nothing proved the event
belonged to this ViewModel's conversation, so a foreign thread's SMS was added to `messages`, animated,
counted in `pendingNewMessagesCount` and written into `ThreadMessageCache` for A.

**Membership and visibility are different questions, and membership comes first.**

### The rule now

```text
BELONGS TO THIS CONVERSATION?
  no  → drop the live event entirely (CROSS_THREAD_LIVE_EVENT_IGNORED)
  yes → IS THE CONVERSATION GENUINELY VISIBLE (RESUMED, foreground)?
          yes → append + read authority applies (INCOMING_WHILE_VISIBLE)
          no  → append, preserve unread, no provider/Room read write
```

`ConversationMembership.belongs()` (pure, tested):

* **two authoritative thread ids decide alone** — a disagreement is final, even when the addresses
  normalise equal;
* **a bounded address fallback only when a thread id is genuinely unavailable**, with the same full
  address treated as the same conversation (this is what makes short codes and branded senders such as
  `PARSIANBANK` work at all, since the digit-oriented comparator normalises them to nothing) and the
  project's conservative comparator for differing spellings of one number;
* **an unknown or blank identity matches nothing**.

The rule is evaluated **at apply time**, so an event queued before an A → B navigation is judged against
the conversation that actually receives it — the navigation race cannot inject A's message into B.

### Defence in depth

`appendLiveMessage()` now refuses a foreign known thread itself (`CHAT_LIVE_REJECTED
reason=THREAD_MISMATCH`): no list append, no cache write, no animation, no pending counter. So the same
class of bug cannot return from another live-event path. Live-event sources audited: `incomingSmsFlow`
(fixed), `outgoingSentFlow` (already thread/address filtered — verified), provider/ContentObserver rows
(sync core, durable path, and its auto-read was already gated in 3.4.28), refresh signals (no message
append), the SMS pipeline and delayed/undo sends (they append optimistically to the conversation they
were sent from). Only `appendLiveMessage` inserts into an open window, which is why the guard sits there.

**Cache isolation:** `ThreadMessageCache.append(currentThreadId, currentPhone, row)` is now
unreachable for a foreign thread, which is asserted by the guard test.

## 2. `FETCH_THREAD_HISTORY` — real on-demand history

New command type on the **existing** transport: same `SecureCommandPoller`, same encrypted envelope,
same durable command lifecycle, same outbox. No second transport, no plaintext list in the ack.

```json
{ "type": "FETCH_THREAD_HISTORY", "requestId": "…", "conversationId": "gm-1",
  "androidThreadId": 12345, "before": { "dateMs": 1791000000000, "providerId": 987654 }, "limit": 20 }
```

* **Thread id is authority.** History is never resolved by phone normalisation, so Persian digits,
  short codes and alphanumeric senders all behave the same: query exactly the requested thread, or fail.
* **Bounded and keyset.** `LIMIT limit + 1` on `thread_id = ? AND (date < ? OR (date = ? AND _id < ?))`,
  ordered `date DESC, _id DESC`. No full scan, no OFFSET, no unbounded request: `limit` defaults to 20
  and is **clamped to 50**; a non-positive limit, a missing thread id or a half-specified cursor is
  refused (`CURSOR_INVALID` / `THREAD_NOT_FOUND`) instead of being guessed. The extra row is how
  `hasMore` is known without a second query, and it is not published.
* **Encrypted replication only.** The page is handed to the canonical ingest
  (`ingestProviderRows(..., HISTORY_BACKFILL)`), which is the same path normal history sync uses to
  produce encrypted events in the existing outbox. The acknowledgement carries `requestId`, `status`,
  `publishedCount`, `hasMore` and the next cursor — never a body, a phone number or a raw sender.
* **Honest completion.** `COMPLETED` means the rows are durably in the replication path or the phone
  positively proved there are none older. Otherwise the command is `FAILED` with its own machine code:
  `ROWS_PUBLISHED`, `END_OF_THREAD_HISTORY`, `THREAD_NOT_FOUND`, `CURSOR_INVALID`,
  `HISTORY_QUERY_FAILED`, `EVENT_ENQUEUE_FAILED`.

## 3. Capability advertisement is now one SSOT

`runtime.commandTypes` is derived from `CommandRouting.ADVERTISED_COMMAND_TYPES`, and
`EXECUTABLE_COMMAND_TYPES` is derived from `routeOf` — so the advertised list and the routable set
cannot drift. A drift test asserts `advertised == executable`, that only `SEND_SMS` reaches the SMS
pipeline, and that every capability has its own route.

```text
SEND_SMS · MARK_THREAD_READ · REFRESH_DEVICE_TELEMETRY · FETCH_THREAD_HISTORY
```

## 4. Client-message / delivery correlation — audit result

`clientMessageId` already survives intake → `RemoteCommandEntity` → `GatewayOutgoingPipeline` →
`SmsSender`, and the **durable** rowId → gateway request map (`GatewayDeliveryReports.remember`, written
synchronously before submission) is what lets SENT and DELIVERY callbacks — which know only the provider
row id, and may arrive after a process death — find the originating task. That is the existing suitable
store for this correlation, and the encrypted event payload already carries `clientMessageId`; no second
identifier and no in-memory map was introduced. Extending the outer (non-PII) event metadata with
`clientMessageId` / `originCommandId` is a GMweb contract change and is recorded as a dependency rather
than invented here.

## 5. Version, gates

```text
targeted: ConversationMembershipTest · ThreadHistoryCommandTest · CommandRoutingDriftTest    PASS
gradlew.bat testDebugUnitTest    PASS   (full suite, no failures)
gradlew.bat assembleDebug        PASS
gradlew.bat lintDebug            PASS   (no new errors)
```

## 6. NOT PHYSICALLY VERIFIED

Unit tests and a green build do not prove carrier behaviour or on-device UI behaviour. The real-device
matrix still to be run: A open while B/C/A receive SMS (A must show only A); five SMS for B while A is
open (zero B rows); A → B during arrival (no cross-thread row); a >100-message thread fetched with
`limit 20` (exactly the next older page, encrypted); the same request repeated (no duplicate events); and
a web `SEND_SMS` whose `clientMessageId` must still be present in the SENT and DELIVERED evidence.
