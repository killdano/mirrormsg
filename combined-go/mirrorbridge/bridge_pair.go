package mirrorbridge

// Google-account (Gaia) pairing: start it, pick the phone, and report
// each step to the sink so the app's own log shows where a pairing stopped.

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync/atomic"
	"time"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
)

// libgm's own log message when the Google account has several phones that
// could take the pairing (pair_google.go, same text in v0.2609.0). VERIFY on a
// libgm bump: if the text changes, multiDevice just stays false.
const multiDeviceLogMsg = "Found multiple primary-looking devices for gaia pairing"

// StartGaiaPairing performs Google-account pairing. It BLOCKS until pairing
// completes or fails; Disconnect cancels it. Events, in order:
//   - PAIR_STEP {"step":"config","ok":bool,"signedIn":bool}: the web client's
//     config, fetched with the cookies (signedIn: it names a Google account).
//   - PAIR_LOG {"level","msg",...}: libgm's own log lines while pairing, with
//     only counts, timings, versions and errors kept (see pairTap).
//   - PAIR_EMOJI {"emoji"}: a phone answered; the user taps this emoji in
//     Google Messages.
//   - PAIR_FAILED {"reason","stage","multiDevice"} when it returns an error
//     (reasons: pairFailReason).
//
// On success the session is paired: call ExportSession to persist, then
// Connect. Requires SetCookies first.
func (b *Bridge) StartGaiaPairing() error {
	// libgm logs the pairing's details through the context's logger.
	ctx := b.log.WithContext(b.ctx)
	b.tap.multi.Store(false)
	b.tap.on.Store(true)
	defer b.tap.on.Store(false)

	// mautrix-gmessages' own Google login fetches the config before pairing:
	// it gives this client the device id Google assigned the signed-in browser
	// (without it the pairing request goes out under an all-zero one) and
	// refreshes the cookies. If it fails, pair anyway, as before.
	if err := b.client.FetchConfig(ctx); err != nil {
		b.emit("PAIR_STEP", map[string]any{"step": "config", "ok": false, "error": err.Error()})
	} else {
		b.emit("PAIR_STEP", map[string]any{"step": "config", "ok": true,
			"signedIn": b.client.Config.GetDeviceInfo().GetEmail() != ""})
	}

	emojiShown := false
	err := b.client.DoGaiaPairing(ctx, func(emoji string) {
		emojiShown = true
		// The func(string) is created here on the Go side, so nothing has to
		// cross JNI — we just forward the emoji to Kotlin via the sink.
		b.emit("PAIR_EMOJI", fmt.Sprintf(`{"emoji":%q}`, emoji))
	})
	if err != nil {
		stage := "start"
		if emojiShown {
			stage = "confirm"
		}
		b.emit("PAIR_FAILED", map[string]any{"reason": pairFailReason(err),
			"stage": stage, "multiDevice": b.tap.multi.Load()})
	}
	return err
}

// pairFailReason names why a pairing failed, for the app to explain it. The
// checks follow mautrix-gmessages' own login (pkg/connector/login.go).
func pairFailReason(err error) string {
	switch {
	case errors.Is(err, libgm.ErrNoCookies):
		return "no_cookies"
	case errors.Is(err, libgm.ErrNoDevicesFound):
		return "no_devices"
	case errors.Is(err, libgm.ErrPairingInitTimeout):
		return "phone_not_responding"
	case errors.Is(err, events.ErrCallerNoPermission):
		return "no_permission"
	case errors.Is(err, libgm.ErrIncorrectEmoji):
		return "wrong_emoji"
	case errors.Is(err, libgm.ErrPairingCancelled):
		return "cancelled_on_phone"
	case errors.Is(err, libgm.ErrPairingTimeout):
		return "expired" // Google said the request timed out
	case errors.Is(err, context.Canceled), errors.Is(err, libgm.ErrConnectionClosed):
		return "stopped" // Disconnect
	default:
		return "other"
	}
}

// SetPairingTarget picks which phone the next StartGaiaPairing asks when the
// Google account has more than one with Google Messages: 0 is the one seen
// most recently, 1 the next, and so on, wrapping round. With one phone it has
// no effect.
func (b *Bridge) SetPairingTarget(n int) {
	if n < 0 {
		n = 0
	}
	b.client.GaiaHackyDeviceSwitcher = n
}

// pairTap copies libgm's log lines to the sink as PAIR_LOG while a pairing
// runs, so the app's own log (Status -> View logs) shows where pairing
// stopped; otherwise libgm logs only to stderr. Only the message (libgm's
// messages are fixed text) and a few named fields are kept: counts, timings,
// versions and errors. Device ids, the account and tokens are never copied.
type pairTap struct {
	sink  EventSink
	on    atomic.Bool
	multi atomic.Bool // libgm saw several phones that could take the pairing
}

// Numeric fields copied as they are.
var pairTapFields = []string{"hacky_device_switcher", "key_derivation_version",
	"verification_code_version", "statusCode", "sleep_seconds"}

func (t *pairTap) Write(p []byte) (int, error) {
	if !t.on.Load() {
		return len(p), nil
	}
	var m map[string]any
	if json.Unmarshal(p, &m) != nil {
		return len(p), nil
	}
	msg, _ := m["message"].(string)
	if msg == multiDeviceLogMsg {
		t.multi.Store(true)
	}
	out := map[string]any{"level": m["level"], "msg": msg}
	if e, ok := m["error"].(string); ok {
		out["error"] = e
	}
	if d, ok := m["devices"].([]any); ok {
		out["devices"] = len(d) // phones that could take the pairing
	}
	if s, ok := m["dest_reg_last_seen"].(string); ok {
		// When the phone being asked was last online, as minutes ago.
		if ts, err := time.Parse(time.RFC3339, s); err == nil && ts.Year() > 2000 {
			out["lastSeenMin"] = int(time.Since(ts).Minutes())
		}
	}
	for _, k := range pairTapFields {
		if v, ok := m[k]; ok {
			out[k] = v
		}
	}
	emitTo(t.sink, "PAIR_LOG", out)
	return len(p), nil
}
