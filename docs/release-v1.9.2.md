# Release v1.9.2 — GMweb-compatible verification fields in EVE status

## Added

- **EVE status contract parity with GMweb**: `GET /send/status/:id` now returns
  `verificationStatus`, `verificationAttempts`, `recipientEvidence`, and
  `conversationUrl`. The Eve panel poller (`panel/jobs/messaging.py`) parses
  these uniformly across both providers, so the Android gateway can sit behind
  GMweb's new `android-gateway` transport without special-casing.
  - `verificationStatus` = `confirmed` on successful native SIM send,
    `manual_review_required` when the radio reports failure (native sends are
    confirmed by the radio — there is no separate DOM verification pass).
  - `recipientEvidence` / `conversationUrl` are explicit `null`: a native SIM
    send has no Google Messages web conversation to point at.
- New fields persist through the SharedPrefs queue store and survive restarts.

## Verification fields round-trip

- Unit test `terminal records carry gmweb-compatible verification fields`
  covers sent → `confirmed`, failed → `manual_review_required`, and the
  persistence round-trip.

## Integration context

This release pairs with GMweb-API v0.3.35 (`ANDROID_GATEWAY_MODE=1`) which
relays its queue to this app's EVE Custom HTTP endpoints over a tunnel.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v1.9.1...v1.9.2
