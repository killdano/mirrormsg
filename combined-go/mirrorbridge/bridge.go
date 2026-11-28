// Package mirrorbridge is a thin, gomobile-friendly wrapper around libgm
// (go.mau.fi/mautrix-gmessages/pkg/libgm). It exposes a FLAT API — only
// strings, []byte, ints, bools, and a single callback interface — so it can be
// compiled into an Android AAR with `gomobile bind` and called from Kotlin.
//
// The reason this wrapper has to exist: libgm's real API is protobuf-heavy
// (almost every method returns a *gmproto.* type, and SetEventHandler takes
// func(evt any) dispatching opaque event structs). None of that crosses the
// JNI boundary cleanly. So everything that touches gmproto / libgm internals
// stays on the Go side of this file; Kotlin only ever sees JSON strings.
//
// Pinned against libgm v0.2611.0 (November 2026). If Google changes the protocol
// and libgm bumps, re-check the spots marked "VERIFY" below against the new source.
package mirrorbridge

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"image"
	png_pkg "image/png"
	"io"
	"os"
	"sync"
	"time"

	"fi.mirrormsg/watchimage"
	"github.com/rs/zerolog"
	"go.mau.fi/util/exhttp"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// EventSink is implemented on the Kotlin side. The Go event loop calls OnEvent
// for every incoming message, conversation update, pairing step, etc.
//   - eventType is a stable string tag (MESSAGE, CONVERSATION, PAIR_EMOJI,
//     PAIR_STEP, PAIR_LOG, PAIR_FAILED, PAIRED, LOGGED_OUT, USER_ALERT,
//     ACCOUNT_CHANGE, UNKNOWN).
//   - jsonPayload is the event marshaled to JSON.
//
// gomobile turns this into a Java/Kotlin interface you implement and pass in.
type EventSink interface {
	OnEvent(eventType string, jsonPayload string)
}

// Bridge wraps a single libgm client plus its lifecycle. One Bridge == one
// paired Google Messages session.
type Bridge struct {
	client *libgm.Client
	sink   EventSink
	ctx    context.Context
	cancel context.CancelFunc
	mu     sync.Mutex

	// Pairing (bridge_pair.go): the logger, and the tap that copies libgm's
	// log lines to the sink while a pairing runs.
	log zerolog.Logger
	tap *pairTap
}

// httpSettings are the HTTP limits libgm used before it took them from its
// caller (v0.2605.0): 10 s to connect and for TLS, 20 s for the response
// headers, 2 min for a request. libgm gives the long poll its own 30 min.
var httpSettings = exhttp.SensibleClientSettings.
	WithResponseHeaderTimeout(20 * time.Second).
	WithGlobalTimeout(2 * time.Minute)

// bg is the context for libgm calls outside pairing. It is never b.ctx:
// Disconnect cancels that, and the app connects the same Bridge again after a
// Disconnect (a network switched off and on). libgm's own timeouts still
// apply. It carries the logger, since libgm logs through the context too.
func (b *Bridge) bg() context.Context {
	return b.log.WithContext(context.Background())
}

func newLogger(tap *pairTap) zerolog.Logger {
	// Logs go to stderr, which shows up in Logcat under the process. Drop the
	// level to Debug while spiking; raise it later. The tap forwards a filtered
	// copy to the app's own log while a pairing runs (see pairTap).
	return zerolog.New(io.MultiWriter(
		zerolog.ConsoleWriter{Out: os.Stderr, TimeFormat: time.RFC3339}, tap)).
		Level(zerolog.DebugLevel).
		With().Timestamp().Logger()
}

// NewBridge creates a bridge. If sessionJSON is non-empty it is treated as a
// previously exported session (see ExportSession) and restored, so no pairing
// is needed. Otherwise a fresh, unpaired client is created and you must pair
// (StartGaiaPairing or StartQRPairing) before Connect.
// SetImageConfig forwards the user's Image Quality dials to the shared watchimage
// encoder. Exposed here (rather than binding the watchimage package directly) so
// gomobile generates a Kotlin-callable entry point without having to bind
// watchimage's other exports (which take stdlib interface types it can't bind).
// Coverages are fractions in (0,1]; caps are a colour COUNT (2/4/16). Called from
// Kotlin at startup and on every settings change.
func SetImageConfig(thumbCoverage, fullCoverage float64, thumbCapColors, fullCapColors int, compressFit bool, dither bool, ditherEdge, ditherStrength, ditherIntensity int) {
	watchimage.SetConfig(thumbCoverage, fullCoverage, thumbCapColors, fullCapColors, compressFit, dither, ditherEdge, ditherStrength, ditherIntensity)
}

// EncodeImageForWatch is a BRIDGE-INDEPENDENT (package-level) image scaler: it runs
// the exact same watchimage pipeline as (*Bridge).ProcessInlineImageForWatch, but
// without needing a *Bridge instance. Exposed for callers that scale images with no
// Google bridge alive — notably the Beeper image path, which is serverless and has
// no Go bridge of its own, and previously borrowed (*Bridge).ProcessInlineImageForWatch
// via Mirror.bridge (null when logged out -> "scaler unavailable"). watchimage is
// standalone pure Go, so scaling has no reason to depend on the Google bridge's
// lifecycle. Same input (base64 image bytes) and same JSON output shape as the
// method form. Mirrors the SetImageConfig pattern so gomobile binds it cleanly.
//
// imageDataB64 — base64 JPEG/PNG bytes (e.g. a cached Beeper PNG).
// maxW, maxH   — bounding box; scaled to fit preserving aspect ratio.
// Returns JSON: {"w":N,"h":N,"pixels":"<base64>", ...}
func EncodeImageForWatch(imageDataB64 string, maxW, maxH, maxBytes, quality int) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(imageDataB64)
	if err != nil {
		raw, err = base64.RawStdEncoding.DecodeString(imageDataB64)
		if err != nil {
			return "", fmt.Errorf("bad imageData base64: %w", err)
		}
	}
	return watchimage.EncodeImageForWatch(raw, maxW, maxH, maxBytes, quality)
}

func NewBridge(sessionJSON string, sink EventSink) (*Bridge, error) {
	authData := libgm.NewAuthData()
	if sessionJSON != "" {
		// AuthData has json tags on every field, so the session round-trips
		// through plain encoding/json.
		if err := json.Unmarshal([]byte(sessionJSON), authData); err != nil {
			return nil, fmt.Errorf("failed to restore session: %w", err)
		}
	}
	tap := &pairTap{sink: sink}
	logger := newLogger(tap)
	client := libgm.NewClient(authData, nil, logger, httpSettings)
	ctx, cancel := context.WithCancel(context.Background())
	b := &Bridge{
		client: client,
		sink:   sink,
		ctx:    ctx,
		cancel: cancel,
		log:    logger,
		tap:    tap,
	}
	client.SetEventHandler(b.handleEvent)
	return b, nil
}

// emit marshals payload (a proto.Message, a pre-built JSON string, or any
// json-able value) and forwards it to the Kotlin sink under the given tag.
func (b *Bridge) emit(eventType string, payload any) {
	emitTo(b.sink, eventType, payload)
}

// emitTo is emit for code that has the sink but not the Bridge (pairTap).
func emitTo(sink EventSink, eventType string, payload any) {
	if sink == nil {
		return
	}
	var data string
	switch p := payload.(type) {
	case proto.Message:
		bytes, err := protojson.Marshal(p)
		if err != nil {
			data = fmt.Sprintf(`{"error":%q}`, err.Error())
		} else {
			data = string(bytes)
		}
	case string:
		data = p
	default:
		bytes, err := json.Marshal(p)
		if err != nil {
			data = fmt.Sprintf(`{"error":%q}`, err.Error())
		} else {
			data = string(bytes)
		}
	}
	sink.OnEvent(eventType, data)
}

// handleEvent is libgm's EventHandler (func(evt any)). We type-switch on the
// events that matter for a read spike and forward them as JSON. Anything
// unrecognized is forwarded under "UNKNOWN" with its Go type name, so you can
// see it in Logcat and decide whether to handle it.
//
// The event types here are taken straight from libgm's event_handler.go
// (v0.2611.0): incoming messages arrive as *libgm.WrappedMessage, conversation
// updates as *gmproto.Conversation, etc.
func (b *Bridge) handleEvent(evt any) {
	switch e := evt.(type) {
	case *libgm.WrappedMessage:
		// An incoming or backfilled message. e.Message is *gmproto.Message.
		// e.IsOld distinguishes history backfill from live messages — this is
		// your delta signal: IsOld == false means "new since we connected".
		b.emit("MESSAGE", e.Message)
	case *gmproto.Conversation:
		// A conversation was added or updated.
		b.emit("CONVERSATION", e)
	case *gmproto.UserAlertEvent:
		b.emit("USER_ALERT", e)
	case *events.GaiaLoggedOut:
		// Pairing/session died — Kotlin should prompt a re-pair.
		b.emit("LOGGED_OUT", `{"reason":"gaia_logged_out"}`)
	case *events.AccountChange:
		b.emit("ACCOUNT_CHANGE",
			fmt.Sprintf(`{"account":%q,"enabled":%t}`, e.GetAccount(), e.GetEnabled()))
	default:
		// Include %+v so error-bearing events (ListenTemporaryError,
		// PingFailed, HackySetActiveMayFail, etc.) reveal their actual cause,
		// not just the Go type name.
		b.emit("EVENT", fmt.Sprintf(`{"goType":%q,"detail":%q}`,
			fmt.Sprintf("%T", evt), fmt.Sprintf("%+v", evt)))
	}
}

// ---- Pairing: Google-account (Gaia) path -----------------------------------

// SetCookies provides Google-account cookies (a JSON object of name -> value)
// for Gaia pairing. Required before StartGaiaPairing. Obtain these from a
// logged-in messages.google.com session in a browser (devtools -> Application
// -> Cookies), e.g. {"SID":"...","HSID":"...","SSID":"...", ...}.
func (b *Bridge) SetCookies(cookiesJSON string) error {
	var cookies map[string]string
	if err := json.Unmarshal([]byte(cookiesJSON), &cookies); err != nil {
		return fmt.Errorf("invalid cookies JSON: %w", err)
	}
	b.client.AuthData.SetCookies(cookies)
	return nil
}

// StartGaiaPairing and SetPairingTarget live in bridge_pair.go.

// ---- Pairing: QR path (alternative) ----------------------------------------

// StartQRPairing begins QR-code pairing and returns a QR payload string. Render
// it as a QR code and scan it with the Google Messages app
// (Settings -> Device pairing -> QR code scanner). Completion arrives later as
// a "PAIRED" event; poll IsLoggedIn or wait for that event, then Connect.
//
// VERIFY: that StartLogin() returns the scannable QR payload directly for your
// libgm version (vs. needing GenerateQRCodeData). The Gaia path above is the
// recommended one; this exists as a fallback if your account offers QR pairing.
func (b *Bridge) StartQRPairing() (string, error) {
	cb := func(data *gmproto.PairedData) {
		b.emit("PAIRED", data)
	}
	b.client.PairCallback.Store(&cb)
	// The QR long poll runs until pairing ends or Disconnect cancels b.ctx.
	return b.client.StartLogin(b.log.WithContext(b.ctx))
}

// ---- Connection ------------------------------------------------------------

// Connect starts the long-poll. After this returns, incoming messages begin
// arriving as MESSAGE events on the sink. Blocking network call — keep it off
// the main thread.
func (b *Bridge) Connect() error {
	return b.client.Connect(b.bg())
}

// Disconnect stops the long-poll and cancels in-flight pairing.
func (b *Bridge) Disconnect() {
	b.cancel()
	b.client.Disconnect()
}

// IsLoggedIn reports whether the session is paired and usable.
func (b *Bridge) IsLoggedIn() bool {
	return b.client.IsLoggedIn()
}

// ExportSession serializes the paired session so it can be restored later via
// NewBridge. Persist this securely (EncryptedSharedPreferences). Returns JSON.
func (b *Bridge) ExportSession() (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	bytes, err := json.Marshal(b.client.AuthData)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// ---- Reading ---------------------------------------------------------------

// ListConversations returns up to count inbox conversations as a JSON object
// (the marshaled ListConversationsResponse, including a cursor for paging).
func (b *Bridge) ListConversations(count int) (string, error) {
	// VERIFY: ListConversationsRequest_INBOX is the expected inbox folder enum;
	// confirm the enum value name if this fails to compile.
	resp, err := b.client.ListConversations(b.bg(), &gmproto.ListConversationsRequest{
		Count: int64(count), Folder: gmproto.ListConversationsRequest_INBOX})
	if err != nil {
		return "", err
	}
	bytes, err := protojson.Marshal(resp)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// FetchMessages returns up to count messages from a conversation as JSON. Pass
// an empty cursorJSON for the most recent messages; to page further back, pass
// the cursor (JSON) from a previous response. This is the backfill half of your
// cache-and-delta design.
func (b *Bridge) FetchMessages(conversationID string, count int64, cursorJSON string) (string, error) {
	var cursor *gmproto.Cursor
	if cursorJSON != "" {
		cursor = &gmproto.Cursor{}
		if err := protojson.Unmarshal([]byte(cursorJSON), cursor); err != nil {
			return "", fmt.Errorf("invalid cursor JSON: %w", err)
		}
	}
	resp, err := b.client.FetchMessages(b.bg(), conversationID, count, cursor)
	if err != nil {
		return "", err
	}
	bytes, err := protojson.Marshal(resp)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// ListContacts returns the contact list as JSON (used to label conversations
// and to power a contact picker on the watch).
func (b *Bridge) ListContacts() (string, error) {
	resp, err := b.client.ListContacts(b.bg())
	if err != nil {
		return "", err
	}
	bytes, err := protojson.Marshal(resp)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// ---- Sending (not needed for the pair+read spike) --------------------------

// SendMessage sends a text message to an existing conversation.
//
// conversationID  — the conversation ID string (e.g. "1", "488").
// text            — the message body.
// participantID   — the defaultOutgoingID from the conversation (e.g. "2").
//
//	This is the "me" participant on the SIM to send from.
//
// simTwo          — SIMPayload.Two value from the conversation's simCard (e.g. 2).
// simNumber       — SIMPayload.SIMNumber from the conversation's simCard (e.g. 2).
//
// All three routing values come from the ListConversations response and are
// stored by the Android side when building the convoOrder list.
func (b *Bridge) SendMessage(conversationID, text, participantID string, simTwo, simNumber int64) (string, error) {
	tmpID := fmt.Sprintf("%d", time.Now().UnixNano())
	req := &gmproto.SendMessageRequest{
		ConversationID: conversationID,
		TmpID:          tmpID,
		SIMPayload: &gmproto.SIMPayload{
			Two:       int32(simTwo),
			SIMNumber: int32(simNumber),
		},
		MessagePayload: &gmproto.MessagePayload{
			TmpID:                 tmpID,
			TmpID2:                tmpID,
			ConversationID:        conversationID,
			ParticipantID:         participantID,
			MessagePayloadContent: nil,
			MessageInfo: []*gmproto.MessageInfo{{
				Data: &gmproto.MessageInfo_MessageContent{
					MessageContent: &gmproto.MessageContent{
						Content: text,
					},
				},
			}},
		},
	}
	resp, err := b.client.SendMessage(b.bg(), req)
	if err != nil {
		return "", err
	}
	bytes, err := protojson.Marshal(resp)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// SendReply sends a text as an RCS reply to targetMsgID. Identical to SendMessage
// but sets the request's Reply payload (the quoted message's id). Returns the same
// protojson SendMessageResponse so the adapter can read the new message id.
func (b *Bridge) SendReply(conversationID, text, participantID, targetMsgID string, simTwo, simNumber int64) (string, error) {
	tmpID := fmt.Sprintf("%d", time.Now().UnixNano())
	req := &gmproto.SendMessageRequest{
		ConversationID: conversationID,
		TmpID:          tmpID,
		SIMPayload: &gmproto.SIMPayload{
			Two:       int32(simTwo),
			SIMNumber: int32(simNumber),
		},
		MessagePayload: &gmproto.MessagePayload{
			TmpID:          tmpID,
			TmpID2:         tmpID,
			ConversationID: conversationID,
			ParticipantID:  participantID,
			MessageInfo: []*gmproto.MessageInfo{{
				Data: &gmproto.MessageInfo_MessageContent{
					MessageContent: &gmproto.MessageContent{Content: text},
				},
			}},
		},
		Reply: &gmproto.ReplyPayload{MessageID: targetMsgID},
	}
	resp, err := b.client.SendMessage(b.bg(), req)
	if err != nil {
		return "", err
	}
	if resp.GetStatus() != gmproto.SendMessageResponse_SUCCESS {
		return "", fmt.Errorf("reply send status: %v", resp.GetStatus())
	}
	// SendMessageResponse carries no message id (only a status); the real reply
	// echoes back as a normal MESSAGE event (with its replyMessage field, which the
	// phone renders). Return the temp id so the adapter's send-success check — which
	// needs a non-empty messageId — passes without falling back to a plain send.
	out, _ := json.Marshal(map[string]string{"messageId": tmpID})
	return string(out), nil
}

// SendReaction adds (remove=false) or removes (remove=true) a tapback reaction on
// targetMsgID. gmproto.MakeReactionData maps the emoji to RCS's tapback set (or
// CUSTOM for an arbitrary unicode emoji). Returns {"messageId": targetMsgID} on
// success so the adapter's send-result check passes; the reaction itself surfaces
// back through the message's embedded reactions on the next update.
func (b *Bridge) SendReaction(targetMsgID, emoji string, remove bool) (string, error) {
	action := gmproto.SendReactionRequest_ADD
	if remove {
		action = gmproto.SendReactionRequest_REMOVE
	}
	resp, err := b.client.SendReaction(b.bg(), &gmproto.SendReactionRequest{
		MessageID:    targetMsgID,
		ReactionData: gmproto.MakeReactionData(emoji),
		Action:       action,
	})
	if err != nil {
		return "", err
	}
	if !resp.GetSuccess() {
		return "", fmt.Errorf("reaction send returned unsuccessful")
	}
	out, _ := json.Marshal(map[string]string{"messageId": targetMsgID})
	return string(out), nil
}

// MarkRead marks the conversation read UP THROUGH messageID: libgm sends a read
// receipt to Google Messages, clearing the thread's unread state on the source
// (not just dismissing a local notification). Mirrors the SendReaction shape:
// returns a small ok-JSON, or an error the caller surfaces.
func (b *Bridge) MarkRead(conversationID, messageID string) (string, error) {
	if err := b.client.MarkRead(b.bg(), conversationID, messageID); err != nil {
		return "", err
	}
	out, _ := json.Marshal(map[string]string{"conversationId": conversationID, "messageId": messageID})
	return string(out), nil
}

// StartNewConversation returns the conversation with phoneNumber, creating it when
// there is none (GetOrCreateConversation), as protojson of the response. The number
// is given the way mautrix-gmessages' ResolveIdentifier gives it (startchat.go).
func (b *Bridge) StartNewConversation(phoneNumber string) (string, error) {
	req := &gmproto.GetOrCreateConversationRequest{
		Numbers: []*gmproto.ContactNumber{{
			MysteriousInt: 2,
			Number:        phoneNumber,
			Number2:       phoneNumber,
		}},
	}
	resp, err := b.client.GetOrCreateConversation(b.bg(), req)
	if err != nil {
		return "", err
	}
	bytes, err := protojson.Marshal(resp)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// ListPeople returns the contacts a new chat can start with (the watch's New chat):
// the ones written to most first (ListTopContacts), then the rest of the list, as
// {"people":[{"id","name","detail","rank","color"}]}. id is the number
// OpenPerson takes; rank 1.. for the top ones.
func (b *Bridge) ListPeople() (string, error) {
	type person struct {
		ID     string `json:"id"`
		Name   string `json:"name"`
		Detail string `json:"detail,omitempty"`
		Rank   int    `json:"rank,omitempty"`
		Color  string `json:"color,omitempty"`
	}
	out := []person{}
	add := func(cs []*gmproto.Contact, ranked bool) {
		for i, c := range cs {
			num := c.GetNumber()
			if num == nil || num.GetNumber() == "" || c.GetName() == "" {
				continue
			}
			p := person{ID: num.GetNumber(), Name: c.GetName(), Detail: num.GetFormattedNumber(),
				Color: c.GetAvatarHexColor()}
			if p.Detail == "" {
				p.Detail = num.GetNumber()
			}
			if ranked {
				p.Rank = i + 1
			}
			out = append(out, p)
		}
	}
	if top, err := b.client.ListTopContacts(b.bg()); err == nil {
		add(top.GetContacts(), true)
	}
	all, err := b.client.ListContacts(b.bg())
	if err != nil && len(out) == 0 {
		return "", err
	}
	if err == nil {
		add(all.GetContacts(), false)
	}
	bs, _ := json.Marshal(map[string]any{"people": out})
	return string(bs), nil
}

// OpenPerson returns the conversation with the number from ListPeople (theirs, or a
// new one Google Messages makes now) as its protojson Conversation: the same fields
// ListConversations gives for it, so its SIM routing reads the same way.
func (b *Bridge) OpenPerson(number string) (string, error) {
	req := &gmproto.GetOrCreateConversationRequest{
		Numbers: []*gmproto.ContactNumber{{MysteriousInt: 2, Number: number, Number2: number}},
	}
	resp, err := b.client.GetOrCreateConversation(b.bg(), req)
	if err != nil {
		return "", err
	}
	if resp.GetConversation() == nil {
		return "", fmt.Errorf("no conversation (status %v)", resp.GetStatus())
	}
	bytes, err := protojson.Marshal(resp.GetConversation())
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// ---- Image download for watch -----------------------------------------

func pngEncodeImg(w *bytes.Buffer, img image.Image) error {
	return png_pkg.Encode(w, img)
}

// DownloadImageForWatch downloads, decrypts, scales, and quantizes a media
// attachment into Pebble GColor8 format.
//
// mediaID          — MediaContent.MediaID from the message JSON.
// decryptionKeyB64 — MediaContent.DecryptionKey (base64, as protojson emits it).
// maxW, maxH       — bounding box; image is scaled to fit while preserving AR.
//
// Returns JSON: {"w":N,"h":N,"pixels":"<base64>"}
func (b *Bridge) DownloadImageForWatch(mediaID, decryptionKeyB64 string, maxW, maxH, maxBytes, quality int) (string, error) {
	// Decode the decryption key from base64.
	key, err := base64.StdEncoding.DecodeString(decryptionKeyB64)
	if err != nil {
		// Try RawStdEncoding (no padding) in case protojson omitted it.
		key, err = base64.RawStdEncoding.DecodeString(decryptionKeyB64)
		if err != nil {
			return "", fmt.Errorf("bad decryptionKey base64: %w", err)
		}
	}

	// Download and decrypt.
	rawBytes, err := b.downloadMedia(mediaID, key)
	if err != nil {
		return "", err
	}
	return watchimage.EncodeImageForWatch(rawBytes, maxW, maxH, maxBytes, quality)
}

// downloadMedia downloads and decrypts a message's media whole. libgm streams
// it and checks each chunk as it is read, so a bad key or a cut-off download
// shows up here as an error, never as part of the bytes.
func (b *Bridge) downloadMedia(mediaID string, key []byte) ([]byte, error) {
	r, err := b.client.DownloadMedia(b.bg(), mediaID, key)
	if err != nil {
		return nil, fmt.Errorf("DownloadMedia: %w", err)
	}
	defer r.Close()
	raw, err := io.ReadAll(r)
	if err != nil {
		return nil, fmt.Errorf("DownloadMedia: %w", err)
	}
	return raw, nil
}

// ProcessInlineImageForWatch handles images that arrive INLINE in the message
// (MMS and the intermediate INCOMING_COMPLETE state carry the JPEG directly in
// MediaContent.MediaData as base64) — no download/decrypt needed. Runs the same
// scale + GColor8 quantize pipeline as the download path.
//
// mediaDataB64 — MediaContent.MediaData (base64 JPEG/PNG bytes).
// maxW, maxH   — bounding box; scaled to fit preserving aspect ratio.
// Returns JSON: {"w":N,"h":N,"pixels":"<base64>"}
func (b *Bridge) ProcessInlineImageForWatch(mediaDataB64 string, maxW, maxH, maxBytes, quality int) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(mediaDataB64)
	if err != nil {
		raw, err = base64.RawStdEncoding.DecodeString(mediaDataB64)
		if err != nil {
			return "", fmt.Errorf("bad mediaData base64: %w", err)
		}
	}
	return watchimage.EncodeImageForWatch(raw, maxW, maxH, maxBytes, quality)
}
