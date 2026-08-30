# Release v2.6.17 — Standards-based delivery evidence + diagnostics

**versionCode 59 · Room schema v6 (unchanged)**

## SMS delivery semantics

Delivery state now follows Android's documented separation of evidence:

- `sentIntent` is modem/transport telemetry. Its raw result is logged and kept
  in the per-part ledger, but it is not written into the shared
  `Telephony.Sms.STATUS` TP-Status column.
- `deliveryIntent` is requested by default (user-controllable) and its raw
  SMS-STATUS-REPORT PDU is parsed with `SmsMessage.createFromPdu`.
- Callback PendingIntents are explicit, non-exported, and mutable so
  Android can attach the documented `pdu`/`errorCode` fill-in extras. The old
  immutable PendingIntent could silently discard the delivery PDU. They remain
  reusable so a temporary TP-Status can later progress to a final report.
- 3GPP TP-Status groups are mapped as specified by TS 23.040: `0x00..0x1f`
  delivered, `0x20..0x3f` temporarily pending, `0x40..0x7f` failed.
- A missing, malformed, delayed, or unsupported report remains Sent/unknown;
  the app never invents a failure. This is important on Iranian carrier/RIL
  combinations where modem acknowledgements and delivery reports can be lossy
  or contradictory, especially for multipart UCS-2 Persian messages.
- Multipart delivery is complete only after every part has positive evidence.
  A parsed permanent failure for any part fails the logical message.
- The SIM-programmed SMSC remains the default; no carrier SMSC is hardcoded.

## Self-SMS conversation crash

Opening a conversation after sending an SMS to the same phone could race the
live incoming event against the provider refresh. The same provider row could
briefly reach Compose twice while `LazyColumn` used an incomplete key, causing
the duplicate-key crash.

- Conversation rows are deduplicated by provider/model id before mapping.
- Body/time optimistic matching is now direction-aware, so the outgoing and
  incoming copies of a self-SMS are not incorrectly collapsed into one row.
- The screen now uses the existing stable `chatItemKey` (`id + date + type`),
  including locale-independent date separator keys.
- A regression test covers the self-SMS duplicate-provider-row race.

## On-device diagnostic log

- Private rotating log: three 384 KiB generations in app-internal storage.
- Records app/device session metadata, SMS dispatch, SIM/subscription, segment
  count, raw callback result code, PDU size, parsed delivery evidence, provider
  state transitions, conversation opens, guarded failures, and uncaught crashes.
- SMS bodies and full phone numbers are never written; phone references use a
  short SHA-256 token.
- Settings → Data tools → **Export diagnostic log** creates a user-approved
  shareable text snapshot.

## Live conversation follow and bubble motion

- Incoming SMS and sends created outside the open composer (REST gateway,
  queue, or notification quick reply) now share one live-insert path.
- When the reader is already at the newest edge, the new bubble is marked for
  entrance motion and the list follows it. When reading older history, the app
  preserves that position and increments the new-message badge instead.
- Scroll commands now retain the newest request during bursts and wait until
  Compose has both mapped and measured the target bubble before animating to
  it, eliminating the previous pre-layout no-op race.
- Live follow decisions are recorded in the privacy-safe diagnostic log.

## Research basis

- Android `SmsManager`: `sentIntent` reports transport success/failure;
  `deliveryIntent` carries the raw status-report `pdu`.
  <https://developer.android.com/reference/android/telephony/SmsManager>
- Android `SmsMessage`: `createFromPdu`, `isStatusReportMessage`, and `getStatus`
  expose the 3GPP/3GPP2 delivery status.
  <https://developer.android.com/reference/android/telephony/SmsMessage>
- Android Telephony provider: `STATUS` is explicitly the TP-Status value, with
  None/Complete/Pending/Failed status groups.
  <https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns>
- 3GPP TS 23.040 specification portal (SMS-STATUS-REPORT / TP-Status):
  <https://portal.3gpp.org/Specifications.aspx?WiUid=410014>
