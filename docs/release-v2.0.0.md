# Release v2.0.0 — bilingual (فارسی/English), send pacing, quiet hours, SIM rules

The biggest release yet: full bilingual UI, operator-safe sending, and smarter
per-contact behaviour.

## 🇮🇷🇬🇧 Bilingual UI (Persian + English)
- Every screen is now localized: onboarding, home, conversation, settings,
  gateway, quick replies, app lock, notifications.
- Persian strings live in `values-fa/`; the device language decides which
  shows. English remains the fallback.

## 🚦 Send rate limiting
- New in Messaging settings: cap sends to N messages per M minutes
  (default off; e.g. 10/min) so bulk bursts never trip operator throttling.
- Enforced inside `SmsSender` — applies to manual sends AND gateway/API sends.

## 🌙 Quiet hours
- Daily window (e.g. 22 → 7) during which incoming-message notifications are
  delivered **silently** — no ring/vibration, nothing missed.
- Configurable from Settings → Security.

## 💳 Per-contact SIM rules
- Picking a SIM in a chat now pins that line to the contact: next time you
  open the same conversation the right SIM is pre-selected.
- "Default (system)" removes the pin.

## 🗓️ Scheduled messages screen
- Settings → Quick replies section now links to a management screen listing
  every scheduled send with status (Scheduled / Sent / Failed / Cancelled)
  and one-tap cancel for pending ones.

## Also fixed
- Unread badge now clears instantly when you open a chat (ThreadRead event),
  instead of lingering until the next reload.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v1.9.1...v2.0.0
