# 📱 Messages & Android SMS/MMS Gateway

[Privacy Policy](PRIVACY.md)

[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF.svg?style=flat&logo=kotlin)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20%28API%2026%2B%20--%2036%29-3DDC84.svg?style=flat&logo=android)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg)]()

**Messages** is a modern, high-performance Android Default SMS & MMS Messaging Application featuring an embedded **HTTP REST API Server** and **Real-Time Webhook Engine**. Transform any Android phone into an automated, self-hosted SMS/MMS Gateway without third-party cloud service fees!

---

## 🌟 Key Features

- 💬 **Full Messaging Suite**: Default SMS & MMS application with real-time updates, contact auto-sync, OTP copying, notification actions, and media previews.
- 🌐 **Embedded Local REST API Gateway**: Native `ServerSocket` HTTP server running as an Android Foreground Service on port `8080`.
- 🔑 **API Key Security**: Endpoints protected with configurable `X-API-Key` authentication.
- ⚡ **Real-Time Webhooks**: Automatically dispatches incoming SMS notifications as HTTP `POST` JSON payloads to your custom webhook endpoints.
- 🖼️ **MMS Image Sending**: API endpoints to send images and media attachments directly via cellular network.
- 📊 **Real-Time Terminal Console**: Built-in visual request logger displaying HTTP status codes and live network activity.
- ⚡ **Butter-Smooth Performance**: Thread-safe in-memory contact caching ensuring zero Main-UI thread lag while scrolling.

---

## 🚀 Quick Start & Installation

### Requirements
- Android Device or AVD Emulator running **Android 8.0+ (API Level 26 or higher)**.
- Active SIM card with an SMS/MMS mobile plan.

### Building from Source

```bash
# Clone the repository
git clone https://github.com/your-username/Messages.git
cd Messages

# Build Debug APK
./gradlew assembleDebug

# Build Production Release APK (Optimized & Signed)
./gradlew assembleRelease
```

The compiled APK will be located at:
`app/build/outputs/apk/release/app-release.apk`

---

## 🔐 Authentication

All API requests require the `X-API-Key` HTTP header:

```http
X-API-Key: your_generated_api_key
```

You can view or regenerate your API Key inside the app under **3-Dots Menu → SMS Gateway**.

---

## 📡 REST API Reference & Usage Examples

> 📚 **Full API documentation package** (for sharing with other projects): [`docs/api/`](docs/api/)
> - [`openapi.yaml`](docs/api/openapi.yaml) — OpenAPI 3.0 spec (import into Swagger UI / Postman / Insomnia)
> - [`index.html`](docs/api/index.html) — interactive Swagger UI docs (serve the folder, open in browser)
> - [`scripts/test-gateway-api.ps1`](scripts/test-gateway-api.ps1) — live smoke tests for every endpoint

### 1. Send SMS Text Message
**Endpoint**: `POST /api/v1/sms/send`

#### Request (cURL)
```bash
curl -X POST http://<PHONE_IP>:8080/api/v1/sms/send \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your_generated_api_key" \
  -d '{
    "phone": "+919876543210",
    "message": "Hello! Your OTP verification code is 482910."
  }'
```

#### Response (200 OK)
```json
{
  "status": "success",
  "id": 1614,
  "phone": "+919876543210",
  "message": "Hello! Your OTP verification code is 482910."
}
```

---

### 2. Send MMS Image Message
**Endpoint**: `POST /api/v1/mms/send`

#### Request (cURL)
```bash
curl -X POST http://<PHONE_IP>:8080/api/v1/mms/send \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your_generated_api_key" \
  -d '{
    "phone": "+919876543210",
    "imageUrl": "https://picsum.photos/300/300",
    "caption": "Check out this image!"
  }'
```

#### Response (200 OK)
```json
{
  "status": "success",
  "phone": "+919876543210"
}
```

> `imageUrl` accepts `https://` URLs (downloaded by the gateway, max 10 MB) or
> `content://` URIs on the device. Plain `http://`, `file://`, and custom schemes are rejected.

---

### 3. Fetch Recent Inbox Messages
**Endpoint**: `GET /api/v1/sms/inbox`

#### Request (cURL)
```bash
curl -H "X-API-Key: your_generated_api_key" http://<PHONE_IP>:8080/api/v1/sms/inbox
```

#### Response (200 OK)
```json
{
  "status": "success",
  "count": 2,
  "messages": [
    {
      "id": 1597,
      "sender": "AX-CANBNK-S",
      "message": "Dear Customer, Acct XXX972 credited with INR 50.00",
      "date": 1785722998962,
      "type": "received"
    },
    {
      "id": 1596,
      "sender": "+919876543210",
      "message": "Hello from Gateway API!",
      "date": 1785719332439,
      "type": "sent"
    }
  ]
}
```

---

### 4. Check Gateway & Device Status
**Endpoint**: `GET /api/v1/status`

#### Request (cURL)
```bash
curl -H "X-API-Key: your_generated_api_key" http://<PHONE_IP>:8080/api/v1/status
```

#### Response (200 OK)
```json
{
  "status": "online",
  "version": "1.0",
  "ip": "192.168.1.4",
  "port": 8080,
  "batteryLevel": 100,
  "isDefaultSmsApp": true,
  "timestamp": 1785764142174
}
```

---

## ⚡ Real-Time Incoming SMS Webhooks

When an incoming SMS is received on the device, the app dispatches an HTTP `POST` JSON payload to your configured Webhook URL.

### Webhook JSON Payload
```json
{
  "event": "sms_received",
  "sender": "+919876543210",
  "message": "Your verification code is 834750",
  "timestamp": 1785764000000,
  "threadId": 5
}
```

### Setting Up Webhooks
1. Open the app → **SMS Gateway**.
2. Paste your endpoint URL under **Incoming SMS Webhook** (e.g. `https://sms.autonomousone.in/api/v1/webhooks/sms-received` or `https://webhook.site/xxx`). Only `https://` URLs are accepted.
3. *(Optional but recommended)* Enter a **signing secret** and tap **Save Secret**.
4. Tap **Save Webhook**.

### Verifying Webhook Signatures (HMAC-SHA256)
When a signing secret is configured, every payload is signed and sent with two extra headers:

```
X-Timestamp: 1785764000000
X-Signature: hex(hmac_sha256(secret, "<timestamp>.<raw-body>"))
```

Verify on your server by recomputing the HMAC over `"$X-Timestamp" + "." + rawBody`
and comparing with a constant-time equality check. Reject requests older than a few
minutes to prevent replay attacks.

---

## 🌐 Accessing Gateway over the Internet / Custom Domain

### Option A: Testing on Emulator (ADB Port Forwarding)
```bash
adb forward tcp:8080 tcp:8080
curl -H "X-API-Key: your_generated_api_key" http://localhost:8080/api/v1/status
```

### Option B: Cloudflare Tunnel (Free Public HTTPS Domain)
```bash
# Forward local port 8080 to a secure HTTPS URL or custom domain
cloudflared tunnel --url http://localhost:8080
```

### Option C: Custom Domain Proxy (`sms.autonomousone.in`)
Connect your Android Gateway device via WebSockets or Nginx Reverse Proxy to route external traffic securely from your custom domain (`https://sms.autonomousone.in`).

---

## 🔒 Gateway Security Hardening (v1.2.0)

- **Encrypted secrets at rest** — the local API key, cloud bearer token, webhook signing secret, and registration secret are encrypted with a hardware-backed Android Keystore key (AES-256-GCM) before being persisted. Legacy plaintext values are migrated automatically on first read.
- **LAN-scoped binding** — by default the REST server binds only to the detected Wi-Fi/LAN IPv4 address instead of `0.0.0.0`. Toggle **Bind to all interfaces** in the Server card if you specifically need exposure on every interface. For public access always use an HTTPS tunnel (Cloudflare Tunnel / Nginx) — the API itself is plain HTTP.
- **Brute-force protection** — 8 failed API-key attempts per IP within 10 minutes triggers a 5-minute lockout; failure records are purged periodically so the table cannot grow unbounded. The API key is generated with 128 bits of `SecureRandom` entropy.
- **Request limits** — bodies over 1 MB are rejected with `413`, headers are capped at 100, and remote MMS image downloads are capped at 10 MB.
- **Registration pairing secret** — set a *Registration secret* in the Cloud card; it is sent as the `X-Registration-Secret` header on `POST /api/gateways/register`. **Your backend must require and verify this header** — otherwise anyone who knows/guesses a device ID can register over it and invalidate its token.
- **Signed webhooks** — see [Verifying Webhook Signatures](#verifying-webhook-signatures-hmac-sha256).
- **HTTPS-only outbound** — backend URL, webhook URLs, and remote `imageUrl` values must use `https://`; plaintext targets are rejected instead of failing silently under Android's cleartext policy.
- **Lock-screen privacy** — incoming-SMS notifications (including OTPs) use `VISIBILITY_PRIVATE`; content is hidden on the lock screen.

---

## 🛡️ Android Security & Permissions

Starting with **Android 13/14**, side-loaded APKs with SMS permissions require granting **Restricted Settings**:
1. Open phone **Settings → Apps → Messages**.
2. Tap the **3-dots menu** (top right) → **Allow restricted settings**.
3. Grant **SMS**, **Contacts**, and **Phone** permissions.

---

## 🔐 Signing the APK (Keystore Security)

The release keystore must **never** be committed to the repository or shared publicly —
anyone with the keystore + password can publish malicious updates as this app.

Generate a keystore **locally** with a strong random password (do not reuse any previous password):

```bash
keytool -genkey -v \
  -keystore release-key.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias sms-key \
  -storepass "$(openssl rand -hex 16)" \
  -keypass "$(openssl rand -hex 16)" \
  -dname "CN=SMS Center, OU=Dev, O=YourName, L=City, ST=State, C=IR"
```

Then add the credentials to **GitHub Secrets** (never to source files):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release-key.jks` |
| `KEYSTORE_PASSWORD` | your store password |
| `KEY_ALIAS` | `sms-key` |
| `KEY_PASSWORD` | your key password |

Local release builds read the same values from environment variables:
`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

Keep `release-key.jks` out of the repo — it is already listed in `.gitignore`.

---

## 💻 Code Examples

### Python (Send SMS)
```python
import requests

url = "http://192.168.1.4:8080/api/v1/sms/send"
headers = {"X-API-Key": "your_generated_api_key", "Content-Type": "application/json"}
payload = {
    "phone": "+919876543210",
    "message": "Hello from Python Script!"
}

response = requests.post(url, json=payload, headers=headers)
print(response.json())
```

### Node.js (Send SMS)
```javascript
const axios = require('axios');

async function sendSms() {
    const response = await axios.post('http://192.168.1.4:8080/api/v1/sms/send', {
        phone: '+919876543210',
        message: 'Hello from Node.js!'
    }, {
        headers: {
            'X-API-Key': 'your_generated_api_key',
            'Content-Type': 'application/json'
        }
    });
    console.log(response.data);
}

sendSms();
```

---

## 📜 License

Distributed under the **MIT License**. See `LICENSE` for more details.
