# Messages v3.4.3 — Render, cache authority and gateway startup

`versionCode` 109 → **110** · `versionName` 3.4.2 → **3.4.3**
No database change: Room stays on **v17**. No migration, no backfill.

Three production bugs fixed, plus instrumentation for one that needs device traces
before it is changed.

---

## A — Persian text was not right-aligned inside its bubble

**Symptom:** an incoming Persian message ("با کارت … واریز شد") rendered with its text
sitting on the **left** of the bubble.

**Why:** the direction *detection* added in v3.4.2 was correct, and the style was
applied — but `TextAlign` positions a line **inside the Text node's own width**, and
`Text` measures to its intrinsic content width. A short paragraph therefore had no
line box to align within:

```
bubble 500px
text   150px
[سلام خوبی؟]                     ← what was rendered
                        [سلام خوبی؟]   ← what should be rendered
```

Long outgoing messages that contain a URL or a Latin run looked "more correct" only
because the child happened to measure wider.

**Fix:** the message body, displayed image/audio captions and reaction quoted text now
**fill the available width**, so `TextAlign.Start` finally means "start of the
paragraph" on a real line box. Nothing else changed — the bubble side, tail,
timestamp/status row, resend action and every control keep their own layout, because
direction was never made a layout-level property.

**Historical messages need no migration.** Direction is a presentation rule evaluated
at render time, so a message received years ago is right-aligned the moment the app
updates. Nothing is written to the database, no message text is modified, and no bidi
control marks are inserted.

---

## B — Home showed a message that was missing when the conversation opened

**Symptom:** the Home snippet showed the newest message; opening that conversation did
not show it. Restarting the app made it appear.

**Why — a cache/tail race:**

```
Room tail emits        [A, B, C]     ← authoritative, newest = C
slower cache read      [A, B]        ← stale
messages.clear(); addAll([A, B])     ← C disappears
```

Restarting cleared the in-memory cache, so Room painted `[A, B, C]` and the message
"came back". The invariant that was missing:

> A cache may paint first. A cache may **never** overwrite authoritative Room state
> that has already arrived.

**Fix, two halves:**

1. `ThreadMessageCache` exposes a monotonic **`authorityRevision`**, advanced every time
   an authoritative window is painted (the reactive Room tail, and the Room first-paint
   path). The ViewModel snapshots it **before** reading the cache and **discards** the
   cache result if it moved in the meantime — logging
   `cache_paint_discarded reason=room-authority-arrived`.
2. `IncomingMessageDispatcher` invalidates **exactly its own thread** immediately after
   the Room upsert commits and before the UI fan-out, so a reopen cannot paint a window
   that predates the new message. A message with no resolvable thread id falls back to
   the deliberate global invalidation. This is O(1) and never invalidates every
   conversation.

---

## C — Gateway did not reach a visible state after an update

**Symptom:** straight after updating, tapping to enable the gateway did not reach
green; after several minutes, tapping again worked.

**This release instruments instead of guessing.** Every phase of the activation path
now logs its own duration under `GATEWAY_START`:

```
phase=SUPERVISOR_START      durationMs=…
phase=NETWORK_SNAPSHOT      durationMs=… totalMs=…
phase=LAN_ADDRESS_LOOKUP    durationMs=… totalMs=…
phase=LAN_BIND              durationMs=… totalMs=…
phase=HEARTBEAT_START       durationMs=… totalMs=…
phase=EVENT_UPLOADER_START  durationMs=… totalMs=…
phase=TRUST_PUBLISHER_START durationMs=… totalMs=…
phase=POLLER_OR_COMMAND_START durationMs=… totalMs=…
phase=SYNC_START_REQUEST    durationMs=… totalMs=…
phase=CONNECTED             durationMs=… totalMs=…
```

A stall now localises to a step — e.g. `LAN_BIND = 30ms` with
`SYNC_START_REQUEST = 185000ms` points at the shadow sync/history sweep rather than at
the LAN server. No secrets are logged.

`start()` itself only flips desired state and nudges the conflated reconcile, so its
own duration is expected to be ~0 ms; a non-trivial value there would mean the caller
is doing synchronous work on the UI thread.

**Not changed without evidence:** the startup contention between the gateway and the
background backfills (`maybeTriggerStartupCloudBackfill`,
`ClassificationBackfillWorker`, `TelephonySyncCoordinator.startGatewaySync`) is a
documented suspect, and the priority rule remains *gateway activation > tail sync >
background indexing*. Making one of those workers yield is a behavioural change that
should be decided from the traces above, not from a hunch, so this release only
measures it.

---

## Tests

`1159` JVM tests, `0` failures. New: `ThreadMessageCacheAuthorityTest` (9) pinning the
cache-vs-authority contract — the authority clock advances and is monotonic, a cache
read that raced an authoritative paint is discarded while one that did not is
published, an incoming message makes only its own thread stale, the global fallback
invalidates everything, and the newest row is never dropped from the window.

## Not changed

Message identity, Room schema (still v17), Telephony sync, contact resolution, Trash,
OTP retention, smart categories, delayed send, and bubble ownership placement.

## Device acceptance before release

1. Old incoming Persian messages align RTL immediately after update.
2. The newest Home snippet's message exists when the conversation is opened.
3. The gateway reaches a visible deterministic startup state, and the
   `GATEWAY_START` phases identify where the time goes.
