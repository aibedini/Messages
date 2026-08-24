# Release v2.0.3 — GMweb pull bridge (no tunnel needed)

## Added

- **GMweb pull bridge**: the gateway can now work as a delivery device for a
  [GMweb-API](https://github.com/aibedini/GMweb-API) server without any tunnel,
  port-forward, or static IP. The phone dials OUT to the server over HTTPS and
  long-polls `GET /gateway/pull`; each task is delivered through the existing
  EveSmsQueue (priority, persistence, native SIM send) and the outcome is
  reported back with `POST /gateway/ack`.
- New "GMweb pull bridge" card in the Gateway screen: paste the server's
  `https://` URL, Save to enable, Disable to turn off. HTTP URLs are rejected
  so the API key never travels in plaintext.
- `OutboxPoller`: bounded coroutine loop with 5s error backoff; long-poll hold
  is 25s so an idle phone makes ~2 requests/minute. A lost ack never re-sends
  locally — the server side owns retry semantics.

## Notes

- The bridge is independent of the cloud backend (heartbeat/registration keep
  working separately) and requires gateway consent + enabled gateway, like all
  other transmission features.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.0.2...v2.0.3
