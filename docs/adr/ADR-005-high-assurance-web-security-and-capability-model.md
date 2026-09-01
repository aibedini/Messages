# ADR-005 — High-Assurance Web/PWA Security: Server-Compromise Resistance & Capability Model

**Status:** Accepted (security boundary — authoritative over convenience and
over the earlier "HTTPS + Login is enough" implicit posture)
**Date:** 2026-09-01
**Repos:** aibedini/GMweb-API (control plane + PWA) · aibedini/Messages (data plane)

## Context

The RFP's security-first posture is not met by TLS + Passkey login alone. Two
distinct threats exist:

1. **Database/API compromise** — an attacker who steals the GMweb database or
   owns the API process must not obtain readable SMS history.
2. **Frontend supply-chain / server compromise** — an attacker who can serve
   malicious JavaScript from the official PWA origin can harvest plaintext
   while the user's session is open. A PWA cannot eliminate this threat the
   way a signed native binary can; defense-in-depth is therefore mandatory.

The security model is explicitly three independent gates:

```text
Passkey Authentication  ≠  Trusted Device  ≠  Message Decryption Capability
```

A client may read a message only with ALL THREE:

```text
authenticated session
+ valid signed DeviceCertificate
+ valid cryptographic key grants
```

## Decision

### 1. No plaintext message storage in GMweb

GMweb must NEVER persist, in plaintext, any of:

```text
SMS body, MMS text, contact name, phone number, conversation title,
message snippet, OTP, bank security message, attachment content,
SEND_SMS command body
```

The Messaging Core stores only:

```text
opaque IDs, ciphertext, encrypted key envelopes,
minimal routing metadata, operationally required timestamps, status evidence
```

(The existing opaque-payload rule of PR-09/ADR-002 is confirmed and extended
to contacts/numbers/titles/snippets — routing metadata must be opaque or
hashed, not raw.)

### 2. Frontend defense-in-depth (mandatory baseline)

The PWA static artifact is an independent, security-sensitive deliverable:

```text
PWA static artifact independent of the API service
no runtime third-party JS · no CDN JavaScript · no analytics
no session replay · HeroUI bundled locally · all fonts self-hosted
strict CSP · Trusted Types · no unsafe-eval
no unsafe-inline where avoidable · dependency lock · SAST/SCA · SBOM
immutable hashed frontend assets
signed / provenance-tracked production builds
2-person approval for production web deployment
security audit trail for every frontend production release
```

Frontend production deployment is classified as a **security-sensitive
operation** with its own release/audit trail.

### 3. Web/API deployment separation

Same repo, separate artifacts:

```text
GMweb repo
   ├── API artifact      → api.messages.example.com (or equivalent)
   └── Web/PWA artifact  → messages.example.com (static, immutable)
```

The PWA must never be produced by dynamic server-side templating. A reverse
proxy with equivalent isolation (different origins, no shared cookies between
static and API origins) is acceptable.

### 4. Browser storage rules

Forbidden:

```text
localStorage access token · localStorage plaintext SMS
sessionStorage plaintext SMS history
```

Sessions are `HttpOnly + Secure + SameSite` cookies. IndexedDB may persist
**ciphertext only**; decryption happens just-in-time for the UI/search that
actually needs it.

### 5. PWA auto-lock (crypto lock)

After a configurable inactivity period the PWA must:

```text
lock the crypto context
clear decrypted transient state
show "Unlock Messages"
```

Unlock prefers Passkey/WebAuthn re-assertion. Lock clears key material from
memory — a locked PWA is a ciphertext viewer.

### 6. Push privacy by default

Default Web Push content:

```text
"New message — Open Messages to view"
```

Never sender/body preview by default. Plaintext preview is an explicit user
opt-in, and even opt-in must not bypass the sensitive-message firewall of
ADR-006 (a LOCAL_ONLY message never generates a push at all).

### 7. Device capability model

DeviceCertificates carry explicit capabilities:

```text
READ_MESSAGES · SEND_MESSAGES · MARK_READ · MANAGE_DEVICES
MANAGE_SECURITY · EXPORT_MESSAGES · MANAGE_SIM
```

Not every device holds every capability. Service identities (e.g. Eve) hold
narrow sets — e.g. `SEND_SMS_AUTOMATION`, `READ_SEND_STATUS` — with
`READ_MESSAGES = false` by default. Authorization is enforced **twice**:
server-side per route AND cryptographically (the request signature covers the
capability-scoped action; a missing grant fails closed).

### 8. Mandatory web security verification

OWASP ASVS L3 controls are the target for applicable controls. Minimum test
matrix (CI + release audit):

```text
XSS · DOM XSS · CSRF · IDOR/BOLA · broken authorization
session fixation · session theft · device revocation
CSP bypass · malicious URL in SMS · HTML injection in SMS
javascript: URL · SVG/content injection · API enumeration
rate-limit bypass · encrypted payload tampering · replay
cross-account access
```

XSS in this product is classified **Critical** (blocker for any web release).

## Consequences

* GMweb storage schema/queries must assume ciphertext-only for message data;
  any feature that needs server-side search over bodies is rejected by design.
* Web release process gains an approval + audit gate.
* The capability registry becomes part of the device enrollment flow (PR-08
  lineage) — new grants require explicit certificate re-issue.
* Web/PWA history sync must NOT be enabled before ADR-006's firewall ships
  (see ADR-006 implementation timing).
