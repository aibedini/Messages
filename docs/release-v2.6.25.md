# Release v2.6.25 — firewall fail-closed hardening (ADR-006 §16)

**versionCode 67 · no schema change (v7)**

## Change

ASK policy for FINANCIAL_NOTIFICATION now resolves **fail-closed**: a
financial notification under "Ask" stays LOCAL_ONLY until the user actively
allows it. Previously ASK behaved as SYNC (fail-open) — a policy the user
could not yet answer must never open the cloud door by default
(ADR-006 §16, privacy strict posture).

Per-message "Ask" interstitial UI ships separately; until then ASK = keep
local, which is the safe direction on every failure mode.

## Verification

* `assembleDebug` + `testDebugUnitTest` — green, **218/218**
  (new: `ask policy fails closed for financial notifications`).

## versionCode

Bumped to **67** (`2.6.25`).
