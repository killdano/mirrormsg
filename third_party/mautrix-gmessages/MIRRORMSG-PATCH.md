# MirrorMsg patch to mautrix-gmessages v0.2605.0

This copy of mautrix-gmessages (AGPL-3.0) is the v0.2605.0 release (module
`go.mau.fi/mautrix-gmessages`, `h1:WbDNDC7GivaW92g2qrx6ibfTZ0R5+CfJunJv/NICsW4=`)
with one change, made for MirrorMsg on 2026-11-28:

**The Google-account pairing's client-finished message carries
`privateAPIConfirmation`.** Since September 2026 Google answers a Gaia pairing
whose `CREATE_GAIA_PAIRING_CLIENT_FINISHED` message lacks
`GaiaPairingRequestContainer` field 8 with finish error 3/32
(`CLIENT_ATTESTATION_MISSING`), straight after the client has shown the
verification emoji and before it can be tapped on the phone. v0.2605.0 logs
this as `unknown error pairing: 3/32`. Upstream added the field, and the text
it carries, in v0.2609.0.

- `pkg/libgm/pair_google.go`: `sendGaiaPairingMessage` builds its request in
  the new `newGaiaPairingRequest`, which adds field 8 to the client-finished
  message only, as v0.2609.0 does. v0.2605.0's generated `gmproto` has no such
  field, so it is set as an unknown field; `proto.Marshal` writes it after the
  known fields, which gives the same bytes as v0.2609.0's generated code (checked
  against v0.2609.0 when the patch was made). `gmproto` is unchanged.
- `pkg/libgm/pair_google_mirrormsg_test.go`: checks that the finish request
  carries the field and the init request doesn't
  (from MirrorMsg's `combined-go`:
  `go test -run TestGaia go.mau.fi/mautrix-gmessages/pkg/libgm`).

Nothing else from later releases is backported (for example error codes 32
to 35 in the enum, the ECDH rewrite, re-pairing with the same phone).

Remove this copy (and the `replace` line in MirrorMsg's `combined-go/go.mod`)
when moving to mautrix-gmessages v0.2609.0 or later.
