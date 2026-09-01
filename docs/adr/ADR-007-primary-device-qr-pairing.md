# ADR-007 — Primary-Device QR Pairing (Linked-Device Trust Model)

**Status:** Accepted (authoritative over any earlier passkey-first first-run
flow in the TechSpec or current Web implementation)
**Date:** 2026-09-01
**Repos:** aibedini/Messages (Trust Root, primary approver) · aibedini/GMweb-API (pairing session relay + PWA)

## Context

The current Web first-run says "Create a passkey on this device" — that makes
the passkey a **bootstrap of trust**, which is architecturally wrong:

```text
Passkey Authentication  ≠  Trusted Device  ≠  Message Decryption Capability
```

(ADR-005 gates stay; what changes is HOW a device becomes Trusted.) The model
is WhatsApp-Web-style Linked-Device pairing:

```text
Web/PWA shows QR → Android Messages → Settings → Linked devices
→ Link new device → scan → verify details → biometric/device-credential
→ Approve → Web becomes Trusted Device → sync starts
```

A user must NOT need to create an account password, passkey, TOTP, or
recovery codes before linking their phone.

## Decision

### 1. Android Agent is the Primary Trust Device (v1)

With no valid web session, the PWA shows ONLY:

```text
Messages

Link this browser to your phone

[ QR CODE ]

Open Messages on your Android phone
Settings → Linked devices → Link new device

QR expires in 01:24
Can't scan? Use pairing code
```

First-run state machine (replaces `NO_PASSKEY → BLOCKED`):

```text
UNLINKED → SHOW_QR → PAIRING_PENDING → PAIRING_APPROVED
        → BOOTSTRAPPING_KEYS → SYNCING → READY
```

Returning browser: valid certificate + session + local keys → straight in;
expired session with intact trust → trusted-device reauthentication (NO
re-pairing). Browser storage cleared / key material gone → "This browser
needs to be linked again." → full re-pairing. The server must NEVER rebuild
a device identity from a cookie alone.

### 2. QR is a short-lived pairing TRANSCRIPT, not a bearer token

Web generates locally BEFORE showing the QR:

```text
webDeviceId · ephemeral pairing keypair ·
operational signing public key · operational encryption public key · nonce
```

Server adds `pairingSessionId` + `expiresAt`. QR payload conceptually:

```text
version · pairingSessionId · webDeviceId ·
webSigningPublicKey · webEncryptionPublicKey · ephemeralPublicKey ·
nonce · origin · expiresAt
```

Properties: **single-use · short-lived (TTL ≈ 90–120s) · origin-bound ·
accountless until Android approves**. A screenshot of the QR alone grants
nothing.

### 3. Android pairing UX (no silent approval)

`Settings → Linked devices` lists linked devices (Chrome · Windows — Active
now / Safari · iPhone — Last active 2h ago) with `[ Link new device ]` and
`[ Unlink ]` per device.

Link flow, in order:

1. **BiometricPrompt / device credential FIRST** (user presence on Android).
2. QR scanner opens.
3. QR parsed + validated locally (single-use, unexpired, origin matches).
4. Pairing session metadata fetched from GMweb.
5. Confirmation screen — exact details, no surprises:

```text
Link new device?

Safari · iPhone · messages.example.com

Requested access:  ✓ Read  ✓ Send  ✓ Notifications
History:           ● Full   ○ From now on
Sensitive:         ✓ OTP stays on this phone
                   ✓ Bank security codes stay on this phone

[Cancel]    [Link device]
```

Sensitive policy shown here is Android-owned; a Web policy-change request is
a `REQUEST_POLICY_CHANGE` command requiring Android approval.

### 4. Android signs the DeviceCertificate (GMweb never trusts a browser alone)

On approval the Android Trust Root signs:

```text
accountId · deviceId · deviceType = WEB_PWA ·
signingPublicKey · encryptionPublicKey · capabilities · historyGrant ·
trustSequence · issuedAt · expires/version ·
pairingTranscriptHash · rootSignature
```

The transcript hash binds the certificate to THIS pairing exchange (the QR
contents). GMweb relays/stores it — **GMweb alone has no authority to make a
browser trusted.**

### 5. Web completion — verify, then bootstrap

Web follows `GET/SSE pairing status`; on approval it must verify
`rootSignature`, `pairingTranscriptHash`, its own public keys, deviceId, and
origin binding. A bare `{"approved":true}` is insufficient. After
verification: authenticated linked-device session → sync cursor bootstrap →
key grants → conversation sync → SSE → Web Push registration.

### 6. Pairing-code fallback

Camera-broken/remote path: short numeric code (e.g. `4829 1842`, sufficient
entropy), short-lived, single-use, rate-limited, Android-confirmed — entered
at `Linked devices → Link with code`. The code alone creates NO trust; the
final approval is still on Android.

### 7. Passkey role — corrected, not removed

Passkeys become **optional post-pairing hardening**:

```text
Device linked ✓ → "Secure this browser — use a passkey to unlock faster"
[Enable passkey]  [Not now]
```

Passkey uses: local unlock, step-up auth, Security Center, device-management
confirmation, sensitive export, recovery. **Never initial device trust.**
Passkey failure is non-fatal and never blocks first-run
(`The operation either timed out…` must not break onboarding). Existing
passkey implementation is preserved behind `Security Center → Passkeys`.

### 8. Remote revoke

Android `Linked devices → [Unlink]`: signed `DEVICE_REVOKED` → GMweb kills
session → push revoke → key grants stop → Web kicked out. (Extends ADR-001's
two-phase revocation to web devices.)

### 9. Security invariant (v1)

> Possession of a GMweb account/session is INSUFFICIENT to access messages.

Reading messages requires additionally: an Android-approved Trusted Device +
its device private keys + valid key grants. Credential theft of GMweb alone
cannot reach the inbox.

## Implementation timing

* This ADR is authoritative NOW; the current first-run Passkey screen and its
  copy are to be REMOVED from the PWA (passkey code retained behind Security
  Center).
* GMweb: pairing-session API (create/status SSE/approve) + web state machine
  — versioned per the API-contract rule (package bump + openapi + INTEGRATION).
* Android: `Linked devices` UI + QR/-code scanner + biometric confirm +
  DeviceCertificate signing (builds on PR-05/08b identity machinery).
* Web/PWA message-history sync stays OFF until BOTH this pairing flow ships
  AND ADR-006's firewall is device-validated.
