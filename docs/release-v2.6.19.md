# Release v2.6.19 — Swipe confirmation: no more accidental archive/delete

**versionCode 61 · Room schema v6 (unchanged)**

## Field report

Thumb grazes the row edge while scrolling → the old one-stage swipe fired
Archive/Delete directly. Users could not keep their inbox: conversations
disappeared into the archive list and deletes happened mid-scroll.

## Changes

### 1. Harder trigger — two-stage swipe (SmsItem)

`positionalThreshold` raised from the default 0.5 (50%) to **0.85 of the row
width**, and the "Release to Archive" copy now goes bold at 72% progress so
there is unmistakable feedback BEFORE the action arms. A fling that never
carries the row 85% of the way simply bounces back; the old velocity fling
path could dismiss with a short fast stroke.

### 2. Confirmation dialog — the actual gate (Home)

Crossing the threshold no longer mutates anything. It parks the row in
`pendingArchive` / `pendingDelete` and an AlertDialog appears:

* "Archive this conversation?" / "این مکالمه بایگانی شود؟"
* "Move back to inbox?" (archived view)
* "Delete this conversation?" (delete button in red)

Cancel (or tap-outside) leaves the row exactly where it was. Confirm performs
the action AND still shows the existing Undo snackbar — belt AND suspenders:
two deliberate taps to archive, a third tap within 4 s to undo.

### 3. Unarchive is reachable without swiping at all

Long-press menu in the Archived view now shows **"Unarchive"** (it previously
hid every archive-related item there, leaving the risky swipe as the only way
back out). The Archived tab itself (top filter chip) keeps listing everything
moved there — nothing ever gets lost.

## Tests

176/176 green (unchanged suite — this is UI-gesture work covered by the
existing route/encoding and policy suites). `assembleDebug` green.

## Files

* `ui/components/SmsItem.kt` — threshold 0.85, bold-copy at 0.72, long-press
  Unarchive item
* `ui/screens/HomeScreen.kt` — pendingArchive/pendingDelete state + two
  AlertDialogs wired to the existing viewModel calls
* `values{,-fa}/strings.xml` — 3 confirm titles
