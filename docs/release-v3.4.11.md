# v3.4.11 — the pull bridge can no longer go silently dead

`versionCode 118` · `versionName "3.4.11"` · minSdk 26 · targetSdk 36

This release fixes a **real-device production failure** reported against v3.4.10: the pull bridge
stopped polling and said it was fine.

---

## The failure, and why it lasted nine hours

A device reported all of this at once:

```text
Pull bridge (GMweb → Android):
  Running: yes
  State: POLLING
  Last poll started: 9h ago
  Last successful poll: 9h ago
  Last error: none
  Consecutive failures: 0
```

Outbound uploads were succeeding (`HTTP 200`, four seconds earlier) and DNS, TCP, TLS and HTTPS were
all healthy — so nothing was wrong with the server, the credential or the network. The pull loop had
simply stopped, and every line above was individually true.

Two defects combined to hide it:

1. **The loop's lifetime was tracked by a boolean, not by its job.** `start()` refused to run while
   that flag was set, and only `stop()` ever cleared it. A loop that exited on its own — the
   access-policy check, or anything thrown between iterations — left the flag `true` for ever. So the
   bridge reported "not running" while `start()` did nothing when the supervisor asked it to.
2. **The supervisor's self-healing was defeated by exactly that.** `retryNow()` correctly noticed the
   dead loop and called `startPoller()`, which returned early on the stale flag; the wake-up then went
   into a channel with no consumer. Recovery was impossible until the app was force-stopped.

And one word made it invisible: **`POLLING` stood for six different things** — a live long-poll, a
backoff sleep, a deliberate wait for the network, an idle gap, and a loop that had been dead for nine
hours. Nobody could tell a working bridge from a corpse.

---

## What changed

**The pull loop's lifetime is now owned by its job.** `start()` is idempotent against an *active*
loop rather than against history, so a dead loop is always replaceable and a live one never
duplicates. Every exit — return, policy stop, throw, cancellation — runs through `try/finally` and
cleans up, so `stop()` is no longer the only tidy path. Each run carries a generation, so a loop that
finishes late cannot clear the state of the loop that replaced it.

**`Running` now means a job is running.** It is read from the job itself, and the state label is
written by the same call, so `Running: yes` beside hours of silence is no longer constructible.

**The state is derived, not asserted.** The diagnostic now distinguishes:

```text
POLLER_JOB_ACTIVE       the loop is alive between cycles — healthy
POLL_REQUEST_IN_FLIGHT  the long-poll is open right now — the strongest "it works" signal
POLL_BACKOFF            a failed cycle is being waited out — healthy
POLL_WAITING_NETWORK    deliberately issuing no requests — healthy
POLL_LOOP_DEAD          nothing is running; the supervisor starts it
PULL_LOOP_STALLED       running but silent; the supervisor replaces it
```

**A stalled loop is now replaced, bounded.** An active job is not proof of a live bridge — a socket
can hang and the network gate can wait — so liveness is judged by freshness against a window derived
from the loop's own timeouts (a full pull, drain and ack, doubled: **six minutes**). Past it the
supervisor restarts the loop. The restart resets the activity clock, so this can fire at most once per
window and cannot become a hot restart loop. The same window is why the nine-hour silence would now be
caught in minutes.

**A second silent-bridge route was closed.** The network gate waited for connectivity with no
timeout — a live job issuing no requests, invisible to any "is it running" check. It now re-checks
every minute.

**The loop's own gate is explicit.** The user's intent outranks the radio: a device that is switched
off *and* offline stops the loop rather than parking in a wait that cannot end.

**Lifecycle telemetry is reported.** The diagnostic now shows the loop generation, when it started and
ended, why it ended, and the last poll cycle — so "the loop was replaced at 09:41" and "the loop never
ran" are no longer the same reading.

---

## Upgrading

No schema change, no migration, no data movement. Nothing needs to be reconfigured.

---

## Known limitations — unchanged and not softened

```text
DEVICE ACCEPTANCE PENDING

The fix has NOT been validated on a physical device or emulator. It is proven by JVM
tests, real-SQL execution, source guards and builds — not by a runtime.

OEM battery behaviour is NOT validated. Whether a given manufacturer's battery manager
keeps the gateway alive has not been measured on any device.

The 360k-message benchmark is validated at the deterministic SQLite/replication layer
ONLY, not against a real 360k-message Telephony Provider.

MMS binary attachment replication still requires GMweb-side protocol support. No
attachment upload endpoint exists and the app does not claim to replicate attachment bytes.

Exactly-once remote SMS transmission cannot be guaranteed across the radio-submit crash
window, for the reasons documented in v3.4.10.
```

## Separately reported, still open

The same device's diagnostic showed full-mirror verification on SMS as
`examined=72000 · alreadyReplicated=1000 · recovered=71000` — meaning history reported `CAUGHT_UP`
while 71,000 messages had no durable event. That is **not** addressed here and is tracked as its own
investigation. It is unrelated to the pull loop.
