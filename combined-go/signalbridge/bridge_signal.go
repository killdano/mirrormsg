// Package signalbridge is the native Signal bridge for MirrorMsg: signalmeow
// (mautrix-signal's client library) on the pure-Go libsignal backend, exposed
// through the same gomobile surface shape as the other native bridges.
//
// Build with `-tags libsignal_go` (selects the pure-Go libsignal backend; the
// default build selects the cgo/Rust one and will not link under gomobile).
//
// MILESTONE ZERO: pair as a linked device (QR), persist the session, receive
// live messages, send text. History sync (link-and-sync), media, reactions and
// replies come in later milestones.
package signalbridge

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	_ "image/gif"
	_ "image/jpeg"
	"image/png"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	_ "modernc.org/sqlite"

	"fi.mirrormsg/mediaroute"
	"fi.mirrormsg/watchimage"

	"github.com/google/uuid"
	"github.com/rs/zerolog"
	qrcode "github.com/skip2/go-qrcode"
	"go.mau.fi/util/dbutil"
	"golang.org/x/image/webp"
	"google.golang.org/protobuf/proto"

	"github.com/cwbudde/mautrix-signal/pkg/libsignalgo"
	"github.com/cwbudde/mautrix-signal/pkg/signalmeow"
	"github.com/cwbudde/mautrix-signal/pkg/signalmeow/events"
	"github.com/cwbudde/mautrix-signal/pkg/signalmeow/protobuf/signalpb"
	"github.com/cwbudde/mautrix-signal/pkg/signalmeow/store"
	"github.com/cwbudde/mautrix-signal/pkg/signalmeow/types"
)

// EventSink is implemented on the Kotlin side. Event tags:
//
//	QR {code}                       provisioning URL to render as a QR (refreshes ~45s)
//	PAIRED {number,aci}             the phone accepted the link; session stored
//	PAIR_ERROR {error,status}       linking failed (status = Signal HTTP status, 0 if n/a)
//	CONNECTED / DISCONNECTED        authed websocket state
//	LOGGED_OUT {reason}             the server unlinked us (re-pair needed)
//	MESSAGE <watch-schema json>     live message (incoming, or sent from any of our devices),
//	                                or a post in the "MirrorMsg · Signal" setup thread
//	REACTION {op,thread,msg,actor,emoji}  reaction added ("upsert") or removed ("delete")
//	CHATS_UPDATED {…}               the conversation list changed (new chat / names or photos learned)
//	QUEUE_EMPTY {}                  the server's offline backlog has been drained
//	DIAG <string>                   diagnostics -> exportable log
//	EVENT {goType}                  anything else, visible by type name during bring-up
type EventSink interface {
	OnEvent(eventType string, jsonPayload string)
}

const (
	deviceName      = "MirrorMsg"
	qrRefreshEvery  = 75 * time.Second
	maxLinkDuration = 15 * time.Minute
	maxMsgsPerChat  = 1000
	groupIDLength   = 44
	statusOutgoing  = "OUTGOING_DELIVERED"
	statusIncoming  = "INCOMING_COMPLETE"
	convoTypeSignal = "SIGNAL"

	// The setup thread: lives in the Signal account like any chat, carries the
	// link QR as a picture message while pairing and status posts afterwards, so
	// the account is never an empty list and the watch is the second screen the
	// phone's Signal can scan.
	setupChatID    = "mirrormsg:signal-link"
	setupChatName  = "MirrorMsg \u00b7 Signal"
	setupSender    = "mirrormsg"
	setupSenderNam = "MirrorMsg"
	linkMediaBase  = "signal-link-qr"
	linkQRText     = "[Link code] Open this picture, then point your phone's Signal scanner at the watch (Signal \u2192 Settings \u2192 Linked devices \u2192 Link new device)."
	linkedText     = "Linked \u2713  Signal chats appear here as messages arrive."

	maxStoredMedia = 2000
	maxAlbumItems  = 30
	maxVideoBytes  = 64 * 1024 * 1024
	pniPrefix      = "PNI:"

	// Chat photos. A chat's picture path is looked up on Signal in the
	// background (one chat at a time, paced) and kept in the index; a path is
	// looked at again after a day, "no picture" after 6 hours, a failed lookup
	// after 30 minutes, and everything pauses for 10 minutes after 3 failures
	// in a row (Signal unreachable).
	avatarRecheckKnown = 24 * time.Hour
	avatarRecheckNone  = 6 * time.Hour
	avatarRetryAfter   = 30 * time.Minute
	avatarPauseFor     = 10 * time.Minute
	avatarMaxFailRun   = 3
	avatarPace         = 300 * time.Millisecond
	avatarEmitEvery    = 5 * time.Second
	avatarSweepCount   = 40
	avatarLookupWait   = 20 * time.Second
	avatarFetchWait    = 30 * time.Second
	// Avatar value of a 1:1 chat whose profile key we don't hold yet: the
	// contact's next message carries it, and that triggers a new look.
	avatarNeedKey = "?"
)

type Bridge struct {
	sink   EventSink
	ctx    context.Context
	cancel context.CancelFunc
	log    zerolog.Logger

	db        *sql.DB
	container *store.Container

	mu         sync.Mutex
	client     *signalmeow.Client
	connected  bool
	pairing    bool
	pairCancel context.CancelFunc

	chatsPath string
	msgsDir   string
	chatsMu   sync.Mutex
	chats     map[string]*chatEntry

	namesPath  string
	nameMu     sync.Mutex
	names      map[string]string
	groupTried map[string]bool
	nameTried  map[string]bool

	linkMu      sync.Mutex
	linkQR      string
	linkSession int
	linkMediaID string

	mediaPath  string
	mediaMu    sync.Mutex
	media      map[string]string
	mediaOrder []string

	reactMu     sync.Mutex
	myReactions map[string]string

	avatarMu      sync.Mutex
	avatarQueue   []string
	avatarQueued  map[string]bool
	avatarFailed  map[string]time.Time
	avatarPause   time.Time
	avatarRunning bool
}

type chatEntry struct {
	ID            string `json:"id"`
	Name          string `json:"name"`
	Snippet       string `json:"snippet"`
	TsMicros      int64  `json:"ts"`
	Number        string `json:"number"`
	FromMe        bool   `json:"fromMe"`
	IsGroup       bool   `json:"group"`
	ExpireTimer   uint32 `json:"exp,omitempty"`
	ExpireVersion uint32 `json:"expv,omitempty"`
	// The chat's picture on Signal's CDN: the contact's profile avatar path
	// for a 1:1 chat, the group's avatar path for a group ("" none or not
	// looked up yet, avatarNeedKey waiting for the contact's profile key),
	// and when it was last looked up (unix seconds).
	Avatar   string      `json:"av,omitempty"`
	AvatarAt int64       `json:"avt,omitempty"`
	Messages []*msgEntry `json:"msgs"`
}

type msgEntry struct {
	ID        string      `json:"id"`
	Text      string      `json:"t"`
	TsMicros  int64       `json:"ts"`
	FromMe    bool        `json:"me"`
	Sender    string      `json:"snd"`
	ReplyToID string      `json:"rid,omitempty"`
	Parts     []mediaPart `json:"parts,omitempty"`

	// Single-attachment fields of entries stored before albums; read through
	// mediaParts(), never written for new messages.
	ImgMime string `json:"im,omitempty"`
	MediaID string `json:"mid,omitempty"`
	VidMime string `json:"vm,omitempty"`
	GifLike bool   `json:"gl,omitempty"`
	AudMime string `json:"am,omitempty"`
}

// mediaPart is one picture, GIF, video or audio item of a message. Item 0's
// media id is the message id, item i's is "<message id>#i".
type mediaPart struct {
	Kind    string `json:"k"`
	Mime    string `json:"m"`
	MediaID string `json:"id"`
	GifLike bool   `json:"g,omitempty"`
}

func (m *msgEntry) mediaParts() []mediaPart {
	if len(m.Parts) > 0 {
		return m.Parts
	}
	switch {
	case m.MediaID == "":
		return nil
	case m.VidMime != "":
		return []mediaPart{{Kind: "video", Mime: m.VidMime, MediaID: m.MediaID, GifLike: m.GifLike}}
	case m.AudMime != "":
		return []mediaPart{{Kind: "audio", Mime: m.AudMime, MediaID: m.MediaID}}
	default:
		return []mediaPart{{Kind: "image", Mime: m.ImgMime, MediaID: m.MediaID}}
	}
}

func (m *msgEntry) clearMedia() {
	m.Parts = nil
	m.MediaID, m.ImgMime, m.VidMime, m.GifLike, m.AudMime = "", "", "", false, ""
}

func (e *chatEntry) addMsg(m *msgEntry) bool {
	for _, x := range e.Messages {
		if x.ID == m.ID {
			x.Text = m.Text
			x.ReplyToID = m.ReplyToID
			if parts := m.mediaParts(); len(parts) > 0 {
				x.clearMedia()
				x.Parts = parts
			}
			return false
		}
	}
	e.Messages = append(e.Messages, m)
	return true
}

func (e *chatEntry) sortAndCap() {
	sort.Slice(e.Messages, func(i, j int) bool {
		if e.Messages[i].TsMicros != e.Messages[j].TsMicros {
			return e.Messages[i].TsMicros < e.Messages[j].TsMicros
		}
		return e.Messages[i].ID < e.Messages[j].ID
	})
	if len(e.Messages) > maxMsgsPerChat {
		e.Messages = e.Messages[len(e.Messages)-maxMsgsPerChat:]
	}
	if len(e.Messages) > 0 {
		last := e.Messages[len(e.Messages)-1]
		e.Snippet = last.Text
		e.TsMicros = last.TsMicros
		e.FromMe = last.FromMe
	}
}

// diagWriter routes signalmeow's zerolog output to the sink: Go stderr is not
// captured by the app, so this is the only way its internal errors surface.
type diagWriter struct{ b *Bridge }

func (w diagWriter) Write(p []byte) (int, error) {
	w.b.emit("DIAG", "SIG-LOG "+strings.TrimSpace(string(p)))
	return len(p), nil
}

func (b *Bridge) emit(eventType, jsonPayload string) {
	if b.sink != nil {
		b.sink.OnEvent(eventType, jsonPayload)
	}
}

func (b *Bridge) emitJSON(eventType string, v any) {
	data, err := json.Marshal(v)
	if err != nil {
		return
	}
	b.emit(eventType, string(data))
}

func NewBridge(storePath string, sink EventSink) (*Bridge, error) {
	ctx, cancel := context.WithCancel(context.Background())
	b := &Bridge{
		sink:         sink,
		cancel:       cancel,
		chats:        make(map[string]*chatEntry),
		names:        make(map[string]string),
		groupTried:   make(map[string]bool),
		nameTried:    make(map[string]bool),
		media:        make(map[string]string),
		myReactions:  make(map[string]string),
		avatarQueued: make(map[string]bool),
		avatarFailed: make(map[string]time.Time),
	}
	b.log = zerolog.New(diagWriter{b}).Level(zerolog.WarnLevel).With().Timestamp().Logger()
	b.ctx = b.log.WithContext(ctx)

	dsn := "file:" + storePath + "?_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("open signal store: %w", err)
	}
	wrapped, err := dbutil.NewWithDB(db, "sqlite3")
	if err != nil {
		_ = db.Close()
		cancel()
		return nil, fmt.Errorf("wrap signal store: %w", err)
	}
	container := store.NewStore(wrapped, dbutil.NoopLogger)
	if err := container.Upgrade(b.ctx); err != nil {
		_ = db.Close()
		cancel()
		return nil, fmt.Errorf("upgrade signal store: %w", err)
	}
	b.db = db
	b.container = container

	devices, err := container.GetAllDevices(b.ctx)
	if err != nil {
		_ = db.Close()
		cancel()
		return nil, fmt.Errorf("load signal devices: %w", err)
	}
	if len(devices) > 0 {
		b.client = b.newClient(devices[0])
	}

	b.chatsPath = storePath + ".chats.json"
	b.msgsDir = storePath + ".msgs"
	b.namesPath = storePath + ".names.json"
	b.mediaPath = storePath + ".media.json"
	_ = os.MkdirAll(b.msgsDir, 0700)
	b.loadChats()
	b.loadNames()
	b.loadMedia()
	b.nameMu.Lock()
	b.names[setupSender] = setupSenderNam
	b.nameMu.Unlock()
	return b, nil
}

func (b *Bridge) newClient(dev *store.Device) *signalmeow.Client {
	return signalmeow.NewClient(dev, b.log.With().Str("component", "signalmeow").Logger(), b.handleEvent)
}

func (b *Bridge) currentClient() *signalmeow.Client {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.client
}

func (b *Bridge) selfACI() uuid.UUID {
	if cli := b.currentClient(); cli != nil && cli.Store != nil {
		return cli.Store.ACI
	}
	return uuid.Nil
}

// ---- lifecycle -------------------------------------------------------------

func (b *Bridge) Connect() error {
	cli := b.currentClient()
	if cli == nil {
		return b.startPairing()
	}
	return b.startLoops(cli)
}

func (b *Bridge) startLoops(cli *signalmeow.Client) error {
	b.mu.Lock()
	if b.connected {
		b.mu.Unlock()
		return nil
	}
	b.mu.Unlock()
	ch, err := cli.StartReceiveLoops(b.ctx)
	if err != nil {
		return fmt.Errorf("start receive loops: %w", err)
	}
	go b.statusLoop(ch)
	return nil
}

func (b *Bridge) statusLoop(ch chan signalmeow.SignalConnectionStatus) {
	for st := range ch {
		errText := ""
		if st.Err != nil {
			errText = st.Err.Error()
		}
		switch st.Event {
		case signalmeow.SignalConnectionEventConnected:
			b.setConnected(true)
			b.emit("CONNECTED", `{}`)
			b.chatsMu.Lock()
			noSetup := b.chats[setupChatID] == nil
			b.chatsMu.Unlock()
			if noSetup {
				b.postSetup(linkedText, "", "")
			}
			go b.consolidatePNIChats()
			go b.queueDueAvatars(avatarSweepCount)
		case signalmeow.SignalConnectionEventDisconnected:
			b.setConnected(false)
			b.emitJSON("DISCONNECTED", map[string]string{"error": errText})
		case signalmeow.SignalConnectionEventLoggedOut:
			b.setConnected(false)
			b.emitJSON("LOGGED_OUT", map[string]string{"reason": "server", "error": errText})
		case signalmeow.SignalConnectionEventError:
			b.emit("DIAG", "SIG-WS error: "+errText)
		case signalmeow.SignalConnectionEventFatalError:
			b.setConnected(false)
			b.emitJSON("DISCONNECTED", map[string]string{"error": "fatal: " + errText})
		case signalmeow.SignalConnectionCleanShutdown:
			b.setConnected(false)
			b.emitJSON("DISCONNECTED", map[string]string{"error": "shutdown"})
		}
	}
}

func (b *Bridge) setConnected(v bool) {
	b.mu.Lock()
	b.connected = v
	b.mu.Unlock()
}

func (b *Bridge) isConnected() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.connected && b.client != nil
}

func (b *Bridge) startPairing() error {
	b.mu.Lock()
	if b.pairing {
		b.mu.Unlock()
		return nil
	}
	b.pairing = true
	pctx, pcancel := context.WithCancel(b.ctx)
	b.pairCancel = pcancel
	b.mu.Unlock()

	b.linkMu.Lock()
	b.linkSession++
	b.linkQR = ""
	b.linkMediaID = fmt.Sprintf("%s-%d", linkMediaBase, b.linkSession)
	mediaID := b.linkMediaID
	b.linkMu.Unlock()

	tctx, tcancel := context.WithTimeout(pctx, maxLinkDuration)
	ch := signalmeow.PerformProvisioningWithQRRefresh(tctx, b.container, deviceName, false, qrRefreshEvery)
	go func() {
		posted := false
		defer func() {
			b.mu.Lock()
			b.pairing = false
			b.pairCancel = nil
			b.mu.Unlock()
			b.linkMu.Lock()
			b.linkQR = ""
			b.linkMu.Unlock()
			tcancel()
			pcancel()
		}()
		for resp := range ch {
			if resp.Err != nil {
				text := pairErrorText(resp.Err)
				b.emitJSON("PAIR_ERROR", map[string]any{
					"error":  text,
					"status": pairErrorStatus(resp.Err),
				})
				b.postSetup("Linking failed: "+text+" Tap Start over on the phone.", "", "")
				continue
			}
			switch resp.State {
			case signalmeow.StateProvisioningURLReceived:
				b.linkMu.Lock()
				b.linkQR = resp.ProvisioningURL
				b.linkMu.Unlock()
				b.emitJSON("QR", map[string]string{"code": resp.ProvisioningURL})
				if !posted {
					posted = true
					b.postSetup(linkQRText, mediaID, "image/png")
				}
			case signalmeow.StateProvisioningDataReceived:
				if resp.ProvisioningData == nil || resp.ProvisioningData.ACI == uuid.Nil {
					b.emitJSON("PAIR_ERROR", map[string]any{"error": "no account id received", "status": 0})
					continue
				}
				dev, err := b.container.DeviceByACI(b.ctx, resp.ProvisioningData.ACI)
				if err != nil || dev == nil {
					b.emitJSON("PAIR_ERROR", map[string]any{"error": "device not stored: " + errString(err), "status": 0})
					continue
				}
				cli := b.newClient(dev)
				cli.SyncContactsOnConnect = true
				b.mu.Lock()
				b.client = cli
				b.mu.Unlock()
				b.emitJSON("PAIRED", map[string]string{
					"number": resp.ProvisioningData.Number,
					"aci":    resp.ProvisioningData.ACI.String(),
				})
				b.postSetup(linkedText, "", "")
				if err := b.startLoops(cli); err != nil {
					b.emitJSON("PAIR_ERROR", map[string]any{"error": err.Error(), "status": 0})
				}
			}
		}
	}()
	return nil
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

func pairErrorStatus(err error) int {
	var linkErr signalmeow.DeviceLinkError
	if errors.As(err, &linkErr) {
		return linkErr.StatusCode
	}
	return 0
}

func pairErrorText(err error) string {
	var linkErr signalmeow.DeviceLinkError
	if errors.As(err, &linkErr) {
		switch linkErr.StatusCode {
		case http.StatusConflict:
			return "Signal rejected the link: a capability is missing. Try again after updating."
		case http.StatusLengthRequired:
			return "This Signal account already has the maximum number of linked devices. Remove one in Signal and try again."
		case http.StatusForbidden:
			return "The QR code was invalid or already used. Start over."
		case http.StatusTooManyRequests:
			return "Signal rate-limited the link. Wait a few minutes and try again."
		default:
			return fmt.Sprintf("Signal rejected the link (HTTP %d) %s", linkErr.StatusCode, linkErr.Message)
		}
	}
	return err.Error()
}

func (b *Bridge) Disconnect() {
	b.mu.Lock()
	cli := b.client
	pc := b.pairCancel
	b.mu.Unlock()
	if pc != nil {
		pc()
	}
	if cli != nil {
		_ = cli.StopReceiveLoops()
	}
	b.setConnected(false)
}

// Logout unlinks this device on Signal's side (best effort), stops the loops
// and deletes the stored session + local index. Kotlin deletes the db file.
func (b *Bridge) Logout() error {
	b.mu.Lock()
	cli := b.client
	b.client = nil
	pc := b.pairCancel
	b.mu.Unlock()
	if pc != nil {
		pc()
	}
	var unlinkErr error
	if cli != nil {
		uctx, ucancel := context.WithTimeout(b.ctx, 15*time.Second)
		unlinkErr = cli.Unlink(uctx)
		ucancel()
		_ = cli.StopReceiveLoops()
		if err := b.container.DeleteDevice(b.ctx, &cli.Store.DeviceData); err != nil {
			b.emit("DIAG", "SIG-LOGOUT delete device: "+err.Error())
		}
	}
	b.setConnected(false)
	b.chatsMu.Lock()
	b.chats = make(map[string]*chatEntry)
	_ = os.Remove(b.chatsPath)
	_ = os.RemoveAll(b.msgsDir)
	_ = os.MkdirAll(b.msgsDir, 0700)
	b.chatsMu.Unlock()
	b.nameMu.Lock()
	b.names = make(map[string]string)
	_ = os.Remove(b.namesPath)
	b.nameMu.Unlock()
	b.mediaMu.Lock()
	b.media = make(map[string]string)
	b.mediaOrder = nil
	_ = os.Remove(b.mediaPath)
	b.mediaMu.Unlock()
	b.avatarMu.Lock()
	b.avatarQueue = nil
	b.avatarQueued = make(map[string]bool)
	b.avatarFailed = make(map[string]time.Time)
	b.avatarPause = time.Time{}
	b.avatarMu.Unlock()
	if unlinkErr != nil {
		b.emit("DIAG", "SIG-LOGOUT unlink: "+unlinkErr.Error())
	}
	return nil
}

func (b *Bridge) Close() {
	b.Disconnect()
	b.cancel()
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.db != nil {
		_ = b.db.Close()
		b.db = nil
	}
}

// IsPaired is true with a stored session, and also while a link attempt is
// live: the account must be in the watch's rail for the setup thread (and its
// QR picture) to be reachable there.
func (b *Bridge) IsPaired() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.client != nil || b.pairing
}

func (b *Bridge) IsLinking() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.client == nil && b.pairing
}

func (b *Bridge) IsLoggedIn() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.client != nil && b.connected
}

// SelfID is our ACI as a string: the actor key our own reactions carry.
func (b *Bridge) SelfID() string {
	if aci := b.selfACI(); aci != uuid.Nil {
		return aci.String()
	}
	return ""
}

// ChatDeepLink is the signal.me link that opens a DM in the Signal app, or ""
// when the chat is a group or the contact's number is unknown (phone-number
// privacy). Signal's matcher wants the raw "+digits" form.
func (b *Bridge) ChatDeepLink(convoID string) string {
	if convoID == setupChatID || isGroupID(convoID) {
		return ""
	}
	num := ""
	if r := b.storedRecipient(convoID); r != nil {
		num = r.E164
	}
	if num == "" {
		b.chatsMu.Lock()
		if ent := b.chats[convoID]; ent != nil && ent.Number != ent.ID {
			num = ent.Number
		}
		b.chatsMu.Unlock()
	}
	if len(num) < 8 || num[0] != '+' {
		return ""
	}
	for _, c := range num[1:] {
		if c < '0' || c > '9' {
			return ""
		}
	}
	return "https://signal.me/#p/" + num
}

func (b *Bridge) SelfNumber() string {
	if cli := b.currentClient(); cli != nil && cli.Store != nil {
		return cli.Store.Number
	}
	return ""
}

func (b *Bridge) SetLogLevel(level string) {
	lvl, err := zerolog.ParseLevel(level)
	if err != nil {
		return
	}
	b.log = b.log.Level(lvl)
}

// RequestContactSync asks the primary phone for its contact list (names,
// numbers). The answer arrives asynchronously as a ContactList event.
func (b *Bridge) RequestContactSync() error {
	cli := b.currentClient()
	if cli == nil {
		return fmt.Errorf("signal not paired")
	}
	return cli.SendContactSyncRequest(b.ctx)
}

// ---- events ----------------------------------------------------------------

func (b *Bridge) handleEvent(raw events.SignalEvent) bool {
	switch evt := raw.(type) {
	case *events.ChatEvent:
		b.handleChatEvent(evt)
	case *events.ContactList:
		b.handleContactList(evt)
	case *events.ACIFound:
		b.mergeChats(evt.PNI.String(), evt.ACI.String())
	case *events.DecryptionError:
		b.emit("DIAG", fmt.Sprintf("SIG-DECRYPT sender=%s err=%v", shortID(evt.Sender.String()), evt.Err))
	case *events.QueueEmpty:
		b.emit("QUEUE_EMPTY", `{}`)
	case *events.LoggedOut:
		b.setConnected(false)
		b.emitJSON("LOGGED_OUT", map[string]string{"reason": "event", "error": errString(evt.Error)})
	default:
		b.emitJSON("EVENT", map[string]string{"goType": fmt.Sprintf("%T", raw)})
	}
	return true
}

func (b *Bridge) handleChatEvent(evt *events.ChatEvent) {
	switch content := evt.Event.(type) {
	case *signalpb.DataMessage:
		b.handleDataMessage(evt.Info, content, 0)
	case *signalpb.EditMessage:
		if content.GetDataMessage() != nil {
			b.handleDataMessage(evt.Info, content.GetDataMessage(), content.GetTargetSentTimestamp())
		}
	case *signalpb.TypingMessage:
	default:
		b.emitJSON("EVENT", map[string]string{"goType": fmt.Sprintf("%T", content)})
	}
}

func messageID(sender uuid.UUID, tsMillis uint64) string {
	return sender.String() + "|" + strconv.FormatUint(tsMillis, 10)
}

// shortID is the first 6 characters of an account, group or message id: enough
// to match log lines up, not enough to identify anyone.
func shortID(id string) string {
	id = strings.TrimPrefix(id, pniPrefix)
	if len(id) > 6 {
		return id[:6] + ".."
	}
	return id
}

func isGroupID(chatID string) bool {
	return len(chatID) == groupIDLength
}

// attachmentLabel is the text for an attachment message without a caption,
// named after the item the watch shows first (a picture or video before any
// audio, audio before other files). Only the bare labels the phone reads as
// "no caption" are used for media.
func attachmentLabel(att []*signalpb.AttachmentPointer) string {
	if len(att) == 0 {
		return ""
	}
	for _, a := range att {
		ct := strings.ToLower(a.GetContentType())
		gif := a.GetFlags()&uint32(signalpb.AttachmentPointer_GIF) != 0
		switch {
		case strings.HasPrefix(ct, "image/gif"), gif && strings.HasPrefix(ct, "video/"):
			return "[GIF]"
		case strings.HasPrefix(ct, "image/"):
			return "[Image]"
		case strings.HasPrefix(ct, "video/"):
			return "[Video]"
		}
	}
	for _, a := range att {
		ct := strings.ToLower(a.GetContentType())
		switch {
		case a.GetFlags()&uint32(signalpb.AttachmentPointer_VOICE_MESSAGE) != 0:
			return "[Voice message]"
		case strings.HasPrefix(ct, "audio/"):
			return "[Audio]"
		}
	}
	return "[Attachment]"
}

func messageText(dm *signalpb.DataMessage) string {
	if dm.GetFlags() != 0 || dm.GetReaction() != nil || dm.GetDelete() != nil ||
		dm.GetGroupCallUpdate() != nil || dm.GetPollVote() != nil || dm.GetPollTerminate() != nil ||
		dm.GetPinMessage() != nil || dm.GetUnpinMessage() != nil || dm.GetAdminDelete() != nil {
		return ""
	}
	body := dm.GetBody()
	label := attachmentLabel(dm.GetAttachments())
	switch {
	case body != "":
		return body
	case label != "":
		return label
	case dm.GetSticker() != nil:
		if dm.GetSticker().GetData() != nil {
			return "[Image]"
		}
		return "[Sticker]"
	case len(dm.GetContact()) > 0:
		return "[Contact]"
	case dm.GetPollCreate() != nil:
		return "[Poll] " + dm.GetPollCreate().GetQuestion()
	case dm.GetPayment() != nil:
		return "[Payment]"
	case dm.GetGiftBadge() != nil:
		return "[Gift badge]"
	case dm.GetStoryContext() != nil:
		return "[Story reply]"
	}
	return ""
}

func quoteID(dm *signalpb.DataMessage) string {
	q := dm.GetQuote()
	if q == nil || q.GetId() == 0 {
		return ""
	}
	var author uuid.UUID
	if raw := q.GetAuthorAciBinary(); len(raw) == 16 {
		author, _ = uuid.FromBytes(raw)
	} else if s := q.GetAuthorAci(); s != "" {
		author, _ = uuid.Parse(s)
	}
	if author == uuid.Nil {
		return ""
	}
	return messageID(author, q.GetId())
}

func (b *Bridge) handleDataMessage(info events.MessageInfo, dm *signalpb.DataMessage, editOf uint64) {
	chatID := b.canonicalChatID(info.ChatID)
	if chatID == "" {
		return
	}
	self := b.selfACI()
	fromMe := self != uuid.Nil && info.Sender == self
	if r := dm.GetReaction(); r != nil {
		b.handleReaction(chatID, info.Sender, r)
		return
	}
	if d := dm.GetDelete(); d != nil {
		b.handleRemoteDelete(chatID, info.Sender, d.GetTargetSentTimestamp())
		return
	}
	text := messageText(dm)
	ts := dm.GetTimestamp()
	if editOf != 0 {
		ts = editOf
	}
	if text == "" {
		b.noteTimer(chatID, dm)
		return
	}
	m := &msgEntry{
		ID:        messageID(info.Sender, ts),
		Text:      text,
		TsMicros:  int64(ts) * 1000,
		FromMe:    fromMe,
		Sender:    info.Sender.String(),
		ReplyToID: quoteID(dm),
	}
	b.attachMedia(m, dm)
	isGroup := isGroupID(chatID)
	b.chatsMu.Lock()
	ent := b.chats[chatID]
	if ent == nil {
		ent = &chatEntry{ID: chatID, IsGroup: isGroup}
		if !isGroup {
			ent.Number = b.numberFor(chatID)
		}
		b.chats[chatID] = ent
	}
	if dm.ExpireTimer != nil {
		ent.ExpireTimer = dm.GetExpireTimer()
		ent.ExpireVersion = dm.GetExpireTimerVersion()
	}
	if !fromMe && ent.Avatar == avatarNeedKey {
		// Their message carried their profile key: their picture can be read now.
		ent.AvatarAt = 0
	}
	ent.addMsg(m)
	ent.sortAndCap()
	b.saveChatMessagesLocked(ent)
	b.saveIndexLocked()
	b.chatsMu.Unlock()

	b.ensureNames(chatID, info.Sender, info.GroupRevision)
	b.emitJSON("MESSAGE", b.makeMessage(chatID, m))
}

func (b *Bridge) noteTimer(chatID string, dm *signalpb.DataMessage) {
	if dm.GetFlags()&uint32(signalpb.DataMessage_EXPIRATION_TIMER_UPDATE) == 0 {
		return
	}
	b.chatsMu.Lock()
	defer b.chatsMu.Unlock()
	if ent := b.chats[chatID]; ent != nil {
		ent.ExpireTimer = dm.GetExpireTimer()
		ent.ExpireVersion = dm.GetExpireTimerVersion()
		b.saveIndexLocked()
	}
}

func (b *Bridge) handleContactList(evt *events.ContactList) {
	changed := 0
	b.nameMu.Lock()
	for _, c := range evt.Contacts {
		if c == nil || c.ACI == uuid.Nil {
			continue
		}
		name := recipientName(c)
		if name == "" {
			continue
		}
		key := c.ACI.String()
		if b.names[key] != name {
			b.names[key] = name
			changed++
		}
	}
	b.nameMu.Unlock()
	b.emit("DIAG", fmt.Sprintf("SIG-CONTACTS received %d contacts, %d names changed (fromDB=%t)", len(evt.Contacts), changed, evt.IsFromDB))
	if changed > 0 {
		b.saveNames()
		b.emitJSON("CHATS_UPDATED", map[string]int{"names": changed})
	}
}

func recipientName(r *types.Recipient) string {
	if r == nil {
		return ""
	}
	switch {
	case r.ContactName != "":
		return r.ContactName
	case r.Nickname != "":
		return r.Nickname
	case r.Profile.Name != "":
		return r.Profile.Name
	case r.E164 != "":
		return r.E164
	}
	return ""
}

// ListPeople returns the people a new chat can start with (the watch's New chat):
// the contacts the primary phone synced (and others the store has a name for), as
// {"people":[{"id","name","detail"}]}. id is the 1:1 chat's id, the contact's ACI
// (what SendMessage takes; the first send creates the chat, recordOwnSend).
func (b *Bridge) ListPeople() (string, error) {
	cli := b.currentClient()
	if cli == nil || cli.Store == nil || cli.Store.RecipientStore == nil {
		return "", fmt.Errorf("signal not connected")
	}
	all, err := cli.Store.RecipientStore.LoadAllContacts(b.ctx)
	if err != nil {
		return "", err
	}
	self := b.selfACI()
	type person struct {
		ID     string `json:"id"`
		Name   string `json:"name"`
		Detail string `json:"detail,omitempty"`
	}
	out := make([]person, 0, len(all))
	for _, r := range all {
		if r == nil || r.ACI == uuid.Nil || r.ACI == self || r.Blocked {
			continue
		}
		if r.ContactName == "" && r.Nickname == "" && r.Profile.Name == "" {
			continue // a bare number, no one named
		}
		out = append(out, person{ID: r.ACI.String(), Name: recipientName(r), Detail: r.E164})
	}
	bs, _ := json.Marshal(map[string]any{"people": out})
	return string(bs), nil
}

// ---- names -----------------------------------------------------------------

func (b *Bridge) lookupName(key string) string {
	b.nameMu.Lock()
	defer b.nameMu.Unlock()
	return b.names[key]
}

func (b *Bridge) setName(key, name string) bool {
	if key == "" || name == "" {
		return false
	}
	b.nameMu.Lock()
	defer b.nameMu.Unlock()
	if b.names[key] == name {
		return false
	}
	b.names[key] = name
	return true
}

// numberFor returns the E164 we know for a DM chat's other party, else the
// service id itself (stable, unique, harmless to the phone's contact lookup).
func (b *Bridge) numberFor(chatID string) string {
	if r := b.storedRecipient(chatID); r != nil && r.E164 != "" {
		return r.E164
	}
	return chatID
}

// storedRecipient reads the recipient row for an ACI or PNI service id from the
// local store only (no network): contact-sync names, E164 and the ACI<->PNI
// mapping once Signal has confirmed it.
func (b *Bridge) storedRecipient(serviceID string) *types.Recipient {
	cli := b.currentClient()
	if cli == nil {
		return nil
	}
	sid, err := libsignalgo.ServiceIDFromString(serviceID)
	if err != nil {
		return nil
	}
	var r *types.Recipient
	switch sid.Type {
	case libsignalgo.ServiceIDTypeACI:
		r, err = cli.Store.RecipientStore.LoadAndUpdateRecipient(b.ctx, sid.UUID, uuid.Nil, nil)
	case libsignalgo.ServiceIDTypePNI:
		r, err = cli.Store.RecipientStore.LoadAndUpdateRecipient(b.ctx, uuid.Nil, sid.UUID, nil)
	default:
		return nil
	}
	if err != nil {
		return nil
	}
	return r
}

// canonicalChatID maps a PNI-addressed DM onto the contact's ACI chat as soon as
// the store knows the mapping (a first message to someone goes to their phone
// number identity; their reply comes from their account identity).
func (b *Bridge) canonicalChatID(chatID string) string {
	if !strings.HasPrefix(chatID, pniPrefix) {
		return chatID
	}
	if r := b.storedRecipient(chatID); r != nil && r.ACI != uuid.Nil {
		return r.ACI.String()
	}
	return chatID
}

// mergeChats folds chat `from` into chat `to` (messages, timer, media stay),
// then drops `from`. Used when Signal confirms a PNI belongs to an ACI.
func (b *Bridge) mergeChats(from, to string) {
	if from == "" || to == "" || from == to {
		return
	}
	b.chatsMu.Lock()
	src := b.chats[from]
	if src == nil {
		b.chatsMu.Unlock()
		return
	}
	dst := b.chats[to]
	if dst == nil {
		src.ID = to
		src.Number = b.numberFor(to)
		src.Avatar, src.AvatarAt = "", 0
		b.chats[to] = src
		dst = src
	} else {
		for _, m := range src.Messages {
			dst.addMsg(m)
		}
		dst.sortAndCap()
		if dst.ExpireTimer == 0 && src.ExpireTimer != 0 {
			dst.ExpireTimer, dst.ExpireVersion = src.ExpireTimer, src.ExpireVersion
		}
	}
	delete(b.chats, from)
	_ = os.Remove(b.msgsDir + "/" + chatMsgFileName(from))
	b.saveChatMessagesLocked(dst)
	b.saveIndexLocked()
	b.chatsMu.Unlock()
	b.emit("DIAG", "SIG-MERGE "+shortID(from)+" -> "+shortID(to))
	b.ensureNames(to, uuid.Nil, 0)
	b.emitJSON("CHATS_UPDATED", map[string]string{"merged": to})
}

// consolidatePNIChats re-keys any PNI chat whose ACI has since become known.
// Idempotent; runs on every connect so chats split before the mapping was
// learned heal themselves.
func (b *Bridge) consolidatePNIChats() {
	b.chatsMu.Lock()
	var pnis []string
	for id := range b.chats {
		if strings.HasPrefix(id, pniPrefix) {
			pnis = append(pnis, id)
		}
	}
	b.chatsMu.Unlock()
	for _, id := range pnis {
		if canon := b.canonicalChatID(id); canon != id {
			b.mergeChats(id, canon)
		}
	}
}

// ensureNames resolves (once per session, off the receive goroutine) the
// display name of the chat and of the message sender.
func (b *Bridge) ensureNames(chatID string, sender uuid.UUID, groupRevision uint32) {
	go func() {
		changed := false
		if isGroupID(chatID) {
			if b.lookupName(chatID) == "" {
				changed = b.fetchGroupTitle(chatID, groupRevision) || changed
			}
		} else if b.lookupName(chatID) == "" {
			changed = b.fetchContactName(chatID) || changed
		}
		if sender != uuid.Nil && sender != b.selfACI() && b.lookupName(sender.String()) == "" {
			changed = b.fetchContactName(sender.String()) || changed
		}
		if changed {
			b.saveNames()
			b.emitJSON("CHATS_UPDATED", map[string]int{"names": 1})
		}
	}()
}

func (b *Bridge) fetchContactName(serviceID string) bool {
	b.nameMu.Lock()
	tried := b.nameTried[serviceID]
	b.nameTried[serviceID] = true
	b.nameMu.Unlock()
	if tried {
		return false
	}
	cli := b.currentClient()
	if cli == nil {
		return false
	}
	sid, err := libsignalgo.ServiceIDFromString(serviceID)
	if err != nil {
		return false
	}
	var r *types.Recipient
	if sid.Type == libsignalgo.ServiceIDTypeACI {
		ctx, cancel := context.WithTimeout(b.ctx, 20*time.Second)
		defer cancel()
		r, err = cli.ContactByACI(ctx, sid.UUID)
		if err != nil && r == nil {
			b.emit("DIAG", "SIG-NAMES contact "+shortID(serviceID)+": "+err.Error())
			return false
		}
	} else {
		b.nameMu.Lock()
		delete(b.nameTried, serviceID)
		b.nameMu.Unlock()
		r = b.storedRecipient(serviceID)
	}
	name := recipientName(r)
	if name == "" {
		return false
	}
	if r.E164 != "" {
		b.chatsMu.Lock()
		if ent := b.chats[serviceID]; ent != nil && ent.Number != r.E164 {
			ent.Number = r.E164
			b.saveIndexLocked()
		}
		b.chatsMu.Unlock()
	}
	return b.setName(serviceID, name)
}

func (b *Bridge) fetchGroupTitle(chatID string, revision uint32) bool {
	b.nameMu.Lock()
	tried := b.groupTried[chatID]
	b.groupTried[chatID] = true
	b.nameMu.Unlock()
	if tried {
		return false
	}
	cli := b.currentClient()
	if cli == nil {
		return false
	}
	ctx, cancel := context.WithTimeout(b.ctx, 30*time.Second)
	defer cancel()
	g, _, err := cli.RetrieveGroupByID(ctx, types.GroupIdentifier(chatID), revision)
	if err != nil || g == nil {
		b.emit("DIAG", "SIG-GROUPS "+shortID(chatID)+": "+errString(err))
		return false
	}
	if g.Title == "" {
		return false
	}
	return b.setName(chatID, g.Title)
}

func (b *Bridge) displayName(ent *chatEntry) string {
	if n := b.lookupName(ent.ID); n != "" {
		return n
	}
	if ent.Name != "" {
		return ent.Name
	}
	if ent.IsGroup {
		return "Group " + ent.ID[:6]
	}
	if ent.Number != "" && ent.Number != ent.ID {
		return ent.Number
	}
	if strings.HasPrefix(ent.ID, pniPrefix) {
		return "Signal contact"
	}
	return "Signal " + ent.ID[:8]
}

func (b *Bridge) senderName(m *msgEntry) string {
	if m.FromMe {
		return ""
	}
	return b.lookupName(m.Sender)
}

func (b *Bridge) loadNames() {
	data, err := os.ReadFile(b.namesPath)
	if err != nil {
		return
	}
	m := map[string]string{}
	if json.Unmarshal(data, &m) == nil {
		b.nameMu.Lock()
		b.names = m
		b.nameMu.Unlock()
	}
}

func (b *Bridge) saveNames() {
	b.nameMu.Lock()
	data, err := json.Marshal(b.names)
	b.nameMu.Unlock()
	if err == nil {
		_ = os.WriteFile(b.namesPath, data, 0600)
	}
}

// ---- chat index persistence --------------------------------------------------

func chatMsgFileName(id string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(id)) + ".json"
}

func (b *Bridge) loadChats() {
	b.chats = make(map[string]*chatEntry)
	data, err := os.ReadFile(b.chatsPath)
	if err != nil {
		return
	}
	var list []*chatEntry
	if json.Unmarshal(data, &list) != nil {
		return
	}
	for _, e := range list {
		if e == nil || e.ID == "" {
			continue
		}
		if msgs := b.loadChatMessages(e.ID); msgs != nil {
			e.Messages = msgs
		}
		b.chats[e.ID] = e
	}
}

func (b *Bridge) loadChatMessages(id string) []*msgEntry {
	data, err := os.ReadFile(b.msgsDir + "/" + chatMsgFileName(id))
	if err != nil {
		return nil
	}
	var msgs []*msgEntry
	if json.Unmarshal(data, &msgs) != nil {
		return nil
	}
	return msgs
}

func (b *Bridge) saveIndexLocked() {
	list := make([]*chatEntry, 0, len(b.chats))
	for _, e := range b.chats {
		row := *e
		row.Messages = nil
		list = append(list, &row)
	}
	if data, err := json.Marshal(list); err == nil {
		_ = os.WriteFile(b.chatsPath, data, 0600)
	}
}

func (b *Bridge) saveChatMessagesLocked(ent *chatEntry) {
	if ent == nil || ent.ID == "" {
		return
	}
	if data, err := json.Marshal(ent.Messages); err == nil {
		_ = os.WriteFile(b.msgsDir+"/"+chatMsgFileName(ent.ID), data, 0600)
	}
}

// ---- watch-schema JSON ---------------------------------------------------------

type msgContentJSON struct {
	Content string `json:"content"`
}
type mediaContentJSON struct {
	Format   string `json:"format"`
	MimeType string `json:"mimeType"`
	MediaID  string `json:"mediaID"`
	GifLike  bool   `json:"gifLike,omitempty"`
}
type messageInfoJSON struct {
	MessageContent *msgContentJSON   `json:"messageContent,omitempty"`
	MediaContent   *mediaContentJSON `json:"mediaContent,omitempty"`
}
type idJSON struct {
	Number string `json:"number"`
}
type senderJSON struct {
	IsMe      bool   `json:"isMe"`
	FirstName string `json:"firstName"`
	FullName  string `json:"fullName"`
	ID        idJSON `json:"ID"`
}
type statusJSON struct {
	Status string `json:"status"`
}
type replyToJSON struct {
	ID string `json:"id"`
}
type messageJSON struct {
	ConversationID    string            `json:"conversationID"`
	MessageID         string            `json:"messageID"`
	Timestamp         string            `json:"timestamp"`
	SenderParticipant senderJSON        `json:"senderParticipant"`
	MessageStatus     statusJSON        `json:"messageStatus"`
	MessageInfo       []messageInfoJSON `json:"messageInfo"`
	IsMe              bool              `json:"isMe"`
	FromMe            bool              `json:"fromMe"`
	ReplyTo           *replyToJSON      `json:"replyTo,omitempty"`
}

func (b *Bridge) makeMessage(convoID string, m *msgEntry) messageJSON {
	status := statusIncoming
	if m.FromMe {
		status = statusOutgoing
	}
	name := b.senderName(m)
	number := m.Sender
	if !m.FromMe && !isGroupID(convoID) {
		b.chatsMu.Lock()
		if ent := b.chats[convoID]; ent != nil && ent.Number != "" {
			number = ent.Number
		}
		b.chatsMu.Unlock()
	}
	msg := messageJSON{
		ConversationID: convoID,
		MessageID:      m.ID,
		Timestamp:      strconv.FormatInt(m.TsMicros, 10),
		SenderParticipant: senderJSON{
			IsMe:      m.FromMe,
			FirstName: name,
			FullName:  name,
			ID:        idJSON{Number: number},
		},
		MessageStatus: statusJSON{Status: status},
		MessageInfo:   []messageInfoJSON{{MessageContent: &msgContentJSON{Content: m.Text}}},
		IsMe:          m.FromMe,
		FromMe:        m.FromMe,
	}
	// One item rides with the text in a single part (the shape every network
	// uses for a lone picture); an album puts the text first and each item in
	// its own part, in order.
	parts := m.mediaParts()
	if len(parts) == 1 {
		msg.MessageInfo[0].MediaContent = partJSON(parts[0])
	} else {
		for _, p := range parts {
			msg.MessageInfo = append(msg.MessageInfo, messageInfoJSON{MediaContent: partJSON(p)})
		}
	}
	if m.ReplyToID != "" {
		msg.ReplyTo = &replyToJSON{ID: m.ReplyToID}
	}
	return msg
}

func partJSON(p mediaPart) *mediaContentJSON {
	switch p.Kind {
	case "video":
		return &mediaContentJSON{Format: "VIDEO", MimeType: p.Mime, MediaID: p.MediaID, GifLike: p.GifLike}
	case "audio":
		return &mediaContentJSON{Format: "AUDIO", MimeType: p.Mime, MediaID: p.MediaID}
	default:
		return &mediaContentJSON{Format: "IMAGE", MimeType: p.Mime, MediaID: p.MediaID}
	}
}

type latestMsgJSON struct {
	DisplayContent string `json:"displayContent"`
}
type participantJSON struct {
	IsMe bool   `json:"isMe"`
	ID   idJSON `json:"ID"`
}
type convoJSON struct {
	ConversationID string            `json:"conversationID"`
	Name           string            `json:"name"`
	Type           string            `json:"type"`
	Unread         bool              `json:"unread"`
	LatestMessage  latestMsgJSON     `json:"latestMessage"`
	Participants   []participantJSON `json:"participants"`
	// The chat photo's name for the phone (see AvatarKey); "" for none.
	AvatarKey string `json:"avatarKey"`
}
type convoListJSON struct {
	Conversations []convoJSON `json:"conversations"`
}

// ListConversations returns the newest `count` chats. Each row carries its
// photo's key, read from the index; chats whose photo is due a look are queued
// for the background check, which emits CHATS_UPDATED when it learns one.
func (b *Bridge) ListConversations(count int) (string, error) {
	now := time.Now()
	b.chatsMu.Lock()
	list := make([]chatEntry, 0, len(b.chats))
	for _, e := range b.chats {
		row := *e
		row.Messages = nil
		list = append(list, row)
	}
	b.chatsMu.Unlock()
	sort.Slice(list, func(i, j int) bool { return list[i].TsMicros > list[j].TsMicros })
	if count > 0 && len(list) > count {
		list = list[:count]
	}
	convos := make([]convoJSON, 0, len(list))
	var due []string
	for i := range list {
		e := &list[i]
		number := e.Number
		if number == "" {
			number = e.ID
		}
		convos = append(convos, convoJSON{
			ConversationID: e.ID,
			Name:           b.displayName(e),
			Type:           convoTypeSignal,
			Unread:         false,
			LatestMessage:  latestMsgJSON{DisplayContent: e.Snippet},
			Participants:   []participantJSON{{IsMe: false, ID: idJSON{Number: number}}},
			AvatarKey:      avatarKeyFor(e),
		})
		if avatarDue(e, now) {
			due = append(due, e.ID)
		}
	}
	b.queueAvatarChecks(due)
	data, err := json.Marshal(convoListJSON{Conversations: convos})
	if err != nil {
		return "", err
	}
	return string(data), nil
}

type cursorJSON struct {
	BeforeTs string `json:"beforeTs"`
	BeforeID string `json:"beforeId"`
}
type msgListJSON struct {
	Messages []messageJSON `json:"messages"`
	Cursor   *cursorJSON   `json:"cursor,omitempty"`
}

func olderThan(m *msgEntry, ts int64, id string) bool {
	if m.TsMicros != ts {
		return m.TsMicros < ts
	}
	return m.ID < id
}

// FetchMessages pages the local index exactly like the WhatsApp bridge:
// cursor=="" returns the newest `count`; a cursor returns the `count` messages
// older than its (beforeTs, beforeId) anchor; the response cursor is omitted
// once the page reaches the oldest stored message.
func (b *Bridge) FetchMessages(convoID string, count int, cursor string) (string, error) {
	b.chatsMu.Lock()
	var msgs []*msgEntry
	if ent := b.chats[convoID]; ent != nil {
		msgs = make([]*msgEntry, len(ent.Messages))
		copy(msgs, ent.Messages)
	}
	b.chatsMu.Unlock()

	var sel []*msgEntry
	if cursor == "" {
		if count > 0 && len(msgs) > count {
			sel = msgs[len(msgs)-count:]
		} else {
			sel = msgs
		}
	} else {
		var c cursorJSON
		if err := json.Unmarshal([]byte(cursor), &c); err != nil {
			return "", fmt.Errorf("bad cursor: %w", err)
		}
		beforeTs, _ := strconv.ParseInt(c.BeforeTs, 10, 64)
		hi := 0
		for hi < len(msgs) && olderThan(msgs[hi], beforeTs, c.BeforeID) {
			hi++
		}
		older := msgs[:hi]
		if count > 0 && len(older) > count {
			sel = older[len(older)-count:]
		} else {
			sel = older
		}
	}
	out := make([]messageJSON, 0, len(sel))
	for _, m := range sel {
		out = append(out, b.makeMessage(convoID, m))
	}
	resp := msgListJSON{Messages: out}
	if len(sel) > 0 && len(msgs) > 0 {
		oldest := sel[0]
		globalOldest := msgs[0]
		if oldest.TsMicros != globalOldest.TsMicros || oldest.ID != globalOldest.ID {
			resp.Cursor = &cursorJSON{BeforeTs: strconv.FormatInt(oldest.TsMicros, 10), BeforeID: oldest.ID}
		}
	}
	data, err := json.Marshal(resp)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

// ---- send ----------------------------------------------------------------------

// SendMessage sends plain text to convoID (a DM service id or a group id).
// The chat's disappearing-message timer is echoed on the message: Signal treats
// a DataMessage whose timer differs from the conversation's as a timer change,
// so omitting it would switch the timer off for everyone. Our own sends are not
// looped back by the server, so the bridge indexes + emits them itself.
func (b *Bridge) SendMessage(convoID, text string) (string, error) {
	ts := uint64(time.Now().UnixMilli())
	dm := &signalpb.DataMessage{Body: proto.String(text), Timestamp: proto.Uint64(ts)}
	if err := b.sendDataMessage(convoID, dm); err != nil {
		return "", err
	}
	return b.recordOwnSend(convoID, ts, text, "")
}

// SendReply sends text quoting targetMsgID ("<authorACI>|<timestampMs>"), with
// the quoted text from the local index when we have it.
func (b *Bridge) SendReply(convoID, targetMsgID, text string) (string, error) {
	author, targetTs, err := parseMessageID(targetMsgID)
	if err != nil {
		return "", err
	}
	quote := &signalpb.DataMessage_Quote{
		Id:              proto.Uint64(targetTs),
		AuthorAciBinary: author[:],
		Type:            signalpb.DataMessage_Quote_NORMAL.Enum(),
	}
	if q := b.findMessage(convoID, targetMsgID); q != nil {
		quote.Text = proto.String(q.Text)
		if parts := q.mediaParts(); len(parts) > 0 {
			ct := parts[0].Mime
			quote.Attachments = []*signalpb.DataMessage_Quote_QuotedAttachment{{ContentType: proto.String(ct)}}
		}
	}
	ts := uint64(time.Now().UnixMilli())
	dm := &signalpb.DataMessage{Body: proto.String(text), Timestamp: proto.Uint64(ts), Quote: quote}
	if err := b.sendDataMessage(convoID, dm); err != nil {
		return "", err
	}
	return b.recordOwnSend(convoID, ts, text, targetMsgID)
}

// SendReaction adds emoji to targetMsgID; an empty emoji removes the reaction we
// last sent to it (Signal needs the emoji being removed named explicitly).
func (b *Bridge) SendReaction(convoID, targetMsgID, emoji string) (string, error) {
	author, targetTs, err := parseMessageID(targetMsgID)
	if err != nil {
		return "", err
	}
	remove := emoji == ""
	if remove {
		b.reactMu.Lock()
		emoji = b.myReactions[targetMsgID]
		b.reactMu.Unlock()
		if emoji == "" {
			return "", fmt.Errorf("no reaction of ours to remove on that message")
		}
	}
	return b.sendReaction(convoID, targetMsgID, author, targetTs, emoji, remove)
}

// RemoveReaction takes back our reaction emoji on targetMsgID. Signal names the
// reaction being removed; the one we last sent there (this session) wins over
// emoji, which can differ from it by a variation selector.
func (b *Bridge) RemoveReaction(convoID, targetMsgID, emoji string) (string, error) {
	author, targetTs, err := parseMessageID(targetMsgID)
	if err != nil {
		return "", err
	}
	b.reactMu.Lock()
	if mine := b.myReactions[targetMsgID]; mine != "" {
		emoji = mine
	}
	b.reactMu.Unlock()
	if emoji == "" {
		return "", fmt.Errorf("no reaction to remove")
	}
	return b.sendReaction(convoID, targetMsgID, author, targetTs, emoji, true)
}

func (b *Bridge) sendReaction(convoID, targetMsgID string, author uuid.UUID, targetTs uint64, emoji string, remove bool) (string, error) {
	ts := uint64(time.Now().UnixMilli())
	dm := &signalpb.DataMessage{
		Timestamp: proto.Uint64(ts),
		Reaction: &signalpb.DataMessage_Reaction{
			Emoji:                 proto.String(emoji),
			Remove:                proto.Bool(remove),
			TargetAuthorAciBinary: author[:],
			TargetSentTimestamp:   proto.Uint64(targetTs),
		},
	}
	if err := b.sendDataMessage(convoID, dm); err != nil {
		return "", err
	}
	b.reactMu.Lock()
	if remove {
		delete(b.myReactions, targetMsgID)
	} else {
		b.myReactions[targetMsgID] = emoji
	}
	b.reactMu.Unlock()
	out, _ := json.Marshal(map[string]string{"messageId": messageID(b.selfACI(), ts)})
	return string(out), nil
}

func parseMessageID(id string) (uuid.UUID, uint64, error) {
	parts := strings.SplitN(id, "|", 2)
	if len(parts) != 2 {
		return uuid.Nil, 0, fmt.Errorf("bad message id %q", id)
	}
	author, err := uuid.Parse(parts[0])
	if err != nil {
		return uuid.Nil, 0, fmt.Errorf("bad message id %q: %w", id, err)
	}
	ts, err := strconv.ParseUint(parts[1], 10, 64)
	if err != nil {
		return uuid.Nil, 0, fmt.Errorf("bad message id %q: %w", id, err)
	}
	return author, ts, nil
}

func (b *Bridge) findMessage(convoID, msgID string) *msgEntry {
	b.chatsMu.Lock()
	defer b.chatsMu.Unlock()
	ent := b.chats[convoID]
	if ent == nil {
		return nil
	}
	for _, m := range ent.Messages {
		if m.ID == msgID {
			cp := *m
			return &cp
		}
	}
	return nil
}

// sendDataMessage routes a DataMessage to a DM or a group, echoing the chat's
// disappearing-message timer: Signal treats a DataMessage whose timer differs
// from the conversation's as a timer change, so omitting it would switch the
// timer off for everyone. Our own sends are not looped back by the server.
func (b *Bridge) sendDataMessage(convoID string, dm *signalpb.DataMessage) error {
	if convoID == setupChatID {
		return fmt.Errorf("this is MirrorMsg's setup thread, not a Signal chat")
	}
	cli := b.currentClient()
	if cli == nil {
		return fmt.Errorf("signal not paired")
	}
	b.chatsMu.Lock()
	if ent := b.chats[convoID]; ent != nil && ent.ExpireTimer > 0 {
		dm.ExpireTimer = proto.Uint32(ent.ExpireTimer)
		if ent.ExpireVersion > 0 {
			dm.ExpireTimerVersion = proto.Uint32(ent.ExpireVersion)
		}
	}
	b.chatsMu.Unlock()
	content := signalmeow.WrapDataMessage(dm)

	sctx, cancel := context.WithTimeout(b.ctx, 60*time.Second)
	defer cancel()
	if isGroupID(convoID) {
		res, err := cli.SendGroupMessage(sctx, types.GroupIdentifier(convoID), content)
		if err != nil {
			return err
		}
		if res != nil && len(res.SuccessfullySentTo) == 0 && len(res.FailedToSendTo) > 0 {
			return fmt.Errorf("failed to send to any group member: %v", res.FailedToSendTo[0].Error)
		}
		return nil
	}
	sid, err := libsignalgo.ServiceIDFromString(convoID)
	if err != nil {
		return fmt.Errorf("bad conversation id %q: %w", convoID, err)
	}
	res := cli.SendMessage(sctx, sid, content)
	if !res.WasSuccessful {
		if res.Error != nil {
			return res.Error
		}
		return fmt.Errorf("send failed")
	}
	return nil
}

func (b *Bridge) recordOwnSend(convoID string, ts uint64, text, replyToID string) (string, error) {
	self := b.selfACI()
	m := &msgEntry{
		ID:        messageID(self, ts),
		Text:      text,
		TsMicros:  int64(ts) * 1000,
		FromMe:    true,
		Sender:    self.String(),
		ReplyToID: replyToID,
	}
	b.chatsMu.Lock()
	ent := b.chats[convoID]
	if ent == nil {
		ent = &chatEntry{ID: convoID, IsGroup: isGroupID(convoID)}
		if !ent.IsGroup {
			ent.Number = b.numberFor(convoID)
		}
		b.chats[convoID] = ent
	}
	ent.addMsg(m)
	ent.sortAndCap()
	b.saveChatMessagesLocked(ent)
	b.saveIndexLocked()
	b.chatsMu.Unlock()
	b.emitJSON("MESSAGE", b.makeMessage(convoID, m))
	out, _ := json.Marshal(map[string]string{"messageId": m.ID})
	return string(out), nil
}

// ---- setup thread + link QR ----------------------------------------------------

func (b *Bridge) postSetup(text, mediaID, mime string) {
	now := time.Now()
	m := &msgEntry{
		ID:       "setup|" + strconv.FormatInt(now.UnixMilli(), 10),
		Text:     text,
		TsMicros: now.UnixMicro(),
		Sender:   setupSender,
	}
	if mediaID != "" {
		m.Parts = []mediaPart{{Kind: "image", Mime: mime, MediaID: mediaID}}
	}
	b.chatsMu.Lock()
	ent := b.chats[setupChatID]
	if ent == nil {
		ent = &chatEntry{ID: setupChatID, Name: setupChatName}
		b.chats[setupChatID] = ent
	}
	ent.addMsg(m)
	ent.sortAndCap()
	b.saveChatMessagesLocked(ent)
	b.saveIndexLocked()
	b.chatsMu.Unlock()
	b.emitJSON("CHATS_UPDATED", map[string]string{"setup": "1"})
	b.emitJSON("MESSAGE", b.makeMessage(setupChatID, m))
}

func errJSON(msg string) string {
	data, _ := json.Marshal(map[string]string{"error": msg})
	return string(data)
}

// renderLinkQR draws the current provisioning URL as a QR at the largest whole
// pixel-per-module size that fits the box, with a 2-module quiet zone, so the
// watch shows it unscaled and crisp.
func (b *Bridge) renderLinkQR(maxW, maxH int) ([]byte, error) {
	b.linkMu.Lock()
	url := b.linkQR
	b.linkMu.Unlock()
	if url == "" {
		return nil, errors.New("no link code right now, start linking again on the phone")
	}
	q, err := qrcode.New(url, qrcode.Low)
	if err != nil {
		return nil, err
	}
	q.DisableBorder = true
	bm := q.Bitmap()
	n := len(bm)
	if n == 0 {
		return nil, errors.New("empty qr")
	}
	const quiet = 2
	limit := maxW
	if maxH > 0 && (limit <= 0 || maxH < limit) {
		limit = maxH
	}
	if limit <= 0 {
		limit = 200
	}
	ppm := limit / (n + 2*quiet)
	if ppm < 1 {
		ppm = 1
	}
	size := (n + 2*quiet) * ppm
	img := image.NewGray(image.Rect(0, 0, size, size))
	for i := range img.Pix {
		img.Pix[i] = 0xFF
	}
	for y := 0; y < n; y++ {
		for x := 0; x < n; x++ {
			if !bm[y][x] {
				continue
			}
			x0 := (x + quiet) * ppm
			y0 := (y + quiet) * ppm
			for dy := 0; dy < ppm; dy++ {
				for dx := 0; dx < ppm; dx++ {
					img.SetGray(x0+dx, y0+dy, color.Gray{Y: 0})
				}
			}
		}
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// DownloadImageForWatch serves a still (an image attachment, a sticker, or the
// setup thread's link QR) through the shared watch encoder.
func (b *Bridge) DownloadImageForWatch(mediaID string, maxW, maxH, maxBytes, quality int) (string, error) {
	var raw []byte
	var err error
	if strings.HasPrefix(mediaID, linkMediaBase) {
		raw, err = b.renderLinkQR(maxW, maxH)
	} else {
		raw, err = b.fetchMediaBytes(mediaID)
	}
	if err != nil {
		return errJSON(err.Error()), nil
	}
	out, err := watchimage.EncodeImageForWatch(raw, maxW, maxH, maxBytes, quality)
	if err != nil {
		return errJSON("encode: " + err.Error()), nil
	}
	return out, nil
}

// DownloadMediaForWatch is the unified twin: mediaroute decides from the bytes
// whether it is an animation or a still. Returns "" on any failure so the phone
// falls back to the still path.
func (b *Bridge) DownloadMediaForWatch(mediaID string,
	maxW, maxH, maxBytes, quality, fpsFloor, maxFrames, gifBytes, startRung int) (string, error) {
	if strings.HasPrefix(mediaID, linkMediaBase) {
		return "", nil
	}
	raw, err := b.fetchMediaBytes(mediaID)
	if err != nil {
		return "", nil
	}
	out, err := mediaroute.Encode(raw, maxW, maxH, maxBytes, quality, fpsFloor, maxFrames, gifBytes, startRung)
	if err != nil {
		return "", nil
	}
	return out, nil
}

// DownloadMediaToFile writes a video or audio attachment (voice notes are
// AAC/M4A, sometimes Ogg from desktop), decrypted, to path for the phone's
// streamer / voice card. Returns the byte count, or -1 on any failure.
func (b *Bridge) DownloadMediaToFile(mediaID, path string) int64 {
	ptr := b.mediaPointer(mediaID)
	if ptr == nil {
		b.emit("DIAG", "SIG-MEDIA no stored handle for "+shortID(mediaID))
		return -1
	}
	if ptr.GetSize() > maxVideoBytes {
		b.emit("DIAG", "SIG-MEDIA too large: "+shortID(mediaID))
		return -1
	}
	f, err := os.Create(path)
	if err != nil {
		b.emit("DIAG", "SIG-MEDIA create: "+err.Error())
		return -1
	}
	ctx, cancel := context.WithTimeout(b.ctx, 3*time.Minute)
	defer cancel()
	_, err = signalmeow.DownloadAttachmentWithPointer(ctx, ptr, nil, f)
	_ = f.Close()
	if err != nil {
		_ = os.Remove(path)
		b.emit("DIAG", "SIG-MEDIA download failed: "+err.Error())
		return -1
	}
	st, err := os.Stat(path)
	if err != nil {
		return -1
	}
	return st.Size()
}

// ---- media handles ---------------------------------------------------------------

// attachMedia records the message's media as download handles and tags the
// index entry so the watch knows what each item is. Every picture, GIF and
// video becomes an item, in order (several of them are an album: Signal sends
// an album as one message); a sticker is one picture; audio (a voice note or an
// audio file) is used only when there is nothing to look at. Signal GIFs are
// mp4 clips with the GIF flag.
func (b *Bridge) attachMedia(m *msgEntry, dm *signalpb.DataMessage) {
	var visual, audio []mediaPart
	var ptrs, audioPtrs []*signalpb.AttachmentPointer
	if st := dm.GetSticker(); st != nil && st.GetData() != nil {
		visual = append(visual, mediaPart{Kind: "image", Mime: "image/png"})
		ptrs = append(ptrs, st.GetData())
	}
	for _, a := range dm.GetAttachments() {
		ct := strings.ToLower(a.GetContentType())
		switch {
		case strings.HasPrefix(ct, "image/"):
			if ct == "image/webp" {
				ct = "image/png"
			}
			visual = append(visual, mediaPart{Kind: "image", Mime: ct})
			ptrs = append(ptrs, a)
		case strings.HasPrefix(ct, "video/"):
			visual = append(visual, mediaPart{Kind: "video", Mime: ct, GifLike: a.GetFlags()&uint32(signalpb.AttachmentPointer_GIF) != 0})
			ptrs = append(ptrs, a)
		case strings.HasPrefix(ct, "audio/") && len(audio) == 0:
			audio = append(audio, mediaPart{Kind: "audio", Mime: ct})
			audioPtrs = append(audioPtrs, a)
		}
	}
	if len(visual) == 0 {
		visual, ptrs = audio, audioPtrs
	}
	if len(visual) > maxAlbumItems {
		visual, ptrs = visual[:maxAlbumItems], ptrs[:maxAlbumItems]
	}
	handles := make([][2]string, 0, len(visual))
	for i := range visual {
		raw, err := proto.Marshal(ptrs[i])
		if err != nil {
			continue
		}
		id := m.ID
		if i > 0 {
			id = m.ID + "#" + strconv.Itoa(i)
		}
		visual[i].MediaID = id
		handles = append(handles, [2]string{id, base64.StdEncoding.EncodeToString(raw)})
		m.Parts = append(m.Parts, visual[i])
	}
	b.rememberMedia(handles)
}

// rememberMedia stores download handles (id, base64 AttachmentPointer) and
// persists the store once per message, FIFO-capped.
func (b *Bridge) rememberMedia(handles [][2]string) {
	if len(handles) == 0 {
		return
	}
	b.mediaMu.Lock()
	for _, h := range handles {
		if _, ok := b.media[h[0]]; !ok {
			b.mediaOrder = append(b.mediaOrder, h[0])
		}
		b.media[h[0]] = h[1]
	}
	for len(b.mediaOrder) > maxStoredMedia {
		old := b.mediaOrder[0]
		b.mediaOrder = b.mediaOrder[1:]
		delete(b.media, old)
	}
	data, err := json.Marshal(b.media)
	b.mediaMu.Unlock()
	if err == nil {
		_ = os.WriteFile(b.mediaPath, data, 0600)
	}
}

func (b *Bridge) loadMedia() {
	data, err := os.ReadFile(b.mediaPath)
	if err != nil {
		return
	}
	m := map[string]string{}
	if json.Unmarshal(data, &m) == nil {
		b.mediaMu.Lock()
		b.media = m
		b.mediaOrder = make([]string, 0, len(m))
		for k := range m {
			b.mediaOrder = append(b.mediaOrder, k)
		}
		b.mediaMu.Unlock()
	}
}

func (b *Bridge) mediaPointer(mediaID string) *signalpb.AttachmentPointer {
	b.mediaMu.Lock()
	enc := b.media[mediaID]
	b.mediaMu.Unlock()
	if enc == "" {
		return nil
	}
	raw, err := base64.StdEncoding.DecodeString(enc)
	if err != nil {
		return nil
	}
	ptr := &signalpb.AttachmentPointer{}
	if proto.Unmarshal(raw, ptr) != nil {
		return nil
	}
	return ptr
}

// fetchMediaBytes downloads + decrypts a stored attachment. WebP (Signal's
// static stickers) is converted to PNG with transparency flattened to white,
// which the shared encoders understand; APNG decodes as its first frame.
func (b *Bridge) fetchMediaBytes(mediaID string) ([]byte, error) {
	ptr := b.mediaPointer(mediaID)
	if ptr == nil {
		return nil, errors.New("no stored media handle for " + mediaID)
	}
	if ptr.GetSize() > 16*1024*1024 {
		return nil, errors.New("attachment too large")
	}
	ctx, cancel := context.WithTimeout(b.ctx, 90*time.Second)
	defer cancel()
	raw, err := signalmeow.DownloadAttachmentWithPointer(ctx, ptr, nil, nil)
	if err != nil {
		return nil, fmt.Errorf("download: %w", err)
	}
	if len(raw) >= 12 && string(raw[:4]) == "RIFF" && string(raw[8:12]) == "WEBP" {
		return webpToPNG(raw)
	}
	return raw, nil
}

func webpToPNG(raw []byte) ([]byte, error) {
	img, err := webp.Decode(bytes.NewReader(raw))
	if err != nil {
		return nil, fmt.Errorf("webp: %w", err)
	}
	bounds := img.Bounds()
	flat := image.NewRGBA(bounds)
	draw.Draw(flat, bounds, image.White, image.Point{}, draw.Src)
	draw.Draw(flat, bounds, img, bounds.Min, draw.Over)
	var buf bytes.Buffer
	if err := png.Encode(&buf, flat); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// ---- chat photos -----------------------------------------------------------------

// avatarKeyFor names a chat's photo for the phone's contact-photo cache:
// "p:<ACI>|<profile avatar path>" for a 1:1 chat, "g:<group id>|<group avatar
// path>" for a group, "" for none. Signal uploads every new picture to a new
// path, so the key changes whenever the photo does.
func avatarKeyFor(e *chatEntry) string {
	if e == nil || e.Avatar == "" || e.Avatar == avatarNeedKey {
		return ""
	}
	if e.IsGroup {
		return "g:" + e.ID + "|" + e.Avatar
	}
	return "p:" + e.ID + "|" + e.Avatar
}

// avatarDue says whether a chat's photo should be looked up (again).
func avatarDue(e *chatEntry, now time.Time) bool {
	if e.ID == setupChatID || strings.HasPrefix(e.ID, pniPrefix) {
		return false
	}
	wait := avatarRecheckNone
	if avatarKeyFor(e) != "" {
		wait = avatarRecheckKnown
	}
	return now.Sub(time.Unix(e.AvatarAt, 0)) > wait
}

// avatarState reads a chat's photo key and whether it is due a look.
func (b *Bridge) avatarState(chatID string) (string, bool) {
	b.chatsMu.Lock()
	defer b.chatsMu.Unlock()
	ent := b.chats[chatID]
	if ent == nil {
		return "", false
	}
	return avatarKeyFor(ent), avatarDue(ent, time.Now())
}

// AvatarKey names a chat's photo (see avatarKeyFor), "" for none. With lookup
// false it only reads the index (the chat-list path: no network; a chat due a
// look is queued for the background check). With lookup true (a notification,
// off the main thread, may block) a chat with no known photo that is due a
// look is looked up on Signal before answering.
func (b *Bridge) AvatarKey(convoID string, lookup bool) string {
	key, due := b.avatarState(convoID)
	if !due {
		return key
	}
	if !lookup || key != "" || !b.avatarMayLook(convoID) {
		b.queueAvatarChecks([]string{convoID})
		return key
	}
	_, err := b.checkAvatar(convoID)
	b.avatarLooked(convoID, err)
	if err != nil {
		b.emit("DIAG", "SIG-PHOTOS lookup failed: "+err.Error())
	}
	key, _ = b.avatarState(convoID)
	return key
}

// AvatarBytes downloads and decrypts the photo an AvatarKey names (the image as
// its owner uploaded it, normally JPEG). nil with no error: there is no picture
// at that key any more (replaced, removed or re-keyed; the chat is looked at
// again so a new key appears). An error: try again later.
func (b *Bridge) AvatarBytes(key string) ([]byte, error) {
	kind, rest, ok := strings.Cut(key, ":")
	if !ok {
		return nil, nil
	}
	chatID, path, ok := strings.Cut(rest, "|")
	if !ok || chatID == "" || path == "" {
		return nil, nil
	}
	cli := b.currentClient()
	if cli == nil {
		return nil, errors.New("signal not linked")
	}
	ctx, cancel := context.WithTimeout(b.ctx, avatarFetchWait)
	defer cancel()
	var raw []byte
	var err error
	switch kind {
	case "p":
		aci, perr := uuid.Parse(chatID)
		if perr != nil {
			return nil, nil
		}
		pk, kerr := cli.Store.RecipientStore.LoadProfileKey(ctx, aci)
		if kerr != nil {
			return nil, kerr
		}
		if pk == nil || pk.IsEmpty() {
			b.relookAvatar(chatID)
			return nil, nil
		}
		raw, err = cli.DownloadUserAvatar(ctx, path, *pk)
	case "g":
		mk, kerr := cli.Store.GroupStore.MasterKeyFromGroupIdentifier(ctx, types.GroupIdentifier(chatID))
		if kerr != nil {
			return nil, kerr
		}
		if mk == "" {
			return nil, nil
		}
		raw, err = cli.DownloadGroupAvatar(ctx, path, mk)
	default:
		return nil, nil
	}
	if err != nil {
		if avatarGone(err) {
			b.relookAvatar(chatID)
			return nil, nil
		}
		return nil, err
	}
	if len(raw) == 0 {
		return nil, nil
	}
	return raw, nil
}

// checkAvatar looks a chat's photo up on Signal (the contact's profile, or the
// group's state) and records its path in the index. Reports whether the chat's
// photo key changed; an error means Signal couldn't answer (try later).
func (b *Bridge) checkAvatar(chatID string) (bool, error) {
	cli := b.currentClient()
	if cli == nil {
		return false, errors.New("signal not linked")
	}
	ctx, cancel := context.WithTimeout(b.ctx, avatarLookupWait)
	defer cancel()
	path := ""
	if isGroupID(chatID) {
		g, _, err := cli.RetrieveGroupByID(ctx, types.GroupIdentifier(chatID), 0)
		switch st := httpStatus(err); {
		case err == nil:
			if g != nil {
				path = g.AvatarPath
			}
		case errors.Is(err, signalmeow.ErrGroupMasterKeyNotFound), st == http.StatusForbidden, st == http.StatusNotFound:
			// Not a group we can read (any more): no picture.
		default:
			return false, err
		}
	} else {
		aci, err := uuid.Parse(chatID)
		if err != nil {
			return b.setAvatar(chatID, ""), nil
		}
		pk, err := cli.Store.RecipientStore.LoadProfileKey(ctx, aci)
		if err != nil {
			return false, err
		}
		if pk == nil || pk.IsEmpty() {
			return b.setAvatar(chatID, avatarNeedKey), nil
		}
		prof, err := cli.RetrieveProfileByID(ctx, aci, signalmeow.DefaultProfileRefreshAfter)
		switch {
		case err == nil:
			if prof != nil && prof.AvatarPath != "clear" {
				path = prof.AvatarPath
			}
		case errors.Is(err, signalmeow.ErrProfileUnauthorized):
			// Our copy of their profile key is out of date; their next
			// message brings the new one.
			path = avatarNeedKey
		case errors.Is(err, signalmeow.ErrProfileNotFound):
			// No such account any more: no picture.
		default:
			return false, err
		}
	}
	return b.setAvatar(chatID, path), nil
}

// setAvatar records a looked-up photo path for a chat. Reports whether the
// chat's photo key changed.
func (b *Bridge) setAvatar(chatID, path string) bool {
	b.chatsMu.Lock()
	defer b.chatsMu.Unlock()
	ent := b.chats[chatID]
	if ent == nil {
		return false
	}
	before := avatarKeyFor(ent)
	ent.Avatar = path
	ent.AvatarAt = time.Now().Unix()
	b.saveIndexLocked()
	return avatarKeyFor(ent) != before
}

// relookAvatar marks a chat's photo for a fresh look (its key stopped working).
func (b *Bridge) relookAvatar(chatID string) {
	b.chatsMu.Lock()
	if ent := b.chats[chatID]; ent != nil {
		ent.AvatarAt = 0
	}
	b.chatsMu.Unlock()
	b.queueAvatarChecks([]string{chatID})
}

// avatarMayLook says whether Signal may be asked about a chat's photo right
// now: connected, not paused, and not inside the chat's retry wait.
func (b *Bridge) avatarMayLook(chatID string) bool {
	if !b.isConnected() {
		return false
	}
	now := time.Now()
	b.avatarMu.Lock()
	defer b.avatarMu.Unlock()
	return !now.Before(b.avatarPause) && now.Sub(b.avatarFailed[chatID]) >= avatarRetryAfter
}

// avatarLooked records a look's outcome for the retry wait.
func (b *Bridge) avatarLooked(chatID string, err error) {
	b.avatarMu.Lock()
	defer b.avatarMu.Unlock()
	if err != nil {
		b.avatarFailed[chatID] = time.Now()
	} else {
		delete(b.avatarFailed, chatID)
	}
}

// queueAvatarChecks queues chats for the background photo check, in order and
// each once, and starts the worker if it isn't running.
func (b *Bridge) queueAvatarChecks(ids []string) {
	if len(ids) == 0 || !b.isConnected() {
		return
	}
	now := time.Now()
	b.avatarMu.Lock()
	if now.Before(b.avatarPause) {
		b.avatarMu.Unlock()
		return
	}
	for _, id := range ids {
		if b.avatarQueued[id] || now.Sub(b.avatarFailed[id]) < avatarRetryAfter {
			continue
		}
		b.avatarQueued[id] = true
		b.avatarQueue = append(b.avatarQueue, id)
	}
	start := !b.avatarRunning && len(b.avatarQueue) > 0
	if start {
		b.avatarRunning = true
	}
	b.avatarMu.Unlock()
	if start {
		go b.avatarWorker()
	}
}

// queueDueAvatars queues the most recent chats whose photo is due a look (run
// on connect, so pictures fill in without waiting for the next list).
func (b *Bridge) queueDueAvatars(limit int) {
	type dueChat struct {
		id string
		ts int64
	}
	now := time.Now()
	var due []dueChat
	b.chatsMu.Lock()
	for _, e := range b.chats {
		if avatarDue(e, now) {
			due = append(due, dueChat{e.ID, e.TsMicros})
		}
	}
	b.chatsMu.Unlock()
	sort.Slice(due, func(i, j int) bool { return due[i].ts > due[j].ts })
	if len(due) > limit {
		due = due[:limit]
	}
	ids := make([]string, len(due))
	for i, d := range due {
		ids[i] = d.id
	}
	b.queueAvatarChecks(ids)
}

// avatarWorker works through the photo-check queue one chat at a time, paced,
// and tells the phone (CHATS_UPDATED) as photos are learned. It stops when
// Signal disconnects; after avatarMaxFailRun failures in a row it drops the
// queue and pauses for avatarPauseFor.
func (b *Bridge) avatarWorker() {
	looked, learned, failed, pending, failRun := 0, 0, 0, 0, 0
	lastEmit := time.Now()
	var lastErr error
	for {
		online := b.isConnected()
		b.avatarMu.Lock()
		if !online || b.ctx.Err() != nil || len(b.avatarQueue) == 0 {
			for _, id := range b.avatarQueue {
				delete(b.avatarQueued, id)
			}
			b.avatarQueue = nil
			b.avatarRunning = false
			b.avatarMu.Unlock()
			break
		}
		id := b.avatarQueue[0]
		b.avatarQueue = b.avatarQueue[1:]
		b.avatarMu.Unlock()

		changed, err := b.checkAvatar(id)
		b.avatarLooked(id, err)
		b.avatarMu.Lock()
		delete(b.avatarQueued, id)
		if err != nil {
			if failRun++; failRun >= avatarMaxFailRun {
				b.avatarPause = time.Now().Add(avatarPauseFor)
				for _, q := range b.avatarQueue {
					delete(b.avatarQueued, q)
				}
				b.avatarQueue = nil
			}
		} else {
			failRun = 0
		}
		b.avatarMu.Unlock()
		looked++
		if err != nil {
			failed++
			lastErr = err
		}
		if changed {
			learned++
			pending++
		}
		if pending > 0 && time.Since(lastEmit) >= avatarEmitEvery {
			b.emitJSON("CHATS_UPDATED", map[string]int{"photos": pending})
			pending, lastEmit = 0, time.Now()
		}
		select {
		case <-b.ctx.Done():
		case <-time.After(avatarPace):
		}
	}
	if pending > 0 {
		b.emitJSON("CHATS_UPDATED", map[string]int{"photos": pending})
	}
	msg := fmt.Sprintf("SIG-PHOTOS looked at %d chats: %d new or changed, %d failed", looked, learned, failed)
	if lastErr != nil {
		msg += " (last: " + lastErr.Error() + ")"
	}
	b.emit("DIAG", msg)
}

// avatarGone says whether a download failed because the picture at that path
// is no longer readable to us (replaced, removed, or encrypted under a newer
// key) rather than for a reason worth retrying. signalmeow reports both only
// as text: "unexpected response status N" and "failed to decrypt ...".
func avatarGone(err error) bool {
	if err == nil {
		return false
	}
	st := httpStatus(err)
	if st >= 400 && st < 500 && st != http.StatusRequestTimeout && st != http.StatusTooManyRequests {
		return true
	}
	return strings.Contains(err.Error(), "decrypt")
}

// httpStatus is the N of a signalmeow "unexpected response status N" error,
// else 0.
func httpStatus(err error) int {
	if err == nil {
		return 0
	}
	const marker = "unexpected response status"
	s := err.Error()
	i := strings.Index(s, marker)
	if i < 0 {
		return 0
	}
	n := 0
	for _, c := range strings.TrimLeft(s[i+len(marker):], ": ") {
		if c < '0' || c > '9' || n > 99 {
			break
		}
		n = n*10 + int(c-'0')
	}
	return n
}

// ---- reactions + remote deletes --------------------------------------------------

func (b *Bridge) handleReaction(chatID string, sender uuid.UUID, r *signalpb.DataMessage_Reaction) {
	var author uuid.UUID
	if raw := r.GetTargetAuthorAciBinary(); len(raw) == 16 {
		author, _ = uuid.FromBytes(raw)
	} else if s := r.GetTargetAuthorAci(); s != "" {
		author, _ = uuid.Parse(s)
	}
	if author == uuid.Nil || r.GetTargetSentTimestamp() == 0 {
		return
	}
	target := messageID(author, r.GetTargetSentTimestamp())
	op := "upsert"
	emoji := r.GetEmoji()
	if r.GetRemove() {
		op = "delete"
		emoji = ""
	}
	if sender == b.selfACI() {
		b.reactMu.Lock()
		if r.GetRemove() {
			delete(b.myReactions, target)
		} else {
			b.myReactions[target] = r.GetEmoji()
		}
		b.reactMu.Unlock()
	}
	b.emitJSON("REACTION", map[string]any{
		"op": op, "thread": chatID, "msg": target, "actor": sender.String(), "emoji": emoji,
	})
}

func (b *Bridge) handleRemoteDelete(chatID string, sender uuid.UUID, targetTs uint64) {
	if targetTs == 0 {
		return
	}
	target := messageID(sender, targetTs)
	b.chatsMu.Lock()
	ent := b.chats[chatID]
	var hit *msgEntry
	if ent != nil {
		for _, m := range ent.Messages {
			if m.ID == target {
				m.Text = "(message deleted)"
				m.clearMedia()
				cp := *m
				hit = &cp
				break
			}
		}
		if hit != nil {
			ent.sortAndCap()
			b.saveChatMessagesLocked(ent)
			b.saveIndexLocked()
		}
	}
	b.chatsMu.Unlock()
	if hit != nil {
		b.emitJSON("MESSAGE", b.makeMessage(chatID, hit))
	}
}
