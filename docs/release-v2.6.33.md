# Release v2.6.33 — ADR-006 Amendment: per-device Sensitive Message Grants

**versionCode 75 · no schema change (v7)**

## Sensitive access is now user-selectable per linked device

The pairing confirmation screen replaces the hardcoded "OTP stays on this
phone" text with **five explicit grant switches** (privacy-first: all OFF):

* OTP & login codes
* Bank security codes
* Password reset codes
* Authentication / 2FA codes
* Bank transaction notifications

Each enabled switch becomes a **signed capability** in the DeviceCertificate
(`READ_OTP`, `READ_BANK_SECURITY`, `READ_PASSWORD_RESET`, `READ_AUTH_CODES`,
`READ_FINANCIAL_NOTIFICATIONS`) — GMweb can never add capabilities, and the
web device can never change them silently.

## Policy model

`LOCAL_ONLY` (no grant — nothing leaves Android) /
`SELECTED_DEVICES` (grants stored per linked device in
`SensitiveGrantStore`) / `ALL_TRUSTED_DEVICES`.

`SensitiveGrantStore.savePairingGrants()` persists the user's choices at
approve time; future changes flow through biometric-confirmed
`DEVICE_CAPABILITIES_CHANGED` (Trust Root signature, trustSequence++).

Critical invariant (unchanged): a device without the category grant never
receives the sensitive DEK grant — ciphertext may exist but decryption is
cryptographically blocked. LOCAL_ONLY still means no outbox row, no
ciphertext, no metadata event.

## Verification

* `assembleDebug` + `testDebugUnitTest` → **229/229**.
* GMweb companion release: v0.13.3 raw-body contract fix deployed
  (`ba2cbba`), 141/141.

## versionCode

Bumped to **75** (`2.6.33`).
