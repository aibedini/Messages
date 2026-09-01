# ADR-006 — Sensitive Message / OTP Local-Only Firewall

**Status:** Accepted (Android Data Plane security boundary — authoritative)
**Date:** 2026-09-01
**Repos:** aibedini/Messages (owner of the boundary) · aibedini/GMweb-API (consumer, ciphertext-only)

## Context

Whether an SMS (OTP, dynamic bank code, verification code, financial security
message) may leave the device must be decided **on Android, before the
Gateway Outbox Event is constructed** — not in the web layer, not by a cloud
classifier. Web/GMweb have no right to be the primary classifier, and
"delete it later from the cloud" is explicitly NOT acceptable: the decision
point is before the event exists.

Architecture invariant:

```text
Telephony Provider
       ↓
Room local persistence
       ↓
SensitiveMessageFirewall
       ↓
Policy Decision
   ├── SYNC      → encrypt → Gateway Outbox
   └── LOCAL_ONLY→ STOP
```

For `LOCAL_ONLY`:

```text
NO message ciphertext to GMweb · NO phone number · NO sender
NO contact name · NO snippet · NO OTP
NO conversation event derived from that message · NO Web Push
```

Default is privacy-first: **zero cloud trace** for sensitive messages.

## Decision

### 1. Categories (deterministic, on-device, extensible)

```text
OTP_SECURITY_CODE
BANK_SECURITY_CODE
PASSWORD_RESET_CODE
AUTHENTICATION_CODE
FINANCIAL_NOTIFICATION
NORMAL
```

### 2. Default policies

```text
OTP_SECURITY_CODE        → LOCAL_ONLY
BANK_SECURITY_CODE       → LOCAL_ONLY
PASSWORD_RESET_CODE      → LOCAL_ONLY
AUTHENTICATION_CODE      → LOCAL_ONLY
FINANCIAL_NOTIFICATION   → user configurable (Ask / Sync / Keep local)
NORMAL                   → SYNC
```

### 3. Android Settings UI

```text
Settings → Privacy & Security → Sensitive messages

Keep OTP & security codes on this phone   [ ON ]   ← default ON
Bank security messages                    Never sync
Regular bank notifications                Ask / Sync / Keep local
Password reset & verification codes       Never sync

Always keep these senders on device       [ + Add sender ]
Always allow syncing from these senders            (explicit user override)
```

### 4. Per-sender policy

`SenderIdentity` supports BOTH phone numbers AND alphanumeric senders
(`BANKMELLAT`, `IR-MCI`) — never numeric-only normalization. Examples:

```text
BANKMELLAT → LOCAL_ONLY · IR-MCI → SYNC · +98210000 → LOCAL_ONLY
```

Explicit user lists: `local-only senders` and `sync allowlist` (allowlist
never overrides an OTP/bank-code classification — it only affects
NORMAL/financial categories).

### 5. Detection is fully on-device and deterministic

No SMS body is ever sent to GMweb, cloud AI, external APIs, or analytics for
classification. v1 classifier:

```text
sender rules + keyword rules + code-pattern analysis + user overrides
```

No cloud ML. Persian/English/Arabic keywords:

```text
OTP · verification · verification code · security code · one-time
one time password · PIN · رمز · رمز پویا · رمز یکبار مصرف · کد
کد تایید · کد تأیید · کد ورود · کد فعالسازی · کد فعال‌سازی · کد امنیتی
```

`DigitNormalizer` canonicalizes `۰۱۲۳۴۵۶۷۸۹`, `٠١٢٣٤٥٦٧٨٩`, `0123456789`
BEFORE detection. A bare 4–8 digit number is NOT sufficient — keyword/context
+ code pattern must co-occur (few false positives; the invoice-number case
stays NORMAL).

Financial distinction:

```text
"رمز پویای شما 392818 است"        → BANK_SECURITY_CODE → LOCAL_ONLY
"مبلغ 500,000 ریال واریز شد"      → FINANCIAL_NOTIFICATION → user policy
```

### 6. Fail-safe

High-confidence security-sensitive → `LOCAL_ONLY`. Ambiguous → configurable:
`privacy strict` (keep local) vs `balanced` (sync). Production security mode
default: **privacy strict**.

### 7. Classification strictly before the Outbox

Tested invariant: a `LOCAL_ONLY` classified message must produce **NO**
`GatewayEventOutbox` row — not a deleted row, not a web-hidden row. It must
never (even transiently) enter:

```text
GatewayEventOutbox · WebhookEngine payload · GMweb request
Push request · cloud logs
```

### 8. All outbound paths obey the firewall

Every network integration — GMweb, incoming webhooks, legacy cloud relay,
future integrations — sits AFTER `SensitiveMessageFirewall`. No legacy path
may bypass it.

### 9. Local completeness preserved

LOCAL_ONLY stops cloud sync only. On Android the message behaves normally:
Telephony Provider, Room, conversation UI, notifications.

### 10. Hidden-message indication (non-goal for v1)

Default: no cloud event at all. A future opt-in may send a bare
"private message received" event with no sender/body/phone/OTP — explicitly
out of scope for v1.

### 11. Local audit (no secrets in logs)

Android may log locally:

```text
message 1842 · classified OTP_SECURITY_CODE · policy LOCAL_ONLY · rule OTP_PERSIAN_KEYWORD
```

The audit entry must NEVER contain the OTP, body, or sender.

## Security invariant (from this ADR forward)

`SensitiveMessageFirewall` is part of the **Android Data Plane security
boundary**. No network integration may receive inbound SMS before this
boundary.

## Implementation timing

* ADR-005 and ADR-006 are recorded NOW.
* PR-01 durability foundation may continue, but schema and repository
  boundaries must know: **not every Telephony message produces a cloud
  event** — event creation sits behind an explicit
  `SyncEligibility`/`SensitiveMessagePolicy` boundary.
* Crypto stays OUT of PR-01.
* `SensitiveMessageFirewall` ships as an INDEPENDENT PR, mandatory BEFORE any
  Web/PWA history sync is enabled.

## Required regression tests (minimum)

```text
Persian OTP · English OTP · Arabic-digit OTP · Persian-digit OTP
alphanumeric bank sender · numeric bank sender · password reset
login verification · normal message containing a 6-digit invoice number
bank transfer notification without OTP · multiple numbers in financial SMS
custom local-only sender · custom sync allowlist
```

The most important test:

```text
classify LOCAL_ONLY → assert no GatewayOutbox row exists
```

(Assert the absence of the event, not merely that the web hides it.)
