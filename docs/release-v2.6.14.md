# Release v2.6.14 — Delivery Evidence Policy + True Per-SIM SMSC

**versionCode 56 · Room schema v6 (unchanged) · No new features**

Follow-up to v2.6.13. The multipart downgrade fix shipped, but field
testing on IR-MCI showed the red "!" still survives one more path — and the
per-SIM SMSC seeding introduced a different problem.

## 1. Delivery evidence now REFUTES a SENT failure (SmsStatusPolicy)

v2.6.13 made SENT-phase failure sticky **forever**: once any part reported
GENERIC_FAILURE, no later success could lift the row. On Iranian networks
the radio frequently returns GENERIC_FAILURE for UCS-2 submits the SMSC
actually accepted and delivered. The successful DELIVERED report for even
ONE part is stronger evidence than the earlier radio error — the network
clearly reached the handset.

New contract (SmsStatusPolicy, pure + unit-tested):

| Evidence                                   | Status |
| ------------------------------------------ | ------ |
| SENT part failed, no delivery reports yet  | FAILED (+ Resend) |
| All SENT parts OK, no delivery reports     | Sent |
| ANY part DELIVERED OK (refutes FAILED)     | at least Sent |
| All parts DELIVERED OK                     | Delivered |
| DELIVERED part failed (multipart gap)      | never downgrades a sent message |

`SmsStatusReceiver` keeps the sticky sent-fail flag purely as history; the
policy checks delivery evidence first. Stale v2.6.13 prefs keys are simply
ignored on upgrade — no migration needed (schema unchanged).

## 2. SMSC: hidden carrier-directory seeding REMOVED

v2.6.13 auto-injected hardcoded SMSCs (Irancell `+989****1400`,
IR-MCI `+989****0500`) on first send without telling the user, under a
global pref that still took precedence anyway — a half-real feature whose
hidden value can itself *cause* radio errors when it mismatches what the
SIM carries.

v2.6.14:

- `SmscDirectory` and `seedPerSimSmsc()` deleted. The send path resolves the
  service centre strictly from **user intent**: per-request override →
  this SIM's manual override (`smsc_sim_manual_<subId>`) → global manual
  override → **null** = "use the SMSC programmed on the (U)SIM" (Android's
  documented behaviour for `scAddress == null`).
- Any leftover v2.6.13 hidden seed key is purged when the user touches the
  per-SIM setting.

## 3. Settings > Messaging > SMSC: real SIM values, per-SIM overrides

Android 11+ exposes `SmsManager.getSmscAddress()` for the default SMS app.
`SimManager.readSmsc(subId)` now uses it (per-subscription manager) and the
SMSC card shows, per SIM row:

- **On SIM card: +98…** — the address actually programmed on that (U)SIM
- **Manual override: +98…** — when the user set one (editable/clearable per
  SIM), with "Use SIM default" to drop it again
- Global override kept below the divider for power users, clearly labelled.

No API below 30, permission missing, or RIL refusing → row shows
"not available", and sending still falls back to the SIM default (null).

## Tests

`SmsStatusPolicyTest` rewritten for the evidence contract — including the
two regression cases: `deliveredSuccessREFUTESEarlierSentFailure` and
`singleDeliveredOnMultipartLiftsFailedToSend`. Suite: **163/163** (was 162;
net +1 from consolidation).

## Verification for the field bug

1. Same Persian multi-part SMS on IR-MCI with delivery reports ON.
2. If the radio lies again (GENERIC_FAILURE at send → red !), the first
   delivery report that arrives lifts it to Sent/Delivered automatically.
3. `adb logcat -s SMS_STATUS` still records the exact lie (`send failure:
   resultCode=… GENERIC_FAILURE`) for diagnosis; Resend remains for genuine
   failures.
