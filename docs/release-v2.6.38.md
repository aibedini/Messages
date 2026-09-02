# Messages v2.6.38

Pairing identity bootstrap diagnostics:

- Preserve GMweb's safe identity-registration failure reason instead of hiding it behind a generic authentication error.
- Show the masked server device-key preview when the configured Android key does not match.
- Carry the exact registration failure into the pairing flow, so setup stops at identity registration instead of later surfacing as `unknown_device`.

The pairing and approval routes remain protected by per-device signatures; this release does not weaken the server trust boundary.

**Full Changelog**: https://github.com/aibedini/Messages/compare/v2.6.37...v2.6.38
