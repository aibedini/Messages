# Messages v3.3.2 — Home "SMS today" ledger correctness

**versionCode 102 · Room schema v12 (send_segments rebuilt, non-destructive) · Compose/Material 3**

A correctness release for the Home "SMS today" chip. The UI was not patched with
timers or a persisted counter: the ledger that feeds it was fixed.

---

## 1. The bug

The chip counted rows that **two asynchronous writers raced on**, over a window
that was **not a real calendar day**.

    SmsSender.markSegmentsSuccessful()   -> ledgerScope.launch, AFTER the submit
    SmsStatusReceiver                    -> SENT callback, same (rowId, partIndex)
                                            with OnConflictStrategy.REPLACE

Last writer wins, so:

* a `RESULT_OK` callback followed by a `NO_SERVICE` callback (or the
  reverse) moved the count 2 → 0;
* the callback overwrote `sentAt` with CALLBACK time, so a segment
  submitted at 23:59:59 could be counted in the next calendar day;
* `HomeViewModel` used `dayEnd = dayStart + 24h`, which is wrong on a DST
  transition day and after a timezone change.

## 2. The ledger: an immutable fact plus a mutable verdict

`send_segments` (v12), primary key still `(rowId, partIndex)`:

| column | role |
|---|---|
| `submittedAt` | **immutable** epoch millis of the native submission |
| `partCount`, `subscriptionId` | ledger identity/diagnostics |
| `callbackAt`, `callbackResult`, `callbackState` | modem verdict |

`submittedAt` is nullable for exactly one reason: a callback may reach
the ledger before the submission write commits. Such a row is inserted with
`submittedAt = NULL` and the submission write completes it once, so both
orderings converge to the identical final row — and a NULL row is simply not
counted yet, never counted and then un-counted.

## 3. Concurrency strategy

Both writers use idempotent, individually atomic statements (no REPLACE):

* **submission** — `INSERT OR IGNORE`, then a guarded
  `UPDATE ... WHERE submittedAt IS NULL` when the row already existed;
* **callback** — targeted `UPDATE` of the callback columns only; if it
  affected no row, `INSERT OR IGNORE` with `submittedAt = NULL` and then
  re-apply the update.

The upsert form (`ON CONFLICT ... DO UPDATE`) was deliberately avoided: it needs
SQLite 3.24 and would break on API 26–29.

## 4. Calendar day, not 24 hours

One `DailyWindowProvider` (java.time `Clock` + `ZoneId`) owns "today":

    start = today.atStartOfDay(zone).toInstant().toEpochMilli()
    end   = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

Timestamps stay UTC epoch millis; the zone is used only for the boundaries. The
Home counter rebuilds its Room observation on process start, on `ON_RESUME` and
on `ACTION_DATE_CHANGED` / `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED`, so a Home
left open across midnight moves to the new day with no Activity recreation. The
midnight timer is only a wakeup — every restart re-derives the window.

The counter itself is:

    SELECT COUNT(*) FROM send_segments
    WHERE submittedAt >= :dayStart AND submittedAt < :dayEnd

never filtered by `callbackState`: a failed callback can no longer make the number
move backwards. Verdicts remain queryable as separate diagnostics.

## 5. Migration 11 -> 12

The table is rebuilt because SQLite cannot drop the old `sentAt` / `success`
columns in place. It is **not destructive**: every row is carried over,
`submittedAt` maps from `sentAt`, `success = 1` becomes `CONFIRMED` and
`success = 0` becomes `FAILED`. No `DELETE`, no destructive fallback in
release builds.

## 6. Tests

`testDebugUnitTest`: **363 tests, 0 failures** (20 new).
`assembleDebug` and `compileDebugAndroidTestKotlin` green.

The ledger contract is SQL behaviour, so it is tested as SQL against a real
SQLite engine, using the shipped statements and the shipped migration — no
mocks, no sleeps (the clock is injected).

## Notes / known limits

- A row whose submission write never commits is never counted (fail closed).
- Legacy v11 rows keep their old `sentAt`, which was callback time for
  callback-written rows — history is preserved with the same approximation, not
  reconstructed.
- The ViewModel-level wiring (broadcast → re-subscribe) is compile- and
  source-verified; a device androidTest would be needed to exercise it end to end.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v3.3.1...v3.3.2