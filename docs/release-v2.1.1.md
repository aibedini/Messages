# Release v2.1.1 — Gateway screen: step-by-step setup + shared API key

## Changed — Gateway UX rebuilt around a 3-step flow

The old screen mixed privacy, cloud, GMweb, LAN server, endpoints and
webhooks with no sense of order or purpose. Rebuilt as a guided flow:

- **"How this works" card** at the top: three numbered steps explaining what
  this page is for (turn on → connect server → share key).
- **Step 1 · Privacy** — clear before/after state; explains nothing leaves the
  phone until the gateway is ON.
- **Step 2 · Connect GMweb server** (recommended path) now includes:
  - Server URL field with https validation message.
  - **Shared API key field** (masked, show/hide toggle): paste the server's
    `GMWEB_ANDROID_DEVICE_KEY` and it becomes this phone's `X-API-Key`.
  - Live hint showing the current key's first/last chars so you can compare
    both sides.
  - Server-side `.env` reminder + "device appears online within ~25s".
- Cloud backend and LAN cards follow as alternative paths.

### Why the shared-key direction matters
Previously the phone GENERATED its own key and you had to copy it into the
server by hand from a cramped row. Now either direction works — paste the
server-generated key here and both sides match without transcribing a long
random string twice.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.1.0...v2.1.1
