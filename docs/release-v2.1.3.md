# Release v2.1.3 — Gateway screen: GMweb is the only visible connection path

## Changed

The SMS Gateway screen previously showed FOUR parallel ways to connect a
server side by side — Cloud Backend Gateway, API Key Authentication,
REST API Endpoints (LAN/Cloud), Incoming SMS Webhook, and the GMweb
pull bridge. That made the screen confusing: which one actually matters?

### Fix

All advanced transport modes are now hidden behind a single feature flag
(`showAdvancedGatewayModes = false` in `GatewayScreen.kt`). The screen now
shows only:

1. Step 1 · Privacy consent
2. **Step 2 · Connect GMweb server** (the supported path)
3. Gateway status + Live logs

No code was deleted — flip the flag to `true` to re-expose the Cloud
Backend, LAN endpoints and Webhook cards later.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.1.2...v2.1.3
