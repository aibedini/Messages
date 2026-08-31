# ADR-002 — Command Encryption and Conversation Key Epoch Model

**Status:** Accepted (LOCK 4/5/6/7/8 of the Messaging Platform architecture session)
**Date:** 2026-08-31
**Scope:** Crypto model for commands and message history, all clients

## Context

The Tech Spec left open: (a) how commands are encrypted, (b) how history scales — per-message-per-device envelopes (1 envelope × every message × every device) do not survive large inboxes, and (c) how a newly paired device gets historical access without re-encrypting message ciphertexts.

## Decision

### 1. Commands are point-to-point, not enveloped to all clients (LOCK 4)

For `SEND_SMS` (and other sensitive commands) the Web device does NOT create per-client key envelopes. Flow:

```text
Web/PWA
   ↓ sign command with Web signing key
   ↓ encrypt command payload specifically to the Android Agent encryption public key
GMweb (ciphertext only)
   ↓
Android: verify trusted DeviceCertificate → verify capability SEND_SMS
         → verify signature → verify target → verify expiry
         → verify nonce/idempotency → decrypt → execute
```

After execution, **Android is the canonical producer of the message and its status/history** (telephony evidence is the truth — Rule 1). Web optimistic UI is display-only until Android emits the canonical event. A sending Web device needs only the Android Agent's valid public key + the signed trust registry (ADR-001) — not the keys of all client devices.

### 2. History uses Conversation Key Epochs, not per-message-per-device envelopes (LOCK 5)

```text
Conversation
    ↓ Conversation Key Epoch (CKE)
    ↓ Message DEK (random 256-bit, per message)
    ↓ Message ciphertext (AEAD, e.g. AES-256-GCM)
```

Per message: random Message DEK → encrypt body → wrap DEK with the current CKE. The CKE itself has **per-device envelopes per epoch**. Net shape: `1 wrapped DEK per message + CKE envelope per device per epoch` instead of `envelope × message × device`.

### 3. Epoch rotation (LOCK 6)

The CKE changes on: device added, device revoked, device key rotated, security-triggered rotation, and periodic policy when required. A revoked device never receives a new epoch. Data a revoked device already decrypted cannot be remotely erased — UI and security documentation must say this explicitly.

### 4. New-device history backfill = CKE re-wrap, never re-encryption (LOCK 7)

Pairing UI lets the user choose history scope for the new device: **Full existing history** or **From now on**. If full: Android decrypts nothing and re-encrypts nothing — it only re-wraps the historical CKEs to the new device's public key and publishes them as batched `KEY_GRANT` events. Large histories never require rewriting message ciphertext. (This closes gap A; per-message DEK wraps must therefore live in rows keyed by message, and CKE envelopes in rows keyed by epoch — schema designed crypto-friendly from PR-01.)

### 5. Honesty about protocol strength (LOCK 8)

This CKE/DEK model is the v1 E2EE architecture. Marketing/documentation must NOT call it "Signal protocol", "Double Ratchet", "MLS", "full Forward Secrecy" or "full Post-Compromise Security". If stronger FS/PCS is wanted (Phase 7+), the protocol is upgraded through the mandatory external Crypto Review. **No custom cryptography remains absolute** (RFP §116 non-negotiable). Candidate baseline primitives: HPKE, P-256, HKDF-SHA-256, AES-256-GCM, ES256 — final library/serialization choices are made at that review.

## Consequences

- GMweb stores only ciphertext for commands and messages; metadata leakage is as defined in TechSpec §27.
- Android performs re-wrap work at pairing time (batched, resumable — it is a durable outbox job).
- Keyring distribution = the signed trust snapshot (ADR-001); clients verify signatures before using any key.
- A future ratchet upgrade changes CKE derivation only — message ciphertext stays immutable.
