# v3.4.19 — The GMweb SMS path actually sends what it was given, on the SIM it was given

`versionCode 126` · `versionName "3.4.19"` · minSdk 26 · targetSdk 36

This release fixes three defects on the GMweb-requested SMS path. Each one was silent: none of them
produced an error anywhere, which is why all three survived.

---

## 1. The transmitted body was trimmed

`POST /api/v1/sms/send` read the message and then sent the trimmed value:

```kotlin
val message = json.optString("message", "").trim()   // transmitted as-is
```

`POST /send` (the EVE provider endpoint) did the same with `text`. `.trim()` removes leading and
trailing line breaks and spaces, so a message GMweb sent as `"\nline1\nline2\n"` left the device as
`"line1\nline2"` — a message the sender never wrote, on a channel where the recipient cannot tell it
was altered.

Now the body is normalised for **line-ending representation only** (`SmsBodyText`): CRLF and lone CR
become LF, and nothing else changes. Runs of newlines, a blank middle line, indentation, trailing
spaces, leading/trailing newlines and emoji all reach the radio exactly as received. Validation asks
`isSendable` (a `isBlank()` check) and never rewrites the value it is validating.

## 2. GMweb's SIM choice was discarded

The pull bridge never parsed a subscription from the task, `EveSmsQueue.Record` had no field for one,
and the queue-send seam hard-coded:

```kotlin
smsSender.sendForResult(record.to, record.text, subscriptionIdOverride = null, …)
```

So a line chosen in GMweb was silently replaced by the user's default line — the wrong-SIM outcome the
explicit choice exists to prevent.

The SIM now travels the whole way: `task.subscriptionId` (canonical; `subscription_id`, `subId`,
`sub_id`, and the same keys inside `meta` are accepted for payloads already in the field) →
`OutboxPoller.Task` → `EveSmsQueue.enqueue(subscriptionId = …)` → `Record.subscriptionId` →
persisted queue record → the send seam → `SmsSender`, whose existing fail-closed policy decides:

* requested line active and the bound `SmsManager` reports it → send on that line;
* requested line not in the active list → **refuse** (`sim_unavailable`);
* the manager reports a different line → **refuse** (`sim_mismatch`);
* a SIM was named but the value is unusable (negative, fractional, boolean, array, non-numeric) →
  the task fails closed with ACK `reason=invalid_subscription` and nothing is submitted — it is never
  downgraded to "no SIM was chosen";
* nothing named → the user's Messaging preference applies, as before.

`subscriptionId` is an additive, nullable field on the persisted record, so queues written by older
builds still load (`null` = no explicit choice). No database is wiped, no queued message is dropped.

## 3. `/ready` claimed readiness it could not have

`/ready` answered `isListening && isDefaultSmsApp() && queueRunning`, so a device with `SEND_SMS`
revoked — or with the selected SIM removed — reported `{"status":"ready"}` while every send was
guaranteed to fail.

The decision table now lives in `SmsSendPreflight` (pure, JVM-tested; `inspect()` is the only Android
entry point) and `/ready` returns `sendReady`, `gatewayReady`, the active subscriptions, the resolved
selection, and machine-readable `blockingReasons`: `gateway_not_running`, `queue_not_running`,
`permission_denied`, `not_default_sms_app`, `no_active_subscription`, `sim_unavailable`. The existing
fields (`status`, `error`, `serverRunning`, `defaultSmsApp`, `queueRunning`) are preserved; a healthy
device still gets `200 {"status":"ready", …}` and a blocked one `503`.

`MessagesApp.logMissingRuntimePermissions()` now includes `SEND_SMS`, so the denied permission is
named in diagnostics instead of the gateway merely looking "degraded".

## 4. `stage` on the ACK

`outcome` is the canonical set GMweb reads, and it cannot express the difference between "the radio
accepted the submit" and "the carrier accepted the message". The ACK now carries `stage`:
`submitted` for `outcome=sent`, `failed`, or `superseded`. A consumer never has to infer a carrier
verdict from a submission: the carrier's own verdict arrives separately on
`/gateway/delivery-report` (`delivered`/`failed`), from definitive evidence only.

---

## Contracts

`docs/gmweb-sms-gateway-contract-v1.md` documents the pull task (including `subscriptionId`), the ACK,
the delivery report, read events both ways, and `/ready`, with examples.

---

## Verification

```text
testDebugUnitTest   2,075 tests, 0 failures, 0 errors, 0 skipped   (195 test classes)
assembleDebug       PASS
lintDebug           PASS (0 errors; 319 pre-existing warnings, 7 hints — no new issue)
```

53 tests were added: `SmsBodyTextTest` (15), `SmsSendPreflightTest` (14),
`OutboxSubscriptionParseTest` (15), `EveQueueSubscriptionPersistenceTest` (9), plus one case in
`OutboxPollerPayloadTest` for `stage`.

---

## Known limitations

```text
NOT PHYSICALLY VERIFIED

No physical device, real SIM or real carrier was involved. Nothing in this release was validated
against a handset: not real SENT/DELIVERED callbacks, not physical SIM 1 vs SIM 2 selection, not
multipart delivery on a real network, not OEM behaviour.

NO REAL SMS WAS SENT DURING TESTING

Every telephony boundary in the tests is a fake; no paid SMS was sent.

PHONE-SIDE READ SYNC AND MARK_READ ARE PRE-EXISTING, NOT RE-VERIFIED HERE

The durable read events (MESSAGE_STATUS_CHANGED with read=true, CONVERSATION_UPSERTED, THREAD_READ)
and the encrypted MARK_THREAD_READ command already existed and are covered by their own tests. They
were audited, not changed.

OEM battery behaviour is not validated.
```

## Real-device checklist (not performed)

1. GMweb selects SIM 1: send `"Line one\nLine two\nخط سوم"` — exact line breaks, correct sending line.
2. Repeat with SIM 2 — confirm the message really leaves on SIM 2.
3. Disable the selected SIM and send: `NO SMS SENT`, ACK `reason=sim_unavailable`, no fallback.
4. Revoke `SEND_SMS`: no send, ACK/`/ready` blocker `permission_denied`.
5. Drop the SMS role: `not_default_sms_app`.
6. Multiline with a blank middle line: `"اول\nدوم\n\nچهارم"`.
7. Long Persian message: multipart send, all SENT callbacks, `sent` only after all parts.
8. Read an unread SMS on the phone: GMweb read state changes without a manual refresh.
9. `MARK_READ` from GMweb: the phone shows it read and the ACK reaches GMweb.
