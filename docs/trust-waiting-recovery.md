# Linked web sync recovery (Android 3.4.16)

Shared feature ID: `linked-web-sync-recovery`.

The signed primary phone asks GMweb for its durable trust position and bounded pairing receipts. A local `WAITING_SERVER_APPROVAL` row with matching server approval is activated with its original signed bytes. A row whose pairing session is still pending remains blocked. An abandoned row is replaced by a fresh primary-root-signed `TRUST_SEQUENCE_VOIDED` statement carrying no capabilities, certificate, or history grant. Publication then resumes in sequence order. A server without the evidence fields cannot trigger automatic voiding.

This repairs the observed Android sequence 54 versus GMweb 43 without granting an unapproved browser. The phone must still publish and receive durable receipts. Full Test and browser decryption require real-device verification after installation.
