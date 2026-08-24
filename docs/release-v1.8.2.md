# Release v1.8.2 — conversation UX polish

Four fixes/improvements to the everyday messaging flow:

## 📊 Smarter SMS counter
- Multi-segment messages now show the **remaining characters in the last
  part** ("SMS 3 · 41 characters left") instead of an opaque "67/part".
- Encoding jargon (gsm7/ucs2) removed from the UI.

## ⬇️ Always land on your sent message
- Sending while scrolled up used to leave the view where it was. Now the list
  always jumps to your new bubble right after sending.

## 📝 Drafts now show in the conversation list (fixed)
- Root cause: `onResume` re-read drafts *before* the closing chat screen saved
  its final state. Drafts are now written live on every keystroke, so the
  italic red "Draft: …" line appears reliably when you come back.

## 👤 Tiny "you" marker
- When a conversation's latest message is yours, a small "you" sits under the
  date — you can tell at a glance who spoke last.

## 🔄 Pull-to-refresh in conversations
- Drag down at the top of a thread to re-check for updates (silent refresh,
  Instagram-style spinner).

**Full Changelog**: https://github.com/aibedini/Messages/compare/v1.8.1...v1.8.2
