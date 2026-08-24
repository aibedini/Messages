# Release v2.0.3 — instant chat opening (stale-while-revalidate cache)

## What changed

Opening any conversation showed a "Reading messages…" spinner while the
provider was queried. For fast typing/sending flows this felt sluggish.

### The engineering fix — the same pattern Google Messages uses

**Stale-while-revalidate (SWR) thread cache:**

1. **First paint from memory** — `ThreadMessageCache` (LRU, last 24 threads,
   up to 400 messages each) returns the thread's last-known messages in
   ~0 ms. The chat renders instantly; no spinner.
2. **Revalidate in background** — if anything changed since the cache was
   written (generation counter bumped by any SMS/MMS provider change, any
   send, or any read-state change), a fresh query runs and atomically swaps
   in. If nothing changed (the common case), the load finishes right there.
3. **Conservative invalidation** — generation bumps on: ContentObserver
   events, our own sends (optimistic path), mark-as-read, restores. Stale
   data is never shown twice.

Cold start (no cache yet) still shows progress once; every subsequent open
of that conversation is instant.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.0.2...v2.0.3
