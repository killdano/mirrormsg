// Package discordbridge is MirrorMsg's native Discord bridge.
//
// It logs a user's own Discord account in over the gateway (the same mechanism
// mautrix-discord and Beeper's Discord bridge use: github.com/beeper/discordgo — a
// BSD-licensed fork of bwmarrin/discordgo that speaks the Android-client protocol
// for user tokens) and mirrors DMs — and, opt-in, selected server channels — into
// the same message pipeline the watch already reads.
//
// Shape and conventions mirror telegrambridge/bridge_tg.go exactly so the phone's
// adapter (DiscordService) drives it the same way it drives Telegram:
//   - a Bridge object built with NewDiscordBridge(), not package-level functions;
//   - a host callback installed via SetLiveSink; the bridge calls
//     LiveSink.OnEvent(eventType, jsonPayload) with "MESSAGE" / "REACTION" / "DIAG";
//   - read methods return JSON strings whose shapes (outConvo / outMsg) match the
//     other bridges byte-for-byte, so PebbleListenerService parsing is unchanged.
//
// Load-bearing invariants copied from bridge_tg.go: timestamp is a STRING of
// MICROSECONDS; messageStatus.status carries the OUTGOING_/INCOMING_ prefix the
// notify gates key on; each messageInfo entry has messageContent (always) plus an
// optional mediaContent on the SAME entry; top-level isMe/fromMe accompany
// senderParticipant.isMe.
//
// Login is token-based: the phone obtains and persists a user token and hands it to
// Start(). (The QR remote-auth flow lives in AGPL code and is deliberately NOT
// vendored; a license-safe login is a separate follow-up.) Everything crossing the
// gomobile boundary is a string, int, or bool, plus the LiveSink callback — no
// discordgo types are exposed to Kotlin.
package discordbridge

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"runtime/debug"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"fi.mirrormsg/mediaroute"
	"fi.mirrormsg/watchimage" // shared scale + palettize/variable-depth encoder (image serving)

	"github.com/bwmarrin/discordgo" // resolves to github.com/beeper/discordgo via go.mod replace
)

// convoIDPrefix namespaces Discord channel ids in the flat MessageStore. Discord
// channel ids are snowflakes (already globally unique), but the prefix keeps them
// unambiguous alongside google/tg/beeper convo ids and lets the phone recognise the
// owning network from the id alone.
const convoIDPrefix = "discord:"

// LiveSink is implemented on the host (Kotlin) side and installed via SetLiveSink;
// the bridge calls it to surface live events. Bound by gomobile as a reverse
// interface. Event types mirror the other native bridges: MESSAGE (a live message
// JSON, shaped exactly like a FetchMessages entry), REACTION, DIAG.
type LiveSink interface {
	OnEvent(eventType string, jsonPayload string)
}

// ---- output JSON shapes (must match bridge_tg.go / the watch renderer) -------

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
	// Nav model: "ENTER" (a forum/bucket to drill into) with the token to push;
	// absent => OPEN (a real chat/channel). Matches the phone's rebuildConvoIndex.
	NavKind    string `json:"navKind,omitempty"`
	NavContext string `json:"navContext,omitempty"`
}
type outConvos struct {
	Conversations []outConvo `json:"conversations"`
	DcError       string     `json:"dcError,omitempty"`
}

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
	Format   string `json:"format"`
	MimeType string `json:"mimeType"`
	MediaID  string `json:"mediaID"`
	// PosterMediaID is a still of a video's first frame (a url): the watch shows
	// it in the chat row with the video icon over it.
	PosterMediaID string `json:"posterMediaID,omitempty"`
}
type outMsgInfo struct {
	MessageContent outContent       `json:"messageContent"`
	MediaContent   *outMediaContent `json:"mediaContent,omitempty"`
}
type outReaction struct {
	Emoji string `json:"emoji"`
	Count int    `json:"count"`
}

// outReplyTo carries only the quoted message's id; the watch resolves it against
// the thread it already holds to compose the "replied to X" header (same shape
// bridge_tg.go emits, so the renderer is network-agnostic).
type outReplyTo struct {
	ID string `json:"id"`
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
	Reactions         *[]outReaction `json:"reactions,omitempty"`
	ReplyTo           *outReplyTo    `json:"replyTo,omitempty"`
	GroupName         string         `json:"groupName,omitempty"`
	Silent            bool           `json:"silent,omitempty"`
}
type outMsgs struct {
	Messages []outMsg `json:"messages"`
	// Cursor is the pagination anchor for infinite backfill: the id of the OLDEST
	// message in this batch. The phone stores it and passes it back as `before` to
	// fetch the next older page. Empty (omitted) = true top-of-channel -> backfill
	// stops. Same round-trip contract as every other native network's cursor.
	Cursor  string `json:"cursor,omitempty"`
	DcError string `json:"dcError,omitempty"`
}

// Guild list for the phone's server-picker (amber reveal) panel — mirrors
// TelegramService.foldersJson()'s {folders:[...]} with {guilds:[...]}.
type outGuild struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Icon string `json:"icon,omitempty"`
}
type outGuilds struct {
	Guilds  []outGuild `json:"guilds"`
	DcError string     `json:"dcError,omitempty"`
}

// ---- Bridge -----------------------------------------------------------------

// dcChanMute / dcGuildMute mirror the slice of Discord's user_guild_settings we
// need to decide whether a message should alert. level is a NotificationLevel
// (0=all, 1=only-mentions, 2=nothing, 3=inherit-from-guild).
type dcChanMute struct {
	muted bool
	level int
}
type dcGuildMute struct {
	muted    bool
	level    int
	channels map[string]dcChanMute // channelID -> override
}

type Bridge struct {
	mu      sync.Mutex
	session *discordgo.Session
	sink    LiveSink
	ready   bool
	lastErr string

	selfID   string
	selfName string

	// DM / group-DM channels: id -> display name, id -> primary recipient id.
	dmName      map[string]string
	dmRecipient map[string]string
	dmOrder     []string
	// dmAvatar: a DM's photo (the other person's avatar, a group DM's icon) by
	// channel id, as a CDN address (AvatarURL, step 90). No entry = none.
	dmAvatar map[string]string
	// The watch's New chat: READY's friends, and each person's 1:1 DM channel.
	friends []dcFriend
	dmWith  map[string]string // user id -> their 1:1 DM channel id

	// Guilds: id -> name / icon, and each guild's text channels.
	guildName    map[string]string
	guildIcon    map[string]string
	guildOrder   []string
	channelGuild map[string]string // channelID -> guildID
	channelName  map[string]string // channelID -> channel name
	// guildMutes holds the user's Discord notification/mute settings per guild
	// (keyed by guildID; "" = the DM bucket), so a message from a muted server or
	// channel is marked silent and never alerts the wrist — the phone still stores
	// it, so it stays browsable. Populated from READY's user_guild_settings and kept
	// live via UserGuildSettingsUpdate.
	guildMutes map[string]*dcGuildMute
	// guildDefaults holds each guild's SERVER-level default notification setting
	// (guild.DefaultMessageNotifications: 0=all, 1=@only). Discord omits a personal
	// user_guild_settings entry for guilds you've never customized, so the effective
	// level for those is the server default — GuildNotifyLevel falls back to this.
	guildDefaults map[string]int
	// guildChannels holds each guild's text-channel ids in display order (by
	// Discord channel Position), so a selected server lists its channels in the
	// same order the Discord client shows them rather than random map order.
	guildChannels map[string][]string // guildID -> ordered channelIDs
	// forumChannels marks channelIDs that are forum/media channels (they hold posts,
	// not messages) so the channel list surfaces them as a drill-in (ENTER) rather
	// than a chat. Their posts are threads, listed via ListForumThreads.
	forumChannels map[string]bool
	// lastText caches the most recent message's preview text per channelID, so the
	// conversation list can show a snippet. Populated opportunistically from live
	// messages and history fetches (no extra REST calls); empty until a channel sees
	// activity or is opened.
	lastText map[string]string

	// imageURL caches the CDN URL of each picture attachment by its media id (the
	// message's id, or "<message id>:<attachment id>" for a message's later pictures:
	// see pictures), so a later DownloadImageForWatch(id) can fetch + scale it on
	// demand. Populated in buildOutMsg (live + history) with no extra REST. It lives
	// in memory only, and Discord signs these URLs with a ~24h expiry: a chat the
	// phone shows from its store asks for pictures before the thread fetch has
	// refilled it, and an old URL 403s. RecoverImage re-learns them on demand.
	imageURL map[string]string
	// imageFresh marks when RecoverImage last asked Discord about a message id (found or
	// not), so a burst of requests around one spot makes one REST call, not one each.
	imageFresh map[string]time.Time
	// recoverMu serializes RecoverImage's REST calls (and relearnAudio's).
	recoverMu sync.Mutex
	// audioURL caches the CDN URL of a voice message (any sound attachment) per
	// messageID, as imageURL does for pictures. The phone fetches the file through
	// DownloadMediaToFile, which re-learns a missing or expired one (step 86).
	audioURL map[string]string

	// enabledGuilds is the opt-in set. Empty by default => only DMs flow.
	enabledGuilds map[string]bool
}

// NewDiscordBridge constructs an idle bridge. Call SetLiveSink, then Start(token).
// Bound by gomobile as Discordbridge.newDiscordBridge.
func NewDiscordBridge() *Bridge {
	return &Bridge{
		dmName:        map[string]string{},
		dmRecipient:   map[string]string{},
		dmAvatar:      map[string]string{},
		guildName:     map[string]string{},
		guildIcon:     map[string]string{},
		channelGuild:  map[string]string{},
		channelName:   map[string]string{},
		guildChannels: map[string][]string{},
		forumChannels: map[string]bool{},
		lastText:      map[string]string{},
		imageURL:      map[string]string{},
		imageFresh:    map[string]time.Time{},
		audioURL:      map[string]string{},
		guildMutes:    map[string]*dcGuildMute{},
		enabledGuilds: map[string]bool{},
	}
}

// SetLiveSink installs the host callback for live updates.
func (b *Bridge) SetLiveSink(s LiveSink) {
	b.mu.Lock()
	b.sink = s
	b.mu.Unlock()
}

// emitEvent delivers a typed event to the host sink (no-op if none set). The sink
// is copied under the lock and called outside it so a re-entrant sink can't
// deadlock.
func (b *Bridge) emitEvent(eventType, jsonPayload string) {
	b.mu.Lock()
	s := b.sink
	b.mu.Unlock()
	if s != nil {
		s.OnEvent(eventType, jsonPayload)
	}
}

// Start opens a gateway session with a previously-harvested user token and begins
// streaming. Returns "" on success, or {"dcError":"..."} on failure. discordgo.New
// auto-detects a user token (no "Bot " prefix) and switches the fork into
// Android-client identify mode.
//
// The connect runs under a deferred recover(): the beeper/discordgo fork's
// user-token Open() does a chunk of work synchronously on THIS goroutine
// (gateway dial, identify, the first READY decode and its follow-up writes)
// before it spawns its own heartbeat/listen goroutines. A nil deref anywhere
// in that synchronous stretch would otherwise be an unrecoverable native
// SIGSEGV that kills the whole app (which is exactly what we saw — a crash on
// the dc-login thread with no catchable stack). Because that work is on our
// goroutine, a recover() here catches it, turns it into an exportable DIAG line
// WITH the Go stack (the only way to see the real frame on this device), and
// fails the login cleanly instead of taking MirrorMsg down.
func (b *Bridge) Start(token string) (result string) {
	defer func() {
		if r := recover(); r != nil {
			st := string(debug.Stack())
			if len(st) > 1400 {
				st = st[:1400]
			}
			b.emitEvent("DIAG", fmt.Sprintf("discord PANIC in Start: %v | %s", r, st))
			result = b.fail(fmt.Sprintf("connect panic: %v", r))
		}
	}()

	if strings.TrimSpace(token) == "" {
		return b.fail("empty token")
	}
	b.mu.Lock()
	if b.session != nil {
		b.mu.Unlock()
		return "" // already started
	}
	b.mu.Unlock()

	s, err := discordgo.New(token)
	if err != nil {
		return b.fail("new: " + err.Error())
	}

	// This discordgo fork dispatches gateway events ONLY through s.EventHandler
	// (its AddHandler/s.handle path is commented out), and it calls s.EventHandler
	// unconditionally with no nil check — so leaving it unset makes the first
	// event (READY, during Open) a nil-function call that crashes the process.
	// mautrix-discord sets it for exactly this reason. onEvent is our single
	// catch-all dispatcher.
	s.EventHandler = b.onEvent

	// Ask the gateway for the COMPLETE user_guild_settings snapshot in READY. The
	// fork's droid identify leaves user_guild_settings_version unset (omitempty =>
	// omitted from the payload), which makes Discord return only a partial/capped
	// set — on large accounts, muted guilds beyond the cap are simply missing, so
	// their messages never get silenced (guildMutes has no entry for them). -1 means
	// "I hold no cached version, send everything."
	if s.Identify.ClientState != nil {
		s.Identify.ClientState.UserGuildSettingsVersion = -1
	}

	if err := s.Open(); err != nil {
		return b.fail("open: " + err.Error())
	}
	b.mu.Lock()
	b.session = s
	b.mu.Unlock()
	return ""
}

// Close tears down the gateway session and clears cached state.
func (b *Bridge) Close() {
	b.mu.Lock()
	s := b.session
	b.session = nil
	b.ready = false
	b.selfID = ""
	b.selfName = ""
	b.dmName = map[string]string{}
	b.dmRecipient = map[string]string{}
	b.dmAvatar = map[string]string{}
	b.dmOrder = nil
	b.guildName = map[string]string{}
	b.guildIcon = map[string]string{}
	b.guildOrder = nil
	b.channelGuild = map[string]string{}
	b.channelName = map[string]string{}
	b.guildChannels = map[string][]string{}
	b.forumChannels = map[string]bool{}
	b.lastText = map[string]string{}
	b.imageURL = map[string]string{}
	b.imageFresh = map[string]time.Time{}
	b.audioURL = map[string]string{}
	b.guildMutes = map[string]*dcGuildMute{}
	b.guildDefaults = map[string]int{}
	b.mu.Unlock()
	if s != nil {
		_ = s.Close()
	}
}

// ConnectStatus reports "READY" once the gateway READY has been processed,
// "CONNECTING" while a session exists but READY hasn't arrived, else "OFFLINE".
func (b *Bridge) ConnectStatus() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.session == nil {
		return "OFFLINE"
	}
	if b.ready {
		return "READY"
	}
	return "CONNECTING"
}

// SelfID returns the logged-in Discord user id (for selfActorKey on the phone).
func (b *Bridge) SelfID() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.selfID
}

// SelfName returns the logged-in account's display name (for accountLabel on the
// phone). Populated on READY; empty before then.
func (b *Bridge) SelfName() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.selfName
}

// LastError returns the most recent failure string (for diagnostics).
func (b *Bridge) LastError() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.lastErr
}

// SetEnabledGuilds sets the opt-in guild set from a comma-separated id list. Live
// messages and conversation listings for guilds NOT in this set are suppressed;
// DMs are always on. Called whenever the phone's server toggles change.
func (b *Bridge) SetEnabledGuilds(idsCSV string) {
	set := map[string]bool{}
	for _, id := range strings.Split(idsCSV, ",") {
		id = strings.TrimSpace(id)
		if id != "" {
			set[id] = true
		}
	}
	b.mu.Lock()
	b.enabledGuilds = set
	b.mu.Unlock()
	// Newly-enabled large guilds need an op-14 subscription before Discord will stream
	// their messages. This fires on the phone's post-READY push (cold connect) and on
	// every toggle. Goroutine: subscribeEnabledGuilds spaces its gateway sends.
	go b.subscribeEnabledGuilds()
}

func (b *Bridge) fail(msg string) string {
	b.mu.Lock()
	b.lastErr = msg
	b.mu.Unlock()
	bs, _ := json.Marshal(map[string]string{"dcError": msg})
	return string(bs)
}

// ---- handlers ---------------------------------------------------------------

// onEvent is the single dispatcher installed as s.EventHandler. This fork calls
// it for every gateway event (it's the only live-dispatch path). It runs
// synchronously in discordgo's read loop — and, for the first READY, on the
// connect goroutine inside Open() — so the recover() lives here to keep any
// panic from killing the process, surfacing it as an exportable DIAG line
// instead.
func (b *Bridge) onEvent(i any) {
	defer b.recoverHandler("onEvent")
	switch e := i.(type) {
	case *discordgo.Ready:
		b.onReady(e)
	case *discordgo.Resumed:
		// A RESUME replays missed events but does NOT re-fire READY, so the op-14
		// guild subscriptions from the dropped connection are gone. Re-send them or
		// large enabled guilds go silent again after any resume.
		go b.subscribeEnabledGuilds()
	case *discordgo.MessageCreate:
		b.onMessageCreate(e)
	case *discordgo.MessageReactionAdd:
		b.onReaction(e.MessageReaction, false)
	case *discordgo.MessageReactionRemove:
		b.onReaction(e.MessageReaction, true)
	case *discordgo.UserGuildSettingsUpdate:
		if e != nil && e.UserGuildSettings != nil {
			b.applyGuildSettings(e.UserGuildSettings)
			// Live mute change pushed from Discord — tell the UI so a visible
			// per-server mute button repaints in real time. Payload is the guild id.
			b.emitEvent("GUILDMUTE", e.UserGuildSettings.GuildID)
		}
	}
}

// recoverHandler turns a panic inside a synchronously-dispatched discordgo
// handler into an exportable DIAG line instead of a process-killing native
// crash. discordgo runs the dispatcher in its gateway read-loop goroutine, so an
// unrecovered panic there would take the whole app down with an unrecoverable
// SIGSEGV; recover() must live in the same goroutine, i.e. in onEvent.
func (b *Bridge) recoverHandler(what string) {
	if r := recover(); r != nil {
		st := string(debug.Stack())
		if len(st) > 700 {
			st = st[:700]
		}
		b.emitEvent("DIAG", fmt.Sprintf("discord PANIC in %s: %v | %s", what, r, st))
	}
}

func (b *Bridge) onReady(r *discordgo.Ready) {
	b.mu.Lock()
	if r.User != nil {
		b.selfID = r.User.ID
		b.selfName = bestName(r.User)
	}
	b.dmName = map[string]string{}
	b.dmRecipient = map[string]string{}
	b.dmAvatar = map[string]string{}
	b.dmWith = map[string]string{}
	b.dmOrder = b.dmOrder[:0]
	// Order DMs / group-DMs most-recent-first by LastMessageID (a snowflake whose
	// high bits encode a timestamp, so higher = newer). The phone renders
	// conversations in the order this bridge emits them — it does not re-sort, same
	// as Telegram — so recency ordering has to happen here. READY's PrivateChannels
	// arrive in no useful order.
	dmChans := make([]*discordgo.Channel, 0, len(r.PrivateChannels))
	for _, ch := range r.PrivateChannels {
		if ch == nil {
			continue
		}
		if ch.Type == discordgo.ChannelTypeDM || ch.Type == discordgo.ChannelTypeGroupDM {
			dmChans = append(dmChans, ch)
		}
	}
	sort.SliceStable(dmChans, func(i, j int) bool {
		return snowflake(dmChans[i].LastMessageID) > snowflake(dmChans[j].LastMessageID)
	})
	for _, ch := range dmChans {
		b.dmName[ch.ID] = dmDisplayName(ch)
		if rid := primaryRecipient(ch); rid != "" {
			b.dmRecipient[ch.ID] = rid
		}
		if u := dmAvatarURL(ch); u != "" {
			b.dmAvatar[ch.ID] = u
		}
		if rid := primaryRecipient(ch); rid != "" && ch.Type == discordgo.ChannelTypeDM {
			if _, had := b.dmWith[rid]; !had {
				b.dmWith[rid] = ch.ID
			}
		}
		b.dmOrder = append(b.dmOrder, ch.ID)
	}
	// Friends (READY's relationships, their names from READY's users), for New chat.
	users := map[string]*discordgo.User{}
	for _, u := range r.Users {
		if u != nil {
			users[u.ID] = u
		}
	}
	b.friends = b.friends[:0]
	for _, rel := range r.Relationships {
		if rel == nil || rel.Type != discordgo.RelationshipFriend || rel.ID == "" {
			continue
		}
		f := dcFriend{ID: rel.ID, Name: rel.Nickname}
		if u := users[rel.ID]; u != nil {
			if f.Name == "" {
				f.Name = bestName(u)
			}
			f.Username = u.Username
		}
		if f.Name != "" {
			b.friends = append(b.friends, f)
		}
	}
	b.guildName = map[string]string{}
	b.guildIcon = map[string]string{}
	b.guildOrder = b.guildOrder[:0]
	b.channelGuild = map[string]string{}
	b.channelName = map[string]string{}
	b.guildChannels = map[string][]string{}
	b.forumChannels = map[string]bool{}
	b.guildMutes = map[string]*dcGuildMute{}
	b.guildDefaults = map[string]int{}
	if r.UserGuildSettings != nil {
		for _, gs := range r.UserGuildSettings.Entries {
			b.applyGuildSettingsLocked(gs)
		}
	}
	for _, g := range r.Guilds {
		b.registerGuildLocked(g)
	}
	b.ready = true
	dmN, gN := len(b.dmOrder), len(b.guildOrder)
	uname := b.selfName
	// DIAG: measure user_guild_settings snapshot coverage. A capped READY snapshot
	// leaves some guilds with no guildMutes entry, so their notify level/mute read
	// as defaults (All / not-muted) AND their non-mention messages aren't silenced.
	// Count loaded entries (excluding the "" DM bucket) and collect the names of
	// guilds with NO settings entry, to see the cap's scale and whether the missing
	// set skews toward low-recency guilds.
	settingsN := 0
	for gid := range b.guildMutes {
		if gid != "" {
			settingsN++
		}
	}
	var missingGuilds []string
	for _, gid := range b.guildOrder {
		if b.guildMutes[gid] == nil {
			nm := b.guildName[gid]
			if nm == "" {
				nm = gid
			}
			missingGuilds = append(missingGuilds, nm)
		}
	}
	b.mu.Unlock()

	b.emitEvent("DIAG", fmt.Sprintf("discord READY user=%s dms=%d guilds=%d", uname, dmN, gN))
	b.emitEvent("DIAG", fmt.Sprintf("discord SETTINGS coverage: loaded=%d/%d guilds, missing=%d", settingsN, gN, len(missingGuilds)))
	if len(missingGuilds) > 0 {
		b.emitEvent("DIAG", "discord SETTINGS missing (no notify/mute entry): "+strings.Join(missingGuilds, " | "))
	}

	// Op-14 subscribe every enabled guild so large servers start streaming live
	// messages (they don't push to a user connection until subscribed). Goroutine:
	// this runs in the gateway dispatch path and subscribeEnabledGuilds sleeps between
	// sends. On a first cold connect enabledGuilds may still be empty here (the phone
	// pushes it just after READY) — that path is covered by SetEnabledGuilds; this call
	// covers full reconnects where the set is already populated.
	go b.subscribeEnabledGuilds()
}

func (b *Bridge) onMessageCreate(m *discordgo.MessageCreate) {
	if m == nil || m.Message == nil {
		return
	}
	if !b.channelAllowed(m.ChannelID, m.GuildID) {
		return
	}
	// Cache the preview text for the conversation list (cheap, no REST).
	txt := displayTextOf(m.Message)
	b.mu.Lock()
	b.lastText[m.ChannelID] = txt
	// A DM that opened since READY: its photo is the sender's (step 90).
	if _, known := b.dmName[m.ChannelID]; !known && m.GuildID == "" && m.Author != nil && m.Author.ID != b.selfID {
		if _, has := b.dmAvatar[m.ChannelID]; !has {
			if u := userAvatarURL(m.Author); u != "" {
				b.dmAvatar[m.ChannelID] = u
			}
		}
	}
	b.mu.Unlock()
	bs, err := json.Marshal(b.buildOutMsg(m.Message))
	if err != nil {
		return
	}
	b.emitEvent("MESSAGE", string(bs))
}

// channelAllowed gates emission: DMs (no guild) always flow; guild channels flow
// only when their guild is in the enabled set.
func (b *Bridge) channelAllowed(channelID, guildID string) bool {
	if guildID == "" {
		return true // DM / group DM
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.enabledGuilds[guildID]
}

// subscribeEnabledGuilds sends a gateway op-14 guild subscription for every enabled
// guild so Discord starts streaming live events (MESSAGE_CREATE, typing, threads) for
// them. Large guilds do NOT push messages to a user connection until subscribed —
// small/medium guilds stream passively, which is why only big enabled servers (e.g.
// Subnautica) went silent while smaller ones worked. Payload mirrors mautrix-discord's
// proven pattern on this same fork. Idempotent and safe to call repeatedly: on READY,
// on RESUME, and whenever the enabled set changes. It issues its own gateway sends
// (spaced to stay gentle on the socket), so callers should invoke it in a goroutine to
// avoid blocking the dispatch loop.
func (b *Bridge) subscribeEnabledGuilds() {
	b.mu.Lock()
	s := b.session
	ids := make([]string, 0, len(b.enabledGuilds))
	for gid := range b.enabledGuilds {
		ids = append(ids, gid)
	}
	b.mu.Unlock()
	if s == nil || len(ids) == 0 {
		return
	}
	for _, gid := range ids {
		err := s.SubscribeGuild(discordgo.GuildSubscribeData{
			GuildID:    gid,
			Typing:     true,
			Activities: true,
			Threads:    true,
		})
		if err != nil {
			b.emitEvent("DIAG", fmt.Sprintf("DC-SUB guild=%s err=%v", gid, err))
		}
		time.Sleep(300 * time.Millisecond)
	}
}

// onReaction turns a live gateway reaction add/remove into the standard per-actor
// REACTION event ({op,msg,actor,emoji,conversationID}) that the host hands straight
// to ReactionStore.handleBridgeEvent. Unicode emoji only (custom emoji carry a
// non-empty id and have no Pebble glyph); gated to allowed channels like messages.
// Discord's event carries no aggregate count, so live deltas are per-user; the
// authoritative totals reconcile on the next thread fetch (hydrateDiscordReactions
// -> ReactionStore.setCounts).
func (b *Bridge) onReaction(r *discordgo.MessageReaction, removed bool) {
	if r == nil {
		return
	}
	if !b.channelAllowed(r.ChannelID, r.GuildID) {
		return
	}
	if r.Emoji.ID != "" || r.Emoji.Name == "" {
		return // custom / unnamed: the watch can't render it
	}
	op := "upsert"
	if removed {
		op = "delete"
	}
	payload := map[string]string{
		"op":             op,
		"msg":            r.MessageID,
		"actor":          r.UserID,
		"emoji":          r.Emoji.Name,
		"conversationID": convoIDPrefix + r.ChannelID,
	}
	bs, err := json.Marshal(payload)
	if err != nil {
		return
	}
	b.emitEvent("REACTION", string(bs))
}

// applyGuildSettings folds one user_guild_settings entry (a guild, or "" for the
// DM bucket) into b.guildMutes, replacing any prior entry. Takes the lock; use
// from event handlers that don't already hold it.
func (b *Bridge) applyGuildSettings(gs *discordgo.UserGuildSettings) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.applyGuildSettingsLocked(gs)
}

// applyGuildSettingsLocked is the core; the caller MUST already hold b.mu. onReady
// builds every map under one lock, so it calls this directly (calling the locking
// wrapper there would deadlock — the mutex is not reentrant).
func (b *Bridge) applyGuildSettingsLocked(gs *discordgo.UserGuildSettings) {
	if gs == nil {
		return
	}
	entry := &dcGuildMute{
		muted:    gs.Muted,
		level:    int(gs.MessageNotifications),
		channels: map[string]dcChanMute{},
	}
	for _, co := range gs.ChannelOverrides {
		if co == nil {
			continue
		}
		entry.channels[co.ChannelID] = dcChanMute{muted: co.Muted, level: int(co.MessageNotifications)}
	}
	b.guildMutes[gs.GuildID] = entry
}

// IsGuildMuted reports whether the given guild is muted in the user's Discord
// settings — the same state the notification gate honors. Used to seed the
// per-server mute button so it reflects Discord's real mute.
func (b *Bridge) IsGuildMuted(guildID string) bool {
	b.mu.Lock()
	gs := b.guildMutes[guildID]
	present := gs != nil
	muted := gs != nil && gs.muted
	b.mu.Unlock()
	b.emitEvent("DIAG", fmt.Sprintf("DC getMuted guild=%s entry=%v muted=%v", guildID, present, muted))
	return muted
}

// SetGuildMute sets the user's REAL Discord mute for a guild (so it also shows muted
// in the Discord app — one source of truth). PATCHes user_guild_settings; on success
// Discord echoes a UserGuildSettingsUpdate that refreshes guildMutes, keeping the
// notification gate consistent. We also update guildMutes optimistically so a follow-
// up IsGuildMuted is correct before that echo lands. Returns {"ok":true} or
// {"dcError":...}.
func (b *Bridge) SetGuildMute(guildID string, muted bool) string {
	if guildID == "" {
		return b.fail("empty guild id")
	}
	b.mu.Lock()
	s := b.session
	b.mu.Unlock()
	if s == nil {
		return b.fail("not started")
	}
	// PATCH ONLY the muted field. UserGuildSettingsEdit's fields are all
	// non-omitempty, so sending the whole struct would also write
	// message_notifications:0, mobile_push:false, etc. — muting a server would reset
	// its notification level to "All". Send a single-field body so Discord merges just
	// this change and leaves the rest of the server's settings alone.
	body := map[string]bool{"muted": muted}
	if _, err := s.RequestWithBucketID("PATCH",
		discordgo.EndpointUserGuildSettings("@me", guildID), body,
		discordgo.EndpointUserGuildSettings("", guildID)); err != nil {
		return b.fail("mute: " + err.Error())
	}
	b.mu.Lock()
	gs := b.guildMutes[guildID]
	if gs == nil {
		gs = &dcGuildMute{channels: map[string]dcChanMute{}}
		b.guildMutes[guildID] = gs
	}
	gs.muted = muted
	b.mu.Unlock()
	bs, _ := json.Marshal(map[string]interface{}{"ok": true})
	return string(bs)
}

// GuildNotifyLevel reports the user's message-notification level for a guild
// (0=all, 1=only-mentions, 2=nothing). Defaults to 0 (All) when there's no explicit
// override or the stored value is out of range. Seeds the 3-way notify selector.
func (b *Bridge) GuildNotifyLevel(guildID string) int {
	b.mu.Lock()
	gs := b.guildMutes[guildID]
	// Personal override wins (levels 0=all,1=@,2=none). A personal "inherit"
	// value (3) or no personal entry falls through to the server default. If the
	// guild isn't even registered (no default known), return -1 = unknown so the
	// UI can show a dim/unavailable state rather than a wrong "all".
	lvl := -1
	source := "unknown"
	if gs != nil && gs.level >= 0 && gs.level <= 2 {
		lvl = gs.level
		source = "personal"
	} else if def, ok := b.guildDefaults[guildID]; ok {
		lvl = def
		source = "default"
	}
	present := gs != nil
	b.mu.Unlock()
	b.emitEvent("DIAG", fmt.Sprintf("DC getNotify guild=%s personal=%v src=%s level=%d", guildID, present, source, lvl))
	return lvl
}

// SetGuildNotifyLevel sets the user's REAL Discord notification level for a guild
// (0=all, 1=only-mentions, 2=nothing). PATCHes ONLY message_notifications so mute
// and every other setting are left intact. Optimistically updates local state so a
// follow-up read is correct before Discord's echo. Returns {"ok":true} or
// {"dcError":...}.
func (b *Bridge) SetGuildNotifyLevel(guildID string, level int) string {
	if guildID == "" {
		return b.fail("empty guild id")
	}
	if level < 0 || level > 2 {
		return b.fail("bad level")
	}
	b.mu.Lock()
	s := b.session
	b.mu.Unlock()
	if s == nil {
		return b.fail("not started")
	}
	body := map[string]int{"message_notifications": level}
	if _, err := s.RequestWithBucketID("PATCH",
		discordgo.EndpointUserGuildSettings("@me", guildID), body,
		discordgo.EndpointUserGuildSettings("", guildID)); err != nil {
		return b.fail("notify: " + err.Error())
	}
	b.mu.Lock()
	gs := b.guildMutes[guildID]
	if gs == nil {
		gs = &dcGuildMute{channels: map[string]dcChanMute{}}
		b.guildMutes[guildID] = gs
	}
	gs.level = level
	b.mu.Unlock()
	bs, _ := json.Marshal(map[string]interface{}{"ok": true})
	return string(bs)
}

// registerGuildLocked folds a guild (name, icon, browsable channels, channel->guild
// map) into the bridge state. The caller MUST already hold b.mu; onReady calls it
// while building every map. Idempotent: re-registering updates fields without
// duplicating the guild in guildOrder.
func (b *Bridge) registerGuildLocked(g *discordgo.Guild) {
	if g == nil || g.Unavailable {
		return
	}
	_, known := b.guildName[g.ID]
	b.guildName[g.ID] = g.Name
	b.guildIcon[g.ID] = g.Icon
	// Server-level default notify (0=all, 1=@only), the effective level for any
	// guild the user hasn't personally overridden.
	b.guildDefaults[g.ID] = int(g.DefaultMessageNotifications)
	if !known {
		b.guildOrder = append(b.guildOrder, g.ID)
	}
	// Browsable channels — text + announcement (readable like text) + forum/media
	// (drill-in: they hold posts, not messages) — ordered by Position to match the
	// Discord client.
	chans := make([]*discordgo.Channel, 0, len(g.Channels))
	for _, ch := range g.Channels {
		if ch == nil {
			continue
		}
		switch ch.Type {
		case discordgo.ChannelTypeGuildText, discordgo.ChannelTypeGuildNews:
			chans = append(chans, ch)
		case discordgo.ChannelTypeGuildForum, discordgo.ChannelTypeGuildMedia:
			b.forumChannels[ch.ID] = true
			chans = append(chans, ch)
		}
	}
	sort.SliceStable(chans, func(i, j int) bool { return chans[i].Position < chans[j].Position })
	order := make([]string, 0, len(chans))
	for _, ch := range chans {
		b.channelGuild[ch.ID] = g.ID
		b.channelName[ch.ID] = ch.Name
		order = append(order, ch.ID)
	}
	b.guildChannels[g.ID] = order
}

// silentFor reports whether a message should NOT alert the wrist, honoring the
// user's Discord settings: a muted guild or channel, "nothing" notifications, or
// "only @mentions" when the message doesn't mention the user. An unknown guild
// defaults to notifying. A channel override wins over the guild; an "inherit"
// (level 3) channel level falls through to the guild's setting.
func (b *Bridge) silentFor(m *discordgo.Message) bool {
	b.mu.Lock()
	gs := b.guildMutes[m.GuildID]
	b.mu.Unlock()
	if gs == nil {
		return false
	}
	if co, ok := gs.channels[m.ChannelID]; ok {
		if co.muted {
			return true
		}
		if co.level != 3 { // concrete channel level
			return b.levelSilent(co.level, m)
		}
	}
	if gs.muted {
		return true
	}
	return b.levelSilent(gs.level, m)
}

// levelSilent maps a NotificationLevel to silence: nothing => always silent;
// only-mentions => silent unless the message mentions the user; all/inherit => alert.
func (b *Bridge) levelSilent(level int, m *discordgo.Message) bool {
	switch level {
	case 2: // NotificationLevelNoMessages
		return true
	case 1: // NotificationLevelOnlyMentions
		return !b.mentionsSelf(m)
	default: // 0 all, 3 inherit
		return false
	}
}

// mentionsSelf reports whether the message @mentions the logged-in user directly
// or via @everyone/@here. Role mentions aren't resolved (v1).
func (b *Bridge) mentionsSelf(m *discordgo.Message) bool {
	if m.MentionEveryone {
		return true
	}
	for _, u := range m.Mentions {
		if u != nil && u.ID == b.selfID {
			return true
		}
	}
	return false
}

// ---- read / send methods (gomobile-exported) --------------------------------

// ListConversations returns the DM / group-DM conversations only, as
// {"conversations":[outConvo...]}, most-recent-first. Server channels are NOT
// mixed in here: each enabled server is its own switcher sub-account and lists
// its channels via ListGuildChannels. This is the "Direct Messages" bucket.
func (b *Bridge) ListConversations(limit int) string {
	b.mu.Lock()
	defer b.mu.Unlock()

	out := outConvos{Conversations: []outConvo{}}
	for _, id := range b.dmOrder {
		out.Conversations = append(out.Conversations, outConvo{
			ConversationID: convoIDPrefix + id,
			Name:           b.dmName[id],
			Type:           "DISCORD",
			LatestMessage:  outLatest{DisplayContent: b.lastText[id]},
			Participants:   []outParticipant{{IsMe: false, ID: outID{Number: b.dmRecipient[id]}}},
		})
	}
	bs, _ := json.Marshal(out)
	return string(bs)
}

// FetchMessages pulls history for a conversation as {"messages":[outMsg...],"cursor":"<id>"},
// oldest-first (Discord returns newest-first; we reverse to match every other network).
//
// `before` is the infinite-backfill pagination anchor: empty fetches the NEWEST page;
// a message id fetches the page strictly OLDER than it (Discord's before= param). The
// returned "cursor" is the id of the OLDEST message in this batch — the phone passes it
// back as `before` for the next older page. An empty cursor (a short page: fewer than
// `limit` returned) means true top-of-channel, so backfill stops.
//
// limit is capped at 100 by the Discord API.
func (b *Bridge) FetchMessages(conversationID string, limit int, before string) string {
	channelID := strings.TrimPrefix(conversationID, convoIDPrefix)
	if channelID == conversationID || channelID == "" {
		return b.msgsErr("not a discord conversation id")
	}
	b.mu.Lock()
	s := b.session
	b.mu.Unlock()
	if s == nil {
		return b.msgsErr("not started")
	}
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	msgs, err := s.ChannelMessages(channelID, limit, before, "", "")
	if err != nil {
		return b.msgsErr(err.Error())
	}
	// Seed the list snippet from the newest message (Discord returns newest-first) —
	// ONLY on the newest-page fetch (before==""). On a backfill page (before set),
	// msgs[0] is the newest of an OLDER page, not the channel's newest, so seeding the
	// convo-list snippet from it would show stale text in the conversation list.
	if before == "" && len(msgs) > 0 && msgs[0] != nil {
		txt := displayTextOf(msgs[0])
		b.mu.Lock()
		b.lastText[channelID] = txt
		b.mu.Unlock()
	}
	out := outMsgs{Messages: []outMsg{}}
	for i := len(msgs) - 1; i >= 0; i-- {
		if msgs[i] != nil {
			out.Messages = append(out.Messages, b.buildOutMsg(msgs[i]))
		}
	}
	// Advertise a cursor only when Discord returned a FULL page (len(msgs) >= limit):
	// a short page means we reached the top, so cursor stays empty and backfill stops.
	// The anchor is the OLDEST message in the batch — out.Messages is oldest-first, so
	// element [0]. (Base the "more exists" test on the raw count, but take the cursor
	// value from an actually-built message in case a raw entry was nil.)
	if len(msgs) >= limit && len(out.Messages) > 0 {
		out.Cursor = out.Messages[0].MessageID
	}
	bs, _ := json.Marshal(out)
	return string(bs)
}

// SendMessage posts text to a conversation. Returns {"ok":true,"messageID":"..."}
// or {"dcError":"..."}.
func (b *Bridge) SendMessage(conversationID, text string) string {
	channelID := strings.TrimPrefix(conversationID, convoIDPrefix)
	if channelID == conversationID || channelID == "" {
		return b.fail("not a discord conversation id")
	}
	b.mu.Lock()
	s := b.session
	b.mu.Unlock()
	if s == nil {
		return b.fail("not started")
	}
	msg, err := s.ChannelMessageSend(channelID, text)
	if err != nil {
		return b.fail(err.Error())
	}
	id := ""
	if msg != nil {
		id = msg.ID
	}
	bs, _ := json.Marshal(map[string]interface{}{"ok": true, "messageID": id})
	return string(bs)
}

// dcFriend: one of READY's friends, for the watch's New chat.
type dcFriend struct {
	ID       string
	Name     string
	Username string
}

// ListPeople returns the people a new chat can start with (the watch's New chat):
// the account's friends, as {"people":[{"id","name","detail","chat"}]}. id is the
// user id OpenPerson takes; chat names their 1:1 DM when there is one.
func (b *Bridge) ListPeople() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	type person struct {
		ID     string `json:"id"`
		Name   string `json:"name"`
		Detail string `json:"detail,omitempty"`
		Chat   string `json:"chat,omitempty"`
	}
	out := make([]person, 0, len(b.friends))
	for _, f := range b.friends {
		p := person{ID: f.ID, Name: f.Name}
		if f.Username != "" {
			p.Detail = "@" + f.Username
		}
		if ch := b.dmWith[f.ID]; ch != "" {
			p.Chat = convoIDPrefix + ch
		}
		out = append(out, p)
	}
	bs, _ := json.Marshal(map[string]any{"people": out})
	return string(bs)
}

// OpenPerson returns the 1:1 DM with user userID as {"conversationID","name"}: the one
// there is, else one made now (UserChannelCreate), listed from then on.
func (b *Bridge) OpenPerson(userID string) string {
	b.mu.Lock()
	s := b.session
	ch := b.dmWith[userID]
	name := b.dmName[ch]
	b.mu.Unlock()
	if ch == "" {
		if s == nil {
			return b.fail("not started")
		}
		c, err := s.UserChannelCreate(userID)
		if err != nil {
			return b.fail(err.Error())
		}
		if c == nil || c.ID == "" {
			return b.fail("no DM channel")
		}
		ch = c.ID
		b.mu.Lock()
		if _, had := b.dmName[ch]; !had {
			b.dmName[ch] = dmDisplayName(c)
			if rid := primaryRecipient(c); rid != "" {
				b.dmRecipient[ch] = rid
			}
			if u := dmAvatarURL(c); u != "" {
				b.dmAvatar[ch] = u
			}
			b.dmOrder = append([]string{ch}, b.dmOrder...)
		}
		if b.dmWith == nil {
			b.dmWith = map[string]string{}
		}
		b.dmWith[userID] = ch
		name = b.dmName[ch]
		b.mu.Unlock()
	}
	bs, _ := json.Marshal(map[string]string{"conversationID": convoIDPrefix + ch, "name": name})
	return string(bs)
}

// MarkThreadRead marks a Discord conversation (channel) read up through messageID
// via ChannelMessageAck. A Discord "conversation" is a channel — DM, group DM, or
// guild text channel — all acked the same way, so no guild/DM split is needed.
// Read state is per-channel with the ack'd message as the high-water mark, so
// acking the newest message clears the channel's unread + mention badge. The
// empty lastToken is the standard one-shot ack (no continuation).
func (b *Bridge) MarkThreadRead(conversationID, messageID string) string {
	channelID := strings.TrimPrefix(conversationID, convoIDPrefix)
	if channelID == conversationID || channelID == "" {
		return b.fail("not a discord conversation id")
	}
	if messageID == "" {
		return b.fail("no message id")
	}
	b.mu.Lock()
	s := b.session
	b.mu.Unlock()
	if s == nil {
		return b.fail("not started")
	}
	if _, err := s.ChannelMessageAck(channelID, messageID, ""); err != nil {
		return b.fail(err.Error())
	}
	bs, _ := json.Marshal(map[string]interface{}{"ok": true, "channelID": channelID, "messageID": messageID})
	return string(bs)
}

// SendReply sends text as a reply quoting replyToMessageID in the conversation,
// mirroring bridge_tg.go's SendReply. Returns {"ok":true,"messageID":...} or
// {"dcError":"..."}.
func (b *Bridge) SendReply(conversationID, replyToMessageID, text string) string {
	channelID := strings.TrimPrefix(conversationID, convoIDPrefix)
	if channelID == conversationID || channelID == "" {
		return b.fail("not a discord conversation id")
	}
	b.mu.Lock()
	s := b.session
	b.mu.Unlock()
	if s == nil {
		return b.fail("not started")
	}
	ref := &discordgo.MessageReference{MessageID: replyToMessageID, ChannelID: channelID}
	msg, err := s.ChannelMessageSendReply(channelID, text, ref)
	if err != nil {
		return b.fail(err.Error())
	}
	id := ""
	if msg != nil {
		id = msg.ID
	}
	bs, _ := json.Marshal(map[string]interface{}{"ok": true, "messageID": id})
	return string(bs)
}

// SendReaction adds (remove=false) or removes (remove=true) the user's own reaction
// on a message. emoji is the raw unicode emoji; the Discord API takes it directly as
// the reaction identifier. A removal goes to "@me" (Delete Own Reaction); the form
// with a user id is for removing someone else's, and needs Manage Messages. Returns
// {"ok":true} or {"dcError":"..."}.
func (b *Bridge) SendReaction(conversationID, messageID, emoji string, remove bool) string {
	channelID := strings.TrimPrefix(conversationID, convoIDPrefix)
	if channelID == conversationID || channelID == "" {
		return b.fail("not a discord conversation id")
	}
	b.mu.Lock()
	s := b.session
	b.mu.Unlock()
	if s == nil {
		return b.fail("not started")
	}
	var err error
	if remove {
		err = s.MessageReactionRemove(channelID, messageID, emoji, "@me")
	} else {
		err = s.MessageReactionAdd(channelID, messageID, emoji)
	}
	if err != nil {
		return b.fail(err.Error())
	}
	bs, _ := json.Marshal(map[string]interface{}{"ok": true})
	return string(bs)
}

// errJSON is the {"error":...} shape the phone's image path expects (distinct from
// fail's {"dcError":...} used by the send/list methods).
func errJSON(msg string) string {
	bs, _ := json.Marshal(map[string]string{"error": msg})
	return string(bs)
}

// DownloadImageForWatch fetches the cached CDN url of picture mediaID (see pictures)
// and hands the bytes to the SHARED watchimage encoder (scale + palettize) so Discord
// images render identically to Google/WhatsApp/Telegram. Returns
// {"w","h","pixels","fmt","palette"} or {"error":...}. maxBytes>0 is the enhanced-view
// heap budget; ==0 is the small chat-preview box. The url was cached in buildOutMsg on
// fetch/live; a signed url that has expired 403s here and a thread reopen re-caches a
// fresh one.
func (b *Bridge) DownloadImageForWatch(mediaID string, maxW, maxH, maxBytes, quality int) string {
	b.mu.Lock()
	url := b.imageURL[mediaID]
	b.mu.Unlock()
	if url == "" {
		return errJSON("no stored image for " + mediaID)
	}
	client := &http.Client{Timeout: 60 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		return errJSON("download: " + err.Error())
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return errJSON(fmt.Sprintf("download: http %d", resp.StatusCode))
	}
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return errJSON("read: " + err.Error())
	}
	out, err := watchimage.EncodeImageForWatch(data, maxW, maxH, maxBytes, quality)
	if err != nil {
		return errJSON("encode: " + err.Error())
	}
	return out
}

// DownloadMediaForWatch is DownloadImageForWatch's unified twin: identical CDN
// fetch, but mediaroute decides from the BYTES whether to encode an animation or
// a still, and wraps the reply as {"kind","data"}. Only the fetch is Discord-
// specific; the decision is shared with every other bridge.
//
// Returns "" (not an error JSON) on any failure, because the caller reads empty
// as "fall back to the still path" — the right outcome for every problem here.
func (b *Bridge) DownloadMediaForWatch(mediaID string,
	maxW, maxH, maxBytes, quality, fpsFloor, maxFrames, gifBytes, startRung int) string {
	b.mu.Lock()
	url := b.imageURL[mediaID]
	b.mu.Unlock()
	if url == "" {
		return ""
	}
	client := &http.Client{Timeout: 60 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return ""
	}
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return ""
	}
	out, err := mediaroute.Encode(data, maxW, maxH, maxBytes, quality,
		fpsFloor, maxFrames, gifBytes, startRung)
	if err != nil {
		return ""
	}
	return out
}

// RecoverImage re-learns the CDN URLs of the pictures of the message mediaID belongs
// to, and those of the messages around it, from Discord: one REST call (up to 50
// messages). mediaID is a picture's media id (pictures: the message's id, or
// "<message id>:<attachment id>" for an album's later pictures). The URL cache is in
// memory only, so after a phone restart a chat shown from the phone's store asks for
// its pictures before the thread fetch has refilled it; and a cached URL whose ~24h
// signature has expired 403s. The phone calls this when a download fails, then tries
// once more.
//
// Calls are serialized; one for a message asked about within the last minute (by
// another call's batch, say) answers from the cache without a REST call. Returns
// whether mediaID's picture URL is known now.
func (b *Bridge) RecoverImage(conversationID, mediaID string) bool {
	channelID := strings.TrimPrefix(conversationID, convoIDPrefix)
	msgID, _, _ := strings.Cut(mediaID, ":")
	if channelID == conversationID || channelID == "" || snowflake(msgID) == 0 {
		return false
	}
	b.recoverMu.Lock()
	defer b.recoverMu.Unlock()
	b.mu.Lock()
	s := b.session
	at, asked := b.imageFresh[msgID]
	known := b.imageURL[mediaID] != ""
	b.mu.Unlock()
	if asked && time.Since(at) < time.Minute {
		return known
	}
	if s == nil {
		return false
	}
	msgs, err := s.ChannelMessages(channelID, 50, "", "", msgID)
	if err != nil {
		b.emitEvent("DIAG", "DC image recover failed: "+err.Error())
		return false
	}
	now := time.Now()
	n := 0
	b.mu.Lock()
	for _, m := range msgs {
		as, ids := pictures(m)
		for k, a := range as {
			b.imageURL[ids[k]] = attachmentURL(a)
		}
		if len(as) > 0 {
			b.imageFresh[m.ID] = now
		}
		n += len(as)
	}
	b.imageFresh[msgID] = now
	known = b.imageURL[mediaID] != ""
	b.mu.Unlock()
	b.emitEvent("DIAG", fmt.Sprintf("DC image recover: %d pictures relearned, asked one found=%v", n, known))
	return known
}

// maxAudioBytes refuses anything absurd before it's read in (the other bridges' cap).
const maxAudioBytes = 64 * 1024 * 1024

// DownloadMediaToFile writes a voice message's file (any sound attachment) to path,
// for the watch's voice-note player (step 86). mediaID is "<channel id>/<message id>"
// (buildOutMsg). The link is the one last seen; Discord signs links for about a day
// and they're kept in memory only, so a missing or refused one is re-learnt from
// Discord (the messages around it: one REST call) and tried once more. Returns the
// byte count, or -1 with a DIAG saying why.
func (b *Bridge) DownloadMediaToFile(mediaID, path string) int64 {
	channelID, msgID, ok := strings.Cut(mediaID, "/")
	if !ok || snowflake(channelID) == 0 || snowflake(msgID) == 0 {
		b.emitEvent("DIAG", "DC voice: not a voice message id")
		return -1
	}
	b.mu.Lock()
	url := b.audioURL[msgID]
	b.mu.Unlock()
	if url != "" {
		n, status := saveTo(url, path)
		if n > 0 {
			return n
		}
		if status != 403 && status != 404 && status != 410 {
			b.emitEvent("DIAG", fmt.Sprintf("DC voice: download failed (http %d)", status))
			return -1
		}
	}
	fresh := b.relearnAudio(channelID, msgID, url)
	if fresh == "" {
		b.emitEvent("DIAG", "DC voice: Discord has no file for it")
		return -1
	}
	n, status := saveTo(fresh, path)
	if n <= 0 {
		b.emitEvent("DIAG", fmt.Sprintf("DC voice: download failed after relearning (http %d)", status))
		return -1
	}
	return n
}

// relearnAudio asks Discord for the messages around msgID (one REST call, serialized
// with RecoverImage's) and keeps the fresh link of every sound attachment among them.
// Returns msgID's, or "". stale is the link that failed (or ""): one another call
// has replaced meanwhile is used without asking again.
func (b *Bridge) relearnAudio(channelID, msgID, stale string) string {
	b.recoverMu.Lock()
	defer b.recoverMu.Unlock()
	b.mu.Lock()
	s := b.session
	if u := b.audioURL[msgID]; u != "" && u != stale {
		b.mu.Unlock()
		return u
	}
	b.mu.Unlock()
	if s == nil {
		return ""
	}
	msgs, err := s.ChannelMessages(channelID, 50, "", "", msgID)
	if err != nil {
		b.emitEvent("DIAG", "DC voice relearn failed: "+err.Error())
		return ""
	}
	n := 0
	b.mu.Lock()
	for _, m := range msgs {
		if a := audioAttachment(m); a != nil {
			b.audioURL[m.ID] = attachmentURL(a)
			n++
		}
	}
	url := b.audioURL[msgID]
	if url == stale {
		url = ""
	}
	b.mu.Unlock()
	b.emitEvent("DIAG", fmt.Sprintf("DC voice relearn: %d relearned, asked one found=%v", n, url != ""))
	return url
}

// saveTo downloads url to path: the byte count (-1 on any failure) and the HTTP
// status (0 when there was none).
func saveTo(url, path string) (int64, int) {
	client := &http.Client{Timeout: 60 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		return -1, 0
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return -1, resp.StatusCode
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxAudioBytes+1))
	if err != nil || len(data) == 0 || len(data) > maxAudioBytes {
		return -1, resp.StatusCode
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return -1, resp.StatusCode
	}
	return int64(len(data)), resp.StatusCode
}

// GuildForChannel returns the guild id that owns a channel, or "" for a DM or an
// unknown channel. The phone uses it to set a server-channel force-open's back-out
// context to that server's channel list instead of the DMs bucket.
func (b *Bridge) GuildForChannel(channelID string) string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.channelGuild[channelID]
}

// ThreadLabel returns the "Server#channel" routing label for a channel — the same
// text buildOutMsg puts on notifications (guild name, then "#channel" when the
// channel name is cached). Empty for DMs (no guild mapping), so the phone falls
// back to just the network name for the watch's detail-view banner.
func (b *Bridge) ThreadLabel(channelID string) string {
	b.mu.Lock()
	defer b.mu.Unlock()
	gid := b.channelGuild[channelID]
	if gid == "" {
		return ""
	}
	label := b.guildName[gid]
	if cn := b.channelName[channelID]; cn != "" {
		label += "#" + cn
	}
	return label
}

// ListGuilds returns the full server list for the phone's server-picker panel.
func (b *Bridge) ListGuilds() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := outGuilds{Guilds: []outGuild{}}
	for _, id := range b.guildOrder {
		out.Guilds = append(out.Guilds, outGuild{ID: id, Name: b.guildName[id], Icon: b.guildIcon[id]})
	}
	bs, _ := json.Marshal(out)
	return string(bs)
}

// ---- Contact photos (step 90) -----------------------------------------------

// AvatarURL returns the address of the picture a conversation shows (the phone's
// notification, the watch's chat list): a DM's other person's avatar, a group DM's
// icon, a server channel's server icon; "guild:<id>" is the server itself. 256 px
// PNGs from Discord's CDN (an animated one gives its first frame); the address
// carries the image's hash, so it changes when the picture does. "" when there's
// none (a default avatar counts as none: the letter shows instead).
func (b *Bridge) AvatarURL(id string) string {
	b.mu.Lock()
	defer b.mu.Unlock()
	if gid, ok := strings.CutPrefix(id, "guild:"); ok {
		return guildIconURL(gid, b.guildIcon[gid])
	}
	ch := strings.TrimPrefix(id, convoIDPrefix)
	if u := b.dmAvatar[ch]; u != "" {
		return u
	}
	if gid := b.channelGuild[ch]; gid != "" {
		return guildIconURL(gid, b.guildIcon[gid])
	}
	return ""
}

const (
	discordCDN    = "https://cdn.discordapp.com/"
	avatarURLSize = ".png?size=256"
)

func userAvatarURL(u *discordgo.User) string {
	if u == nil || u.ID == "" || u.Avatar == "" {
		return ""
	}
	return discordCDN + "avatars/" + u.ID + "/" + u.Avatar + avatarURLSize
}

func guildIconURL(guildID, icon string) string {
	if guildID == "" || icon == "" {
		return ""
	}
	return discordCDN + "icons/" + guildID + "/" + icon + avatarURLSize
}

// dmAvatarURL: a group DM's icon, else the (first) other person's avatar.
func dmAvatarURL(ch *discordgo.Channel) string {
	if ch.Type == discordgo.ChannelTypeGroupDM {
		if ch.Icon == "" {
			return ""
		}
		return discordCDN + "channel-icons/" + ch.ID + "/" + ch.Icon + avatarURLSize
	}
	for _, r := range ch.Recipients {
		if r != nil {
			return userAvatarURL(r)
		}
	}
	return ""
}

// ListGuildChannels returns one guild's text channels as {"conversations":[...]},
// in display (Position) order, for when a server sub-account is selected.
func (b *Bridge) ListGuildChannels(guildID string) string {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := outConvos{Conversations: []outConvo{}}
	for _, chID := range b.guildChannels[guildID] {
		if b.forumChannels[chID] {
			// A forum: drill into its posts (ENTER), no "#" and no subtitle.
			out.Conversations = append(out.Conversations, outConvo{
				ConversationID: convoIDPrefix + chID,
				Name:           b.channelName[chID],
				Type:           "DISCORD",
				NavKind:        "ENTER",
				NavContext:     "forum:" + chID,
				Participants:   []outParticipant{{IsMe: false, ID: outID{Number: guildID}}},
			})
			continue
		}
		out.Conversations = append(out.Conversations, outConvo{
			ConversationID: convoIDPrefix + chID,
			Name:           "#" + b.channelName[chID],
			Type:           "DISCORD",
			LatestMessage:  outLatest{DisplayContent: b.lastText[chID]},
			Participants:   []outParticipant{{IsMe: false, ID: outID{Number: guildID}}},
		})
	}
	bs, _ := json.Marshal(out)
	return string(bs)
}

// ListForumThreads returns a forum channel's posts (threads) as {"conversations":[...]}
// OPEN chats, newest-first. Merges active posts (from the guild's active threads,
// filtered to this forum) with public archived posts (where most posts live once
// inactive). Each thread is itself a channel, so FetchMessages/SendMessage work on
// its id directly; we cache the thread's name/guild so those resolve.
func (b *Bridge) ListForumThreads(forumChannelID string) string {
	channelID := strings.TrimPrefix(forumChannelID, convoIDPrefix)
	b.mu.Lock()
	s := b.session
	guildID := b.channelGuild[channelID]
	b.mu.Unlock()
	out := outConvos{Conversations: []outConvo{}}
	if s == nil {
		bs, _ := json.Marshal(out)
		return string(bs)
	}
	seen := map[string]bool{}
	threads := []*discordgo.Channel{}
	if guildID != "" {
		if act, err := s.GuildThreadsActive(guildID); err == nil && act != nil {
			for _, t := range act.Threads {
				if t != nil && t.ParentID == channelID && !seen[t.ID] {
					seen[t.ID] = true
					threads = append(threads, t)
				}
			}
		}
	}
	if arch, err := s.ThreadsArchived(channelID, nil, 50); err == nil && arch != nil {
		for _, t := range arch.Threads {
			if t != nil && !seen[t.ID] {
				seen[t.ID] = true
				threads = append(threads, t)
			}
		}
	}
	// Newest post first (thread id snowflake ~ creation order).
	sort.SliceStable(threads, func(i, j int) bool {
		return snowflake(threads[i].ID) > snowflake(threads[j].ID)
	})
	if len(threads) > 50 {
		threads = threads[:50]
	}
	b.mu.Lock()
	for _, t := range threads {
		b.channelName[t.ID] = t.Name
		b.channelGuild[t.ID] = guildID
		out.Conversations = append(out.Conversations, outConvo{
			ConversationID: convoIDPrefix + t.ID,
			Name:           t.Name,
			Type:           "DISCORD",
			LatestMessage:  outLatest{DisplayContent: b.lastText[t.ID]},
			Participants:   []outParticipant{{IsMe: false, ID: outID{Number: guildID}}},
		})
	}
	b.mu.Unlock()
	bs, _ := json.Marshal(out)
	return string(bs)
}

func (b *Bridge) msgsErr(msg string) string {
	b.mu.Lock()
	b.lastErr = msg
	b.mu.Unlock()
	bs, _ := json.Marshal(outMsgs{Messages: []outMsg{}, DcError: msg})
	return string(bs)
}

// isPicture says whether attachment a is a raster image the shared encoder handles
// (jpeg/png/webp/gif — webp included because WhatsApp's webp stickers scale through
// the same watchimage encoder).
//
// Animated GIFs used to be excluded here, on the grounds that a [GIF] label read
// better than a frozen frame. The watch animates them now, so they go through
// like any other image: mediaroute decides from the BYTES whether a download is
// an animation or a still, and a single-frame GIF comes back as a still.
func isPicture(a *discordgo.MessageAttachment) bool {
	if a == nil {
		return false
	}
	switch a.ContentType {
	case "image/jpeg", "image/png", "image/webp", "image/gif":
		return true
	}
	return false
}

// pictures lists message m's pictures in the order they were attached, each with its
// media id: the message's id for the first (the id a picture has always had, so the
// pictures the phone stored before keep loading), "<message id>:<attachment id>" for
// the rest (step 88: every picture of a message goes to the watch, as an album).
// DownloadImageForWatch, DownloadMediaForWatch and RecoverImage key on these ids.
func pictures(m *discordgo.Message) (as []*discordgo.MessageAttachment, ids []string) {
	if m == nil {
		return nil, nil
	}
	for i, a := range m.Attachments {
		if !isPicture(a) {
			continue
		}
		id := m.ID
		if len(as) > 0 {
			if a.ID != "" {
				id = m.ID + ":" + a.ID
			} else {
				id = m.ID + ":" + strconv.Itoa(i)
			}
		}
		as = append(as, a)
		ids = append(ids, id)
	}
	return as, ids
}

// embedMedia returns the media Discord itself resolved for a LINK in the message,
// and whether it is a video. A GIF picked from a picker is a link, not an upload:
// Discord's own picker sends klipy.com/gifs/..., others tenor.com/view/... . The
// real client shows the GIF because Discord unfurls the link and hands back the
// file in the embed — so this reads the same embed rather than guessing from the
// url. Only embeds that actually carry a GIF or a short clip qualify; an article
// preview keeps its link text.
func embedMedia(m *discordgo.Message) (url string, isVideo bool) {
	if m == nil {
		return "", false
	}
	for _, e := range m.Embeds {
		if e == nil {
			continue
		}
		kind := strings.ToLower(string(e.Type))
		if kind != "gifv" && kind != "image" && kind != "video" {
			continue
		}
		if e.Image != nil {
			if u := firstURL(e.Image.URL, e.Image.ProxyURL); isGifURL(u) {
				return u, false
			}
		}
		if e.Thumbnail != nil {
			if u := firstURL(e.Thumbnail.URL, e.Thumbnail.ProxyURL); isGifURL(u) {
				return u, false
			}
		}
		if e.Video != nil {
			if u := firstURL(e.Video.URL, e.Video.ProxyURL); u != "" {
				// A picker's "GIF" is a short mp4 loop (kind "gifv", or any clip
				// from a GIF host). It reads as a GIF, so the phone loops it
				// instead of handing it to the video player.
				return u, !(kind == "gifv" || isGifHost(u))
			}
		}
		if e.Image != nil {
			if u := firstURL(e.Image.URL, e.Image.ProxyURL); u != "" {
				return u, false
			}
		}
	}
	return "", false
}

// embedPoster is the still Discord already made for a linked video (the embed's
// thumbnail), or "".
func embedPoster(m *discordgo.Message) string {
	for _, e := range m.Embeds {
		if e != nil && e.Thumbnail != nil {
			if u := firstURL(e.Thumbnail.URL, e.Thumbnail.ProxyURL); u != "" {
				return u
			}
		}
	}
	return ""
}

// embedSourceURL is the link the embed came from, so the message can drop it: the
// watch shows the picture, not a url nobody can read.
func embedSourceURL(m *discordgo.Message) string {
	for _, e := range m.Embeds {
		if e != nil && e.URL != "" {
			return e.URL
		}
	}
	return ""
}

func firstURL(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

// isGifHost spots the GIF pickers: their clips loop, whatever the file says.
func isGifHost(u string) bool {
	h := ""
	if i := strings.Index(u, "://"); i >= 0 {
		h = u[i+3:]
	} else {
		h = u
	}
	if i := strings.IndexAny(h, "/?#"); i >= 0 {
		h = h[:i]
	}
	h = strings.ToLower(strings.TrimPrefix(h, "www."))
	for _, s := range []string{"tenor.com", "klipy.com", "giphy.com", "gfycat.com"} {
		if h == s || strings.HasSuffix(h, "."+s) {
			return true
		}
	}
	return false
}

func isGifURL(u string) bool {
	if u == "" {
		return false
	}
	if i := strings.IndexAny(u, "?#"); i >= 0 {
		u = u[:i]
	}
	return strings.HasSuffix(strings.ToLower(u), ".gif")
}

// videoPoster asks Discord's media proxy for a video attachment's first frame as
// a JPEG — the same request Discord's own client makes for its video previews.
func videoPoster(a *discordgo.MessageAttachment) string {
	u := a.ProxyURL
	if u == "" {
		return ""
	}
	sep := "?"
	if strings.Contains(u, "?") {
		sep = "&"
	}
	return u + sep + "format=jpeg"
}

// audioAttachment returns the first sound attachment: a voice message (audio/ogg with
// a duration) or a sound file. The phone plays it on the watch (step 86).
func audioAttachment(m *discordgo.Message) *discordgo.MessageAttachment {
	if m == nil {
		return nil
	}
	for _, a := range m.Attachments {
		if a == nil {
			continue
		}
		if strings.HasPrefix(a.ContentType, "audio/") || (a.ContentType == "" && a.DurationSeconds > 0) {
			return a
		}
	}
	return nil
}

// attachmentURL prefers the canonical CDN url, falling back to the media proxy.
func attachmentURL(a *discordgo.MessageAttachment) string {
	if a == nil {
		return ""
	}
	if a.URL != "" {
		return a.URL
	}
	return a.ProxyURL
}

// mediaLabelOf is the bracket label for a message that carries media instead of
// text, in the same format the other bridges use: [Photo]/[Video]/[Voice]/[GIF]/
// [Sticker], plus [Link] for an embed-only (link-preview) message and the filename
// or [Attachment] for anything else. A voice message is an audio attachment with a
// non-zero duration. Images still return "[Photo]" here so the conversation-list
// snippet has text even though the message body serves the picture as bytes.
func mediaLabelOf(m *discordgo.Message) string {
	if m == nil {
		return ""
	}
	if len(m.StickerItems) > 0 {
		return "[Sticker]"
	}
	if len(m.Attachments) > 0 && m.Attachments[0] != nil {
		a := m.Attachments[0]
		switch {
		case a.ContentType == "image/gif":
			return "[GIF]"
		case strings.HasPrefix(a.ContentType, "image/"):
			return "[Photo]"
		case strings.HasPrefix(a.ContentType, "video/"):
			return "[Video]"
		case strings.HasPrefix(a.ContentType, "audio/") && a.DurationSeconds > 0:
			return "[Voice]"
		case a.Filename != "":
			return a.Filename
		default:
			return "[Attachment]"
		}
	}
	if len(m.Embeds) > 0 {
		return "[Link]"
	}
	return ""
}

// displayTextOf is the short preview text for a message: its content, or a media
// label when the content is empty. Used both for the message body fallback and for
// the conversation-list snippet cache.
func displayTextOf(m *discordgo.Message) string {
	if m == nil {
		return ""
	}
	if m.Content != "" {
		return m.Content
	}
	return mediaLabelOf(m)
}

// ---- message shaping (mirrors bridge_tg.go buildOutMsg) ---------------------

func (b *Bridge) buildOutMsg(m *discordgo.Message) outMsg {
	b.mu.Lock()
	self := b.selfID
	b.mu.Unlock()

	isMe := m.Author != nil && m.Author.ID == self

	// Media: every picture and video attachment becomes real mediaContent, in the
	// order they were attached (step 88): one is the message's media as it always
	// was; two or more, the phone shows as an album (one bubble in the chat, paged
	// through on the watch). A picture's bytes come through DiscordService.downloadImage
	// -> DownloadImageForWatch, so its URL is cached here under its media id
	// (pictures). A caption rides along as content; a caption-less message sends
	// empty content so the watch just shows the pictures. Without pictures or videos,
	// a voice message or a linked GIF / clip is the media (below). Every other kind
	// stays a text label ([Sticker]/[Link]/filename), applied only when there's no
	// text and no media.
	content := b.resolveMentions(m, m.Content)
	var mcs []*outMediaContent
	picAs, picIDs := pictures(m)
	picID := make(map[*discordgo.MessageAttachment]string, len(picAs))
	for k, a := range picAs {
		picID[a] = picIDs[k]
	}
	b.mu.Lock()
	for _, a := range m.Attachments {
		if a == nil {
			continue
		}
		if id, ok := picID[a]; ok {
			b.imageURL[id] = attachmentURL(a)
			mcs = append(mcs, &outMediaContent{Format: "IMAGE", MimeType: a.ContentType, MediaID: id})
		} else if strings.HasPrefix(a.ContentType, "video/") {
			// A video's media id IS its CDN url: the phone downloads the file by url
			// for its own decoder, so there's no bridge round-trip to arrange (and the
			// same phone-side path already handles a clip that arrived as a link).
			// Discord signs these urls and they expire; a stale one simply fails to
			// download and a thread reopen re-caches a fresh one, exactly as for images.
			mcs = append(mcs, &outMediaContent{Format: "VIDEO", MimeType: a.ContentType, MediaID: attachmentURL(a),
				PosterMediaID: videoPoster(a)})
		}
	}
	b.mu.Unlock()
	if len(mcs) == 0 {
		if a := audioAttachment(m); a != nil {
			// A voice message (or a sound file), played on the watch's speaker (step 86).
			// Its media id is "<channel>/<message>": the phone fetches it through
			// DownloadMediaToFile, which re-learns an expired link (the phone keeps a
			// message's first JSON, so a link here would go stale after a day). Its label
			// ("[Voice]", or a file's name) stays as the text, for the chat list and
			// notifications; the phone shows the voice card instead of a bare label.
			b.mu.Lock()
			b.audioURL[m.ID] = attachmentURL(a)
			b.mu.Unlock()
			mime := a.ContentType
			if mime == "" {
				mime = "audio/ogg"
			}
			mcs = append(mcs, &outMediaContent{Format: "AUDIO", MimeType: mime, MediaID: m.ChannelID + "/" + m.ID})
			if content == "" {
				content = mediaLabelOf(m)
			}
		} else if u, isVideo := embedMedia(m); u != "" {
			// A linked GIF / clip: the url is the media id, and the phone fetches it.
			if isVideo {
				mcs = append(mcs, &outMediaContent{Format: "VIDEO", MimeType: "video/mp4", MediaID: u,
					PosterMediaID: embedPoster(m)})
			} else {
				mcs = append(mcs, &outMediaContent{Format: "IMAGE", MimeType: "image/gif", MediaID: u})
			}
			if src := embedSourceURL(m); src != "" {
				content = strings.TrimSpace(strings.Replace(content, src, "", 1))
			}
		}
	}
	if content == "" && len(mcs) == 0 {
		content = mediaLabelOf(m)
	}
	// The text and the first medium share the first entry (as a single one always
	// has); each further picture or video is an entry of its own.
	infos := []outMsgInfo{{MessageContent: outContent{Content: content}}}
	if len(mcs) > 0 {
		infos[0].MediaContent = mcs[0]
		for _, mc := range mcs[1:] {
			infos = append(infos, outMsgInfo{MediaContent: mc})
		}
	}

	status := "INCOMING_COMPLETE"
	if isMe {
		status = "OUTGOING_DELIVERED"
	}

	senderNum, senderName := "", "Discord"
	if m.Author != nil {
		senderName = bestName(m.Author)
		if !isMe {
			senderNum = m.Author.ID
		}
	}

	om := outMsg{
		ConversationID: convoIDPrefix + m.ChannelID,
		MessageID:      m.ID,
		Timestamp:      fmt.Sprintf("%d", m.Timestamp.UnixMicro()),
		SenderParticipant: outSender{
			IsMe:      isMe,
			FirstName: senderName,
			FullName:  senderName,
			ID:        outID{Number: senderNum},
		},
		MessageStatus: outStatus{Status: status},
		MessageInfo:   infos,
		IsMe:          isMe,
		FromMe:        isMe,
	}

	// Inline reactions: unicode only (skip custom emoji, which have a non-empty id
	// and no Pebble glyph). Present => the store adopts this set on load / on any
	// new message in the thread.
	if len(m.Reactions) > 0 {
		rs := []outReaction{}
		for _, r := range m.Reactions {
			if r == nil || r.Emoji == nil {
				continue
			}
			if r.Emoji.ID != "" || r.Emoji.Name == "" {
				continue // custom / unnamed
			}
			rs = append(rs, outReaction{Emoji: r.Emoji.Name, Count: r.Count})
		}
		if len(rs) > 0 {
			om.Reactions = &rs
		}
	}

	// Reply-to: carry the quoted message's id so the watch composes the "replied to
	// X" header. The renderer resolves the id against the thread it holds — the same
	// shape Telegram emits, so no watch-side change is needed.
	if m.MessageReference != nil && m.MessageReference.MessageID != "" {
		om.ReplyTo = &outReplyTo{ID: m.MessageReference.MessageID}
	}

	// Server (guild) name for the notification's top line; empty for DMs. Prefer the
	// message's own GuildID (set on live gateway events); fall back to the channel->
	// guild map for REST-fetched messages that may omit it.
	b.mu.Lock()
	gid := m.GuildID
	if gid == "" {
		gid = b.channelGuild[m.ChannelID]
	}
	if gid != "" {
		om.GroupName = b.guildName[gid]
		// Append the channel so the notification reads "Server#channel" (e.g.
		// Discord→BONELAB#art). If the channel isn't cached (e.g. an uncached
		// thread), fall back to just the server name.
		if cn := b.channelName[m.ChannelID]; cn != "" {
			om.GroupName += "#" + cn
		}
	}
	b.mu.Unlock()

	// Silent when the user's Discord settings say so (muted guild/channel, "nothing",
	// or "only mentions" without a mention) — the phone still stores it, just no alert.
	om.Silent = b.silentFor(m)
	return om
}

// ---- helpers ----------------------------------------------------------------

// snowflake parses a Discord snowflake id into its numeric value for ordering.
// Empty/invalid ids (e.g. a DM that has never had a message) sort as 0 = oldest.
func snowflake(id string) uint64 {
	n, _ := strconv.ParseUint(id, 10, 64)
	return n
}

// resolveMentions rewrites Discord's raw mention tokens in message text into the
// readable names its own client shows: "<@id>"/"<@!id>" -> "@DisplayName",
// "<#channelID>" -> "#channel-name", "<@&roleID>" -> "@role-name". Without this the
// watch shows the raw "<@1385699117723947031>" token (a bare snowflake) instead of
// "@Sweet Gherkins", since the watch renders content verbatim. The names come from
// data already on the message: m.Mentions (full user objects) and m.MentionRoles,
// plus the bridge's channelName map. Unknown ids are left as-is (rare; a name we
// never learned), which is still no worse than today.
func (b *Bridge) resolveMentions(m *discordgo.Message, content string) string {
	if content == "" || !strings.Contains(content, "<") {
		return content
	}
	repl := make([]string, 0, 16)
	// User mentions: both "<@id>" and the legacy nickname form "<@!id>" map to the
	// same user. m.Mentions carries the resolved user object for each.
	for _, u := range m.Mentions {
		if u == nil || u.ID == "" {
			continue
		}
		name := "@" + bestName(u)
		repl = append(repl, "<@"+u.ID+">", name, "<@!"+u.ID+">", name)
	}
	// Channel mentions: name from the bridge's channelID->name cache.
	b.mu.Lock()
	for id, name := range b.channelName {
		tok := "<#" + id + ">"
		if name != "" && strings.Contains(content, tok) {
			repl = append(repl, tok, "#"+name)
		}
	}
	b.mu.Unlock()
	// NOTE: role mentions ("<@&roleID>") are intentionally left unresolved for now.
	// Resolving them needs the guild role list, and this fork's State/role API is
	// unverified here — a wrong call would fail the whole gomobile build. User and
	// channel mentions (the common cases, and the reported bug) are covered above.
	// Add roles later once State.Role's signature is confirmed against the vendored
	// discordgo, if role mentions turn out to leak in practice.
	if len(repl) == 0 {
		return content
	}
	return strings.NewReplacer(repl...).Replace(content)
}

func bestName(u *discordgo.User) string {
	if u == nil {
		return "Discord"
	}
	if u.GlobalName != "" {
		return u.GlobalName
	}
	if u.Username != "" {
		return u.Username
	}
	return "Discord"
}

func primaryRecipient(ch *discordgo.Channel) string {
	for _, r := range ch.Recipients {
		if r != nil {
			return r.ID
		}
	}
	return ""
}

func dmDisplayName(ch *discordgo.Channel) string {
	if ch.Type == discordgo.ChannelTypeGroupDM {
		if ch.Name != "" {
			return ch.Name
		}
		names := make([]string, 0, len(ch.Recipients))
		for _, r := range ch.Recipients {
			names = append(names, bestName(r))
		}
		if len(names) > 0 {
			return strings.Join(names, ", ")
		}
		return "Group"
	}
	for _, r := range ch.Recipients {
		if r != nil {
			return bestName(r)
		}
	}
	return "Direct Message"
}
