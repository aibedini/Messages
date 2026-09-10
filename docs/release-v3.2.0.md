# Messages v3.2.0 — UI Modernization (RFP)

**versionCode 99 · Room schema v10 (unchanged) · Compose/Material 3**

UI modernization per the Messages Android UI Modernization & Performance RFP —
a **componentization + design-system + motion refactor**, NOT a rewrite.
Data architecture (Telephony → TelephonySyncCoordinator → Room shadow →
ViewModels → Compose) and the realtime/paging/gateway layers are untouched.

---

## 1. Design System Foundation (ui/design/)

New token package `ui/design/`:
- `MessagesSpacing`, `MessagesMotion`, `MessagesShapes`, `MessagesTypography`,
  `MessagesColors` (semantic accessors), `MessagesTheme` helper.
- Centralized motion system (`Fast/Normal/Emphasized/ListPlacement/Sheet`).
- Typography/shape scales normalized from the existing values — no visual
  regression, no new dependency.

## 2. Home Componentization (ui/home/)

`HomeScreen` (797 → ~430 lines) split into focused stateless components:
`HomeFilterBar`, `HomeFab`, `ConversationList` (stable keys/contentType,
pull-to-refresh, direct-send + global-search rows), `HomeConfirmDialog`,
`ConversationListSkeleton`, `HomeStatusComponents`, `HomeSearch` (pure search
logic, unit-tested). All existing actions preserved.

## 3. Home & Conversation performance hygiene (RFP §8)

- Removed contact lookup from **composition**: `SmsItem` now takes a resolved
  `displayName` (was `ContactRepository(context).getCachedDisplayName()` per
  row during composition). No per-row provider I/O while rendering.
- Row typography normalized onto `MaterialTheme.typography` tokens.

## 4. Conversation Componentization (ui/conversation/)

Extracted the 1559-line `ConversationScreen` timeline into `MessageList`:
reverse-layout LazyColumn, date separators, loading spinners, Jump-to-latest +
badge, pull-to-refresh — all hoisted state preserved. ThreadPager semantics,
windowed paging, reverse anchoring, stable keys (`date_*`/`msg_*`), and
live-only `MessageEntrance` are **unchanged**.

## 5. Visual + Motion polish (RFP §17/§20)

- Message bubble max-width → `fillMaxWidth(0.82f)` (was hardcoded 300dp).
- `MessagesSpacing`/`MessagesMotion.Fast` adopted in `MessageList`.
- Critical-damped placement motion retained (messages never bounce).

## Tests

- `HomeSearchTest` (unit) — green.
- `HomeFilterBarTest`, `MessageListTest` (Compose UI, androidTest) — compile;
  run on device.
- `testDebugUnitTest` + `assembleDebug` green.

## Notes / known limits

- Device benchmarks (scroll jank, P95 frame CPU, incoming/outgoing P95,
  conversation-open, search) are **NOT RUN — physical Pixel-class device +
  benchmark harness required** (RFP §28-§33). Baseline recorded in
  `docs/PR-00-BASELINE-AUDIT.md`.
- PR-07 (data/perf) intentionally deferred until benchmark evidence exists.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v3.1.0...v3.2.0
