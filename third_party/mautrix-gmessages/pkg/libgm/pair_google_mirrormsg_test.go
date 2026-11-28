package libgm

// MirrorMsg's test of its v0.2609.0 backport (see MIRRORMSG-PATCH.md).

import (
	"testing"

	"google.golang.org/protobuf/encoding/protowire"
	"google.golang.org/protobuf/proto"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// gaiaRequestField8 returns field 8 of a marshaled GaiaPairingRequestContainer.
func gaiaRequestField8(t *testing.T, b []byte) (string, bool) {
	t.Helper()
	for len(b) > 0 {
		num, typ, n := protowire.ConsumeTag(b)
		if n < 0 {
			t.Fatalf("bad tag: %v", protowire.ParseError(n))
		}
		b = b[n:]
		if num == gaiaPrivateAPIConfirmationField {
			if typ != protowire.BytesType {
				t.Fatalf("field 8 has wire type %v, want bytes", typ)
			}
			v, m := protowire.ConsumeString(b)
			if m < 0 {
				t.Fatalf("bad field 8: %v", protowire.ParseError(m))
			}
			return v, true
		}
		m := protowire.ConsumeFieldValue(num, typ, b)
		if m < 0 {
			t.Fatalf("bad field %d: %v", num, protowire.ParseError(m))
		}
		b = b[m:]
	}
	return "", false
}

func TestGaiaFinishCarriesPrivateAPIConfirmation(t *testing.T) {
	ps := NewPairingSession(primaryDeviceID{})
	req, msgType := newGaiaPairingRequest(ps, gmproto.ActionType_CREATE_GAIA_PAIRING_CLIENT_FINISHED, []byte{1, 2, 3})
	if msgType != gmproto.MessageType_BUGLE_MESSAGE {
		t.Fatalf("finish message type %v, want BUGLE_MESSAGE", msgType)
	}
	b, err := proto.Marshal(req)
	if err != nil {
		t.Fatal(err)
	}
	got, ok := gaiaRequestField8(t, b)
	if !ok {
		t.Fatal("finish request has no field 8")
	}
	if got != gaiaPrivateAPIConfirmation {
		t.Fatalf("field 8 = %q", got)
	}
	// The known fields still round-trip.
	var back gmproto.GaiaPairingRequestContainer
	if err := proto.Unmarshal(b, &back); err != nil {
		t.Fatal(err)
	}
	if back.GetPairingAttemptID() != ps.UUID.String() || string(back.GetData()) != "\x01\x02\x03" ||
		back.GetProposedVerificationCodeVersion() != 0 || back.GetProposedKeyDerivationVersion() != 0 {
		t.Fatalf("finish request fields changed: %v", &back)
	}
}

func TestGaiaInitUnchanged(t *testing.T) {
	ps := NewPairingSession(primaryDeviceID{})
	req, msgType := newGaiaPairingRequest(ps, gmproto.ActionType_CREATE_GAIA_PAIRING_CLIENT_INIT, []byte{1})
	if msgType != gmproto.MessageType_GAIA_2 {
		t.Fatalf("init message type %v, want GAIA_2", msgType)
	}
	b, err := proto.Marshal(req)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := gaiaRequestField8(t, b); ok {
		t.Fatal("init request has field 8; v0.2609.0 sends it with the finish only")
	}
	if req.GetProposedVerificationCodeVersion() != 1 || req.GetProposedKeyDerivationVersion() != 1 {
		t.Fatal("init request lost its proposed versions")
	}
}
