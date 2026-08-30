# Release v2.6.15 — False “Not delivered” fix

**versionCode 57 · Room schema v6 (unchanged) · No new features**

This release fixes the field regression where an outgoing Persian SMS showed
the red **Resend / Not delivered** state even though the recipient received it
and replied.

## Root cause

The v2.6.14 policy documented that some Iranian carrier/RIL combinations return
`RESULT_ERROR_GENERIC_FAILURE` for UCS-2 submits which their SMSC actually
accepted. However, the implementation still classified that callback as a
message-wide failure until a successful delivery report arrived. With delivery
reports disabled or missing, the false failure remained forever.

## Fix

- A SENT-phase `RESULT_ERROR_GENERIC_FAILURE` is now treated as
  resolved-but-unconfirmed for UI status aggregation. It no longer produces a
  red failure or invites a duplicate resend.
- The raw callback remains in `SMS_STATUS` logs as `AMBIGUOUS_ACCEPTED`, and the
  send-segment ledger still records that the modem did not return `RESULT_OK`.
- Concrete failures (`NO_SERVICE`, `RADIO_OFF`, `NULL_PDU`, and other explicit
  error codes) remain authoritative and continue to show Failed/Resend.
- Multipart messages count an ambiguous callback as a resolved part for display,
  so the row cannot remain Pending forever after every callback has arrived.

## Regression scenario

1. Send a multi-part Persian SMS on the affected SIM/network.
2. Radio returns `RESULT_ERROR_GENERIC_FAILURE`, but the SMSC delivers it.
3. The bubble settles at Sent instead of showing the false red failure.

