# Messages v3.3.5 — GMweb send deduplication and persistent reconnect

**versionCode 105 · Room schema v15 · Compose/Material 3**

This patch release fixes two gateway-facing reliability issues.

## One bubble per GMweb SMS

An SMS sent by GMweb was persisted in the Android Telephony provider and then
announced to the open conversation with a timestamp-based synthetic identity.
The provider refresh consequently treated the same physical SMS as a different
row: one bubble acquired the authoritative Delivered state while the synthetic
copy remained pending.

Outgoing events now carry the provider row id returned by persistence. The live
event and subsequent provider/Room refresh therefore share one stable identity,
so the authoritative row replaces the pending representation instead of adding
a second bubble. Producers without a provider row id retain the timestamp
fallback for compatibility.

## Reconnect remains self-healing

After a reconcile failure, `ConnectionSupervisor` now explicitly queues the
next attempt after its capped exponential backoff. Retry no longer depends only
on the periodic health tick and has no attempt limit while gateway consent and
the persisted user intent remain enabled.

The service restart watchdog now uses that persisted desired state rather than
the transient runtime-enabled flag. An unexpectedly destroyed desired gateway
is restarted, while an explicit user stop remains stopped.

## Verification

- Regression coverage proves an outgoing event and a later delivered provider
  row collapse to one bubble even when their timestamps differ.
- Reconnect policy coverage proves retries continue only with consent and
  enabled user intent.
- `testDebugUnitTest` and `assembleDebug` pass.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v3.3.4...v3.3.5
