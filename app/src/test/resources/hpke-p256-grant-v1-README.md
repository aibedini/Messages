# `hpke-p256-grant-v1.json` is synthetic test material

This is the LDV-004 / XR-GA-008 cross-implementation HPKE grant vector shared
with god-ai (`docs/contracts/vectors/hpke-p256-grant-v1.json`).

- It was produced once by `HpkeGrantVectorTest`'s generator through the same
  Tink 1.18.0 `HybridEncrypt` path as `MessageCrypto.wrapForDevice`.
- It contains a **throwaway P-256 recipient key**. The key is not a device
  identity, not a production key and **not a secret**. Never use it for a real
  device, never copy it into an app build, and never treat it as key material
  for anything but this test.
- The plaintext is the fixed 32-byte pattern `0x00..0x1f`, not a real CKE.
- The committed file is pinned on both sides: this test decrypts it with Tink
  and god-ai's `crypto/hpke` opens the same bytes. Regenerating it requires
  updating both repositories and both tests.

The resource is read only by JVM unit tests; it is not packaged into the app.
