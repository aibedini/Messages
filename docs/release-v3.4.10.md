# v3.4.10 — durable replication corrections and Android 15+ lifecycle fixes

`versionCode 117` · `versionName "3.4.10"` · minSdk 26 · targetSdk 36

This release contains the corrections found while validating the v3.4.9 replication work. It is a
**correctness release**, not a feature release: every change below fixes behaviour that was measured
to be wrong, and each one carries the test that demonstrated it.

---

## Reliability

- **Retention could be switched off by a single failed event.** A permanently failed history event
  froze the acknowledgement watermark, and because retention only removes a history row at or below
  that watermark, *every* acknowledged row produced afterwards became permanently undeletable —
  4,001 rows were pinned in the reproduction. A dead position is now terminal for the frontier
  (delivered **or** permanently failed), so retention resumes. Outstanding work (`PENDING`,
  `RETRY_WAIT`, `SENDING`) still stops the frontier, so "nothing left to do below this point" remains
  true, and `isDelivered` is unchanged and still refuses to call a source with any failure delivered.
- **The diagnostic reported a frontier position as a row count.** `acked` was taken from
  `ackedContiguousOrdinal` and `pending` derived as `produced − acked`, so with a dead letter the
  report claimed 4,000 events were still pending when they were delivered — while never reporting the
  failure at all. `acked` is now counted, `failed` is reported, and `pending` is the remainder, so
  `produced = acked + pending + failed` holds by construction.
- **`CAUGHT_UP` was unreachable for any source that had ever lost one event.** It required full
  delivery, including zero dead letters, which made a terminal state that could never be reached.
  It now means *resolved* — acknowledged, or permanently failed and counted — so the state is
  reachable and the failure stays visible rather than hidden. A running scan, or an outstanding row,
  still blocks it.
- **Retry and dead-letter rescue behaviour** (from v3.4.9) remains: a rescue restores the retry
  budget and the due time, is filtered to the cohort it can actually repair, and no rescue may leave
  a row claimable with an exhausted budget.

## Android platform compatibility

- **Reboot recovery was illegal on Android 15+.** The boot receiver launched a `dataSync` foreground
  service, which Android 15 forbids from `BOOT_COMPLETED`; the refusal was a thrown exception at boot,
  and because the gateway has no periodic scheduler the gateway stayed down until the app was opened.
  The start reason now decides the service type, and a refused start is caught and deferred to
  WorkManager instead of throwing.
- **The restart watchdog could not restart anything on API 31+.** It armed an alarm that started a
  foreground service from the background — refused by the platform, and because the *system* performs
  that start, the refusal never reached this process: the watchdog logged a revival that never
  happened. WorkManager now owns the retry on every platform; the alarm is armed only where a direct
  background start is actually permitted.
- **Timeout-safe foreground data sync.** Android 15 stops a `dataSync` service that has run too long
  in the background. That `onTimeout` path is now handled: the timeout is recorded, the restart is
  deferred to WorkManager, and the service stops promptly. Nothing is lost — history progress is
  durable per page — but the bridge cannot restart itself into a restricted background, so recovery
  happens when the platform next allows it.

## Messaging

- **Delivery and status events now receive their intended replication priority.** A status change was
  enqueued as `REALTIME` because its priority was never set, so its declared `STATUS_UPDATE` rank was
  dead and a burst of delivery reports could rank equal to new messages.
- **Failed remote-send commands retain an actionable reason.** Terminal command transitions recorded
  only the state, leaving `completedAt` at 0 and `lastErrorCode` empty — a send the radio refused
  reached GMweb as FAILED with no reason. A guarded terminal transition now records the reason and the
  completion time together, and the state-only transition refuses a terminal target state so the
  defect cannot return through a new call site.
- **Diagnostic wording corrected for terminally failed events.** A scan that finished with permanent
  failures was reported as "GMweb does not yet have all of it", which promises events that will never
  arrive. That case is now named as permanent; genuinely outstanding work keeps the original wording.

## Internal cleanup

- Corrected architectural guards, including one that matched prose instead of code and one that
  false-passed on a call site passing a local variable.
- The MMS attachment gap is now **visible** in the diagnostic instead of absent: it reports how many
  attachments exist on the device and that there is no protocol to replicate any of them. Previously a
  device holding un-replicable photos produced a report identical to one holding none.

> **Already in v3.4.9, not re-claimed here:** the removal of the dead `SendState` /
> `aggregateSendState` duplicate derivation, and the original retry/dead-letter rescue corrections.
> Those shipped in v3.4.9 and are listed in that release, not this one.

---

## Known limitations — unchanged and not softened

```text
DEVICE ACCEPTANCE PENDING

Physical-device validation has NOT been performed. There is no device or emulator run
behind any statement in this release.

OEM battery behaviour is NOT validated. Whether a given manufacturer's battery manager
keeps the gateway alive has not been measured on any device.

The 360k-message benchmark is validated at the deterministic SQLite/replication layer
ONLY. It exercises the shipped SQL, transaction shape, event construction and retention
against real SQLite. It is NOT a 360k-message Telephony Provider run.

MMS binary attachment replication still requires GMweb-side protocol support. No
attachment upload endpoint exists; the app cannot replicate attachment bytes and does not
claim to. See docs/gmweb-mms-attachment-handoff.md.

Exactly-once remote SMS transmission cannot be guaranteed across the radio-submit crash
window. There is no atomic step between recording the attempt and the radio accepting the
submit, so if the process dies inside that window the device cannot know whether the SMS
was sent. The app never retries automatically; the outcome is reported as unknown and a
human or GMweb resolves it with a new command id.
```

Everything in this release was verified by JVM unit tests, real-SQL execution against SQLite, source
guards, `lintDebug`, and debug/release builds. See `docs/production-validation-plan.md` for the
measurements, the per-defect evidence, and the exact scope of what each test does and does not prove.
