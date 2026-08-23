# Messages v1.2.1 — privacy and Play Protect readiness

This release introduces a policy-aligned setup flow and explicit controls for sensitive SMS data.

## Highlights

- Default SMS role is requested before restricted SMS permissions.
- No permission dialog launches automatically on first start.
- Contacts and notifications are optional and requested separately.
- Permanently denied permissions link safely to Android app settings.
- SMS Gateway requires separate, versioned consent before any service, registration, heartbeat or webhook can start.
- Revoking Gateway consent stops networking and forwarding and clears cloud credentials.
- Unused Phone State, Boot and unrelated foreground-service permissions were removed.
- A public privacy policy and reproducible signed-release metadata are included.

## Play Protect notice

Google Play Protect may still block internet-sideloaded SMS applications that declare READ_SMS or RECEIVE_SMS while Google reviews their classification. This release does not disable or bypass Play Protect. The project will use Google's official appeal process.
