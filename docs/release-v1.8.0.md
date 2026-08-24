# Release v1.8.0 — message drafts

Never lose a half-typed message again.

## What's new

### 📝 Drafts (like WhatsApp / Telegram / Google Messages)
- Type a message in any conversation and leave without sending — it's
  automatically saved as a **draft** for that conversation.
- The conversation list shows an italic red **Draft:** line with the saved
  text in place of the last message, exactly like the big messaging apps.
- Reopen the chat → your text is right back in the input field.
- Send (or schedule) the message → the draft clears itself.
- Works for existing threads *and* brand-new chats that were never persisted.

## Technical

- New `DraftRepository` — per-conversation drafts keyed by threadId or the
  normalized recipient phone; JSON in SharedPreferences (tiny, no Room).
- `ConversationScreen` persists on dispose via `DisposableEffect`, clears on
  send/schedule.
- Home list re-reads drafts on every resume (cheap) and renders them through
  `SmsItem(draftText = …)`.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v1.7.0...v1.8.0
