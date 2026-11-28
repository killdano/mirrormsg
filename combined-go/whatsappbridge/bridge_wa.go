// Package whatsappbridge is the native WhatsApp bridge for MirrorMsg. It wraps
// whatsmeow (the Go WhatsApp-web multidevice client) and exposes the same
// gomobile surface shape as the other native bridges:
// an EventSink the Kotlin side implements, a NewBridge constructor, and a flat
// set of lifecycle/data methods.
//
// THIS FILE IS MILESTONE ZERO. It deliberately does only enough to prove the
// risky unknowns on-device before any features are built:
//  1. whatsmeow compiles + runs under gomobile on android/arm64 with a PURE-GO
//     SQLite store (modernc.org/sqlite). whatsmeow's DEFAULT store driver is
//     mattn/go-sqlite3, which is cgo and would break the gomobile build — so we
//     never use it; we open a modernc *sql.DB ourselves and hand it to whatsmeow.
//  2. QR pairing completes and the device session PERSISTS across restarts.
//
// Receive/translate-to-Google-schema, media, and send are intentionally NOT here
// yet — they come in later milestones once this proves out.
package whatsappbridge

import (
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	// Pure-Go SQLite. Registers a database/sql driver under the name "sqlite".
	// MUST be modernc, NOT github.com/mattn/go-sqlite3 (cgo) — cgo breaks gomobile.
	_ "modernc.org/sqlite"

	// Reuse the EXACT watch image encoder Google uses (scale + 4bpp palettize),
	// so WhatsApp images render identically. We only do the WhatsApp-specific
	// download/decrypt here, then hand raw bytes to watchimage.EncodeImageForWatch.
	"fi.mirrormsg/watchimage"

	"go.mau.fi/whatsmeow"
	waCompanionReg "go.mau.fi/whatsmeow/proto/waCompanionReg"
	// Image download handles are persisted as marshaled ImageMessage protos so
	// images survive an app restart. If this proto import path is wrong for this
	// whatsmeow version, the compile error names the right one (older layout is
	// "go.mau.fi/whatsmeow/binary/proto" with type waProto.ImageMessage).
	waE2E "go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
)

// EventSink is implemented on the Kotlin side; the Go event loop calls OnEvent
// for every relevant event. Identical contract to the other two bridges, so the
// Kotlin adapter pattern is unchanged.
//   - eventType is a stable string tag (CONNECTED, LOGGED_OUT, QR, PAIRED,
//     PAIR_TIMEOUT, PAIR_ERROR, EVENT). Receive/media tags (MESSAGE, ...) arrive
//     in a later milestone.
//   - jsonPayload is the event marshaled to JSON.
type EventSink interface {
	OnEvent(eventType string, jsonPayload string)
}

// Bridge wraps a single whatsmeow client plus its store + lifecycle. One Bridge
// == one paired WhatsApp linked-device session.
type Bridge struct {
	client    *whatsmeow.Client
	container *sqlstore.Container
	db        *sql.DB
	sink      EventSink
	ctx       context.Context
	cancel    context.CancelFunc
	mu        sync.Mutex

	// WhatsApp Web version lookup (see fetchLatestWAVersion). verLatest is written
	// once by the lookup goroutine, before verDone closes; read only after verDone.
	verPath   string
	verDone   chan struct{}
	verLatest store.WAVersionContainer

	// Chat index: whatsmeow keeps no message archive and only delivers the chat
	// list once (via HistorySync, right after linking), so we capture it here and
	// persist to chatsPath as JSON. Live messages keep it current. ListConversations
	// serves the watch's conversation list from this.
	chatsPath string
	msgsDir   string // dir holding one JSON file of messages per chat
	chatsMu   sync.Mutex
	chats     map[string]*chatEntry

	// Image downloadables: whatsmeow images carry an encrypted URL + keys (not the
	// bytes). We keep each image's ImageMessage proto (marshaled, base64) keyed by
	// message ID so the watch can fetch it on demand. PERSISTED to disk so images
	// still load after an app restart (the affordance is persisted phone-side, so
	// the handle has to be too — otherwise old images show a dead icon).
	imgMu    sync.Mutex
	imgStore map[string]string // msgID -> base64(proto-marshaled ImageMessage)
	imgOrder []string
	imgPath  string

	// Profile/push names (the names people set on their OWN WhatsApp account —
	// what WhatsApp shows for people you haven't saved as contacts). Keyed by JID
	// user number. Sourced from the HistorySync push-names table + kept fresh by
	// live messages. Persisted so the re-link to capture it is a ONE-TIME cost.
	pushPath  string
	pushMu    sync.Mutex
	pushNames map[string]string

	// Group subjects (the group's name, e.g. "Familia"), keyed by group JID.
	// Fetched on demand via GetGroupInfo and persisted, so the list shows real
	// group names instead of the raw group id.
	groupPath  string
	groupMu    sync.Mutex
	groupNames map[string]string
	// Group JIDs whose subject we've already tried to fetch this session (success
	// or failure), so repeated CHATS_UPDATED sweeps don't re-hit the network or
	// spam the log for the same group. Guarded by groupMu.
	groupTried map[string]bool

	// Contact/group photos: chat key -> the picture's id (or "none"), looked up in
	// the background (one at a time) and persisted, so the chat list can name each
	// chat's photo without a network call. See AvatarID / AvatarURL.
	avatarPath    string
	avatarMu      sync.Mutex
	avatars       map[string]*avatarEntry
	avatarFailed  map[string]time.Time
	avatarPending map[string]bool
	avatarQueue   chan string
	avatarLearned bool
}

// maxStoredImages caps how many image download-handles we keep (FIFO).
const maxStoredImages = 2000

// chatEntry is one row of the conversation list plus a capped tail of recent
// messages (so opening a thread can show history, not just the last message).
type chatEntry struct {
	JID      string      `json:"jid"`
	Name     string      `json:"name"`
	Snippet  string      `json:"snippet"`
	TsMicros int64       `json:"ts"`
	Number   string      `json:"number"`
	FromMe   bool        `json:"fromMe"`
	Messages []*msgEntry `json:"msgs"`
}

// msgEntry is one stored message (compact; the watch-schema JSON is built on read).
type msgEntry struct {
	ID       string `json:"id"`
	Text     string `json:"t"`
	TsMicros int64  `json:"ts"`
	FromMe   bool   `json:"me"`
	Name     string `json:"n"`
	Number   string `json:"num"`
	// Sender is the message author's FULL JID string (e.g. "140492792680561@lid"
	// or "13239193589@s.whatsapp.net"). Unlike Number (the bare user), it keeps
	// the server, so a group participant on @lid resolves through GetPNForLID ->
	// phone -> saved contact instead of being mistaken for a phone number.
	Sender string `json:"snd,omitempty"`
	// ImgMime is the image's mimetype when this message is an image. Persisted
	// alongside the download handle (imgStore), so after a restart the message
	// still advertises its image AND the handle is there to serve it.
	ImgMime string `json:"im,omitempty"`
	// VidMime is the video's mimetype when this message is a video. Its download
	// handle lives in the same store as images, under "v:"+ID, so a video can't
	// collide with an image and survives restarts the same way.
	VidMime string `json:"vm,omitempty"`
	// GifLike marks a video WhatsApp flagged for GIF playback (a looping clip).
	GifLike bool `json:"gl,omitempty"`
	// VidThumb is the video's embedded JPEG thumbnail, base64, sent as the poster.
	VidThumb string `json:"vt,omitempty"`
	// AudMime is the audio's mimetype when this message is a voice note or audio
	// file. Its download handle lives in the media store under "a:"+ID.
	AudMime string `json:"am,omitempty"`
	// AlbumID is the album parent's message id when this picture or video is one
	// item of a WhatsApp album; the phone groups messages sharing it.
	AlbumID string `json:"al,omitempty"`
	// ReplyToID is the quoted message's id when this message is a reply (from
	// whatsmeow ContextInfo.StanzaID). The phone resolves the quoted sender/text
	// from the stored thread by this id; "" for non-replies.
	ReplyToID string `json:"rid,omitempty"`
}

// maxMsgsPerChat caps the per-chat history we keep (and serve to the watch).
// Raised from 50 so a FULL history sync's depth survives (measured up to ~380
// displayable in an active group). Aligned with the phone-side MessageStore
// MAX_PER_CONVO = 1000, so bridge and phone trim to the same depth.
const maxMsgsPerChat = 1000

// addMsg appends a message (deduped by ID). Call sortAndCap after a batch.
func (e *chatEntry) addMsg(m *msgEntry) {
	if m.ID != "" {
		for _, x := range e.Messages {
			if x.ID == m.ID {
				return
			}
		}
	}
	e.Messages = append(e.Messages, m)
}

// sortAndCap orders messages oldest->newest, trims to the cap, and refreshes the
// list-row summary (snippet/timestamp/fromMe) from the newest message.
func (e *chatEntry) sortAndCap() {
	// Sort by (timestamp, id). WhatsApp message timestamps are second-resolution,
	// so many messages in a busy chat share the same TsMicros; the id tiebreak
	// gives a total, deterministic order so the (ts,id) backfill cursor pages
	// through same-second runs without stalling or skipping.
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

// emit forwards a pre-built JSON string to the Kotlin sink under the given tag.
// (Kept string-only for milestone zero; it grows a proto/protojson case when we
// start emitting MESSAGE events in the receive milestone.)
func (b *Bridge) emit(eventType, jsonPayload string) {
	if b.sink != nil {
		b.sink.OnEvent(eventType, jsonPayload)
	}
}

// NewBridge opens (or creates) whatsmeow's device store at storePath and builds
// an unconnected client. If the store already holds a paired device, the session
// is restored and no pairing is needed — call Connect. Otherwise call Connect to
// begin QR pairing (QR codes arrive as "QR" events on the sink).
//
// storePath is a file path the KOTLIN side owns (e.g. ctx.filesDir + "/wa.db"),
// mirroring the existing "Kotlin owns location, Go owns content" pattern. Unlike
// the cookie bridges (which take a one-shot session JSON string), whatsmeow owns
// and re-keys its store continuously, so we point it at a file rather than pass a
// blob.
// fallbackWAVersion is a known-good WhatsApp Web version (upstream whatsmeow main,
// Sept 2026). WhatsApp rejects clients reporting an old version with
// err-client-outdated (405), and the pinned whatsmeow's built-in version went
// stale. The live lookup (fetchLatestWAVersion) supersedes this when it succeeds.
var fallbackWAVersion = store.WAVersionContainer{2, 3000, 1047769893}

// applyWAVersion switches the reported WhatsApp Web version to v if v is newer
// than the current one. Must run while disconnected (it writes whatsmeow package
// globals read at connect time). The pinned whatsmeow's SetWAVersion does not
// refresh the login user-agent's AppVersion (fixed upstream later), so set it too.
func applyWAVersion(v store.WAVersionContainer) bool {
	if v.IsZero() || !store.GetWAVersion().LessThan(v) {
		return false
	}
	store.SetWAVersion(v)
	store.BaseClientPayload.UserAgent.AppVersion = v.ProtoAppVersion()
	return true
}

// fetchLatestWAVersion asks web.whatsapp.com for its current version and caches
// it to verPath so the next launch starts on it without waiting. Runs once per
// bridge, in the background; Connect paths pick the result up via syncWAVersion.
func (b *Bridge) fetchLatestWAVersion() {
	defer close(b.verDone)
	ctx, cancel := context.WithTimeout(b.ctx, 8*time.Second)
	defer cancel()
	v, err := whatsmeow.GetLatestVersion(ctx, nil)
	if err != nil || v == nil {
		msg := "no version returned"
		if err != nil {
			msg = err.Error()
		}
		b.emit("DIAG", fmt.Sprintf("WA-VERSION lookup failed (%s); using %s", scrubURLs(msg), store.GetWAVersion().String()))
		return
	}
	b.verLatest = *v
	_ = os.WriteFile(b.verPath, []byte(v.String()), 0600)
}

// syncWAVersion applies the live-lookup result if it's in and newer. wait bounds
// how long to block for it: 0 never blocks (session restore can run on the
// startup path), pairing waits a few seconds (it runs on the pair screen's worker
// thread, and pairing is where an outdated version fails outright).
func (b *Bridge) syncWAVersion(wait time.Duration) {
	if wait <= 0 {
		select {
		case <-b.verDone:
		default:
			return
		}
	} else {
		select {
		case <-b.verDone:
		case <-time.After(wait):
			return
		}
	}
	if applyWAVersion(b.verLatest) {
		b.emit("DIAG", "WA-VERSION using live "+b.verLatest.String())
	}
}

func NewBridge(storePath string, sink EventSink) (*Bridge, error) {
	ctx, cancel := context.WithCancel(context.Background())

	// Report a current WhatsApp Web version before anything connects: the newer
	// of the pinned default, the known-good fallback, and last run's live lookup.
	applyWAVersion(fallbackWAVersion)
	verPath := storePath + ".waversion"
	if data, err := os.ReadFile(verPath); err == nil {
		if v, err := store.ParseVersion(strings.TrimSpace(string(data))); err == nil {
			applyWAVersion(v)
		}
	}

	// Request a FULL history sync (instead of the default recent-only) so a
	// (re-)link pulls deep per-chat history — the source of WhatsApp backfill.
	// store.DeviceProps is a whatsmeow package global read when the pairing
	// ClientPayload is built, so it MUST be set before pairing — NewBridge runs
	// before Connect, so this covers the re-link. We set only the full-sync LIMIT
	// fields on the existing config (rather than replacing the whole struct) so the
	// library's default support flags — SupportGroupHistory in particular — stay on.
	// Bounded by the phone's own retention + these limits, and one-shot: there's no
	// re-request without a re-link, so depth is captured at link time.
	store.DeviceProps.RequireFullSync = proto.Bool(true)
	if store.DeviceProps.HistorySyncConfig == nil {
		store.DeviceProps.HistorySyncConfig = &waCompanionReg.DeviceProps_HistorySyncConfig{}
	}
	store.DeviceProps.HistorySyncConfig.FullSyncDaysLimit = proto.Uint32(365)
	store.DeviceProps.HistorySyncConfig.FullSyncSizeMbLimit = proto.Uint32(10240)
	store.DeviceProps.HistorySyncConfig.StorageQuotaMb = proto.Uint32(10240)

	// A store left by the short-lived encrypted build can't be read by plain SQLite:
	// remove it (and its journals) so WhatsApp just needs linking again.
	if isUnreadableStore(storePath) {
		for _, sfx := range []string{"", "-wal", "-shm", "-journal"} {
			_ = os.Remove(storePath + sfx)
		}
		if sink != nil {
			sink.OnEvent("DIAG", "WA-STORE encrypted store removed; link WhatsApp again")
		}
	}

	// Open a PURE-GO SQLite connection ourselves (driver name "sqlite" = modernc),
	// then hand the live *sql.DB to whatsmeow with the "sqlite3" DIALECT (which
	// selects whatsmeow's SQLite SQL syntax). This decouples "which driver" from
	// "which SQL dialect", so the cgo mattn driver is never pulled in.
	//
	// modernc DSN pragmas use the `_pragma=NAME(VALUE)` form (NOT mattn's
	// `_foreign_keys=on`). Foreign keys on is required by whatsmeow's schema.
	dsn := "file:" + storePath + "?_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("open wa store: %w", err)
	}

	// VERIFY #1 (the milestone-zero unknown): the exact modernc-wiring constructor.
	// Primary (used here): NewWithDB(db, "sqlite3", log) wraps our modernc *sql.DB.
	// If this signature differs in the pinned whatsmeow, the two fallbacks are:
	//   (a) container, err := sqlstore.New(ctx, "sqlite", dsn, log)   // if dbutil
	//       accepts the "sqlite" dialect string + auto-opens the modernc driver;
	//   (b) wrap via dbutil: sqlstore.NewWithWrappedDB(dbutil.NewWithDB(db,"sqlite3"), log).
	container := sqlstore.NewWithDB(db, "sqlite3", waLog.Noop)

	// VERIFY #2: Upgrade runs the schema migrations. Current signature takes ctx;
	// if the pinned version's is parameterless, drop the ctx arg.
	if err := container.Upgrade(ctx); err != nil {
		_ = db.Close()
		cancel()
		return nil, fmt.Errorf("upgrade wa store: %w", err)
	}

	// First device in the store, or a fresh unpaired one if the store is empty.
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		_ = db.Close()
		cancel()
		return nil, fmt.Errorf("get wa device: %w", err)
	}

	client := whatsmeow.NewClient(device, waLog.Noop)
	b := &Bridge{
		client:     client,
		container:  container,
		db:         db,
		sink:       sink,
		ctx:        ctx,
		cancel:     cancel,
		imgStore:   make(map[string]string),
		pushNames:  make(map[string]string),
		groupNames: make(map[string]string),
		groupTried: make(map[string]bool),
		verPath:    verPath,
		verDone:    make(chan struct{}),

		avatars:       make(map[string]*avatarEntry),
		avatarFailed:  make(map[string]time.Time),
		avatarPending: make(map[string]bool),
		avatarQueue:   make(chan string, 512),
	}
	b.emit("DIAG", "WA-VERSION start "+store.GetWAVersion().String())
	go b.fetchLatestWAVersion()
	client.AddEventHandler(b.handleEvent)
	b.chatsPath = storePath + ".chats.json"
	// Per-chat message files live under this dir. The chat index (chatsPath) now
	// holds SUMMARY ROWS ONLY (no messages), so a live message rewrites just one
	// small per-chat file + the small index — not the whole history of every chat.
	b.msgsDir = storePath + ".msgs"
	_ = os.MkdirAll(b.msgsDir, 0700)
	b.loadChats()
	b.imgPath = storePath + ".images.json"
	b.loadImages()
	b.pushPath = storePath + ".pushnames.json"
	b.loadPushNames()
	b.groupPath = storePath + ".groups.json"
	b.loadGroupNames()
	b.avatarPath = storePath + ".avatars.json"
	b.loadAvatars()
	go b.avatarWorker()
	return b, nil
}

// isUnreadableStore reports whether a non-empty file exists at path that isn't a
// plain SQLite database (no "SQLite format 3" header), e.g. an encrypted store.
func isUnreadableStore(path string) bool {
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()
	hdr := make([]byte, 16)
	n, _ := f.Read(hdr)
	return n > 0 && !(n == 16 && string(hdr) == "SQLite format 3\x00")
}

// handleEvent is whatsmeow's event handler (func(evt any)). Milestone zero only
// surfaces lifecycle so we can confirm the loop is alive; everything else is
// forwarded under "EVENT" with its Go type name so it shows up while bringing up.
// The real MESSAGE/HISTORY_SYNC translation to Google-schema JSON lands next.
func (b *Bridge) handleEvent(evt any) {
	switch e := evt.(type) {
	case *events.Message:
		b.emitMessage(e)
	case *events.HistorySync:
		b.handleHistorySync(e)
	case *events.Connected:
		b.emit("CONNECTED", `{}`)
	case *events.LoggedOut:
		// Session died on the server side — Kotlin should prompt a re-pair.
		b.emit("LOGGED_OUT", `{"reason":"logged_out"}`)
	case *events.Picture:
		b.onPictureChanged(e)
	default:
		// e.g. *events.HistorySync, *events.Receipt — visible by type name during
		// bring-up so we can see traffic without yet decoding it.
		b.emit("EVENT", fmt.Sprintf(`{"goType":%q}`, fmt.Sprintf("%T", evt)))
	}
}

// ---- Receive: translate live messages to the watch's Google-schema JSON ------
//
// The watch pipeline (and LiveRouter's gates) read a specific shape. The two
// load-bearing fields: timestamp MUST be in MICROSECONDS as a string (the
// recency gate only divides by 1000 above 1e15, so ms/seconds silently fail it),
// and direction is read from senderParticipant.isMe / top-level isMe / fromMe /
// status starting "OUTGOING" (any one of them). Build 1 carries text only —
// media is labelled (e.g. "[Image]") with no bytes; real media lands later.

type waMsgContent struct {
	Content string `json:"content"`
}

// waMediaContent mirrors the watch's image-message schema. The phone reads
// format/mimeType (to skip video) and mediaID (to request the bytes via
// downloadImage). WhatsApp needs no decryptionKey field — the key lives in the
// stored ImageMessage handle, looked up by mediaID (= the message ID).
type waMediaContent struct {
	Format     string `json:"format"`
	MimeType   string `json:"mimeType"`
	MediaID    string `json:"mediaID"`
	GifLike    *bool  `json:"gifLike,omitempty"`    // VIDEO only: true for GIF-style clips
	PosterData string `json:"posterData,omitempty"` // VIDEO only: base64 JPEG poster
}
type waMessageInfo struct {
	MessageContent waMsgContent    `json:"messageContent"`
	MediaContent   *waMediaContent `json:"mediaContent,omitempty"`
}
type waID struct {
	Number string `json:"number"`
}
type waSender struct {
	IsMe      bool   `json:"isMe"`
	FirstName string `json:"firstName"`
	FullName  string `json:"fullName"`
	ID        waID   `json:"ID"`
}
type waStatus struct {
	Status string `json:"status"`
}

// waReplyTo mirrors the FB bridge's "replyTo" object so the phone's quoted-reply
// resolution treats WhatsApp identically: it carries the quoted message id and the
// phone fills sender/text/isMe from the stored thread.
type waReplyTo struct {
	ID string `json:"id"`
}
type waMessage struct {
	ConversationID    string          `json:"conversationID"`
	MessageID         string          `json:"messageID"`
	Timestamp         string          `json:"timestamp"` // microseconds, as a string
	SenderParticipant waSender        `json:"senderParticipant"`
	MessageStatus     waStatus        `json:"messageStatus"`
	MessageInfo       []waMessageInfo `json:"messageInfo"`
	IsMe              bool            `json:"isMe"`
	FromMe            bool            `json:"fromMe"`
	ReplyTo           *waReplyTo      `json:"replyTo,omitempty"`
	AlbumID           string          `json:"albumID,omitempty"`
}

func (b *Bridge) emitMessage(e *events.Message) {
	// A reaction is not a displayable message — surface it on the source-agnostic
	// REACTION stream and stop, before messagePreview() collapses it to an empty
	// body. A reaction must not bump the chat list or emit a MESSAGE.
	if rm := e.Message.GetReactionMessage(); rm != nil {
		b.emitReaction(e, rm)
		return
	}
	// Keep the conversation list + per-chat history current from live traffic.
	b.upsertChatFromMessage(e)

	body := messagePreview(e)
	if body == "" {
		// Not a real displayable message (reaction, receipt, protocol update).
		return
	}
	mime, _ := imageHandle(e)
	vmime, _ := videoHandle(e)
	amime, _ := audioHandle(e)
	m := &msgEntry{
		ID:        e.Info.ID,
		Text:      body,
		TsMicros:  e.Info.Timestamp.UnixMicro(),
		FromMe:    e.Info.IsFromMe,
		Name:      e.Info.PushName,
		Number:    e.Info.Sender.ToNonAD().User,
		Sender:    e.Info.Sender.String(),
		ImgMime:   mime,
		VidMime:   vmime,
		GifLike:   vmime != "" && videoGifLike(e),
		VidThumb:  videoThumb(e),
		AudMime:   amime,
		AlbumID:   albumID(e),
		ReplyToID: quotedStanzaID(e.Message),
	}
	cv := b.canonicalChatJID(e.Info.Chat).String()
	data, err := json.Marshal(makeWaMessage(cv, m, b.resolveSenderName(cv, m)))
	if err != nil {
		b.emit("EVENT", fmt.Sprintf(`{"goType":"marshal-error","detail":%q}`, err.Error()))
		return
	}
	b.emit("MESSAGE", string(data))
}

// emitReaction surfaces a WhatsApp reaction on the source-agnostic REACTION stream:
// target message from the reaction's Key, emoji from Text (empty => the actor
// removed it), actor = the sender's number. Same event shape the FB bridge emits, so
// the Kotlin side handles both through ReactionStore.handleBridgeEvent.
func (b *Bridge) emitReaction(e *events.Message, rm *waE2E.ReactionMessage) {
	targetId := rm.GetKey().GetID()
	if targetId == "" {
		return
	}
	emoji := rm.GetText()
	op := "upsert"
	if emoji == "" {
		op = "delete"
	}
	data, err := json.Marshal(map[string]any{
		"op":     op,
		"thread": e.Info.Chat.ToNonAD().User,
		"msg":    targetId,
		"actor":  e.Info.Sender.ToNonAD().User,
		"emoji":  emoji,
	})
	if err != nil {
		return
	}
	b.emit("REACTION", string(data))
}

// videoHandle returns the video's mimetype + downloadable handle, or ("", nil).
// The watch streams video itself (the phone decodes the file frame by frame), so
// all this bridge keeps is the handle to fetch the file with.
func videoHandle(e *events.Message) (string, whatsmeow.DownloadableMessage) {
	if e.Message == nil {
		return "", nil
	}
	if v := e.Message.GetVideoMessage(); v != nil {
		mime := v.GetMimetype()
		if mime == "" {
			mime = "video/mp4"
		}
		return mime, v
	}
	return "", nil
}

// videoGifLike reports whether the message is a video WhatsApp sent as a GIF
// (GifPlayback set): a short looping clip rather than a regular video.
func videoGifLike(e *events.Message) bool {
	if e.Message == nil {
		return false
	}
	return e.Message.GetVideoMessage().GetGifPlayback()
}

// videoThumb returns the video's embedded JPEG thumbnail as base64, or "".
func videoThumb(e *events.Message) string {
	if e.Message == nil {
		return ""
	}
	if t := e.Message.GetVideoMessage().GetJPEGThumbnail(); len(t) > 0 {
		return base64.StdEncoding.EncodeToString(t)
	}
	return ""
}

// videoKey is where a video's handle lives in the (shared) media store.
func videoKey(msgID string) string { return "v:" + msgID }

// audioHandle returns the audio's mimetype + downloadable handle, or ("", nil).
// Covers voice notes (PTT) and audio files alike; the phone plays the file itself.
func audioHandle(e *events.Message) (string, whatsmeow.DownloadableMessage) {
	if e.Message == nil {
		return "", nil
	}
	if a := e.Message.GetAudioMessage(); a != nil {
		mime := a.GetMimetype()
		if mime == "" {
			mime = "audio/ogg"
		}
		return mime, a
	}
	return "", nil
}

// albumID returns the album parent's message id when the message is one item of a
// WhatsApp album (MessageContextInfo.MessageAssociation, type MEDIA_ALBUM), else
// "". The album parent itself (AlbumMessage) has no displayable body, so
// messagePreview drops it and it never becomes a row.
func albumID(e *events.Message) string {
	if e.Message == nil {
		return ""
	}
	a := e.Message.GetMessageContextInfo().GetMessageAssociation()
	if a.GetAssociationType() != waE2E.MessageAssociation_MEDIA_ALBUM {
		return ""
	}
	return a.GetParentMessageKey().GetID()
}

// audioKey is where an audio message's handle lives in the (shared) media store.
func audioKey(msgID string) string { return "a:" + msgID }

// imageHandle returns the image's mimetype + downloadable handle, or ("", nil) if
// the message is not a (still) image. Video is NOT an image: it has its own handle
// (videoHandle) and goes to the watch's video player, not the image pipeline.
func imageHandle(e *events.Message) (string, whatsmeow.DownloadableMessage) {
	if e.Message == nil {
		return "", nil
	}
	if img := e.Message.GetImageMessage(); img != nil {
		return img.GetMimetype(), img
	}
	return "", nil
}

// rememberImage stores an image's download handle (the ImageMessage proto,
// marshaled + base64) keyed by message ID, FIFO-capped. In-memory update only;
// callers call saveImages() to persist (batched, off the per-message hot path).
func (b *Bridge) rememberImage(msgID string, dl whatsmeow.DownloadableMessage) {
	if msgID == "" || dl == nil {
		return
	}
	pm, ok := dl.(proto.Message)
	if !ok {
		return
	}
	raw, err := proto.Marshal(pm)
	if err != nil {
		return
	}
	enc := base64.StdEncoding.EncodeToString(raw)
	b.imgMu.Lock()
	defer b.imgMu.Unlock()
	if _, ok := b.imgStore[msgID]; !ok {
		b.imgOrder = append(b.imgOrder, msgID)
		for len(b.imgOrder) > maxStoredImages {
			old := b.imgOrder[0]
			b.imgOrder = b.imgOrder[1:]
			delete(b.imgStore, old)
		}
	}
	b.imgStore[msgID] = enc
}

func (b *Bridge) loadImages() {
	data, err := os.ReadFile(b.imgPath)
	if err != nil {
		return
	}
	m := map[string]string{}
	if json.Unmarshal(data, &m) == nil {
		b.imgMu.Lock()
		b.imgStore = m
		b.imgOrder = make([]string, 0, len(m))
		for k := range m {
			b.imgOrder = append(b.imgOrder, k)
		}
		b.imgMu.Unlock()
	}
}

func (b *Bridge) saveImages() {
	b.imgMu.Lock()
	data, err := json.Marshal(b.imgStore)
	b.imgMu.Unlock()
	if err == nil {
		_ = os.WriteFile(b.imgPath, data, 0600)
	}
}

// makeWaMessage builds one watch-schema message object from a stored msgEntry.
// displayName overrides the sender name shown on the watch (resolved from the
// contact store by the caller); "" leaves the watch to fall back to the number.
func makeWaMessage(convoID string, m *msgEntry, displayName string) waMessage {
	status := "INCOMING_COMPLETE"
	if m.FromMe {
		status = "OUTGOING_DELIVERED"
	}
	info := waMessageInfo{MessageContent: waMsgContent{Content: m.Text}}
	if m.ImgMime != "" {
		// mediaID = the message ID; DownloadImageForWatch looks the handle up by it.
		info.MediaContent = &waMediaContent{
			Format:   "IMAGE",
			MimeType: m.ImgMime,
			MediaID:  m.ID,
		}
	} else if m.VidMime != "" {
		// mediaID = the message ID; DownloadMediaToFile looks the handle up by it.
		gifLike := m.GifLike
		info.MediaContent = &waMediaContent{
			Format:     "VIDEO",
			MimeType:   m.VidMime,
			MediaID:    m.ID,
			GifLike:    &gifLike,
			PosterData: m.VidThumb,
		}
	} else if m.AudMime != "" {
		// mediaID = the message ID; DownloadMediaToFile looks the handle up by it.
		info.MediaContent = &waMediaContent{
			Format:   "AUDIO",
			MimeType: m.AudMime,
			MediaID:  m.ID,
		}
	}
	msg := waMessage{
		ConversationID: convoID,
		MessageID:      m.ID,
		Timestamp:      strconv.FormatInt(m.TsMicros, 10),
		SenderParticipant: waSender{
			IsMe:      m.FromMe,
			FirstName: displayName,
			FullName:  displayName,
			ID:        waID{Number: m.Number},
		},
		MessageStatus: waStatus{Status: status},
		MessageInfo:   []waMessageInfo{info},
		IsMe:          m.FromMe,
		FromMe:        m.FromMe,
	}
	if m.ReplyToID != "" {
		msg.ReplyTo = &waReplyTo{ID: m.ReplyToID}
	}
	if m.AlbumID != "" && (m.ImgMime != "" || m.VidMime != "") {
		msg.AlbumID = m.AlbumID
	}
	return msg
}

// canonicalChatJID maps a 1:1 chat JID to a single stable key so a contact whose
// messages arrive under both their @lid privacy id and their phone JID don't
// split into two separate conversations. A LID 1:1 chat resolves to its phone
// JID (which matches the contact store and older history); groups (@g.us) and
// LIDs we can't resolve are returned unchanged.
func (b *Bridge) canonicalChatJID(jid types.JID) types.JID {
	if jid.Server != "lid" {
		return jid
	}
	if pn, err := b.client.Store.LIDs.GetPNForLID(b.ctx, jid); err == nil && !pn.IsEmpty() {
		return pn.ToNonAD()
	}
	return jid
}

// resolveName returns the saved contact name for a USER jid (from whatsmeow's
// contact store, synced from the phone's address book), or "" if none. For @lid
// privacy JIDs it first resolves the LID to the real phone JID. Groups (@g.us)
// return "" — they have no contact entry (group names are a separate lookup).
func (b *Bridge) resolveName(jid types.JID) string {
	if jid.IsEmpty() || jid.Server == "g.us" {
		return ""
	}
	lookup := jid
	pnUser := ""
	if jid.Server == "lid" {
		// --- RISKY LINE (whatsmeow LID store API). Confirmed working in testing. ---
		if pn, err := b.client.Store.LIDs.GetPNForLID(b.ctx, jid); err == nil && !pn.IsEmpty() {
			lookup = pn
			pnUser = pn.ToNonAD().User
		}
	}
	// 1. Saved address-book contact (name you gave them).
	if ci, err := b.client.Store.Contacts.GetContact(b.ctx, lookup); err == nil && ci.Found {
		if ci.FullName != "" {
			return ci.FullName
		}
		if ci.FirstName != "" {
			return ci.FirstName
		}
		if ci.PushName != "" {
			return ci.PushName
		}
	}
	// 2. Profile/push name (what they set on their own account). Try the chat's
	// own user, then the resolved phone-number user — covers either keying.
	if rn := b.lookupPushName(jid.ToNonAD().User); rn != "" {
		return rn
	}
	if pnUser != "" {
		if rn := b.lookupPushName(pnUser); rn != "" {
			return rn
		}
	}
	return ""
}

// resolveSenderName picks the best display name for a message's sender: our own
// name for outgoing; for incoming, the saved contact name (1:1 → the chat
// partner; group → the participant's number), falling back to the push name.
func (b *Bridge) resolveSenderName(convoID string, m *msgEntry) string {
	if m.FromMe {
		return m.Name
	}
	// Preferred: resolve from the sender's full JID, which keeps @lid vs
	// @s.whatsapp.net intact so a LID privacy ID is mapped through GetPNForLID ->
	// phone number -> saved contact (or push name). This is the fix for group
	// members showing as raw numbers even when they're in your address book.
	if m.Sender != "" {
		if sj, perr := types.ParseJID(m.Sender); perr == nil {
			if rn := b.resolveName(sj); rn != "" {
				return rn
			}
		}
	}
	jid, err := types.ParseJID(convoID)
	if err != nil {
		return m.Name
	}
	if jid.Server == "g.us" {
		// Legacy fallback for messages stored before Sender was captured: the bare
		// Number lost its server, so it could be a LID or a phone — try LID first
		// (which triggers the LID->phone->contact path), then the phone server.
		if m.Number != "" {
			if rn := b.resolveName(types.NewJID(m.Number, "lid")); rn != "" {
				return rn
			}
			if rn := b.resolveName(types.NewJID(m.Number, "s.whatsapp.net")); rn != "" {
				return rn
			}
		}
		return m.Name
	}
	if rn := b.resolveName(jid); rn != "" {
		return rn
	}
	return m.Name
}

// messagePreview extracts displayable text for a message, labelling media. Returns
// "" for non-displayable payloads (reactions, receipts, protocol messages).
func messagePreview(e *events.Message) string {
	// Text: plain Conversation, falling back to ExtendedTextMessage (used for
	// replies, link previews, disappearing chats — see whatsmeow discussion #568).
	body := e.Message.GetConversation()
	if body == "" {
		if x := e.Message.GetExtendedTextMessage(); x != nil {
			body = x.GetText()
		}
	}
	if body == "" {
		switch {
		case e.Message.GetImageMessage() != nil:
			body = captionOr(e.Message.GetImageMessage().GetCaption(), "[Image]")
		case e.Message.GetVideoMessage() != nil:
			body = captionOr(e.Message.GetVideoMessage().GetCaption(), "[Video]")
		case e.Message.GetAudioMessage() != nil:
			body = "[Voice message]"
		case e.Message.GetDocumentMessage() != nil:
			body = "[Document]"
		case e.Message.GetStickerMessage() != nil:
			body = "[Sticker]"
		}
	}
	return body
}

func captionOr(caption, fallback string) string {
	if caption != "" {
		return caption
	}
	return fallback
}

// quotedStanzaID returns the id of the message this one replies to, or "" if it's
// not a reply. The quote lives in the per-content-type ContextInfo (StanzaID); the
// generated getters are nil-safe, so an absent ContextInfo yields "".
func quotedStanzaID(msg *waE2E.Message) string {
	if msg == nil {
		return ""
	}
	var ci *waE2E.ContextInfo
	switch {
	case msg.GetExtendedTextMessage() != nil:
		ci = msg.GetExtendedTextMessage().GetContextInfo()
	case msg.GetImageMessage() != nil:
		ci = msg.GetImageMessage().GetContextInfo()
	case msg.GetVideoMessage() != nil:
		ci = msg.GetVideoMessage().GetContextInfo()
	case msg.GetAudioMessage() != nil:
		ci = msg.GetAudioMessage().GetContextInfo()
	case msg.GetDocumentMessage() != nil:
		ci = msg.GetDocumentMessage().GetContextInfo()
	case msg.GetStickerMessage() != nil:
		ci = msg.GetStickerMessage().GetContextInfo()
	}
	return ci.GetStanzaID()
}

// ---- Conversation list: chat index from HistorySync + live messages ----------

// chatMsgFileName is the per-chat message file name for a JID. base64url keeps it
// filesystem-safe and collision-free (JIDs contain '@', '.', and for LIDs ':').
func chatMsgFileName(jid string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(jid)) + ".json"
}

// loadChats reads the summary index, then loads each chat's messages from its
// per-chat file. Best-effort; empty map on any error. Back-compat: an old-format
// index that still carries inline messages keeps them unless a per-chat file
// overrides (the next save migrates it to the split layout).
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
		if e == nil || e.JID == "" {
			continue
		}
		if msgs := b.loadChatMessages(e.JID); msgs != nil {
			e.Messages = msgs
		}
		b.chats[e.JID] = e
	}
}

// loadChatMessages reads one chat's persisted message slice (nil on any error).
func (b *Bridge) loadChatMessages(jid string) []*msgEntry {
	data, err := os.ReadFile(b.msgsDir + "/" + chatMsgFileName(jid))
	if err != nil {
		return nil
	}
	var msgs []*msgEntry
	if json.Unmarshal(data, &msgs) != nil {
		return nil
	}
	return msgs
}

// saveIndexLocked writes the conversation index — SUMMARY ROWS ONLY (messages
// omitted, they live in per-chat files). Small, so it's cheap to rewrite on every
// live message. Caller MUST hold chatsMu.
func (b *Bridge) saveIndexLocked() {
	list := make([]*chatEntry, 0, len(b.chats))
	for _, e := range b.chats {
		row := *e // shallow copy
		row.Messages = nil
		list = append(list, &row)
	}
	if data, err := json.Marshal(list); err == nil {
		_ = os.WriteFile(b.chatsPath, data, 0600)
	}
}

// saveChatMessagesLocked writes one chat's full message slice to its per-chat
// file. Caller MUST hold chatsMu.
func (b *Bridge) saveChatMessagesLocked(ent *chatEntry) {
	if ent == nil || ent.JID == "" {
		return
	}
	if data, err := json.Marshal(ent.Messages); err == nil {
		_ = os.WriteFile(b.msgsDir+"/"+chatMsgFileName(ent.JID), data, 0600)
	}
}

// deleteChatMessagesLocked removes a chat's per-chat message file (used when a
// chat is merged away). Caller MUST hold chatsMu.
func (b *Bridge) deleteChatMessagesLocked(jid string) {
	if jid == "" {
		return
	}
	_ = os.Remove(b.msgsDir + "/" + chatMsgFileName(jid))
}

// ConsolidateLidChats merges any pre-existing @lid 1:1 chat into its phone-JID
// canonical, repairing conversations that split before canonicalChatJID existed.
// Safe + cheap to call repeatedly (idempotent): once merged there's nothing left
// to do. Self-healing — a LID whose phone mapping only becomes known later gets
// folded in on a subsequent call.
func (b *Bridge) ConsolidateLidChats() {
	go b.consolidateLidChatsInternal()
}

func (b *Bridge) consolidateLidChatsInternal() {
	b.chatsMu.Lock()
	merged := 0
	for key, ent := range b.chats {
		jid, err := types.ParseJID(key)
		if err != nil || jid.Server != "lid" {
			continue
		}
		canon := b.canonicalChatJID(jid)
		canonKey := canon.String()
		if canonKey == key {
			continue // LID -> phone not resolvable yet; leave it alone
		}
		if dst := b.chats[canonKey]; dst != nil {
			for _, m := range ent.Messages {
				dst.addMsg(m)
			}
			dst.sortAndCap()
			if (dst.Name == "" || dst.Name == dst.Number) && ent.Name != "" {
				dst.Name = ent.Name
			}
		} else {
			// No phone-keyed entry yet: just re-key this one in place.
			ent.JID = canonKey
			ent.Number = canon.ToNonAD().User
			b.chats[canonKey] = ent
		}
		// Persist the survivor's merged messages under the canonical key and drop
		// the old LID chat's per-chat file.
		if surv := b.chats[canonKey]; surv != nil {
			b.saveChatMessagesLocked(surv)
		}
		b.deleteChatMessagesLocked(key)
		delete(b.chats, key)
		merged++
	}
	if merged > 0 {
		b.saveIndexLocked()
	}
	b.chatsMu.Unlock()
	if merged > 0 {
		b.emit("DIAG", fmt.Sprintf("WA-MERGE folded %d split LID chat(s) into phone-keyed convos", merged))
		b.emit("CHATS_UPDATED", fmt.Sprintf(`{"merged":%d}`, merged))
	}
}

// loadPushNames restores the persisted profile-name map.
func (b *Bridge) loadPushNames() {
	data, err := os.ReadFile(b.pushPath)
	if err != nil {
		return
	}
	m := map[string]string{}
	if json.Unmarshal(data, &m) == nil {
		b.pushMu.Lock()
		b.pushNames = m
		b.pushMu.Unlock()
	}
}

func (b *Bridge) savePushNames() {
	b.pushMu.Lock()
	data, err := json.Marshal(b.pushNames)
	b.pushMu.Unlock()
	if err == nil {
		_ = os.WriteFile(b.pushPath, data, 0600)
	}
}

// setPushName records a profile name for a number; returns true if it changed.
func (b *Bridge) setPushName(number, name string) bool {
	if number == "" || name == "" {
		return false
	}
	b.pushMu.Lock()
	defer b.pushMu.Unlock()
	if b.pushNames[number] == name {
		return false
	}
	b.pushNames[number] = name
	return true
}

func (b *Bridge) lookupPushName(number string) string {
	if number == "" {
		return ""
	}
	b.pushMu.Lock()
	defer b.pushMu.Unlock()
	return b.pushNames[number]
}

// --- group subjects -------------------------------------------------------

func (b *Bridge) loadGroupNames() {
	data, err := os.ReadFile(b.groupPath)
	if err != nil {
		return
	}
	m := map[string]string{}
	if json.Unmarshal(data, &m) == nil {
		b.groupMu.Lock()
		b.groupNames = m
		b.groupMu.Unlock()
	}
}

func (b *Bridge) saveGroupNames() {
	b.groupMu.Lock()
	data, err := json.Marshal(b.groupNames)
	b.groupMu.Unlock()
	if err == nil {
		_ = os.WriteFile(b.groupPath, data, 0600)
	}
}

func (b *Bridge) lookupGroupName(jidStr string) string {
	b.groupMu.Lock()
	defer b.groupMu.Unlock()
	return b.groupNames[jidStr]
}

// EnsureGroupNames fetches + caches the subject of every known group chat that we
// don't already have a name for. Runs off-thread; emits CHATS_UPDATED if it
// learned any new names so the watch list refreshes. Safe to call on each connect.
func (b *Bridge) EnsureGroupNames() {
	go b.ensureGroupNamesInternal()
}

func (b *Bridge) ensureGroupNamesInternal() {
	b.chatsMu.Lock()
	groups := make([]string, 0)
	for jidStr := range b.chats {
		if j, err := types.ParseJID(jidStr); err == nil && j.Server == "g.us" {
			groups = append(groups, jidStr)
		}
	}
	b.chatsMu.Unlock()

	changed := false
	attempted := 0
	named := 0
	for _, jidStr := range groups {
		if b.lookupGroupName(jidStr) != "" {
			continue
		}
		// Try each group at most once per session (success or failure), so a
		// group whose GetGroupInfo keeps failing — and the repeated CHATS_UPDATED
		// sweeps in general — don't re-hit the network or flood the log. A group
		// first seen AFTER the connect-time sweep is still untried here, so it
		// gets its pass: that's the timing fix for newly-created groups.
		b.groupMu.Lock()
		tried := b.groupTried[jidStr]
		b.groupTried[jidStr] = true
		b.groupMu.Unlock()
		if tried {
			continue
		}
		jid, err := types.ParseJID(jidStr)
		if err != nil {
			b.emit("DIAG", "WA-GROUPS "+jidStr+": bad jid")
			continue
		}
		attempted++
		// --- RISKY (GetGroupInfo signature + gi.Name field, unverified offline).
		// If this won't compile, the error names the right signature/field. ---
		gi, err := b.client.GetGroupInfo(b.ctx, jid)
		if err != nil {
			b.emit("DIAG", fmt.Sprintf("WA-GROUPS %s GetGroupInfo error: %s", jidStr, scrubURLs(err.Error())))
			continue
		}
		if gi == nil || gi.Name == "" {
			b.emit("DIAG", fmt.Sprintf("WA-GROUPS %s: empty subject (gi=%v)", jidStr, gi != nil))
			continue
		}
		b.groupMu.Lock()
		b.groupNames[jidStr] = gi.Name
		b.groupMu.Unlock()
		named++
		b.emit("DIAG", fmt.Sprintf("WA-GROUPS %s named (%d chars)", jidStr, len([]rune(gi.Name))))
		changed = true
	}
	if attempted > 0 {
		b.emit("DIAG", fmt.Sprintf("WA-GROUPS sweep: %d groups, %d newly attempted, %d named",
			len(groups), attempted, named))
	}
	if changed {
		b.saveGroupNames()
		b.emit("CHATS_UPDATED", `{"groups":1}`)
	}
}

// upsertChatFromMessage folds one live message into the chat index.
func (b *Bridge) upsertChatFromMessage(e *events.Message) {
	cj := b.canonicalChatJID(e.Info.Chat)
	jid := cj.String()
	if jid == "" {
		return
	}
	text := messagePreview(e)
	if text == "" {
		return
	}

	b.chatsMu.Lock()
	defer b.chatsMu.Unlock()
	ent := b.chats[jid]
	if ent == nil {
		ent = &chatEntry{JID: jid, Number: cj.ToNonAD().User}
		b.chats[jid] = ent
	}
	// Name: the other party's push name (DMs); never overwrite with our own name.
	if !e.Info.IsFromMe && e.Info.PushName != "" {
		ent.Name = e.Info.PushName
		if b.setPushName(e.Info.Sender.ToNonAD().User, e.Info.PushName) {
			b.savePushNames()
		}
	}
	mime, dl := imageHandle(e)
	if dl != nil {
		b.rememberImage(e.Info.ID, dl)
		b.saveImages()
	}
	vmime, vdl := videoHandle(e)
	if vdl != nil {
		b.rememberImage(videoKey(e.Info.ID), vdl)
		b.saveImages()
	}
	amime, adl := audioHandle(e)
	if adl != nil {
		b.rememberImage(audioKey(e.Info.ID), adl)
		b.saveImages()
	}
	ent.addMsg(&msgEntry{
		ID:        e.Info.ID,
		Text:      text,
		TsMicros:  e.Info.Timestamp.UnixMicro(),
		FromMe:    e.Info.IsFromMe,
		Name:      e.Info.PushName,
		Number:    e.Info.Sender.ToNonAD().User,
		Sender:    e.Info.Sender.String(),
		ImgMime:   mime,
		VidMime:   vmime,
		GifLike:   vmime != "" && videoGifLike(e),
		VidThumb:  videoThumb(e),
		AudMime:   amime,
		AlbumID:   albumID(e),
		ReplyToID: quotedStanzaID(e.Message),
	})
	ent.sortAndCap()
	if ent.Name == "" {
		ent.Name = ent.Number
	}
	b.saveChatMessagesLocked(ent)
	b.saveIndexLocked()
}

// handleHistorySync folds the post-link history blob into the chat index. This is
// where the EXISTING conversation list comes from — it arrives once after linking.
func (b *Bridge) handleHistorySync(e *events.HistorySync) {
	// Capture the push-names table FIRST (it can arrive even in a sync with no
	// conversations). These are the profile names WhatsApp shows for people you
	// haven't saved as contacts — the names we were missing.
	pnChanged := false
	for _, p := range e.Data.GetPushnames() {
		// --- if these getters don't compile, the error names the right ones ---
		idStr := p.GetID()
		name := p.GetPushname()
		if idStr == "" || name == "" {
			continue
		}
		if j, perr := types.ParseJID(idStr); perr == nil {
			if b.setPushName(j.ToNonAD().User, name) {
				pnChanged = true
			}
		}
	}
	if pnChanged {
		b.savePushNames()
	}

	convs := e.Data.GetConversations()
	if len(convs) == 0 {
		return
	}
	// WA-SYNC accumulators (summarized in one line at the end of this event).
	var histTotalRaw, histMaxRaw, histOver50 int
	changed := 0
	b.chatsMu.Lock()
	for _, conv := range convs {
		jidStr := conv.GetID()
		if jidStr == "" {
			continue
		}
		origJID, err := types.ParseJID(jidStr)
		if err != nil {
			continue
		}
		// Key by the canonical (phone) JID so LID-delivered history folds into the
		// same conversation; parse the messages with the original JID they arrived
		// under so whatsmeow resolves them correctly.
		canonJID := b.canonicalChatJID(origJID)
		key := canonJID.String()
		ent := b.chats[key]
		if ent == nil {
			ent = &chatEntry{JID: key, Number: canonJID.ToNonAD().User}
			b.chats[key] = ent
		}
		rawCount := len(conv.GetMessages()) // WA-SYNC: messages this blob delivered
		for _, hmsg := range conv.GetMessages() {
			pm, err := b.client.ParseWebMessage(origJID, hmsg.GetMessage())
			if err != nil || pm == nil {
				continue
			}
			text := messagePreview(pm)
			if text == "" {
				continue
			}
			if !pm.Info.IsFromMe && pm.Info.PushName != "" {
				ent.Name = pm.Info.PushName
			}
			mime, dl := imageHandle(pm)
			if dl != nil {
				b.rememberImage(pm.Info.ID, dl)
			}
			vmime, vdl := videoHandle(pm)
			if vdl != nil {
				b.rememberImage(videoKey(pm.Info.ID), vdl)
			}
			amime, adl := audioHandle(pm)
			if adl != nil {
				b.rememberImage(audioKey(pm.Info.ID), adl)
			}
			ent.addMsg(&msgEntry{
				ID:       pm.Info.ID,
				Text:     text,
				TsMicros: pm.Info.Timestamp.UnixMicro(),
				FromMe:   pm.Info.IsFromMe,
				Name:     pm.Info.PushName,
				Number:   pm.Info.Sender.ToNonAD().User,
				Sender:   pm.Info.Sender.String(),
				ImgMime:  mime,
				VidMime:  vmime,
				GifLike:  vmime != "" && videoGifLike(pm),
				VidThumb: videoThumb(pm),
				AudMime:  amime,
				AlbumID:  albumID(pm),
			})
		}
		ent.sortAndCap()
		b.saveChatMessagesLocked(ent) // persist this chat's history to its own file
		histTotalRaw += rawCount
		if rawCount > histMaxRaw {
			histMaxRaw = rawCount
		}
		if rawCount > maxMsgsPerChat {
			histOver50++
		}
		if ent.Name == "" {
			ent.Name = ent.Number
		}
		changed++
	}
	// WA-SYNC: one concise line per history-sync event, so we can confirm a FULL
	// sync fired and how deep it reached after a (re-)link. chatsOverCap flags any
	// chat whose delivered history exceeded our per-chat cap.
	b.emit("DIAG", fmt.Sprintf(
		"WA-SYNC type=%s chats=%d totalRaw=%d maxPerChat=%d chatsOverCap=%d",
		e.Data.GetSyncType().String(), changed, histTotalRaw, histMaxRaw, histOver50))
	b.saveIndexLocked()
	b.chatsMu.Unlock()
	b.saveImages() // persist any image handles captured from history (batched)

	if changed > 0 {
		// Tell Kotlin the list grew so it can refresh the watch's conversation view.
		b.emit("CHATS_UPDATED", fmt.Sprintf(`{"count":%d}`, changed))
	}
}

// JSON shapes for the watch's conversation list (matches PebbleListenerService).
type latestMsgJSON struct {
	DisplayContent string `json:"displayContent"`
}
type participantJSON struct {
	IsMe bool `json:"isMe"`
	ID   waID `json:"ID"`
}
type convoJSON struct {
	ConversationID string            `json:"conversationID"`
	Name           string            `json:"name"`
	Type           string            `json:"type"`
	Unread         bool              `json:"unread"`
	LatestMessage  latestMsgJSON     `json:"latestMessage"`
	Participants   []participantJSON `json:"participants"`
}
type convoListJSON struct {
	Conversations []convoJSON `json:"conversations"`
}

// ListConversations returns the chat list as the watch's Google-schema JSON,
// most-recent first, capped at count (0 = all).
func (b *Bridge) ListConversations(count int) (string, error) {
	b.chatsMu.Lock()
	list := make([]*chatEntry, 0, len(b.chats))
	for _, e := range b.chats {
		list = append(list, e)
	}
	b.chatsMu.Unlock()

	sort.Slice(list, func(i, j int) bool { return list[i].TsMicros > list[j].TsMicros })
	if count > 0 && len(list) > count {
		list = list[:count]
	}

	convos := make([]convoJSON, 0, len(list))
	for _, e := range list {
		// Prefer the saved contact name (1:1 chats) or the group subject (groups);
		// fall back to the stored name (push name / number).
		name := e.Name
		if jid, perr := types.ParseJID(e.JID); perr == nil {
			if jid.Server == "g.us" {
				if gn := b.lookupGroupName(e.JID); gn != "" {
					name = gn
				}
			} else if rn := b.resolveName(jid); rn != "" {
				name = rn
			}
		}
		convos = append(convos, convoJSON{
			ConversationID: e.JID,
			Name:           name,
			Type:           "WA",
			Unread:         false,
			LatestMessage:  latestMsgJSON{DisplayContent: e.Snippet},
			Participants:   []participantJSON{{IsMe: false, ID: waID{Number: e.Number}}},
		})
	}
	data, err := json.Marshal(convoListJSON{Conversations: convos})
	if err != nil {
		return "", err
	}
	return string(data), nil
}

// waCursor is the backfill pagination anchor: the (timestamp, id) of a message,
// so same-second WhatsApp timestamps page deterministically. Carried in the fetch
// response and passed back on the next (older) fetch.
type waCursor struct {
	BeforeTs string `json:"beforeTs"`
	BeforeID string `json:"beforeId"`
}

// msgListWithCursorJSON is the fetch response: a page of messages plus the anchor
// for the next-older page. Cursor is OMITTED when the page reached the oldest
// stored message (=> phone marks the thread exhausted; clean stop).
type msgListWithCursorJSON struct {
	Messages []waMessage `json:"messages"`
	Cursor   *waCursor   `json:"cursor,omitempty"`
}

// olderThan reports whether m sorts strictly before the (ts,id) anchor, matching
// sortAndCap's (TsMicros, ID) ordering.
func olderThan(m *msgEntry, ts int64, id string) bool {
	if m.TsMicros != ts {
		return m.TsMicros < ts
	}
	return m.ID < id
}

// FetchMessages returns a page of a chat's stored history as the watch's
// Google-schema JSON ({"messages":[...],"cursor":{...}}), oldest-first.
//   - cursor == ""  -> the newest `count` messages (initial thread open).
//   - cursor set    -> the `count` messages immediately OLDER than the cursor
//     anchor (backfill), so the watch can scroll further back.
//
// The response "cursor" points at the oldest message in THIS page and is omitted
// once the page reaches the oldest stored message — that's the clean stop that
// tells the phone to set backfillExhausted. WhatsApp history is bounded (whatever
// the last (full) sync delivered), so this pages a fixed local store; there are
// no network calls here and no runaway.
func (b *Bridge) FetchMessages(convoID string, count int, cursor string) (string, error) {
	b.chatsMu.Lock()
	var msgs []*msgEntry
	if ent := b.chats[convoID]; ent != nil {
		msgs = make([]*msgEntry, len(ent.Messages))
		copy(msgs, ent.Messages) // ascending by (ts,id) from sortAndCap
	}
	b.chatsMu.Unlock()

	var sel []*msgEntry
	if cursor == "" {
		// Initial open: newest `count`.
		if count > 0 && len(msgs) > count {
			sel = msgs[len(msgs)-count:]
		} else {
			sel = msgs
		}
	} else {
		var c waCursor
		if err := json.Unmarshal([]byte(cursor), &c); err != nil {
			return "", fmt.Errorf("bad cursor: %w", err)
		}
		beforeTs, _ := strconv.ParseInt(c.BeforeTs, 10, 64)
		// Older-than-anchor messages form a prefix of the ascending slice.
		hi := 0
		for hi < len(msgs) && olderThan(msgs[hi], beforeTs, c.BeforeID) {
			hi++
		}
		older := msgs[:hi]
		if count > 0 && len(older) > count {
			sel = older[len(older)-count:] // newest `count` of the older set
		} else {
			sel = older
		}
	}

	out := make([]waMessage, 0, len(sel))
	for _, m := range sel {
		out = append(out, makeWaMessage(convoID, m, b.resolveSenderName(convoID, m)))
	}
	resp := msgListWithCursorJSON{Messages: out}
	// Emit a cursor only if there is older history beyond this page.
	if len(sel) > 0 && len(msgs) > 0 {
		oldest := sel[0]
		globalOldest := msgs[0]
		if oldest.TsMicros != globalOldest.TsMicros || oldest.ID != globalOldest.ID {
			resp.Cursor = &waCursor{
				BeforeTs: strconv.FormatInt(oldest.TsMicros, 10),
				BeforeID: oldest.ID,
			}
		}
	}
	data, err := json.Marshal(resp)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

// SendMessage sends a plain-text message to the thread identified by convoID,
// which is the chat JID string — the same value FetchMessages/ListConversations
// are keyed by, so sending back to it lands in the same conversation it was read
// from. whatsmeow echoes our own sent message back as an events.Message with
// IsFromMe set, so it reaches the watch through the normal live path; we do NOT
// optimistically insert it here. Returns {"messageId":"..."} on success; a hard
// failure returns an error (gomobile surfaces it as a thrown exception to Kotlin).
//
// NOTE on @lid chats: convoID may be a LID JID (e.g. "...@lid"). We send to it
// as-is because that's the address the conversation lives under. If a particular
// LID chat rejects the send on this whatsmeow version, resolve it to the phone
// JID first via b.client.Store.LIDs.GetPNForLID before sending.
func (b *Bridge) SendMessage(convoID, text string) (string, error) {
	if b.client == nil {
		return "", fmt.Errorf("whatsapp not connected")
	}
	to, err := types.ParseJID(convoID)
	if err != nil {
		return "", fmt.Errorf("bad jid %q: %w", convoID, err)
	}
	resp, err := b.client.SendMessage(b.ctx, to, &waE2E.Message{
		Conversation: proto.String(text),
	})
	if err != nil {
		return "", err
	}
	out, _ := json.Marshal(map[string]string{"messageId": string(resp.ID)})
	return string(out), nil
}

// ListPeople returns the people a new chat can start with (the watch's New chat):
// the contacts saved in the phone's address book that WhatsApp synced, as
// {"people":[{"id","name","detail"}]}. id is the 1:1 chat's JID, the key
// ListConversations and SendMessage use (a LID contact resolved to its phone JID);
// a first send to it creates the chat.
func (b *Bridge) ListPeople() (string, error) {
	if b.client == nil || b.client.Store == nil || b.client.Store.Contacts == nil {
		return "", fmt.Errorf("whatsapp not connected")
	}
	all, err := b.client.Store.Contacts.GetAllContacts(b.ctx)
	if err != nil {
		return "", err
	}
	type person struct {
		ID     string `json:"id"`
		Name   string `json:"name"`
		Detail string `json:"detail,omitempty"`
	}
	own := ""
	if b.client.Store.ID != nil {
		own = b.client.Store.ID.User
	}
	seen := map[string]bool{}
	out := make([]person, 0, len(all))
	for jid, ci := range all {
		if jid.Server != types.DefaultUserServer && jid.Server != types.HiddenUserServer {
			continue
		}
		c := b.canonicalChatJID(jid.ToNonAD())
		if c.Server != types.DefaultUserServer || c.User == "" || c.User == own {
			continue
		}
		name := ci.FullName
		if name == "" {
			name = ci.FirstName
		}
		if name == "" {
			continue // not in the address book: not a contact
		}
		key := c.String()
		if seen[key] {
			continue
		}
		seen[key] = true
		out = append(out, person{ID: key, Name: name, Detail: "+" + c.User})
	}
	bs, _ := json.Marshal(map[string]any{"people": out})
	return string(bs), nil
}

// quotedFor resolves the sender JID and text of the message being replied-to or
// reacted-to, from the bridge's stored history for convoID. The sender is our own
// JID when the quoted message is ours (so the key/context mark it FromMe), the
// participant's JID otherwise. Both are zero/"" when the message isn't in the
// stored window — the reply/reaction still sends, just with a thinner quote.
func (b *Bridge) quotedFor(convoID, msgID string, to types.JID) (types.JID, string) {
	b.chatsMu.Lock()
	defer b.chatsMu.Unlock()
	ent := b.chats[convoID]
	if ent == nil {
		return types.JID{}, ""
	}
	for _, m := range ent.Messages {
		if m == nil || m.ID != msgID {
			continue
		}
		if m.FromMe {
			if b.client != nil && b.client.Store.ID != nil {
				return b.client.Store.ID.ToNonAD(), m.Text
			}
			return types.JID{}, m.Text
		}
		// Their message: in a group the quoted participant must be that sender's
		// ACTUAL JID, preserving @lid vs phone, so WhatsApp can match the quote.
		// Reconstructing it as <number>@s.whatsapp.net mishandles LID senders, and
		// the quote silently detaches — the reply/reaction sends as a plain message
		// (it only looks attached on our optimistic local insert). In a 1:1 the
		// sender is the chat itself.
		if to.Server == types.GroupServer {
			if m.Sender != "" {
				if sj, perr := types.ParseJID(m.Sender); perr == nil && !sj.IsEmpty() {
					return sj, m.Text
				}
			}
			if m.Number != "" {
				return types.NewJID(m.Number, types.DefaultUserServer), m.Text
			}
		}
		return to.ToNonAD(), m.Text
	}
	return types.JID{}, ""
}

// SendReply sends text as a native WhatsApp reply quoting targetMsgID. It builds an
// ExtendedTextMessage whose ContextInfo links the quoted message (StanzaID +
// participant + a copy of its text for the recipient to render). Like SendMessage,
// whatsmeow won't loop our own send back, so the adapter optimistically inserts it.
func (b *Bridge) SendReply(convoID, targetMsgID, text string) (string, error) {
	if b.client == nil {
		return "", fmt.Errorf("whatsapp not connected")
	}
	to, err := types.ParseJID(convoID)
	if err != nil {
		return "", fmt.Errorf("bad jid %q: %w", convoID, err)
	}
	sender, quotedText := b.quotedFor(convoID, targetMsgID, to)
	ci := &waE2E.ContextInfo{StanzaID: proto.String(targetMsgID)}
	if !sender.IsEmpty() {
		ci.Participant = proto.String(sender.String())
	}
	if quotedText != "" {
		ci.QuotedMessage = &waE2E.Message{Conversation: proto.String(quotedText)}
	}
	resp, err := b.client.SendMessage(b.ctx, to, &waE2E.Message{
		ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text:        proto.String(text),
			ContextInfo: ci,
		},
	})
	if err != nil {
		return "", err
	}
	out, _ := json.Marshal(map[string]string{"messageId": string(resp.ID)})
	return string(out), nil
}

// SendReaction adds the emoji reaction to targetMsgID (whatsmeow's BuildReaction
// builds the reaction message; an empty emoji would remove it). targetFromMe tells
// us whether the reacted message is ours, used to resolve the reaction key's sender
// when the message isn't in our stored window.
func (b *Bridge) SendReaction(convoID, targetMsgID, emoji string, targetFromMe bool) (string, error) {
	if b.client == nil {
		return "", fmt.Errorf("whatsapp not connected")
	}
	to, err := types.ParseJID(convoID)
	if err != nil {
		return "", fmt.Errorf("bad jid %q: %w", convoID, err)
	}
	sender, _ := b.quotedFor(convoID, targetMsgID, to)
	if sender.IsEmpty() && !targetFromMe {
		sender = to.ToNonAD() // not in store, but known to be theirs (1:1)
	}
	react := b.client.BuildReaction(to, sender, targetMsgID, emoji)
	resp, err := b.client.SendMessage(b.ctx, to, react)
	if err != nil {
		return "", err
	}
	out, _ := json.Marshal(map[string]string{"messageId": string(resp.ID)})
	return string(out), nil
}

// MarkThreadRead marks a thread read on WhatsApp up through msgID. WhatsApp read
// state is a per-chat high-water mark, so sending a read receipt for the newest
// message clears the chat's unread state (on the phone and other linked devices).
// whatsmeow needs the message's SENDER in group chats; we resolve it via the same
// quotedFor lookup the reaction/reply paths use. In a 1:1 the sender is the chat
// itself (an empty sender is fine — whatsmeow only requires it for groups). The
// read-at time is now (when the user actually viewed it), not the message's own
// timestamp. Mirrors SendReaction's shape/return.
func (b *Bridge) MarkThreadRead(convoID, msgID string) (string, error) {
	if b.client == nil {
		return "", fmt.Errorf("whatsapp not connected")
	}
	if msgID == "" {
		return "", fmt.Errorf("no message id")
	}
	to, err := types.ParseJID(convoID)
	if err != nil {
		return "", fmt.Errorf("bad jid %q: %w", convoID, err)
	}
	// Resolve the sender of msgID (required for groups). quotedFor returns the
	// sender JID for a known message, or empty if it isn't cached.
	sender, _ := b.quotedFor(convoID, msgID, to)
	if to.Server == types.GroupServer && sender.IsEmpty() {
		// Can't send a valid group read receipt without naming the sender; skip
		// rather than fire a malformed one. Self-heals when the message re-caches.
		return "", fmt.Errorf("no sender for group message %q", msgID)
	}
	if err := b.client.MarkRead(
		b.ctx,
		[]types.MessageID{types.MessageID(msgID)},
		time.Now(),
		to,
		sender,
	); err != nil {
		return "", fmt.Errorf("MarkRead: %w", err)
	}
	out, _ := json.Marshal(map[string]string{"convoId": convoID, "messageId": msgID})
	return string(out), nil
}

// urlRe matches web addresses; scrubURLs keeps only their host, so media links
// (which carry access tokens) never reach the log or the phone.
var urlRe = regexp.MustCompile(`(https?://[^/\s"']+)[^\s"']*`)

func scrubURLs(s string) string { return urlRe.ReplaceAllString(s, "$1/…") }

// errJSON wraps an error message in the {"error":...} shape the phone's image
// path recognizes (it logs + aborts the transfer instead of hanging the watch).
func errJSON(msg string) string {
	b, _ := json.Marshal(map[string]string{"error": scrubURLs(msg)})
	return string(b)
}

// DownloadImageForWatch fetches+decrypts the image whose handle we stored under
// msgID, then runs it through the SHARED watchimage encoder (scale + 4bpp
// palettize) so WhatsApp images render identically to Google's. maxBytes>0 is the
// enhanced-view heap budget; maxBytes==0 is the small chat-preview box.
//
// Returns the watch image JSON {"w","h","pixels","fmt","palette"}, or an
// {"error":...} object the phone handles gracefully (never a hung transfer).
func (b *Bridge) DownloadImageForWatch(msgID string, maxW, maxH, maxBytes, quality int) (string, error) {
	b.imgMu.Lock()
	enc := b.imgStore[msgID]
	b.imgMu.Unlock()
	if enc == "" {
		return errJSON("no stored image handle for " + msgID), nil
	}
	rawProto, err := base64.StdEncoding.DecodeString(enc)
	if err != nil {
		return errJSON("bad stored image: " + err.Error()), nil
	}
	img := &waE2E.ImageMessage{}
	if err := proto.Unmarshal(rawProto, img); err != nil {
		return errJSON("unmarshal image: " + err.Error()), nil
	}
	raw, err := b.client.Download(b.ctx, img)
	if err != nil {
		return errJSON("download: " + err.Error()), nil
	}
	out, err := watchimage.EncodeImageForWatch(raw, maxW, maxH, maxBytes, quality)
	if err != nil {
		return errJSON("encode: " + err.Error()), nil
	}
	return out, nil
}

// maxVideoBytes refuses anything absurd before pulling it into memory: whatsmeow
// downloads (and decrypts) the whole file in one go. Applies to video and audio.
const maxVideoBytes = 64 * 1024 * 1024

// mediaDiag emits a DIAG explaining why DownloadMediaToFile returned -1.
func (b *Bridge) mediaDiag(kind, msgID, why string) {
	b.emit("DIAG", fmt.Sprintf(`{"media":%q,"kind":%q,"id":%q}`, scrubURLs(why), kind, msgID))
}

// DownloadMediaToFile fetches and decrypts a video or audio message to path, for
// the phone's video streamer and voice-note player. msgID is the message ID (the
// mediaID the watch was given); the handle is looked up under the video key, then
// the audio key. Writes the whole playable file or nothing; returns the byte
// count, or -1 on any failure (with a DIAG saying why).
func (b *Bridge) DownloadMediaToFile(msgID, path string) int64 {
	kind := "video"
	b.imgMu.Lock()
	enc := b.imgStore[videoKey(msgID)]
	if enc == "" {
		kind = "audio"
		enc = b.imgStore[audioKey(msgID)]
	}
	b.imgMu.Unlock()
	if enc == "" {
		b.mediaDiag("none", msgID, "no stored handle")
		return -1
	}
	rawProto, err := base64.StdEncoding.DecodeString(enc)
	if err != nil {
		b.mediaDiag(kind, msgID, "bad stored handle: "+err.Error())
		return -1
	}
	var dl whatsmeow.DownloadableMessage
	var size uint64
	if kind == "video" {
		v := &waE2E.VideoMessage{}
		if err := proto.Unmarshal(rawProto, v); err != nil {
			b.mediaDiag(kind, msgID, "unmarshal: "+err.Error())
			return -1
		}
		dl, size = v, v.GetFileLength()
	} else {
		a := &waE2E.AudioMessage{}
		if err := proto.Unmarshal(rawProto, a); err != nil {
			b.mediaDiag(kind, msgID, "unmarshal: "+err.Error())
			return -1
		}
		dl, size = a, a.GetFileLength()
	}
	if size > maxVideoBytes {
		b.mediaDiag(kind, msgID, "too large")
		return -1
	}
	raw, err := b.client.Download(b.ctx, dl)
	if err != nil {
		b.mediaDiag(kind, msgID, "download failed: "+err.Error())
		return -1
	}
	if len(raw) == 0 || len(raw) > maxVideoBytes {
		b.mediaDiag(kind, msgID, fmt.Sprintf("bad size %d", len(raw)))
		return -1
	}
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		_ = os.Remove(path)
		b.mediaDiag(kind, msgID, "write: "+err.Error())
		return -1
	}
	return int64(len(raw))
}

// it just connects. Otherwise it sets up QR pairing FIRST (required before
// Connect), connects, and streams the rotating QR codes to the sink as "QR"
// events; the terminal result arrives as "PAIRED" / "PAIR_TIMEOUT" / "PAIR_ERROR".
// Returns quickly — whatsmeow runs its read loop in the background. Keep off the
// main thread anyway (the initial socket connect blocks briefly).
func (b *Bridge) Connect() error {
	if b.client.Store.ID != nil {
		// Already paired — restore session. Never block here on the version lookup.
		b.syncWAVersion(0)
		return b.client.Connect()
	}
	return b.startPairingFlow()
}

// freshClientIfDeleted swaps in a new client on a fresh, unpaired device when the
// current device record was deleted. A server-side unlink (removing this linked
// device from the phone) makes whatsmeow emit LoggedOut and delete the device, and
// it refuses to reuse a deleted device ("invalid use of deleted device"). Only
// runs while unpaired and disconnected, so nothing else is using the old client.
func (b *Bridge) freshClientIfDeleted() {
	if b.client.Store == nil || !b.client.Store.Deleted {
		return
	}
	b.client.Disconnect()
	client := whatsmeow.NewClient(b.container.NewDevice(), waLog.Noop)
	client.AddEventHandler(b.handleEvent)
	b.client = client
	b.emit("DIAG", "WA-PAIR previous device was deleted; pairing on a fresh one")
}

// startPairingFlow obtains a fresh QR channel (must be done BEFORE Connect and
// while disconnected), connects, and forwards QR/terminal events to Kotlin. Used
// for initial pairing and to re-establish the pairing socket if it closed — the
// socket has a ~160s lifetime and a failed PairPhone attempt can leave it down.
func (b *Bridge) startPairingFlow() error {
	b.freshClientIfDeleted()
	b.syncWAVersion(6 * time.Second)
	qrChan, err := b.client.GetQRChannel(b.ctx)
	if err != nil {
		return fmt.Errorf("get qr channel: %w", err)
	}
	if err := b.client.Connect(); err != nil {
		return fmt.Errorf("connect: %w", err)
	}
	// The channel emits a fresh "code" every ~20s until paired, then one terminal
	// event. Range it off the caller's thread and forward to Kotlin. The goroutine
	// exits when the channel closes (pairing done/timed out/socket closed), so a
	// later reconnect starts exactly one new goroutine.
	go func() {
		for item := range qrChan {
			switch item.Event {
			case "code":
				b.emit("QR", fmt.Sprintf(`{"code":%q}`, item.Code))
			case "success":
				b.emit("PAIRED", `{"reason":"qr_success"}`)
			case "timeout":
				b.emit("PAIR_TIMEOUT", `{"reason":"qr_timeout"}`)
			default:
				// err-unexpected-state / err-client-outdated / err-scanned-without-multidevice / error
				b.emit("PAIR_ERROR", fmt.Sprintf(`{"event":%q}`, item.Event))
			}
		}
	}()
	return nil
}

// RequestPairCode generates an 8-character linking code for the given phone
// number (full international format, digits only — non-digits are stripped, and
// numbers starting with 0 or shorter than 7 digits are rejected). The user types
// this code into WhatsApp on their primary phone (Linked devices -> Link a device
// -> "Link with phone number instead"). The client MUST already be connecting
// with the pairing flow live before calling this — callers connect first and wait
// for the first QR event (readiness) before requesting the code. QR codes keep
// generating in parallel; they can be ignored in code-pairing mode.
func (b *Bridge) RequestPairCode(phone string) (string, error) {
	if b.client == nil {
		return "", fmt.Errorf("no client")
	}
	// The pairing websocket has a ~160s lifetime and a prior failed attempt (e.g.
	// a bad number) can leave it closed — which is the "websocket not available"
	// error. Re-establish the pairing flow on demand so any retry just works.
	if !b.client.IsConnected() {
		b.client.Disconnect() // ensure fully down before GetQRChannel + Connect
		if err := b.startPairingFlow(); err != nil {
			return "", fmt.Errorf("reconnect for pairing: %w", err)
		}
		time.Sleep(1500 * time.Millisecond) // let the socket come up before the IQ
	}
	// ctx-first signature (current whatsmeow); display name must be "Browser (OS)".
	return b.client.PairPhone(b.ctx, phone, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
}

// Disconnect stops the read loop and cancels in-flight pairing. The store file is
// left intact so the session restores on the next NewBridge + Connect.
func (b *Bridge) Disconnect() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.cancel()
	b.client.Disconnect()
}

// IsLoggedIn reports whether the client is connected AND authenticated right now.
func (b *Bridge) IsLoggedIn() bool {
	return b.client.IsLoggedIn()
}

// IsPaired reports whether a device session exists in the store (i.e. pairing has
// completed at least once), regardless of whether we're currently connected.
// This is the milestone-zero persistence check: pair once, restart, expect true.
func (b *Bridge) IsPaired() bool {
	return b.client.Store.ID != nil
}

// SelfNumber returns the logged-in account's phone number (digits with country
// code, no device/agent suffix), or "" if no device session exists yet. Sourced
// from the linked-device JID; used for the account-row label.
func (b *Bridge) SelfNumber() string {
	if b.client == nil || b.client.Store == nil || b.client.Store.ID == nil {
		return ""
	}
	return b.client.Store.ID.ToNonAD().User
}

// ---- Contact and group photos ----------------------------------------------

// avatarEntry is what we know about one chat's photo: its WhatsApp picture id
// (changes whenever the picture changes), or None when there is no photo or it is
// hidden from us. At is when it was last confirmed.
type avatarEntry struct {
	ID   string `json:"id,omitempty"`
	None bool   `json:"n,omitempty"`
	At   int64  `json:"at"`
}

const (
	avatarFresh = 24 * time.Hour   // re-check a known photo id after this
	avatarRetry = 10 * time.Minute // after a failed lookup, wait this long
	avatarGap   = 500 * time.Millisecond
)

var errWANotConnected = errors.New("whatsapp not connected")

func (b *Bridge) loadAvatars() {
	data, err := os.ReadFile(b.avatarPath)
	if err != nil {
		return
	}
	m := map[string]*avatarEntry{}
	if json.Unmarshal(data, &m) == nil {
		b.avatarMu.Lock()
		for k, v := range m {
			if v != nil {
				b.avatars[k] = v
			}
		}
		b.avatarMu.Unlock()
	}
}

func (b *Bridge) saveAvatars() {
	b.avatarMu.Lock()
	data, err := json.Marshal(b.avatars)
	b.avatarMu.Unlock()
	if err == nil {
		_ = os.WriteFile(b.avatarPath, data, 0600)
	}
}

// avatarKey maps a conversation id to the JID to query and the key photos are
// stored under (the same canonical key the chat list uses).
func (b *Bridge) avatarKey(convoID string) (types.JID, string, bool) {
	jid, err := types.ParseJID(convoID)
	if err != nil || jid.IsEmpty() {
		return types.JID{}, "", false
	}
	jid = b.canonicalChatJID(jid.ToNonAD())
	return jid, jid.String(), true
}

func avatarIDFor(key string, e *avatarEntry) string {
	if e == nil || e.None || e.ID == "" {
		return ""
	}
	return key + "|" + e.ID
}

// setAvatar records a lookup result; reports whether the chat's photo id changed.
func (b *Bridge) setAvatar(key, id string) bool {
	b.avatarMu.Lock()
	defer b.avatarMu.Unlock()
	old := b.avatars[key]
	b.avatars[key] = &avatarEntry{ID: id, None: id == "", At: time.Now().Unix()}
	delete(b.avatarFailed, key)
	return old == nil || old.ID != id || old.None != (id == "")
}

// lookupAvatar asks WhatsApp for a chat's current photo id. No photo, or one
// hidden from us, is recorded as none; anything else is an error (recorded so the
// chat isn't asked again for avatarRetry).
func (b *Bridge) lookupAvatar(jid types.JID, key string) (bool, error) {
	if b.client == nil || !b.client.IsLoggedIn() {
		return false, errWANotConnected
	}
	ctx, cancel := context.WithTimeout(b.ctx, 15*time.Second)
	defer cancel()
	info, err := b.client.GetProfilePictureInfo(ctx, jid, &whatsmeow.GetProfilePictureParams{Preview: true})
	if errors.Is(err, whatsmeow.ErrProfilePictureNotSet) || errors.Is(err, whatsmeow.ErrProfilePictureUnauthorized) {
		return b.setAvatar(key, ""), nil
	}
	if err == nil && (info == nil || info.ID == "") {
		err = errors.New("no picture in the answer")
	}
	if err != nil {
		b.avatarMu.Lock()
		b.avatarFailed[key] = time.Now()
		b.avatarMu.Unlock()
		return false, err
	}
	return b.setAvatar(key, info.ID), nil
}

// queueAvatar asks the background worker to look a chat's photo up, unless it is
// already queued or failed recently.
func (b *Bridge) queueAvatar(key string) {
	b.avatarMu.Lock()
	if b.avatarPending[key] {
		b.avatarMu.Unlock()
		return
	}
	if t, ok := b.avatarFailed[key]; ok && time.Since(t) < avatarRetry {
		b.avatarMu.Unlock()
		return
	}
	b.avatarPending[key] = true
	b.avatarMu.Unlock()
	select {
	case b.avatarQueue <- key:
	default:
		b.avatarMu.Lock()
		delete(b.avatarPending, key)
		b.avatarMu.Unlock()
	}
}

// avatarWorker looks queued chats up one at a time, spaced out. When the queue
// empties after learning a new or changed photo id, it saves and tells Kotlin the
// list changed, so the next list read carries the photos.
func (b *Bridge) avatarWorker() {
	for {
		select {
		case <-b.ctx.Done():
			return
		case key := <-b.avatarQueue:
			if jid, err := types.ParseJID(key); err == nil {
				if changed, _ := b.lookupAvatar(jid, key); changed {
					b.avatarMu.Lock()
					b.avatarLearned = true
					b.avatarMu.Unlock()
				}
			}
			b.avatarMu.Lock()
			delete(b.avatarPending, key)
			flush := len(b.avatarQueue) == 0 && b.avatarLearned
			if flush {
				b.avatarLearned = false
			}
			b.avatarMu.Unlock()
			if flush {
				b.saveAvatars()
				b.emit("CHATS_UPDATED", `{"avatars":1}`)
			}
			select {
			case <-b.ctx.Done():
				return
			case <-time.After(avatarGap):
			}
		}
	}
}

// onPictureChanged applies a live picture change (a contact or group changed or
// removed its photo) straight to the cache.
func (b *Bridge) onPictureChanged(e *events.Picture) {
	_, key, ok := b.avatarKey(e.JID.String())
	if !ok {
		return
	}
	if !e.Remove && e.PictureID == "" {
		b.queueAvatar(key)
		return
	}
	id := e.PictureID
	if e.Remove {
		id = ""
	}
	if b.setAvatar(key, id) {
		b.saveAvatars()
		b.emit("CHATS_UPDATED", `{"avatars":1}`)
	}
}

// AvatarID names a chat's photo for the phone: "<chat jid>|<picture id>", which
// changes whenever the picture does, or "" when there is no photo (or it isn't
// known yet). lookup=false (packing the chat list) never touches the network: it
// answers from the cache and queues a background lookup for unknown or stale
// chats. lookup=true (a notification, off the main thread) may ask WhatsApp now.
func (b *Bridge) AvatarID(convoID string, lookup bool) string {
	jid, key, ok := b.avatarKey(convoID)
	if !ok {
		return ""
	}
	b.avatarMu.Lock()
	e := b.avatars[key]
	fresh := e != nil && time.Since(time.Unix(e.At, 0)) < avatarFresh
	b.avatarMu.Unlock()
	if fresh {
		return avatarIDFor(key, e)
	}
	if !lookup {
		b.queueAvatar(key)
		return avatarIDFor(key, e)
	}
	if changed, err := b.lookupAvatar(jid, key); err == nil {
		if changed {
			b.saveAvatars()
		}
		b.avatarMu.Lock()
		e = b.avatars[key]
		b.avatarMu.Unlock()
	}
	return avatarIDFor(key, e)
}

// AvatarURL returns a fresh download address for the photo an AvatarID named
// (WhatsApp's addresses lapse, so one is never kept). "" means there is no photo
// any more; an error means it can't be had right now (the phone tries again).
func (b *Bridge) AvatarURL(avatarID string) (string, error) {
	i := strings.LastIndex(avatarID, "|")
	if i <= 0 {
		return "", nil
	}
	key := avatarID[:i]
	jid, err := types.ParseJID(key)
	if err != nil {
		return "", nil
	}
	if b.client == nil || !b.client.IsLoggedIn() {
		return "", errWANotConnected
	}
	ctx, cancel := context.WithTimeout(b.ctx, 20*time.Second)
	defer cancel()
	info, err := b.client.GetProfilePictureInfo(ctx, jid, &whatsmeow.GetProfilePictureParams{Preview: true})
	if errors.Is(err, whatsmeow.ErrProfilePictureNotSet) || errors.Is(err, whatsmeow.ErrProfilePictureUnauthorized) {
		if b.setAvatar(key, "") {
			b.saveAvatars()
		}
		return "", nil
	}
	if err != nil {
		return "", err
	}
	if info == nil || info.URL == "" {
		return "", errors.New("no picture address in the answer")
	}
	if info.ID != "" && b.setAvatar(key, info.ID) {
		b.saveAvatars()
	}
	return info.URL, nil
}

// Close disconnects and closes the underlying store DB. Call when tearing the
// bridge down for good (not on a transient disconnect).
func (b *Bridge) Close() {
	b.Disconnect()
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.db != nil {
		_ = b.db.Close()
		b.db = nil
	}
}
