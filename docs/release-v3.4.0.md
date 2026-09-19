# Messages v3.4.0 — UX / Messaging Intelligence

`versionCode` 106 → **107** · `versionName` 3.3.6 → **3.4.0**
Room schema **v17** (non-destructive `15 → 16 → 17`)

This release is a user-facing feature release. Every feature below is implemented
and covered by the JVM unit suite (`./gradlew testDebugUnitTest`, 1100+ tests, 0
failures). Nothing in this document is aspirational: features that were not
finished are not listed.

---

## Messaging

### Search inside one conversation
The conversation overflow menu gains **Search**. The top bar becomes
`[←] [Search messages…] [3 of 14] [↑] [↓] [X]`, results are debounced (280 ms,
minimum 2 characters), matching text is highlighted in each hit, and tapping a
result — or the arrows — jumps to the **exact** message.

* Reuses the existing FTS4 index (`messages_fts`); no second index, no
  `body.contains()` scan, no whole-conversation load.
* The jump resolves the row by its composite identity and reads two **bounded**
  keyset windows around it (never a sequential page walk). The painted window is
  capped at 40 rows, and the ↑/↓ control reports whether more history exists on
  each side.
* The highlight folds ZWNJ on both sides, so `میروم` typed without the zero-width
  joiner still highlights `میروم`, and the highlighted span always maps back to
  the stored text (message bodies are never rewritten).

### Undo Send / send delay
Settings → Messaging → Sending → **Undo Send**: OFF (default) / 3 / 5 / 10 / 30
seconds. With a delay set, a normal composer send becomes a durable pending send:
the composer clears immediately, a pending bubble with a clock appears, and a
snackbar offers **UNDO**.

* Durable across process death via the `pending_delayed_sends` ledger (Room v17).
* The claim `PENDING → SENDING` is a single atomic compare-and-set, so two workers
  can never send the same message twice; `SENDING` is **not** claimable and
  `CANCELLED` can never become `SENT`.
* Applies to normal composer sends only — gateway sends, scheduled messages,
  automation and notification quick reply stay immediate.

### Notification quick actions
Contextual, because Android shows only a few collapsed actions:

| Notification | Actions |
| --- | --- |
| OTP | Copy code · Reply · Mark as read |
| Normal message | Reply · Mark as read · Archive |

Muted conversations post nothing; blocked senders post nothing. Quick reply keeps
its existing `goAsync` + `Dispatchers.IO` path. Copying an OTP no longer echoes the
code in a toast.

---

## Conversations

### Mark as unread
Long-press a Home row, or use the conversation menu, to bookmark a conversation as
unread. This is **UX-only user state** — the provider's `READ` column is never
rewritten back to 0, so it cannot fight the sync engine. Opening the conversation
clears the bookmark, and a provider refresh can never overwrite it.

### Mute
Per-conversation presets: 1 hour / 8 hours / 24 hours / 7 days / Forever /
Unmute. Mute suppresses the **local** Android notification only: Room ingest, the
gateway, linked-device sync and the unread count are untouched. Expiry is a
comparison, so no unmute job is scheduled. Muted conversations show a muted icon on
Home.

### Per-conversation notification settings
Conversation Info → Notifications → **Default / Custom**. Choosing Custom creates a
real Android notification channel (`conversation_<threadId>`) and opens the
**system** channel screen, so sound and vibration are configured where Android
actually owns them — never in a parallel app-side setting. Channels are created
only for conversations the user explicitly customised.

### Conversation Info
A dedicated screen with the participant header (avatar, name, number) and the
existing participant actions (Call / Message / Copy / Add to contacts /
View contact), then: Notifications (mute + custom channel), Media, links & files,
Starred messages (N), Category (Automatic or an explicit override), Spam &
blocking, and a destructive **Move conversation to trash** behind a confirmation.

### Starred messages
Star/unstar from the message long-press menu; starred bubbles carry a star
indicator. Conversation Info links to the conversation's starred list, and Home
exposes a global **Starred messages** browser. Tapping a starred row opens the
conversation **at that exact message**. A starred message is exempt from OTP
cleanup, and a starred message inside a trashed conversation belongs to Trash, not
to Starred.

### Trash / Recently Deleted
Deleting a conversation is now durable and reversible: the conversation is hidden
immediately via a tombstone, a "Moved to Trash" snackbar offers **UNDO**, and the
provider rows are left alone.

* Retention is 30 days (`TrashRepository.RETENTION_MILLIS`); the Recently Deleted
  screen shows the deleted date, the days remaining, Restore, Delete permanently
  and Empty Trash (destructive actions require confirmation).
* A new message arriving **after** the deletion cutoff is visible again and
  re-creates the conversation, while the old deleted snapshot stays hidden.
* A permanent purge deletes only the canonical range the tombstone stands for, and
  only after the provider write **succeeds**; a provider failure keeps the trash
  state and is retried by WorkManager. The app never claims "deleted" while the
  provider still holds the rows.

### Multi-select and bulk actions
Long-press enters selection mode in both lists; the top bar becomes
`X  3 selected  [actions]`, BACK exits selection first, and selected rows are
visually distinct.

* Home: Mark read / Mark unread / Archive (or Unarchive) / Mute / Pin / Move to Trash.
* Conversation: Star / Unstar / Copy text / Forward / Move to Trash.
* Batches are applied through one Room transaction per action, with provider writes
  bounded to 3 concurrent operations, and the result reports partial failure
  ("12 updated, 2 could not be updated") instead of claiming success.

---

## Intelligence (local only)

### Smart Categories
Home gains a scrollable category row — Personal / OTP / Transactions / Promotions /
Spam — shown only for categories that contain data. Classification is **entirely
local**: no content ever leaves the device.

* One classifier handles OTP → transaction → promotion → personal → unknown, with
  **Spam reserved for explicit user reports** (weak heuristics never auto-file
  Spam), and a user override always beating the automatic result.
* Existing history is classified by a checkpointed, resumable WorkManager sweep
  (200–500 rows per batch, keyset paging); new messages classify during ingest.

### Unified OTP detection
One detector behind every OTP surface — notifications, the bubble "Copy code"
affordance, smart categories and OTP retention. It understands ASCII, Persian
(۰۱۲۳۴۵۶۷۸۹) and Arabic-Indic (٠١٢٣٤٥٦٧٨٩) digits, ranks 4–8 digit codes highest,
requires real OTP context for alphanumeric codes, and rejects dates, prices, phone
numbers and account numbers. OTP codes are never logged.

### Configurable global OTP retention
Settings → Messaging → **OTP & verification codes**. The switch is **OFF by
default**. When on: 1 hour / 6 hours / 24 hours / 3 days / 7 days / Custom (1 hour
to 30 days, validated).

* Expired OTP messages **move to Trash** — they are never permanently deleted at
  that moment, so the user can restore them; Trash retention handles deletion later.
* Starred messages and messages the user explicitly **kept** are never touched.
  Long-press an OTP to choose "Keep this message".
* "Apply to existing OTP messages" is a separate, explicit action; turning the
  switch on never retroactively cleans history.
* One scheduled job (`otp_cleanup`) is kept pointed at the earliest deadline — never
  one job per OTP, never a periodic full scan.

### Local spam + block
Conversation Info offers **Block** and **Report spam**; Home gains a Spam category.

* "Mark as spam & block" is a **local** decision — there is no carrier or server
  report in this release, and the UI never claims otherwise. The report retains
  every message, blocks the sender, hides the conversation from the normal inbox
  and shows it under Spam.
* **Spam is not Trash**: a report never moves a message to Trash and never deletes
  anything (structurally — the spam port exposes no trash operation at all).
* Not-Spam restores the conversation to the Inbox and unblocks the sender **only
  if the report itself created the block**; a number the user had blocked manually
  stays blocked.

---

## Platform

### Room schema v17
`15 → 16 → 17`, both migrations additive and non-destructive; `fallbackToDestructiveMigration`
remains DEBUG-only.

* **v16** adds the UX user-state tables: `conversation_preferences`,
  `message_user_state`, `trashed_threads`, `message_classification`,
  `conversation_classification`, `message_assets`.
* **v17** adds `pending_delayed_sends` (the durable Undo Send ledger).

User-owned state lives in dedicated tables rather than on `MessageEntity`, so a
provider Upsert can never overwrite a star, a mute, a manual-unread bookmark or a
trash flag. Message identity is `(source, providerId)` everywhere — SMS `_id` 100
and MMS `_id` 100 are different messages.

### Performance and privacy
No new full-provider scans and no `ContentResolver` work on the UI thread.
Conversation open, search, categories, starred, trash and media paging are all
indexed and bounded; OTP cleanup queries only its due set. OTP and message content
never leave the device, and diagnostics log counts, ids and phone tokens — never
message bodies, codes or full numbers.
