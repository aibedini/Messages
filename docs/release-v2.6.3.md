# Release v2.6.3 — Sync P0 hardening + Gateway self-healing supervisor

Two P0 areas in one release: (1) the sync pipeline could wipe the UI on
update, block Home on hundreds of thousands of history rows, and mis-track
watermarks; (2) the gateway recovered from network drops only by luck — a
heartbeat stuck in a 5-minute backoff did not retry when WiFi returned, a LAN
server kept its dead DHCP binding, the poller burned HTTP against a dead
radio, and a phone reboot required manually re-toggling the gateway.

## Sync P0 fixes

### 1. Data-preserving `sync_state` migration (no more full rescan)
v2.6.2 rebuilt `sync_state` by DROP + create — correct but lossy: every
upgrade threw away watermarks and forced a full provider rescan. v2.6.3
detects the actual table shape (`PRAGMA table_info`) and migrates values:

- v4-shaped → no-op
- v2 (`newestSyncedDate`/`backfillComplete`) → column-mapped copy;
  `backfillComplete=1` seeds `oldest=0` so history is not re-crawled
- v3 hybrid → shared columns copied, missing ones `ALTER`-added
- unknown shape → the old drop+create (last resort only)

Proven with a sqlite3 simulation over 7 scenarios + rewritten
`MigrationToV4SqlTest` asserting data survival (no unconditional DROP).

### 2. Read-cutover never shows an empty list
`initialWindowReady` was set *inside* `syncSource` — before
`fullRebuildConversations()` populated the projection. Home could switch its
read path to a Room table that was momentarily empty. The flag is now flipped
only after the rebuild completes, sequentially per source.

### 3. Keyset (watermark) backfill with durable resume
The OFFSET-based crawl (`LIMIT n OFFSET m`) re-read and mis-skipped rows as
provider contents shifted, and an interrupted crawl restarted from zero.
Readers are now pure keyset:

```sql
WHERE date < :beforeDate OR (date = :beforeDate AND _id < :beforeId)
ORDER BY date DESC, _id DESC LIMIT :batch
```

The cursor is persisted *before* each batch is yielded, so a kill resumes
exactly where it stopped. `readNewerThan` is keyset too (a message stamped
exactly at the watermark used to be skipped forever). MMS seconds-vs-millis
and the negative-id provider encoding (`providerId = abs(id)`) are handled in
the shared keyset helpers.

### 4. Watermark regression guard (the copy-overwrite bug)
Watermark advances used read-modify-write of the whole `sync_state` row; a
stale read could copy an older `newestDate` back over a newer one. `advanceNewest`
is now a single guarded UPDATE:

```sql
UPDATE sync_state SET newestDate=:date, newestId=:id, lastReconcileAt=:now
WHERE source=:source AND (newestDate < :date OR (newestDate = :date AND newestId < :id))
```

— monotonic by construction.

### 5. `syncNow()` no longer awaits the whole history
The 360k-row backfill was inline on the Home path (fire-and-hope). It now runs
detached on the coordinator's own scope with a per-source guard; Home syncs the
initial window and returns. The inline first-contact backfill call was removed
as well — `scheduleBackfill` owns it.

### 6. Full MMS scan removed from startup paths
- `getConversations` fallback: merged only the **newest** MMS rows
  (`DATE DESC LIMIT 400` at the provider) instead of the entire MMS table.
- `getMessagesByPhone` fallback: derives the thread ids from the SMS rows just
  read and filters MMS **at the provider** (`THREAD_ID IN (…)`); the old path
  scanned every MMS row into memory and filtered client-side.
- Thread view MMS merge is explicitly thread-scoped.

## Other correctness fixes

- **MMS source dispatch**: `IncomingMessageDispatcher.dispatch()` took a
  hardcoded `source = SOURCE_SMS`, so an inbound MMS upserted the shadow under
  the wrong source (and reconciles then re-read the SMS table for it).
  `MmsReceiver` now passes `SOURCE_MMS`; the SMS call sites keep `SOURCE_SMS`.
- **ChangeRouter** classified rows by `uri.path?.contains("mms")` — true for
  SMS URIs containing "mms" substrings in unrelated path segments. Now keyed on
  the URI **authority** (`content://mms/…`), which is what identifies the table.
- **UnreadDelta**: the unread→read transition returned 0 (double-guarded early
  return); the counter never decremented and badges drifted upward. Rewritten
  as a full truth table; `UnreadDeltaTest` rewritten and green.
- **ThreadPager**: a single shared keyset cursor drove both the SMS and MMS
  queries; after a merge the cursor from one source was applied to the other,
  silently skipping rows. Per-source cursors now advance independently.

## Gateway self-healing (ConnectionSupervisor)

New architecture: one **reconcile loop over a declarative desired state**
replaces the scattered start/retry code paths.

```
state = f(desiredEnabled, hasConsent, online, serverIsUp, boundIp == currentIp)
```

Every input change (user toggle, consent, network callback, IP shift) nudges a
CONFLATED channel; the loop re-derives everything and acts idempotently.

- **`NetworkMonitor`** — single source of truth for "usable network": a
  CONNECTED network that is INTERNET **and VALIDATED** (captive portals and
  dead radios don't wake anything). Event-driven `onlineFlow()`, zero polling.
- **`ConnectionSupervisor`** — states
  `DISABLED / WAITING_FOR_NETWORK / CONNECTING / CONNECTED / RECONNECTING / ERROR`.
  Offline is WAITING, never ERROR. A failed bind retries with exponential
  backoff on the loop itself (5s → 2 min cap).
- **Rebind on IP change** — the reconcile compares `boundIp` against the
  current LAN address every 10s and on every nudge; a DHCP/network switch
  replaces the (non-restartable) `GatewayServer` instance transparently.
- **`retryNow()` on network return** — cancels the heartbeat's backoff
  mid-ladder (up to 5 minutes saved) and wakes the poller immediately.
  `HeartbeatManager`'s sleeps are now interruptible (`withTimeoutOrNull` +
  wake channel); `OutboxPoller` gates on the monitor and makes **zero HTTP
  requests while offline**, then suspends for the online flip.
- **Intent vs runtime split** — `gatewayDesiredEnabled` (persisted user
  intent) is separate from `isEnabled` (runtime gate, written only by the
  supervisor). The old "stop path wipes isEnabled, start path sets it"
  race is gone; ACTION_STOP and teardown no longer clobber intent.
- **Reboot recovery** — `BootGatewayReceiver` (BOOT_COMPLETED /
  LOCKED_BOOT_COMPLETED, `RECEIVE_BOOT_COMPLETED` permission) replays
  ACTION_START when the user left the gateway on; consent is re-checked,
  START_STICKY's null intent lands on the same reconcile path.
- **UI** — the status card dot/label reflects the live supervisor state
  (amber while waiting/reconnecting), the switch tracks user intent so it
  doesn't fight a WiFi blip, and `reconnectNow()` routes through the
  supervisor (heartbeat cancel + rebind + poller wake) in addition to the
  direct cloud re-register.

## Verification

- `compileDebugKotlin` / `compileDebugUnitTestKotlin` — clean
- `testDebugUnitTest` (19 classes incl. `MigrationToV4SqlTest`,
  `UnreadDeltaTest`, `GatewayAccessPolicyTest`) — all green
- `assembleDebug` — BUILD SUCCESSFUL
- Migration SQL exercised against real sqlite3 in 7 table-shape scenarios

## Files

New: `gateway/ConnectionSupervisor.kt`, `gateway/NetworkMonitor.kt`,
`receiver/BootGatewayReceiver.kt`
Changed: sync coordinator/DAOs/database, `SmsRepository`, `ThreadPager`,
`ChangeRouter`, `UnreadDelta`, `IncomingMessageDispatcher`, `MmsReceiver`,
gateway service/prefs/heartbeat/poller, `GatewayViewModel`, `GatewayScreen`,
manifest, version 2.6.2→2.6.3 (code 44→45)
