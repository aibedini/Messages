# Release v2.2.0 — real MMS receive, hardened incoming path

## Added

### MMS reception actually works now

Until this release the app could *send* MMS (images, audio, group text via
`SmsManager.sendMultimediaMessage`) but **could not receive one**. `MmsReceiver`
was a logging stub — and because `WAP_PUSH_DELIVER` is only delivered to the
default SMS app, becoming the default app meant every inbound MMS was silently
dropped: no download from the carrier MMSC, no row in `Telephony.Mms`, nothing.

The receive path is now a complete stack, mirroring how Fossify Messages does it:

1. **mmslib** (`org.fossify:mmslib:1.0.0` — the Fossify fork of klinker's
   android-smsmms) is wired in. Its manifest-declared `PushReceiver` takes
   `WAP_PUSH_DELIVER`, persists the notification-indication into
   `Telephony.Mms`, and its `TransactionService` downloads the actual payload
   from the carrier MMSC over the MMS APN.
2. Our `.receiver.MmsReceiver` extends the library's `MmsReceivedReceiver`
   (matched via taskAffinity). When the download finishes it reads the
   persisted row back **from the provider** — FROM address from `content://mms/addr`
   (skipping `insert-address-token` placeholders), text body from the
   text/plain part, dates converted seconds→millis — and hands the result to
   the shared dispatcher.
3. Blocked senders are screened before any fan-out; the library's own
   screening hook (`isAddressBlocked`) uses the same blocklist.

New Gradle wiring: JitPack repository + `libs.mmslib`, and R8 keep-rules for
the library's transaction/PDU machinery.

### One dispatcher for every incoming message

New `IncomingMessageDispatcher` is the single fan-out point for SMS **and**
MMS: event bus → gateway webhook/cloud event → notification. The logic lived
inline inside `SmsReceiver` only; MMS would have had to duplicate it. Both
paths now share it, including the "user is currently viewing this conversation"
check via a new pure `ContactRepository.sameConversation()` predicate
(normalization + suffix matching), covered by 5 new unit tests.

## Changed

### Incoming SMS: persist first, then trust only the provider

The receiver previously built the UI/webhook model from broadcast extras with
a made-up id (`System.currentTimeMillis()`) and `threadId = 0L`. Now:

1. Heavy work runs inside `goAsync()` on a background thread — inserting rows
   plus network work inside the bare `onReceive` window risked the process
   being killed mid-INSERT.
2. The inbox INSERT carries a real `THREAD_ID` resolved via
   `Telephony.Threads.getOrCreateThreadId`, so the platform updates the Threads
   table exactly as it does for the stock SMS app (same class of bug fixed for
   outgoing rows in v2.1.6).
3. After persisting, the row is **read back from Telephony.Sms** and everything
   downstream (UI event, webhook payload, notification) is dispatched from that
   confirmed state — single source of truth instead of optimistic local state.

## Verification

- `assembleDebug` ✓ · `testDebugUnitTest` ✓ (new `SameConversationRoutingTest`,
  suite green)
- MMS end-to-end requires a device with SIM/carrier MMSC: send yourself an MMS
  and confirm it lands in the conversation list.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.1.6...v2.2.0
