# Messages v3.4.1 — Home contact names

`versionCode` 107 → **108** · `versionName` 3.4.0 → **3.4.1**
No database change: Room stays on **v17**.

A P0 presentation regression fix. No new features.

---

## The bug

After the Room read-SSOT cutover the Home conversation list showed **phone numbers**
instead of contact names, while opening the same conversation usually showed the
**correct name** in the header.

## Root cause — one wrong coupling

There were two independent contact-resolution paths, and only one of them ran.

The Home row rendered:

```kotlin
viewModel.contactNames[ContactRepository.normalizePhone(sms.sender)] ?: sms.sender
```

but `contactNames` was populated in exactly one place — inside
`HomeViewModel.loadProviderConversations()`, the explicitly GATED provider fallback.
The normal Room path never reaches it:

```
performLoad() → ensureRoomGate() → roomReadEnabled == true
              → roomConversations() → return      // contactNames never loaded
```

So `contactNames` stayed `emptyMap()` and every row fell back to the raw address.
The conversation header did not have the problem because it independently resolves
one recipient through `ContactsContract.PhoneLookup`.

Contact names are UI metadata: whether message rows came from Room or from the
provider fallback is irrelevant to them. The loading was simply attached to the wrong
owner.

A **second** mismatch sat underneath it. Even with the map populated, the lookup was
too strict: `normalizePhone` stripped formatting but carried no equivalence rules, so
these two spellings of the same number were different keys:

```
09121234567        (how the SMS provider usually stores a local number)
+989121234567      (how a contact is very often stored)
```

The header's `PhoneLookup` tolerates that; the exact map lookup did not.

## What changed

### Contact loading is independent of the provider fallback
`HomeViewModel.refreshContactNames(force)` loads the contact directory on
`Dispatchers.IO`, publishes it on Main, and is triggered from `init` — **not** from
`loadProviderConversations()`. It never scans SMS and never blocks the first Room
paint: rows render immediately with numbers and become names as soon as the map
arrives.

### One phone-identity policy (`PhoneIdentity`)
A single, closed alias set instead of ad-hoc string handling:

* formatting is stripped, so `0912 123 4567` and `09121234567` agree;
* a number that is unambiguously an Iranian mobile expands to its local (`0912…`),
  national (`98912…`), plus (`+98912…`) and IDD (`0098912…`) spellings, so a
  conversation stored one way finds a contact stored the other way;
* **only** those shapes are expanded — `98`+`9`+9 digits, `0`+`9`+9, or `00`+`98`+`9`+9.
  `112`, `110`, `115`, `125`, `100`, USSD codes, short alphanumeric sender IDs and
  foreign or landline numbers are **never** rewritten, so a short code cannot
  accidentally match an unrelated contact.

The contact directory now registers every alias for each contact, which is what makes
the local/international lookup work in both directions.

### One display-name resolver
`ContactRepository.displayNameFrom(directory, address)` (exposed as
`HomeViewModel.displayNameFor(address)`) replaces the duplicated lookups. The Home
row, Home search hits, the navigation snapshot and the archive/trash confirmation
dialogs all use it, so they can no longer disagree about the same conversation.

### Contact changes while the app is open
A `ContactsChangeObserver` watches the Contacts provider, debounced by 400 ms so one
edit (which emits a burst of notifications) causes exactly **one** directory reload.
The reload invalidates `ContactRepository`'s cache — including the single-recipient
participant cache, so the chat header cannot keep a stale name either. Resume stays
the documented fallback, and a provider that refuses registration is logged and
ignored rather than breaking Home.

### Permission lifecycle
An earlier permission denial is never cached as a permanent empty map: with
`READ_CONTACTS` denied the app shows numbers without crashing, and granting it later
loads names on the next resume — no process restart.

## Tests

`1118` JVM tests, `0` failures. New:

* `PhoneIdentityTest` (14) — formatting normalization, the Iranian local/national/plus/
  IDD equivalence in both directions, symmetry, and the negative cases that matter:
  service and short codes are never expanded, a short code cannot match a contact
  number that merely contains it, alphanumeric sender IDs resolve to nothing, and
  foreign and landline numbers keep their own identity.
* `HomeContactLoadingWiringTest` (4) — a source-level guard for the actual defect:
  `init` must trigger the contact load, the loader must exist and use the async IO
  query, the Room-ready branch must still `return` before the provider fallback (so the
  two concerns stay independent rather than re-coupling), and `HomeScreen` must not do
  its own contact-map lookup.

## Not changed

Message identity, Telephony sync, Trash semantics, OTP retention, Smart Categories,
Delayed Send and the v3.4.0 database schema are untouched. This release needed no
migration.
