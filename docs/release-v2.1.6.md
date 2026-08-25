# Release v2.1.6 — the list and the conversation finally agree

## Fixed

The most visible remaining bug: a conversation row in the Home list showed an
OLD preview at an OLD position while opening that same conversation showed newer
messages. In the reported case thread `S3-1560` sat in the list as
`٩٩٥ · 2:01 pm`, but the thread's real last message was an outgoing one at
`4:25 pm` — and the row was sorted as if 2:01 pm were still current.

### Root cause

`SmsSender.persistToSent()` inserted the sent row **without `THREAD_ID`**:

```kotlin
put(Telephony.Sms.ADDRESS, phone)
put(Telephony.Sms.BODY, text)
put(Telephony.Sms.DATE, now)
// … no THREAD_ID
```

The Home list is built from `Telephony.Threads`, whose `SNIPPET`/`DATE`/`READ`
columns the platform provider maintains — and it only maintains them for message
rows correctly associated with a thread. An orphan row updates nothing, so the
thread row kept its old snippet and old date forever, while the conversation
screen (which queries by `ADDRESS`, not by thread) showed the new message. Two
readers, two different answers, from the same database.

`MmsSender` had always done this correctly via
`Telephony.Threads.getOrCreateThreadId`; SMS never did.

### Fix — two layers

1. **Write correctly.** `persistToSent` now resolves the canonical thread id
   with `getOrCreateThreadId(context, phone)` and stores it, so the provider
   updates the Threads table exactly as it does for the stock SMS app. Falls
   back to inserting without it if the provider refuses, rather than dropping
   the message.

2. **Reconcile on read (heals existing data).** New pure helper `ThreadSnippet`
   reconciles each list row against the newest message actually present for that
   thread — last-write-wins by timestamp, the same reconciliation a WhatsApp or
   Telegram client applies between its local store and the server. Rows already
   written by older versions of the app are therefore corrected too, without a
   migration. `SmsRepository.newestMessagePerThread` supplies that data in a
   single bounded (600-row) DATE-DESC scan, not one query per thread.

   Applied on both the cold-start load and the silent refresh, so the very first
   painted list is already correct.

### Also

- `silentRefresh` now writes the reconciled list back to `ConversationCache`.
  Previously only the cold-start path saved to disk, so the next app start could
  hydrate a snapshot older than what the user had just been looking at — another
  source of "why is it showing me old state".

Unit tests: new `ThreadSnippetTest` (5 cases) covers stale-snippet replacement,
unread-badge clearing on your own send, not marking a thread read on an incoming
message, ignoring older/equal data, and leaving untouched threads alone. Suite
is 70 tests, all green.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.1.5...v2.1.6
