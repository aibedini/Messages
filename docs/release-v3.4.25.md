# v3.4.25 — fix the launch crash and the Android 15 foreground-service crash

`versionCode 132` · `versionName "3.4.25"` · minSdk 26 · targetSdk 36

Two crashes were found from the app's own diagnostics (no ADB needed); both came from the 3.4.24
telemetry work and both are fixed here.

## 1. Launch crash: the failure sanitiser itself crashed the process

`TelemetryHealth.sanitizeDetail()` used a regular expression with an inline `(?U)` flag. Android's ICU
regex engine rejects that construct, so the moment a telemetry attempt FAILED — the STARTUP attempt on
every launch — the sanitiser threw `PatternSyntaxException` while recording the failure, inside
`onFailure`, inside `DeviceTelemetry.performReport`, and took the whole process down. The diagnostic
file recorded the same stack on every restart.

**Fix:** the sanitiser no longer uses a regex at all. Phone-like digit runs are redacted by a small
character scanner that cannot throw, and the method is documented as "must be crash-safe, because it
runs while handling an already-failed report". Telemetry failures now stay failures instead of becoming
crashes.

## 2. Second crash: Android 15 exhausted the `dataSync` foreground-service budget

After repeated restarts, Android 15 refused the service with
`ForegroundServiceStartNotAllowedException: Time limit already exhausted`, thrown from
`GatewayService.startForegroundNotification()`.

**Fix:** on Android 15+ the gateway no longer depends on the `dataSync` budget and starts with
`specialUse`. The start policy and its test were updated to match; older versions keep the previous
behaviour.

## Untouched

Pairing, identity, keys, the command claim and the `runtime` advertisement are unchanged. Telemetry's
stage classification and attempt accounting from 3.4.24 are unchanged.

## Verification

```text
Build: assembleDebug/assembleRelease produced by CI on the v3.4.25 tag
APK:   applicationId com.autonomousone.messages · versionName 3.4.25 · versionCode 132

NOT PHYSICALLY VERIFIED by the agent: no device was connected here. The crash causes were identified
from the on-device diagnostic stack traces, and the fixes follow those traces; confirmation that the
app now launches and reports telemetry on the SM-G998B is the user's install test.
```
