# Release v2.1.5 — millisecond-live sync: no more debounce lag

## Fixed

Conversations and the Home list felt slow and "hung" even though the data was
already in the provider. Three independent sources of delay were removed.

### 1. The observer added 300 ms to EVERY message

`SmsContentObserver` was trailing-only: every provider change went through a
fixed `postDelayed(300ms)` before the UI heard about it. Incoming SMS, outgoing
sends, delivery-status updates — all paid that toll.

Now it is **leading-edge**: the first change dispatches immediately (no delay at
all), and further changes inside a 150 ms window collapse into one trailing
reconcile so multipart SMS / MMS parts still end with a final pass.

### 2. The conversation list re-queried the address tables on every refresh

`getConversationsFast()` called `loadCanonicalAddresses()` (full
`content://mms-sms/canonical-addresses` scan) on **every** refresh, plus one
extra per-thread `resolveSmsAddressForThread` query for each thread with blank
recipient ids. On a phone with many threads this dominated refresh time — that
was the perceived "hang" when returning to the list.

Both are now cached process-wide, and `SmsReceiver` drops the caches when a
message arrives from a sender not seen before, so a brand-new number still
resolves instead of rendering as "Unknown".

### 3. A refresh arriving during a refresh was silently dropped

`ConversationViewModel.refresh()` returned early when one was already in
flight, so the LAST notification of a burst could be lost — the classic "close
and reopen and a few more messages appear". It is now re-entrant: a request
during a run marks the pass dirty and exactly one more reconcile runs on
completion.

### Also

- Chat-open spinner is delayed 120 ms and cancelled if the windowed read beats
  it, so short reads go straight from nothing to messages instead of flashing a
  loader.
- Live merges (refresh + incoming SMS while the chat is open) now write through
  to `ThreadMessageCache`, so leaving and re-entering paints the exact same list
  with zero reload.

Unit tests: new `SmsObserverTimingTest` (2 cases) pins the leading-edge timing
contract and burst collapsing.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.1.4...v2.1.5
