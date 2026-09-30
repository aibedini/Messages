# linked-sms-realtime-v1

Shared feature ID with GMweb `specs/008-linked-sms-realtime/`.

Every uploaded event carries `sourceOrder`, the immutable positive Room outbox
row ID. It orders state updates by device enqueue order even when upload
priorities, retries, or history backfill change server arrival order. The field
contains no message content or recipient data.

The Primary Android agent publishes a privacy-safe active SMS subscription list
in signed device telemetry. It excludes phone numbers and SIM identifiers beyond
the Android subscription ID needed to make the exact send selection. The linked
PWA places that ID inside its encrypted `SEND_SMS` command. At execution, Android
checks the requested subscription is still active and that the bound SmsManager
reports that exact ID; otherwise it records `SIM_NOT_AVAILABLE` before any modem
submission. A missing subscription list is an unavailable choice, not permission
to fall back to another SIM. The historical no-choice command retains Android's
default-SIM behavior for mixed-version rollout.

Physical dual-SIM send and phone-to-browser latency acceptance require a real
device and remain NOT VERIFIED until a full device run records them.
