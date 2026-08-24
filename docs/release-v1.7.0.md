# Release v1.7.0 — scheduled SMS APIs, Persian-digit awareness & smarter sync

This release makes the gateway a real automation endpoint and fixes two
long-standing Persian-user pain points, plus a major UX fix for the
conversation list.

## Highlights

### ⏰ Scheduled send APIs (for external projects)
- `POST /api/v1/sms/schedule` with `sendAt` (epoch ms) or `delaySeconds`
- `GET /api/v1/sms/schedule` — list · `GET …/{id}` — status · `DELETE …/{id}` — cancel
- Backed by WorkManager: fires even after reboot / process death (3 retries)
- Idempotent on (phone + message + sendAt); 200 in-flight job cap
- In-app too: **long-press the Send button** → pick time → sends later

### 🔢 Persian/Arabic digit support everywhere
- Tapping «۰۹۱۲۴۸۸۷۳۳۸» inside any message now opens the action sheet:
  Send SMS / Call / Add to contacts / Copy (was invisible before)
- All gateway send endpoints normalize Persian digits automatically —
  projects can POST `"to": "۰۹۱۲…"` and it just works
- Dialer & contacts always receive ASCII digits; display/copy keep what the
  sender wrote

### 📊 Standards-based SMS segment counter
- GSM-7 (160/153) vs UCS-2 (70/67) per 3GPP TS 23.038, extended-table chars
  counted double — shown live above the chat input

### ⚡ No more "Syncing…" flash when returning from a chat
- Single Source of Truth pattern: the list renders from cache instantly;
  resume only re-syncs when the provider actually changed (one cheap indexed
  query), then swaps atomically without clearing the visible list

## Also in this release
- Block numbers (system blocklist + silent receiver), pin conversations,
  global all-messages search, XML backup/restore (SMS Backup & Restore
  compatible), biometric app lock — shipped mid-cycle as v1.6.x work

**Full Changelog**: https://github.com/aibedini/Messages/compare/v1.6.0...v1.7.0
