# Messages v2.6.40

Improves the GMweb PWA conversation model.

- Adds the normalized sender/recipient address to new `MESSAGE_CREATED` payload
  envelopes after the existing sensitive-message sync policy allows the event.
- Keeps the address inside the payload envelope rather than routing metadata.
- Lets GMweb v0.13.12 show a useful conversation title for newly synced
  messages instead of an opaque conversation UUID.
- Extends the envelope contract regression test to cover the address field.

Required server counterpart: GMweb v0.13.12 or newer.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.6.39...v2.6.40
