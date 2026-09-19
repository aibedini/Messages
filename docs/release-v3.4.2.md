# Messages v3.4.2 — Content-aware message direction

`versionCode` 108 → **109** · `versionName` 3.4.1 → **3.4.2**
No database change: Room stays on **v17**.

A presentation fix. No new features.

---

## The problem

Message text had no explicit direction, so how a message read depended on the
surrounding UI layout rather than on the message itself. In an RTL interface an
English message could sit awkwardly, and the composer gave no directional cue at all
while typing Persian.

## The rule this release implements

**Ownership and direction are two different things:**

| | controls |
| --- | --- |
| incoming / outgoing | which **side** the bubble is on |
| RTL / LTR | how the **text inside** the bubble reads |

A Persian **outgoing** message stays on the outgoing side and simply starts from the
**right**. An English **outgoing** message stays there and starts from the **left**.
The same applies to the composer: the attachment button and Send never move; only the
text and caret take the direction of what is being typed.

## How direction is decided

Direction comes from the **content**, using Unicode bidi character directionality —
never from the app locale, never from the bubble side, and never from a hand-written
list of Persian letters. The **first strong directional character** wins, and
characters that carry no strong direction are skipped:

| content | direction |
| --- | --- |
| `سلام خوبی؟` · `مرحبا` · `שלום` | RTL |
| `Hello, how are you?` · `Bonjour` | LTR |
| `😊 سلام` · `!!! سلام` · `123 سلام` | RTL (the emoji / punctuation / digits are skipped) |
| `😂 Hello` · `123 Hello` | LTR |
| `سلام John` | RTL (first strong run) |
| `Hello علی` | LTR |
| `سلام https://example.com` | RTL |
| `https://example.com سلام` | LTR |
| `۱۲۳۴۵۶` · `+98 912 123 4567` · `😊🎉` · `!!!` | **NEUTRAL** |

`NEUTRAL` is a real answer, not a failure: digits — including Persian and
Arabic-Indic digits, which are **numbers, not RTL letters** — emoji, symbols and
punctuation carry no paragraph direction, so the text falls back to the surrounding
UI layout. That is what keeps a number-only message or an OTP code reading naturally
in both a Persian and an English app.

Mixed inline content is left to Unicode bidi: nothing is reversed, no word order is
changed, and no punctuation is reordered.

## Where it applies

* the rendered message body inside the bubble;
* image and audio **captions** (the `[IMAGE:…]` / `[AUDIO:…]` marker never takes part
  in detection — the displayed caption does);
* the **reaction quoted text**, which takes its direction from itself rather than
  from the surrounding emoji;
* the **composer** field, so a Persian draft opens and types from the right the moment
  it is restored, and English from the left.

`TextDirection.Rtl` is paired with `TextAlign.Start` (and `Ltr` with `Start`), so a
short `سلام` hugs the right inside its bubble and a short `Hi` hugs the left — no
hardcoded Left/Right, and no change to the maximum bubble width.

## What deliberately did NOT change

* **The bubble is never wrapped in an RTL layout direction.** Direction is a text
  property, so the bubble side, tail, timestamp/status row, resend action, icons,
  attachments and menus all keep their existing layout.
* **No text is mutated.** No bidi control marks (`U+200E` / `U+200F`) are ever inserted
  into a stored or sent body; direction is presentation only. Highlight ranges, OTP
  extraction, copy and TalkBack all keep operating on the original characters.
* Delayed send, OTP retention, smart categories, Trash, contact resolution, message
  identity, Telephony sync and the Room schema are untouched — no migration.

## Tests

`1150` JVM tests, `0` failures. New: `ContentDirectionResolverTest` (32) covering
Persian, Arabic, Hebrew, English and other Latin scripts; the leading-neutral cases
(emoji, punctuation, whitespace, digits); digits-only (ASCII, Persian, Arabic-Indic)
and phone-number neutrality; the mixed `first strong run` cases in both orders,
including URLs on either side; multiline text; and two guards proving the resolver
never mutates the text and never relies on bidi marks.

Direction is resolved once per body with `remember(text)`, so recomposition does not
re-run it.
