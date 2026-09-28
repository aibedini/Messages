# Android/web recovery v1

Shared feature ID: `android-web-recovery-v1` (GMweb and Messages Android).

## Incident evidence

The primary phone's linked browser certificate claimed trust sequence 48 while GMweb's signed trust registry stopped at 43. The old Android trust publisher marked any HTTP 200 response `PUBLISHED`, including GMweb's `{applied:false, reason:"sequence_gap"}`. That left the phone showing zero pending trust rows even though the server lacked the signed approvals. Separately, the server returned `CONFLICTING_DUPLICATE` for repeatedly submitted event identities; the old batch parser treated that stable refusal as retryable.

## Recovery mechanism

The publisher reads the authenticated server trust position, checks that every local signed row above it exists in order, and requeues only rows falsely marked `PUBLISHED`. It sends the original signed payloads, never reconstructs or invents certificates. Every POST requires an applied or exact-duplicate receipt with the matching sequence before local publication is ACKed. A gap or unknown receipt stays pending and visible. The outbox parser parks server-declared identity conflicts and invalid events as dead letters rather than retrying them indefinitely.

## Rollout and acceptance

Deploy GMweb 0.19.21 before installing Android 3.4.13. On the primary phone, rerun Full Test and confirm the server trust sequence catches up to the local signed sequence, the trust outbox drains, and a newly linked browser receives its verified root and history grant. Compare the outbox dead-letter reason breakdown before any targeted replay; do not bulk-reset the 28k existing dead letters. Android device execution and browser decryption remain NOT VERIFIED until observed on the real phone.
