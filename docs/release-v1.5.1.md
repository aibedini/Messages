# Messages v1.5.1 — delete-undo crash fix, archive undo

This patch release fixes a crash in the delete/undo flow and completes undo coverage for archiving.

## Fixes

- **Crash when tapping Undo after deleting** — three stacked issues resolved:
  - Conversations are now removed by `threadId` instead of data-class equality; observer reloads create fresh instances (with updated status fields), which made the old equality-based removal a silent no-op.
  - Threads with a pending delete are excluded from observer-triggered list rebuilds during the grace window. Previously any DB change (new message, mark-read…) re-inserted the row while its snackbar was open, and a subsequent Undo added a second copy — duplicate LazyColumn keys → `IllegalArgumentException` crash.
  - Pressing Undo after the permanent delete already committed no longer resurrects a ghost conversation; the list resyncs from the provider instead.
- **Archive now has Undo**: the archive/unarchive snackbar offers an Undo action that performs the inverse operation.
- Every re-insert path is guarded by a dedup helper, so duplicate rows can never re-enter the list.

The APK is signed with the project's existing release key.
