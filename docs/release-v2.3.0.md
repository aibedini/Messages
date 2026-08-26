# Release v2.3.0 — share-intent receive, race-free conversations, Room foundation

## Added

### The app now appears in the Android share sheet ("Open with")

Until now sharing text from any app never offered Messages: ACTION_SEND and
ACTION_SENDTO lived in ONE intent-filter whose data section only declared sms/
smsto/mms/mmsto schemes — no mimeType — so plain-text shares could not match.
Filters are now split per the official receiving-simple-data guidance:

- `SENDTO` keeps the scheme-only filter (sms:, smsto:, mms:, mmsto: links).
- `SEND` gets its own filter with `mimeType="text/plain"`.

Full delivery path, cold and warm:

- `MainActivity` parses the launching intent in `onCreate` (cold start) and
  `onNewIntent` (share while running); `launchMode="singleTask"` routes shares
  into the existing task.
- New pure `IncomingShareParser` handles `sms:`/`smsto:` URIs with `?body=`,
  `sms_body=` and EXTRA_TEXT fallbacks, and percent-decodes WITHOUT turning
  '+' into a space so `+98…` numbers survive. Covered by 6 new unit tests.
- `AppNavigation` consumes the payload exactly once (rotation-safe) and opens
  New Conversation with the recipient search pre-filled.

**Shared text is a DRAFT, never auto-sent.** Two separate concepts now exist:
`forwardText` (internal forward — existing auto-send behavior unchanged) and
`draftText` (external share — seeds the composer; the user presses Send). A
shared message can therefore never leave the device without an explicit tap.

### Room read-SSOT foundation (shadow layer)

First step of the phase-2 architecture, wired as a shadow so UI behavior is
unchanged while the local store fills up:

- **Room schema v1** (`messages.db`, schema exported): `messages` keyed by the
  composite `(source, providerId)` — SMS and MMS id sequences overlap, so ids
  alone are not unique — with `(threadId, date, providerId)`,
  `(normalizedAddress, date)` and `date` indices; dates normalized to millis
  at sync time (MMS rows arrive in seconds). `conversations` projection and
  per-source `sync_state`.
- **`TelephonySyncCoordinator`: THE single writer.** Receivers and observers
  only nudge a CONFLATED channel — N queued nudges collapse into one sync
  cycle on one IO loop, making parallel provider scans structurally
  impossible. First sync mirrors the newest 100 rows per source newest-first,
  then backfills history batch-by-batch (200/batch); steady-state syncs pull
  only rows newer than the last mirrored date. Conversation projections
  rebuild from the messages table after every cycle.
- Gradle: KSP `2.2.10-2.0.2`, Room `2.8.4`.

## Fixed

All four conversation-load bugs found in review (commits 982adbb, 984bf24):

1. **Cache-hit re-open built no pager** — scroll-up history and tail refresh
   silently did nothing on every re-open of a cached chat. The pager is now
   constructed before the early return.
2. **Phone-only pager kept querying `THREAD_ID = 0`** after the real thread id
   was resolved, so live refresh missed new messages. The pager now rebuilds
   itself on the resolved thread.
3. **Every provider change dispatched twice** (`ContentObserver.onChange(self,
   uri)` delegating through `super` back into `onChange(self)`), doubling all
   refresh work.
4. **Conversation switch races**: one monotonic `conversationGeneration` stamp
   now guards the initial load, older-page loads, refresh apply and the shared
   spinner guard — a superseded load can neither paint over the newly opened
   thread nor cancel its spinner. `loadOlderMessages` calls are serialized.

Also: `replay=1` removed from the incoming-SMS SharedFlow (new collectors no
longer re-receive the last message); `MmsReceiver` is `exported=false`; phone
matching consolidated into ONE `sameConversation()` predicate with a ≥7-digit
minimum for suffix matching (short fragments like "12" can no longer falsely
join conversations) — used everywhere: Home list, open-chat routing,
blocklist, mark-read; CI now runs `testDebugUnitTest`.

## Verification

- `assembleDebug` ✓ · `testDebugUnitTest` ✓ (85 tests, green)
- Share-sheet appearance and draft-only send verified by unit tests for the
  parser; end-to-end share flow needs a device test.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.2.0...v2.3.0
