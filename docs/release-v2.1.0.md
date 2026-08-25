# Release v2.1.0 — windowed conversation history (lazy paging)

## Changed — the big one

Conversations no longer load their ENTIRE history on open. Google
Messages-style **windowed loading**:

- On open, only the newest page (~80 rows: 40 SMS + 40 MMS interleaved by date)
  is read from the provider.
- Scrolling to the top transparently pulls the next older page and inserts it
  above the visible window — scroll position is preserved.
- The "Reading messages… N/N" progress UI is GONE entirely: there is no
  long-running scan anymore, so there is nothing to report.

### Why this is the standard approach (your question)
Chat apps never render full history: WhatsApp/Telegram/Google Messages all
render a window anchored at the newest message and page backwards on demand.
Benefits here:
- Open time is O(page) not O(thread) — a 5,000-message thread opens as fast
  as a 10-message one.
- Memory stays flat regardless of history size.
- The provider query carries LIMIT/OFFSET, so work happens in SQLite (the
  provider's backing store), not in our process reading thousands of cursors.

### Implementation
- New `ThreadPager`: per-thread cursor over `Telephony.Sms`/`Telephony.Mms`
  with LIMIT/OFFSET paging + date-interleaved merge of both sources.
- `SmsRepository.querySmsRaw/queryMmsRaw`: raw paged accessors.
- `ConversationScreen` pulls older pages when `canScrollBackward` becomes
  false at the top; insertion at list head keeps scroll anchor stable.
- Cache (`ThreadMessageCache`) stores only loaded pages; own sends are
  appended so re-opens stay instant.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.0.5...v2.1.0
