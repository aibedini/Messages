# Release v1.6.0 — quick replies, message forwarding & tap-to-act links

This release focuses on faster everyday messaging: pre-defined reply templates
with `/shortcuts`, forwarding, and tappable links and phone numbers inside any
message bubble — plus a smarter conversation search.

## Highlights

### ⚡ Quick replies (`/shortcuts`)
- **Settings → Quick replies**: manage reusable message templates, each with its
  own shortcut (e.g. `/c1` → "Done ✅").
- Sensible starter set seeded on first launch: `/c1`, `/c2`, `/ty`, `/soon`,
  `/later` — edit or delete freely.
- Type `/` in any conversation to see matching suggestions as chips; tapping one
  fills the input. Longest-shortcut-first matching so `/c10` wins over `/c1`.

### ↪️ Forward messages
- Long-press any bubble → **Forward** → pick a contact (single or group) or type
  a new number; the text is sent automatically once the recipient is chosen.
- Forwarding banner previews the message while choosing the recipient.

### 👆 Tap-to-act entities in bubbles
- URLs are underlined links — tap to open in browser.
- Phone numbers (7–15 digits) are highlighted — tap to open an action sheet:
  send SMS, call, add to contacts, copy.
- URLs take precedence over numbers when they overlap.

### 📋 Long-press action menu
- Replaces the old long-press details toggle: **Copy**, **Copy link/number**,
  **Forward**, and **Message details** (outgoing only) in one menu.

### 🔍 Smarter search (Home)
- Number-like queries show a direct "Send to …" row (Google Messages style).
- Search now also matches phone numbers of conversations via normalized contact
  names map.
- Live match counter shows how many conversations matched.

### 💬 Conversation UX polish
- Chat opens instantly anchored to the newest message — `requestScrollToItem`
  before first paint, no visible scroll animation from the top.
- Auto-follow new incoming/outgoing messages only when already at the bottom.

## Technical

- New: `QuickRepliesPreferences` (SharedPreferences + JSON store), `QuickRepliesScreen`,
  `PhoneNumberActionDialog`.
- Navigation: `forward` argument added to `NewConversation` and `Conversation`
  routes (URL-encoded).
- `HomeViewModel.contactNames` exposed for search.
- versionCode 16 → 17, versionName 1.5.1 → 1.6.0.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v1.5.1...v1.6.0
