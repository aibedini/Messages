# Messages v1.5.0 — EVE-compatible SMS provider

This release turns the embedded gateway into a drop-in **EVE panel "Custom HTTP" SMS provider**, with a persistent priority queue, idempotent sends and full status tracking.

## Highlights

- **EVE provider endpoints** served by the same local server (same API key):
  - `GET /health` (no auth) and `GET /ready` (auth; 503 when not ready)
  - `POST /send` with `Idempotency-Key`, `{to, text, priority}`
  - `GET /send/status/{requestId}` — `queued → active → sent | failed` with `terminal/successful` flags and ISO-8601 `sentAt`
  - `POST /send/cancel/{requestId}` — cancels messages still queued
  - `GET /send/capacity` — per-priority pending counts + announcement bucket
- **Priority queue**: critical=1, expired=3, expiring=6, announcement=10; higher priority dispatches first.
- **Idempotency**: duplicate `Idempotency-Key` headers return the original response without creating a second message.
- **Durability**: queue state is persisted; queued/active requests survive app or device restarts (active ones are re-queued).
- **Capacity guard**: HTTP 429 with `Retry-After: 30` once 500 pending messages are reached.
- **Docs & tests**: EVE endpoints documented in `docs/api/openapi.yaml` + README setup guide for the EVE panel; the queue core is covered by JVM unit tests (priority order, idempotency replay, cancel semantics, status flow, capacity).

### Connecting EVE

In the EVE panel → SMS settings: Provider = `Custom HTTP`, Base URL = `http://<device-ip>:8080` (or tunnel URL), API Key = gateway key, timeout = 15s. Then Test Connection → Send Test SMS → enable SMS Automation. Full guide: [`docs/api/README.md`](docs/api/README.md#eve-integration-custom-http-provider).

The APK is signed with the project's existing release key. Play Protect may still review or block sideloaded SMS apps distributed outside Google Play; this release does not disable or bypass that security feature.
