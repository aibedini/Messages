<div align="center">

# 📱💬 Messages

### **Your phone. Your messages. Your API.** 🚀

**A private, self-hosted Android SMS/MMS app that turns your phone into an automation endpoint — with encrypted web sync, AI-assisted replies, and real privacy controls.**

[🔐 Privacy](PRIVACY.md) · [🚀 Releases](https://github.com/aibedini/Messages/releases) · [📖 Wiki](openwiki/index.md) · [🧾 Changelog](CHANGELOG.md) · [📡 API docs](docs/api/openapi.yaml)

[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF.svg?style=flat&logo=kotlin)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20%28API%2026%2B--36%29-3DDC84.svg?style=flat&logo=android)](https://developer.android.com)
[![Material 3](https://img.shields.io/badge/UI-Jetpack%20Compose%20%C2%B7%20Material%203-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/aibedini/Messages?style=flat-square&color=22c55e)](https://github.com/aibedini/Messages/releases)

</div>

---

## 🌟 What is Messages?

**Messages** is a modern **default SMS/MMS app** for Android that also runs a **self-hosted HTTP gateway** and **webhook engine** on your own phone. No third-party SMS cloud, no monthly fee, and **no server address baked into the APK** — nothing is contacted until *you* configure it. 🔒

It is an **independent, actively developed project**: the messaging UI, gateway, encryption, AI-suggestion pipeline, diagnostics and data tools are all built and maintained here.

> 🛡️ **Privacy-first** — telemetry is off by default; OTP and bank security codes are classified **LOCAL_ONLY** and never leave the device, while other financial notifications follow your explicit policy.
>
> ⚡ **Automation-ready** — REST API, signed webhooks, scheduled sends, and a persistent priority queue.
>
> 🤖 **AI with a human in the loop** — suggestions are only ever *suggestions*; tapping one fills the composer, it never sends.
>
> ☁️ **Web companion on your terms** — encrypted replication to your own GMweb server, with per-conversation grants and a one-tap **Hide from web**.

---

## 🎯 Why people use it

- **📵 Own the pipe** — your SIM, your phone, your messages; no SaaS middleman.
- **🤝 Own the trust** — the phone is the trust root; web devices and AI devices are approved on-device with biometrics.
- **🧠 Useful automation** — send, schedule, read, forward, and react to SMS from any script or dashboard.
- **🇮🇷 Made for Persian** — Persian/Arabic digits, RTL layouts, Jalali calendar, and Persian-aware links and phone numbers.
- **🧩 Hackable** — clean Kotlin, Room SSOT, pure-policy modules and a machine-readable protocol folder.

---

## 💬 Messaging that feels native

- 💬 **Full SMS + MMS** — real-time inbox, group MMS, media previews, location sharing and audio messages.
- ⌨️ **Quick replies** — type `/c1` in any chat for a template; create, edit and delete templates from Settings.
- 📝 **Drafts** — leave a chat mid-typing and the draft is saved; the list shows a live *Draft:* line.
- 📌 **Pin · 🗂️ archive · 🗑️ trash** — swipe actions with undo, plus a durable **Recently Deleted** screen.
- 🚫 **Block & report spam** — silent blocking with provenance, so a Not-Spam action never silently unblocks someone.
- 🛡️ **On-device spam shield** — service-sender shape + Persian/English promo and phishing patterns + links; high-confidence spam moves to **Spam**, its notification is silenced, and **Not spam** teaches the filter to leave that conversation alone.
- 🔍 **Search everywhere** — conversation names and message bodies, with Persian-aware prefix matching.
- 📶 **Dual-SIM aware** — in-chat SIM switcher and a sticky per-conversation SIM preference.
- 📊 **Standards-based segment counter** — GSM-7 160/153 and UCS-2 70/67 per 3GPP TS 23.038.
- 🔒 **Biometric app lock** — fingerprint, face or device credential.
- 🌈 **Beautiful by default** — dark & light themes, six accent presets, and a **Persian (Jalali) calendar**.
- 🔗 **Smart links & numbers** — tappable inside bubbles, including «۰۹۱۲…» Persian-digit numbers.

---

## 🌐 Gateway & automation

- 🌐 **Embedded HTTP server** — `ServerSocket` inside a foreground service, default port **8080**.
- 🔑 **API-key authentication** — `X-API-Key` with brute-force lockout and rate limiting.
- 📤 **Send SMS + MMS** — text, media, and multi-part messages.
- 📥 **Inbox & status APIs** — recent messages, device status, delivery state.
- ⏰ **Scheduled sends** — via API or long-press-send in chat; survive reboot with WorkManager.
- 🪝 **Signed webhooks** — HMAC-SHA256, persistent priority queue, retries and dead-letter visibility.
- 🩺 **Health & readiness** — `/health`, `/ready`, `/send/capacity` for your uptime checks.
- 🧾 **API docs** — full OpenAPI contract in [docs/api/openapi.yaml](docs/api/openapi.yaml).

---

## 🤖 AI, web sync & privacy boundaries

- 🧠 **AI reply suggestions** — 2–3 contextual cards under the latest inbound message; tap **only fills the composer**, never sends. Cards disappear when a newer message arrives or `expires_at` passes.
- 🤝 **Linked AI devices** — QR pairing, explicit capability approval, and a hard rule: **`SEND_MESSAGES` is never granted** to an AI device.
- 🔐 **Per-conversation grants** — `KEY_GRANT` / revoke events wrapped to the AI device key and signed by the phone’s **ECDSA P-256** trust root, with a monotonic `scopeSequence`.
- 🎛️ **Conversation scope** — share all eligible conversations or an explicit list; sensitive/`LOCAL_ONLY` threads are never listable or grantable.
- 🕵️ **Hide from web** — from the 3-dot menu or contact actions: removes the conversation/contact from the web replica, suppresses future sync events, and restores it when you show it again.
- 🏷️ **Versioned sender naming** — `senderKind`, `displayNameSource` and `namingVersion` travel with the payload so a replica can never turn a bank name into *“Unknown conversation”*. Contract: [protocol/sender-naming-v1.json](protocol/sender-naming-v1.json).
- 🛡️ **SensitiveMessageFirewall** — OTP, bank security codes and password resets are kept local by default; financial notifications follow your explicit policy.
- ☁️ **GMweb sync** — encrypted event replication to your own server, with a live diagnostics card and connectivity probes.
- 📊 **Honest diagnostics** — separate dimensions for upload, pull bridge, auth, queue depth and retained historical backlog.

---

## 💾 Data tools you can trust

- 💾 **XML backup / restore** — compatible with *SMS Backup & Restore*; exact `<smses count>` from the rows actually written.
- 🧾 **JSON export of every chat** — streamed conversation-by-conversation; no whole-inbox object tree, no OOM.
- 📈 **Real progress** — rows, bytes and percent, with **Cancel** that cleans up partial files.
- ✅ **Completeness checks** — a short read is an error, not a silent “success”; the archive is discarded instead of handed out incomplete.
- 🗑️ **Bulk delete by period** — SMS + MMS, with a clear before/after choice.
- 🧹 **Trash** — recover or permanently delete without touching the provider’s other rows.

---

## 🔐 Security model

- **No secrets in the app** — the server origin is user configuration; API keys live on-device and are shown once.
- **Phone as trust root** — device certificates are signed by a non-exportable **ECDSA P-256** key in Android Keystore.
- **Encrypted replication** — message events travel in versioned encrypted envelopes; the outer transport carries IDs and crypto metadata only.
- **Fail closed** — capability violations refuse signing; sensitive messages never enter the cloud outbox; invalid/expired keys are rejected.
- **Two-way consent** — nothing listens or syncs until the gateway is explicitly enabled; blocked numbers never fire webhooks.
- **Auditable** — structured diagnostics, no message bodies or full numbers in logs/exports.

---

## 🧱 Architecture at a glance

```text
┌──────────────────────────── Android phone ────────────────────────────┐
│  🎨 Compose UI  ⇄  🧠 ViewModels  ⇄  🗄️ Room SSOT  ⇄  📞 Telephony     │
│                                      │                               │
│                      🔐 SensitiveMessageFirewall                     │
│                                      │                               │
│              📦 Encrypted outbox  ──┴──  🌐 Embedded HTTP gateway     │
│                       │                         │                    │
└───────────────────────┼─────────────────────────┼────────────────────┘
                        │ HTTPS                   │ REST / webhooks
                        ▼                         ▼
                ☁️ GMweb (server-blind)      🤖 Your scripts & dashboards
                        │
                        ▼
                🧠 GOD AI / AI suggestions
```

- **Kotlin + Jetpack Compose (Material 3)** — modern Android UI.
- **Room as the read SSOT** — reactive repositories and StateFlow.
- **WorkManager** — scheduled sends and durable background work.
- **ServerSocket gateway** — no external server needed for LAN automation.
- **Protocol folder** — machine-readable contracts shared with other systems.

---

## 📡 REST API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/sms/send` | 📤 Send SMS text |
| `POST` | `/api/v1/mms/send` | 🖼️ Send MMS image |
| `GET` | `/api/v1/sms/inbox` | 📥 Recent inbox messages |
| `GET` | `/api/v1/status` | 📊 Gateway/device status |
| `POST` | `/api/v1/sms/schedule` | ⏰ Schedule an SMS (`sendAt` epoch-ms or `delaySeconds`) |
| `GET` | `/api/v1/sms/schedule` | 🗓️ List scheduled sends |
| `GET`/`DELETE` | `/api/v1/sms/schedule/{id}` | 🔎 Status / cancel a scheduled send |
| `POST` | `/send` | 🤝 EVE-provider compatible enqueue |
| `GET` | `/send/status/{id}` | 📬 EVE job status |
| `POST` | `/send/cancel/{id}` | 🛑 Cancel queued EVE job |
| `GET` | `/health` · `/ready` · `/send/capacity` | 🩺 Health probes |

> 🔢 Persian digits in `phone`/`to` are normalized automatically.

### 🧪 Examples

```bash
# Send now
curl -X POST http://PHONE_IP:8080/api/v1/sms/send \
  -H "X-API-Key: KEY" -H "Content-Type: application/json" \
  -d '{"phone": "+989124887338", "message": "Hello!"}'

# Send in 1 hour (survives reboot)
curl -X POST http://PHONE_IP:8080/api/v1/sms/schedule \
  -H "X-API-Key: KEY" -H "Content-Type: application/json" \
  -d '{"phone": "09124887338", "message": "Reminder", "delaySeconds": 3600}'

# Check a scheduled send
curl http://PHONE_IP:8080/api/v1/sms/schedule/sch_xxxx -H "X-API-Key: KEY"
```

---

## 🪝 Incoming webhooks

Incoming SMS are POSTed as JSON to your endpoints and signed with **HMAC-SHA256** (`X-Signature`). Configure them in **SMS Gateway → Incoming SMS Webhook**. 🚫 Blocked numbers never trigger webhooks.

---

## ☁️ Optional GMweb server

The gateway can connect to a **GMweb server** so external projects reach the phone through a fixed HTTPS URL instead of a changing LAN IP.

- 🎯 **One address** in Settings → Gateway carries enrollment, events, heartbeat, trust **and** the SMS pull bridge.
- 🕵️ **No built-in backend** — nothing is contacted until you configure a server.
- 📡 **Heartbeats** report battery, signal and queue depth while the Gateway Service is on.
- 🔒 **HTTPS only** — certificate and hostname validation cannot be disabled.
- ⛔ **Nothing is sent unless the service is enabled** in Settings → Gateway.

### 🌍 Remote access options

| Option | Best for | Notes |
|---|---|---|
| ☁️ **GMweb server** | A fixed public URL | You supply the server |
| 🌩️ **Cloudflare Tunnel** | Free stable domain | `cloudflared tunnel --url http://localhost:8080` |
| 🕸️ **Tailscale** | Private devices only | No exposed ports; mesh VPN |
| 🏠 **Port forwarding + DDNS** | Fixed home WiFi | Router config required |
| 🧪 **ADB reverse** | Emulator/testing | `adb reverse tcp:8080 tcp:8080` |

---

## 🚀 Quick start

1. 📲 Install the [latest APK](https://github.com/aibedini/Messages/releases) on an **Android 8.0+** phone with an active SIM.
2. ✅ Complete onboarding: accept the disclosure → set as the **default SMS app** → grant SMS/Contacts permissions.
3. 🌐 Open **3-Dots Menu → SMS Gateway** → enable it → copy your API key.
4. 🧪 Test from your computer:

```bash
curl http://PHONE_IP:8080/health
```

---

## 🧪 Development

```bash
git clone https://github.com/aibedini/Messages.git
cd Messages
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # lint gate
```

Optional build-time cloud override:

```bash
./gradlew assembleDebug -PGATEWAY_BACKEND_URL=https://your-relay.example.com
```

> 🧠 **Tip:** The project is developed with AI agents. Read [AGENTS.md](AGENTS.md) and the
> [OpenWiki quickstart](openwiki/quickstart.md) before a large change.

---

## 📚 Documentation

- 📖 [OpenWiki](openwiki/index.md) — architecture, data model, gateway, sync, UI, testing, workflow traces.
- 🧾 [Changelog](CHANGELOG.md) — what changed in every release.
- 📡 [API contract](docs/api/openapi.yaml) — request/response schemas.
- 🤝 [Sender naming contract](protocol/sender-naming-v1.json) — shared with GMweb/GOD AI.
- 🧭 [ADRs](docs/adr/) — the decisions behind encryption, sync and trust.

---

## 🗺️ Roadmap

- [ ] 🧠 Shared **spam-sender reputation** across devices (local heuristic ships first).
- [ ] 🏦 Shared **Iranian bank/service sender directory** for every replica.
- [ ] 🖥️ Full **scheduled sends management** screen.
- [ ] 🇮🇷 Complete Persian localization + RTL polish.
- [ ] 🌐 Messages-for-Web **PWA** on top of the embedded server.
- [ ] 📊 Richer **AI suggestion analytics** (selected / edited / rejected).

---

## 📄 License

**MIT** — see [LICENSE](LICENSE). ❤️

<div align="center">

### ⭐ If Messages is useful to you, star the repo — it helps others find a private alternative.

**Made with ☕ + 📱 + a lot of attention to privacy.**

</div>
