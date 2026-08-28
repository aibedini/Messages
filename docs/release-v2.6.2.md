# Release v2.6.2 — P0 migration fix: DB v2 upgrade crashed at open (sync_state)

## Root cause

The `v2 → v3` migration (shipped in v2.6.0) only `ADD COLUMN`ed `sync_state`
without migrating the old `newestSyncedDate` column to `newestDate`, and left
the three legacy columns behind. A device that still had a **version 2** DB
therefore opened a `sync_state` missing `newestDate` — Room's schema validation
failed and the process crashed **before the message list could load**:

```text
Home starts → MessagesDatabase.get() → migration → validation fails → crash
```

This matches the reported symptom: app opens → exits immediately → no SMS list.

## Fix

`sync_state` is sync bookkeeping only (no business data), so it is rebuilt
instead of patched with `ALTER`:

- **New `MIGRATION_2_4`** — a direct `2 → 4` path: drop + recreate `sync_state`
  with the correct v4 columns, create the Room-managed indexes, and create the
  FTS table + triggers. Old users never touch the broken historical `2 → 3`.
- **`MIGRATION_3_4`** now also rebuilds `sync_state`, so DBs that already went
  through the broken `2 → 3` (missing `newestDate`) are repaired too.
- Removed the broken `MIGRATION_2_3` from the builder.

After migration `sync_state` is empty and the coordinator simply re-runs its
initial sync from the Telephony provider.

## Fail-safe (defense in depth)

`HomeViewModel` no longer crashes the app when the read-model DB cannot be
opened. If Room startup fails, the app logs, disables the read-cutover and
falls back to the provider path — the Telephony provider remains the source of
truth, so a corrupt shadow must never terminate the whole SMS app.

## Tests

Migration SQL is now locked to the KSP-generated `4.json` for **both** upgrade
paths (`MigrationToV4SqlTest`, 5 tests): every CREATE statement matches the
generated schema, `sync_state` is rebuilt (no leftover `newestSyncedDate`), the
legacy indexes are dropped, and `MIGRATION_2_4` + `MIGRATION_3_4` both target
v4.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.6.1...v2.6.2
