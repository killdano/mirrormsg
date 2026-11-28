// Package telegrambridge is MirrorMsg's native Telegram bridge, built on gotd/td
// (pure-Go MTProto). gotd's client is only alive inside client.Run's callback, so
// we run that callback as a long-lived pump on its own goroutine and marshal every
// operation into it via cmds. Exported methods are synchronous for gomobile; the
// internals (channels, context, peer cache) never cross the boundary. Facebook /
// WhatsApp / Google are untouched.
//
// Login is driven as discrete steps the UI orchestrates:
//
//	SendCode(phone) -> "code-sent"
//	SignIn(code)    -> "authorized" | "password-needed" | "error: ..."
//	SubmitPassword  -> "authorized" | "error: ..."
//
// Read (this milestone): ListConversations(limit) -> the WhatsApp-shaped
// {"conversations":[...]} the watch already renders. FetchMessages is a stub until
// the next milestone. All read/write ops run on the pump goroutine, so the peer
// cache needs no locking.
package telegrambridge

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	crand "crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	_ "image/jpeg"
	"image/png"
	"math/rand"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	// Reuse the EXACT watch image encoder Google/WhatsApp use (scale + 4bpp
	// palettize); we only do the Telegram-specific download, then hand raw bytes
	// to watchimage.EncodeImageForWatch so images render identically.
	"fi.mirrormsg/watchimage"
	"github.com/gotd/td/session"
	"github.com/gotd/td/telegram"
	"github.com/gotd/td/telegram/auth"
	"github.com/gotd/td/telegram/downloader"
	"github.com/gotd/td/tg"
	_ "golang.org/x/image/webp" // sticker pictures are WebP
)

// MirrorMsg's Telegram app credentials (from my.telegram.org). App-identifier
// only — NOT account access; login still requires phone + code + 2FA. Do not
// publish source publicly with these embedded.

// peerRef is the cached addressing for one conversation. Telegram's getHistory
// needs an InputPeer built from the peer id + access_hash, and the access_hash
// only comes back in the getDialogs users/chats vectors — so ListConversations
// stashes it here (keyed by conversationID) for FetchMessages to reuse.
type peerRef struct {
	kind string // "user" | "chat" | "channel"
	id   int64
	hash int64
}

const (
	convoCacheTTL    = 30 * time.Second // serve cached dialogs without refetch within this window
	convoMinInterval = 8 * time.Second  // never poll getDialogs more often than this (FLOOD_WAIT guard)
	folderCacheTTL   = 60 * time.Second // folders change rarely; serve cached within this window
)

// Bridge wraps a gotd client. All access to b.client, b.phone, b.codeHash and
// b.peers happens on the run/pump goroutine (inside Run's callback), so they need
// no locking; only runErr (read from caller goroutines) is mutex-guarded.
type Bridge struct {
	client *telegram.Client
	cmds   chan func(ctx context.Context)
	ready  chan struct{} // closed once connected and pumping
	done   chan struct{} // closed when the run loop exits
	cancel context.CancelFunc

	phone    string             // set by SendCode, read by SignIn (pump goroutine only)
	codeHash string             // from SendCode, needed by SignIn (pump goroutine only)
	peers    map[string]peerRef // conversationID -> addressing (pump goroutine only)

	convoCache     string    // last good ListConversations JSON (pump goroutine only)
	convoCacheAt   time.Time // when convoCache was built
	convoAttemptAt time.Time // last getDialogs attempt (FLOOD_WAIT throttle)

	folderCache    string                      // last good ListFolders JSON (pump goroutine only)
	folderCacheAt  time.Time                   // when folderCache was built
	folderPeers    map[int][]tg.InputPeerClass // folder id -> member peers (for getPeerDialogs)
	folderedConvos map[string]bool             // convoIDs that belong to any folder (excluded from main)

	mu     sync.Mutex
	runErr error
	sink   LiveSink // live-update callback into the host (guarded by mu)

	photos     map[string]photoRef // media base ("c<chan>-<msg>") -> photo download ref (guarded by mu)
	photoOrder []string            // FIFO eviction order for the photo cache

	docs     map[string]docRef // media base ("c<chan>-<msg>") -> video/audio document ref (guarded by mu)
	docOrder []string          // FIFO eviction order for the document cache

	avatars map[string]int64 // conversationID -> its photo's id, learnt from lists and live updates (guarded by mu)
}

// photoRef is enough to re-download a message's photo later. Keyed by the
// conversation-qualified media base: channel message ids are per-channel, so a bare
// message id could hand one channel's picture to another.
type photoRef struct {
	id         int64
	accessHash int64
	fileRef    []byte
	thumbType  string // the chosen PhotoSize.Type for InputPhotoFileLocation
}

const maxStoredPhotos = 300

// docRef is enough to re-download a message's video, audio or sticker document (and
// a video's poster thumbnail) later. Keyed by a conversation-qualified media base,
// because channel message ids are per-channel and would collide across channels.
type docRef struct {
	id         int64
	accessHash int64
	fileRef    []byte
	size       int64
	mime       string
	thumbType  string // largest downloadable thumb (poster); "" = none
}

const (
	maxStoredDocs         = 300
	maxMediaBytes         = 64 << 20
	mediaDownloadTimeout  = 3 * time.Minute
	mediaThreads          = 4
	avatarDownloadTimeout = 30 * time.Second
)

// LiveSink is implemented on the host (Kotlin) side and set via SetLiveSink; the
// bridge calls it to surface live events (new messages, diagnostics). Bound by
// gomobile as a reverse interface.
type LiveSink interface {
	OnEvent(eventType string, jsonPayload string)
}

// SetLiveSink installs the host callback for live updates.
func (b *Bridge) SetLiveSink(s LiveSink) {
	b.mu.Lock()
	b.sink = s
	b.mu.Unlock()
}

// emitEvent delivers a typed event to the host sink (no-op if none set). The sink
// is copied under the lock and called outside it, so a sink that re-enters the
// bridge can't deadlock. Event types mirror the WhatsApp bridge: MESSAGE (a live
// message JSON), REACTION, DIAG.
func (b *Bridge) emitEvent(eventType, jsonPayload string) {
	b.mu.Lock()
	s := b.sink
	b.mu.Unlock()
	if s != nil {
		s.OnEvent(eventType, jsonPayload)
	}
}

// peerToConvoID maps a message's peer to our conversationID scheme.
func peerToConvoID(peer tg.PeerClass) string {
	switch p := peer.(type) {
	case *tg.PeerUser:
		return fmt.Sprintf("tg:user:%d", p.UserID)
	case *tg.PeerChat:
		return fmt.Sprintf("tg:chat:%d", p.ChatID)
	case *tg.PeerChannel:
		return fmt.Sprintf("tg:channel:%d", p.ChannelID)
	default:
		return ""
	}
}

// onUpdateMessage handles a live new-message update: formats it into the same
// watch-shaped JSON as history and emits it as a MESSAGE event, which the host
// routes into LiveRouter (store + notify). Own-sent messages (m.Out) flow too —
// the host's self-sent gate keeps them from buzzing, and they dedup against the
// optimistic echo on message id.
func (b *Bridge) onUpdateMessage(mc tg.MessageClass, e tg.Entities) {
	m, ok := mc.(*tg.Message)
	if !ok {
		return
	}
	convoID := peerToConvoID(m.PeerID)
	if convoID == "" {
		return
	}
	// Learn photos before the message goes out, so its notification can name one.
	b.learnAvatarsFromEntities(e)
	userByID := make(map[int64]*tg.User)
	for id, u := range e.Users {
		userByID[id] = u
	}
	bs, err := json.Marshal(b.buildOutMsg(convoID, m, userByID))
	if err != nil {
		return
	}
	b.emitEvent("MESSAGE", string(bs))
}

// NewTelegramBridge starts the client run loop against the session file at
// sessionPath (e.g. <filesDir>/tg_session.json) and returns immediately. The session
// is the whole login, so it's sealed with sessionKeyB64 (32 bytes, base64, kept in the
// app's Keystore vault); an empty key keeps it plain. Bound by gomobile as
// Telegrambridge.newTelegramBridge.
func NewTelegramBridge(sessionPath string, sessionKeyB64 string) *Bridge {
	b := &Bridge{
		cmds:           make(chan func(context.Context), 8),
		ready:          make(chan struct{}),
		done:           make(chan struct{}),
		peers:          make(map[string]peerRef),
		folderPeers:    make(map[int][]tg.InputPeerClass),
		folderedConvos: make(map[string]bool),
		photos:         make(map[string]photoRef),
		docs:           make(map[string]docRef),
		avatars:        make(map[string]int64),
	}
	ctx, cancel := context.WithCancel(context.Background())
	b.cancel = cancel
	go b.run(ctx, newSessionStore(sessionPath, sessionKeyB64))
	return b
}

// ---- Sealed session file ---------------------------------------------------------
//
// sealedSession stores gotd's session (the auth key, i.e. the login) as
// "MMS1" + nonce + AES-256-GCM ciphertext. A plain session left by an older build is
// read as-is and sealed straight away, so nobody has to log in again. One that won't
// open (key lost) reads as "no session": Telegram then asks for a fresh login.

var sealedMagic = []byte("MMS1")

type sealedSession struct {
	path string
	aead cipher.AEAD
	mu   sync.Mutex
}

func newSessionStore(path, keyB64 string) telegram.SessionStorage {
	key, err := base64.StdEncoding.DecodeString(keyB64)
	if err != nil || len(key) != 32 {
		return &telegram.FileSessionStorage{Path: path}
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return &telegram.FileSessionStorage{Path: path}
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return &telegram.FileSessionStorage{Path: path}
	}
	return &sealedSession{path: path, aead: aead}
}

func (s *sealedSession) LoadSession(_ context.Context) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	data, err := os.ReadFile(s.path)
	if os.IsNotExist(err) {
		return nil, session.ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if !bytes.HasPrefix(data, sealedMagic) {
		if err := s.write(data); err != nil {
			return nil, err
		}
		return data, nil
	}
	ns := s.aead.NonceSize()
	body := data[len(sealedMagic):]
	if len(body) < ns {
		return nil, session.ErrNotFound
	}
	plain, err := s.aead.Open(nil, body[:ns], body[ns:], sealedMagic)
	if err != nil {
		return nil, session.ErrNotFound
	}
	return plain, nil
}

func (s *sealedSession) StoreSession(_ context.Context, data []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.write(data)
}

// write seals data and replaces the file atomically. Caller holds mu.
func (s *sealedSession) write(data []byte) error {
	nonce := make([]byte, s.aead.NonceSize())
	if _, err := crand.Read(nonce); err != nil {
		return err
	}
	out := append(append(append([]byte{}, sealedMagic...), nonce...), s.aead.Seal(nil, nonce, data, sealedMagic)...)
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, out, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func (b *Bridge) run(ctx context.Context, store telegram.SessionStorage) {
	defer close(b.done)
	// Providing an UpdateHandler enables update delivery (gotd disables updates by
	// default). The dispatcher routes typed updates to our handlers.
	disp := tg.NewUpdateDispatcher()
	disp.OnNewMessage(func(ctx context.Context, e tg.Entities, u *tg.UpdateNewMessage) error {
		b.onUpdateMessage(u.Message, e)
		return nil
	})
	disp.OnNewChannelMessage(func(ctx context.Context, e tg.Entities, u *tg.UpdateNewChannelMessage) error {
		b.onUpdateMessage(u.Message, e)
		return nil
	})
	// Edits re-carry the message with its current reactions (Telegram delivers the
	// first reaction, and later reaction changes, as an edit). Routing them through
	// the same path re-applies reactions on the host; the message dedups on id.
	disp.OnEditMessage(func(ctx context.Context, e tg.Entities, u *tg.UpdateEditMessage) error {
		b.onUpdateMessage(u.Message, e)
		return nil
	})
	disp.OnEditChannelMessage(func(ctx context.Context, e tg.Entities, u *tg.UpdateEditChannelMessage) error {
		b.onUpdateMessage(u.Message, e)
		return nil
	})
	client := telegram.NewClient(apiID, apiHash, telegram.Options{
		SessionStorage: store,
		UpdateHandler:  disp,
	})
	b.client = client
	err := client.Run(ctx, func(ctx context.Context) error {
		close(b.ready)
		for {
			select {
			case fn := <-b.cmds:
				fn(ctx)
			case <-ctx.Done():
				return ctx.Err()
			}
		}
	})
	b.mu.Lock()
	b.runErr = err
	b.mu.Unlock()
}

// waitReady blocks until connected+pumping, the run loop exits, or timeout.
func (b *Bridge) waitReady(d time.Duration) bool {
	select {
	case <-b.ready:
		return true
	case <-b.done:
		return false
	case <-time.After(d):
		return false
	}
}

// call marshals fn onto the pump goroutine (where the client is alive) and returns
// its string result, with connection/enqueue/result timeouts turned into
// diagnostic strings so gomobile callers always get an answer.
func (b *Bridge) call(timeout time.Duration, fn func(ctx context.Context) string) string {
	if !b.waitReady(25 * time.Second) {
		if e := b.LastError(); e != "" {
			return "not-connected: " + e
		}
		return "not-connected: timeout"
	}
	res := make(chan string, 1)
	wrapped := func(ctx context.Context) { res <- fn(ctx) }
	select {
	case b.cmds <- wrapped:
	case <-b.done:
		return "not-connected: run loop exited"
	case <-time.After(5 * time.Second):
		return "busy"
	}
	select {
	case s := <-res:
		return s
	case <-time.After(timeout):
		return "timeout"
	}
}

// ConnectStatus reports auth state: "authorized", "unauthorized", "error: ...",
// or a "not-connected: ..."/"timeout" diagnostic. On a fresh install it returns
// "unauthorized" once the run loop has connected to Telegram with no session yet.
func (b *Bridge) ConnectStatus() string {
	return b.call(15*time.Second, func(ctx context.Context) string {
		st, err := b.client.Auth().Status(ctx)
		if err != nil {
			return "error: " + err.Error()
		}
		if st.Authorized {
			return "authorized"
		}
		return "unauthorized"
	})
}

// SendCode requests a login code for phone (international format, e.g.
// "+15551234567"). Returns "code-sent" or "error: ...".
func (b *Bridge) SendCode(phone string) string {
	return b.call(30*time.Second, func(ctx context.Context) string {
		b.phone = phone
		sent, err := b.client.Auth().SendCode(ctx, phone, auth.SendCodeOptions{})
		if err != nil {
			return "error: " + err.Error()
		}
		sc, ok := sent.(*tg.AuthSentCode)
		if !ok {
			return "error: unexpected sent-code type"
		}
		b.codeHash = sc.PhoneCodeHash
		return "code-sent"
	})
}

// SignIn submits the login code. Returns "authorized", "password-needed" (2FA is
// set — call SubmitPassword next), or "error: ...".
func (b *Bridge) SignIn(code string) string {
	return b.call(30*time.Second, func(ctx context.Context) string {
		_, err := b.client.Auth().SignIn(ctx, b.phone, code, b.codeHash)
		if errors.Is(err, auth.ErrPasswordAuthNeeded) {
			return "password-needed"
		}
		if err != nil {
			return "error: " + err.Error()
		}
		return "authorized"
	})
}

// SubmitPassword completes 2FA (cloud password). Returns "authorized" or
// "error: ...".
func (b *Bridge) SubmitPassword(password string) string {
	return b.call(30*time.Second, func(ctx context.Context) string {
		_, err := b.client.Auth().Password(ctx, password)
		if err != nil {
			return "error: " + err.Error()
		}
		return "authorized"
	})
}

// ---- Read: conversations ------------------------------------------------------

// JSON output shapes — must match exactly what the watch renderer + notify gates
// expect (mirrors the Google/WhatsApp bridge output).
type outID struct {
	Number string `json:"number"`
}
type outParticipant struct {
	IsMe bool  `json:"isMe"`
	ID   outID `json:"ID"`
}
type outLatest struct {
	DisplayContent string `json:"displayContent"`
}
type outConvo struct {
	ConversationID string           `json:"conversationID"`
	Name           string           `json:"name"`
	Type           string           `json:"type"`
	Unread         bool             `json:"unread"`
	LatestMessage  outLatest        `json:"latestMessage"`
	Participants   []outParticipant `json:"participants"`
	AvatarID       string           `json:"avatarID,omitempty"` // "<convoID>|<photo id>"; absent = no photo
}
type outConvos struct {
	Conversations []outConvo `json:"conversations"`
	TgError       string     `json:"tgError,omitempty"`
}

func emptyConvos(errMsg string) string {
	bs, _ := json.Marshal(outConvos{Conversations: []outConvo{}, TgError: errMsg})
	return string(bs)
}

// dialogsToConvos converts a {dialogs, messages, chats, users} bundle (from
// getDialogs or getPeerDialogs) into conversation objects, caching each peer's
// access hash along the way. Runs on the pump goroutine.
func (b *Bridge) dialogsToConvos(dialogs []tg.DialogClass, msgs []tg.MessageClass, chats []tg.ChatClass, users []tg.UserClass, excludeFoldered bool) []outConvo {
	userByID := make(map[int64]*tg.User)
	for _, u := range users {
		if uu, ok := u.(*tg.User); ok {
			userByID[uu.ID] = uu
		}
	}
	chatByID := make(map[int64]*tg.Chat)
	channelByID := make(map[int64]*tg.Channel)
	for _, c := range chats {
		switch cc := c.(type) {
		case *tg.Chat:
			chatByID[cc.ID] = cc
		case *tg.Channel:
			channelByID[cc.ID] = cc
		}
	}
	msgByID := make(map[int]*tg.Message)
	for _, m := range msgs {
		if mm, ok := m.(*tg.Message); ok {
			msgByID[mm.ID] = mm
		}
	}

	out := []outConvo{}
	for _, dc := range dialogs {
		d, ok := dc.(*tg.Dialog)
		if !ok {
			continue
		}
		var convoID, name, number string
		var pr peerRef
		var photoID int64
		switch p := d.Peer.(type) {
		case *tg.PeerUser:
			u := userByID[p.UserID]
			if u == nil {
				continue
			}
			hash, _ := u.GetAccessHash()
			convoID = fmt.Sprintf("tg:user:%d", p.UserID)
			name = strings.TrimSpace(u.FirstName + " " + u.LastName)
			number = fmt.Sprintf("%d", p.UserID)
			pr = peerRef{kind: "user", id: p.UserID, hash: hash}
			photoID = userPhotoID(u)
		case *tg.PeerChat:
			convoID = fmt.Sprintf("tg:chat:%d", p.ChatID)
			number = fmt.Sprintf("%d", p.ChatID)
			if c := chatByID[p.ChatID]; c != nil {
				name = c.Title
				photoID = chatPhotoID(c.Photo)
			}
			pr = peerRef{kind: "chat", id: p.ChatID, hash: 0}
		case *tg.PeerChannel:
			convoID = fmt.Sprintf("tg:channel:%d", p.ChannelID)
			number = fmt.Sprintf("%d", p.ChannelID)
			var hash int64
			if c := channelByID[p.ChannelID]; c != nil {
				hash, _ = c.GetAccessHash()
				name = c.Title
				photoID = chatPhotoID(c.Photo)
			}
			pr = peerRef{kind: "channel", id: p.ChannelID, hash: hash}
		default:
			continue
		}
		// A chat that lives in a folder belongs to that folder's sub-account, not
		// the main "Telegram" list.
		if excludeFoldered && b.folderedConvos[convoID] {
			continue
		}
		if name == "" {
			name = number
		}
		b.peers[convoID] = pr

		preview := ""
		if m := msgByID[d.TopMessage]; m != nil {
			preview = m.Message
			if preview == "" {
				if _, ok := m.GetMedia(); ok {
					preview = "[Media]"
				}
			}
		}

		out = append(out, outConvo{
			ConversationID: convoID,
			Name:           name,
			Type:           "TG",
			Unread:         d.UnreadCount > 0,
			LatestMessage:  outLatest{DisplayContent: preview},
			Participants:   []outParticipant{{IsMe: false, ID: outID{Number: number}}},
			AvatarID:       b.learnAvatar(convoID, photoID),
		})
	}
	return out
}

// ---- Contact photos ------------------------------------------------------------
//
// A chat's photo is named "<convoID>|<photo id>": the other person's for a 1:1 chat,
// the group's or channel's own for the rest. The id changes when the photo does.
// Chat rows carry it as avatarID; notifications ask AvatarID, which answers from what
// lists and live updates taught it, with no network call.

func userPhotoID(u *tg.User) int64 {
	if p, ok := u.GetPhoto(); ok {
		if up, ok := p.(*tg.UserProfilePhoto); ok {
			return up.PhotoID
		}
	}
	return 0
}

func chatPhotoID(p tg.ChatPhotoClass) int64 {
	if cp, ok := p.(*tg.ChatPhoto); ok {
		return cp.PhotoID
	}
	return 0
}

// learnAvatar records a chat's current photo (0 = none) and returns its avatar id.
func (b *Bridge) learnAvatar(convoID string, photoID int64) string {
	b.mu.Lock()
	defer b.mu.Unlock()
	if photoID == 0 {
		delete(b.avatars, convoID)
		return ""
	}
	b.avatars[convoID] = photoID
	return fmt.Sprintf("%s|%d", convoID, photoID)
}

// learnAvatarsFromEntities records the photos of the users, groups and channels a live
// update carries. "Min" entities are partial and may leave the photo out, so they are
// skipped rather than allowed to erase a known one.
func (b *Bridge) learnAvatarsFromEntities(e tg.Entities) {
	for id, u := range e.Users {
		if u != nil && !u.Min {
			b.learnAvatar(fmt.Sprintf("tg:user:%d", id), userPhotoID(u))
		}
	}
	for id, c := range e.Chats {
		if c != nil {
			b.learnAvatar(fmt.Sprintf("tg:chat:%d", id), chatPhotoID(c.Photo))
		}
	}
	for id, c := range e.Channels {
		if c != nil && !c.Min {
			b.learnAvatar(fmt.Sprintf("tg:channel:%d", id), chatPhotoID(c.Photo))
		}
	}
}

// AvatarID names a chat's photo for a notification, from what the bridge has already
// seen; "" = no photo known. Never touches the network.
func (b *Bridge) AvatarID(conversationID string) string {
	b.mu.Lock()
	defer b.mu.Unlock()
	if id, ok := b.avatars[conversationID]; ok && id != 0 {
		return fmt.Sprintf("%s|%d", conversationID, id)
	}
	return ""
}

// AvatarBytes downloads the small (160 px) JPEG an avatar id names. It returns an error
// whenever the photo can't be had right now, so the phone tries again instead of
// remembering "no photo". The download runs off the pump.
func (b *Bridge) AvatarBytes(avatarID string) ([]byte, error) {
	i := strings.LastIndex(avatarID, "|")
	if i <= 0 {
		return nil, errors.New("bad avatar id")
	}
	convoID := avatarID[:i]
	photoID, err := strconv.ParseInt(avatarID[i+1:], 10, 64)
	if err != nil || photoID == 0 {
		return nil, errors.New("bad avatar id")
	}
	var peer tg.InputPeerClass
	if r := b.call(10*time.Second, func(ctx context.Context) string {
		p, ok := b.inputPeerFor(convoID)
		if !ok {
			return "unknown conversation (list first)"
		}
		peer = p
		return ""
	}); r != "" {
		return nil, errors.New("avatar: " + r)
	}
	ctx, cancel := context.WithTimeout(context.Background(), avatarDownloadTimeout)
	defer cancel()
	go func() {
		select {
		case <-b.done:
			cancel()
		case <-ctx.Done():
		}
	}()
	loc := &tg.InputPeerPhotoFileLocation{Peer: peer, PhotoID: photoID}
	var buf bytes.Buffer
	if _, err := downloader.NewDownloader().Download(b.client.API(), loc).Stream(ctx, &buf); err != nil {
		return nil, errors.New("avatar download: " + err.Error())
	}
	if buf.Len() == 0 {
		return nil, errors.New("avatar: empty file")
	}
	return buf.Bytes(), nil
}

// ListConversations returns the most-recent `limit` dialogs (DMs, groups, and
// channels) as {"conversations":[...]}, and caches each peer's addressing for
// FetchMessages. Telegram returns dialogs already in most-recent-first order.
func (b *Bridge) ListConversations(limit int) string {
	if limit <= 0 {
		limit = 15
	}
	r := b.call(30*time.Second, func(ctx context.Context) string {
		now := time.Now()
		// Serve the cached dialog list if still fresh: Telegram FLOOD_WAITs getDialogs
		// when polled rapidly, and the watch requests the list very often.
		if b.convoCache != "" && now.Sub(b.convoCacheAt) < convoCacheTTL {
			return b.convoCache
		}
		// Throttle refetch attempts so a burst can't re-trigger FLOOD_WAIT.
		if now.Sub(b.convoAttemptAt) < convoMinInterval {
			if b.convoCache != "" {
				return b.convoCache
			}
			return emptyConvos("throttled")
		}
		b.convoAttemptAt = now
		res, err := b.client.API().MessagesGetDialogs(ctx, &tg.MessagesGetDialogsRequest{
			OffsetPeer: &tg.InputPeerEmpty{},
			Limit:      limit,
		})
		if err != nil {
			// FLOOD_WAIT / transient: keep showing the last good list if we have one.
			if b.convoCache != "" {
				return b.convoCache
			}
			return emptyConvos("getDialogs: " + err.Error())
		}

		var dialogs []tg.DialogClass
		var msgs []tg.MessageClass
		var chats []tg.ChatClass
		var users []tg.UserClass
		switch d := res.(type) {
		case *tg.MessagesDialogs:
			dialogs, msgs, chats, users = d.Dialogs, d.Messages, d.Chats, d.Users
		case *tg.MessagesDialogsSlice:
			dialogs, msgs, chats, users = d.Dialogs, d.Messages, d.Chats, d.Users
		default:
			return emptyConvos("getDialogs: unexpected response type")
		}

		// Make sure the folder membership is current, then drop foldered chats
		// from the main list (they live under their sub-account).
		b.loadFolders(ctx)
		out := outConvos{Conversations: b.dialogsToConvos(dialogs, msgs, chats, users, true)}
		bs, _ := json.Marshal(out)
		js := string(bs)
		b.convoCache = js
		b.convoCacheAt = now
		return js
	})
	// A connection diagnostic from call() isn't JSON — hand the watch an empty list.
	if !strings.HasPrefix(r, "{") {
		return emptyConvos(r)
	}
	return r
}

// ---- Read: messages ----------------------------------------------------------

type outSender struct {
	IsMe      bool   `json:"isMe"`
	FirstName string `json:"firstName"`
	FullName  string `json:"fullName"`
	ID        outID  `json:"ID"`
}
type outStatus struct {
	Status string `json:"status"`
}
type outContent struct {
	Content string `json:"content"`
}
type outMediaContent struct {
	Format        string `json:"format"`
	MimeType      string `json:"mimeType"`
	MediaID       string `json:"mediaID"`
	GifLike       *bool  `json:"gifLike,omitempty"`       // video parts only
	PosterMediaID string `json:"posterMediaID,omitempty"` // video poster, via DownloadImageForWatch
}
type outMsgInfo struct {
	MessageContent outContent       `json:"messageContent"`
	MediaContent   *outMediaContent `json:"mediaContent,omitempty"`
}
type outReplyTo struct {
	ID string `json:"id"`
}
type outReaction struct {
	Emoji string `json:"emoji"`
	Count int    `json:"count"`
}
type outMsg struct {
	ConversationID    string         `json:"conversationID"`
	MessageID         string         `json:"messageID"`
	Timestamp         string         `json:"timestamp"`
	SenderParticipant outSender      `json:"senderParticipant"`
	MessageStatus     outStatus      `json:"messageStatus"`
	MessageInfo       []outMsgInfo   `json:"messageInfo"`
	IsMe              bool           `json:"isMe"`
	FromMe            bool           `json:"fromMe"`
	ReplyTo           *outReplyTo    `json:"replyTo,omitempty"`
	AlbumID           string         `json:"albumID,omitempty"` // Telegram grouped_id, on every item of an album
	Reactions         *[]outReaction `json:"reactions,omitempty"`
}
type outMsgs struct {
	Messages []outMsg `json:"messages"`
	Cursor   string   `json:"cursor,omitempty"` // oldest id in batch; "" = true top
	TgError  string   `json:"tgError,omitempty"`
}

func emptyMsgs(errMsg string) string {
	bs, _ := json.Marshal(outMsgs{Messages: []outMsg{}, TgError: errMsg})
	return string(bs)
}

// FetchMessages returns up to `limit` recent messages for conversationID as
// {"messages":[...]} in oldest-first order (the watch's expectation). Requires the
// peer to be in the cache from a prior ListConversations; getHistory needs the
// access_hash that only ListConversations captured.
// mediaLabel returns a short type placeholder for a message's media ([Photo],
// [Video], [GIF], [Voice], [Sticker], [File], [Location], ...), or "" if the
// message has no media (or is a link preview, whose text already carries the URL).
func mediaLabel(m *tg.Message) string {
	media, ok := m.GetMedia()
	if !ok {
		return ""
	}
	switch md := media.(type) {
	case *tg.MessageMediaPhoto:
		return "[Photo]"
	case *tg.MessageMediaDocument:
		doc, ok := md.Document.(*tg.Document)
		if !ok {
			return "[File]"
		}
		var isVideo, isVoice, isAudio, isSticker, isGif, isRound bool
		for _, a := range doc.Attributes {
			switch at := a.(type) {
			case *tg.DocumentAttributeVideo:
				isVideo = true
				if at.RoundMessage {
					isRound = true
				}
			case *tg.DocumentAttributeAudio:
				if at.Voice {
					isVoice = true
				} else {
					isAudio = true
				}
			case *tg.DocumentAttributeSticker:
				isSticker = true
			case *tg.DocumentAttributeAnimated:
				isGif = true
			}
		}
		switch {
		case isSticker:
			return "[Sticker]"
		case isGif:
			return "[GIF]"
		case isVoice:
			return "[Voice]"
		case isRound:
			return "[Video message]"
		case isVideo:
			return "[Video]"
		case isAudio:
			return "[Audio]"
		default:
			return "[File]"
		}
	case *tg.MessageMediaGeo, *tg.MessageMediaGeoLive, *tg.MessageMediaVenue:
		return "[Location]"
	case *tg.MessageMediaContact:
		return "[Contact]"
	case *tg.MessageMediaPoll:
		return "[Poll]"
	case *tg.MessageMediaGame:
		return "[Game]"
	case *tg.MessageMediaInvoice:
		return "[Invoice]"
	case *tg.MessageMediaDice:
		if md.Emoticon != "" {
			return "[" + md.Emoticon + "]"
		}
		return "[Dice]"
	case *tg.MessageMediaWebPage:
		return "" // link preview — the message text already holds the URL
	default:
		return "[Media]"
	}
}

func largestPhotoSizeType(sizes []tg.PhotoSizeClass) string {
	best := ""
	bestW := -1
	for _, sz := range sizes {
		var typ string
		var w int
		switch ps := sz.(type) {
		case *tg.PhotoSize:
			typ, w = ps.Type, ps.W
		case *tg.PhotoSizeProgressive:
			typ, w = ps.Type, ps.W
		default:
			continue // stripped / cached / empty — not directly downloadable
		}
		if w > bestW {
			bestW = w
			best = typ
		}
	}
	return best
}

// rememberPhoto caches a message photo's download ref by media base (FIFO-capped),
// so DownloadImageForWatch can fetch it later. Guarded by mu since it is called
// from both the pump (FetchMessages) and the update-dispatcher goroutines. Reports
// false when the photo has no downloadable size.
func (b *Bridge) rememberPhoto(base string, mp *tg.MessageMediaPhoto) (photoRef, bool) {
	photo, ok := mp.Photo.(*tg.Photo)
	if !ok {
		return photoRef{}, false
	}
	typ := largestPhotoSizeType(photo.Sizes)
	if typ == "" {
		return photoRef{}, false
	}
	pr := photoRef{
		id:         photo.ID,
		accessHash: photo.AccessHash,
		fileRef:    photo.FileReference,
		thumbType:  typ,
	}
	b.mu.Lock()
	if _, exists := b.photos[base]; !exists {
		b.photoOrder = append(b.photoOrder, base)
		for len(b.photoOrder) > maxStoredPhotos {
			old := b.photoOrder[0]
			b.photoOrder = b.photoOrder[1:]
			delete(b.photos, old)
		}
	}
	b.photos[base] = pr
	b.mu.Unlock()
	return pr, true
}

func (b *Bridge) cachedPhoto(base string) (photoRef, bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	pr, ok := b.photos[base]
	return pr, ok
}

// buildOutMsg formats one Telegram message into the watch-shaped outMsg, shared by
// history fetch and live updates so both look identical. Adds a media-type label
// when there's no text, and a reply-to id when the message quotes another.
func (b *Bridge) buildOutMsg(conversationID string, m *tg.Message, userByID map[int64]*tg.User) outMsg {
	// Photo -> a requestable image (mediaContent); the watch fetches the bytes via
	// DownloadImageForWatch keyed by the chat-scoped "tgi-" id. Other media keeps its
	// text label. rememberPhoto caches the download ref for that later fetch.
	msgID := fmt.Sprintf("%d", m.ID)
	var mc *outMediaContent
	if media, ok := m.GetMedia(); ok {
		if mp, ok := media.(*tg.MessageMediaPhoto); ok {
			if base := mediaBase(conversationID, msgID); base != "" {
				if _, ok := b.rememberPhoto(base, mp); ok {
					mc = &outMediaContent{Format: "IMAGE", MimeType: "image/jpeg", MediaID: photoPrefix + base}
				}
			}
		}
	}
	if mc == nil {
		if d, gifLike, ok := videoDoc(m); ok {
			mc = b.videoContent(conversationID, msgID, d, gifLike)
		} else if d, ok := audioDoc(m); ok {
			mc = b.audioContent(conversationID, msgID, d)
		} else if d, ok := stickerImageDoc(m); ok {
			mc = b.stickerContent(conversationID, msgID, d)
		}
	}
	content := m.Message
	if content == "" {
		switch {
		case mc == nil:
			content = mediaLabel(m)
		case isStickerMsg(m):
			// A sticker shown as a picture or a looping clip carries no text, like a photo.
		case mc.Format == "VIDEO":
			// Keep the type label ([Video] / [GIF] / [Video message]) as the row text.
			if content = mediaLabel(m); content == "" || content == "[File]" {
				content = "[Video]"
			}
		case mc.Format == "AUDIO":
			// Keep the type label ([Voice] / [Audio]) as the row text.
			if content = mediaLabel(m); content == "" || content == "[File]" {
				content = "[Audio]"
			}
		}
	}
	isMe := m.Out
	status := "INCOMING_COMPLETE"
	if isMe {
		status = "OUTGOING_DELIVERED"
	}

	var senderNum, senderFirst, senderFull string
	if !isMe {
		var sid int64
		if fid, ok := m.GetFromID(); ok {
			if pu, ok := fid.(*tg.PeerUser); ok {
				sid = pu.UserID
			}
		}
		// DM fallback: the sender is the peer user itself.
		if sid == 0 && strings.HasPrefix(conversationID, "tg:user:") {
			if id, err := strconv.ParseInt(strings.TrimPrefix(conversationID, "tg:user:"), 10, 64); err == nil {
				sid = id
			}
		}
		if sid != 0 {
			senderNum = fmt.Sprintf("%d", sid)
			if u := userByID[sid]; u != nil {
				senderFirst = u.FirstName
				senderFull = strings.TrimSpace(u.FirstName + " " + u.LastName)
			}
		}
	}

	om := outMsg{
		ConversationID: conversationID,
		MessageID:      fmt.Sprintf("%d", m.ID),
		Timestamp:      fmt.Sprintf("%d", int64(m.Date)*1000000),
		SenderParticipant: outSender{
			IsMe:      isMe,
			FirstName: senderFirst,
			FullName:  senderFull,
			ID:        outID{Number: senderNum},
		},
		MessageStatus: outStatus{Status: status},
		MessageInfo:   []outMsgInfo{{MessageContent: outContent{Content: content}, MediaContent: mc}},
		IsMe:          isMe,
		FromMe:        isMe,
	}
	// Album: the messages of a media group share grouped_id. Only pictures and videos
	// are album items on the phone, so files and music sent as a group (Telegram
	// groups those too) stay separate rows.
	if gid, ok := m.GetGroupedID(); ok && gid != 0 && mc != nil && (mc.Format == "IMAGE" || mc.Format == "VIDEO") {
		om.AlbumID = fmt.Sprintf("%d", gid)
	}
	if rh, ok := m.GetReplyTo(); ok {
		if rhm, ok := rh.(*tg.MessageReplyHeader); ok {
			if rid, ok := rhm.GetReplyToMsgID(); ok && rid != 0 {
				om.ReplyTo = &outReplyTo{ID: fmt.Sprintf("%d", rid)}
			}
		}
	}
	// Reactions: when the message carries the reactions flag, emit the current set
	// (emoji + count) — even if empty, so a removal-to-none reaches the store and
	// clears the badge. Custom (document-id) reactions are skipped (no Pebble glyph).
	// Absent flag => field omitted => the store is left untouched for this message.
	if mr, ok := m.GetReactions(); ok {
		rs := []outReaction{}
		for _, rc := range mr.Results {
			if re, ok := rc.Reaction.(*tg.ReactionEmoji); ok && re.Emoticon != "" {
				rs = append(rs, outReaction{Emoji: re.Emoticon, Count: rc.Count})
			}
		}
		om.Reactions = &rs
	}
	return om
}

// inputPeerFor builds the MTProto input peer for a cached conversation id.
func (b *Bridge) inputPeerFor(conversationID string) (tg.InputPeerClass, bool) {
	pr, ok := b.peers[conversationID]
	if !ok {
		return nil, false
	}
	switch pr.kind {
	case "user":
		return &tg.InputPeerUser{UserID: pr.id, AccessHash: pr.hash}, true
	case "chat":
		return &tg.InputPeerChat{ChatID: pr.id}, true
	case "channel":
		return &tg.InputPeerChannel{ChannelID: pr.id, AccessHash: pr.hash}, true
	}
	return nil, false
}

// extractSentID pulls the new message id from a send response's updates —
// UpdateMessageID (channels/groups) or UpdateShortSentMessage (DMs).
func extractSentID(upd tg.UpdatesClass) int {
	switch u := upd.(type) {
	case *tg.UpdateShortSentMessage:
		return u.ID
	case *tg.Updates:
		for _, up := range u.Updates {
			if mid, ok := up.(*tg.UpdateMessageID); ok {
				return mid.ID
			}
		}
	case *tg.UpdatesCombined:
		for _, up := range u.Updates {
			if mid, ok := up.(*tg.UpdateMessageID); ok {
				return mid.ID
			}
		}
	}
	return 0
}

func errJSON(msg string) string {
	bs, _ := json.Marshal(map[string]string{"error": msg})
	return string(bs)
}
func okID(id int) string {
	bs, _ := json.Marshal(map[string]string{"messageId": fmt.Sprintf("%d", id)})
	return string(bs)
}

// SendMessage sends plain text to a conversation. Returns {"messageId":"<id>"}.
func (b *Bridge) SendMessage(conversationID string, text string) string {
	return b.sendText(conversationID, text, 0)
}

// SendReply sends text quoting replyToMsgID within the conversation.
func (b *Bridge) SendReply(conversationID string, replyToMsgID int, text string) string {
	return b.sendText(conversationID, text, replyToMsgID)
}

func (b *Bridge) sendText(conversationID, text string, replyTo int) string {
	return b.call(30*time.Second, func(ctx context.Context) string {
		peer, ok := b.inputPeerFor(conversationID)
		if !ok {
			return errJSON("unknown conversation (list first)")
		}
		req := &tg.MessagesSendMessageRequest{
			Peer:     peer,
			Message:  text,
			RandomID: rand.Int63(),
		}
		if replyTo != 0 {
			req.SetReplyTo(&tg.InputReplyToMessage{ReplyToMsgID: replyTo})
		}
		upd, err := b.client.API().MessagesSendMessage(ctx, req)
		if err != nil {
			return errJSON("sendMessage: " + err.Error())
		}
		return okID(extractSentID(upd))
	})
}

// SendReaction sets (or, with an empty emoji, clears) our reaction on msgID.
func (b *Bridge) SendReaction(conversationID string, msgID int, emoji string) string {
	return b.call(30*time.Second, func(ctx context.Context) string {
		peer, ok := b.inputPeerFor(conversationID)
		if !ok {
			return errJSON("unknown conversation (list first)")
		}
		req := &tg.MessagesSendReactionRequest{
			Peer:  peer,
			MsgID: msgID,
		}
		if emoji != "" {
			req.Reaction = []tg.ReactionClass{&tg.ReactionEmoji{Emoticon: emoji}}
		}
		if _, err := b.client.API().MessagesSendReaction(ctx, req); err != nil {
			// MESSAGE_NOT_MODIFIED means the message already carries exactly this
			// reaction (e.g. re-tapping the same emoji, or our optimistic reaction
			// already synced back). That's a no-op, not a failure — the desired
			// state is achieved. Report success so the caller does NOT fall back to
			// posting the emoji as a plain message.
			if strings.Contains(err.Error(), "MESSAGE_NOT_MODIFIED") {
				return okID(msgID)
			}
			return errJSON("sendReaction: " + err.Error())
		}
		return okID(msgID)
	})
}

// MarkThreadRead marks a conversation read up through msgID. Telegram splits this
// by peer type: channels/supergroups use channels.readHistory (needs an
// InputChannel), while user DMs and basic groups use messages.readHistory (an
// InputPeer). Read state is a per-dialog high-water mark (MaxID = read up to and
// including that id), so marking the newest message clears the dialog's unread
// count. msgID arrives as a string (the interface shape); Telegram ids are ints,
// so we parse and no-op safely on a non-numeric id.
func (b *Bridge) MarkThreadRead(conversationID string, msgID string) string {
	maxID, perr := strconv.Atoi(msgID)
	if perr != nil || maxID <= 0 {
		return errJSON("bad message id " + msgID)
	}
	return b.call(30*time.Second, func(ctx context.Context) string {
		pr, ok := b.peers[conversationID]
		if !ok {
			return errJSON("unknown conversation (list first)")
		}
		if pr.kind == "channel" {
			// channels/supergroups: channels.readHistory with an InputChannel.
			_, err := b.client.API().ChannelsReadHistory(ctx, &tg.ChannelsReadHistoryRequest{
				Channel: &tg.InputChannel{ChannelID: pr.id, AccessHash: pr.hash},
				MaxID:   maxID,
			})
			if err != nil {
				return errJSON("channelsReadHistory: " + err.Error())
			}
			return okID(maxID)
		}
		// user DMs and basic groups: messages.readHistory with an InputPeer.
		peer, pok := b.inputPeerFor(conversationID)
		if !pok {
			return errJSON("unknown conversation (list first)")
		}
		_, err := b.client.API().MessagesReadHistory(ctx, &tg.MessagesReadHistoryRequest{
			Peer:  peer,
			MaxID: maxID,
		})
		if err != nil {
			return errJSON("messagesReadHistory: " + err.Error())
		}
		return okID(maxID)
	})
}

// DownloadImageForWatch fetches a photo ("tgi-") or a video poster ("tgp-"), then
// runs it through the SHARED watchimage encoder (scale + 4bpp palettize) so Telegram
// images render identically to Google/WhatsApp. Returns {"w","h","pixels","fmt",
// "palette"} or an {"error":...} object the phone handles gracefully. maxBytes>0 is
// the enhanced-view heap budget; maxBytes==0 is the small chat-preview box. A bare
// message id (stored before ids were chat-scoped) is refused rather than guessed:
// it cannot say which chat it belongs to.
func (b *Bridge) DownloadImageForWatch(mediaID string, maxW, maxH, maxBytes, quality int) string {
	switch {
	case strings.HasPrefix(mediaID, photoPrefix):
		return b.encodeForWatch(b.photoLocation(strings.TrimPrefix(mediaID, photoPrefix)), false, maxW, maxH, maxBytes, quality)
	case strings.HasPrefix(mediaID, posterPrefix):
		return b.encodeForWatch(b.posterLocation(strings.TrimPrefix(mediaID, posterPrefix)), false, maxW, maxH, maxBytes, quality)
	case strings.HasPrefix(mediaID, stickerPrefix):
		return b.encodeForWatch(b.stickerLocation(strings.TrimPrefix(mediaID, stickerPrefix)), true, maxW, maxH, maxBytes, quality)
	}
	return errJSON("unscoped image id " + mediaID)
}

// fileLocator yields the file location for one image, from the cache or (fresh, or
// on a cache miss) by re-fetching its message. Pump goroutine only.
type fileLocator func(ctx context.Context, fresh bool) (tg.InputFileLocationClass, string)

// encodeForWatch downloads one small image on the pump and encodes it for the watch.
// A failed download retries once with a freshly resolved location, since Telegram
// file references expire. toPNG first re-encodes the image as PNG (stickers are WebP,
// which the watch encoder doesn't read).
func (b *Bridge) encodeForWatch(locate fileLocator, toPNG bool, maxW, maxH, maxBytes, quality int) string {
	return b.call(60*time.Second, func(ctx context.Context) string {
		var lastErr string
		for attempt := 0; attempt < 2; attempt++ {
			loc, reason := locate(ctx, attempt > 0)
			if reason != "" {
				return errJSON(reason)
			}
			var buf bytes.Buffer
			if _, err := downloader.NewDownloader().Download(b.client.API(), loc).Stream(ctx, &buf); err != nil {
				lastErr = err.Error()
				continue
			}
			raw := buf.Bytes()
			if toPNG {
				var err error
				if raw, err = reencodePNG(raw); err != nil {
					return errJSON("decode: " + err.Error())
				}
			}
			out, err := watchimage.EncodeImageForWatch(raw, maxW, maxH, maxBytes, quality)
			if err != nil {
				return errJSON("encode: " + err.Error())
			}
			return out
		}
		return errJSON("download: " + lastErr)
	})
}

// reencodePNG decodes a WebP, PNG or JPEG image and writes it back out as PNG.
func reencodePNG(raw []byte) ([]byte, error) {
	img, _, err := image.Decode(bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	var out bytes.Buffer
	if err := png.Encode(&out, img); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}

// stickerLocation names a sticker's picture: the whole file for a still sticker, the
// built-in preview for an animated (.tgs) one.
func (b *Bridge) stickerLocation(base string) fileLocator {
	return func(ctx context.Context, fresh bool) (tg.InputFileLocationClass, string) {
		ref, ok := b.cachedDoc(base)
		if !ok || fresh {
			var reason string
			if ref, reason = b.resolveDoc(ctx, base); reason != "" {
				return nil, "sticker: " + reason
			}
		}
		loc := &tg.InputDocumentFileLocation{ID: ref.id, AccessHash: ref.accessHash, FileReference: ref.fileRef}
		if !strings.HasPrefix(ref.mime, "image/") {
			if ref.thumbType == "" {
				return nil, "sticker: no preview"
			}
			loc.ThumbSize = ref.thumbType
		}
		return loc, ""
	}
}

func (b *Bridge) photoLocation(base string) fileLocator {
	return func(ctx context.Context, fresh bool) (tg.InputFileLocationClass, string) {
		pr, ok := b.cachedPhoto(base)
		if !ok || fresh {
			var reason string
			if pr, reason = b.resolvePhoto(ctx, base); reason != "" {
				return nil, "photo: " + reason
			}
		}
		return &tg.InputPhotoFileLocation{
			ID:            pr.id,
			AccessHash:    pr.accessHash,
			FileReference: pr.fileRef,
			ThumbSize:     pr.thumbType,
		}, ""
	}
}

func (b *Bridge) posterLocation(base string) fileLocator {
	return func(ctx context.Context, fresh bool) (tg.InputFileLocationClass, string) {
		ref, ok := b.cachedDoc(base)
		if !ok || fresh {
			var reason string
			if ref, reason = b.resolveDoc(ctx, base); reason != "" {
				return nil, "poster: " + reason
			}
		}
		if ref.thumbType == "" {
			return nil, "poster: no thumbnail"
		}
		return &tg.InputDocumentFileLocation{
			ID:            ref.id,
			AccessHash:    ref.accessHash,
			FileReference: ref.fileRef,
			ThumbSize:     ref.thumbType,
		}, ""
	}
}

// ---- Media ids -----------------------------------------------------------------
//
// Every media id is "<prefix><base>", where <base> is conversation-qualified and
// filename-safe ("c2102748773-4521" = channel 2102748773, message 4521). Channel
// message ids are per-channel, so the chat must be part of the id. The base also
// lets the bridge re-resolve the message itself: that survives an app restart
// (history loaded from disk) and renews expired file references.
//
//	tgi-<base>  photo         -> DownloadImageForWatch
//	tgp-<base>  video poster  -> DownloadImageForWatch
//	tgv-<base>  video file    -> DownloadMediaToFile
//	tga-<base>  audio file    -> DownloadMediaToFile (voice notes and audio files)
//	tgk-<base>  sticker       -> DownloadImageForWatch (still, or an animated one's preview)

const (
	photoPrefix   = "tgi-"
	posterPrefix  = "tgp-"
	videoPrefix   = "tgv-"
	audioPrefix   = "tga-"
	stickerPrefix = "tgk-"
)

// videoDoc returns m's document when it is a playable video (regular, round, GIF
// animation, a video sent as a file, or a video sticker), plus whether it is a
// GIF-style clip. Video stickers (short WebM clips) loop like GIFs; other stickers
// are not videos.
func videoDoc(m *tg.Message) (*tg.Document, bool, bool) {
	media, ok := m.GetMedia()
	if !ok {
		return nil, false, false
	}
	md, ok := media.(*tg.MessageMediaDocument)
	if !ok {
		return nil, false, false
	}
	d, ok := md.Document.(*tg.Document)
	if !ok {
		return nil, false, false
	}
	var isVideo, isSticker, isAnim bool
	for _, a := range d.Attributes {
		switch a.(type) {
		case *tg.DocumentAttributeVideo:
			isVideo = true
		case *tg.DocumentAttributeSticker:
			isSticker = true
		case *tg.DocumentAttributeAnimated:
			isAnim = true
		}
	}
	isVideo = isVideo || strings.HasPrefix(d.MimeType, "video/")
	if isSticker {
		if isVideo {
			return d, true, true
		}
		return nil, false, false
	}
	if !isVideo {
		return nil, false, false
	}
	return d, isAnim, true
}

// stickerDocOf returns m's document when it is a sticker of any kind.
func stickerDocOf(m *tg.Message) (*tg.Document, bool) {
	media, ok := m.GetMedia()
	if !ok {
		return nil, false
	}
	md, ok := media.(*tg.MessageMediaDocument)
	if !ok {
		return nil, false
	}
	d, ok := md.Document.(*tg.Document)
	if !ok {
		return nil, false
	}
	for _, a := range d.Attributes {
		if _, ok := a.(*tg.DocumentAttributeSticker); ok {
			return d, true
		}
	}
	return nil, false
}

func isStickerMsg(m *tg.Message) bool {
	_, ok := stickerDocOf(m)
	return ok
}

// stickerImageDoc returns m's sticker when it shows as a picture: a still sticker
// (WebP), or an animated one (.tgs) that carries a still preview. Video stickers are
// clips (videoDoc). A .tgs without a preview stays a "[Sticker]" text row.
func stickerImageDoc(m *tg.Message) (*tg.Document, bool) {
	d, ok := stickerDocOf(m)
	if !ok || strings.HasPrefix(d.MimeType, "video/") {
		return nil, false
	}
	for _, a := range d.Attributes {
		if _, ok := a.(*tg.DocumentAttributeVideo); ok {
			return nil, false
		}
	}
	if strings.HasPrefix(d.MimeType, "image/") || largestPhotoSizeType(d.Thumbs) != "" {
		return d, true
	}
	return nil, false
}

// audioDoc returns m's document when it is audio: a voice note or an audio file.
// Videos (round video messages included) and stickers are not audio.
func audioDoc(m *tg.Message) (*tg.Document, bool) {
	if _, _, isVideo := videoDoc(m); isVideo {
		return nil, false
	}
	media, ok := m.GetMedia()
	if !ok {
		return nil, false
	}
	md, ok := media.(*tg.MessageMediaDocument)
	if !ok {
		return nil, false
	}
	d, ok := md.Document.(*tg.Document)
	if !ok {
		return nil, false
	}
	isAudio := strings.HasPrefix(d.MimeType, "audio/")
	for _, a := range d.Attributes {
		switch a.(type) {
		case *tg.DocumentAttributeAudio:
			isAudio = true
		case *tg.DocumentAttributeSticker:
			return nil, false
		}
	}
	if !isAudio {
		return nil, false
	}
	return d, true
}

// mediaBase builds the conversation-qualified media base for a message.
func mediaBase(conversationID, msgID string) string {
	switch {
	case strings.HasPrefix(conversationID, "tg:user:"):
		return "u" + strings.TrimPrefix(conversationID, "tg:user:") + "-" + msgID
	case strings.HasPrefix(conversationID, "tg:chat:"):
		return "g" + strings.TrimPrefix(conversationID, "tg:chat:") + "-" + msgID
	case strings.HasPrefix(conversationID, "tg:channel:"):
		return "c" + strings.TrimPrefix(conversationID, "tg:channel:") + "-" + msgID
	}
	return ""
}

// parseMediaBase reverses mediaBase into the conversation id and message id.
func parseMediaBase(base string) (string, int, bool) {
	parts := strings.SplitN(base, "-", 2)
	if len(parts) != 2 || len(parts[0]) < 2 {
		return "", 0, false
	}
	msgID, err := strconv.Atoi(parts[1])
	if err != nil {
		return "", 0, false
	}
	var prefix string
	switch parts[0][0] {
	case 'u':
		prefix = "tg:user:"
	case 'g':
		prefix = "tg:chat:"
	case 'c':
		prefix = "tg:channel:"
	default:
		return "", 0, false
	}
	return prefix + parts[0][1:], msgID, true
}

func docRefOf(d *tg.Document) docRef {
	return docRef{
		id:         d.ID,
		accessHash: d.AccessHash,
		fileRef:    d.FileReference,
		size:       int64(d.Size),
		mime:       d.MimeType,
		thumbType:  largestPhotoSizeType(d.Thumbs),
	}
}

// rememberDoc caches a video or audio document ref (FIFO-capped). Called from the
// pump and the update-dispatcher goroutines, so guarded by mu.
func (b *Bridge) rememberDoc(base string, d *tg.Document) docRef {
	ref := docRefOf(d)
	b.mu.Lock()
	if _, exists := b.docs[base]; !exists {
		b.docOrder = append(b.docOrder, base)
		for len(b.docOrder) > maxStoredDocs {
			old := b.docOrder[0]
			b.docOrder = b.docOrder[1:]
			delete(b.docs, old)
		}
	}
	b.docs[base] = ref
	b.mu.Unlock()
	return ref
}

func (b *Bridge) cachedDoc(base string) (docRef, bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	ref, ok := b.docs[base]
	return ref, ok
}

// videoContent builds the VIDEO media part and caches its download ref. GIF-style
// clips get an animated preview on the phone, so only real videos carry a poster.
func (b *Bridge) videoContent(conversationID, msgID string, d *tg.Document, gifLike bool) *outMediaContent {
	base := mediaBase(conversationID, msgID)
	if base == "" {
		return nil
	}
	ref := b.rememberDoc(base, d)
	mime := d.MimeType
	if !strings.HasPrefix(mime, "video/") {
		mime = "video/mp4"
	}
	gl := gifLike
	mc := &outMediaContent{Format: "VIDEO", MimeType: mime, MediaID: videoPrefix + base, GifLike: &gl}
	if !gifLike && ref.thumbType != "" {
		mc.PosterMediaID = posterPrefix + base
	}
	return mc
}

// stickerContent builds the IMAGE part for a sticker shown as a picture and caches
// its download ref.
func (b *Bridge) stickerContent(conversationID, msgID string, d *tg.Document) *outMediaContent {
	base := mediaBase(conversationID, msgID)
	if base == "" {
		return nil
	}
	b.rememberDoc(base, d)
	return &outMediaContent{Format: "IMAGE", MimeType: "image/png", MediaID: stickerPrefix + base}
}

// audioContent builds the AUDIO media part and caches its download ref. The phone
// reads the file itself for the length and waveform, so no poster or duration.
func (b *Bridge) audioContent(conversationID, msgID string, d *tg.Document) *outMediaContent {
	base := mediaBase(conversationID, msgID)
	if base == "" {
		return nil
	}
	b.rememberDoc(base, d)
	mime := d.MimeType
	if mime == "" {
		mime = "audio/ogg"
	}
	return &outMediaContent{Format: "AUDIO", MimeType: mime, MediaID: audioPrefix + base}
}

// fetchMessageByBase re-fetches the single message behind base. Pump goroutine only
// (reads the peer cache). Returns "" on success, else a reason.
func (b *Bridge) fetchMessageByBase(ctx context.Context, base string) (*tg.Message, string) {
	convoID, msgID, ok := parseMediaBase(base)
	if !ok {
		return nil, "bad media id"
	}
	ids := []tg.InputMessageClass{&tg.InputMessageID{ID: msgID}}
	var res tg.MessagesMessagesClass
	var err error
	if strings.HasPrefix(convoID, "tg:channel:") {
		pr, ok := b.peers[convoID]
		if !ok {
			return nil, "unknown channel (list first)"
		}
		res, err = b.client.API().ChannelsGetMessages(ctx, &tg.ChannelsGetMessagesRequest{
			Channel: &tg.InputChannel{ChannelID: pr.id, AccessHash: pr.hash},
			ID:      ids,
		})
	} else {
		res, err = b.client.API().MessagesGetMessages(ctx, ids)
	}
	if err != nil {
		return nil, "getMessages: " + err.Error()
	}
	var msgs []tg.MessageClass
	switch r := res.(type) {
	case *tg.MessagesMessages:
		msgs = r.Messages
	case *tg.MessagesMessagesSlice:
		msgs = r.Messages
	case *tg.MessagesChannelMessages:
		msgs = r.Messages
	}
	for _, mc := range msgs {
		if m, ok := mc.(*tg.Message); ok && m.ID == msgID {
			return m, ""
		}
	}
	return nil, "message not found"
}

// resolveDoc re-fetches the message behind base and caches a fresh video or audio
// ref.
func (b *Bridge) resolveDoc(ctx context.Context, base string) (docRef, string) {
	m, reason := b.fetchMessageByBase(ctx, base)
	if reason != "" {
		return docRef{}, reason
	}
	if d, _, ok := videoDoc(m); ok {
		return b.rememberDoc(base, d), ""
	}
	if d, ok := audioDoc(m); ok {
		return b.rememberDoc(base, d), ""
	}
	if d, ok := stickerImageDoc(m); ok {
		return b.rememberDoc(base, d), ""
	}
	return docRef{}, "message has no video, audio or sticker"
}

// resolvePhoto re-fetches the message behind base and caches a fresh photo ref.
func (b *Bridge) resolvePhoto(ctx context.Context, base string) (photoRef, string) {
	m, reason := b.fetchMessageByBase(ctx, base)
	if reason != "" {
		return photoRef{}, reason
	}
	if media, ok := m.GetMedia(); ok {
		if mp, ok := media.(*tg.MessageMediaPhoto); ok {
			if pr, ok := b.rememberPhoto(base, mp); ok {
				return pr, ""
			}
		}
	}
	return photoRef{}, "message has no photo"
}

// resolveDocOnPump runs resolveDoc on the pump goroutine.
func (b *Bridge) resolveDocOnPump(base string) (docRef, string) {
	var ref docRef
	r := b.call(20*time.Second, func(ctx context.Context) string {
		var reason string
		ref, reason = b.resolveDoc(ctx, base)
		return reason
	})
	if r != "" {
		return docRef{}, r
	}
	return ref, ""
}

// downloadDocToFile writes the whole document to path with parallel part fetches.
// Runs OFF the pump so a large file never stalls lists, fetches or sends; the gotd
// client is safe for concurrent use while its run loop is up.
func (b *Bridge) downloadDocToFile(ref docRef, path string) (int64, error) {
	if !b.waitReady(25 * time.Second) {
		return 0, errors.New("not connected")
	}
	f, err := os.Create(path)
	if err != nil {
		return 0, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), mediaDownloadTimeout)
	defer cancel()
	go func() {
		select {
		case <-b.done:
			cancel()
		case <-ctx.Done():
		}
	}()
	loc := &tg.InputDocumentFileLocation{ID: ref.id, AccessHash: ref.accessHash, FileReference: ref.fileRef}
	_, derr := downloader.NewDownloader().Download(b.client.API(), loc).WithThreads(mediaThreads).Parallel(ctx, f)
	cerr := f.Close()
	if derr != nil {
		return 0, derr
	}
	if cerr != nil {
		return 0, cerr
	}
	st, err := os.Stat(path)
	if err != nil {
		return 0, err
	}
	if st.Size() == 0 {
		return 0, errors.New("empty file")
	}
	return st.Size(), nil
}

// DownloadMediaToFile writes the complete video ("tgv-") or audio ("tga-") file for
// mediaID to path and returns its byte count, or -1 on any failure (never a partial
// file). A failed download re-resolves the message once, since Telegram file
// references expire.
func (b *Bridge) DownloadMediaToFile(mediaID, path string) int64 {
	var base string
	switch {
	case strings.HasPrefix(mediaID, videoPrefix):
		base = strings.TrimPrefix(mediaID, videoPrefix)
	case strings.HasPrefix(mediaID, audioPrefix):
		base = strings.TrimPrefix(mediaID, audioPrefix)
	default:
		b.mediaDiag("bad media id", mediaID)
		return -1
	}
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		ref, ok := b.cachedDoc(base)
		if !ok || attempt > 0 {
			var reason string
			if ref, reason = b.resolveDocOnPump(base); reason != "" {
				os.Remove(path)
				b.mediaDiag(reason, mediaID)
				return -1
			}
		}
		if ref.size > maxMediaBytes {
			os.Remove(path)
			b.mediaDiag("too large", mediaID)
			return -1
		}
		n, err := b.downloadDocToFile(ref, path)
		if err == nil {
			return n
		}
		lastErr = err
	}
	os.Remove(path)
	b.mediaDiag("download failed: "+lastErr.Error(), mediaID)
	return -1
}

func (b *Bridge) mediaDiag(reason, mediaID string) {
	bs, _ := json.Marshal(map[string]string{"tgMedia": reason, "id": mediaID})
	b.emitEvent("DIAG", string(bs))
}

func (b *Bridge) FetchMessages(conversationID string, limit int, offsetID int) string {
	// offsetID paginates into older history: 0 = newest page; a real message id =
	// the batch strictly older than it (MTProto getHistory offset_id semantics).
	// The old 25-cap is gone — infinite backfill needs full pages. Telegram's
	// getHistory tops out at 100 per call, so clamp there (default 25 for a bad limit).
	if limit <= 0 {
		limit = 25
	}
	if limit > 100 {
		limit = 100
	}
	r := b.call(30*time.Second, func(ctx context.Context) string {
		peer, ok := b.inputPeerFor(conversationID)
		if !ok {
			return emptyMsgs("unknown conversation (list first): " + conversationID)
		}

		res, err := b.client.API().MessagesGetHistory(ctx, &tg.MessagesGetHistoryRequest{
			Peer:     peer,
			OffsetID: offsetID, // 0 = newest; else the anchor to page strictly older than
			Limit:    limit,
		})
		if err != nil {
			return emptyMsgs("getHistory: " + err.Error())
		}

		var msgs []tg.MessageClass
		var users []tg.UserClass
		switch m := res.(type) {
		case *tg.MessagesMessages:
			msgs, users = m.Messages, m.Users
		case *tg.MessagesMessagesSlice:
			msgs, users = m.Messages, m.Users
		case *tg.MessagesChannelMessages:
			msgs, users = m.Messages, m.Users
		default:
			return emptyMsgs("getHistory: unexpected response type")
		}

		userByID := make(map[int64]*tg.User)
		for _, u := range users {
			if uu, ok := u.(*tg.User); ok {
				userByID[uu.ID] = uu
			}
		}

		out := outMsgs{Messages: []outMsg{}}
		// getHistory is newest-first; the watch renders oldest-first, so reverse.
		for i := len(msgs) - 1; i >= 0; i-- {
			m, ok := msgs[i].(*tg.Message)
			if !ok {
				continue // skip service/empty messages
			}
			out.Messages = append(out.Messages, b.buildOutMsg(conversationID, m, userByID))
		}
		// Cursor = the OLDEST message id in this raw batch. msgs is newest-first, so
		// the last element is oldest; feed its id back as offsetID to page older.
		// Keyed off the raw oldest id (including skipped service/empty messages) so the
		// next page never straddles or repeats a boundary. Empty when the batch is
		// empty = true top of the thread, and the phone stops backfilling there.
		if n := len(msgs); n > 0 {
			out.Cursor = fmt.Sprintf("%d", msgs[n-1].GetID())
		}
		bs, _ := json.Marshal(out)
		return string(bs)
	})
	if !strings.HasPrefix(r, "{") {
		return emptyMsgs(r)
	}
	return r
}

// ---- Sub-accounts: folders as selectable accounts ----------------------------

type outFolder struct {
	ID    int      `json:"id"`
	Title string   `json:"title"`
	Chats []string `json:"chats"` // conversationIDs in this folder
}
type outFolders struct {
	Folders []outFolder `json:"folders"`
	TgError string      `json:"tgError,omitempty"`
}

func emptyFolders(errMsg string) string {
	bs, _ := json.Marshal(outFolders{Folders: []outFolder{}, TgError: errMsg})
	return string(bs)
}

// cachePeer records an InputPeer's access hash in b.peers (so FetchMessages can
// open it even if it's archived / not in the main getDialogs) and returns its
// conversationID.
func (b *Bridge) cachePeer(p tg.InputPeerClass) string {
	switch v := p.(type) {
	case *tg.InputPeerUser:
		cid := fmt.Sprintf("tg:user:%d", v.UserID)
		b.peers[cid] = peerRef{kind: "user", id: v.UserID, hash: v.AccessHash}
		return cid
	case *tg.InputPeerChannel:
		cid := fmt.Sprintf("tg:channel:%d", v.ChannelID)
		b.peers[cid] = peerRef{kind: "channel", id: v.ChannelID, hash: v.AccessHash}
		return cid
	case *tg.InputPeerChat:
		cid := fmt.Sprintf("tg:chat:%d", v.ChatID)
		b.peers[cid] = peerRef{kind: "chat", id: v.ChatID, hash: 0}
		return cid
	default:
		return ""
	}
}

// ListPeople returns the people a new chat can start with (the watch's New chat):
// the account's Telegram contacts, the ones written to most ranked first
// (contacts.getTopPeers), as {"people":[{"id","name","detail","rank"}]}. id is
// the 1:1 chat's conversationID (tg:user:<id>), whose access hash is cached here so
// FetchMessages and SendMessage reach it with or without a dialog. Runs on the pump.
func (b *Bridge) ListPeople() string {
	r := b.call(30*time.Second, func(ctx context.Context) string {
		api := b.client.API()
		res, err := api.ContactsGetContacts(ctx, 0)
		if err != nil {
			return errJSON("getContacts: " + err.Error())
		}
		cs, ok := res.(*tg.ContactsContacts)
		if !ok {
			return errJSON("getContacts: not modified")
		}
		top := map[int64]int{}
		if tp, err := api.ContactsGetTopPeers(ctx, &tg.ContactsGetTopPeersRequest{Correspondents: true, Limit: 50}); err == nil {
			if t, ok := tp.(*tg.ContactsTopPeers); ok {
				for _, cat := range t.Categories {
					for i, p := range cat.Peers {
						if pu, ok := p.Peer.(*tg.PeerUser); ok {
							if _, had := top[pu.UserID]; !had {
								top[pu.UserID] = i + 1
							}
						}
					}
				}
			}
		}
		type person struct {
			ID     string `json:"id"`
			Name   string `json:"name"`
			Detail string `json:"detail,omitempty"`
			Rank   int    `json:"rank,omitempty"`
		}
		out := make([]person, 0, len(cs.Users))
		for _, uc := range cs.Users {
			u, ok := uc.(*tg.User)
			if !ok || u.Self || u.Bot || u.Deleted {
				continue
			}
			name := strings.TrimSpace(u.FirstName + " " + u.LastName)
			if name == "" {
				continue
			}
			cid := b.cachePeer(&tg.InputPeerUser{UserID: u.ID, AccessHash: u.AccessHash})
			detail := ""
			if u.Username != "" {
				detail = "@" + u.Username
			} else if u.Phone != "" {
				detail = "+" + u.Phone
			}
			out = append(out, person{ID: cid, Name: name, Detail: detail, Rank: top[u.ID]})
		}
		bs, _ := json.Marshal(map[string]any{"people": out})
		return string(bs)
	})
	if !strings.HasPrefix(r, "{") {
		return errJSON(r)
	}
	return r
}

// ListFolders returns the account's chat folders as sub-accounts:
// {"folders":[{id,title,chats:[conversationID,...]}]}. Each folder's chats have
// their access hashes cached here, so they stay openable regardless of archive
// state. The default "All Chats" filter is skipped (that's the main "Telegram"
// account). Called by the host to build the Networks-page sub-account rows.
// loadFolders fetches the account's chat folders and (re)populates folderPeers,
// folderedConvos (all convoIDs that live in a folder), and the folderCache JSON.
// Respects folderCacheTTL so it won't hammer getDialogFilters. Runs on the pump
// goroutine. On error it keeps whatever it had.
func (b *Bridge) loadFolders(ctx context.Context) {
	if b.folderCache != "" && time.Since(b.folderCacheAt) < folderCacheTTL {
		return // still fresh — folderPeers/folderedConvos already reflect it
	}
	res, err := b.client.API().MessagesGetDialogFilters(ctx)
	if err != nil {
		return
	}
	out := outFolders{Folders: []outFolder{}}
	foldered := make(map[string]bool)
	for _, fc := range res.Filters {
		var id int
		var title string
		var peers []tg.InputPeerClass
		switch f := fc.(type) {
		case *tg.DialogFilter:
			id, title, peers = f.ID, f.Title.Text, f.IncludePeers
		case *tg.DialogFilterChatlist:
			id, title, peers = f.ID, f.Title.Text, f.IncludePeers
		default:
			continue // DialogFilterDefault ("All Chats") = main account; skip
		}
		chats := []string{}
		for _, p := range peers {
			if cid := b.cachePeer(p); cid != "" {
				chats = append(chats, cid)
				foldered[cid] = true
			}
		}
		b.folderPeers[id] = peers
		out.Folders = append(out.Folders, outFolder{ID: id, Title: title, Chats: chats})
	}
	b.folderedConvos = foldered
	bs, _ := json.Marshal(out)
	b.folderCache = string(bs)
	b.folderCacheAt = time.Now()
}

func (b *Bridge) ListFolders() string {
	r := b.call(30*time.Second, func(ctx context.Context) string {
		b.loadFolders(ctx)
		if b.folderCache == "" {
			return emptyFolders("no folders")
		}
		return b.folderCache
	})
	if !strings.HasPrefix(r, "{") {
		return emptyFolders(r)
	}
	return r
}

// ListFolderConversations resolves a folder's member chats into a full
// conversation list (names + previews) via getPeerDialogs, same shape as
// ListConversations. Used when a folder sub-account is the active account.
// Requires the folder to have been seen by a prior ListFolders (that's where the
// member peers + their access hashes come from).
func (b *Bridge) ListFolderConversations(folderID int, limit int) string {
	r := b.call(30*time.Second, func(ctx context.Context) string {
		peers := b.folderPeers[folderID]
		if len(peers) == 0 {
			return emptyConvos(fmt.Sprintf("folder %d not loaded (list folders first)", folderID))
		}
		dp := make([]tg.InputDialogPeerClass, 0, len(peers))
		for _, p := range peers {
			dp = append(dp, &tg.InputDialogPeer{Peer: p})
		}
		res, err := b.client.API().MessagesGetPeerDialogs(ctx, dp)
		if err != nil {
			return emptyConvos("getPeerDialogs: " + err.Error())
		}
		out := outConvos{Conversations: b.dialogsToConvos(res.Dialogs, res.Messages, res.Chats, res.Users, false)}
		bs, _ := json.Marshal(out)
		return string(bs)
	})
	if !strings.HasPrefix(r, "{") {
		return emptyConvos(r)
	}
	return r
}

// ---- Diagnostic: folders (chat filters) --------------------------------------

func peerDesc(p tg.InputPeerClass) string {
	switch v := p.(type) {
	case *tg.InputPeerUser:
		return fmt.Sprintf("tg:user:%d", v.UserID)
	case *tg.InputPeerChat:
		return fmt.Sprintf("tg:chat:%d", v.ChatID)
	case *tg.InputPeerChannel:
		return fmt.Sprintf("tg:channel:%d", v.ChannelID)
	default:
		return "tg:other"
	}
}

// DumpFolders lists the account's chat folders (DialogFilters) and their member
// chats — the grouping behind the "channels as sub-accounts" feature. Diagnostic
// only; safe to remove once sub-accounts are built.
func (b *Bridge) DumpFolders() string {
	return b.call(30*time.Second, func(ctx context.Context) string {
		res, err := b.client.API().MessagesGetDialogFilters(ctx)
		if err != nil {
			return "folders-error: " + err.Error()
		}
		var sb strings.Builder
		sb.WriteString(fmt.Sprintf("count=%d\n", len(res.Filters)))
		for _, fc := range res.Filters {
			switch f := fc.(type) {
			case *tg.DialogFilter:
				sb.WriteString(fmt.Sprintf("FOLDER id=%d title=%q peers=%d [", f.ID, f.Title.Text, len(f.IncludePeers)))
				for _, p := range f.IncludePeers {
					sb.WriteString(peerDesc(p) + " ")
				}
				sb.WriteString("]\n")
			case *tg.DialogFilterChatlist:
				sb.WriteString(fmt.Sprintf("CHATLIST id=%d title=%q peers=%d [", f.ID, f.Title.Text, len(f.IncludePeers)))
				for _, p := range f.IncludePeers {
					sb.WriteString(peerDesc(p) + " ")
				}
				sb.WriteString("]\n")
			case *tg.DialogFilterDefault:
				sb.WriteString("DEFAULT (All Chats)\n")
			default:
				sb.WriteString(fmt.Sprintf("UNKNOWN type=%T\n", fc))
			}
		}
		out := sb.String()
		if out == "" {
			out = "(no folders)"
		}
		return out
	})
}

// LastError returns the run loop's exit error, if any (empty while running).
func (b *Bridge) LastError() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.runErr == nil {
		return ""
	}
	return b.runErr.Error()
}

// Close tears down the run loop and the connection.
func (b *Bridge) Close() {
	if b.cancel != nil {
		b.cancel()
	}
}
