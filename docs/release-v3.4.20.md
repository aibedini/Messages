# v3.4.20 — GMweb realtime sync and read propagation

`versionCode 127` · `versionName "3.4.20"` · minSdk 26 · targetSdk 36

Two silent P0s, both of which the user experiences as "GMweb is not updating".

---

## 1. A read on the phone never reached GMweb

The local read path wrote Room first and the Android provider second, so when the observer reconciled
the thread the read flag already matched (`ProviderTransition.UNCHANGED`) and no read-carrying event
was ever built. `THREAD_READ` had exactly one caller — the remote command — so opening a conversation
**on the phone** told GMweb nothing, and its unread badge stayed set until some unrelated event
re-published that conversation. (The only case that did publish was the failure path, when the local
write had *not* happened.)

The local path now applies the shadow read and enqueues the durable `THREAD_READ` in one transaction
(`TelephonySyncCoordinator.applyMarkThreadRead`), before the provider write. The event identity is
deterministic per conversation+revision, so a repeated read is a dedupe, not a storm.

## 2. The command channel — the only transport that carries `MARK_THREAD_READ` — never ran

`ConnectionSupervisor.reconcile()` stopped the command poller in its `LEGACY_PULL` branch (the branch
that is always taken, because nothing passes `CONTROL_PLANE_COMMANDS`) and nothing ever started it. A
read command sent from GMweb could not be claimed, executed or ACKed at all: the phone's answer to
"mark this conversation read" was silence.

Both intakes now run. The poller's own single-owner guard for `SEND_SMS` is unchanged, so there is
still one owner for a remote send.

**`MARK_THREAD_READ` no longer reports a false `COMPLETED`.** The provider write's result and the
durable event's `EnqueueAttempt` were both discarded, and `finishCommandFrom`'s boolean with them — so
a read that changed nothing and told nobody still completed. The order is now load-bearing: resolve
mapping → provider write → durable event → *then* complete and ACK. Failures carry read-specific
codes instead of `SMS_SEND_FAILED`/`UNKNOWN`:

```text
READ_COMMAND_DECRYPT_FAILED · READ_INVALID_PAYLOAD · READ_MAPPING_MISSING
READ_UNKNOWN_CONVERSATION · READ_PROVIDER_WRITE_FAILED · READ_EVENT_PUBLISH_FAILED
READ_PERMISSION_DENIED
```

## 3. Realtime latency

* **An incoming SMS now wakes the gateway.** Ingestion (the observer relay), the provider-repair
  scheduler and the uploader are all created in `GatewayService.onCreate`, and the app has no periodic
  gateway scheduler — so a message that arrived while the foreground service was down was stored and
  then waited for the user to open the app. `GatewayWakeOnSms` starts what the user already enabled
  (`SMS_DELIVER` is exempt from the background FGS-start restriction; a refusal is deferred to
  WorkManager). An SMS can never enable the gateway by itself.
* **The uploader's 30-second gate sleep is interruptible**, so an event that arrives while a gate is
  held is uploaded as soon as the gate clears instead of up to 30 s later.
* Verified unchanged and safe: a new inbox SMS is `PRIORITY_REALTIME`, and foreground/background
  claim separate queries with a 70/30 quota, so a large BACKFILL backlog cannot delay it.

## 4. Telemetry is event-driven, not only a 60-second timer

Immediate report on `STARTUP`, `NETWORK_RECONNECTED`, `GATEWAY_CONNECTED`, `SUBSCRIPTIONS_CHANGED`,
`DEFAULT_SMS_CHANGED`, `PHONE_PERMISSION_GRANTED`, `APP_UPDATED`, `MANUAL_DIAGNOSTIC_REFRESH` —
debounced, single-flight with exactly one queued follow-up (`TelemetryWakeState`).

* **SIM discovery is tri-state** (`Available` / `PermissionMissing` / `Failed`): "the user has not
  granted Phone permission", "this phone has no active subscription" and "the platform refused to
  answer" no longer all look like `items: []`.
* `smsSubscriptions` keeps `available`/`items` and adds `reason`, `permissionGranted`,
  `defaultSubscriptionId`, `lastChangedAt`; a separate `permissions` block reports `readPhoneState`,
  `sendSms` and `defaultSmsRole` — being able to list SIMs says nothing about being able to send.
* `OnSubscriptionsChangedListener` (lifecycle-safe, debounced, compared as a set) reports SIM
  insert/eject/eSIM/default-line changes immediately, and a permission grant triggers a report instead
  of waiting for the heartbeat.
* `TelemetryHealth` keeps last attempt / last success / HTTP status / error code and surfaces them in
  diagnostics, so "GMweb says connected while the phone is not reporting" is visible on the device.

## 5. Also in this release

* `MY_PACKAGE_REPLACED` restarts the gateway after an app update (previously only a reboot did).
* The three copies of `isDefaultSmsApp()` are one `DefaultSmsRole` — the SMS receiver's copy did not
  check `RoleManager` at all, so it could disagree with the send path.
* Diagnostics gained telemetry/presence, SIM+permission and command-channel sections.

---

## Verification

```text
testDebugUnitTest  2106 tests, 0 failures, 0 errors, 0 skipped   (199 test classes)
assembleDebug      PASS
lintDebug          PASS — 0 errors, 320 warnings, 7 hints
```

31 tests were added for this work (`ReadCommandErrorTest`, `SimDiscoveryTest`,
`TelemetryWakeStateTest`, `SubscriptionChangePolicyTest`), plus the `SyncErrorCode` tripwire.

## Known limitations

```text
NOT PHYSICALLY VERIFIED

No physical device, SIM or carrier was used. Incoming-SMS latency, screen-off delivery, read
propagation in both directions, offline command recovery, SIM-change telemetry and the dual-SIM
send paths are all UNVERIFIED on hardware.

GMWEB_DEPENDENCIES

- Each SEND_SMS must arrive through exactly ONE transport (pull task or command).
  controlPlaneSendsEnabled defaults to true while its KDoc claims false, so with the command
  channel now running, SEND_SMS commands execute.
- A read of a conversation whose newest message is LOCAL_ONLY (OTP/bank/reset) is deliberately
  not published and is reported READ_EVENT_PUBLISH_FAILED. GMweb's badge for it cannot clear
  until that policy is decided.
- Repairing a remote_conversation_map row that this install never minted (reinstall/restore)
  needs a server-side handshake; Android will not guess a thread id from a phone number.

OEM behaviour (Samsung/One UI battery management, Android 15 foreground-service timeout) is not
validated. Realtime sync lasts as long as the platform lets the foreground service live.
```
