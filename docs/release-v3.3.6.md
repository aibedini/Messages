# Messages v3.3.6 — conversation identity and contact actions

**versionCode 106 · Room schema v15 · Compose/Material 3**

This patch release fixes incoming-message identity, external compose routing,
and conversation participant actions.

## One bubble per incoming SMS

Non-default-app `SMS_RECEIVED` broadcasts no longer create a synthetic Room
message before the Android SMS provider exposes its authoritative row. The
provider observer ingests that row once, while the default-app `SMS_DELIVER`
path publishes only after reading back a real provider id. Live and Room rows
now converge on the same `(source, providerId)` identity.

Persisted messages are no longer collapsed merely because their body and time
are similar. Two genuinely distinct provider rows therefore remain two
messages, including SMS and MMS rows whose numeric ids happen to match.

## Dialer and Call Log compose routing

Android `sms:`, `smsto:`, `mms:`, and `mmsto:` compose intents now resolve an
existing Room conversation first, including safe Iranian international/local
number equivalence. Unknown numbers open a new addressed conversation. Any
supplied body is populated as a draft and is never sent automatically.

## Conversation participant actions

The conversation header and overflow menu now expose real participant actions:
copy and call a usable number, add an unknown number through the system Contacts
UI, or view a known contact. Returning from Contacts clears the contact cache
and refreshes the displayed participant.

The inactive in-chat Search and Clear items and the fake video-call button were
removed for this bug-fix release rather than shipping clickable no-ops.

## Verification

- Regression coverage verifies live/Room identity convergence and preserves
  distinct same-body messages and cross-source ids.
- Resolver coverage verifies existing, unknown, international/local, short-code,
  and draft-preservation behavior.
- Participant action coverage verifies known, unknown, empty, and group states.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v3.3.5...v3.3.6
