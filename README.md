# 📱 Messages — Android SMS/MMS App + Self-Hosted SMS Gateway

[Privacy Policy](PRIVACY.md) · [Releases](https://github.com/aibedini/Messages/releases) · [Wiki 📖](openwiki/)

[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF.svg?style=flat&logo=kotlin)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20%28API%2026%2B--36%29-3DDC84.svg?style=flat&logo=android)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/aibedini/Messages?style=flat-square&color=22c55e)](../../releases)

**Messages** is a modern Android default SMS/MMS app with an embedded
**HTTP REST API Gateway** and **Webhook Engine**. Turn any Android phone into
a self-hosted SMS/MMS automation endpoint — no third-party cloud fees.

> Forked from [manjitrana/Messages](https://github.com/manjitrana/Messages) and
> heavily extended: scheduled sends, quick replies, drafts, blocklist, app lock,
> backups, Persian-digit support and more (see below).

---

## ✨ Features

### Messaging (default SMS app)
- 💬 Full SMS + MMS: real-time inbox updates, group MMS, media previews,
  location sharing, audio messages
- ⌨️ **Quick replies** — type `/c1` in any chat for template responses;
  create / edit / delete from Settings
- ↪️ **Forward**, long-press action menu (copy · copy link/number · forward · details)
- 🔗 **Tappable links & phone numbers** inside bubbles — including
  **Persian/Arabic digits** («۰۹۱۲…» → call / SMS / add to contacts)
- 📝 **Drafts** — leave a chat mid-typing and the text is saved; the list shows
  an italic red *Draft:* line (live-updated)
- 📌 Pin conversations · 🗂 Archive (swipe) · 🚫 Block numbers (silent receiver)
- 🔍 Search across conversation names *and* all message texts
- 👤 "you" marker under the date when the last message is yours
- 🔄 Pull-to-refresh inside a thread
- 📶 **In-chat SIM switcher** when 2+ SIMs are active
- 📊 Standards-based segment counter (GSM-7 160/153 · UCS-2 70/67 per 3GPP TS 23.038)
- 🔒 Biometric app lock (fingerprint / face / device credential)
- 🌙 Dark & light themes, six accent presets, Persian (Jalali) calendar

### Gateway & Automation
- 🌐 Embedded HTTP server (`ServerSocket`, foreground service, port `8080`)
- 🔑 `X-API-Key` authentication + brute-force lockout + rate limiting
- 📤 REST endpoints: send SMS/MMS · inbox read · status · EVE provider contract
- ⏰ **Scheduled sends** via API or long-press-send in chat — survive reboot (WorkManager)
- 🪝 Webhooks with HMAC-SHA256 signatures · persistent priority queue (`EveSmsQueue`)
- 💾 XML backup/restore compatible with "SMS Backup & Restore"

---

## 🚀 Quick Start

1. Install the [latest APK](../../releases) on an Android 8.0+ phone with an
   active SIM.
2. Complete onboarding: accept disclosure → set as default SMS app → grant
   SMS/Contacts permissions.
3. Open **3-Dots Menu → SMS Gateway** → toggle on → copy your API key.

The gateway listens at `http://<phone-ip>:8080`. See
[Accessing over the Internet](#-accessing-the-gateway-over-the-internet) for
remote access options.

## 🔐 Authentication

```http
X-API-Key: your_api_key
```

View/regenerate the key in-app under **3-Dots Menu → SMS Gateway**.

## 📡 REST API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/sms/send` | Send SMS text |
| POST | `/api/v1/mms/send` | Send MMS image |
| GET | `/api/v1/sms/inbox` | Recent inbox messages |
| GET | `/api/v1/status` | Gateway/device status |
| POST | `/api/v1/sms/schedule` | Schedule an SMS (`sendAt` epoch-ms or `delaySeconds`) |
| GET | `/api/v1/sms/schedule` | List scheduled sends |
| GET/DELETE | `/api/v1/sms/schedule/{id}` | Status / cancel a scheduled send |
| POST | `/send` | EVE-provider compatible enqueue |
| GET | `/send/status/{id}` | EVE job status |
| POST | `/send/cancel/{id}` | Cancel queued EVE job |
| GET | `/health` · `/ready` · `/send/capacity` | Health probes |

Persian digits in `phone`/`to` fields are normalized automatically.

### Examples

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

Full request/response samples: [docs/api/openapi.yaml](docs/api/openapi.yaml).

## 🪝 Incoming Webhooks

Incoming SMS are POSTed as JSON to your configured endpoints, signed with
HMAC-SHA256 (`X-Signature` header). Configure endpoints in
**SMS Gateway → Incoming SMS Webhook**. Blocked numbers never trigger webhooks.

## ☁️ Optional GMweb Server

The gateway can optionally connect to a **GMweb server** so external projects
reach the phone through a fixed HTTPS URL instead of the phone's changing LAN IP:

- Enter your server's address once in Settings → Gateway. The **panel address you
  already use is accepted** (e.g. `https://gmweb.example.com/app`); the server
  origin is derived from it.
- That ONE address carries everything: the control plane (enrollment, events,
  heartbeat, trust) *and* the SMS pull bridge. The app deliberately has **no
  server address built in**, so nothing is contacted until you configure one.
- The phone registers and then sends periodic heartbeats (battery, signal,
  queue depth) while **Gateway Service** is enabled.
- Projects queue messages on the server; your phone pulls and sends them.
- **Nothing is sent unless you enable the service** in Settings → Gateway.
- HTTPS only, with certificate and hostname validation that cannot be disabled.
- Alternatives: Cloudflare Tunnel, Tailscale, or any reverse proxy — see below.

## 🌐 Accessing the Gateway over the Internet

| Option | Best for | Notes |
|---|---|---|
| **GMweb server** (above) | A fixed public URL | You supply the server |
| **Cloudflare Tunnel** | Free stable domain | `cloudflared tunnel --url http://localhost:8080` |
| **Tailscale** | Private devices only | No exposed ports; mesh VPN |
| **Port forwarding + DDNS** | Fixed home WiFi | Router config required |
| **ADB reverse** | Emulator/testing | `adb reverse tcp:8080 tcp:8080` |

## 💻 Tech Stack

- Kotlin · Jetpack Compose (Material 3) · Navigation Compose
- MVVM + Single Source of Truth pattern (reactive repositories, StateFlow)
- WorkManager (scheduled sends) · Biometric API (app lock) · Coil (images)
- Tree-sitter-free 🙂 — pure Android Telephony providers

## 🧪 Development

```bash
git clone https://github.com/aibedini/Messages.git
cd Messages
./gradlew assembleDebug          # debug build
./gradlew testDebugUnitTest      # unit tests
```

Optionally override the cloud backend at build time:
`./gradlew assembleDebug -PGATEWAY_BACKEND_URL=https://your-relay.example.com`

## 📖 Documentation Wiki (`openwiki/`)

The repo carries an agent-maintained wiki: [openwiki/index.md](openwiki/index.md).

- **Entry point:** [openwiki/quickstart.md](openwiki/quickstart.md) — layout, build/test commands, task-routing map
- **Architecture** (data model, gateway service, send/receive pipelines, sync coordinator, UI, paging),
  **Integrations** (EVE REST, GMweb pull, cloud relay, webhooks), **Operations** (release, device setup),
  **Testing**, and **Workflow traces** — every claim cites the source file and is re-verified against code.
- **How it stays fresh:** `openwiki code --update` locally, or the daily
  [.github/workflows/openwiki-update.yml](.github/workflows/openwiki-update.yml) which opens a docs PR
  whenever code drifts from the wiki. Regenerate anytime: `openwiki code` (CLI is on npm).

## 🗺️ Roadmap

- [ ] Full Persian localization (strings extraction + RTL pass)
- [ ] Scheduled sends UI management screen
- [ ] Auto-classify conversations (personal / transactional / OTP)
- [ ] Messages-for-Web PWA on top of the embedded server

## 📄 License

MIT — see [LICENSE](LICENSE). Upstream: [manjitrana/Messages](https://github.com/manjitrana/Messages).
