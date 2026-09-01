# Release v2.6.26 — per-message ASK prompt (ADR-006 §11)

**versionCode 68 · no schema change (v7)**

## What ships

The "Ask every time" policy for regular bank notifications is now a real
per-message prompt instead of a silent keep-local:

* **AskPrompt** notification (content-free by contract): "Sync financial
  notification from <name>?" with two actions — *Sync once* / *Keep
  private*. NO body, NO OTP, NO message text ever renders in the prompt
  (VISIBILITY_SECRET, own channel).
* **AskPolicyLedger**: durable per-message verdicts keyed by
  `source:providerId` — no content keys. Fail-closed semantics:
  unanswered/swiped = keep local forever; only an explicit "Sync once"
  flips that exact message; "Keep private" is durable and idempotent.
* **Gate integration**: `enqueueCloudEvent` re-consults the ledger when the
  financial policy is ASK — an explicit grant lets THAT message sync, an
  unresolved one logs `policy=ASK_PENDING` and returns (still no outbox
  row).
* `AskActionReceiver` (non-exported) handles the two notification actions.
* FA strings added (فقط یک‌بار sync / خصوصی بماند).

## Invariants kept

* LOCAL_ONLY still means zero outbox rows (pinned in SyncEligibilityTest).
* OTP/bank-code/password-reset categories remain unconditionally local —
  the ASK prompt only ever appears for FINANCIAL_NOTIFICATION.
* §16 fail-closed on every default path.

## Verification

* `assembleDebug` + `testDebugUnitTest` — green, **221/221** (3 new ledger
  verdict tests).

## versionCode

Bumped to **68** (`2.6.26`).
