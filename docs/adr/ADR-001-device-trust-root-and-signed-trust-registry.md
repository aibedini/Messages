# ADR-001 — Device Trust Root and Signed Trust Registry

**Status:** Accepted (LOCK 1/2/3/9 of the Messaging Platform architecture session, authoritative over TechSpec §21–§26 where they conflict)
**Date:** 2026-08-31
**Scope:** Android Agent, GMweb, Web/PWA clients

## Context

The Tech Spec makes every device independently keyed (signing + encryption keypair, private keys never leave the device) but left open **who is the authority for "this client is a Trusted Device"**. If GMweb is that authority, a compromised GMweb can mint devices, forge revocations, add capabilities, or swap public keys — breaking the RFP's core principle that the server must not be trusted more than necessary.

## Decision

**In v1, the Android Agent is the root of trust. GMweb is never the authority for device trust.**

### Key hierarchy

At first enrollment the Android Agent holds two levels of keys (Android Keystore, hardware-backed / StrongBox where available, non-exportable):

1. **Account Trust Root Signing Key** — used ONLY to sign device certificates, capability changes and revocations. Never used for day-to-day event signing.
2. **Operational Android Device Keys** — signing key + encryption/key-agreement key (see ADR-002).

Every Web/PWA device also holds its own operational signing/encryption keys (WebCrypto, non-extractable, IndexedDB).

### Pairing (v1)

```text
Web generates keys
        ↓
pairing request + nonce (single-use, ≤5 min, ≥128-bit entropy)
        ↓
Android explicitly approves
        ↓
Android Trust Root signs DeviceCertificate
        ↓
GMweb stores/relays certificate
```

**In v1 only the Android Agent may approve a new device.** Delegated device approval is explicitly out of scope.

### DeviceCertificate (minimum fields)

```text
accountId
deviceId
signingPublicKey
encryptionPublicKey
capabilities
trustSequence
issuedAt
version
rootSignature
```

GMweb can store and relay certificates but cannot forge one.

### Signed Trust Registry

Android keeps a durable trust registry. Trust changes are signed statements:

```text
DEVICE_APPROVED
DEVICE_REVOKED
DEVICE_CAPABILITIES_CHANGED
DEVICE_KEY_ROTATED
```

Each statement carries `trustSequence` (monotonic), `statementId`, `deviceId`, `operation`, `publicKeys/capabilities`, `issuedAt`, `rootSignature`. GMweb relays the trust log only. **All clients (Web and Android) verify the root signature locally.** Key material is distributed via `GET /api/v1/trust/snapshot` (rootPublicKey, trustSequence, active DeviceCertificates, revocations, capabilities, key versions). No public key is trusted merely because GMweb returned it.

### Revocation semantics (two-phase, honest UI)

When a device is revoked from the Web Security Center:

1. GMweb immediately blocks server-side: sessions, push subscriptions, API access, new commands.
2. **Cryptographic revocation becomes authoritative only when the Android Trust Root signs a `DEVICE_REVOKED` statement** (trustSequence++).

If Android is offline, the UI must show `Revocation pending on Android` — never falsely claim completed cryptographic revocation. When Android is back online, it signs the statement and all clients sync it.

## Consequences

- A compromised GMweb can perform availability/censorship attacks, but cannot create devices, forge revocations, add capabilities, or replace public keys.
- Trust changes require Android to be online at some point (eventual, durable — never lost: requests persist on GMweb until signed).
- Loss of the Android Trust Root key is an account-recovery problem for a future ADR; v1 accepts this.
- Android must cache and verify the signed trust registry (statements travel like any other sync event, not as trusted GMweb API responses).
