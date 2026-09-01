# Release v2.6.24 — SensitiveMessageFirewall (ADR-006 implementation)

**versionCode 66 · no Room schema change (stays v7)**

## What ships

The ADR-006 security boundary, now live: sensitive SMS (OTP, dynamic bank
passwords, verification/password-reset codes) are classified **on-device
BEFORE any cloud event exists** — and LOCAL_ONLY messages never reach
`GatewayEventOutbox`, GMweb, WebhookEngine, push, or logs. Not "deleted
later": never constructed.

### SensitiveMessageFirewall (new, `security/`)

Deterministic v1 detector stack (no cloud, no ML, no network):

* sender rules + keyword rules (Persian/English/Arabic) + 4–8-digit
  code-pattern analysis — context + code must co-occur; a bare number is
  never sufficient (invoice numbers stay NORMAL)
* `DigitNormalizer` canonicalization BEFORE matching (۰۱۲۳۴۵۶۷۸۹ /
  ٠١٢٣٤٥٦٧٨٩ / 0123456789)
* categories: OTP_SECURITY_CODE · BANK_SECURITY_CODE · PASSWORD_RESET_CODE ·
  AUTHENTICATION_CODE · FINANCIAL_NOTIFICATION · NORMAL
* ADR-006 §15 disambiguation: «رمز پویای شما …» → BANK_SECURITY_CODE (local),
  «واریز شد» → FINANCIAL_NOTIFICATION (user policy)
* per-sender overrides: phone AND alphanumeric senders; sync allowlist can
  never bypass an OTP/bank-code verdict; ambiguity fail-safe default =
  privacy strict

### Gate integration (TelephonySyncCoordinator)

`enqueueCloudEvent` remains the single choke point; it now classifies and,
for LOCAL_ONLY, logs only category/rule (never content) and returns — the
event row is never built. MarkThreadRead is gated on the thread's latest
message so a sensitive thread leaves no read-receipt trace either.

### Settings UI (ADR-006 §11)

Messaging settings → "Sensitive messages": the two always-on security
invariants (OTP/bank codes), regular-bank-notifications policy
(Ask/Sync/Keep local), and both per-sender list editors
("Always keep these senders on device" / "Always allow syncing").

### Not in this release (by ADR timing)

Crypto/E2EE (stays out per ADR-005/006 timing), PWA history sync (blocked
until this firewall was validated), Ask-mode interstitials.

### Follow-up (v2.6.25)

ASK policy resolved FAIL-CLOSED: a FINANCIAL_NOTIFICATION under ASK stays
LOCAL_ONLY until the user actively allows it (per-message prompt ships
later). ADR-006 §16 — an unanswered prompt never fails open.

## Verification

* `assembleDebug` + `testDebugUnitTest` — green, **217/217** (17 new
  classifier tests covering the full ADR-006 §22 matrix).
* Boundary invariant pinned in `SyncEligibilityTest`: LOCAL_ONLY ⇒ no outbox
  row ever constructed.

## versionCode

Bumped to **66** (`2.6.24`).
