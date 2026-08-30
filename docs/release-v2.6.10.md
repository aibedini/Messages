# Release v2.6.10 — Safety Pass (P0 Hardening)

**versionCode 52 · Room schema v6 (unchanged) · No new features**

This is **Pass 1 (Safety/P0)** of the architecture roadmap: close the six
red-severity defects before any durability or SSOT refactor. Each fix maps
1:1 to a review finding; behavior-visible changes are limited to the gateway
HTTP contract and notification-reply event flow.

---

## Fixes

### 1. NotificationActionReceiver: main-thread ANR path closed 🔴→✅

`onReceive` used to run `SmsRepository` provider writes and `SmsSender.send()`
— which blocks on the send rate limiter (`Thread.sleep`) — directly on the
main thread. All work now runs under `goAsync()` on `Dispatchers.IO`, with
`pendingResult.finish()` in `finally`.

Also removed the **duplicate event**: the receiver emitted a second `Sms`
event (via the incoming-flow emitter) right after `SmsSender` had already
persisted the reply and emitted its own `OutgoingSent`. One send → one event;
the downstream 5-second text-window dedupe heuristic now has nothing to
repair on this path.

### 2. Gateway `/api/v1/sms/send` can no longer lie about success 🔴→✅

`SmsSender.send()` returned a row id even when `SmsManager.sendTextMessage`
threw — dispatch failure was silently discarded and the gateway answered
`200 {"status":"success"}` for a message that never reached the modem.

New `SmsSender.sendWithOutcome(): SendOutcome` (`Accepted(rowId)` /
`Rejected(rowId, reason)`) and the gateway now answers:

- `202 Accepted` `{"status":"accepted","id":…}` — handed to telephony
  (SENT/DELIVERED still arrive via status callbacks; `Accepted` ≠ delivered)
- `503 Service Unavailable` `{"status":"failed","error":…}` — modem rejected

Request logs are PII-trimmed: only the last 4 phone digits are logged.

### 3. Gateway HTTP parser is byte-accurate UTF-8 🔴→✅

The old `BufferedReader` parser treated `Content-Length` as a **char** count,
but HTTP defines it in **bytes** — any Persian/emoji body under-read or
stalled until the 10s socket timeout (English-only testing never saw it).

- Headers are read as raw bytes up to CRLFCRLF (`readUntilHeaderEnd`,
  32KB cap), decoded ISO-8859-1 (octets preserved).
- The body is read as exact bytes and decoded UTF-8 only after full read;
  truncated bodies get `400`.
- `Transfer-Encoding: chunked` is now explicitly rejected with
  `411 Length Required` instead of being mis-parsed.

### 4. MMS `content://` + SSRF surface closed 🔴→✅

- `content://` is **no longer accepted from remote API callers** — a gateway
  client must not aim the app at arbitrary ContentProviders with the app's
  own permissions. (UI-internal MMS flows still pass content:// natively.)
- `https://` downloads now enforce a public-host policy: loopback, RFC1918
  (10/8, 172.16/12, 192.168/16), 169.254/16, 0.0.0.0/8, IPv6 loopback/
  link-local/site-local and fc00::/7 ULA are all refused — checked inside
  `downloadImageToCache` so every download path is covered, not just the
  route. The phone can no longer be used as a pivot into its own LAN.
- Error message updated (`imageUrl must be an https:// URL on a public host`).

### 5. Secrets fail closed on Keystore failure 🔴→✅

`GatewayPreferences.storeEncrypted` silently fell back to **plaintext** when
the Android Keystore was unavailable — converting a crypto incident into a
data-at-rest incident for the API key, webhook secret, bearer token and
registration secret. It now fails closed: if encryption is unavailable the
secret is **not persisted** (read-side re-encryption of legacy plaintext
values is unchanged), and the accessor returns blank until the Keystore
recovers or the secret is re-issued.

### 6. No more destructive Room fallback in release 🔴→✅

`fallbackToDestructiveMigration(dropAllTables = true)` wiped the local read
model (send_segments ledger, sync state, projections) on any missing
migration and forced a full Telephony re-crawl. It is now **DEBUG-only**;
release builds use explicit migrations only — a missing migration fails
loudly in QA instead of silently deleting user-visible state.

### 7. SmsStatusReceiver: durable async work 🟠→✅ (bonus)

The segment-ledger write and provider update outlive `onReceive` under
`goAsync()`/`finish()` instead of racing process death.

---

## Deliberately NOT in this pass

Per the agreed sequence, these stay open for later passes:
Durability (Room-backed OutgoingSmsQueue / GatewayOutbox — the receiver is
still not crash-proof against process death mid-send), single-SSOT/Home
observer consolidation, ConversationScreen/VM decomposition, MessageKey
identity, GatewayServer splitting, draft encryption, streaming export,
Macrobenchmark/CI gates.

## Files

| file | change |
|---|---|
| `receiver/NotificationActionReceiver.kt` | goAsync+IO, no duplicate emit |
| `sms/SmsSender.kt` | `sendWithOutcome()` + `SendOutcome` |
| `gateway/GatewayServer.kt` | byte parser, 202/503, SSRF guard, 411 |
| `gateway/GatewayPreferences.kt` | fail-closed secret storage |
| `data/MessagesDatabase.kt` | destructive fallback = debug only |
| `sms/SmsStatusReceiver.kt` | goAsync wrap |

## Gates

- `compileDebugKotlin` green · `testDebugUnitTest` **153/153** · `assembleDebug` green
- No schema change, no new dependency, no API breaking change beyond the
  documented `/api/v1/sms/send` status-code correction (200→202/503)
