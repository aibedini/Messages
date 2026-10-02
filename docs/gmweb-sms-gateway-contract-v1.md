# GMweb ↔ Messages SMS gateway contract (Android side)

Source of truth for what this app accepts and emits on the GMweb bridge, as implemented. Where a
field is described as canonical, that is the one to send; the alternatives listed are tolerated for
payloads already in the field, not invitations to send something else.

All endpoints are authenticated with the existing gateway API key (`X-API-Key` / `Authorization:
Bearer`, enforced before routing). Nothing here weakens or replaces that.

## 1. Outbound task (GMweb → Android), legacy pull bridge

`GET /gateway/pull` returns at most one task:

```json
{
  "task": {
    "requestId": "gw-request-1",
    "to": "+989120000001",
    "text": "سلام\nخوبی؟\n\nفردا می‌بینمت.",
    "priority": "critical",
    "subscriptionId": 7,
    "meta": {
      "source": "eve",
      "serviceKey": "eve:server-1:client-1",
      "notificationKind": "volume_ended",
      "generation": 17,
      "correlationId": "3f1c0a2e-0000-4000-8000-000000000000",
      "requiresValidation": true
    }
  }
}
```

* `text` — transmitted **exactly** as received. Only CRLF / lone CR are normalised to LF. Leading and
  trailing newlines, runs of newlines, indentation and trailing spaces are content. Segmentation is
  `SmsManager.divideMessage`'s job, never a character count.
* `priority` — one of `critical` | `expired` | `expiring` | `announcement` (default `announcement`).
* `subscriptionId` — **canonical, optional, JSON number.** Null/absent means "no line was chosen, use
  the user's Messaging preference". A number means "send on exactly this Android subscription, or do
  not send". Also accepted, in this precedence order: `subscription_id`, `subId`, `sub_id`; then the
  same four keys inside `meta`. The canonical key wins.
  * A value that is present but unusable (negative, fractional, boolean, array, non-numeric string)
    is **not** downgraded to "no choice": the task fails closed with ACK
    `outcome=failed, stage=failed, reason=invalid_subscription` and nothing is submitted.
  * A numeric string (`"7"`) is accepted.
* `meta.requiresValidation` — when true, the record must pass the pre-send validation gate; an
  unobtainable verdict parks it in `DEFERRED` (fail-closed) rather than sending.
* `requestId` is the task's server-side identity and the `gatewayRequestId` reported on every ACK and
  delivery report. Re-pulling the same `requestId` reuses the durable local record: one task can never
  produce two physical SMS.

Per-message SIM availability is re-checked at send time against the device's active subscription list
and against the subscription the resolved `SmsManager` reports. A requested line that is gone is
refused (`sim_unavailable`); a manager bound to a different line is refused (`sim_mismatch`). There is
no silent fallback to another SIM, ever.

## 2. Status callbacks (Android → GMweb)

### `POST /gateway/ack` — one report per outbound task

```json
{
  "requestId": "gw-request-1",
  "ok": true,
  "outcome": "sent",
  "stage": "submitted",
  "sentAt": 1700000000000,
  "ackAt": 1700000000000
}
```

* `outcome` — the canonical set `sent` | `failed` | `superseded`, unchanged.
* `stage` — which physical stage this report describes:
  * `submitted` — the modem hand-off (`SmsManager.sendTextMessage` / `sendMultipartTextMessage`)
    returned without an immediate dispatch exception. This is **not** carrier acceptance and **not**
    delivery.
  * `failed` — nothing was submitted, or a part was refused. `reason` carries the machine code.
  * `superseded` — GMweb's own validation invalidated the task before submission.
* `reason` (present on failures): `invalid_subscription`, `sim_unavailable`, `sim_mismatch`,
  `device_send_failed`, `provider_error`, `cancelled_locally`, `interrupted_after_submit`,
  `validation_unavailable`, or a business reason such as `renewed`.
* `ok` is `true` only for `outcome=sent`.

**GMweb must treat `outcome=sent` as submission.** The carrier's verdict is a separate, later message
(§2.2). An ACK is retried until GMweb returns 2xx; ACK acceptance is recorded durably, so a restart
cannot lose or duplicate it.

### `POST /gateway/delivery-report` — carrier verdict, definitive evidence only

```json
{
  "eventId": "dlr_1f0c…",
  "requestId": "gw-request-1",
  "status": "delivered",
  "occurredAt": 1700000000000
}
```

* `status` — `delivered` (`Telephony.Sms.STATUS_COMPLETE`) or `failed` (`STATUS_FAILED`). Nothing else
  is ever reported: a temporary TP-Status, a missing/invalid report PDU or an ambiguous SENT
  `GENERIC_FAILURE` is **not** delivery evidence and produces no report.
* `eventId` is deterministic (`sha256(requestId|status)`), so a redelivered report is the same event
  and GMweb can dedupe it.
* Reports are persisted **before** upload and retried; a 404 `unknown_request_id` quarantines the
  report for operator review instead of discarding the evidence.
* The `requestId → Telephony row id` mapping is written durably *before* native submission, so a
  callback that arrives after process death still resolves to its task.

This is where "delivered" comes from. It is never inferred from `sent`.

## 3. Read state

### Android → GMweb: durable read events

A read that happens **on the phone** (conversation opened, notification action) writes the Android
provider (`SMS.READ = 1` via the `THREAD_ID`/`ADDRESS` path in `SmsRepository.markThreadAsReadStrict`)
and, in the SAME transaction, the Room shadow plus a durable `THREAD_READ` event. The sync then
observes the provider change and publishes, through the existing durable event outbox with
retry/backoff — never a fire-and-forget HTTP call:

* `THREAD_READ` at thread level — the primary read signal:

```json
{
  "type": "THREAD_READ",
  "conversationId": "…",
  "readAtMs": 1700000000000
}
```

* `CONVERSATION_UPSERTED` with the new `unreadCount`.
* `MESSAGE_STATUS_CHANGED` per affected message with `read: true` (the `READ_CHANGED` transition) —
  note this transition is only observable when the provider changes *before* the local shadow does.

`eventId` / `eventUuid` are deterministic (conversation + revision, and the thread-read identity
respectively), so retries and repeated reads of the same state are duplicates by construction and are
deduped by `insertOrIgnore`.

**Read state is gated on content, deliberately.** The read event is built from the thread's newest
message, and a conversation whose newest message is LOCAL_ONLY (OTP/bank/password-reset codes) is not
published at all. A read command for such a conversation is reported as
`READ_EVENT_PUBLISH_FAILED` — the phone is read, GMweb is not told, and the command does **not** claim
success.

### Read command failure codes (ACK `FAILED` result / durable `errorCode`)

```text
READ_COMMAND_DECRYPT_FAILED  payload could not be decrypted
READ_INVALID_PAYLOAD         payload decrypted but has no usable conversationId
READ_MAPPING_MISSING         no remote_conversation_map row for that conversationId
READ_UNKNOWN_CONVERSATION    a mapping exists but its thread is not present locally
READ_PROVIDER_WRITE_FAILED   the Android provider refused the read write
READ_EVENT_PUBLISH_FAILED    provider updated, but the durable read event was refused
READ_PERMISSION_DENIED       provider permission missing while applying the read
```

A read failure is never reported as `SMS_SEND_FAILED` or a bare `UNKNOWN`.

### GMweb → Android: `MARK_THREAD_READ` command (control plane, encrypted)

```json
{ "type": "MARK_THREAD_READ", "conversationId": "…" }
```

* Executed through the existing encrypted command path (`cryptoVersion=1`), capability `MARK_READ`,
  and idempotent: re-executing the same command succeeds, because the state it sets is already set.
  It is the one re-drivable type.
* The command is ACKed only after the Android provider update actually succeeded. Failing to resolve
  the conversation, or a provider update failure, is reported as a failure, never as success.
* Opening a conversation in GMweb does **not** mutate phone read state. Only this command does.

## 4. `GET /ready`

```json
{
  "status": "ready",
  "ready": true,
  "gatewayRunning": true,
  "serverRunning": true,
  "gatewayReady": true,
  "queueRunning": true,
  "defaultSmsApp": true,
  "sendSmsPermission": true,
  "subscriptionsKnown": true,
  "activeSubscriptions": [
    { "subscriptionId": 1, "slotIndex": 0, "displayName": "SIM 1", "carrierName": "Carrier", "active": true }
  ],
  "selectedSubscriptionId": 1,
  "selectedSubscriptionAvailable": true,
  "sendReady": true,
  "blockingReasons": []
}
```

Blocked example (`SEND_SMS` revoked, no active SIM): HTTP **503**

```json
{
  "status": "not_ready",
  "error": "not_ready",
  "ready": false,
  "gatewayRunning": true,
  "serverRunning": true,
  "gatewayReady": true,
  "queueRunning": true,
  "defaultSmsApp": true,
  "sendSmsPermission": false,
  "subscriptionsKnown": true,
  "activeSubscriptions": [],
  "selectedSubscriptionId": null,
  "selectedSubscriptionAvailable": true,
  "sendReady": false,
  "blockingReasons": ["permission_denied", "no_active_subscription"]
}
```

* `gatewayReady` — HTTP gateway + send queue are alive.
* `sendReady` — an SMS may actually be attempted: gateway and queue up, `SEND_SMS` granted, SMS role
  held, and a usable line resolved (`sim_unavailable` when the explicitly selected line is not active;
  `no_active_subscription` when the device reports none).
* `blockingReasons` — machine codes: `gateway_not_running`, `queue_not_running`, `permission_denied`,
  `not_default_sms_app`, `no_active_subscription`, `sim_unavailable`.
* `subscriptionsKnown=false` means the device withheld the subscription list (no `READ_PHONE_STATE`).
  Availability is then *unknown* and is not reported as a blocker; the manager-level fail-closed check
  still refuses a proven mismatch at send time.
* No IMSI, ICCID or phone number is exposed.

## 6. Device telemetry / presence (Android → GMweb)

`POST /api/v1/agent/device-telemetry` (authenticated, `X-Agent-Auth` signed). The 60-second heartbeat
remains, and it is no longer the only report: the same payload is sent immediately on `STARTUP`,
`NETWORK_RECONNECTED`, `GATEWAY_CONNECTED`, `SUBSCRIPTIONS_CHANGED`, `DEFAULT_SMS_CHANGED`,
`PHONE_PERMISSION_GRANTED`, `APP_UPDATED` and `MANUAL_DIAGNOSTIC_REFRESH` (bursts are debounced, at
most one report in flight and one queued behind it).

```json
{
  "deviceId": "…",
  "timestamp": 1700000000000,
  "trigger": "SUBSCRIPTIONS_CHANGED",
  "smsSubscriptions": {
    "available": true,
    "reason": null,
    "permissionGranted": true,
    "defaultSubscriptionId": 1,
    "lastChangedAt": 1699999990000,
    "items": [
      { "subscriptionId": 1, "slotIndex": 0, "displayName": "SIM 1", "carrierName": "Carrier",
        "isDefaultSms": true, "isActive": true }
    ]
  },
  "permissions": { "readPhoneState": true, "sendSms": true, "defaultSmsRole": true },
  "battery": { "level": 71, "isCharging": false, "chargingSource": "NONE" },
  "sync": { "outboxDepth": 0, "realtimeQueueDepth": 0, "backfillQueueDepth": 0,
            "deadLetterCount": 0, "historyAckLag": 0, "trustOutboxDepth": 0 },
  "trust": { "isEnrolled": true, "approvedDevicesCount": 1, "trustSequence": 12 },
  "network": { "isConnected": true, "networkType": "WIFI" },
  "app": { "versionName": "3.4.19", "versionCode": 126, "uptimeMs": 51234 },
  "device": { "manufacturer": "samsung", "model": "SM-G998B", "androidVersion": "15", "sdkInt": 35 }
}
```

* `smsSubscriptions.available` means the question was ANSWERED — it is `true` with an empty `items`
  when the device genuinely has no active subscription.
* `reason` distinguishes the two non-answers: `READ_PHONE_STATE_MISSING` (the permission is missing —
  the user can fix it) and `SUBSCRIPTION_API_FAILURE` (granted, but the platform refused). Neither is
  ever reported as "no SIM".
* `available`, `items` and every previously-published field keep their old meaning; the rest is
  additive.
* `trigger` says why this report exists, so a consumer can tell a heartbeat from a change report.
* Presence/"last seen" should be derived from the server's own receive time; the device timestamp is
  for diagnostics only.

## 5. Other send endpoints (unchanged contract, body rule applies)

* `POST /send` — EVE provider endpoint, body field `text`. Normalised for line endings only.
* `POST /api/v1/sms/send` — body field `message` (same rule), optional `subscription_id`
  (`-1` = user preference), optional `idempotency-key` header or `idempotencyKey` body field.

Both are already authenticated; neither sends a body it rewrote.
