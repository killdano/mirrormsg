package mirrorbridge

import (
	"context"
	"fmt"
	"testing"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// Google started answering the pairing's last step with error 32 when the
// request lacks the confirmation libgm v0.2609.0 sends. An older libgm doesn't
// know the code (it printed "3/32"); this fails if one comes back.
func TestLibgmKnowsAttestationError(t *testing.T) {
	if got := gmproto.GaiaPairingErrorCode(32).String(); got != "CLIENT_ATTESTATION_MISSING" {
		t.Fatalf("error code 32 is %q", got)
	}
}

func TestPairFailReason(t *testing.T) {
	for _, c := range []struct {
		err  error
		want string
	}{
		{libgm.ErrIncorrectEmoji, "wrong_emoji"},
		{fmt.Errorf("%w (code: 3/2)", libgm.ErrPairingTimeout), "expired"},
		{fmt.Errorf("failed to send client finish: %w", context.Canceled), "stopped"},
		{fmt.Errorf("failed to send client finish: %w", libgm.ErrConnectionClosed), "stopped"},
		{fmt.Errorf("unknown error pairing: 3/CLIENT_ATTESTATION_MISSING"), "other"},
	} {
		if got := pairFailReason(c.err); got != c.want {
			t.Errorf("pairFailReason(%v) = %q, want %q", c.err, got, c.want)
		}
	}
}
