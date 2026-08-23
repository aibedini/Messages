# Messages v1.3.0 — user-controlled messaging, delivery status, group MMS and shareable API docs

This release moves several hardcoded gateway behaviours into explicit user settings (Google Messages-style) and completes the messaging feature set.

## Highlights

- **Messaging settings screen** (Settings → Messaging) with every option OFF/unset by default:
  - Delivery reports toggle — SENT/DELIVERED broadcasts persist `Telephony.Sms.STATUS` and the chat bubble shows Delivered ✔✔ / Sending 🕗 / Failed ⚠.
  - SIM line picker — outgoing SMS honour the selected subscription via `createForSubscriptionId` (`READ_PHONE_STATE`/`READ_PHONE_NUMBERS` added).
  - Custom SMSC address — empty means network default.
  - iPhone reaction rendering — strict parser turns tapback texts (`Loved "…"`, `Liked an image`, …) into emoji; ordinary sentences are never misclassified (23 unit tests).
  - Group messaging toggle.
- **Real group messaging** — with the toggle on, multi-recipient sends go out as ONE group MMS (`MmsSender.sendGroupText`, proper group thread id) instead of N separate SMS; New Conversation gains a group mode with contact multi-select.
- **REST API additions** — `POST /api/v1/sms/send` accepts optional per-call `subscription_id` and `smsc` overrides with validation; falls back to the in-app Messaging preferences.
- **Shareable API documentation package** — `docs/api/openapi.yaml` (OpenAPI 3.0), interactive Swagger UI viewer (`docs/api/index.html`), quick reference (`docs/api/README.md`) and live smoke-test script (`scripts/test-gateway-api.ps1`).

The APK is signed with the project's existing release key. Play Protect may still review or block sideloaded SMS apps distributed outside Google Play; this release does not disable or bypass that security feature.
