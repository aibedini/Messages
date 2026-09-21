# Messages v3.4.6 — one GMweb server, and a gateway status you can trust

`versionCode` 112 → **113** · `versionName` 3.4.5 → **3.4.6**
No database change: Room stays on **v17**. No migration, no backfill.

Two things shipped together, because the second is what made the first impossible to ignore.

---

## A — The gateway screen showed a green light that meant nothing

`ConnectionSupervisor` reported `CONNECTED` as soon as it had **started** the heartbeat, the
event uploader, the trust publisher, the poller and the sync loop. That sentence means "the
components were started". The user reads it as "GMweb and this phone are talking". In
production the two came apart:

```text
Android → GMweb event sync     ✅  "2/2 event(s) ACKed by GMweb"
GMweb → Android delivery       ❌  /gateway/pull never succeeded
supervisor state               CONNECTED        ← green
```

An event upload ACK proves `/api/v1/agent/events/batch` worked. It is a different route, in
the other direction, from `/gateway/pull` — which is what actually delivers an EVE send
request to this phone.

**The screen now reports independent dimensions**, and the headline is derived from what the
components *observed*:

```text
Gateway                                [ Run diagnostics ] [ Reconnect ]
⚠️ Degraded
GMweb is reachable, but send requests are not reaching this phone.

Internet                ✅ Wi-Fi
Server                  ✅ 42ms
TLS                     ✅ TLSv1.3
Authentication          ✅ device enrolled
Android → GMweb sync    ✅ ok · 8s ago
GMweb → Android pull    🔴 HTTP_AUTH
EVE queue               ⚪ idle
```

* **An empty long-poll is a SUCCESS.** `HTTP 200 {"task": null}` proves the phone reached
  GMweb, authenticated, was recognised and got a well-formed answer. Requiring an actual SMS
  task would report a working bridge as broken whenever the queue is empty.
* **`CONNECTED` no longer implies health.** The supervisor publishes what it knows — desired
  state, endpoint, validated route — and writes **no verdict**. Its card now says "Components
  running".
* **Failures are classified**: DNS, TCP, TLS, HTTP 401/403/404/409/429/5xx, read/write
  timeout, offline, invalid response — each with a sentence the user can act on.
* **A rejected key is an ERROR on the first occurrence**; a transient failure is DEGRADED
  until it persists.
* **The old "Gateway Active" card is now "Local phone API"**, because that is what it is: the
  switch, the LAN address and the bind options. It sat above a `http://192.168.x.x:8080` URL
  while reading as the GMweb connection.

**Tools:** `Run diagnostics` walks NETWORK → DNS → TCP → TLS → HTTPS → AUTH → PULL and stops
at the first failure, marking everything after it SKIPPED. `Reconnect` now *does* something:
it restarts the delivery poll loop when that loop has exited (idempotently, so no second
poller) and wakes the backoff instead of waiting it out. Live logs are structured and
filterable (All / Errors / Connection / Bridge / Sync / EVE), with a one-tap **redacting**
diagnostic report — no API key, no device key, no message body, no full number.

The same data reaches the existing **exported on-device diagnostics**, so a support report
now answers "where exactly did `/gateway/pull` break?":

```text
GATEWAY_PULL  failed kind=HTTP_AUTH status=401 consecutive=3 detail=HTTP 401
GATEWAY_PULL  recovered status=200 task=true
GATEWAY_UPLOAD failed status=503 events=12 kind=HTTP_SERVER
```

---

## B — Two servers, and one of them was baked into the APK

The app held **two independent GMweb values**:

| value | drove | default |
| --- | --- | --- |
| `gmwebUrl` | the pull bridge (`/gateway/*`) | empty |
| `backendUrl` | enrollment, events, heartbeat, trust | **a domain compiled into the APK** |

So a user could point the delivery bridge at their own GMweb while the control plane quietly
kept talking to the baked-in one — the phone pulling its send-requests from one server and
uploading its events to another, with nothing in the UI saying so.

**There is now ONE origin, and every route is derived from it:**

```text
                    ONE CONFIG
             https://gmweb.okgfx.ir
                        │
            ┌───────────┴───────────┐
       Control Plane            SMS Pull Bridge
       /api/v1/agent/...        /gateway/...
```

* The user pastes **the panel URL they actually have** (`https://gmweb.okgfx.ir/app`) and the
  origin is derived. `/app`, `/dashboard`, trailing slashes and uppercase schemes are all
  accepted; the stored value is always the bare origin, because a stored `/app` would 404 on
  every API route.
* **Rejected, each with its own message:** `http://` (HTTPS with no exception — this address
  carries a device key and a bearer token), embedded credentials, query, fragment, a missing
  scheme, a missing host, and an unknown path. An unknown path is refused rather than silently
  dropped: if GMweb ever lives under a sub-path, stripping it would produce 404s that look
  like a server fault.
* **No domain is compiled into the APK.** `GATEWAY_BACKEND_URL` has no default; an empty value
  means the app ships unconfigured, which is the honest default. `PairingEndpointResolver` no
  longer falls back to it either — a phone that was never configured used to "trust" a
  compiled-in domain, which is also how pairing and the pull bridge could disagree.
* **Saving takes effect everywhere, with no restart**: the poller (restarted if its loop had
  exited), the event uploader, the heartbeat, the trust publisher, the control plane and the
  diagnostics target.
* **The panel has its own button.** `Open panel` opens `…/app`; the user never has to reason
  about origin vs `/app`.
* **Existing installs migrate safely.** Priority is the old `gmwebUrl`, then an *explicitly
  stored* `backendUrl`, then nothing. The migration reads the legacy key with `contains`,
  never through the old getter — that getter returned the APK-baked domain for every install
  whose owner never chose a server, so asking for "the backend URL" would have adopted a
  domain the user never picked and persisted it as their choice. A marker makes it idempotent,
  so a rollback cannot resurrect a second authority.
* **The misleading "REST API Endpoints" card is split in two.** It used to swap its base URL
  between the GMweb origin and the LAN address behind a "Cloud Mode" flag, advertising the
  phone's OWN `/api/v1/sms/send` under the GMweb host — a combination that is not a real
  endpoint anywhere. Now: the GMweb connection is the health card, and the **Local phone API**
  card is explicitly labelled as this phone on your LAN, never a GMweb address.

---

## Tests

`1381` JVM tests, `0` failures — `187` of them added by this release:

* `GmwebServerProfileTest` (14) — the four accepted forms collapsing to one origin, every
  rejection, ports, and that every derived route stays on the configured origin.
* `GmwebServerMigrationTest` (12) — priority, idempotency, and the case that matters most: a
  `backendUrl` that was never written must NOT be inherited as a choice.
* `GatewayArchitectureGuardTest` (9) — source scans that fail if a GMweb host is ever hardcoded
  again, if the build regains a default domain, if `BuildConfig.GATEWAY_BACKEND_URL` is read at
  runtime, if the local API card starts using the GMweb origin, or if "Reconnected" returns.
* The gateway health suite: failure classification (36), the live recorder (27), the staged
  probe (26), the redacting report (15), the bounded log feed (21), the presentation mapping
  (17) and the EVE queue projection (10).

Gate: `testDebugUnitTest` · `assembleDebug` · `compileDebugAndroidTestKotlin` ·
`lintDebug` (`0 errors`) — all pass.

## Device acceptance before release

1. Enter **`https://gmweb.okgfx.ir/app`**. The stored server must show `https://gmweb.okgfx.ir`
   and the panel `https://gmweb.okgfx.ir/app`.
2. Run diagnostics. It must test **that same host**, and **no request may go to
   `gmweb.46.31.76.103.nip.io`** (or any other host) at any point.
3. Confirm the health card shows the pull dimension separately from the sync dimension, and
   that the live log's default feed contains no `N/N event(s) ACKed` lines.
4. Save a different server and confirm the delivery poll and the event upload both move to it
   without restarting the app.

## Not changed

Composer send routing and the v3.4.4 double-send fix, the v3.4.5 conversation-open authority
fix, optimistic reconciliation, RTL/LTR content direction, OTP retention, smart categories,
custom categories, and the Room schema (still v17). No certificate validation was relaxed
anywhere: no trust-all `TrustManager`, no `HostnameVerifier { true }`, no bypass — and the
connectivity probe sets hostname verification explicitly, because Android does not default it
for a raw `SSLSocket`.
