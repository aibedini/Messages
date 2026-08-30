---
type: "Reference"
title: "Workflow: Incoming Message to UI, Webhook, and Notification"
openwiki_generated: true
verified:
  - by: openwiki/0.4.3
    at: 2026-08-30T16:24:36.837Z
sources:
  - id: openwiki-source-186e96b8d6739f3745947903
    resource: repo://app/src/main/AndroidManifest.xml
  - id: openwiki-source-c4ec49afa1d2ec40206e27c3
    resource: repo://app/src/main/java/com/autonomousone/messages/data/ChangeRouter.kt
  - id: openwiki-source-b4330449f6d1b6163298aae4
    resource: repo://app/src/main/java/com/autonomousone/messages/data/LocalProviderWrites.kt
  - id: openwiki-source-b394c571401a67cd53a9d162
    resource: repo://app/src/main/java/com/autonomousone/messages/data/TelephonySyncCoordinator.kt
  - id: openwiki-source-ce0009a1275103aa86ecc82a
    resource: repo://app/src/main/java/com/autonomousone/messages/data/UnreadDelta.kt
  - id: openwiki-source-deeb7f22dbb08abc85208b19
    resource: repo://app/src/main/java/com/autonomousone/messages/event/SmsEventBus.kt
  - id: openwiki-source-5b64d9fe16083515732d7fa1
    resource: repo://app/src/main/java/com/autonomousone/messages/gateway/GatewayAccessPolicy.kt
  - id: openwiki-source-8234b1c40928ccc75e3a6a70
    resource: repo://app/src/main/java/com/autonomousone/messages/gateway/WebhookEngine.kt
  - id: openwiki-source-8f0b87397d9aa4ca05c1f774
    resource: repo://app/src/main/java/com/autonomousone/messages/MainActivity.kt
  - id: openwiki-source-f624f17c409bc74370fff0b7
    resource: repo://app/src/main/java/com/autonomousone/messages/observer/SmsContentObserver.kt
  - id: openwiki-source-362c458761cae734c7912208
    resource: repo://app/src/main/java/com/autonomousone/messages/receiver/IncomingMessageDispatcher.kt
  - id: openwiki-source-cbedede08291dc5be228b226
    resource: repo://app/src/main/java/com/autonomousone/messages/receiver/MmsReceiver.kt
  - id: openwiki-source-99fef859245bb7a59c2e041e
    resource: repo://app/src/main/java/com/autonomousone/messages/receiver/SmsReceiver.kt
  - id: openwiki-source-43a076a66e5ae070cd7e78f5
    resource: repo://app/src/main/java/com/autonomousone/messages/repository/BlocklistRepository.kt
  - id: openwiki-source-311ed32a68df077c7ffde611
    resource: repo://app/src/main/java/com/autonomousone/messages/repository/SmsRepository.kt
  - id: openwiki-source-133b4174f0a9fbf729268733
    resource: repo://app/src/main/java/com/autonomousone/messages/utils/NotificationHelper.kt
  - id: openwiki-source-23938f5029c2373f94d20806
    resource: repo://app/src/main/java/com/autonomousone/messages/viewmodel/ConversationViewModel.kt
  - id: openwiki-source-f6696f3e9ef52f48e813b920
    resource: repo://app/src/main/java/com/autonomousone/messages/viewmodel/HomeViewModel.kt
  - id: openwiki-source-353c7d0bc150b187b3587e50
    resource: repo://app/src/test/java/com/autonomousone/messages/ChangeRouterExtractIdTest.kt
  - id: openwiki-source-72b0bf10f6f0169c18fa69f3
    resource: repo://app/src/test/java/com/autonomousone/messages/SameConversationRoutingTest.kt
  - id: openwiki-source-4910c1194d1e60dcd7fb5000
    resource: repo://app/src/test/java/com/autonomousone/messages/SmsObserverTimingTest.kt
  - id: openwiki-source-7abea9ce6f657aff34d4e142
    resource: repo://app/src/test/java/com/autonomousone/messages/UnreadDeltaTest.kt
generated: { by: "openwiki/0.4.3", at: "2026-08-30T16:24:36.837Z" }
---


# Workflow: Incoming Message to UI, Webhook, and Notification

This page follows one inbound message from the moment Android raises a broadcast to the moment the user sees it — on the Home list, in the open conversation, on a webhook endpoint, or as a notification. The pipeline is defined by one invariant and one funnel:

- **Persist before fan-out.** The Telephony provider is the single source of truth. Every consumer (Room shadow, UI, webhook, notification) is fed from *provider-confirmed state* — the row the receiver just wrote, or read back after someone else wrote it. No consumer ever sees optimistic local state as truth.
- **One funnel.** Every inbound path — default-app `SMS_DELIVER`, non-default `SMS_RECEIVED`, and MMS via mmslib — terminates at `IncomingMessageDispatcher.dispatch(context, sms, source)`, which applies a fixed order: Room mirror → blocklist gate → address-cache invalidation → event bus → webhook → notification.

The static anatomy of each component lives on [Incoming SMS/MMS Reception](/openwiki/architecture/incoming-messaging.md) and [Provider-to-Room Sync](/openwiki/architecture/sync-coordinator.md); the transport details of the two webhook legs live on [Incoming-SMS Webhooks and Cloud Events](/openwiki/integrations/incoming-webhooks.md); the paged conversation window that renders the appended row is on [Conversation Window and Keyset Pagination](/openwiki/architecture/conversation-paging.md). This page is the trace itself.

## The whole trace

```mermaid
sequenceDiagram
    participant P as Telephony provider
    participant SR as SmsReceiver
    participant D as IncomingMessageDispatcher
    participant C as TelephonySyncCoordinator
    participant RM as Room shadow
    participant OBS as SmsContentObserver
    participant CR as ChangeRouter
    participant CB as SmsEventBus
    participant W as WebhookEngine
    participant N as NotificationHelper
    participant H as HomeViewModel

    alt Default SMS app
        P->>SR: SMS_DELIVER broadcast
        SR->>SR: goAsync, work on background thread
        SR->>P: INSERT inbox row with resolved THREAD_ID
        P-->>SR: persisted row id
        SR->>P: read row back by id
        P-->>SR: provider-confirmed Sms
        SR->>D: dispatch with confirmed row
    else Non-default SMS app
        P-->>SR: SMS_RECEIVED only, system default app writes the row
        SR->>D: dispatch with broadcast fallback model
        OBS->>CR: provider change fires with row URI or null
        CR->>C: targeted mutate or bounded reconcile
    end

    D->>C: mutate(Upsert) mirrors to Room first
    C->>RM: transaction upsert message and conversation
    RM-->>H: Room Flow re-emits, list repaints
    D->>D: blocked sender? early exit, silent
    D->>D: invalidate address caches
    D->>CB: emitSms
    CB-->>H: optimistic prepend to Home list
    D->>W: sendIncomingSmsWebhook, fire and forget
    D->>N: showSmsNotification unless viewing this conversation
```

Caption: one received SMS traced from broadcast to every consumer — the default-app persist/read-back path, the non-default ContentObserver fallback, and the dispatcher's fixed fan-out order with its three parallel branches (Room/UI, webhook, notification).

## Step 1 — Broadcast entry: `SmsReceiver`

`SmsReceiver` is declared in the manifest (`app/src/main/AndroidManifest.xml`) exported with `android.permission.BROADCAST_SMS`, with two intent filters of priority 999 — `SMS_RECEIVED` and `SMS_DELIVER`. `onReceive` handles the dedupe that makes the two roles safe:

- **When this app IS the default SMS app**, `SMS_DELIVER` fires (it only goes to the default app) and the receiver skips any `SMS_RECEIVED` for the same PDU — otherwise the same message would be processed twice. The default-app check is `Telephony.Sms.getDefaultSmsPackage(context) == context.packageName`, with exceptions collapsing to `false`.
- **When this app is NOT the default SMS app**, only `SMS_RECEIVED` fires; the system default app writes the inbox row.

Heavy work never runs on the broadcast thread: after `goAsync()`, the receiver spawns a `Thread` that runs `processIntent` and calls `pending.finish()` in a `finally`. This keeps the system from killing the process mid-INSERT — the provider write and read-back are the one piece of work that must complete under the broadcast lifetime.

`processIntent` parses PDUs with `Telephony.Sms.Intents.getMessagesFromIntent`, concatenates multipart (concatenated SMS) parts into one body, and bails out on an empty body or blank sender. The canonical thread id is resolved up front via `IncomingMessageDispatcher.resolveThreadId`, a wrapper over `Telephony.Threads.getOrCreateThreadId` that returns `0L` on failure so callers keep a well-formed model instead of inventing ids.

## Step 2 — Persist, then read back (the SSOT rule)

For `SMS_DELIVER` (default-app path only), the receiver:

1. **INSERTs the row itself** into `Telephony.Sms.Inbox` with `ADDRESS`, `BODY`, `DATE`, `DATE_SENT`, `READ=0`, `SEEN=0`, `TYPE=MESSAGE_TYPE_INBOX`, and the resolved `THREAD_ID` (so the platform Threads table stays consistent, mirroring what the stock SMS app does). The insert is wrapped in `try/catch`; a failure yields `persistedId = -1`.
2. **Reads the freshly persisted row back** from `Telephony.Sms` by id (`readBackFromProvider`), so every downstream consumer sees exactly what the provider holds — the real row id, the provider-normalized `THREAD_ID`, the authoritative `DATE` and `READ` flag.
3. **Falls back to broadcast data** when the row is not visible (non-default role, insert failure, read-back exception): a `Sms` model built from the PDU with `id` set to the PDU timestamp as a surrogate, `threadId` from `resolveThreadId`, `unread = true`. The fan-out still runs; the fallback degrades id quality, not delivery.

Then a single call — `IncomingMessageDispatcher.dispatch(context, sms)` — ends the receiver's job.

The MMS path reaches the same funnel a different way: mmslib's manifest-declared `PushReceiver` takes `WAP_PUSH_DELIVER`, persists the notification-indication into `Telephony.Mms`, and drives `TransactionService` to download the payload from the carrier MMSC. When the download finishes it broadcasts `MMS_RECEIVED` to the app's `MmsReceiver` (found by `taskAffinity`, so it is not exported and carries no intent filter). `MmsReceiver` reads the persisted `Telephony.Mms` row back plus its `addr` (FROM, type 137 preferred) and `text/plain` part, normalizes dates from seconds to milliseconds, negates the id (the repo-wide convention that MMS ids are negative so mixed UI lists never collide), and calls `dispatch(context, sms, source = MessageEntity.SOURCE_MMS)`. The `source` argument selects the shadow's keyset space and dedupe id; `TelephonySyncCoordinator` maps the negative id back with `abs()` when keying the Room shadow. Blocked MMS senders are additionally screened inside the library via the `isAddressBlocked` override, which is backed by `BlocklistRepository`.

## Step 3 — The single fan-out: `IncomingMessageDispatcher.dispatch`

`dispatch` is the one funnel for all inbound traffic (SMS and MMS alike). Its contract is that `sms.id`/`sms.threadId` **must** come from the provider row that was just written or read back. It runs on a caller-supplied background context and does network/DB work freely. The steps, in this exact order:

1. **Room shadow mirror — always first.** `TelephonySyncCoordinator.get(context).mutate(MessageMutation.Upsert(source, sms))`. This is the ordering guarantee of the whole pipeline: persistence into the provider precedes every fan-out, and the Room mirror precedes every *other* fan-out. Comment in code: blocking is a notification policy, not a sync policy — the row stays persisted either way.
2. **Blocked-sender early exit.** `BlocklistRepository.isBlocked(context, sms.sender)` — the static shortcut exists so broadcast receivers can check without instance state. If blocked, `dispatch` **returns immediately**: no bus event, no webhook, no notification. Silent handling; the message remains persisted in the provider and the Room shadow. Blocking silences, it does not delete, and blocked senders are invisible to webhook consumers by construction.
3. **Address-cache invalidation.** `SmsRepository(context).invalidateAddressCaches()` drops the process-wide `AddressCache` (the canonical thread→address map and the per-thread cache used by the conversation-list fast path). A brand-new sender must not render as "Unknown" on the next list refresh — the caches are dropped so names re-resolve against the contact list.
4. **Optimistic UI nudge.** `SmsEventBus.emitSms(sms)` — a fire-and-forget `SharedFlow` (no replay, `extraBufferCapacity = 64` absorbs bursts). It is a nudge, not a data source: the authoritative repaint comes from Room invalidation (step 5 below), and the bus makes the first frame instant.
5. **Webhook dispatch.** `WebhookEngine.sendIncomingSmsWebhook(context, sms)` — fire-and-forget, consent-gated inside (see step 5 below).
6. **Notification.** `NotificationHelper.showSmsNotification(context, sms)` — **skipped when the user is actively viewing this conversation**: `ContactRepository.sameConversation(sms.sender, SmsEventBus.activeConversationPhone) && SmsEventBus.isAppInForeground`. `activeConversationPhone` is set by `ConversationViewModel` on conversation load and cleared on `onCleared`; `isAppInForeground` is set by `MainActivity.onResume`/`onPause`. `sameConversation` normalizes (strips non-digits, handles `+`) and matches on suffix with a ≥7-digit guard so short fragments never join two conversations. So: viewing the *other* chat → notify; app backgrounded → notify; viewing *this* chat in foreground → suppress.

### The Room mirror: `mutate(Upsert)` to the O(1) fast path

`TelephonySyncCoordinator` is the **single writer into Room**, a per-process singleton with two completely separate channels:

- **`mutations`** — `Channel(MessageMutation, capacity = UNLIMITED)`. Exact, sequential, **never conflated, never dropped**: a bounded channel plus `trySend` would silently lose an event under a burst (100 SMS in one second), leaving that row stale in the shadow until the next reconcile.
- **`reconciles`** — `Channel(ReconcileRequest, capacity = CONFLATED)`. N queued nudges collapse into one bounded repair pass (startup, crash recovery, observer fallback).

For an incoming message, `applyMutation(Upsert)` runs **one Room transaction**: upsert the `MessageEntity` (keyed by `(source, providerId)`), compute the unread delta with `UnreadDelta` (signed O(1) — brand-new unread is +1, re-upsert is 0, unread→read is −1; the thread is never recounted), and upsert the conversation projection with `upsertPreservingFlags` — a *true* upsert, so a brand-new thread is INSERTed right here and Home never depends on a later rebuild. `lastMessageDate` is the max of new and existing, and pin/archived flags are preserved.

The committed transaction invalidates the Room `Flow`s. That is the authoritative leg of the UI update: `HomeViewModel.observeRoomConversations` collects `conversationDao().observeAll()` and repaints the list once the read-cutover gate is open (see below). The event-bus prepend from step 4 is what makes the row appear *instantly*; the Room Flow commit is what makes it *correct* — and `HomeViewModel` deliberately does **not** run a provider scan on the bus event, because the exact mutation already reached Room.

**Read-cutover gate.** Room may serve the UI only after both sources have mirrored their initial window: `isShadowReady()` checks `initialWindowReady` for both `SOURCE_SMS` and `SOURCE_MMS` in the sync-state table, and the flag flips only *after* the conversations projection has been rebuilt from the mirrored messages — the UI can never observe "ready" against an empty projection. Until the gate opens, Home falls back to the provider path (or the persisted `ConversationCache`); if the shadow DB cannot be opened at all (failed migration), `HomeViewModel` disables the cutover and degrades to the provider path — the shadow is a read model, the provider remains truth.

**Conversation screen.** `ConversationViewModel` collects the same bus flow: when the incoming sender matches the open conversation (`sameConversation`), it appends the row via `appendLiveMessage` (deduping by id, or by body + ≤5 s time proximity so the bus row collapses with the provider-confirmed one), scrolls to it when the user is at the latest edge, and — because the user is watching — marks the thread read in the provider (`repository.markThreadAsRead`, which updates both `Telephony.Sms.READ` and `Telephony.Mms.READ` and notes the write in `LocalProviderWrites`). That provider write fires the ContentObserver again, closing the loop on the fallback path below.

### The webhook branch: `WebhookEngine`

`sendIncomingSmsWebhook` is gated once, up front, by `GatewayAccessPolicy.canTransmit(prefs.hasGatewayConsent, prefs.isEnabled)` — versioned privacy consent **and** the supervisor-derived runtime `isEnabled` flag. If the gate fails, *both* legs are skipped at once. When open:

- **Local webhook** — JSON POST (`event: "sms_received"`, sender, message, timestamp, threadId) to the user-configured URL on the engine's IO scope. Non-HTTPS URLs are rejected and logged. With a configured secret, the body is HMAC-SHA256 signed over `"<timestamp>.<body>"` and sent as `X-Signature` + `X-Timestamp` so receivers can verify authenticity and reject replays. Timeouts are 8 s each; failures are logged, never thrown.
- **Cloud event** — the same message posted as `type: "sms.received"` to the backend's `/api/gateways/events/sms`, only when the gateway is registered with a token. The `eventId` is a **deterministic** UUID (name-based hash of sender + date + first 100 chars of body), checked against a local sent-event cache before sending and marked sent only on `Success` — a crash before acknowledgment re-derives the same id, and the backend dedupes on it. A `Failure` leaves the event unmarked (in practice `SMS_DELIVER` fires once, so a permanently down backend means a lost event; the backend's own retry covers downstream webhook delivery).

Both legs are fire-and-forget: nothing downstream of the dispatcher depends on webhook success, and the receiver process is already past `pending.finish()` by then.

### The notification branch: `NotificationHelper`

`showSmsNotification` (only reached when the viewing-suppression check passed):

- Checks `POST_NOTIFICATIONS` on Android 13+ and silently returns if not granted.
- Posts on `messages_notification_channel` (`IMPORTANCE_HIGH`) with **notification id = `sender.hashCode()`** — per-sender dedupe, so a new SMS from the same sender replaces the previous notification.
- Resolves the contact display name (normalized lookup, then raw).
- **Quiet hours**: if inside the configured daily window, the notification still appears but silently — no defaults, `PRIORITY_LOW`.
- **OTP extraction**: if the body contains trigger keywords (otp, code, verification, …) a 4–8 digit code is highlighted in the title (`🔑 OTP: …`) with a dedicated "Copy <code>" action.
- Always carries an inline **Reply** action (`RemoteInput`) and a **Mark as read** action, both targeting `NotificationActionReceiver`.
- Tap intent targets `MainActivity` with `AppLaunchIntent.ACTION_OPEN_CONVERSATION`, per-thread `threadId`/`phone` extras, and a per-thread data URI (`messages://conversation/<threadId>`) so PendingIntents of different threads stay distinct; `VISIBILITY_PRIVATE` hides body and OTP on the lock screen.

## Step 4 — The non-default-app fallback: ContentObserver → `ChangeRouter`

When the app is **not** the default SMS app, the receiver never INSERTs: the system default app writes the inbox row, the receiver's read-back returns nothing, and dispatch goes out from the broadcast fallback model (id = PDU timestamp). The *authoritative* row reaches the Room shadow a different way — through the provider change itself:

1. **`SmsContentObserver`** — attached by both `HomeViewModel` and `ConversationViewModel` via `SmsRepository.registerObserver`, which registers the same observer on **both** `Telephony.Sms.CONTENT_URI` and `Telephony.Mms.CONTENT_URI`. It fires on the **main looper** with leading-edge dispatch: the first change in a burst fires immediately (millisecond-live), and any further change inside `COALESCE_MS = 150 ms` collapses into a single trailing call that passes `null` (unknown change → reconcile). `onChange(selfChange, uri)` deliberately does **not** call `super` — the base class would delegate back into the other override and double-dispatch every change.
2. **`ChangeRouter.route(context, uri)`** — the decision point, never doing provider I/O on the main thread:
   - **URI with an extractable row id** (`content://sms/348201`): the source table is chosen by **authority** (`content://mms/…` → MMS, else SMS — not path substring, which would misroute `content://sms/thread/…` and OEM `mms-sms` URIs). Off the main thread, the router reads *exactly that one row*; a hit becomes `mutate(Upsert)` (the targeted O(1) path), a miss (row deleted externally) becomes `mutate(Delete)`. Extraction is deliberately conservative: `/thread/` paths yield `null` because the trailing number there is a thread id, not a row id — reading it as `_ID` would upsert a random unrelated message.
   - **URI without a row id, or `null`** (table-level change, OEM without row URIs, or the coalesced trailing call): the router consults `LocalProviderWrites.claimRecentMarkRead()` — a 2-second window of the app's own mark-read writes — and downgrades to `reconcile(ForThread(threadId))` when its own bulk mark-read explains the burst; otherwise `reconcile(FullSync)`, the bounded dual-source window sync.

The row-id URI is treated as an **optimization, not a contract**: Android *may* deliver it, but OEM builds frequently do not. The reconcile channel exists precisely so a deployment without row URIs still converges — with the cost of a bounded watermark-based window instead of the whole inbox.

This observer path is what keeps the shadow authoritative for non-default deployments, and it doubles as the general repair path: any provider change the app did not itself dispatch (other apps' writes, status callbacks, external edits) flows through the same router.

## Invariants and failure semantics

- **Persistence precedes fan-out; provider state precedes everything.** No consumer (UI, webhook, notification, shadow) is ever fed optimistic state as truth; a read-back failure degrades to broadcast data with a logged warning rather than aborting delivery.
- **The Room mirror is unconditional** — it precedes the blocklist gate, so a blocked message exists in the provider *and* the shadow; blocking silences bus/webhook/notification, it does not delete.
- **Exactly-once Room writes on the realtime path.** The UNLIMITED mutation channel never conflates or drops; one message = one transaction = one Flow emission. Full scans (`fullRebuildConversations`, the keyset backfill) are confined to the conflated reconcile path and never run on the incoming-message hot path.
- **Two identity spaces, one key.** SMS ids are positive provider ids; MMS ids are negated at the `MmsReceiver` boundary and restored with `abs()` in `TelephonySyncCoordinator.providerId`; the composite shadow key is `(source, providerId)` with stable wire values `"sms"`/`"mms"`.
- **The broadcast window is bounded and sufficient.** Only the INSERT + read-back must complete inside `goAsync()`; the Room commit, webhooks, and notification are handed to background scopes (coordinator loop, engine IO scope, main-thread notification post) and survive `pending.finish()`.
- **The shadow is degradable.** If Room cannot be opened, the read-cutover latch disables Room reads and the app continues on the provider path — a broken shadow must never kill the app.

## Focused tests

The boundary logic that the pipeline depends on has JVM tests that pin it:

- `ChangeRouterExtractIdTest` — URI parsing: `//sms/348201` → `348201`, `//sms` → null, `/thread/123` → null (a thread id is not a row id), non-numeric/blank → null. This is exactly the row-id-vs-reconcile decision of the non-default fallback.
- `SmsObserverTimingTest` — the observer's timing contract: the first change fires synchronously on the leading edge (<50 ms), and a 10-change burst fires only once (the rest coalesces into the trailing call).
- `UnreadDeltaTest` — every state transition of the O(1) unread-delta rule the mutation transaction relies on (new unread +1, re-upsert 0, unread→read −1), so a 360K-message thread pays nothing per incoming SMS and badges actually come down.
- `SameConversationRoutingTest` — the `sameConversation` predicate behind both the viewing-suppression check and the blocklist matching: normalization, country-code suffix matching, and the ≥7-digit guard.

## Related pages

- [Incoming SMS/MMS Reception](/openwiki/architecture/incoming-messaging.md) — static anatomy of the receiver layer, mmslib, dispatcher, and blocklist
- [Provider-to-Room Sync](/openwiki/architecture/sync-coordinator.md) — the dual-channel coordinator, cutover gate, and durable backfill
- [Incoming-SMS Webhooks and Cloud Events](/openwiki/integrations/incoming-webhooks.md) — the two webhook legs, signing, and idempotency
- [Conversation Window and Keyset Pagination](/openwiki/architecture/conversation-paging.md) — how the conversation screen renders the appended live row
