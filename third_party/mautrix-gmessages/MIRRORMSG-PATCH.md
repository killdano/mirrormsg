# MirrorMsg patch to mautrix-gmessages v0.2605.0

This copy of mautrix-gmessages (AGPL-3.0) is the v0.2605.0 release (module
`go.mau.fi/mautrix-gmessages`, `h1:WbDNDC7GivaW92g2qrx6ibfTZ0R5+CfJunJv/NICsW4=`)
with two changes, made for MirrorMsg on 2026-11-28:

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

**Ids sent in the new format are filled in** (from v0.2611.0, "Fixed reading
chat/user IDs from messages when the phone sends them in a new format"). The
phone can leave a conversation's `conversationID` and `latestMessageID`, and a
message's `messageID` and `conversationID`, empty and send them only in the
nested `someKindOfGroupID`, `latestMessageAnoherID` and `anotherMessageID`
fields. Without the top-level ids, every chat in the list has an empty id, so
opening one asks for the messages of chat "" and gets none, and live messages
are dropped.

- `pkg/libgm/event_handler.go`: `decryptInternalMessage` calls the new
  `fillMissingIDs` on each decrypted data message, as v0.2611.0 does. It fills
  the empty top-level ids of the conversations and messages in `UpdateEvents`,
  `ListConversationsResponse`, `GetConversationResponse`,
  `GetOrCreateConversationResponse` and `ListMessagesResponse` from the nested
  fields; ids already set are left alone. v0.2605.0's gmproto spells field 35
  `latestMessageAnoherID` (v0.2611.0 renamed it `latestMessageAnotherID`).
- `pkg/libgm/ids_mirrormsg_test.go`: checks each of those types, ids already
  set, and a new-format chat list going through `decryptInternalMessage`
  (`go test -run 'TestFillMissingIDs|TestDecryptFills' go.mau.fi/mautrix-gmessages/pkg/libgm`).

Nothing else from later releases is backported (for example error codes 32
to 35 in the enum, the ECDH rewrite, re-pairing with the same phone).

Remove this copy (and the `replace` line in MirrorMsg's `combined-go/go.mod`)
when moving to mautrix-gmessages v0.2611.0 or later.
