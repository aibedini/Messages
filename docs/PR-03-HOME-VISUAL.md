# PR-03 — Home Visual Modernization

> Status: **applied (code + audit). Device benchmark deferred to PR-08.**
> Repo: `aibedini/Messages` · after PR-02 (`25cd053`)

## What changed

### 1. Removed contact lookup from composition (RFP §8)
`SmsItem` (the conversation row) previously did `ContactRepository(context)
.getCachedDisplayName(sms.sender)` **during composition** — a cached provider
read per visible row. That is exactly the anti-pattern RFP §8 forbids.

Now `SmsItem` takes a resolved `displayName: String` parameter. The caller
(`HomeScreen` → `HomeRow`) resolves the name once from `viewModel.contactNames`
(a preloaded map) when building the row model. Composition does zero provider
I/O. Applied to both normal rows and global-search hit rows.

### 2. Typography normalization on the row (RFP §10)
Hardcoded `fontSize`/sp on the conversation row migrated onto
`MaterialTheme.typography` tokens so the hierarchy is single-sourced:
- name `16sp` → `titleMedium`
- snippet `14sp` → `bodyMedium`
- draft `14sp` → `bodyMedium` (+ `labelMedium` for the "Draft:" prefix)
- timestamp `12sp` → `labelMedium`
- "you" marker `10sp` → `labelSmall`

All tokens were defined in PR-01 (`ui/design/MessagesTypography`) with the same
sizes, so **no visual regression** is expected. Swipe background copy (`14sp`,
already intentional bold white on color) left as-is.

### 3. Visual-language confirmation (audit, not change)
The Home list already targets Google Messages style and was NOT card-ified:
whitespace + avatar + typography hierarchy + unread dot on `primaryContainer
@ 12%`, restrained color, no per-conversation elevated cards (RFP §10). Avatar
uses the existing gradient set. No change needed there.

## Baseline (recorded — device required to complete)

Per RFP §29, a device benchmark is the definition of "done" for performance.
This PR records the **baseline state**; actual numbers require the PR-08
benchmark harness + a Pixel-class device (not present in this session).

| journey | budget (RFP §29) | status |
|---|---|---|
| Home scroll jank% | ≤1.5% | NOT MEASURED (needs device) |
| Home P95 frame CPU | ≤8 ms | NOT MEASURED (needs device) |
| incoming foreground SMS P95 | ≤150 ms | NOT MEASURED |
| conversation-open P95 | ≤250 ms | NOT MEASURED |

Architecture-level perf mitigations already present (from PR-00 audit): atomic
`Snapshot.withMutableSnapshot` swaps, stable keys/contentType, windowed
ThreadPager, live-only entrance animation. These remain intact.

## Data-architecture & gateway isolation (RFP §26/§48)
No change to TelephonySyncCoordinator, SmsEventBus, Room, ThreadPager, or any
gateway component. No new dependency. No OpenWiki edit.

## Gates
- `testDebugUnitTest`: green (HomeSearchTest passes)
- `assembleDebug`: green
- `compileDebugAndroidTestKotlin`: green (HomeFilterBarTest compiles)
- Compose UI test `HomeFilterBarTest` runs on device (PR-02)

## Next
PR-04 (Conversation componentization) then PR-05 (Conversation visual).
Device benchmark executes once PR-08 harness + physical device are available.
