# Google Play Protect appeal — Messages

Use the official **File a Play Protect appeal** action at:
<https://developers.google.com/android/play-protect/warning-dev-guidance#appeals>

## Application details

- App name: Messages
- Package: `com.autonomousone.messages`
- Distribution: GitHub Releases
- Source: <https://github.com/aibedini/Messages>
- Privacy policy: <https://github.com/aibedini/Messages/blob/main/PRIVACY.md>
- Release metadata: attach `release-metadata.txt` from the GitHub release

## Suggested appeal description

Messages is an open-source Android default SMS/MMS handler. Its primary and user-visible purpose is to display, send and receive SMS/MMS, so READ_SMS and RECEIVE_SMS are necessary core permissions. The app first presents a prominent disclosure, then asks the user to assign the Android default SMS role, and only after that role is held requests restricted SMS permissions. Contacts and notifications are requested separately and remain optional.

The optional SMS Gateway is disabled by default. It cannot start, register, send a heartbeat or forward message data until the user accepts a separate versioned disclosure describing the precise data, destination and remote-send capability. Revoking consent stops those operations and clears cloud credentials. All configured external endpoints require HTTPS. The privacy policy and complete source are public.

The warning appears when the signed APK is installed from GitHub/File Manager and matches the Play Protect internet-sideload sensitive-permission warning. Please review the attached APK and its signing certificate and correct the classification if the application complies with Play Protect policies.

## Submission checklist

- Upload the exact APK from the GitHub release, without rebuilding or renaming it.
- Copy the APK SHA-256 and signing-certificate SHA-256 from `release-metadata.txt`.
- Include a screenshot of the blocked dialog and the GitHub release URL.
- Keep the confirmation email/case ID with the release record.
