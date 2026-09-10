# PR-05 + PR-06 — Conversation Visual Modernization + Motion Adoption

> Status: **applied.** Repo: `aibedini/Messages` · after PR-04 (`60f4331`)

## PR-05 — Conversation visual modernization (RFP §17)

- **Message bubble max-width** now `fillMaxWidth(0.82f)` (was a hardcoded
  `max = 300.dp`). This matches RFP §17's ~78-82% guideline and scales
  correctly on wide/landscape screens; `min = 80.dp` retained so short bubbles
  stay compact. Padding `14×10dp`, restrained corners/shadows, no gradients,
  no blur — unchanged (already compliant).
- Spacing tokenized in `MessageList`: list `contentPadding` and row `spacedBy`
  now use `MessagesSpacing` (`Md+2dp` horizontal / `Sm` vertical; `Xs` gap),
  replacing raw dp literals. Same effective values — no visual regression.

## PR-06 — Motion & interaction polish (RFP §20)

- **Adopted `MessagesMotion.Fast`** (120ms FastOutSlowIn) for the Jump-to-latest
  button's fade in/out — replaces inline `tween(140/100)`. The scale-in/out
  spring (500f) is kept as-is (interaction feel unchanged).
- The **list placement spec** (`spring(damping=1, stiffness=550)`, critical
  damped, placement-only) is confirmed as the canonical placement motion. It
  stays inline in `MessageList` because `Modifier.animateItem`'s placementSpec
  is typed `FiniteAnimationSpec<IntOffset>`, which the `Float`-typed
  `MessagesMotion.ListPlacement` token cannot satisfy directly. Documented here
  so a future typed placement token can supersede it without behavior change.
- No decorative animation was added; incoming SMS never animates the whole list
  (live-only `MessageEntrance` preserved).

## Invariants (RFP §20/§43)
- Messages never bounce (critical-damped placement retained).
- No whole-list recomposition introduced; motion is per-item / overlay only.
- No data-architecture / gateway change. No new dependency.

## Gates
- `testDebugUnitTest`: green
- `assembleDebug`: green

## Next
PR-07 (data/UI perf) requires benchmark evidence first — blocked until PR-08
harness + device. PR-08 (macrobenchmark/baseline/360k) and PR-09 (RTL/a11y)
need a physical Pixel-class device (not present this session).
