# Messages v3.4.7 — a diagnostic that tells the truth about the gateway

`versionCode` 113 → **114** · `versionName` 3.4.6 → **3.4.7**
No database change: Room stays on **v17**. No migration, no backfill.

## What this release is about

v3.4.6 worked on a real device, and its diagnostic subsystem then contradicted it. The gateway
was demonstrably healthy — `/health` returned 200, the outbound event sync returned 200, the
delivery poll was live with a task received and its result ACKed 25 seconds earlier, zero
consecutive failures — while the health card reported:

```text
TLS: not checked                                  (while the same run said "TLS PASSED · TLSv1.3")
Authentication: not verified                      (while the same run said "rejected this device's key")
AUTH FAILED HTTP 400
GMweb rejected this device's key, so it is not enrolled.
```

Not one of those was a gateway fault. Every one was a defect in the diagnostic's own code.

---

## 1 — The AUTH probe was sending a different request than the heartbeat

The single cause of the HTTP 400:

```text
heartbeat (works)   appVersion, batteryLevel, networkType, timestamp, events, sourceDeviceId
auth probe (400)    appVersion, timestamp, diagnostic,  events, sourceDeviceId
                    └ an extra field the server never saw, two it expects MISSING
```

The probe hand-rolled its own body, so the diagnostic was measuring **its own malformed
request** and reporting the result as a fact about the device's credential.

`AgentLivenessRequest` now builds that body for both callers, and `AgentDeviceFacts` owns the
battery/network lookups; the private copies inside `HeartbeatManager` are deleted, because a
private copy is exactly what allowed the drift. The probe also captures the server's response
body now — redacted and bounded — instead of reading and discarding it, so the next 400
explains itself instead of being guessed at.

## 2 — A 400 was rendered as "GMweb rejected this device's key"

The failure text was the hardcoded string `"device key not accepted by GMweb"` for *any*
failure, and 400 mapped to a payload-validation kind. Classification and wording are now
derived:

| Status | Meaning | Claims a rejected credential? |
| --- | --- | --- |
| 2xx | the check passed | — |
| 401 / 403 | the server rejected the credential | **yes, and only here** |
| 400 | the server refused the REQUEST (contract mismatch) | no |
| 404 | the route is not on this server | no |
| 405 | wrong method for the route | no |
| 422 | the server validated and refused the payload | no |
| 5xx | the server failed | no |
| network / TLS | connectivity failure | no |

`needsConfigurationChange` deliberately excludes the contract kinds: telling a user to check
their key for a malformed request sends them to fix the wrong thing.

## 3 — "not verified" and "rejected" were the same state

`AuthHealth.enrolled: Boolean` forced every outcome into yes/no, so *we could not check* was
rendered as *rejected*. It is now an `AuthVerification` tri-state —
`UNKNOWN / VERIFIED / REJECTED / UNVERIFIABLE` — and **only REJECTED is an ERROR**.
UNVERIFIABLE degrades the card at most and never outranks the pull bridge's live evidence,
because a bridge that is delivering *is* proof the credential is accepted.

## 4 — The card said "TLS: not checked" while the report said "TLS PASSED"

The publish step sat after the last stage of `run()`, so the early return for a failed stage
skipped it: a probe that measured TLSv1.3 successfully and then failed at AUTH recorded no TLS
result at all. Publishing moved into `finish()`, which every exit path goes through, and now
records what was *learned* — including a **failed** handshake, which is still a measured fact.
A stage that never ran leaves its dimension untouched, so "not checked" can only ever appear
for something genuinely unchecked.

## 5 — "Last result ACKed: 25s ago" vs "Last gateway ACK: never"

The same event, reported twice with different answers. `EveSmsQueue.healthSnapshot()`
deliberately does not set `lastGatewayAckAt` — the ACK leg is owned by whoever performs it —
but `OutboxPoller` calls `setEveQueue` with a whole object every ~25 seconds, and
`eveQueue = health` **erased** the stamp `onAckSuccess` had just written. `setEveQueue` now
merges, keeping the field the snapshot does not own, and a test asserts the two metrics agree.

## 6 — Local Phone API advertised a VPN address (and bound to it)

The UI showed `http://30.194.216.38:8080` on a phone with a VPN active. That is not RFC1918:
`getLocalIpAddress()` returned the **first** non-loopback IPv4 the OS happened to enumerate —
and `GatewayServer` **binds** to that same value, so the REST server may have been listening on
the tunnel instead of Wi-Fi. This was a functional bug, not a display bug.

`LocalAddressSelector` now decides: prefer a private LAN address on a non-tunnel interface,
never advertise loopback / link-local / reserved / `198.18.0.0/15` / `100.64.0.0/10`,
deprioritise point-to-point and `tun`/`tap`/`ppp`/`utun`/`wg` names, keep a deterministic
tie-break, and flag a fallback so the UI can say "no LAN address found". Nothing hardcodes
`192.168.x.x` — `10/8` and `172.16/12` are equally valid LANs.

## 7 — A VPN fake-IP was presented as the server's real IP

`gmweb.okgfx.ir -> 198.18.223.193` was printed unlabelled. `198.18.0.0/15` is the benchmarking
range and is common as a VPN/proxy fake IP, so that number may not exist on the internet. The
report now prints the **system DNS answer** and the **actual TLS peer** separately, each
labelled through `NetworkAddressFacts` (synthetic / carrier-NAT / private / link-local /
public). This is not an error state: TLS and HTTPS succeeding to `gmweb.okgfx.ir` with that
answer only means a tunnel is answering DNS.

## 8 — The 270 dead letters: aggregate only, nothing deleted

A new read-only query groups by `eventType × cryptoVersion × priority` with a count, attempt
range and first/last `createdAt`, and the report prints it with an explicit note: the outbox row
persists **no failure reason, no HTTP status and no update time**, so those cannot be reported
per event and must be correlated by time with the `GATEWAY_UPLOAD` lines in the on-device
diagnostics log. Reporting them per row would mean inventing data. Adding them would be a Room
schema change, and none was made.

## Also fixed: the report was truncating its own conclusion

The per-line bound used by the redaction sweep was cutting long free text, so the sentence
*"…this is NOT a rejected key"* was being clipped out of the shared report. Long text is now
wrapped. A report that silently cuts off its verdict is worse than no report.

---

## Tests

`1416` JVM tests, `0` failures (35 added here):

* `LocalAddressSelectorTest` (21) — Wi-Fi + VPN, Wi-Fi only, mobile, multiple interfaces,
  IPv4/IPv6, IPv6 ULA, every RFC1918 range, `172.32` correctly *not* private, carrier-NAT and
  fake-IP never advertised, and the enumeration order not changing the answer.
* `AgentLivenessRequestTest` (7) — the contracted key set, that no `diagnostic` field is sent,
  that the two fields the probe used to omit are present, and that the body enqueues nothing.
* Regressions for the empty-tail publish, the ACK-stamp clobber, `400 ≠ 401`, and
  "unverifiable is not rejected".

Gate: `testDebugUnitTest` · `assembleDebug` · `compileDebugAndroidTestKotlin` ·
`lintDebug` (`0 errors`) — all pass. `git diff --check` clean.

## Preserved

No `backendUrl` / `gmwebUrl` authority, no baked domain, one GMweb origin. The only read of the
legacy backend key remains the one-time migration, guarded by `contains`. No certificate
validation was relaxed anywhere: no trust-all `TrustManager`, no `HostnameVerifier { true }`,
and the TLS probe sets hostname verification explicitly.

## Device acceptance before release

1. Enter your panel URL and confirm the stored origin.
2. Run diagnostics. `AUTH` must now read either **verified**, or **"could not verify — not a
   rejection"** with the server's own words attached — and it must never claim a rejected key
   from anything other than a 401/403.
3. Confirm the card's TLS row agrees with the report's TLS row after a run.
4. Confirm "Last result ACKed" and "Last gateway ACK" now agree.
5. Confirm the Local Phone API shows a private LAN address while the VPN is up, and that no
   request goes to any host other than the configured origin.
6. Export the diagnostic report and check the dead-letter aggregate appears with its
   explanation, and that no event was deleted.
