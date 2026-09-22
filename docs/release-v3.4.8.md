# Messages v3.4.8 — accurate gateway health on compact screens

`versionCode` 114 → **115** · `versionName` 3.4.7 → **3.4.8**

Room moves from schema **17** to **18** with an additive migration. Existing dead-letter rows
are preserved and keep unavailable metadata as null.

## Fixed

- Historical dead-letter rows no longer report a current outbound sync failure when uploads
  are succeeding.
- Authentication HTTP 400 remains a request-contract mismatch. Only 401/403 can report a
  rejected credential, and successful runtime traffic is shown separately from the neutral
  independent-probe state.
- ACK health now distinguishes the process-session total from consecutive current failures;
  a successful ACK clears the current failure state.
- Future dead-letter rows retain safe failure category, HTTP status, attempt timestamps,
  dead-letter timestamp, and app version. No payload, API key, signature, message body, or
  phone number is added.
- The primary card calls the measured 3 ms value a TCP connect time only in advanced details.
  Synthetic VPN/proxy DNS receives an explicit explanation.
- Gateway actions stack at compact widths, status labels and values use separate lines, log
  actions wrap cleanly, and filter chips use a horizontal lazy list.

## Verification

- 1,428 JVM tests, 0 failures and 0 errors.
- `assembleDebug` passed.
- `compileDebugAndroidTestKotlin` passed.
- `lintDebug` passed with 0 errors.
- `git diff --check` passed.

The v18 migration is verified the way the v16 migration is: `MigrationToV18SqlTest` builds a
real v17 shape from `17.json`, runs the real `UPGRADE_TO_V18_SQL`, and compares SQLite's own
`PRAGMA table_info` against a table built from `18.json`, so the hand-written SQL must produce
exactly the schema Room generates. It also asserts an existing dead-letter row survives with
every new column NULL, that only `ADD COLUMN` statements are used, and that the summary
aggregate over zero rows reads as 0 rather than crashing on a clean install.

The dead-letter rule's live-degradation branches are pinned separately: a stale queued upload,
a queue that was never attempted, and the uploader's own consecutive-failure count each degrade
on their own, while an upload that is exactly at the freshness boundary does not.

Responsive instrumentation covers 320, 360, 393, and 412 dp at font scales 1.0, 1.15, 1.3,
and 1.5. The suite compiled successfully; this build environment had no connected Android
device or installed emulator to execute it.
