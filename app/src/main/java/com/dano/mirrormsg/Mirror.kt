package com.dano.mirrormsg

import android.content.Context
import fi.mirrormsg.mirrorbridge.Bridge
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleSender
import java.util.Collections
import java.util.UUID

object Mirror {
    @Volatile var bridge: Bridge? = null

    // Application context for storage access from EventSink / service.
    @Volatile var appContext: Context? = null

    val convoOrder: MutableList<String> = Collections.synchronizedList(mutableListOf())
    data class SimRoute(val participantID: String, val simTwo: Long, val simNumber: Long)
    val simRoutes: MutableList<SimRoute> = Collections.synchronizedList(mutableListOf())

    // Hierarchical navigation. The active account's current context path (empty =
    // the account's top level / root). For a hierarchical service (Discord: root ->
    // "dms"/"guild:<id>" -> chats) each drill pushes a token, BACK pops. Flat
    // services never populate it. navKinds/navContexts run PARALLEL to convoOrder:
    // navKinds[i] is 0 (OPEN, a real chat -> convoOrder[i]) or 1 (ENTER, a
    // bucket/server -> navContexts[i] is the token to push). Rebuilt every stream.
    val navStack: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val navKinds: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val navContexts: MutableList<String> = Collections.synchronizedList(mutableListOf())

    // Per-conversation deep-link target (protocol + address), so "Open on phone"
    // / banner tap can jump into the native app on the right thread. Keyed by
    // conversationID. Protocol-agnostic (see DeepLink.kt).
    val convoTargets: MutableMap<String, ConvoTarget> = Collections.synchronizedMap(HashMap())

    // A reply room (step 99, Rooms) is open while this is one: anything else opening
    // (another chat, a force-open, the chat closed) ends the chain of rooms.
    @Volatile var currentOpenConvoId: String? = null
        set(v) { field = v; if (!Rooms.isRoom(v)) Rooms.leave() }

    // True while the Pebble watchapp is open (set from PebbleListenerService's
    // onAppOpened/onAppClosed). Drives the "when the watchapp is open, use IANs"
    // sub-options (notifyIanWhenOpen / hybridIanWhenOpen) in LiveRouter's routing.
    // NOTE: it no longer switches Notifier to the silent channel — a Pebble
    // notification chosen by the routing must ALERT regardless of watch state
    // ("new message = pebble system notification, nothing can change it").
    @Volatile var watchAppOpen: Boolean = false
    @Volatile var currentOpenRoute: SimRoute? = null
    // Last display width the connected watch reported (via KEY_REQ_DISPLAY_W on its
    // requests): 144 Basalt / 200 Emery. 0 until the first request carries it. Used
    // to size enhanced images AND to scale the conversation-list drill-in arrow pad
    // to the real screen width. Refreshed on every request that stamps it, so a
    // watch-swap self-corrects; both platforms have only two values, so brief
    // staleness is harmless (one list render at the old pad).
    @Volatile var watchDisplayW: Int = 0

    // The watch's MESSAGE window size (20 Basalt / 40 Emery), cached from the count it
    // sends on thread-open and message-span requests. Newest-span pushes (thread open,
    // live update) must use this exact n: the span base is total-n, and the watch
    // keeps low slots, so a wrong n lands the watch in the middle of the thread
    // instead of at the bottom. 0 until the watch tells us; until then we fall back to
    // the platform default derived from watchDisplayW. NOT shared with the convo-span
    // count (10/20) — only message requests update this.
    @Volatile var msgSpanCount: Int = 0

    // The watch's CONVO-list window size (10 Basalt / 20 Emery), cached from the count
    // it sends on convo-span requests. Distinguishes the two watches at convo-list
    // build time — unlike watchDisplayW, which is 0 until an image request stamps it,
    // so it's the reliable signal for the drill-arrow padding (>=20 => Emery wide pad).
    @Volatile var convoSpanCount: Int = 0

    // Backfill (3b) per-thread state. `backfillCursor[convoId]` is the pagination
    // cursor JSON from the LAST fetch — pass it to fetchMessages(count, cursor) to get
    // the next-older batch. `backfillExhausted[convoId]` is set once the bridge returns
    // no cursor (true start of the thread reached) so we stop fetching and stop growing
    // KEY_LIST_TOTAL. Both keyed by convoId; cleared when a thread is freshly opened.
    val backfillCursor: MutableMap<String, String> =
        java.util.Collections.synchronizedMap(HashMap())
    val backfillExhausted: MutableMap<String, Boolean> =
        java.util.Collections.synchronizedMap(HashMap())
    // Per-convo "suppress live pushes until" timestamp (epoch ms). Set when a backfill
    // fetch runs; while now < this, the fetched older messages (which the bridge
    // re-emits as MESSAGE events for ~1-2s AFTER the fetch, indistinguishable from new
    // ones) merge into the store SILENTLY — the live path skips the fresh thread push
    // that would otherwise jump the reader to the newest span mid-scroll.
    /**
     * How far down the degradation ladder an animation must start.
     *
     * The watch is the ONLY party that knows whether an animation actually fit:
     * the heap budget it reports is sampled before the encode, and by the time
     * the arena is allocated the display bitmap and other work have taken some of
     * it. That gap is not a fixed size, so padding the budget with a reserve just
     * swaps one arbitrary number for another.
     *
     * Instead the watch refuses, and the phone re-encodes one rung LOWER. Each
     * refusal steps the ladder, which guarantees forward progress — a refusal can
     * come from heap fragmentation rather than size, so retrying at the same rung
     * with a corrected budget could loop forever. Cleared once the animation is
     * displayed, so a later attempt starts from full quality again.
     */
    private val gifRung: MutableMap<String, Int> =
        java.util.Collections.synchronizedMap(HashMap())

    fun gifKey(convoId: String, msgIdx: Int) = "$convoId#$msgIdx"

    fun gifStartRung(convoId: String, msgIdx: Int): Int = gifRung[gifKey(convoId, msgIdx)] ?: 0

    /** Step the ladder after a refusal; returns the rung to try next. */
    fun bumpGifRung(convoId: String, msgIdx: Int): Int {
        val k = gifKey(convoId, msgIdx)
        val next = (gifRung[k] ?: 0) + 1
        gifRung[k] = next
        return next
    }

    fun clearGifRung(convoId: String, msgIdx: Int) { gifRung.remove(gifKey(convoId, msgIdx)) }

    /** Forget every ladder position — called when the watch leaves a thread, so a
     *  rung bumped by a moment of fragmentation doesn't degrade that clip forever. */
    fun clearGifRungs() { gifRung.clear() }

    val backfillQuietUntil: MutableMap<String, Long> =
        java.util.Collections.synchronizedMap(HashMap())
    // Per-convo "IAN in flight" window (epoch ms). Set when LiveRouter launches a
    // force-open sequence; while now < this, the debounced live repaint for that
    // convo is SKIPPED. Without it the repaint fires ~400ms in — BEFORE the
    // ENHANCED push (which waits out the watchapp-launch delay) — so the watch got
    // a VIEW_CHAT fresh push first: it consumed the new-message delta (killing the
    // scrolled-up hold) and overwrote the watch's thread token (poisoning same-chat
    // detection into a quiet buzz instead of an IAN). The ENHANCED push streams the
    // full thread itself, so the skipped repaint carries nothing that's lost.
    // Cleared (set to now) as soon as pushForceOpen returns, so later message
    // stages (image attach) repaint normally.
    val forceOpenQuietUntil: MutableMap<String, Long> =
        java.util.Collections.synchronizedMap(HashMap())
    // Per-convo cooldown: no new backfill until now passes this. Set after each
    // backfill. Breaks the feedback loop where a backfill grows the total, the watch's
    // position-shift moves the window toward the new floor, and that immediately
    // re-triggers backfill — a runaway that raced 80->1000 and teleported the reader
    // to the top. Genuine (slow, human) scrolling never hits the cooldown; the sub-
    // second auto re-trigger does.
    val backfillCooldownUntil: MutableMap<String, Long> =
        java.util.Collections.synchronizedMap(HashMap())
    // Which message page the open thread is currently showing (0 = newest). The
    // watch stays ignorant of paging and always addresses rows 0..MSG_PAGE_SIZE-1;
    // this is the offset the phone adds (page*MSG_PAGE_SIZE) when resolving a
    // row-indexed request. Reset to 0 on every fresh open / live new message.
    @Volatile var currentMsgPage: Int = 0

    // ---- Settings -----------------------------------------------------------
    @Volatile var nightMode: Boolean = false
    // When on, outbound text is run through EmojiSub: emoji the Pebble font can't
    // draw are swapped for a close supported glyph (unknown ones pass through).
    // Mirrored into EmojiSub.enabled so the filter can gate itself statically.
    @Volatile var emojiSubstitution: Boolean = false
    // The logged-in account's own phone number (from simCard.SIMData), shown as
    // the account subtitle. Empty until the first conversation list is parsed.
    @Volatile var accountNumber: String = ""
    // The id of the currently-active messaging service (which network's
    // conversations the watch is showing). Persisted so a switch survives app
    // restarts — otherwise the app reverts to google on relaunch while the watch
    // still shows the previously-selected network's cached list.
    @Volatile private var _activeServiceId: String = "google"
    var activeServiceId: String
        get() = _activeServiceId
        set(value) {
            _activeServiceId = value
            appContext?.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
                ?.edit()?.putString("activeServiceId", value)?.apply()
        }

    // Active Telegram sub-account (chat folder). 0 = main Telegram; >0 = a folder
    // id, filtering the Telegram conversation list to that folder's chats. Set
    // alongside activeServiceId by the watch account switch; persisted so a relaunch
    // restores the selected sub-account.
    @Volatile private var _activeTgFolder: Int = 0
    var activeTgFolder: Int
        get() = _activeTgFolder
        set(value) {
            _activeTgFolder = value
            appContext?.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
                ?.edit()?.putInt("activeTgFolder", value)?.apply()
        }

    // Active Discord server sub-account (guild). "" = base Discord (DMs only); a
    // non-empty guild id filters the Discord conversation list to that server's
    // channels. Parallels activeTgFolder — Discord servers ride the same switcher
    // rails as Telegram folders, but keyed by snowflake string (too big for Int).
    @Volatile private var _activeDiscordGuild: String = ""
    var activeDiscordGuild: String
        get() = _activeDiscordGuild
        set(value) {
            _activeDiscordGuild = value
            appContext?.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
                ?.edit()?.putString("activeDiscordGuild", value)?.apply()
        }

    /**
     * The currently-active messaging service, resolved through the registry.
     * This is the multiplexer entry point: all watch-bound data calls
     * (list/fetch/send/image) go through here, so switching accounts is just a
     * matter of flipping activeServiceId — the watch pipeline is unchanged.
     */
    fun activeService(): MessagingService? =
        appContext?.let { ServiceRegistry.byId(it, activeServiceId) }

    /**
     * Repoint [activeServiceId] at a real logged-in service when the current one
     * isn't logged in (e.g. after the phone app's data is wiped, the persisted
     * activeServiceId — or the "google" default — may name an account that no
     * longer has a session). Returns the resolved active service, or null when
     * NOTHING is logged in. Called on watchapp open (and load) so the watch never
     * boots into a dead account. Idempotent: a no-op when the active service is
     * already logged in.
     */
    fun ensureActiveLoggedIn(ctx: Context): MessagingService? {
        val current = ServiceRegistry.byId(ctx, activeServiceId)
        if (current != null && current.isLoggedIn()) return current
        val first = ServiceRegistry.loggedIn(ctx).firstOrNull()
        if (first != null) {
            // Reset nav context too — the stale active account's folder/guild
            // filters don't apply to the new one.
            activeServiceId = first.id
            activeTgFolder = 0
            activeDiscordGuild = ""
            return first
        }
        return null
    }

    // ---- Watch account switcher entries ----------------------------------
    // The rail the watch shows = every logged-in service, plus (right after
    // Telegram) each ENABLED chat folder as an indented sub-account. Centralized
    // here so both rail emitters (PebbleListenerService.reportServiceList and
    // MainActivity.pushServiceListToWatch) and the watch->phone selection handler
    // agree on the exact labels + index order.
    // folderId is a Telegram chat-folder id (0 = none). guildId is a Discord
    // server (guild) snowflake ("" = none / base DMs) — separate because Discord
    // ids exceed Int32. A given entry uses at most one of the two.
    data class SwitcherEntry(
        val label: String,
        val serviceId: String,
        val folderId: Int,
        val guildId: String = "",
    )

    // The entries behind the rail last sent to the watch, so KEY_REQ_SERVICE (an
    // index) resolves to the right service/folder. Set by whichever emitter last
    // pushed the rail.
    @Volatile var lastSwitcherEntries: List<SwitcherEntry> = emptyList()

    fun switcherEntries(ctx: Context): List<SwitcherEntry> {
        val entries = ArrayList<SwitcherEntry>()
        for (svc in ServiceRegistry.loggedIn(ctx)) {
            // Rail labels are shortened for the narrow watch switcher, independent of
            // svc.displayName (which the Network tab + notifications keep in full).
            val railLabel = when (svc.id) {
                "google"   -> "Google Msgs"
                "facebook" -> "Facebook Msgr"
                else       -> svc.displayName
            }
            entries.add(SwitcherEntry(railLabel, svc.id, 0))
            // Telegram folders and Discord servers no longer explode into the rail —
            // both live in the paginated nav-context list (root: "Direct Messages" +
            // folders/servers). Their show/hide filters (tgsub_enabled_* /
            // dcserver_enabled_*) now gate that root list instead of the rail, so each
            // hierarchical account is a single rail service.
        }
        return entries
    }

    // Beeper grouping bitmask over the entries (bit i set if entry i is a
    // Beeper-backed real account; folder sub-accounts are native Telegram, so 0).
    /** New chat (People.kt): bit i set when switcher entry i can start a chat (an
     *  account's own entry: not a Telegram folder or a Discord server). */
    fun newChatMask(ctx: Context, entries: List<SwitcherEntry>): Int {
        var mask = 0
        entries.forEachIndexed { i, e ->
            val ok = try { ServiceRegistry.byId(ctx, e.serviceId)?.supportsNewChat == true } catch (_: Throwable) { false }
            if (i < 8 && ok && e.folderId == 0 && e.guildId.isEmpty()) mask = mask or (1 shl i)
        }
        return mask
    }

    fun switcherFlags(ctx: Context, entries: List<SwitcherEntry>): Int {
        var flags = 0
        entries.forEachIndexed { i, e ->
            if (i < 32 && e.folderId == 0 && ServiceRegistry.byId(ctx, e.serviceId) is BeeperServiceBase) {
                flags = flags or (1 shl i)
            }
        }
        return flags
    }

    // How incoming messages alert/open on the watch. Two BASE behaviors exist:
    //   Pebble notification — Android notification on the alerting channel; the
    //     Pebble system notification does ALL alerting (buzz + banner). The
    //     watchapp itself does nothing active (an open chat repaints silently).
    //   IAN (in-app notification, formerly "force open") — open the watchapp (if
    //     closed) straight to the new message's detail view; already in a detail
    //     view -> replace with the new message; in the SAME chat's message list ->
    //     no IAN, quiet double-buzz (position held when scrolled up).
    // The mode (plus its sub-option) decides WHICH behavior a message gets:
    //   1 = Notification: always the Pebble notification, regardless of watch
    //       state — unless notifyIanWhenOpen is on AND the watchapp is open, in
    //       which case it's an IAN.
    //   2 = IAN ("In-app notification" in the UI; constant name kept for the
    //       persisted value's sake): ALWAYS the IAN behavior. No sub-option.
    //   3 = Hybrid: an IMAGE is always an IAN (even app-closed — pictures can't
    //       render in a Pebble notification); TEXT follows Notification mode,
    //       honoring its own hybridIanWhenOpen sub-option the same way.
    // SAME-CHAT EXCEPTION (modes 1 and 3): a message for the convo the user is
    // actively viewing on the watch (currentOpenConvoId) is ALWAYS delivered as
    // an IAN — the watch's same-chat branch makes that a quiet double-buzz, never
    // a Pebble notification popping over the open conversation.
    const val MODE_NOTIFY = 1
    const val MODE_FORCE  = 2   // UI label: "In-app notification"
    const val MODE_HYBRID = 3
    @Volatile var notifyMode: Int = MODE_NOTIFY

    // Sub-option of Notification mode: when the watchapp is OPEN, deliver
    // notify-worthy messages as IANs instead of Pebble notifications (closed ->
    // Pebble notification as normal). Off = Pebble notifications always.
    @Volatile var notifyIanWhenOpen: Boolean = false
    // Sub-option of Hybrid mode: same switch, applied to Hybrid's TEXT path only
    // (an image is an IAN regardless, so the sub-option is moot for images).
    @Volatile var hybridIanWhenOpen: Boolean = false

    // How many chat-view thumbnails the watch caches (0-8). Default 4.
    @Volatile var thumbLimit: Int = 4
    // True once the watchapp has said it can hold animated chat-row previews
    // (KEY_REQ_ANIM_THUMB on its open handshake / image requests). Older builds
    // never say so and keep receiving still previews.
    @Volatile var watchAnimThumbs: Boolean = false
    // What the watch's image transport takes (Schema.IMGCAP_* bits; 0 = an older
    // watch, which gets the plain framing) and the room a detail-view picture gets
    // there (0 = unknown, so nothing is encoded ahead). Both ride every thread open
    // and image request.
    @Volatile var watchCaps: Int = 0
    @Volatile var watchFullRoom: Int = 0

    // A video's sound on the watch's speaker. Whether it plays is the watch's to say:
    // its sound switch (step 82, on the watch: beside a video's poster and on the
    // playing video). watchSound is whether the watch has a speaker to play it on, on
    // its last video request (Schema.VIDCAP_SOUND: a speaker, not muted): with one, a
    // short clip loops like a GIF only when it has nothing to hear; without, every clip
    // up to 12 s loops. A change turns some short videos into loops or back,
    // so it bumps clipGen, which the thread builder's cache watches. videoSoundLow
    // (Settings > Watch settings > Video sound quality): Low sends half the sound
    // (4 kHz instead of 8), leaving more of the link to the picture.
    @Volatile var watchSound: Boolean = true
    @Volatile var clipGen: Int = 0
    @Volatile var videoSoundLow: Boolean = false
    // The notification sound (step 89, Settings > Watch settings > Notification sound):
    // which of the watch's sounds (step 91: TONE_NAMES, 1..6), 0 = none. Saucer at first.
    // With one, the message's video also follows the watch's video sound switch; with
    // none, it starts silent. The watch's switcher has an on/off row for it (KEY_SOUND):
    // off is 0, on brings back lastTone, the one chosen last.
    @Volatile var pushTone: Int = 1
    @Volatile var lastTone: Int = 1
    val TONE_NAMES = listOf("Off", "Saucer", "Chirp", "Zap", "Theremin", "Neon", "Beacon")
    // How loud it plays (step 111, Settings > Watch settings > Notification sound volume,
    // and the watch's sound row, which steps Low, Med, High, Off): TONE_VOL_NAMES, sent as
    // 1..3 (KEY_TONE_VOL). High at first: as loud as before.
    @Volatile var toneVol: Int = 2
    val TONE_VOL_NAMES = listOf("Low", "Medium", "High")
    // The row with Off (step 111, Settings and the watch's sound row): 0..2 a volume, 3 off.
    val TONE_LEVEL_NAMES = listOf("Low", "Medium", "High", "Off")
    fun toneLevel(): Int = if (pushTone == 0) TONE_LEVEL_NAMES.size - 1 else toneVol
    fun setToneLevel(ctx: Context, level: Int) {
        if (level >= TONE_LEVEL_NAMES.size - 1) { setPushTone(ctx, 0); return }
        setToneVol(ctx, level)
        setSoundOn(ctx, true)
    }
    /** Told when the sound changes (the watch's row, or the setting): the Settings screen. */
    @Volatile var onSoundChanged: (() -> Unit)? = null
    /** Whether a short clip's sound would play now (the rule for which clips loop). */
    fun soundRule(): Boolean = watchSound

    // Feed chats (step 85): a network's timeline shown as a chat, which the watch draws
    // newest on top (Schema.KEY_THREAD_FEED rides every thread frame of one). The
    // network marks its feed chat by its convoId once it knows it, on every launch
    // (kept in memory only): Mirror.setFeed(convoId). Everything else about the chat
    // (store, spans, backfill, live pushes) is a chat's.
    private val feedChats: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    fun setFeed(convoId: String, feed: Boolean = true) {
        if (feed) feedChats.add(convoId) else feedChats.remove(convoId)
    }
    // (A reply room, step 99, is shown as a feed too: the post at the top.)
    fun isFeed(convoId: String): Boolean = convoId in feedChats || Rooms.isRoom(convoId)

    // Image memory reserve (bytes): the minimum free heap the watch keeps when
    // allocating an image bitmap. Before each image alloc it dumps its convo/
    // thumbnail caches if needed to stay above this, and shows a placeholder
    // rather than crashing if the image still won't fit. (Replaces the old
    // entry-time cache-dump check, which couldn't catch a mid-session image
    // load driving the heap to near-zero.) The watch hard-clamps the effective
    // floor to a 600-byte minimum, so 0 just means minimal protection.
    // Wire key/type unchanged (KEY_HEAP_GUARD, int bytes). Default 800 B.
    @Volatile var heapGuard: Int = 800

    // ---- Image quality dials (phone-side encoder config) ------------------
    // Colour caps are a COUNT (2/4/16) = the max palette size the encoder may use
    // for that path; the detector picks the smallest depth that still looks right
    // UP TO this cap. Coverages are an int percent (10-100) = how much of the image
    // (by colour area) the significant-colour count must cover before the rarest
    // colours are dropped — LOWER = complex images settle for fewer colours more
    // often (smaller files), HIGHER = keep more colours. Pushed into the Go encoder
    // via Watchimage.setConfig at startup and on change. Defaults match the encoder.
    // Both image types get the full 16-colour ceiling. Thumbnails were capped at
    // 4 to keep the churny, dynamically-allocated previews cheap; with the
    // coverage figures below the analyzer still drops simple thumbnails to a
    // lower depth on its own, so the cap no longer has to do that job bluntly.
    // (A 16-colour thumbnail costs w*h/2 bytes of watch heap instead of w*h/4 —
    // the analyzer keeps that from being the common case.)
    @Volatile var imgThumbCap: Int = 16      // colours: 2/4/16
    @Volatile var imgFullCap: Int = 16       // colours: 2/4/16
    // Coverage = the share of pixel AREA the "significant colour" count must
    // explain. Higher means rare colours still count, so the analyzer picks a
    // higher depth. 94% for the full view keeps photographic detail; 72% for
    // thumbnails lets genuinely simple previews fall to a cheaper depth.
    @Volatile var imgThumbCoverage: Int = 72 // percent 10-100
    @Volatile var imgFullCoverage: Int = 94  // percent 10-100

    // Phase 2: heap-saving row compression on the full-size (enhanced) view only.
    // The watch keeps the image compressed in RAM and expands one scanline at a time
    // into the framebuffer, so a large image costs its compressed size in heap, not
    // its decoded size. Enhanced-view ONLY by design: thumbnails are tiny, cached as
    // decoded bitmaps, and redraw every scroll frame — decode-on-draw there would
    // spend CPU per frame to save a few hundred bytes and add resident decoder code,
    // a net loss (that's why there's no "compress thumbnails" option). Opt-in; the
    // phone ships raw when an image doesn't compress, so it's never worse than off.
    @Volatile var imgCompress: Boolean = true

    // Adaptive dithering (opt-in). Reconstructs smooth gradients against the chosen
    // palette so few-colour images look like they have more colours, at a
    // compression cost — that's why it's opt-in. Flat fills and hard edges are left
    // crisp. Three dials: edge protection (keep boundaries sharp), strength (how
    // readily a region counts as a ditherable gradient), intensity (how strong the
    // dither texture is — lower = softer, cleaner, more compressible). The slider
    // UI shows friendly 0-100 values; these are mapped to the encoder's internal
    // squared-distance thresholds in pushImageConfig.
    // ON by default. Without error diffusion a 16-colour palette shows hard
    // banding on any photographic gradient (measured: ~28x worse perceptual
    // error than whole-image dithering on a real dark photo).
    @Volatile var imgDither: Boolean = true
    // Compression bias: how much of the image is EXCLUDED from dithering to keep
    // it row-compressible. 0 = dither every pixel (smoothest gradients, largest
    // transfers); higher = protect more flat regions and hard edges, so long runs
    // of identical pixels survive for the compressor.
    //
    // This single dial replaced three controls ("edge protection", "dither
    // strength", and a "dither entire image" switch) that were really one idea
    // wearing three hats — and whose interaction hid a cliff: the strength slider
    // only disabled edge protection at exactly 100, so 99 and 100 ran visibly
    // different algorithms. It drives both encoder thresholds together, so the
    // range is continuous end to end.
    //
    // Only meaningful when imgCompress is on; with compression off there is
    // nothing to preserve, so the encoder always dithers the whole image and the
    // UI hides this control entirely.
    @Volatile var imgDitherCompress: Int = 0      // 0-100 UI (compression bias)
    @Volatile var imgDitherEdge: Int = 50         // legacy, no longer surfaced in the UI
    @Volatile var imgDitherStrength: Int = 100     // legacy, no longer surfaced in the UI
    @Volatile var imgDitherIntensity: Int = 100    // 0-100 UI (diffusion strength)

    fun loadSettings(ctx: Context) {
        val prefs = ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
        nightMode = prefs.getBoolean("nightMode", false)
        watchCalls = prefs.getBoolean("watchCalls", false)
        emojiSubstitution = prefs.getBoolean("emojiSubstitution", false)
        EmojiSub.enabled = emojiSubstitution
        videoSoundLow = prefs.getBoolean("videoSoundLow", false)
        pushTone = prefs.getInt("pushTone", 1).coerceIn(0, TONE_NAMES.size - 1)
        lastTone = prefs.getInt("lastTone", if (pushTone > 0) pushTone else 1)
            .coerceIn(1, TONE_NAMES.size - 1)
        toneVol = prefs.getInt("toneVol", 2).coerceIn(0, TONE_VOL_NAMES.size - 1)
        notifyMode = prefs.getInt("notifyMode", MODE_NOTIFY)
        notifyIanWhenOpen = prefs.getBoolean("notifyIanWhenOpen", false)
        hybridIanWhenOpen = prefs.getBoolean("hybridIanWhenOpen", false)
        thumbLimit = prefs.getInt("thumbLimit", 4).coerceIn(0, 8)
        heapGuard = prefs.getInt("heapGuard", 800)
        imgThumbCap = prefs.getInt("imgThumbCap", 16)
        imgFullCap = prefs.getInt("imgFullCap", 16)
        imgThumbCoverage = prefs.getInt("imgThumbCoverage", 72).coerceIn(10, 100)
        imgFullCoverage = prefs.getInt("imgFullCoverage", 94).coerceIn(10, 100)
        // Compression ON by default: dithered photos row-compress poorly, but
        // flat/graphic images compress hard, and the encoder ships whichever of
        // raw/compressed is smaller — so this is never worse than off.
        imgCompress = prefs.getBoolean("imgCompress", true)
        imgDither = prefs.getBoolean("imgDither", true)
        imgDitherEdge = prefs.getInt("imgDitherEdge", 50).coerceIn(0, 100)
        imgDitherStrength = prefs.getInt("imgDitherStrength", 100).coerceIn(0, 100)
        imgDitherCompress = prefs.getInt("imgDitherCompress", 0).coerceIn(0, 100)
        imgDitherIntensity = prefs.getInt("imgDitherIntensity", 100).coerceIn(0, 100)
        pushImageConfig()
        accountNumber = prefs.getString("accountNumber", "") ?: ""
        _activeServiceId = prefs.getString("activeServiceId", "google") ?: "google"
        _activeTgFolder = prefs.getInt("activeTgFolder", 0)
        _activeDiscordGuild = prefs.getString("activeDiscordGuild", "") ?: ""
        loadQuickReplies(ctx)
    }

    fun setThumbLimit(ctx: Context, n: Int) {
        thumbLimit = n.coerceIn(0, 8)
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putInt("thumbLimit", thumbLimit).apply()
    }

    // ---- Image quality setters --------------------------------------------
    // Each persists and immediately re-pushes the full config to the encoder so a
    // dial change applies to the very next image (no restart). Caps clamp to the
    // three watch-supported palette sizes; coverages to 10-100%.
    private fun persistInt(ctx: Context, key: String, v: Int) {
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putInt(key, v).apply()
    }

    fun setImgThumbCap(ctx: Context, colors: Int) {
        imgThumbCap = if (colors == 2 || colors == 4 || colors == 16) colors else 16
        persistInt(ctx, "imgThumbCap", imgThumbCap); pushImageConfig()
    }

    fun setImgFullCap(ctx: Context, colors: Int) {
        imgFullCap = if (colors == 2 || colors == 4 || colors == 16) colors else 16
        persistInt(ctx, "imgFullCap", imgFullCap); pushImageConfig()
    }

    fun setImgThumbCoverage(ctx: Context, percent: Int) {
        imgThumbCoverage = percent.coerceIn(10, 100)
        persistInt(ctx, "imgThumbCoverage", imgThumbCoverage); pushImageConfig()
    }

    fun setImgFullCoverage(ctx: Context, percent: Int) {
        imgFullCoverage = percent.coerceIn(10, 100)
        persistInt(ctx, "imgFullCoverage", imgFullCoverage); pushImageConfig()
    }

    fun setImgCompress(ctx: Context, on: Boolean) {
        imgCompress = on
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putBoolean("imgCompress", on).apply()
        pushImageConfig() // CompressFit sizing follows this toggle in the encoder
    }

    fun setImgDither(ctx: Context, on: Boolean) {
        imgDither = on
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putBoolean("imgDither", on).apply()
        pushImageConfig()
    }

    fun setImgDitherEdge(ctx: Context, v: Int) {
        imgDitherEdge = v.coerceIn(0, 100)
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putInt("imgDitherEdge", imgDitherEdge).apply()
        pushImageConfig()
    }

    fun setImgDitherStrength(ctx: Context, v: Int) {
        imgDitherStrength = v.coerceIn(0, 100)
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putInt("imgDitherStrength", imgDitherStrength).apply()
        pushImageConfig()
    }

    fun setImgDitherCompress(ctx: Context, v: Int) {
        imgDitherCompress = v.coerceIn(0, 100)
        persistInt(ctx, "imgDitherCompress", imgDitherCompress); pushImageConfig()
    }

    fun setImgDitherIntensity(ctx: Context, v: Int) {
        imgDitherIntensity = v.coerceIn(0, 100)
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putInt("imgDitherIntensity", imgDitherIntensity).apply()
        pushImageConfig()
    }

    // Push the four dials into the Go encoder. Coverage percent -> fraction. Safe
    // to call anytime; the Go side clamps anything out of range to its defaults.
    fun pushImageConfig() {
        try {
            // One dial -> both encoder thresholds, scaled together so the range is
            // continuous (no magic value, no cliff).
            //   gradient threshold: 0 at bias 0 (every pixel dithers) up to 3000
            //     (only strong gradients dither, flat areas left solid).
            //   edge threshold: a concentrated jump >= this counts as an edge and
            //     is kept crisp AND excluded from diffusion. Huge at bias 0 so
            //     nothing qualifies; tight at bias 100 so edges are protected.
            // Compression off => nothing to preserve => always dither everything.
            val bias = if (imgCompress) imgDitherCompress else 0
            val gradThresh = (bias * 30).coerceIn(0, 3000)
            val edgeThresh = (110000 - bias * 1000).coerceIn(10000, 110000)
            fi.mirrormsg.mirrorbridge.Mirrorbridge.setImageConfig(
                imgThumbCoverage / 100.0,
                imgFullCoverage / 100.0,
                imgThumbCap.toLong(),
                imgFullCap.toLong(),
                imgCompress,
                imgDither,
                edgeThresh.toLong(),
                gradThresh.toLong(),
                imgDitherIntensity.toLong(),
            )
        } catch (e: Throwable) {
            // Encoder not loaded yet / older AAR without setImageConfig — harmless,
            // the encoder just uses its built-in defaults until the next push.
        }
    }

    fun setHeapGuard(ctx: Context, bytes: Int) {
        heapGuard = bytes.coerceIn(0, 6000)
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putInt("heapGuard", heapGuard).apply()
    }

    fun setNotifyMode(ctx: Context, mode: Int) {
        notifyMode = mode
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putInt("notifyMode", mode).apply()
    }

    fun setNotifyIanWhenOpen(ctx: Context, on: Boolean) {
        notifyIanWhenOpen = on
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putBoolean("notifyIanWhenOpen", on).apply()
    }

    fun setHybridIanWhenOpen(ctx: Context, on: Boolean) {
        hybridIanWhenOpen = on
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putBoolean("hybridIanWhenOpen", on).apply()
    }

    // Quick replies (canned messages). Defaults mirror Pebble's stock set.
    val DEFAULT_QUICK_REPLIES = listOf("Ok", "Yes", "No", "Call me", "Call you later")
    @Volatile var quickReplies: List<String> = DEFAULT_QUICK_REPLIES

    fun loadQuickReplies(ctx: Context) {
        val raw = ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .getString("quickReplies", null)
        quickReplies = if (raw.isNullOrEmpty()) DEFAULT_QUICK_REPLIES
                       else raw.split("\u001e").filter { it.isNotBlank() }
    }

    fun setQuickReplies(ctx: Context, list: List<String>) {
        val clean = list.map { it.trim() }.filter { it.isNotEmpty() }.take(12)
        quickReplies = if (clean.isEmpty()) DEFAULT_QUICK_REPLIES else clean
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putString("quickReplies", quickReplies.joinToString("\u001e")).apply()
    }

    // Calls from the watch (Calls.kt): a Call row on the watch for chats that can be
    // called. Off until switched on in Settings (which asks for the permissions).
    @Volatile var watchCalls: Boolean = false

    fun setWatchCalls(ctx: Context, enabled: Boolean) {
        watchCalls = enabled
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putBoolean("watchCalls", enabled).apply()
    }

    fun setNightMode(ctx: Context, enabled: Boolean) {
        nightMode = enabled
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putBoolean("nightMode", enabled).apply()
    }

    fun setEmojiSubstitution(ctx: Context, enabled: Boolean) {
        emojiSubstitution = enabled
        EmojiSub.enabled = enabled
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putBoolean("emojiSubstitution", enabled).apply()
    }

    fun setVideoSoundLow(ctx: Context, low: Boolean) {
        videoSoundLow = low
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putBoolean("videoSoundLow", low).apply()
    }

    fun setPushTone(ctx: Context, tone: Int) {
        pushTone = tone.coerceIn(0, TONE_NAMES.size - 1)
        if (pushTone > 0) lastTone = pushTone
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putInt("pushTone", pushTone).putInt("lastTone", lastTone).apply()
        onSoundChanged?.invoke()
    }

    fun setToneVol(ctx: Context, level: Int) {
        toneVol = level.coerceIn(0, TONE_VOL_NAMES.size - 1)
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putInt("toneVol", toneVol).apply()
        onSoundChanged?.invoke()
    }

    /** The watch's sound row: off, or back on with the sound chosen last. */
    fun setSoundOn(ctx: Context, on: Boolean) = setPushTone(ctx, if (on) lastTone else 0)

    fun setAccountNumber(ctx: Context, number: String) {
        accountNumber = number
        ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().putString("accountNumber", number).apply()
    }

    // ---- Conversation list (persistent, no expiry, PER ACCOUNT) ------------
    // One cached ROOT list per switcher context — the (service, Telegram folder,
    // Discord guild-pin) tuple an account switch sets. The key resolves from the
    // ACTIVE tuple at call time, so every call site keeps its "active account's
    // root list" semantics unchanged. Cache-then-freshen serving (the open AND
    // switch paths) makes account switches instant regardless of how slow a
    // network's list fetch is: Google's listConversations is a libgm round-trip
    // to Google's backend (measured ~4.2s for the 200-row list); the local-store
    // networks answer in milliseconds and never showed the stall. Memory map +
    // one disk file per context (survives restarts); refreshed only when the
    // bridge reports new activity or a background fetch returns a changed list.
    private val convoListMem = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The ACTIVE switcher context's cache key. Callers that FETCH then PUT must
     *  capture this BEFORE the fetch and pass it to putCachedConvoJson — the user
     *  can switch accounts mid-fetch, and an unpinned put would persist account
     *  A's list into account B's slot (worse than the old single-slot race: it
     *  would survive on B's disk file). */
    fun convoCacheKey(): String = "$activeServiceId|$activeTgFolder|$activeDiscordGuild"

    private fun convoListFile(ctx: Context, key: String): java.io.File {
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return java.io.File(ctx.filesDir, "convo_list_$safe.json")
    }

    /** Cached root list for the ACTIVE context (memory, then disk). Never expires. */
    fun getCachedConvoJson(): String? {
        val key = convoCacheKey()
        convoListMem[key]?.let { return it }
        val ctx = appContext ?: return null
        val f = convoListFile(ctx, key)
        if (!f.exists()) return null
        return try {
            val s = f.readText()
            convoListMem[key] = s
            s
        } catch (e: Exception) { null }
    }

    /** Store a root list to memory and disk. forKey defaults to the active
     *  context; pass a pre-fetch pinned key whenever the json came from a
     *  suspending fetch (see convoCacheKey). */
    fun putCachedConvoJson(json: String, forKey: String = convoCacheKey()) {
        convoListMem[forKey] = json
        val ctx = appContext ?: return
        try { convoListFile(ctx, forKey).writeText(json) } catch (_: Exception) {}
    }

    /** Forget every cached root list of one account (all its folder and guild
     *  contexts), memory and disk. Called on logout, so a later login (maybe to
     *  another account on the same network) never shows the old account's list. */
    fun dropCachedConvoLists(ctx: Context, serviceId: String) {
        val prefix = "$serviceId|"
        convoListMem.keys.removeAll { it.startsWith(prefix) }
        // Disk names are convoListFile(key): "<id>|<folder>|<guild>" made file-safe.
        val safeId = prefix.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val name = Regex("^" + Regex.escape("convo_list_$safeId") + "-?\\d+_\\d*\\.json$")
        try {
            ctx.filesDir.listFiles()?.forEach { f -> if (name.matches(f.name)) f.delete() }
        } catch (_: Exception) {}
    }

    /** Force the next list read for the ACTIVE context to skip memory (call on
     *  new-activity events). */
    fun invalidateConvoList() { convoListMem.remove(convoCacheKey()) }

    private var _sender: PebbleSender? = null
    fun sender(ctx: Context): PebbleSender =
        _sender ?: DefaultPebbleSender(ctx.applicationContext).also { _sender = it }
    fun closeSender() {
        try { _sender?.close() } catch (_: Exception) {}
        _sender = null
    }
}

object Schema {
    val KEY_OP: UInt         = 0u
    val KEY_IDX: UInt        = 1u
    val KEY_COUNT: UInt      = 2u
    val KEY_NAME: UInt       = 3u
    val KEY_SNIPPET: UInt    = 4u
    val KEY_COLOR: UInt      = 5u
    val KEY_TYPE: UInt       = 6u
    val KEY_UNREAD: UInt     = 7u
    val KEY_MSG_TEXT: UInt   = 8u
    val KEY_MSG_IN: UInt     = 9u
    val KEY_MSG_SENDER: UInt = 10u
    val KEY_REQ_OPEN: UInt    = 20u
    val KEY_REPLY_TEXT: UInt  = 21u
    // 22 (KEY_HAVE_VERSION), 23 (KEY_NO_CHANGE), 25 (KEY_THREAD_VER) RETIRED —
    // the watch dropped its thread-text cache, so there's no version handshake;
    // the full blob is always sent. Numbers reserved, do not reuse.
    val KEY_THREAD_BLOB: UInt = 24u   // phone->watch: entire thread text packed into one string
    val KEY_IMG_INDICES: UInt = 26u   // phone->watch: comma-list of message indices that are images
    val KEY_NIGHT_MODE: UInt  = 27u   // phone->watch: 1=dark theme, 0=light
    val KEY_QUICK_REPLIES: UInt = 28u // phone->watch: canned replies, \x1e-separated
    val KEY_OPEN_VIEW: UInt   = 29u   // phone->watch (OP_MSG): 0=chat view, 1=enhanced view
    val KEY_REQ_IMAGE: UInt   = 40u   // watch->phone: stream the image for this msg index
    val KEY_REQ_IMG_FULL: UInt = 41u  // watch->phone: 0=small (chat preview), 1=full (enhanced)
    val KEY_REQ_IMG_BUDGET: UInt = 42u // watch->phone: free-heap byte budget for a full image
    val KEY_DIAG: UInt = 43u           // watch->phone: diagnostic text string (logged on phone)
    val KEY_REQ_IMG_BUDGET_MAX: UInt = 44u // watch->phone: budget if it frees its convo cache
    val KEY_IMG_WIPE_CACHE: UInt = 45u // phone->watch (IMG_START): 1 = free cache before building
    val KEY_IMG_DEPTH: UInt = 46u      // phone->watch (IMG_START): packed bits/pixel 1/2/4 (palettized); absent => 4 (legacy)    // 46 (KEY_CACHE_LIMIT) RETIRED — watch no longer caches thread text. Reserved, do not reuse.
    val KEY_REQ_IMG_QUALITY: UInt = 47u // watch->phone: 0=full color (8bpp), 1=16-color (4bpp)
    val KEY_IMG_FORMAT: UInt = 48u     // phone->watch (IMG_START): 0=8bpp, 1=4bpp palette
    val KEY_IMG_PALETTE: UInt = 49u    // phone->watch (IMG_START): palette bytes (GColor8) for 4bpp
    val KEY_THUMB_LIMIT: UInt = 50u    // phone->watch (convo frame): how many thumbnails to cache (0-6)
    val KEY_REQ_IMG_PRIORITY: UInt = 51u  // watch->phone: abort other image work, stream this now
    // Phase 2 (heap-saving row compression). The watch stores the COMPRESSED
    // stream resident and decompresses one scanline at a time straight into the
    // 8-bit framebuffer at draw time — the full bitmap never exists in heap.
    // KEY_IMG_ENCODING on IMG_START: 0=RAW (phase-1 native gbitmap path), 1=COMPRESSED.
    // Compressed frames use TIGHT rows (no word-align — the watch decodes per-pixel
    // into the framebuffer, no gbitmap stride). Each row is compressed independently
    // (PackBits + a whole-row 0x00 "repeat previous row" token); runs never cross a
    // row boundary so any row is a valid decode start. KEY_IMG_ENC_BYTES = the
    // compressed resident size (the watch reserves THIS, not the decoded size).
    // KEY_IMG_RESTART_IDX = a seek table (uint16-LE compressed offset of every 8th
    // row) so a scrolled view can jump to the viewport top. Restart rows (every 8th)
    // are NEVER encoded as repeat tokens, so a seek always lands on a decodable row.
    // The phone ships whichever of {raw, compressed} is smaller per image, so an
    // incompressible photo just takes the untouched RAW path — never worse than today.
    val KEY_IMG_ENCODING: UInt = 76u   // phone->watch (IMG_START): 0=RAW, 1=COMPRESSED (row-RLE)
    val KEY_IMG_ENC_BYTES: UInt = 77u  // phone->watch (IMG_START): compressed resident size (reserve)
    // ---- Animated GIF -------------------------------------------------------
    // A GIF is transferred as a frame SEQUENCE, not a single image: frame 0 whole,
    // every later frame as a delta against the one before it (measured ~23% of raw
    // on typical clips, which is what makes animation fit in watch heap at all).
    // The watch keeps ONE frame buffer plus the delta streams and applies them in
    // order, so resident cost is one frame + deltas rather than N frames.
    // These ride the SAME chunk transport as images (KEY_IMG_SEQ byte offsets,
    // KEY_IMG_DATA payloads, KEY_IMG_END per segment) — only the framing keys are
    // new, so nothing about the proven transport changes.
    val KEY_GIF_START: UInt = 92u    // phone->watch: msg index; begins an animation.
                                     // Carries KEY_IDX=w, KEY_COUNT=h, KEY_IMG_DEPTH,
                                     // KEY_IMG_PALETTE, KEY_GIF_FRAMES, KEY_GIF_BUFBYTES.
    val KEY_GIF_FRAMES: UInt = 93u   // frame count in this animation
    val KEY_GIF_FRAME: UInt = 94u    // which frame a header/chunk/END belongs to
    val KEY_GIF_DELAY: UInt = 95u    // that frame's display time in MILLISECONDS
    val KEY_GIF_DELTA: UInt = 96u    // 1 = this frame is a delta stream, 0 = full frame
    val KEY_GIF_BUFBYTES: UInt = 97u // packed bytes of ONE frame: the watch's buffer size
    val KEY_GIF_TOTAL: UInt = 100u   // phone->watch (GIF_START): total bytes of ALL frame
                                     // streams. The watch allocates ONE arena of this size
                                     // instead of a malloc per frame — 32 small allocations
                                     // fragmented the heap and a later one would fail
                                     // mid-stream, abandoning an animation that had already
                                     // shown its first frame. One arena either succeeds
                                     // before anything streams, or declines cleanly.
    // Video streaming. Byte layouts are little-endian.
    val KEY_VID_REQ: UInt = 102u     // watch->phone: play the video of msg index N (as KEY_REQ_IMAGE)
    val KEY_VID_HEAP: UInt = 103u    // watch->phone (with VID_REQ): free heap for the stream
    val KEY_VID_START: UInt = 104u   // phone->watch: [u16 w][u16 h][u16 fps*10][u32 durMs][u8 rung][u8 colours]
                                     //   [u8 sound: bits 0-1 the room kept for it, KEY_VID_AUDIO
                                     //   follows (0 none, 1 Standard, 2 Low); bit 2 it has
                                     //   sound (step 82)]; w=0 = can't play
    val KEY_VID_FRAME: UInt = 105u   // phone->watch: [u16 no][u32 ptsMs][u16 total][u16 off] + payload bytes
    val KEY_VID_STATUS: UInt = 106u  // watch->phone: [u16 last][u32 clockMs][u16 free][u8 buffered][u8 state][u8 needFull]
                                     //   [u8 silent: its sound isn't wanted now (step 82)]
    val KEY_VID_END: UInt = 107u     // phone->watch: last frame number sent (stream complete)
    val KEY_VID_STOP: UInt = 108u    // watch->phone: stop streaming (left the video)
    // Faster image transport (step 58). The watch says what it takes on every
    // thread open and image request; without it the plain framing is used.
    val KEY_REQ_CAPS: UInt = 112u    // watch->phone: IMGCAP_* bits
    const val IMGCAP_FOLD = 1        //   a START may carry its data and END; a band's
                                     //   header and END may ride on its data
    const val IMGCAP_GIF_PACKED = 2  //   an animation's frames go as one stream
    const val IMGCAP_ALBUM = 4       //   albums: a row's item count (blob field 11), and
                                     //   one item at a time in the detail view (step 88)
    const val IMGCAP_ROOMS = 8       //   reply rooms (step 99): a row's room (blob field
                                     //   12), KEY_REQ_ROOM / KEY_ROOM_BACK
    const val IMGCAP_CALL = 16       //   calls: a Call row in its reply menu (KEY_THREAD_CALL)
    const val IMGCAP_REACT = 32      //   reactions come only as KEY_REQ_REACT: an emoji in
                                     //   a reply (KEY_REPLY_TEXT) is text, never a reaction
    val KEY_GIF_TABLE: UInt = 113u   // phone->watch (GIF_START): per frame [u16 bytes]
                                     //   [u16 delay ms][u8 bit0 delta, bit1 comp]; then
                                     //   KEY_GIF_FRAME -1 chunks, KEY_IMG_SEQ = offset
    val KEY_FULL_ROOM: UInt = 114u   // watch->phone (Time 2): the room a detail-view
                                     //   picture gets, so it can be encoded ahead
    // Sharp patch over a zoomed picture (Time 2); layouts in PebbleListenerService.
    val KEY_IZ_REQ: UInt = 110u      // watch->phone: crop request (bytes)
    val KEY_IZ_PATCH: UInt = 111u    // phone->watch: start / data / end / none (bytes)
    val KEY_IZ_GEO: UInt = 115u      // phone->watch, on a GIF_START: the animation is the
                                     //   sharp section of a zoomed GIF. [u16 seq][i32 x 16.16]
                                     //   [i32 y 16.16][u32 kx 16.16][u32 ky 16.16]
    // The compose bar: each chat's draft, kept here (DraftStore), shown on the watch.
    val KEY_DRAFT_OP: UInt = 116u    // watch->phone: DRAFT_ADD (text in KEY_DRAFT_TEXT),
                                     //   DRAFT_CLEAR, DRAFT_SEND (KEY_REPLY_TO_INDEX from an
                                     //   open message). phone->watch: the chat's thread
                                     //   token (KEY_THREAD_TOKEN's value) the draft is for
    val KEY_DRAFT_TEXT: UInt = 117u  // watch->phone: the text to add. phone->watch: the
                                     //   chat's draft to show ("" = none), its end when long
    const val DRAFT_ADD = 1
    const val DRAFT_CLEAR = 2
    const val DRAFT_SEND = 3
    const val DRAFT_GET = 4            // the keyboard opened: send the whole draft (KEY_DRAFT_FULL)
    const val DRAFT_SET = 5            // the keyboard's edited draft, in pieces (KEY_DRAFT_CHUNK)
    // The watch keyboard (Time 2).
    val KEY_KB_REQ: UInt = 118u      // watch->phone (bytes): a word's taps, to read:
                                     //   [seq][flags][n][x,y]*n[literal len][literal][previous word]
                                     //   x key widths x16, y rows x16 (bit 7: picked with the
                                     //   magnifier); x 255 = a letter given exactly (y = Latin-1)
    val KEY_KB_SUGG: UInt = 119u     // phone->watch (bytes): [seq][flags KS_*][left 1F centre 1F right]
    val KEY_DRAFT_FULL: UInt = 120u  // phone->watch (bytes): the whole draft to edit (+ KEY_DRAFT_OP
                                     //   = the chat's thread token, KEY_DRAFT_BASE)
    val KEY_DRAFT_CHUNK: UInt = 121u // watch->phone (bytes): a piece of the edited draft
    val KEY_DRAFT_OFS: UInt = 122u   // watch->phone: that piece's byte offset
    val KEY_DRAFT_END: UInt = 123u   // watch->phone: on the last piece: DSET_*
    val KEY_DRAFT_TOK: UInt = 124u   // watch->phone: the chat (its thread token) of a draft op
    val KEY_DRAFT_BASE: UInt = 125u  // both: where the watch's copy starts in the draft (chars)
    // Video, step 81 (Time 2). A watch that sends KEY_VID_CAPS with its VID_REQ can
    // switch an opened video to a looping clip and play a video's sound.
    val KEY_VID_CAPS: UInt = 126u    // watch->phone (with VID_REQ): VIDCAP_* bits
    const val VIDCAP_CLIP = 1        //   takes KEY_VID_CLIP
    const val VIDCAP_SOUND = 2       //   has a speaker that isn't muted: send the sound
    const val VIDCAP_MUTED = 4       //   its sound switch is off: no room for sound, all to the picture (step 82)
    val KEY_VID_CLIP: UInt = 127u    // phone->watch: msg index N's video is a short loop:
                                     //   show it as an animation (ask for it as a picture)
    val KEY_VID_AUDIO: UInt = 128u   // phone->watch (bytes): sound spans, each
                                     //   [u32 first sample][u16 n][i16 pred][u8 index][u8 0]
                                     //   + 4-bit IMA ADPCM, low nibble first, 8 kHz mono: n
                                     //   samples ((n+1)/2 bytes), Low n/2 at 4 kHz
    val KEY_VID_AT: UInt = 129u      // watch->phone (with VID_REQ): start this far in, ms (step 82)
    const val DSET_KEEP = 1
    const val DSET_SEND = 2
    const val DSET_ADD = 3
    const val DSET_ADD_SEND = 4
    const val KS_AUTO = 1            // the centre word replaces the typed one on space
    const val KS_PREDICT = 2         // words to come next (no word under way)
    val KEY_REQ_ANIM_THUMB: UInt = 101u  // watch->phone (open handshake + image requests):
                                         // 1 = this watchapp plays animated chat-row previews.
                                         // phone->watch it rides GIF_START as KEY_REQ_IMG_FULL=0.
    val KEY_GIF_COMP: UInt = 99u     // frame 0 only: 1 = row-compressed (same format as
                                     // still images), so the watch expands it once into the
                                     // frame buffer. Frame 0 is a whole image and is often
                                     // the bulk of a clip's bytes; deltas stay raw.
    val KEY_GIF_ABORT: UInt = 98u    // watch->phone: stop sending, animation abandoned

    val KEY_IMG_BAND: UInt = 91u  // phone->watch: FULL images stream as horizontal BANDS —
                                  // each band its own small watch-side allocation (~1/N of
                                  // the image, ends fragmented-heap alloc failures) and
                                  // displayed progressively as it completes. On IMG_START:
                                  // the band COUNT. On a band-header frame (no START/DATA/
                                  // END): which band follows, with KEY_COUNT=rows,
                                  // KEY_TYPE=band decoded bytes, and per-band ENC_BYTES/
                                  // RESTART_IDX when compressed. On chunks and band-ENDs:
                                  // the owning band. Absent everywhere = legacy single-
                                  // segment transfer (thumbnails/small stay that shape).
                                  // Must match main.c's #define.
    val KEY_IMG_RESTART_IDX: UInt = 78u // phone->watch (IMG_START): uint16-LE compressed offsets of every 8th row (seek table)
    // watch->phone (enhanced-image request): the connected watch's actual display
    // width in pixels (144 Basalt / 200 Emery / whatever future platforms report).
    // Enhanced images are sized to fill THIS width instead of a hardcoded 144, so a
    // 200-wide Time 2 fills its screen. Absent/0 => fall back to IMG_MAX_W (144), so
    // older watchapps that don't send it render exactly as before.
    val KEY_REQ_DISPLAY_W: UInt = 79u

    // Continuous-scroll span protocol (replaces page-turning in both the convo list
    // and the message thread). The watch is a sliding window over the full list: it
    // requests a span of absolute rows, and as the selection nears a window edge it
    // silently requests the next span. Drill navigation (KEY_REQ_ENTER) is unchanged
    // — each drill level is its own streamed list.
    //   KEY_REQ_SPAN  (watch->phone): stream rows [v, v+windowSlots) of the CURRENT
    //     list (top-level convos, a drilled sub-list, or — Stage 2 — the open thread).
    //   KEY_LIST_ABS  (phone->watch, per row): the row's ABSOLUTE index in the full
    //     list. Its presence marks a span-protocol row (supersedes page addressing).
    //   KEY_LIST_TOTAL (phone->watch): total rows in the full list (scrollbar/count).
    // Frame-0 invariant: a span response to KEY_REQ_SPAN=v carries abs==v on frame 0;
    // a fresh/unsolicited push (open, account switch, drill, reorder) carries abs==0
    // (or, for a thread open, the clamped newest-span start). The watch tells "my
    // response" from "fresh context" by whether frame-0 abs == its pending request.
    val KEY_REQ_SPAN: UInt   = 80u   // watch->phone: stream span starting at this abs row
    val KEY_LIST_ABS: UInt   = 81u   // phone->watch (per row): absolute row index
    val KEY_LIST_TOTAL: UInt = 82u   // phone->watch: total rows in the full list
    // watch->phone (rides with KEY_REQ_SPAN): how many rows to stream — the watch's
    // own window size (10 Basalt / 20 Emery). The phone streams exactly [v, v+n) so
    // it never has to know the platform. Absent => default 10 (defensive only).
    val KEY_REQ_SPAN_COUNT: UInt = 83u
    // phone->watch: a whole convo span packed into ONE frame (US/RS-delimited text,
    // records in absolute order from KEY_LIST_ABS). Replaces N per-row OP_CONVO frames
    // with one blob -> one ACK, so a fast scroll can't outrun the fill. The watch
    // branches on this key's presence: present => unpack blob; absent => legacy
    // per-row path (kept during transition). Same OP_CONVO routing either way.
    val KEY_CONVO_BLOB: UInt = 84u
    // watch->phone: request a MESSAGE span [v, v+n) within the OPEN thread (rides
    // KEY_REQ_SPAN_COUNT for n). Distinct from KEY_REQ_SPAN (80, convo list) so the
    // phone routes by the key — never by open-thread state, which can go stale. The
    // old protocol used the same two-key split (KEY_REQ_CONVO_PAGE vs KEY_REQ_MSG_PAGE).
    val KEY_REQ_MSG_SPAN: UInt = 85u
    // phone->watch: "span in progress, hold" — sent the instant a KEY_REQ_MSG_SPAN
    // will need a blocking Google backfill fetch (>2s possible). Echoes the requested
    // v. The watch suspends/extends its 2s span watchdog for that v instead of firing
    // it, so a slow backfill span can't clear pending, race a duplicate, and get
    // misread as a fresh push (which would jump the user to newest mid-scroll).
    // Inert until backfill ships; defined now so both sides can wire to the number.
    val KEY_SPAN_ACK: UInt = 86u
    // phone->watch (OP_REACT_PATCH): update ONE message's reactions in place, matched
    // by stable message ID — never touches the message window (so it can't blank the
    // view the way a reaction-only span re-push did). The watch finds the resident row
    // whose msgId matches and repaints just its reaction badge, keeping scroll; if the
    // message isn't resident, it's ignored (rides the next normal blob).
    val KEY_REACT_MSG_ID: UInt = 87u   // watch matches this against blob field 10 (msgId)
    val KEY_REACT_SET: UInt   = 88u    // new reaction summary (thread-blob field-7 format; "" clears)
    // phone->watch: FNV-1a hash of the convoId (uint32), sent on every message thread
    // push (manual-open AND force-open). Lets the watch identify WHICH chat a push is
    // for — it stores this on open and compares it on a force-open, to decide the
    // notification YES/NO branch (same chat you're viewing -> quiet in-list buzz;
    // different chat -> force-open PN). The watch has no convoId otherwise (its state
    // is index-based), so this is the only same-chat signal. Matches threadToken().
    val KEY_CONVO_HASH: UInt  = 89u
    val KEY_REQ_FULL_TEXT: UInt = 52u     // watch->phone: send full untruncated text for this msg index
    val KEY_FULL_TEXT: UInt     = 53u     // phone->watch: the full text payload
    val KEY_FULL_TEXT_IDX: UInt = 54u     // phone->watch: which msg index the full text is for
    val KEY_SERVICES: UInt      = 55u     // phone->watch: \x1e-separated service labels
    val KEY_ACTIVE_SERVICE: UInt = 56u    // phone->watch: index of the active service
    val KEY_HEAP_GUARD: UInt = 57u        // phone->watch (convo frame): low-heap byte floor (0=off)
    val KEY_REQ_SERVICE: UInt   = 57u     // watch->phone: switch to service index N
    val KEY_PN_ROW: UInt = 58u            // phone->watch (OP_MSG force-open): row index of the PN's
                                          // convo in the just-pushed list, so the watch sets s_open_row
                                          // to match (reply routing fallback + thread-cache key correctness)
    val KEY_SERVICE_FLAGS: UInt = 59u     // phone->watch: int32 bitmask sent alongside KEY_SERVICES in
                                          // the service-report frame. bit i set => the i-th service in
                                          // the KEY_SERVICES list is a Beeper-backed account. Watch
                                          // groups natives first, then a "Beeper" divider, then the
                                          // flagged ones. Absent => watch treats every service as
                                          // native and draws no divider (additive; old builds unaffected).

    val KEY_REPLY_TO_INDEX: UInt = 60u // watch->phone: when replying from the DETAIL
                                       // view, the target message's index in the open
                                       // thread (the reply-to / react target). Sent
                                       // alongside KEY_REPLY_TEXT. Absent or -1 => a
                                       // normal chat-view reply (plain message to the
                                       // conversation). The phone routes by the owning
                                       // service's opt-in capability; unsupported => it
                                       // falls back to a plain message (additive — old
                                       // watch builds that never send this are unaffected).
    val KEY_ACTIVE_CAN_REACT: UInt = 61u // phone->watch: 1 if the currently active
                                       // service supports reaction-send, else 0. Lets
                                       // the watch predict whether a single-emoji send
                                       // from the detail view will become a reaction
                                       // (stay scrolled) vs a message (jump to bottom).
                                       // Absent => watch defaults to 0 (message).
    val KEY_THREAD_TOKEN: UInt = 62u   // phone->watch (thread + image frames): a stable
                                       // uint32 hash of the convoId. The watch keys its
                                       // thumbnail cache by (token, row) so same-index
                                       // images in different chats don't collide. Absent
                                       // => watch uses token 0 (index-only, legacy).

    val KEY_REACTION_ONLY: UInt = 63u  // phone->watch (OP_MSG): 1 => this thread re-push
                                       // is a reaction/badge-only update; the watch keeps
                                       // the current scroll position (s_react_keep_pos)
                                       // instead of snapping to the newest row. Absent/0
                                       // => normal push, jumps to newest as before.

    // ---- Convo-list pagination (watch pages the full list, CONVO_PAGE_SIZE per
    // page, one page in watch memory). Must match main.c's KEY_* values. ----
    val KEY_CONVO_MORE: UInt      = 64u // phone->watch (OP_CONVO): 1 if conversations
                                        // exist beyond the page being sent (drives the
                                        // watch's "next page" row), else 0/absent.
    val KEY_REQ_CONVO_PAGE: UInt  = 65u // watch->phone: the 0-based page the watch wants;
                                        // phone re-streams that page into indices 0..N-1.
    val KEY_CONVO_PAGE: UInt      = 66u // phone->watch (OP_CONVO): which 0-based page this
                                        // push represents. Sent on EVERY convo push; the
                                        // watch treats it as authoritative and syncs its
                                        // page, so a page-0 refresh snaps a paged-forward
                                        // watch back and KEY_REQ_OPEN offsets stay correct.

    // ---- Hierarchical navigation (nav-context lists: DMs bucket / servers /
    // channels for Discord, and later Telegram folders). The watch is depth-agnostic:
    // each entry is OPEN or ENTER, and BACK climbs when the list has a parent. Must
    // match main.c's KEY_* values. ----
    val KEY_REQ_ENTER: UInt       = 67u // watch->phone: drill into the current list's
                                        // absolute entry n (same page math as
                                        // KEY_REQ_OPEN); -1 => pop up one level. Either
                                        // way the phone re-streams the new context page 0.
    val KEY_LIST_HAS_PARENT: UInt = 68u // phone->watch (OP_CONVO): 1 if the list being
                                        // sent is NOT an account's top level, so the
                                        // watch's BACK climbs a level instead of leaving.
    val KEY_ENTRY_KIND: UInt      = 69u // phone->watch (OP_CONVO, per entry): 0/absent =
                                        // OPEN (a real chat/channel -> KEY_REQ_OPEN),
                                        // 1 = ENTER (a bucket/server/folder -> the watch
                                        // sends KEY_REQ_ENTER to drill in).

    val KEY_THREAD_FEED: UInt     = 130u // phone->watch (OP_MSG, step 85): this chat is a feed
                                        // (Mirror.setFeed): the watch shows it newest on top.
    val KEY_SPAN_REPLY: UInt      = 139u // phone->watch (OP_MSG, step 96): this frame answers the
                                        // watch's KEY_REQ_MSG_SPAN (its start); a late one is
                                        // dropped there, never taken as a fresh push
    // Reply rooms (step 99, Rooms): a feed post's replies, the post at the top, shown as a
    // feed of their own; Back goes back up the chain.
    val KEY_REQ_ROOM: UInt        = 140u // watch->phone: open the room of message (v and 0xFFFF)
                                        // of the chat on screen, (v shr 16) rooms deep (a
                                        // request from another depth is stale: ignored)
    val KEY_ROOM_BACK: UInt       = 141u // watch->phone: Back out of a room, to this depth
                                        // (0 = the feed)
    val KEY_ROOM_DEPTH: UInt      = 142u // phone->watch (every thread frame of a room, and the
                                        // feed's after a Back: 0): how deep it is
    val KEY_LAND_ABS: UInt        = 143u // phone->watch (a room's or the feed's push): land on
                                        // this message (the post a Back came from, a reply sent)
    // Emoji actions (step 100, FeedActions in Rooms.kt): a feed's own actions on a post (a
    // like, a vote), rows of their own at the top of the watch's reply menu, sent at once.
    val KEY_THREAD_ACTIONS: UInt  = 144u // phone->watch (every thread frame of a feed or room
                                        // that has them): up to 3 FeedActions codes, 4 bits
                                        // each from the low end, how many in bits 12-15
    val KEY_REQ_ACTION: UInt      = 145u // watch->phone: action (v shr 16) on message
                                        // (v and 0xFFFF) of the chat on screen
    // Calls from the watch (Calls.kt): a Call row in the watch's reply menu, under Emoji.
    val KEY_THREAD_CALL: UInt     = 148u // phone->watch (every thread frame of a chat Calls
                                        // can call, to a watch that says IMGCAP_CALL): 1
    val KEY_REQ_CALL: UInt        = 149u // watch->phone: call the chat on screen; v = its
                                        // thread token (a stale one calls no one)
    // Reactions from the watch's reply menu (its React row, and the emoji list it opens):
    // asked for outright, so a reaction never goes as a message.
    val KEY_REQ_REACT: UInt       = 150u // watch->phone (text): react with this emoji to
                                        // message KEY_REPLY_TO_INDEX (with KEY_REQ_HASH) of
                                        // the chat KEY_DRAFT_TOK names
    // New chat (People.kt): the watch's switcher starts a chat with someone on an account.
    // The people come as a list level (a BACK row above them), filtered by the screen's
    // own draft (its token, People.TOKEN), and Select on one opens the chat with them.
    val KEY_NEWCHAT_MASK: UInt    = 151u // phone->watch (with KEY_SERVICES): bit i set =
                                        // switcher entry i can start chats
    val KEY_REQ_NEWCHAT: UInt     = 152u // watch->phone: the people of switcher entry v
    val KEY_PEOPLE: UInt          = 153u // phone->watch (every list frame of the people):
                                        // the screen's token, which its draft ops name
    val KEY_THREAD_GREW: UInt     = 138u // phone->watch (OP_MSG of a feed, step 95): these rows
                                        // were fetched in the background and go below what
                                        // the watch holds, its view unmoved (KEY_LIST_ABS =
                                        // the old total, KEY_LIST_TOTAL the new)
    // Albums (step 88): several pictures or videos in one row. The chat shows them as
    // one collage (drawn here); the detail view shows one at a time and pages through
    // them. A row's item count rides its thread-blob record (field 11).
    val KEY_IMG_ITEM: UInt        = 131u // watch->phone (image, video and zoom requests): which
                                        // of the album's items; phone->watch (an item's START,
                                        // GIF START or empty START): which item it is
    val KEY_ALBUM_VIDEO: UInt     = 132u // phone->watch (an item's empty START): the item is a
                                        // video: play it (KEY_VID_REQ with KEY_IMG_ITEM)
    // The notification sound (step 89): the Time 2 plays it with its buzz when a message
    // pops up there (never while the watch is muted or in Quiet Time). With one, the
    // message's video follows the watch's sound switch; with none, it starts silent.
    // Mirror.pushTone.
    val KEY_PUSH_TONE: UInt       = 133u // phone->watch (a force-open's thread push): the sound,
                                        // 0 = none (an older phone sends none: the watch's first)
    val KEY_TONE_TEST: UInt       = 134u // phone->watch, alone (or with KEY_SOUND): play this
                                        // sound now; watch->phone: 1 it played, 0 the watch is
                                        // muted or in Quiet Time, so it didn't
    // The watch's switcher has a sound row (step 91, Time 2): a bell above ACCOUNTS.
    val KEY_SOUND: UInt           = 137u // phone->watch (with the account list, and when it
                                        // changes): the sound, 0 = off; watch->phone: 1 turn
                                        // it on (Mirror.lastTone), 0 off; 0x100 | level: on
                                        // at that level (step 111)
    val KEY_TONE_VOL: UInt        = 147u // phone->watch, with KEY_SOUND (step 111): how loud,
                                        // 1 low, 2 medium, 3 high (Mirror.toneVol + 1)
    // Contact photos (step 90): a chat-list row whose record carries a photo key (the
    // sixth field, 8 hex digits: Avatars.rowKey) shows the photo in its avatar's circle.
    val KEY_AV_REQ: UInt          = 135u // watch->phone, bytes: [u8 dd][u32 key]... the photos
                                        // missing from the rows on its screen, dd px across
    val KEY_AV_DATA: UInt         = 136u // phone->watch, bytes: records [u32 key][u8 dd][u8 kind]
                                        // + kind 8: dd*dd GColor8 pixels (corners clear); 0 = none
    val KEY_THREAD_LABEL: UInt    = 70u // phone->watch (OP_MSG): detail-view banner label.
                                        // "<network>", or "<network>\n<server>#<channel>"
                                        // for drillable accounts (Discord channels). The
                                        // watch draws two lines iff a '\n' is present.

    // ---- Message-view (thread) pagination. The watch holds one page (MSG_PAGE_SIZE)
    // as plain rows 0..N-1 and stays ignorant of paging; the PHONE owns the offset
    // (page*MSG_PAGE_SIZE) when resolving any row-indexed request (reply/react target,
    // image, full-text). Page 0 = newest page; higher pages are older. Mirrors the
    // convo-list pagination (KEY_REQ_CONVO_PAGE / KEY_CONVO_PAGE / KEY_CONVO_MORE).
    val KEY_REQ_MSG_PAGE: UInt    = 71u // watch->phone: the 0-based message page to
                                        // (re)stream for the OPEN thread (up = older).
    val KEY_MSG_PAGE: UInt        = 72u // phone->watch (OP_MSG): which page this push is;
                                        // the watch syncs its page authoritatively from it.
    val KEY_MSG_MORE_OLDER: UInt  = 73u // phone->watch (OP_MSG): 1 if older messages exist
    // 74 is NOT free: the watch defines it as KEY_LICENSED (PKJS->watch entitlement
    // status on the watch/PKJS side). Never assign 74 phone-side.
    val KEY_MARK_THREAD_READ: UInt = 75u // watch->phone: mark this convo read
                                        // before this page (drives the watch's up-arrow).
    val KEY_THREAD_CLOSED: UInt = 90u  // watch->phone: the message-list window left the
                                       // watch's window stack (back to the convo list,
                                       // account switch, or app exit). Payload always 0.
                                       // Clears currentOpenConvoId so live repaints stop
                                       // and the same-chat IAN exception (a Pebble
                                       // notification must never pop over the chat being
                                       // viewed) stays honest after a back-out.
                                       // Fire-and-forget + idempotent; a drop self-heals
                                       // on the next thread open or watchapp close.

    val KEY_IMG_START: UInt  = 30u
    val KEY_IMG_SEQ: UInt    = 31u
    val KEY_IMG_DATA: UInt   = 32u
    val KEY_IMG_END: UInt    = 33u

    const val OP_CONVO  = 1
    const val OP_MSG    = 2
    const val OP_IMG    = 3
    // Reaction patch: repaint ONE message's reaction badge by msgId match, no window
    // machinery (separate op so it can't trip span/fresh classification or a base
    // reset — the bug that blanked the view when a reaction re-pushed the newest span).
    const val OP_REACT_PATCH = 4

    // Conversations sent per watch page. MUST match main.c's MAX_CONVOS (the watch
    // holds exactly one page in memory). KEY_IDX/KEY_COUNT on a convo push are
    // page-relative (0..CONVO_PAGE_SIZE-1); KEY_REQ_OPEN from the watch is absolute
    // (page*CONVO_PAGE_SIZE + row).
    const val CONVO_PAGE_SIZE = 10

    // Messages per thread page — the watch holds exactly one page and addresses it
    // as rows 0..MSG_PAGE_SIZE-1; the phone adds page*MSG_PAGE_SIZE to reach the real
    // message. Single source of truth: a future "messages per page" setting flips
    // this one value (watch + phone must agree on the active size). MUST match the
    // watch's per-page cap.
    const val MSG_PAGE_SIZE = 20

    // Open-view targets (KEY_OPEN_VIEW).
    const val VIEW_CHAT     = 0   // land on the message thread (chat view)
    const val VIEW_ENHANCED = 1   // land on the enhanced single-message view

    val WATCH_UUID: UUID = UUID.fromString("4fe41bb2-f27f-41d0-9bf0-e3b8e206193f")

    const val IMG_MAX_W = 144          // full size (enhanced view) - edge to edge
    const val IMG_MAX_H = 111
    const val IMG_SMALL_W = 80         // chat-view preview size
    const val IMG_SMALL_H = 74
    const val IMG_CHUNK_SIZE = 3800   // bytes per chunk frame. The watch inbox is 4096
                                      // (opened for the thread blob); a chunk frame is
                                      // data(3800)+seq+band+dict overhead ≈ 3830. The old
                                      // 380 dated from a 512-byte inbox and cost a full
                                      // AppMessage round-trip per 380 bytes — 10× the
                                      // frames. This constant is now PHONE-ONLY slicing:
                                      // KEY_IMG_SEQ carries the chunk's BYTE OFFSET, so
                                      // the watch needs no matching constant (the old
                                      // "MUST match main.c" footgun is retired).
    const val IMG_BUDGET_FLOOR = 8000  // min bytes for a full image if watch reports too little
}
