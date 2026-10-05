# v3.4.28 — Messages was missing from the browser's SMS chooser (resolver contract)

`versionCode 135` · `versionName "3.4.28"` · minSdk 26 · targetSdk 36

EVE's "Send to client → SMS" opens `sms:+98912…?body=…`. On the phone Android showed an app chooser and
**our Messages app was not in it**, even though it is the default SMS app.

Being the default SMS app does not make an activity a candidate for an intent. Eligibility is the
`intent-filter` match on action + categories + data. Two facts made this a real gap:

**1. A browser emits `ACTION_VIEW`, not `ACTION_SENDTO`.** Chrome builds the external intent as

```java
// components/external_intents/.../ExternalNavigationHandler.java
} else {
    targetIntent = new Intent(Intent.ACTION_VIEW);
    targetIntent.setData(Uri.parse(params.getUrl().getSpec()));
}
```

**2. Chrome's "give it to the default SMS app" shortcut requires this app to resolve that very intent.**

```java
private boolean maybeSetSmsPackage(Intent targetIntent) {
    if (targetIntent.getPackage() == null && uri != null
            && UrlConstants.SMS_SCHEME.equals(uri.getScheme())) {
        List<ResolveInfo> resolvingInfos = queryIntentActivities(targetIntent);
        targetIntent.setPackage(getDefaultSmsPackageName(resolvingInfos));
    }
}

private @Nullable String getDefaultSmsPackageName(List<ResolveInfo> resolvingComponentNames) {
    // "Makes sure that the default SMS app actually resolves the intent."
    … return defaultSmsPackageName only if it appears in resolvingComponentNames;
}
```

The manifest declared `ACTION_SENDTO` (sms/smsto/mms/mmsto) and `ACTION_SEND` (text/plain) only, so
Messages never appeared in `resolvingInfos` for the `ACTION_VIEW` intent: Chrome left the package null
and Android showed the chooser of the apps that *do* resolve it. The default-SMS role was never the
missing piece.

## Changes

1. **`AndroidManifest.xml`** — `MainActivity` now also declares, scheme-scoped:

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="sms" /> <data android:scheme="smsto" />
    <data android:scheme="mms" /> <data android:scheme="mmsto" />
</intent-filter>
```

No `http(s)`, no `*/*`, no generic `VIEW`: no unrelated chooser is affected.

2. **`MainActivity.parseShareIntent`** — `ACTION_SENDTO` and `ACTION_VIEW` go through one
   `smsPayload()` path, and a URI is accepted only when its scheme is one of the four advertised ones.
   An explicit intent aimed at MainActivity can no longer feed `https:` / `tel:` / `content:` / `file:`
   into the compose path. Cold start (`onCreate`) and warm (`onNewIntent`, `singleTask`) share it.

3. **`IncomingShareParser`** — `SUPPORTED_SMS_SCHEMES`, `isSupportedSmsUri()`, `fromView()`.

## Test matrix

`IncomingShareParserTest` / `SmsComposeIntentFilterTest` (JVM, no device):

| Case | Input | Expected |
|------|-------|----------|
| A | `ACTION_SENDTO` `smsto:+98…` | eligible, recipient parsed |
| B | `ACTION_SENDTO` `sms:+98…?body=` / `sms_body` extra | recipient + body |
| C | `ACTION_VIEW` `smsto:+98…?body=hello` | eligible, recipient + body |
| D | `ACTION_VIEW` `sms:+98…` | eligible, recipient |
| E | `ACTION_SEND` `text/plain` | eligible, text |
| F | `ACTION_VIEW` `https://…`, `tel:`, `content:`, `file:` | rejected |
| G | `ACTION_SEND` `image/*` | not advertised |
| H | warm `singleTask` `onNewIntent` | unchanged path, newest payload wins |

## Real-device acceptance (owner, Samsung Android 15)

Default SMS app stays Messages; no re-selection, no data clear, normal APK upgrade.

- EVE → Send to client → SMS: Messages is the handler (Chrome routes `sms:` straight to the default SMS
  app now that it resolves), compose opens with the right recipient and body.
- Chrome `sms:` link, Contacts SMS action, Android text share sheet: same.

---

# v3.4.28 (P0) — an SMS is read only when its conversation is genuinely on screen

A newly received SMS could be marked READ although the user never opened that conversation, on the phone
or on the web. On a real device `Co5-p91` showed as read while `PARSIANBANK` (a different conversation,
same session) correctly stayed unread. Read state is mirrored to Room, to the Telephony provider and to
GMweb, so a false READ destroys the user's unread information — a data-integrity bug, not a badge
cosmetic. Carrier delivery (v3.4.27) is untouched here.

## Root cause: two things that were not a visible screen were treated as read evidence

1. `ConversationViewModel.observeIncomingSms()` matched the incoming sender against `currentPhone` and
   then wrote `unread = false` plus a durable mark-read. `currentPhone` survives navigation to Home,
   backgrounding, and a back-stack entry nobody is looking at: matching it proves the *ViewModel
   remembers* a conversation, not that the *user is looking at* one.
2. `VisibleConversationTracker` — consulted by the sync core when it folds an incoming unread provider
   row to `read = true` — was set from **composition** and from the ViewModel's **load path**, and
   cleared only in `onCleared()`. So "data was loaded" and "the composable exists" both became "the user
   is looking at it", and a backgrounded app kept claiming the last conversation was visible.

Two smaller routes to the same outcome were closed with them: the Activity-resume refresh marked the
remembered conversation read unconditionally (true on Home, in Search, anywhere), and
`loadConversation()` called `markReadUseCase.markRead(...)` **before** its stale-result guard, so a load
that began for A and finished after the user left could still mark A read.

## The rule now enforced

Only four authorities may turn unread into read:

```text
USER_OPEN_RESUMED        the conversation's screen is RESUMED and the app is foreground
USER_MARK_READ           the user explicitly pressed Mark as read
REMOTE_MARK_READ         an authenticated GMweb/Web command explicitly asked for it
INCOMING_WHILE_VISIBLE   a message arrived while that SAME screen was RESUMED
```

Everything else — Home, another conversation, background, a surviving ViewModel, a loader, cache
hydration, notification display or dismissal, provider/cloud sync, app merely being foreground — has no
authority and is recorded as a refusal: `SCREEN_NOT_VISIBLE`, `STALE_GENERATION`,
`CONVERSATION_MISMATCH`, `UNKNOWN_THREAD`, `APP_NOT_FOREGROUND`, `NO_USER_EVIDENCE`.

## Lifecycle change (before → after)

```text
BEFORE                                             AFTER
composition of ConversationScreen → visible        lifecycle ON_RESUME             → visible
ViewModel load path (onOpened)    → visible        ON_PAUSE / ON_STOP / ON_DESTROY → not visible
ViewModel.onCleared()             → not visible    DisposableEffect disposal       → not visible
app backgrounded                  → still visible  app backgrounded                → not visible
```

`isVisible(threadId)` is true only while `threadId > 0`, the screen is RESUMED, the app is foreground and
the identity matches. The thread id is authoritative; the normalized address is a fallback only for the
legacy `threadId == 0` case, so an unknown id can never match a stale chat (the
`Co5-p91`/`PARSIANBANK` failure mode). `SmsEventBus.activeConversationPhone` is set and cleared at the
same boundary, so the notification layer and the read authority cannot disagree. The ViewModel no longer
touches visibility at all.

## Read horizon

A mark-read is a bulk provider update, and a message can land between the user's decision and the
statement executing; the blind form would swallow it. `markThreadAsReadStrict` now captures the highest
row id per source before updating and bounds the write:

```sql
UPDATE sms SET read = 1 WHERE thread_id = ? AND read = 0 AND _id <= ?   -- the horizon
```

A row arriving after the boundary stays unread; if the user is genuinely looking at the thread, the
`INCOMING_WHILE_VISIBLE` authority reads that new row through its own decision. The two facts are not
conflated. If no horizon can be established the write degrades to the previous thread-scoped update
rather than refusing the user's explicit action.

## Diagnostics

Every unread → read transition and every refusal emits a privacy-safe `CONVERSATION_READ` line: cause or
skip reason, hashed thread token, `source`, `beforeRead`, `afterRead`, `screenLifecycle`,
`visibilityGeneration`. Never a body, a phone number or a credential.

## Tests and verification

* `VisibleConversationTrackerTest` (13 tests): the Co5-p91 regression, surviving-ViewModel case, process
  recreation, Home/background clearing, A→B and late teardown, unknown/zero thread ids, address-fallback
  rules, generation monotonicity, token privacy.
* `ReadHorizonSelectionTest` (5 tests): bounded update, post-decision row excluded, no-horizon
  degradation, non-positive horizons, read filter always present.

```text
compileDebugKotlin                                    PASS
targeted: VisibleConversationTrackerTest, ReadHorizonSelectionTest   PASS
NOT run: the full unit suite and instrumentation suites (deliberately kept short)
```

## NOT PHYSICALLY VERIFIED

Not proven on a handset. To confirm (no GMweb, no EVE involved): open conversation A, return to Home,
background the app, receive an SMS for A — Home must show A **unread**; then receive one for another
sender and confirm both stay unread and independent.
