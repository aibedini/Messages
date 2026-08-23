# Messages v1.4.0 — appearance presets, Persian calendar, data tools

This release hands full visual and data control to the user, Google Messages-style.

## Highlights

- **Theme color presets** (Settings → Appearance): six curated palettes — Ocean Blue (default), Emerald Green, Royal Purple, Sunset Amber, Rose Pink, Teal Cyan — applied instantly across the app via a reactive theme controller.
- **Dark / Light / System mode** selector.
- **Calendar system** setting: Gregorian or Persian (Jalali). All rendered dates — chat bubbles, delivery details, list items, section headers — honour the choice. Jalali conversion uses an embedded implementation of the standard 33-year-cycle algorithm with Persian digits, month names and ق.ظ/ب.ظ; correctness is covered by unit tests against known dates (Nowruz, 22 Bahman 1357, …).
- **Delete messages by period** (Settings → Data tools): pick a date & time and delete everything BEFORE or AFTER it — SMS and MMS both, with a deleted-rows summary.
- **Export all chats**: one tap builds a structured JSON archive of every conversation and opens the Android share sheet via FileProvider (no storage permission needed).
- Flat Google-Messages-style bubbles remain: sent = `primaryContainer`, received = `surfaceVariant`; long-press a bubble for the Sent/Delivered detail line.

The APK is signed with the project's existing release key. Play Protect may still review or block sideloaded SMS apps distributed outside Google Play; this release does not disable or bypass that security feature.
