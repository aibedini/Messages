# ANR and stall diagnostics

Scope: what this branch can explain about a frozen main thread or a dead process,
and what it deliberately does not do. Branch `fix/realtime-room-ssot`.

## Historical process exits (API 30+)

`ProcessExitDiagnostics.install(context, scope)` reads
`ActivityManager.getHistoricalProcessExitReasons` asynchronously after startup and
records ANR, CRASH, CRASH_NATIVE, LOW_MEMORY and EXCESSIVE_RESOURCE_USAGE.

- API-gated: older devices take no code path at all.
- An ANR trace is read from `getTraceInputStream` with a HARD 64 KiB cap, on
  `Dispatchers.IO`. An unbounded trace read is never attempted.
- De-duplicated so the same exit is not re-recorded on every launch.
- Startup is not blocked.

## Sanitization

`ExitReasonSanitizer` (pure, unit-tested) redacts before anything is persisted:
phone numbers, SMS-body assignments, credential/token assignments, Bearer tokens,
JWTs and long opaque tokens. Nothing in this pipeline writes an SMS body, a raw
phone number, a secret or a key.

## Main thread stall watchdog

- Warning at ~2 s, critical at ~5 s.
- It NEVER kills the process, never restarts an Activity and never itself performs
  main-thread I/O: the probe posts to the main Looper and does all thresholding
  and reporting on a background thread.
- `StallReportSink` exposes `report()` only - there is no kill/restart member in
  the API at all, so the "never kill the app" rule is enforced by the type, not by
  convention.
- Rate limited: at most one warning and one critical per stall, with a 30 s
  minimum interval. Escalating from WARNING to CRITICAL inside an ACTIVE stall is
  deliberately exempt from the cooldown (otherwise the critical event would be
  suppressed by the warning that preceded it).

## Breadcrumbs

A stall report carries: current screen, a HASHED conversation token (never the raw
thread id), visible message count, current sync operation, exact-repair queue
depth, history-backfill state, Room operation label, provider operation label,
memory stats and the stall duration.

Producers wired today: the visible conversation
(`VisibleConversationTracker.onOpened/onClosed`) and the exact-repair queue depth
(the repair loop). The screen route and the remaining operation labels still need
call sites in `MainActivity` and the ingest paths.

## Tracing and frame metrics

- `TraceSections` provides 12 named `android.os.Trace` sections plus an
  exception-safe `trace(name) { ... }` bracket, disabled-safe.
- DEBUG only, log-only StrictMode (`detectDiskReads/Writes/Network`), with NO
  death penalty and no dialog: a debug build must not be able to kill itself.
- DEBUG only, dependency-free `FrameMetrics` counters (bounded), written off the
  main thread.
- No JankStats or analytics dependency was added.

## Local performance telemetry

`PerfTelemetry` keeps bounded ring buffers (256 samples) per boundary and computes
nearest-rank p50/p95. It is local only - nothing is uploaded. An empty metric
reports "no samples" (`MetricStats.p50Ms == null`) rather than a fabricated 0.

## Honest status

    GATE-VERIFIED: NO
    TESTS EXECUTED: 0
    COMPILE STATUS: UNVERIFIED

Nothing here has been observed on a device. The watchdog has never fired in this
branch, and no ANR trace has actually been imported by a running build. The
code exists, is unit-testable, and is not verified.
