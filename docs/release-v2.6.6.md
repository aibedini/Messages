# Release v2.6.6 — Build no longer depends on JitPack

**versionCode 48 · 2026-08-28**

Includes everything from v2.6.5 (conversation open = latest window first —
see release-v2.6.5.md). The only code change here is build infrastructure,
but it is the reason v2.6.5 never got a green CI run or a published APK.

## Problem
Every CI run for v2.6.4 and v2.6.5 failed with:

```
Could not resolve org.fossify:mmslib:1.0.0
> Could not GET 'https://www.jitpack.io/.../mmslib-1.0.0.pom' > Read timed out
```

Verified during the outage:
- JitPack itself: connection times out (0 bytes after 25s+), reruns unchanged.
- Maven Central: healthy (200 in 0.4s) — this is a JitPack-side outage, not ours.
- `org.fossify:mmslib` is published on JitPack ONLY (Central 404,
  `search.maven.org` numFound=0), so no repository switch can work around it.
- Local Gradle cache held a complete, working copy (aar + pom + module).

## Fix — vendor the artifact
`org.fossify:mmslib:1.0.0` now ships as `app/libs/mmslib-1.0.0.aar`
(SHA-256 `21070df1daf7a798ce19e5e46c06ece8ab902ae728990317613ffbbd6b23d5ef`,
663,985 bytes, taken byte-for-byte from the Gradle module cache entry
originally fetched from JitPack).

- `app/build.gradle.kts`: `implementation(libs.mmslib)` →
  `implementation(files("libs/mmslib-1.0.0.aar"))`.
- A file dependency carries NO metadata, so the three runtime dependencies
  declared in the published POM are now explicit Central coords:
  `com.klinkerapps:logger:1.0.3`, `com.squareup.okhttp:okhttp:2.5.0`,
  `com.squareup.okhttp:okhttp-urlconnection:2.5.0` — all verified present on
  Maven Central.
- `settings.gradle.kts`: JitPack repository removed entirely; grep confirms
  nothing else in the build referenced it.

## Verification
- `:app:dependencies --configuration debugRuntimeClasspath
  --refresh-dependencies`: resolution completes with ZERO FAILED lines —
  every artifact now comes from google()/mavenCentral()/aliyun-mirror only.
- `testDebugUnitTest assembleDebug` after the switch: BUILD SUCCESSFUL.
- Local classpath is unchanged: same aar bytes, same transitive coords.

## Upgrading mmslib in the future
Replacing `app/libs/mmslib-1.0.0.aar` is a manual copy (from a working
JitPack fetch or from building FossifyOrg/mmslib locally), plus re-checking
its POM for transitive changes. The longer-term home for this is our own
Maven publication or a fork-build (tracked as a backlog item, aligned with
the zero-third-party-infrastructure principle).
