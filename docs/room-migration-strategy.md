# Room migration strategy (messages.db)

## Current state

`MessagesDatabase` is at **version 2**:

| Version | Change | Migration shipped |
|---------|--------|-------------------|
| 1 | Initial schema (`messages`, `conversations`, `sync_state`) | — |
| 2 | `conversations.rawAddress` column added | **No — destructive fallback** |

## Why v1 → v2 has no real Migration

The database is a **shadow / read-cache**: the system Telephony provider remains
the durable source of truth, and `TelephonySyncCoordinator` can rebuild the
entire shadow from scratch in seconds (first sync mirrors the newest 100 rows
per source, then backfills history). A pre-2.4.0 install that upgrades wipes
the shadow once and silently re-mirrors it. The user loses nothing.

`fallbackToDestructiveMigration(dropAllTables = true)` is therefore **intentional
for now** — but it must be removed before the shadow becomes authoritative for
anything not recoverable from the provider (e.g. app-only flags if they move
into Room).

## Rules going forward

1. **Every schema change bumps the version** and adds a real `Migration`
   object wired into `.addMigrations(...)`.
2. **Never edit an exported schema JSON** — they are build artifacts of
   history (`app/schemas/<db>/<version>.json`) and are committed to git.
3. **Migration test**: when a destructive fallback is no longer acceptable,
   add `androidx.room:room-testing` and a `MigrationTestHelper` instrumented
   test that validates each vN → vN+1 path against the exported schemas.
4. Before shipping a schema change, run a debug build over an installed
   previous version on device/emulator and confirm the upgrade path does not
   wipe or corrupt data.
