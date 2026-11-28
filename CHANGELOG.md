# 🧾 Changelog

All notable changes to **Messages** are recorded here. Every release tag gets a matching
`## vX.Y.Z` section; the release workflow publishes that section as the GitHub Release body.

> ✍️ **Before tagging:** add a `## vX.Y.Z — YYYY-MM-DD` section with emoji and bold feature names.
> The workflow extracts it verbatim; if it is missing, it falls back to the commit list.

---

## v3.11.14 — 2026-11-28

### 🛡️ On-device spam shield

- **Automatic spam category:** a **service sender** (short code or alphanumeric originator) plus
  promotional/phishing language plus a **link or call-to-action** now files the conversation under
  **Spam** — no cloud, no account, no message content leaving the phone.
- **Quiet by default:** a message auto-filed as spam does **not** post a notification. OTP and
  transaction messages are classified first, so bank receipts and verification codes are never
  silenced by a stray word.
- **Persian + English patterns:** discounts, “free”, prizes, loans, crypto, card/dynamic-password
  requests, Telegram/short links and “click/register/buy” actions.
- **Not spam is learned:** pressing **Not spam** pins the conversation to **Personal**; the automatic
  heuristic cannot re-file it, and notifications resume. The override can be reset from
  Conversation Info.
- **Known contacts are never spam**, even when they forward an advertisement.
- **Conservative by design:** a plain discount campaign stays in **Promotion**; only **received**
  messages can be auto-spam (a sent advertisement never hides your own conversation); order/payment/
  tracking updates and bank security warnings are excluded; nothing is ever deleted.

---

## v3.11.13 — 2026-11-25

### 📞 SMS during a phone call

- **Use the call's own line:** if you did not pin a line for the conversation, a message sent during a
  call now goes out on the SIM carrying that call instead of the system-default SIM (which a
  single-radio phone cannot use while the other SIM is on a call).
- **Correct dual-SIM call detection:** the phone now scans the active subscriptions for a non-IDLE
  call state. Previously, the sending SIM reporting `IDLE` was treated as proof that the call was on
  the *other* SIM, which could hold a perfectly valid VoLTE/LTE send.
- **Held messages keep their line:** a message queued behind a call remembers the resolved SIM and is
  released on that same line when the call ends, not re-routed through the system default.
- **Explicit choices still win:** a sticky conversation SIM, a per-message SIM, or the global SIM
  preference is never silently overridden; if that line is blocked by a call, the message waits.

> 📡 **Incoming SMS:** the app has no call-state gate on the receive path. Delivery during a call
> depends on the carrier supporting SMS over IMS (VoLTE). On a 2G/3G call the network cannot deliver
> an SMS until the call ends; if it still does not arrive after hang-up, the issue is outside the
> app (carrier or default-SMS-app state).

---

## v3.11.12 — 2026-11-24

### 🏷️ Sender naming contract

- **`senderKind`** now travels with every message/conversation event: `PHONE`, `SHORT_CODE`,
  `ALPHANUMERIC` or `UNKNOWN`.
- **`displayNameSource`** distinguishes a real **CONTACT** name from an **ADDRESS** fallback, and
  **`namingVersion`** versions the contract.
- **Machine-readable spec:** [`protocol/sender-naming-v1.json`](protocol/sender-naming-v1.json)
  defines the consumer precedence **USER > CONTACT > DIRECTORY > ADDRESS**.
- **No more silent name downgrades:** an `ADDRESS` fallback must never overwrite a user, contact or
  directory name — the exact path that turned a known bank into *“Unknown conversation”*.
- **Test fix:** the preferred-SIM privacy test no longer uses Base64 substring checks (a collision,
  not a leak); the payload-shape fixture includes the new naming fields.

---

## v3.11.10 — 2026-11-24

### 🕵️ Hide from web

- **3-dot menu + contact actions:** hide or show a conversation/contact on the GMweb companion.
- **Web copy removed:** hiding queues a `CONVERSATION_DELETED` event so the conversation and its
  messages disappear from the web replica.
- **Future events suppressed:** messages, conversation upserts, SIM-preference changes and suggestion
  feedback for a hidden conversation never enter the cloud outbox.
- **Contacts excluded:** hidden numbers are omitted from contact snapshots and published as deleted.
- **Reversible locally:** pending rows are retained with a private `HIDDEN_FROM_WEB` marker and are
  restored when you show the conversation again.
- **AI scope safety:** hidden conversations are never offered as AI-device grants.
- **Clearer diagnostics:** the retained historical backlog is labeled separately from live failures.

### 🩹 Fix

- **Lint-clean snackbars:** strings are resolved at composition instead of calling
  `context.getString` from a composable scope.

---

## v3.11.8 — 2026-11-24

### 🔐 Per-conversation AI grants + scope (PR #24)

- **`KEY_GRANT` / revoke per conversation:** HPKE-wrapped to the AI device key and signed by the
  phone’s **ECDSA P-256** trust root, with a monotonic `scopeSequence`.
- **Room v30:** new `linked_device_scope_*` tables and migration, with atomic scope updates.
- **Scope screen:** account-wide or an explicit conversation list; sensitive/`LOCAL_ONLY` threads are
  never listed or grantable.
- **Revocation lifecycle:** pause all access, revoke one conversation, sensitive reclassification,
  and single-device unlink hooks.
- **Existing protections stay:** capability contract, biometric approval, and `SEND_MESSAGES` never
  granted to AI devices.

---

## v3.11.7 — 2026-11-23

### 💾 Export every conversation (for real)

- **Per-thread `_id` paging** replaces the multi-column keyset that a device provider silently
  ignored; the old code could report *“500 messages in 1 conversation”* as success.
- **Hard completeness check:** if fewer messages are read than the provider reported, the archive is
  **discarded** and the export fails with both counts.
- **Same check for XML backups** — no more plausible-looking truncated files.
- **Regression test:** a 500-of-50,000 read must throw.

---

## v3.11.6 — 2026-11-22

### 📈 Streamed backup & export with real progress

- **Staged XML backup:** rows are written to a private staging file, then `<smses count>` is emitted
  from what was actually written, and copied bytes are verified.
- **Streamed JSON export:** one conversation at a time; no whole-inbox `JSONObject` tree and no OOM
  on 40–50k messages.
- **Live progress + cancel:** rows, bytes, percent and a working cancel path in Settings → Data tools.
- **Process-scoped operations:** leaving Settings no longer cancels a backup/export; the result is
  shown when you return.
- **Batched restore:** 200 inserts per transaction with a per-row fallback.
- **Safe text encoding:** XML control characters are stripped; Persian text and emoji round-trip.
- **50k-row tests:** exact counts, short-write failure and both archive formats.

---

## v3.11.5 — 2026-11-16

### 🤖 AI devices, suggestions and supply-chain hardening

- **AI_AGENT linked devices:** QR pairing, explicit capability approval, biometric confirmation and
  a fail-closed capability policy (`READ_MESSAGES` + `AI_SUGGEST` only).
- **Suggestion cards:** `SUGGESTIONS_READY` rendering under the newest inbound message, composer-only
  insertion, expiry and newer-message hiding.
- **Suggestion feedback:** `selected` / `edited` / `rejected` / `reported` through the encrypted
  outbox.
- **Security:** gitleaks secret scanning and stricter release gates.

---

<div align="center">

**Older releases:** see the [GitHub Releases page](https://github.com/aibedini/Messages/releases). 🚀

</div>
