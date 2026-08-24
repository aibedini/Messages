# Release v2.0.4 — pasted phone numbers are cleaned before send/display

## Fixed

Copying a phone number like `+98 991 716 6454` (with spaces, as apps often
render them) and pasting it into New Conversation produced broken behaviour:

- The "Send SMS to …" card carried the spaced string through navigation.
- The chat header then showed the mangled `+98+991+716+6…` form.
- The number was handed to telephony with embedded spaces.

### Fixes
- `sendMessage()` / group recipients now run through `normalizePhone()`
  (strips spaces, dashes, parentheses and stray `+`) so the network always
  receives a clean dialable number — for SMS, MMS and gateway sends alike.
- MMS address resolution skips the `insert-address-token` placeholder and
  normalizes stored addresses, so headers can no longer render as
  `+98+991+716+6…`.

Display formatting (grouped digits like `0912 345 6789`) remains a pure
presentation concern; stored/sent numbers are always normalized.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.0.3...v2.0.4
