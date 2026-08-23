# SMS Gateway — API Documentation Package

Standard, shareable documentation for the REST API exposed by the **Messages**
Android app (`com.autonomousone.messages`). Hand this folder to any other
project/team that needs to talk to the gateway.

## What's in here

| File | Purpose |
|------|---------|
| `openapi.yaml` | **OpenAPI 3.0.3 spec** — the machine-readable source of truth. Import into Swagger UI, Postman, Insomnia, Redoc, or generate client SDKs from it. |
| `index.html` | **Interactive docs** — self-contained Swagger UI viewer. Serve the folder and open it in a browser; "Try it out" works against a real device. |
| `README.md` | This quick reference (endpoints, auth, errors, webhook contract, tests). |

## Quick view

```bash
# serve docs locally, then open http://localhost:8000/docs/api/
python -m http.server 8000

# open with your key pre-filled for Try-it-out:
#   http://localhost:8000/docs/api/index.html?key=gw_yourkey
```

---

## Endpoint summary

Base URL: `http://<device-lan-ip>:8080`

| # | Method | Path | Description |
|---|--------|------|-------------|
| 1 | POST | `/api/v1/sms/send` | Send an SMS text message |
| 2 | POST | `/api/v1/mms/send` | Send an MMS image (https:// or content:// image) |
| 3 | GET | `/api/v1/sms/inbox` | Latest 50 messages |
| 4 | GET | `/api/v1/sms` | Query messages: `limit`, `offset`, `type`, `phone`\|`from`, `from_date`, `to_date` |
| 5 | GET | `/api/v1/status` | Gateway health, IP/port, battery, default-SMS-app flag |

### Authentication (all endpoints)

```
X-API-Key: gw_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
# or
Authorization: Bearer gw_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### Error codes (global)

| Status | Meaning |
|--------|---------|
| 400 | Missing/blank required fields |
| 401 | Invalid API key (counts toward lockout) |
| 404 | Unknown path |
| 413 | Body > 1 MB |
| 429 | IP locked out — 8 failed auths within 10 min → 5 min lockout |
| 500 | Internal error (details never leaked to clients) |

### Outgoing webhook (incoming SMS → your server)

On every received SMS the app POSTs to your configured **HTTPS** URL:

```json
{ "event": "sms_received", "sender": "+98...", "message": "...",
  "timestamp": 1755900000000, "threadId": 12 }
```

With a secret configured, verify authenticity:

```
X-Timestamp = <unix-millis-string>
X-Signature = hex( HMAC_SHA256(secret, "<timestamp>.<raw-body>") )
```

### Cloud backend contract (app → backend)

Only relevant if the consuming project *is* the cloud backend:

- `POST {backend}/api/gateways/register` — no bearer token; optional `X-Registration-Secret`
- `POST {backend}/api/gateways/heartbeat` — Bearer token
- `POST {backend}/api/gateways/events/sms` — Bearer token; `eventId` is a stable UUID for idempotency

All require HTTPS; plaintext URLs are rejected by the client.

---

## EVE integration (Custom HTTP provider)

This gateway implements the **EVE panel "Custom HTTP" SMS provider** contract
out of the box. In the EVE SMS settings choose:

| Setting | Value |
|---|---|
| Provider | `Custom HTTP` |
| Base URL | `http://<device-ip>:8080` (or your tunnel/domain URL) |
| API Key | the same `gw_…` key from the Gateway screen |
| Timeout | `15` |

Then run **Test Connection** → **Send Test SMS** → enable SMS Automation.

### Endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/health` | none | 200 when the service is up |
| GET | `/ready` | Bearer | 200 ready · 401 bad key · 503 not default-SMS-app |
| POST | `/send` | Bearer + `Idempotency-Key` | body `{to, text, priority}`; replay-safe |
| GET | `/send/status/{requestId}` | Bearer | `queued→active→sent/failed`, `terminal/successful` flags |
| POST | `/send/cancel/{requestId}` | Bearer | cancels while still queued |
| GET | `/send/capacity` | Bearer | pending counts + announcement bucket |

Priority → level mapping: `critical`=1, `expired`=3, `expiring`=6, `announcement`=10.
Duplicate Idempotency Keys never create duplicate messages. Queue state survives
app restarts (persisted). Capacity guard returns `429` with `Retry-After: 30`
once 500 pending messages are reached.

Example:

```bash
curl -X POST http://192.168.1.20:8080/send \
  -H "Authorization: Bearer gw_..." \
  -H "Idempotency-Key: eve-2026-08-23-001" \
  -H "Content-Type: application/json" \
  -d '{"to":"09123456789","text":"Your subscription expires soon","priority":"expiring"}'
```

---

## Tests

Two layers:

1. **Unit tests** (in-repo, JVM): policy logic covered by
   `app/src/test/.../GatewayAccessPolicyTest.kt` and friends — run with
   `./gradlew test`.
2. **Live smoke tests** (against a running device):
   [`scripts/test-gateway-api.ps1`](../../scripts/test-gateway-api.ps1)

   ```powershell
   # phone must be reachable via adb forward tcp:8080 tcp:8080, or on LAN
   .\scripts\test-gateway-api.ps1 -Host 127.0.0.1 -Port 8080 -ApiKey "gw_..."
   ```

   Covers: status probe, auth rejection (401), send SMS, inbox, filtered query,
   MMS validation (bad scheme → 400), unknown route (404).

## Versioning

- URL-versioned (`/api/v1/…`) — breaking changes ship under `/api/v2/…`.
- Keep `openapi.yaml` updated in the same PR as any endpoint change.
