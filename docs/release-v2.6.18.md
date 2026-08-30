# Release v2.6.18 — Today-counter counts accepted sends; spaced contact names fixed

**versionCode 60 · Room schema v6 (unchanged) · Two field bugs**

## 1. "SMS today" chip stuck (user report: frozen at 71)

The counter counts `send_segments` rows with `success = 1`, and v2.6.17
wrote `success` from the raw SENT callback: **only `RESULT_OK`**.
v2.6.15 had already decided that `RESULT_ERROR_GENERIC_FAILURE` on the
affected Iranian RIL/SMSC combinations means AMBIGUOUS_ACCEPTED — the
message goes out, shows Sent, and the recipient replies — but the ledger
still refused to count those segments. Result: as soon as the SIM/network
started answering every submit with GENERIC_FAILURE, the chip stopped
moving while sends kept succeeding.

Fix: the ledger's `success` now follows the same verdict as the UI —
`RESULT_OK` **or** GENERIC_FAILURE counts as a billable segment; explicit
radio-level errors (`NO_SERVICE`, `RADIO_OFF`, `NULL_PDU`) remain
uncounted. Composite PK `(rowId, partIndex)` still makes redelivered
callbacks idempotent.

Historical note: rows recorded `success = 0` by v2.6.17 today are not
back-filled — the chip counts what it observes from now on, and tomorrow
the window resets anyway.

## 2. Contact header showed `+` instead of spaces ("hamid+dadash")

`Screen.createRoute` FORM-encoded navigation arguments
(`URLEncoder.encode`: space → `+`), but Navigation decodes query
parameters percent-style (`Uri.getQueryParameter`: `+` is a LITERAL plus,
only `%20` is a space). Every contact display name containing a space —
or a name literally like "hamid+dadash" colliding with the broken form of
"hamid dadash" — arrived in the conversation header with pluses.

Fix: single `Screen.encode()` helper that form-encodes then promotes
`+` → `%20` (space), while `&`/`=`/`#` still get escaped so they cannot
split query arguments or retrigger the v2.6.12 `{forward}` class of bugs.
Percent signs in message text are escaped too (`%25`), so a draft body
containing "%20" survives exactly one decode. Phone numbers like `+98…`
are unaffected on the wire (their '+' becomes `%2B`).

No extra decode call was added at the read sites — `StringType` already
decodes once; a second decode would corrupt legitimately-percenty text.

### Regression tests (`NavigationRouteEncodingTest`)

space → `%20`, name/phone/Persian round-trips, `&` in name cannot split
args, `%` in forward text not double-decoded. Suite: **176/176**.
