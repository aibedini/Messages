# PR-00 — Baseline & Audit (UI Modernization)

> Status: **Baseline recorded — no visual redesign, no data-architecture change.**
> Repo: `aibedini/Messages` · Branch: `master` · HEAD: `3e61ddd` (v3.1.0)
> Generated docs (`openwiki/`) are secondary; source + tests authoritative.

## 1. Architecture inventory (current)

```
Telephony Provider (durable truth)
        │
        ▼
TelephonySyncCoordinator (single writer) + ChangeRouter + SmsEventBus (nudge)
        │
        ▼
Room Read Shadow
        │
        ▼
HomeViewModel / ConversationViewModel (StateFlow + snapshot list state)
        │
        ▼
Jetpack Compose (Material 3) UI
```

Realtime acceleration via `SmsEventBus` → optimistic Home/Conversation update, then
Room/provider authoritative reconciliation. **Preserve — this matches RFP §5/§49.**

### ThreadPager / windowed history (settled, must NOT change — RFP §6)
- `repository/ThreadPager.kt`: per-thread paged cursor, PAGE=40/source, newest N SMS
  + newest N MMS DESC, date-interleaved, handed reversed (ASC). `loadOlder()` pulls
  the next page; `loadNewerSince(date)` refreshes the tail.
- `ThreadMessageCache` stores loaded pages; own sends `append(...)` (no generation
  bump) so re-open paints cache instantly.
- `ConversationViewModel.loadOlderMessages()` inserts at index 0 on Main; the screen
  arms it when `canScrollBackward` goes false at top (400ms settle latch).
- OFFSET paging over live provider is documented-acceptable (append-mostly); keyset
  is the fallback if exactness ever matters.

## 2. UI package structure (current)

```
ui/
├── components/   Avatar, ChatBubble, ContactItem, EmptyView, SearchBar,
│                 SmsItem, TopBar
├── conversation/ ConversationListMapper (ChatListItem sealed: MessageItem/
│                 DateSeparator + buildReverseChatItems + chatItemKey),
│                 MessageEntrance (live-only bubble enter)
├── screens/      HomeScreen, ConversationScreen, + 13 other screens
└── theme/        Color, Shape, Theme, ThemeController, ThemePresets, Type
```

### Gaps vs RFP §7/§20
- **No `ui/design/` package.** No `MessagesColors/MessagesTypography/MessagesShapes/
  MessagesSpacing/MessagesMotion/MessagesTheme`. Theme tokens are split across
  `Color.kt`, `Shape.kt`, `Type.kt` with no centralized token objects.
- **No motion system** — `animateItem` (placement-only spring 550) and
  `MessageEntrance` exist inline in `ConversationScreen.kt`, but there is no
  central `MessagesMotion` object with Fast/Normal/Emphasized/ListPlacement/
  ContentChange tokens (RFP §20).
- `HomeScreen.kt` = **797 lines**, `ConversationScreen.kt` = **1559 lines**
  (monolithic). Componentization (RFP §11/§15) is the main refactor surface.

## 3. Home list — already-good parts (v2.6.x, do NOT regress)
- LazyColumn with stable keys: `key = { "c${it.id}" }`, `contentType = "conversation"`.
  Global-search branch uses `"global_${it.sms.id}"` + `"direct_send"`/`"result_count"`/
  `"global_header"` namespaced item keys. (RFP §12 satisfied.)
- Atomic list swap via `Snapshot.withMutableSnapshot { clear(); addAll(source) }`
  inside `HomeViewModel.applySwap` — one before→after state change, keyed rows keep
  identity. No full-list fade. (RFP §13/§14, and skill `windowed-history-and-list-perf`.)
- SmsEventBus optimistic update inserts `incomingSms` at index 0 + sortByPin, so an
  incoming message reorders the correct row immediately; Room reconciles after.
  (RFP §13.)

## 4. Conversation list — already-good parts (v2.6.7/v2.6.9, do NOT regress)
- `reverseLayout = true`; data order newest→oldest (`buildReverseChatItems`);
  index 0 = newest paints at the visual bottom. Opening lands on the latest message.
- Stable namespaced keys via `chatItemKey`: `date_${dayKey}` for separators,
  `msg_${id}_${date}_${type}` for messages (RFP §16 satisfied).
- `contentType` branches on `ChatListItem` type (message vs date).
- Motion split: `animateItem` is placement-only (critical-damped, no bounce);
  genuinely new bubbles use `MessageEntrance` (rise 10dp/190ms + scale spring
  anchored at bubble corner + alpha). Hydration = no per-message animation.
- First-paint: `ConversationLaunchStore` one-frame snapshot handoff + crossfade;
  cold start uses `QuietConversationSkeleton` (no blank→POP).
- Composer collapse via `animateContentSize(160ms)`.

## 5. Dependency / toolchain inventory (RFP §35 baseline)

| component | version |
|---|---|
| AGP | 8.10.1 |
| Kotlin | 2.2.10 |
| Compose BOM | 2026.02.01 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| Room | 2.8.4 |
| Navigation Compose | 2.9.3 |
| lifecycle-runtime-compose | 2.9.4 |
| material-icons-extended | via BOM |
| coil-compose | 2.7.0 |
| kotlinx-coroutines-play-services | 1.9.0 |

**Constraint (RFP §35):** UI redesign works on THIS toolchain. No AGP/Kotlin/
compileSdk/Compose-BOM upgrade inside a UI PR. Any dependency upgrade is a separate
proposal PR with old/new/breaking/benefit/benchmark/APK sections.

## 6. Benchmark / measurement gap (RFP §28–§33)

- **No `:macrobenchmark` module, no `:baselineprofile` module, no Compose UI-test
  suite.** Single `:app` module only.
- `androidTest/` has device tests (GatewayDurability, MessageSyncE2E, PairingProtocol,
  EncryptedHistory, KeyGrantsOnApproval, TrustPublication, ConversationKeyMigration,
  ExampleInstrumented) — but **no Macrobenchmark and no Compose UI tests**.
- **No 360k synthetic dataset generator.** RFP §27/§30 requires a deterministic
  synthetic fixture (360k SMS/MMS, realistic distribution, fake data only) and
  explicitly forbids a 360k-element Compose list.
- **No recorded performance baseline** (jank%, P95 frame CPU, incoming/outgoing P95,
  conversation-open P95, search P95) on a Pixel-class device. RFP §29 budgets:
  Home/Conversation scroll jank ≤1.5% & P95 frame ≤8ms; incoming/outgoing ≤150ms;
  conversation-open ≤250ms; search ≤250ms.

> PR-08 builds the benchmark + baseline-profile + 360k dataset. PR-00 records that
> the baseline is currently **unmeasured** — first measurable step is standing up the
> benchmark tooling, then PR-07 (data/perf) proceeds only on that evidence.

## 7. Known bottlenecks / risk (from static inspection — to be confirmed by PR-08)

1. `HomeViewModel.conversations = mutableStateListOf<Sms>()` holds the **domain model
   `Sms`** directly in UI state; no dedicated immutable `ConversationUiModel` (RFP §9).
   `contactNames` is a `mutableStateOf<Map<String,String>>` resolved in a coroutine and
   swapped wholesale — candidates for keyed reconciliation / `distinctUntilChanged`.
2. `ConversationScreen.kt` (1559 lines) mixes layout, logic, dialogs, MMS actions —
   componentization risk. Bubble grouping / date-separator insertion must not
   destabilize keys (RFP §17 last bullet).
3. `conversationDao().observeAll()` + full mapping (RFP §14) — currently unproven;
   do NOT redesign on theory. Benchmark first; optimize only if allocation/jank is
   proven (better immutable models, `distinctUntilChanged`, keyed reconciliation).
4. Search path (`matchesSearch` over `contactNames` map + `rawList`) does provider
   queries; verify no per-row contact lookup during composition (RFP §8/§31).
5. Typography is split per-style in `Type.kt` with hardcoded `FontWeight.Default` /
   no `FontFamily` branding decision — normalize in PR-01.

## 8. Code that MUST NOT change (RFP §26, §48, DoD-Architecture)

- **TelephonySyncCoordinator / ChangeRouter / SmsEventBus / Telephony Provider /
  Room shadow** — preserve the realtime path; SmsEventBus stays a nudge, never
  authoritative.
- **ThreadPager / ThreadMerge / ThreadMessageCache / ConversationCache** — windowed
  keyset paging stays; never full-thread load, never OFFSET→360k, no Paging 3 without
  evidence.
- **Gateway**: GatewayService, GatewayServer, EventUploader, SecureCommandPoller,
  GatewayOutgoingPipeline, gateway crypto, pairing security, web sync protocol — a UI
  change must not alter protocol semantics.
- **No third-party UI framework, no Compose→View migration, no second message DB.**

## 9. Deliverable plan mapped to PRs (from this audit)

| PR | Scope | Depends on |
|---|---|---|
| PR-00 | this audit + 360k dataset strategy + benchmark baseline | — |
| PR-01 | `ui/design/` tokens (Colors/Typography/Shapes/Spacing/Motion/Theme) | PR-00 |
| PR-02 | Home componentization + stable keys review + Compose UI tests | PR-01 |
| PR-03 | Home visual modernization + before/after benchmark | PR-02 |
| PR-04 | Conversation componentization | PR-01 |
| PR-05 | Conversation visual modernization | PR-04 |
| PR-06 | Motion & interaction polish (MessagesMotion) | PR-01 |
| PR-07 | Data/UI perf **only after** benchmark evidence | PR-08 tooling |
| PR-08 | Macrobenchmark + Baseline Profile + 360k synthetic dataset | — |
| PR-09 | RTL / Accessibility / final polish | PR-03, PR-05 |

Execution per RFP §50: inspect → measure → change narrowly → test → benchmark → report.

## 10. 360k synthetic dataset strategy (PR-08; design decided now)

Deterministic fixture, **fake data only** (RFP §25/§27). Injected via a new
`:benchmark`/`:macrobenchmark` module's `Rule` that writes into the Room shadow +
provider mirror directly — **never** through a 360k-element Compose list.

Distribution (deterministic, seeded RNG):
- ~3,000 conversations; a handful of very large threads (10k–40k), many short (1–3),
  the rest spread.
- Mix: sent/received, MMS, drafts, unread, pinned, archived, Persian + English text,
  emoji, long (multi-segment) messages.
- Fake contacts map (fake names ↔ fake normalized numbers) resolved **once** in the
  ViewModel, never per-row in composition.

Invariants:
- Home projection queries remain bounded (conversation count, not 360k).
- Conversation windows stay PAGE-bounded via ThreadPager (never full-thread).
- Baseline Profile + Macrobenchmark journeys: cold launch, Home first paint, Home
  scroll, open conversation, Conversation scroll, back, search, compose/send boundary.

> PR-08 stands up the module + generator + benchmark. This strategy is the agreed
> spec so implementation is deterministic, not ad-hoc.

