package com.dano.mirrormsg

/**
 * Message card hand-off to MirrorMap Dash: the names, numbers and decisions of the
 * exchange. Plain Kotlin (no Android types) so the JVM unit tests cover it; [MapCard]
 * is the Android half (the bound service, the Messenger, the timeouts).
 *
 * When a new message would force-open the MirrorMsg watchapp, MirrorMsg first asks
 * Dash's bound service ([ACTION_MSG_CARD]) to show it as a small card inside the map
 * watchapp on screen. Dash answers with a [MSG_RESULT]; later it reports what the user
 * did with the card ([MSG_EVENT]). Only the sender's display name and the message text
 * go to Dash, to be drawn on the card; chat ids stay here, behind a token.
 */
object MapCardRules {
    const val ACTION_MSG_CARD = "com.mirrormap.dash.action.MSG_CARD"

    // Message.what
    const val MSG_SHOW = 1       // MirrorMsg -> Dash: show a card
    const val MSG_RESULT = 2     // Dash -> replyTo: the answer to a SHOW
    const val MSG_EVENT = 3      // Dash -> replyTo: what the user did with a card
    const val MSG_RETURN = 4     // MirrorMsg -> Dash: relaunch that map app
    const val MSG_RETURNED = 5   // Dash -> replyTo: the launch was handed on

    // Message.data fields
    const val KEY_TOKEN = "token"
    const val KEY_SENDER = "sender"
    const val KEY_TEXT = "text"
    const val KEY_COLOR = "color"
    const val KEY_BUZZ = "buzz"
    const val KEY_RESULT = "result"
    const val KEY_APP = "app"
    const val KEY_EVENT = "event"
    const val KEY_OK = "ok"

    // RESULT values (Dash's)
    const val RESULT_SHOWN = "shown"
    const val RESULT_QUEUED = "queued"
    const val RESULT_NOT_SHOWN = "not_shown"
    const val RESULT_NOT_OPEN = "not_open"
    const val RESULT_NOT_ALLOWED = "not_allowed"
    const val RESULT_BAD_REQUEST = "bad_request"
    const val RESULT_FAILED = "failed"
    // MirrorMsg's own, never sent by Dash: no answer in time, or no service to ask.
    const val RESULT_TIMEOUT = "timeout"
    const val RESULT_UNAVAILABLE = "unavailable"

    // EVENT values
    const val EVENT_OPENED = "opened"
    const val EVENT_DISMISSED = "dismissed"
    const val EVENT_TIMEOUT = "timeout"
    const val EVENT_DROPPED = "dropped"

    // The map apps a card can be on.
    const val APP_MIRRORMAP = "mirrormap"
    const val APP_MIRRORMOV = "mirrormov"

    /** The whole SHOW exchange: bind, send, Dash's own 1.5 s wait for the watch. */
    const val SHOW_TIMEOUT_MS = 4000L
    /** The RETURN exchange: bind, send, Dash hands the launch to the Pebble app. */
    const val RETURN_TIMEOUT_MS = 4000L
    /** A "back to the map when this chat closes" mark lasts this long. */
    const val MARK_TTL_MS = 30L * 60 * 1000
    /** Tokens remembered, so an event for an older card still finds its chat. */
    const val MAX_TOKENS = 8
    /** Characters handed to Dash at most (it clips to the watch's bytes itself). */
    const val SENDER_MAX_CHARS = 64
    const val TEXT_MAX_CHARS = 400

    /** What MirrorMsg does after Dash's answer to a SHOW. */
    enum class Action {
        /** The card is up, or will be: nothing more. */
        DONE,
        /** The map app is on screen but can't show cards: the Pebble notification instead. */
        NOTIFY,
        /** Anything else (no map app, refused, failed, no answer): force-open as before. */
        FORCE_OPEN,
    }

    fun actionFor(result: String?): Action = when (result) {
        RESULT_SHOWN, RESULT_QUEUED -> Action.DONE
        RESULT_NOT_SHOWN -> Action.NOTIFY
        else -> Action.FORCE_OPEN
    }

    /** One of the map apps a card can be on (the only values RETURN may name). */
    fun validApp(app: String?): Boolean = app == APP_MIRRORMAP || app == APP_MIRRORMOV

    /** A value from Dash fit for a log line: a short lowercase word, else "?". */
    fun logWord(s: String?): String =
        s?.takeIf { it.isNotEmpty() && it.length <= 16 && it.all { c -> c in 'a'..'z' || c == '_' } } ?: "?"

    /** [s] cut to [max] characters, never splitting a surrogate pair. */
    fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = max
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end)
    }

    /**
     * The card box's colour for a network (its ServiceRegistry id), as a GColor8 argb
     * byte (0b11rrggbb, 2 bits per channel), or 0 when unknown (the card's default blue).
     * Native and Beeper paths to one network share its colour. Each is the network's
     * brand colour brought to the watch's 64 colours, nudged where two blues would
     * otherwise land on the same one; all are dark enough for the white initial.
     */
    fun colorFor(serviceId: String?): Int = when (serviceId) {
        "google" -> 0xC7                         // Google Messages blue (0,85,255)
        "facebook", "fbmessenger" -> 0xE7        // Messenger violet (170,85,255)
        "whatsapp", "wabeeper" -> 0xC9           // WhatsApp green (0,170,85)
        "tgnative", "tgbeeper" -> 0xCB           // Telegram blue (0,170,255)
        "discord", "dcbeeper" -> 0xD7            // Discord blurple (85,85,255)
        "signalnative", "signal" -> 0xC3         // Signal ultramarine (0,0,255)
        "instagram", "igbeeper" -> 0xF1          // Instagram pink (255,0,85)
        "xnative", "x" -> 0xC0                   // X black
        "imessage" -> 0xD9                       // iMessage green (85,170,85)
        "linkedin" -> 0xC6                       // LinkedIn blue (0,85,170)
        "slack" -> 0xD1                          // Slack aubergine (85,0,85)
        "googlechat" -> 0xC8                     // Google Chat green (0,170,0)
        "line" -> 0xC9                           // LINE green (0,170,85)
        else -> 0
    }
}

/**
 * The last [max] cards' tokens and the chat each one stands for, so an event from Dash
 * (which knows only the token) finds its chat. Tokens are positive and count up; past
 * Int.MAX_VALUE they start again at 1. Thread-safe.
 */
class CardTokens(private val max: Int = MapCardRules.MAX_TOKENS, start: Int = 0) {
    class Entry(val convoId: String, val ownerServiceId: String) {
        /** The map app Dash said the card went to (from its RESULT), if it said. */
        @Volatile var app: String? = null
        /** The card was opened (guarded by the table's lock). */
        var opened: Boolean = false
        // The chat id never in a log line.
        override fun toString(): String = "Entry(owner=$ownerServiceId, app=$app)"
    }

    private var last = start
    private val entries = LinkedHashMap<Int, Entry>()

    /** A new token for a card about [convoId] of [ownerServiceId]. */
    @Synchronized fun add(convoId: String, ownerServiceId: String): Int {
        last = if (last <= 0 || last == Int.MAX_VALUE) 1 else last + 1
        entries.remove(last)
        entries[last] = Entry(convoId, ownerServiceId)
        while (entries.size > max) entries.remove(entries.keys.first())
        return last
    }

    @Synchronized fun get(token: Int): Entry? = if (token > 0) entries[token] else null

    /** [token]'s entry the first time its card is opened; null after that, or when unknown. */
    @Synchronized fun firstOpen(token: Int): Entry? {
        val e = get(token) ?: return null
        if (e.opened) return null
        e.opened = true
        return e
    }

    @Synchronized fun size(): Int = entries.size
}

/**
 * "Back to [app] when chat [convoId] closes": set when the chat is opened from a card,
 * for [MapCardRules.MARK_TTL_MS]. Times are a monotonic clock's milliseconds.
 */
class ReturnMark(val convoId: String, val app: String, val atMs: Long) {
    fun liveAt(now: Long): Boolean = now >= atMs && now - atMs < MapCardRules.MARK_TTL_MS
    override fun toString(): String = "ReturnMark(app=$app)"
}

/** The one mark there can be. Thread-safe. */
class ReturnMarkSlot {
    private var mark: ReturnMark? = null

    @Synchronized fun set(m: ReturnMark) { mark = m }

    @Synchronized fun peek(): ReturnMark? = mark

    /**
     * The mark, cleared, when the chat that just closed ([closedConvoId], the chat the
     * phone had open) is the marked one and the mark hasn't expired; else null (an
     * expired mark is dropped too).
     */
    @Synchronized fun take(closedConvoId: String?, now: Long): ReturnMark? {
        val m = mark ?: return null
        if (!m.liveAt(now)) { mark = null; return null }
        if (closedConvoId == null || closedConvoId != m.convoId) return null
        mark = null
        return m
    }

    /** Clears the mark, returning what it was. */
    @Synchronized fun clear(): ReturnMark? { val m = mark; mark = null; return m }

    /** Clears the mark unless it is for [convoId] (another chat took the screen). */
    @Synchronized fun clearUnless(convoId: String): ReturnMark? {
        val m = mark ?: return null
        if (m.convoId == convoId) return null
        mark = null
        return m
    }
}
