# v3.4.26 — remove the last ICU-incompatible regex from the telemetry payload

`versionCode 133` · `versionName "3.4.26"` · minSdk 26 · targetSdk 36

The remaining telemetry failure is fixed, and this time it was named by the app's own diagnostics on a
real SM-G998B running 3.4.25:

```text
Failure stage   PAYLOAD
Failure code    PAYLOAD_BUILD_FAILED
Failure detail  PatternSyntaxException
Last HTTP       n/a          ← the request never left the phone
```

## What was wrong

3.4.25 removed the `(?U)` regex from `TelemetryHealth.sanitizeDetail()`, but **a second copy of the
same construct was still in `DeviceTelemetry.safeSimLabel()`**:

```kotlin
.replace(Regex("(?U)\\+?\\d[\\d\\s()\\-]{6,}"), "SIM")
```

`safeSimLabel` is called while building the SIM section of every telemetry payload. Android's ICU
regex engine rejects the inline `(?U)` flag, so as soon as the device had SIM labels to sanitise the
payload build threw before any HTTP request was made — which is exactly why `Last HTTP` was `n/a`
rather than a status. The rest of the phone was healthy the whole time: pairing, command claim HTTP
200, runtime advertisement, both SIMs discovered.

## The fix

* `safeSimLabel()` no longer uses a regex at all. It flattens whitespace and redacts phone-like digit
  runs with a small character scanner that cannot throw.
* Redaction uses `Char.isDigit()`, so Persian/Arabic-Indic digits are redacted as well as ASCII ones.
* A regression test guards the sanitiser: it fails if `safeSimLabel` regains a `Regex(` or a `(?U)`.

Both crash paths found from the on-device diagnostics are now gone: the 3.4.25 `sanitizeDetail` regex
and the Android 15 `dataSync` foreground-service budget (fixed via `specialUse`).

## Verification

```text
CI on this PR:  Build Debug APK  pass (2163 unit tests, 0 failures)
                drift            pass
                gate             pass
APK:            com.autonomousone.messages · versionName 3.4.26 · versionCode 133
```

## NOT PHYSICALLY VERIFIED

No device was connected here. The evidence is the on-device failure detail

```text
PAYLOAD · PAYLOAD_BUILD_FAILED · PatternSyntaxException
```

and the source location it points at, plus the CI suite. Confirmation is the install test over 3.4.25
(update-in-place, no uninstall, no clear-data, no re-pair) followed by **Test telemetry now**.

If any further failure exists, the diagnostic no longer stops at `PAYLOAD`: it will name the next
stage — `SIGNING`, `HTTP`, or `NETWORK` — with its exception class.
