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
