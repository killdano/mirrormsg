package com.dano.mirrormsg

import android.app.Activity
import android.content.Context

/**
 * MessagingService is the contract every messaging network implements. It is the
 * single abstraction the CORE of the app depends on — MainActivity, Mirror,
 * PebbleListenerService, ReplyReceiver talk to *this*, never to a concrete
 * network class or a bridge AAR.
 *
 * That decoupling is what makes networks drop-in/drop-out modular: a network is
 * a class implementing this interface plus its own bridge AAR. The core never
 * names Google or Facebook directly, so adding or removing a network's files
 * never touches (or breaks) the core. Discovery happens at runtime via
 * [ServiceRegistry], which finds implementations by reflection — present class
 * → network available; absent class → network simply isn't there.
 *
 * GoogleMessagesService is the first implementation (wrapping the existing
 * libgm/mirrorbridge AAR). FacebookMessengerService is the second (wrapping the
 * messagix/fbmessagebridge AAR), and is a true drop-out pair with its AAR.
 *
 * Design notes:
 *  - The data methods ([listConversations], [fetchMessages], [sendMessage],
 *    [downloadImage], [processInlineImage]) are the UNIVERSAL read/send surface.
 *    Their return shapes are JSON strings, exactly as the bridges already
 *    produce, so the existing parsing in PebbleListenerService is unchanged.
 *  - [sendMessage] takes an OPAQUE replyToken string rather than network-specific
 *    routing args. Google packs SIM routing (participantID/simTwo/simNumber) into
 *    it and unpacks it inside its adapter; Facebook ignores it. The core and the
 *    watch only ever echo the token back — they never interpret it. One signature
 *    serves every network.
 *  - [startLogin] is per-network: Google opens the cookie WebView + Gaia pairing;
 *    Facebook opens its own cookie harvest. The core just calls startLogin and
 *    lets the service do whatever that network needs.
 */
interface MessagingService {

    // ---- Identity & state --------------------------------------------------

    /** Stable lowercase id, e.g. "google", "facebook". Used as the service tag. */
    val id: String

    /** Human-facing name shown in the Networks tab, e.g. "Google Messages". */
    val displayName: String

    /** True if this network has a valid, usable session right now. */
    fun isLoggedIn(): Boolean

    /**
     * Mutual-exclusion key for networks that exist via BOTH a native bridge and a
     * Beeper bridge. Such twins declare the SAME non-null group on both sides
     * (e.g. native Facebook and Beeper Facebook both return "facebook");
     * [ServiceRegistry.conflictingActive] then forbids turning both on at once,
     * since two clients on one account fight over the connection. Default null
     * means "no twin, no conflict." Keyed on this stable string, never on
     * [displayName], so a cosmetic rename can't silently break the safety rule.
     */
    val conflictGroup: String? get() = null

    /**
     * Whether this service counts as "on" for [conflictGroup] mutual exclusion.
     * Defaults to [isLoggedIn] — a native service is active when it holds a
     * session. Beeper services override this to read their enabled state
     * (BeeperState) instead, so enabling a Beeper account blocks its native twin
     * immediately, before any sync. Callers wrap this defensively.
     */
    fun isActive(ctx: Context): Boolean = isLoggedIn()

    /**
     * The reaction actorKey representing THIS user on this network, if known — e.g.
     * the logged-in Facebook fbid. Used so a reaction the user sends from the watch
     * is stored optimistically under the SAME key the network's own reaction events
     * carry, letting a later change/removal from the native app reconcile it. Null
     * (the default) means "unknown" and callers fall back to a synthetic key.
     */
    fun selfActorKey(): String? = null

    /**
     * Short label for the account subtitle (phone number for Google, the FB
     * display name/handle for Facebook). Empty string if unknown yet.
     */
    fun accountLabel(): String

    // ---- Avatar color (per-account brand family) ---------------------------

    /**
     * This account's avatar background palette as "#RRGGBB" hex strings — the
     * account's brand-color family (Facebook blues, Discord blurples, X blacks,
     * ...). The watch fills each conversation's avatar circle with one of these
     * and auto-picks a readable letter color, so ONLY the background is defined
     * here.
     *
     * Declared in the ACCOUNT'S OWN file (the single per-account part), so a
     * network's colors travel with it — drop the network's file and its colors
     * go too, with nothing left behind in shared code. Empty (the default) means
     * "no brand palette": the pipeline then uses any bridge-provided color (e.g.
     * Google's per-contact colors, darkened at the source) or the global default.
     * Keep entries under ~140 luma if you want white initials (the branded look).
     */
    val avatarPalette: List<String> get() = emptyList()

    /**
     * Pick a STABLE avatar color for one conversation out of [avatarPalette], so
     * a given thread always gets the same shade while different threads in the
     * account still vary. This is the SHARED selection engine — every network
     * reuses it; networks define the palette, never this. Returns null when the
     * palette is empty (the pipeline then defers to bridge color / default).
     */
    fun avatarColorFor(conversationKey: String): String? {
        val pal = avatarPalette
        if (pal.isEmpty()) return null
        val h = conversationKey.hashCode().toLong() and 0xFFFFFFFFL
        return pal[(h % pal.size).toInt()]
    }

    // ---- Lifecycle ---------------------------------------------------------

    /**
     * Restore any saved session and bring the network online (open the realtime
     * channel / long-poll). Safe to call on a background thread; may block.
     * No-op if already connected. Returns true if connected/usable afterward.
     */
    fun connectIfPossible(): Boolean

    /** Tear down the live connection (but keep the saved session). */
    fun disconnect()

    /** Clear the saved session/cookies and fully sign out of this network. */
    fun logout(context: Context)

    // ---- Login (per-network) ----------------------------------------------

    /**
     * Begin this network's login flow. Each network does its own thing —
     * Google opens the cookie WebView and Gaia pairing; Facebook opens its own
     * cookie harvest. The core just calls this; it does not know the mechanism.
     */
    fun startLogin(activity: Activity)

    // ---- Universal data surface (JSON in/out, as the bridges produce) ------

    /** Inbox conversations as JSON (same shape the bridge returns). */
    fun listConversations(count: Int): String

    /**
     * True if this service navigates a hierarchy (buckets/servers/folders) rather
     * than a flat inbox. Flat services (the default) stream one OPEN list; the watch
     * treats every entry as a chat. Hierarchical services override [listContext].
     */
    val hierarchical: Boolean get() = false

    /**
     * Conversations for a navigation context — the list the watch should show for the
     * current drill path. [context] is the path from the account root (empty = root).
     * Default: flat — the account's inbox, ignoring context. Hierarchical services
     * override to return a mix of ENTER entries (buckets/servers: JSON field
     * "navKind":"ENTER" + "navContext":"<token>") and OPEN chats (no navKind).
     */
    fun listContext(count: Int, context: List<String>): String = listConversations(count)

    /**
     * The nav-context stack a force-open (notification / "Open on watch") should land
     * in, so the streamed list CONTAINS the target and BACK climbs coherently. Default:
     * a hierarchical account drops into its "dms" bucket; a flat account stays at root.
     * A network with deeper structure (e.g. Discord servers) overrides this to return
     * the target's real home — its server's channel list — so back-out lands there.
     */
    fun forceOpenNavContext(convoId: String): List<String> =
        if (hierarchical) listOf("dms") else emptyList()

    /**
     * Routing-context label for the watch's thread detail-view banner. Default: just
     * [displayName] (the network name). Drillable networks (Discord servers/channels)
     * override to return "<network>\n<server>#<channel>"; the watch treats the presence
     * of a '\n' as the two-line-banner signal, so no separate "is drillable" flag is
     * needed. Flat accounts (DMs, Google, WhatsApp, Telegram DMs) keep the single line.
     */
    fun threadLabel(convoId: String): String = displayName

    /** Messages for a conversation as JSON. cursor "" = most recent. */
    fun fetchMessages(conversationId: String, count: Long, cursor: String): String

    // ---- New chat (the watch's people screen, People.kt) ---------------------

    /** True when this account can start a chat with someone it has no chat with yet. */
    val supportsNewChat: Boolean get() = false

    /**
     * The people this account can start a chat with, as JSON
     * {"people":[{"id","name","detail","rank","chat"}]}: [id] is what [openPerson]
     * takes, detail a short line under the name (a number, a username), rank 1 for the
     * one written to most (0 or absent: not known), chat the id of the chat with them
     * when the network knows there is one. Called off the main thread; may take a
     * network round trip.
     */
    fun listPeople(): String = "{\"people\":[]}"

    /**
     * The chat with person [personId] (from [listPeople]): theirs if there is one,
     * else a new one, made on the network now or by its first send. Its list entry as
     * JSON, the conversationID at least (and whatever [sendMessage] routes by, the way
     * [listConversations] gives it); "" or JSON with "error" when it can't.
     */
    fun openPerson(personId: String): String = ""

    /**
     * Send a text message. [replyToken] is opaque routing the service itself
     * produced (e.g. via [replyTokenFor]) and knows how to interpret; the core
     * passes it back untouched. Returns the bridge's JSON response.
     */
    fun sendMessage(conversationId: String, text: String, replyToken: String): String

    /**
     * Produce the opaque reply token for a conversation, to be stored and later
     * handed back to [sendMessage]. Google encodes SIM routing here; Facebook
     * can return "" since it needs no routing. Keeps all network-specific reply
     * routing inside the adapter.
     */
    fun replyTokenFor(conversationId: String): String

    // ---- Targeted send: reply-to & reaction (OPT-IN) -----------------------

    /**
     * Whether this service can send a REPLY targeted at a specific message (a
     * quoted reply), rather than a plain message to the conversation. Default
     * false — a service opts in only if its bridge can do it. When false, a
     * detail-view reply falls back to [sendMessage], so the network keeps working
     * exactly as before. This is the whole "opt-in" mechanism: capability lives
     * with the service, the core gates behavior on it.
     */
    val supportsReplyTo: Boolean get() = false

    /**
     * Whether this service can send a REACTION to a specific message. Default
     * false — opt in only if supported. When false, an emoji-only detail-view
     * reply falls back to a normal [sendMessage] containing the emoji.
     */
    val supportsReactionSend: Boolean get() = false

    /**
     * Whether this service can mark a conversation read on the SOURCE network —
     * propagating the read back to e.g. Google Messages, not merely clearing the
     * local notification. Default false; opt in only when [markRead] is actually
     * implemented. The notification's "Mark as read" action is added ONLY for
     * services that return true, so the button never appears where it does nothing.
     */
    val supportsMarkRead: Boolean get() = false

    /**
     * Send a quoted reply to [targetMessageId] within [conversationId]. Only
     * called when [supportsReplyTo] is true. [targetFromMe] is whether the message
     * being replied to was sent by us (some networks fold it into the target's
     * addressing). Returns the bridge's JSON response. Default throws — declaring
     * support without implementing is a bug.
     */
    fun sendReplyTo(
        conversationId: String, targetMessageId: String,
        targetFromMe: Boolean, text: String
    ): String = throw UnsupportedOperationException("$id does not support reply-to")

    /**
     * Send reaction [emoji] to [targetMessageId] within [conversationId] (an empty
     * emoji removes it). Only called when [supportsReactionSend] is true.
     * [targetFromMe] is whether the target message was sent by us. Returns the
     * bridge's JSON response. Default throws.
     */
    fun sendReaction(
        conversationId: String, targetMessageId: String,
        targetFromMe: Boolean, emoji: String
    ): String = throw UnsupportedOperationException("$id does not support reaction send")

    /**
     * Mark [conversationId] read UP THROUGH [messageId] on the source network
     * (a real read receipt, so the thread's unread state clears on the network
     * itself — not just a dismissed notification). Only called when
     * [supportsMarkRead] is true. Returns the bridge's JSON response. Default
     * throws — declaring support without implementing is a bug.
     */
    fun markRead(conversationId: String, messageId: String): String =
        throw UnsupportedOperationException("$id does not support mark-read")

    /**
     * True when opening [conversationId] on the watch should wait for its catch-up
     * fetch instead of first showing what's stored (default false). For a list
     * that is always behind when opened (X's Following feed), showing it first
     * means it grows under the reader, and every row the watch then names by
     * position (to reply to, react to, open) points somewhere else.
     */
    fun catchUpBeforeShowing(conversationId: String): Boolean = false

    /**
     * The text the watch's detail view shows for a message it opened: by default
     * the message's own [fullText]. A network may add to it on demand (X appends a
     * post's top replies). Called off the main thread; may block on the network.
     */
    fun detailText(conversationId: String, messageId: String, fullText: String): String = fullText

    /** Download+scale a media image for the watch. Returns the bridge's JSON. */
    fun downloadImage(
        mediaId: String, decryptionKeyB64: String,
        maxW: Int, maxH: Int, maxBytes: Int, quality: Int
    ): String

    /** Process an inline (already-embedded) image for the watch. Returns JSON. */
    fun processInlineImage(
        mediaDataB64: String,
        maxW: Int, maxH: Int, maxBytes: Int, quality: Int
    ): String

    /**
     * Download media ONCE and encode it as whichever it turns out to be:
     *
     *     {"kind":"gif","data":{...}}  or  {"kind":"image","data":{...}}
     *
     * Nothing can tell an animated GIF from a still without the bytes, and the
     * bytes cost a network round-trip — so asking first and downloading second
     * would mean fetching twice. This asks once and reports what arrived.
     *
     * DEFAULTS TO "" so a bridge that hasn't implemented it simply falls through
     * to the existing still-image path. Networks light up one at a time as their
     * bridges gain the method, instead of the caller needing to know which are
     * ready.
     *
     * gifBytes bounds an ANIMATION specifically (0 = use maxBytes). startRung
     * skips that many rungs of the encoder's degradation ladder — the watch
     * bumps it after refusing one, so each retry is strictly smaller than the
     * attempt it rejected.
     */
    fun downloadMediaForWatch(
        mediaId: String, decryptionKeyB64: String,
        maxW: Int, maxH: Int, maxBytes: Int, quality: Int,
        fpsFloor: Int, maxFrames: Int, gifBytes: Int, startRung: Int
    ): String = ""

    /**
     * Download and decrypt a message's media (a video) to [path] for the phone's
     * own decoder (video streaming). Returns the byte count, or -1 when this
     * network can't (the default).
     */
    fun downloadMediaToFile(mediaId: String, decryptionKeyB64: String, path: String): Long = -1L

    /** Inline twin of [downloadMediaForWatch]; same contract, same default. */
    fun processInlineMediaForWatch(
        mediaDataB64: String,
        maxW: Int, maxH: Int, maxBytes: Int, quality: Int,
        fpsFloor: Int, maxFrames: Int, gifBytes: Int, startRung: Int
    ): String = ""

    /**
     * True if this message's media is an ANIMATION rather than a still.
     *
     * Judged from the message JSON's mime type, so it is metadata-cheap and
     * available at thread-push time — no download required. That makes it
     * suitable for telling the watch which rows animate (so they can show a
     * motion icon rather than a photo icon, and eventually animate their
     * thumbnails). It is a hint, not proof: a mislabelled file will be corrected
     * when the bytes are actually fetched and IsAnimatedGif disagrees.
     */
    fun messageIsAnimated(messageJson: String): Boolean {
        return messageJson.contains("\"image/gif\"", ignoreCase = true)
    }

    // ---- Contact photos (OPT-IN) ---------------------------------------------

    /**
     * The photo a conversation shows: in the watch's chat list (the row's avatar,
     * cut to its circle) and as the picture of its notifications. Returns an id that
     * names the photo and changes when the photo does (an address carrying the
     * image's own hash, say), or null for none: the DEFAULT, which keeps the coloured
     * letter, so a network lights up only once it implements this and [avatarBytes].
     *
     * [row] is the conversation's JSON as [listConversations] / [listContext] gave it,
     * when asked for a row of the chat list; null when asked for a notification (a
     * network may answer differently: Discord's server channels keep their letters in
     * the list but show the server's icon on notifications). With a row it must be
     * cheap (no network); with null it's asked off the main thread and may block.
     */
    fun avatarId(conversationId: String, row: org.json.JSONObject?): String? = null

    /**
     * The photo [avatarId] names, as image bytes (JPEG, PNG or WebP), or null when
     * there is none. THROW when it can't be had right now (a timeout, an error page):
     * it's asked again later, where null is remembered for a day. Called off the main
     * thread; may block on the network. Kept on the phone for days, so asked rarely.
     */
    fun avatarBytes(avatarId: String): ByteArray? = null
}
