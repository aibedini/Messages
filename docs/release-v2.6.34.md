# Release v2.6.34 — Android SMS delivery runtime stability

**versionCode 76 · no schema change (v7)**

This patch fixes commands remaining queued while the Android gateway appears
connected and removes several restart/backlog hazards. It introduces no new
user-facing feature.

## Delivery fixes

* Strategic command claims now use the registered device ID for both the
  authenticated identity and `agentId`; the hardcoded `android-agent` identity
  is removed.
* Android reads the GMweb `ciphertext` field and rejects missing, empty, or
  invalid ciphertext with a `protocol_error` instead of executing an empty
  command.
* Delivery intake is explicit: `LEGACY_PULL` is the safe default and the legacy
  and control-plane SEND_SMS consumers cannot run concurrently.
* `EveSmsQueue` is again single-consumer. `OutboxPoller` observes the requested
  record's terminal state without draining the queue itself.

## Restart and hang protection

* Service teardown preserves the user's persisted gateway-enabled intent;
  only an explicit Stop action clears it.
* The restart watchdog now uses `SystemClock.elapsedRealtime()`, matching its
  `ELAPSED_REALTIME_WAKEUP` alarm type.
* Debug builds fail immediately if `SmsSender.sendWithOutcome()` is invoked on
  the main thread, protecting UI responsiveness while backlog sends execute.

## Verification

* Strategic command contract tests cover the real-device claim identity,
  exact ciphertext decoding, rejection of the legacy `payload` field, and
  fail-closed malformed ciphertext handling.
* `testDebugUnitTest` and signed `assembleRelease` pass.

## versionCode

Bumped to **76** (`2.6.34`).
