# Messages v3.4.4 — Composer double-send and the ghost bubble

`versionCode` 110 → **111** · `versionName` 3.4.3 → **3.4.4**
No database change: Room stays on **v17**. No migration, no backfill.

Two production bugs in the composer send path, both introduced by the v3.4.0
Send-delay feature, both triggered by a single tap.

> **With Send Delay OFF, one tap submitted the SMS to the radio TWICE.**

---

## A — One tap, two physical SMS

**Symptom:** the user tapped Send once; the recipient received two SMS.

**Why — two layers each believed they owned the immediate send:**

```text
Send button
   ↓
ConversationViewModel.sendMessage()
   ↓
DelayedSendCoordinator.send()        [delay OFF → DelayPlan.Immediate]
   ↓
sink.send(...) = smsSender.sendForResult(...)   ← PHYSICAL SMS #1
   ↓
returns SendResult.SentNow(rowId)
   ↓
routeComposerSend() folded every non-DelayedSend result into null
   ↓
sendMessage() read null as "not sent yet"
   ↓
smsSender.send(...)                             ← PHYSICAL SMS #2
```

The coordinator was right and the durable state machine was right; the
**vocabulary** was wrong. A nullable `String?` could express "held" or "not
held" but not "already submitted by the sink you handed me", so the caller
guessed — and guessed wrong. Delay OFF is the default, so this fired on every
plain composer send.

**Fix — an explicit contract (`ComposerSendRouting.kt`, pure JVM):**

| Coordinator result | Caller action |
| --- | --- |
| `SendResult.SentNow` | the sink already submitted it — **never send again** |
| `SendResult.DelayedSend` | the durable ledger owns it — never send directly |
| `SendResult.Ignored` | send nothing |
| `null` | timeout/throw only — exactly ONE fallback direct send |

`routeComposerSend` is now `suspend` and returns `SendResult?`; the
`runBlocking` around the bounded decision is gone because the caller already
runs on `Dispatchers.IO`.

**Invariant:** one composer tap ⇒ at most one `SmsManager` submission.

---

## B — A permanent clock bubble beside the real one

**Symptom:** after one tap the conversation showed the optimistic pending bubble
and the confirmed bubble (plus the second confirmed one from A).

**Why:** the optimistic row carries a synthetic timestamp id, so its composite
`(source, providerId)` identity can never equal the real Telephony row.
`ConversationWindow.mergeRoomTail` keeps every visible identity the Room tail
does not mention — that is precisely what protects older pages — so the synthetic
row survived forever. `mergeOptimistic` only pruned `optimisticMessages`,
never `messages`.

**Fix:** the ViewModel reconciles the **exact** optimistic row it created for that
tap onto the provider identity the send returned
(`ConversationWindow.reconcileOwnOptimistic`). When the live outgoing event has
already appended the real row, the synthetic one is dropped instead. The
instant-open `ThreadMessageCache` entry is invalidated at the same time, so the
phantom cannot repaint on the next open.

Matching is one-to-one and per-send on purpose: **no body + time dedupe**, so
sending the same text twice in the same second is still two messages.

---

## C — A third pending bubble while the delay counts down

**Symptom:** with Send Delay ON, up to three "Sending…" bubbles for one tap.

**Why:** `ScheduledSms.schedule` publishes its own synthetic pending row through
`SmsEventBus`; on the delay path that was a third representation next to the
generic optimistic bubble and the durable ledger bubble.

**Fix:** the delay path schedules with `emitOptimistic = false`; the durable
`pending_delayed_sends` row is the single source of the pending bubble. The
long-press "Schedule send" flow keeps its bubble unchanged.

---

## D — A timeout can no longer become a second submission

A bounded-wait timeout can fire between the durable `INSERT` and the WorkManager
enqueue. The composer now generates the durable `intentId` **before** routing,
and after a timeout asks the ledger (`DelayedSendCoordinator.exists`):

* ledger owns the intent → **re-arm its timer** (`rearmIfPending`); never fall back;
* ledger does not own it → exactly one fallback direct send.

That is stricter than "null ⇒ send again" and is what makes the exactly-once claim
hold at the boundary.

---

## Diagnostics

```text
COMPOSER_SEND tap=<random 8-char id> route=IMMEDIATE|DELAYED|FALLBACK providerRowId=<id|-1>
SMS_SEND      dispatch row=<id> phone=<token> sub=… parts=… reports=…
```

One tap with delay OFF logs exactly one `COMPOSER_SEND route=IMMEDIATE` and one
physical `SMS_SEND dispatch row=…`. No message body and no dialable number are
logged.

---

## Tests

`1176` JVM tests, `0` failures. New (17):

* `ComposerSendRoutingTest` (9) — one tap ⇒ one submit; `SentNow` never
  triggers the fallback; a timeout falls back exactly once; a timeout after the
  durable insert is held, not sent; delay ON submits nothing from the tap; only
  `null` may fall back.
* `OptimisticReconciliationTest` (8) — promotion onto the provider identity; a
  confirmed send leaves no ghost; the race with the live outgoing event; a refused
  dispatch drops the synthetic row; the same text twice stays two messages; a held
  send leaves exactly one pending bubble; an external outgoing event still appears.

Gate: `testDebugUnitTest` · `assembleDebug` · `compileDebugAndroidTestKotlin` ·
`lintDebug` (0 errors) — all pass.

## Not changed

Message identity, Room schema (still v17), Telephony sync, contact resolution,
Trash, OTP retention, smart categories, group and attachment sends, and the
durable delayed-send claim in `DelayedSendExecutor`.

## Device acceptance before release

1. **Delay OFF:** tap Send once → the recipient receives **exactly one** SMS and the
   conversation shows **exactly one** final bubble.
2. **Delay 3 s:** tap once → one pending bubble; after 3 s the recipient receives
   **exactly one**; one final bubble.
3. Send identical text twice quickly → the recipient receives **exactly two** and
   the UI shows **exactly two**.
4. Restart the app → no ghost pending bubble remains.
