# Release v2.6.12 — Send Diagnostics + Resend + {forward} fix

**versionCode 54 · Room schema v6 (unchanged) · No new features**

Three targeted fixes from field reports on v2.6.10/11:

## 1. Red "!" on sent messages (diagnose + recover)

The report: bubble shows the red failed mark, yet the SMS *reaches* the
recipient. Code forensics (v2.6.9..v2.6.10 diff over the whole
send → callback → UI chain) showed the chat-send path was untouched by
v2.6.10 — the mark comes from the modem SENT callback, not our code:

- `SmsManager` now reports `RESULT_ERROR_GENERIC_FAILURE` on some
  networks/SMSCs **while the SMSC still accepts and delivers the
  (UCS-2/Persian) submit**. Without carrier delivery reports there is no
  second callback to correct the state, so the red mark sticks even though
  the message went out.

Fix (two layers):
- **Exact diagnostics**: `SmsStatusReceiver` logs the precise resultCode
  (name + part index + phase) on every failure — `adb logcat -s SMS_STATUS`
  now tells us exactly which error the radio reported, no guessing.
- **One-tap Resend on failed bubbles**: an outgoing bubble with
  `STATUS_FAILED` shows a small "Resend / ارسال مجدد" action that re-sends
  the same body through the normal pipeline (rate limiter, SIM preference,
  optimistic UI). Same pattern as Google Messages. The per-conversation SIM
  selection was hoisted so the resend uses the exact chip selection.

## 2. `{forward}` typed into the composer

Home's FAB navigated with `Screen.NewConversation.route` — the route
**pattern** with `{forward}`/`{draft}` placeholders — instead of a filled
route. Navigation-Compose then handed the literal string `{forward}` back
as the argument value, and the composer banner "typed" it.

Fix:
- All Home entries now navigate with the plain `baseRoute`
  (`new_conversation`), no query string.
- Defense-in-depth: `Screen.cleanArg()` sanitizes every forward/draft/
  shared_phone argument at the graph layer — a value containing route
  braces is treated as a leaked pattern (not user data) and dropped. This
  also covers process-death back-stack restores of a stale bad route.

## Verification
- compileDebugKotlin green; testDebugUnitTest 153/153; assembleDebug green.
- Chat-send path byte-identical to v2.6.9 (verified by diff) — no behavior
  change for the normal send, only added observability + recovery.
