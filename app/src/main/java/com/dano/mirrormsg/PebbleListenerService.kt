package com.dano.mirrormsg

import android.util.Base64
import android.util.Log
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class PebbleListenerService : BasePebbleListenerService() {

    // Serialize image streaming: only ONE image transfer in flight at a time so
    // chunks never interleave (interleaving corrupted the watch bitmap AND
    // leaked heap). A newer request supersedes the latest pending one.
    internal val imgMutex = Mutex()
    // Track in-flight image-stream jobs so a PRIORITY request (user opened the
    // enhanced view) can cancel everything else and stream the wanted image now.
    private val imgJobs = java.util.Collections.synchronizedList(mutableListOf<kotlinx.coroutines.Job>())
    private fun cancelAllImageJobs() {
        synchronized(imgJobs) {
            imgJobs.forEach { it.cancel() }
            imgJobs.clear()
        }
        LogStore.i(TAG, "Cancelled all in-flight image jobs (priority request)")
    }
    @Volatile private var latestImgRequest = -1

    // ---- Chat-row preview cache ----------------------------------------------
    // A preview is encoded once and kept, so the watch asking for it again —
    // scrolling back, reopening the chat, its pool having pushed it out — costs
    // only the stream. The previews around the one the watch asks for are encoded
    // ahead (warmThumbs), a couple at a time, so its next requests skip the
    // download too. Full pictures are never kept: they're sized to the watch's
    // memory at the moment it asks.
    private val thumbCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > THUMB_CACHE_MAX
    }
    private val thumbBuilding = HashMap<String, Deferred<String>>()   // under thumbLock
    private val thumbLock = Any()
    private val thumbWarmGate = Semaphore(THUMB_WARM_PARALLEL)
    private val thumbWarmJobs = java.util.Collections.synchronizedList(mutableListOf<kotlinx.coroutines.Job>())

    // Idempotency guard for KEY_MARK_THREAD_READ. The watch fires that key on
    // EVERY appearance of a thread (including reply-overlay close onto an already-
    // visible thread), so we get duplicates by design. markRead is itself a no-op
    // on an already-read thread, but this skips the redundant network round-trip:
    // remember the last "convoId:newestMsgId" we marked and ignore an identical
    // repeat. Cleared implicitly whenever a genuinely newer message arrives (the
    // newestMsgId changes), so a new message in an open thread still re-marks.
    @Volatile private var lastMarkedRead: String? = null

    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        // A like (step 101: a heart, as X and Instagram show one), and what marks a badge
        // with the user's own reaction (or like) among it: the watch shows it magenta.
        private const val LIKE_GLYPH = "\u2764"
        private const val BADGE_MINE = "\u0001"
        private const val TAG = "MirrorMsgSpike"
        private const val FETCH_COUNT = 80L   // per-thread history depth
        // Full conversation-list depth we build + page through. The watch shows
        // Schema.CONVO_PAGE_SIZE per page; this is how many total we keep indexed
        // (so ~CONVO_CAP/CONVO_PAGE_SIZE pages). Was 50 (5 pages) — raised to 20
        // pages. Cheap: only the current page is streamed; this just caps how deep
        // the index (and each list fetch) goes.
        // Slowest animation playback still worth showing in the detail view. The
        // encoder drops frames toward this before it starts shrinking the picture.
        internal const val GIF_FPS_FLOOR = 8
        // Rungs in the encoder's degradation ladder. Once a retry has stepped past
        // the last one there is nothing smaller to send, so the still image is the
        // only remaining option.
        internal const val GIF_LADDER_STEPS = 10
        // Animated chat-row previews: a fixed 2fps, and at most one thumbnail
        // slot's worth of watch heap — what a full-colour still preview at the
        // preview box may occupy — so they fit the memory the thumbnail setting
        // already allows. The Go encoder's thumbnail ladder has 5 rungs.
        private const val THUMB_ANIM_FPS = 2
        // A watch 200 px wide (the Time 2) gets previews in a box 1.33x the old one's
        // (step 103); a narrower one keeps Schema's 80 x 74.
        private const val PREVIEW_W_BIG = 106
        private const val PREVIEW_H_BIG = 98
        internal const val THUMB_ANIM_LADDER_STEPS = 5
        // Chat-row preview cache: encodes kept, rows either side of a request whose
        // previews are encoded ahead, and how many of those run at once.
        private const val THUMB_CACHE_MAX = 64
        private const val THUMB_WARM_ROWS = 6
        private const val THUMB_WARM_PARALLEL = 2
        private const val THUMB_ENCODE_MS = 45_000L
        // Albums (step 88): at most this many items in one; separate pictures sent
        // within this long of each other (microseconds) are one album; and the chat's
        // collage is this wide on a 200px watch (proportionally narrower on others), 4:3.
        private const val ALBUM_MAX = 30
        // Step 110: watch -> phone with every row-indexed request (a picture, a video,
        // full text, a reply's or reaction's target, a feed action, a room): the
        // message's id hash (FNV-1a of its field 10, as the watch keeps it), so the
        // request reaches that message even when the watch's rows are behind the
        // phone's. Must match main.c / mm_shared.h's KEY_REQ_HASH.
        private val KEY_REQ_HASH: UInt = 146u
        private const val ALBUM_GAP_US = 8_000_000L
        private const val ALBUM_THUMB_W = 160
        // A collage of two (step 103): as tall as its pictures' shapes make it, at least
        // this, at most a single preview's height.
        private const val ALBUM_TWO_MIN_H = 40
        // The collage's gap between tiles (px), how long its tiles' pictures are waited
        // for, and how many of those pictures are kept (an album sent as separate
        // messages grows one picture at a time, each a new collage).
        private const val ALBUM_GAP_PX = 2
        private const val ALBUM_TILE_MS = 30_000L
        private const val ALBUM_TILES_KEPT = 8
        // Detail-view pictures and animations: encodes kept (the watch's room for them
        // is the same every time now), how long a wait for one lasts, and how many
        // are encoded ahead of being opened at once.
        private const val FULL_CACHE_MAX = 6
        private const val FULL_ENCODE_MS = 90_000L
        private const val FULL_PREP_PARALLEL = 1
        // The largest message the watch's inbox takes (4096) less a margin: frames
        // folded together (IMGCAP_FOLD) stay within it.
        internal const val FOLD_MAX = 4000
        // DISABLED (0 = no wire cap). This once capped an animation's total bytes
        // to bound how long the user waited before anything moved — but the watch
        // now plays progressively, starting as soon as two frames have landed and
        // lengthening its loop as the rest stream in. There is no longer a wait to
        // shorten, so the only real limit is what the watch can hold, and capping
        // size beyond that just threw away colours and resolution for nothing.
        // Set this to a byte count to bring the cap back.
        private const val GIF_WIRE_BUDGET = 0
        private const val CONVO_CAP = 200
        // Leading spaces before the ENTER "drill-in" arrow (watch text is left-
        // aligned, so padding pushes it rightward). Tuning reference: ~30 rode the
        // RIGHT edge; ~22 was the original; 15 sat MIDDLE. 29 = on the edge with a
        // one-space gap.
        private const val ENTER_ARROW_PAD = 29
        // Extra leading spaces added on the wider 200-wide Emery (Pebble Time 2) so
        // the drill-in arrow reaches its right edge. 29 + 4 = 33. Flat per-platform
        // bump — nudge this if the arrow lands short (raise) or overshoots (lower).
        private const val EMERY_ARROW_EXTRA = 3
        // Row count for a FRESH/unsolicited convo span (on-open origin, drill result,
        // account switch, force-open, reorder) — these aren't a response to a
        // KEY_REQ_SPAN so they carry no watch-sent count. Fill the LARGEST window (20,
        // Emery) so the window is complete on open with no scroll needed. Basalt-safe:
        // the watch's reception guard (slot >= MAX_CONVOS => drop) silently ignores
        // rows 10-19 on a 10-slot Basalt window — no overflow. Emery takes all 20. The
        // watch's geometry-based prefetch (fires near the window edge, not at row 0)
        // won't top up a half-filled window on its own, so the fresh push must fill it.
        private const val DEFAULT_SPAN_SLOTS = 20
        // Blob field/record separators, shared by the thread blob and the convo-span
        // blob. US (0x1f) between fields, RS (0x1e) between records. Neither can
        // appear in names/snippets (fit + sanitized), so no escaping is needed.
        private const val BLOB_US = "\u001f"
        private const val BLOB_RS = "\u001e"
        // Cap for a convo-span blob (Watch Claude's inbox is sized for the 4096 thread
        // blob; 2KB keeps convo spans comfortably under it). 20 rows ~1KB, so this
        // clamp is effectively never hit; if a span would exceed it we send as many
        // whole records as fit and the watch re-requests the remainder.
        private const val CONVO_BLOB_BYTE_CAP = 2048
        // Contact photos (step 90): records packed per KEY_AV_DATA message, under the
        // watch's 4096-byte inbox (the biggest record, 45 px, is 2,031 bytes).
        private const val AV_MSG_BYTES = 3600
        // 3b backfill: trigger a fetch of older history when a message-span request
        // reaches within BACKFILL_MARGIN of the loaded floor (early, so the fetch
        // usually completes before the reader hits the wall). BACKFILL_BATCH = how many
        // older messages to pull per fetch (matches the eager FETCH_COUNT feel).
        private const val BACKFILL_MARGIN = 15
        private const val BACKFILL_BATCH = 80L
        // How long after a backfill fetch to suppress live pushes for that convo, so
        // the bridge's async re-emit of the fetched older messages (which trail the
        // fetch by ~1-2s) doesn't fire a jump-to-newest fresh push. Covers the tail
        // with margin; a genuine new message in this window still stores and appears
        // on the next span (just no instant jump — fine during an active scroll).
        private const val BACKFILL_QUIET_MS = 4000L
        // Minimum gap between backfills for one convo — breaks the shift->refetch
        // runaway loop. Longer than the watch's post-growth re-request (sub-second),
        // shorter than a human scrolling through a fresh 80-message batch.
        private const val BACKFILL_COOLDOWN_MS = 2500L
        // A feed that opens short (step 95): at most this many of its pages are fetched
        // ahead in the background, to fill the watch's window.
        private const val FEED_FILL_PAGES = 4
        // Max age of a stored message allowed to override a bridge's convo-list
        // preview (see convoSnippet). Generous enough to cover "sent today, list
        // still shows yesterday"; bounded so a stale store entry can't mask a
        // genuinely newer bridge preview indefinitely. 24h.
        private const val SNIPPET_STORE_MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val MSG_TEXT_MAX = 135
        private const val FULL_TEXT_MAX = 900   // cap for on-demand full text (one AppMessage)
        private const val DRAFT_SHOW_MAX = 120  // bytes of a draft the watch shows (its DRAFT_MAX)
        private const val KB_FULL_MAX = 700     // bytes of a draft the keyboard edits (it holds 1000)
        private const val SENDER_MAX = 22
        // Quoted-reply line caps (field 8): keep enough of the quoted message to
        // recognize it; a long quote is cut with "…" and the user scrolls up to
        // the original for the rest.
        // Fixed-width watch buffers (bytes, leaving room for the null): the bubble
        // sender and the reply target are sender[16]/target[16]; the reply snippet is
        // snippet[24]. We pre-fit to these so the watch never has to silently hard-clamp.
        private const val WATCH_NAME_MAX_BYTES = 15
        private const val WATCH_SNIPPET_MAX_BYTES = 23
        // The watch inbox is a hard 4096-byte AppMessage dict and KEY_THREAD_BLOB
        // is a single un-chunked tuple, so blob + other tuples + framing must fit.
        // Keep the blob comfortably under, leaving ~300B for the other tuples.
        private const val BLOB_BYTE_BUDGET = 3800
        private const val TEXT_FRAME_DELAY = 50L
        // (IMG_CHUNK_DELAY removed: chunk pacing is result-paced via the
        // suspend sendDataToPebble + retry in sendImgFrame, not an open-loop sleep.)

        // Live reference to the running service so the activity's live-message
        // handler can push thread updates without re-implementing streaming.
        @Volatile var instance: PebbleListenerService? = null

        /** Called from MainActivity when a live MESSAGE arrives for a convo. */
        fun onLiveMessage(convoId: String) = scheduleThreadPush(convoId, reactionOnly = false)

        /**
         * Called when only a REACTION changed on a convo (no new message). Carries
         * the specific message ID whose reactions changed, so the debounced fire can
         * send a targeted OP_REACT_PATCH (repaint that one badge in place) instead of
         * re-pushing a message span — which, when the reader was scrolled up, streamed
         * the newest span over their view and blanked it. If msgId is unknown (older
         * callers), it falls back to a reaction-only thread re-push.
         */
        fun onLiveReaction(convoId: String, msgId: String = "") {
            if (msgId.isNotEmpty()) synchronized(debounceJobs) {
                changedReactionIds.getOrPut(convoId) { LinkedHashSet() }.add(msgId)
            }
            scheduleThreadPush(convoId, reactionOnly = true)
        }

        private fun scheduleThreadPush(convoId: String, reactionOnly: Boolean) {
            val svc = instance ?: return
            // History/realtime messages arrive in bursts (20-88 at once). Pushing
            // the whole thread to the watch on EVERY message floods it with dozens
            // of partial re-renders and scrambles the view. Debounce: restart a
            // short timer on each message and only push once it goes quiet.
            synchronized(debounceJobs) {
                // The coalesced push stays reaction-only ONLY if every contributor in
                // this window was reaction-only; a single real message in the burst
                // makes it a normal push (jump to newest).
                val prev = pendingReactionOnly[convoId]
                pendingReactionOnly[convoId] = (prev ?: true) && reactionOnly
                debounceJobs.remove(convoId)?.cancel()
                debounceJobs[convoId] = svc.scope.launch {
                    delay(DEBOUNCE_MS)
                    val ro = synchronized(debounceJobs) {
                        debounceJobs.remove(convoId)
                        pendingReactionOnly.remove(convoId) ?: reactionOnly
                    }
                    val changedIds = synchronized(debounceJobs) {
                        changedReactionIds.remove(convoId) ?: LinkedHashSet()
                    }
                    // An IAN sequence is in flight for this convo: its ENHANCED push
                    // streams the thread AND the list itself. Firing the repaint now
                    // would land a VIEW_CHAT fresh push BEFORE it — consuming the
                    // watch's new-message delta and poisoning same-chat detection
                    // (the dual-push bug). Skip entirely; nothing here isn't already
                    // carried by the force-open. (Window is lifted the moment the
                    // ENHANCED push is out, so later stages repaint normally.)
                    if (System.currentTimeMillis() < (Mirror.forceOpenQuietUntil[convoId] ?: 0L)) {
                        LogStore.i(TAG, "Debounced repaint skipped (IAN in flight) for $convoId")
                        return@launch
                    }
                    if (Mirror.currentOpenConvoId == convoId) {
                        if (ro) {
                            // Reaction-only: patch by ID, never re-stream a span (a span
                            // push blanked the view when scrolled up). changedIds may be
                            // empty (e.g. FB's bulk "something changed" signal) — then
                            // pushReactionPatches patches ALL currently-reacted messages;
                            // the watch applies the resident ones, ignores the rest.
                            svc.pushReactionPatches(convoId, changedIds)
                        } else {
                            // A real new message in the burst: normal newest-span push.
                            svc.pushThreadUpdate(convoId, reactionOnly = false)
                        }
                    }
                    svc.refreshConversationList()
                }
            }
        }

        private const val DEBOUNCE_MS = 400L
        private val debounceJobs =
            java.util.Collections.synchronizedMap(HashMap<String, kotlinx.coroutines.Job>())
        // Per-convo set of message IDs whose reactions changed during the current
        // debounce window, drained on fire into OP_REACT_PATCH emits. Guarded by the
        // debounceJobs monitor (same lock as pendingReactionOnly).
        private val changedReactionIds = HashMap<String, LinkedHashSet<String>>()
        // Per-convo: is the pending debounced push reaction-only? Guarded by the
        // debounceJobs monitor (only touched inside synchronized(debounceJobs)).
        private val pendingReactionOnly = HashMap<String, Boolean>()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // The service can start without MainActivity ever being created (after a
        // reboot, or an OS-triggered service restart). MainActivity is the only
        // other place that loads settings, so without this the persisted
        // activeServiceId never loads and the watch is served the wrong account's
        // list on open. Ensure appContext + settings are loaded here too.
        if (Mirror.appContext == null) Mirror.appContext = applicationContext
        Mirror.loadSettings(applicationContext)
        LogStore.i(TAG, "Service onCreate: activeServiceId=${Mirror.activeServiceId}")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Re-stream the stored thread to the watch (used for live updates). */
    // 3b backfill: when a message-span request reaches within BACKFILL_MARGIN of the
    // loaded floor and older history remains (cursor present, not exhausted), fetch
    // the next BACKFILL_BATCH from Google and merge it. Sends KEY_SPAN_ACK first so the
    // watch holds its watchdog through the (possibly >2s) blocking fetch. Google-only:
    // other networks never capture a cursor, so this returns early for them. Serialized
    // per convo by backfillMutex so overlapping span requests don't double-fetch.
    private val backfillMutex = Mutex()
    private suspend fun maybeBackfill(convoId: String, v: Int, n: Int) {
        val nn = if (n > 0) n else 20
        // Cheap pre-checks outside the lock.
        if (System.currentTimeMillis() < (Mirror.backfillCooldownUntil[convoId] ?: 0L))
            return                                              // cooldown: break the shift->refetch loop
        val total0 = buildTrimmedCached(convoId).size
        if (v + nn < total0 - BACKFILL_MARGIN) return           // not near the floor yet
        if (Mirror.backfillExhausted[convoId] == true) return   // true top reached
        Mirror.backfillCursor[convoId] ?: return                // no cursor (non-Google / not fetched)
        // Only ONE backfill in flight at a time. If one's already running (fast scroll
        // fired several triggers), SKIP this one rather than queueing a pile of blocking
        // Google fetches — the watch re-requests as it scrolls, so more history still
        // loads once the current fetch finishes. Prevents the runaway cascade where
        // total rocketed 345->599 in 2s from stacked fetches.
        if (!backfillMutex.tryLock()) return
        try {
            // Re-check under the lock (another request may have just backfilled).
            val total = buildTrimmedCached(convoId).size
            if (v + nn < total - BACKFILL_MARGIN) return
            if (Mirror.backfillExhausted[convoId] == true) return
            val curNow = Mirror.backfillCursor[convoId] ?: return
            val svc = Mirror.activeService() ?: return
            // Suppress live pushes for this convo through the fetch AND the async tail
            // of MESSAGE re-emits that follow it, so backfilled older messages merge
            // silently instead of triggering a jump-to-newest fresh push.
            Mirror.backfillQuietUntil[convoId] =
                System.currentTimeMillis() + BACKFILL_QUIET_MS
            // Tell the watch to hold its span watchdog for v — the fetch may exceed 2s.
            sendSpanAck(v)
            LogStore.i(TAG, "Backfill: fetching $BACKFILL_BATCH older for $convoId (v=$v total=$total)")
            try {
                val arr = fetchBelow(svc, convoId, curNow)   // blocking older-history fetch; cursor passed VERBATIM (opaque per-network anchor)
                if (Rooms.isRoom(convoId) && Rooms.get(convoId) == null) return   // (a room left meanwhile)
                if (arr.length() == 0) {
                    Mirror.backfillExhausted[convoId] = true   // nothing older -> true top
                    LogStore.i(TAG, "Backfill: empty batch -> $convoId at true top")
                    return
                }
                val added = mergeBelow(convoId, arr)
                val newTotal = buildTrimmedCached(convoId).size
                LogStore.i(TAG, "Backfill: merged $added ($total -> $newTotal) for $convoId")
                // Cooldown before the next backfill for this convo, so the watch's
                // post-growth position-shift doesn't immediately re-trigger a fetch
                // (the runaway-to-top loop). Human scrolling is far slower than this.
                Mirror.backfillCooldownUntil[convoId] =
                    System.currentTimeMillis() + BACKFILL_COOLDOWN_MS
                // Refresh the quiet window to cover re-emits that trail the merge.
                Mirror.backfillQuietUntil[convoId] =
                    System.currentTimeMillis() + BACKFILL_QUIET_MS
                // All dupes / no growth -> we've reached the top of what Google gives
                // beyond the store; stop to avoid a fetch loop.
                if (newTotal <= total) Mirror.backfillExhausted[convoId] = true
            } catch (e: Exception) {
                LogStore.e(TAG, "Backfill failed for $convoId", e)
            }
        } finally {
            backfillMutex.unlock()
        }
    }

    // Send KEY_SPAN_ACK(v): the watch extends its span watchdog (2s -> 15s) so a slow
    // backfill fetch can't fire it and get the late blob misread as a fresh push.
    private suspend fun sendSpanAck(v: Int) {
        try {
            val frame = HashMap<UInt, PebbleDictionaryItem>()
            frame[Schema.KEY_SPAN_ACK] = PebbleDictionaryItem.Int32(v)
            Mirror.sender(this).sendDataToPebble(Schema.WATCH_UUID, frame, watches = null)
            LogStore.i(TAG, "Sent KEY_SPAN_ACK=$v (backfill in progress)")
        } catch (e: Exception) {
            LogStore.w(TAG, "sendSpanAck failed: ${e.message}")
        }
    }

    // Send a targeted reaction patch (OP_REACT_PATCH) per changed message: match by
    // stable msgId, repaint that one badge in place on the watch, no window touch.
    // The reaction set is pulled from ReactionStore in the SAME format as thread-blob
    // field 7, so the watch reuses its existing react-summary parse. A message that
    // isn't resident on the watch is simply ignored there (rides the next blob). This
    // replaces the reaction-only span re-push that blanked the view when scrolled up.
    suspend fun pushReactionPatches(convoId: String, msgIds: Collection<String>) {
        if (!watchSendAllowed()) return
        try {
            val sender = Mirror.sender(this)
            val flat = buildTrimmedCached(convoId)
            val rowById = HashMap<String, MsgRow>(flat.size)
            for (mm in flat) if (mm.messageId.isNotEmpty()) rowById.putIfAbsent(mm.messageId, mm)
            // An album's pictures (step 88) show their reactions on it (rowReactions),
            // and the watch knows it by its rowWatchId.
            val albumOf = HashMap<String, MsgRow>()
            for (mm in flat) if (mm.album.size >= 2) {
                albumOf[mm.messageId] = mm
                for (p in mm.album) if (p.messageId.isNotEmpty()) albumOf.putIfAbsent(p.messageId, mm)
            }
            // If no specific IDs were captured (e.g. a bulk "reactions changed" signal),
            // patch every message that currently has a reaction — the watch applies the
            // resident ones and ignores the rest. Never a span, so the view can't blank.
            // (A feed post's likes alone, step 99, aren't a reaction: not patched in bulk.)
            val targets: Collection<String> = if (msgIds.isNotEmpty()) msgIds
                else flat.mapNotNull { mm ->
                    mm.messageId.takeIf {
                        it.isNotEmpty() && rowReactions(mm.copy(likes = 0L, liked = -1), convoId).isNotEmpty()
                    }
                }
            val sent = HashSet<String>()
            for (id in targets) {
                if (id.isEmpty()) continue
                val album = albumOf[id]
                val watchId = if (album != null) rowWatchId(album).replace(BLOB_RS, "").replace(BLOB_US, "") else id
                if (!sent.add(watchId)) continue
                // (A row's own: a feed post's likes, step 99, before reactions.)
                val row = album ?: rowById[id]
                val set = if (row != null) rowReactions(row, convoId)
                          else ReactionStore.summaryForStamped(id, convoId, 0L)  // "" clears
                val frame = HashMap<UInt, PebbleDictionaryItem>()
                frame[Schema.KEY_OP]           = PebbleDictionaryItem.Int32(Schema.OP_REACT_PATCH)
                frame[Schema.KEY_REACT_MSG_ID] = PebbleDictionaryItem.Text(watchId)
                frame[Schema.KEY_REACT_SET]    = PebbleDictionaryItem.Text(set)
                sender.sendDataToPebble(Schema.WATCH_UUID, frame, watches = null)
                LogStore.i(TAG, "Reaction patch: msg ${id.takeLast(8)}${if (album != null) " (album)" else ""} set=\"$set\" convo $convoId")
            }
        } catch (e: Exception) {
            LogStore.e(TAG, "pushReactionPatches failed", e)
        }
    }

    suspend fun pushThreadUpdate(convoId: String, openView: Int = Schema.VIEW_CHAT, pnRow: Int = -1, reactionOnly: Boolean = false) {
        try {
            val json = MessageStore.loadAsThreadJson(this, convoId)
            // A reaction/badge re-push holds the reader's current page; a live NEW
            // Live update: push the newest span so a new message lands at the bottom.
            // reactionOnly rides along (the watch keeps its scroll position and just
            // repaints reaction badges for resident rows). No page concept anymore —
            // streamMessages defaults spanStart=-1 to the newest window.
            streamMessages(convoId, json, openView, pnRow, reactionOnly)
            LogStore.i(TAG, "Live thread update pushed for convo $convoId (view=$openView${if (reactionOnly) " reactOnly" else ""})")
        } catch (e: Exception) {
            LogStore.e(TAG, "pushThreadUpdate failed", e)
        }
    }

    /**
     * The multi-account pseudo-notification sequence. A PN makes its OWNING
     * account active, always: switch activeServiceId to the owner, refresh that
     * account's conversation list + switcher rail (so the watch shows the owner's
     * list, not whatever was active before), then push the enhanced frame for the
     * convo — carrying its row index in the just-pushed list (KEY_PN_ROW) so the
     * watch sets s_open_row to match. Ordering matters: list refresh BEFORE the
     * enhanced frame, so the row index is valid against the list the watch holds.
     *
     * This also fixes cross-account image serving: once the owner is active,
     * streamImage's Mirror.activeService() resolves to the owning adapter, so a
     * Beeper image is served by Beeper instead of by whatever account was active.
     */
    suspend fun pushForceOpen(convoId: String, openView: Int, ownerServiceId: String) {
        try {
            Mirror.currentMsgPage = 0   // force-open lands on the newest page
            // 1) Switch active account to the owner (persisted; flips the multiplexer).
            if (Mirror.activeServiceId != ownerServiceId) {
                Mirror.activeServiceId = ownerServiceId
                Mirror.activeTgFolder = 0   // force-open targets the owner's main context
                Mirror.activeDiscordGuild = ""   // ...and Discord's base (DMs) context
                LogStore.i(TAG, "pushForceOpen: active account -> $ownerServiceId (was different)")
            }
            // 2) Refresh that account's list, but stream the PAGE that contains the
            //    target so the watch syncs to it (KEY_CONVO_PAGE) and KEY_PN_ROW can
            //    stay page-relative. Inline (not repushConversations) so we stream the
            //    target's page directly instead of page 0 then jumping.
            val svc = Mirror.activeService()
            if (svc == null) {
                LogStore.w(TAG, "pushForceOpen: no active service")
                return
            }
            // Set the nav context so the streamed list CONTAINS the target and BACK
            // climbs coherently. Each account decides its own home: a DM drops into
            // "dms"; a Discord server channel drops into that server's channel list so
            // back-out lands there (not DMs). Because the streamed list is that context,
            // the target row also resolves — setting s_open_row on server channels.
            Mirror.navStack.clear()
            Mirror.navStack.addAll(svc.forceOpenNavContext(convoId))
            reportServiceList()
            val pinnedKey = Mirror.convoCacheKey()
            val isRoot = Mirror.navStack.isEmpty()
            val json = svc.listContext(CONVO_CAP, Mirror.navStack.toList())
            // Cache only the root list (see refreshConversationList); a drilled context
            // must not later be served as the account's root. Key pinned pre-fetch.
            if (isRoot && Mirror.convoCacheKey() == pinnedKey)
                Mirror.putCachedConvoJson(json, pinnedKey)
            val total = rebuildConvoIndex(json)
            // 3) Resolve the convo's ABSOLUTE row. The span protocol addresses the
            // watch with absolute indices throughout.
            val abs = Mirror.convoOrder.indexOf(convoId)
            if (abs < 0)
                LogStore.w(TAG, "pushForceOpen: convo $convoId not in owner's list (row=-1); s_open_row not set")
            // Fresh push => span from the top (frame-0 abs 0); the watch opens the
            // target via the ABSOLUTE KEY_PN_ROW, not a window position.
            streamConvoSpan(json, 0, total)
            val pnRow = abs                                   // absolute convo row
            // 4) Push the enhanced frame with the resolved row index.
            pushThreadUpdate(convoId, openView, pnRow)
            pushDraft(convoId)   // the chat's draft, if it has one, in its bar
            LogStore.i(TAG, "pushForceOpen: convo $convoId owner=$ownerServiceId abs=$abs pnRow=$pnRow view=$openView")
        } catch (e: Exception) {
            LogStore.e(TAG, "pushForceOpen failed", e)
        }
    }

    /** Re-fetch the conversation list and re-stream it so the watch's list
     *  updates live (new message bumps that convo to the top). */
    suspend fun refreshConversationList() {
        try {
            val svc = Mirror.activeService() ?: return
            // Pin context BEFORE the fetch: an account switch mid-fetch must not
            // let this list be cached under (or streamed as) the new account's.
            val pinnedKey = Mirror.convoCacheKey()
            val isRoot = Mirror.navStack.isEmpty()
            val json = svc.listContext(CONVO_CAP, Mirror.navStack.toList())
            if (Mirror.convoCacheKey() != pinnedKey) {
                LogStore.i(TAG, "Live refresh abandoned (account switched mid-fetch)")
                return
            }
            // Only the account root is cached (the fast open path); a drilled context
            // must not later be served as root.
            if (isRoot) Mirror.putCachedConvoJson(json, pinnedKey)
            streamConvos(json)
            LogStore.i(TAG, "Live conversation list refresh pushed")
        } catch (e: Exception) {
            LogStore.e(TAG, "refreshConversationList failed", e)
        }
    }

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier
    ): ReceiveResult {
        if (watchappUUID != Schema.WATCH_UUID) return ReceiveResult.Nack

        // Watch-reported display width (144 Basalt / 200 Emery), stamped on the
        // watchapp-open handshake and on image requests. Cache it before anything
        // else so the conversation list (built on open) and enhanced-image sizing
        // both use the real screen width. Absent => cache unchanged (defaults to 0 →
        // 144-equivalent behavior everywhere it's consumed).
        (data[Schema.KEY_REQ_DISPLAY_W] as? PebbleDictionaryItem.Int32)?.value?.let {
            if (it > 0 && it != Mirror.lastWatchW) LogStore.i(TAG,
                "Watch display width $it (was ${Mirror.lastWatchW}); emoji substitution " +
                (if (it > 144) "off for this watch" else "available"))
            if (it > 0) Mirror.noteWatchWidth(applicationContext, it)
        }
        (data[Schema.KEY_REQ_ANIM_THUMB] as? PebbleDictionaryItem.Int32)?.value?.let {
            Mirror.watchAnimThumbs = it == 1
        }
        // What the watch's image transport takes, and the room a full picture gets
        // there. Every thread open and image request carries both: one without them
        // is an older watch, which gets the plain framing and nothing made ahead.
        if (data[Schema.KEY_REQ_IMAGE] != null || data[Schema.KEY_REQ_OPEN] != null) {
            Mirror.watchCaps = (data[Schema.KEY_REQ_CAPS] as? PebbleDictionaryItem.Int32)?.value ?: 0
            Mirror.watchFullRoom = (data[Schema.KEY_FULL_ROOM] as? PebbleDictionaryItem.Int32)?.value ?: 0
        }

        val reqHash = (data[KEY_REQ_HASH] as? PebbleDictionaryItem.Int32)?.value ?: 0
        if (Media.engine.onWatchMessage(this, data, reqHash)) return ReceiveResult.Ack

        val replyText = (data[Schema.KEY_REPLY_TEXT] as? PebbleDictionaryItem.Text)?.value
        if (replyText != null) {
            val row = (data[Schema.KEY_REQ_OPEN] as? PebbleDictionaryItem.Int32)?.value
                ?: return ReceiveResult.Nack
            // Present (>=0) only when the reply came from the DETAIL view: it's the
            // target message's index for a reply-to / reaction. Absent => -1 => a
            // normal chat-view reply.
            val targetIndex = targetByHash((data[Schema.KEY_REPLY_TO_INDEX] as? PebbleDictionaryItem.Int32)?.value ?: -1, reqHash)
            LogStore.i(TAG, "Reply received for row $row (target=$targetIndex): ${replyText.length} chars")
            scope.launch { sendReply(row, replyText, targetIndex) }
            return ReceiveResult.Ack
        }

        // The keyboard: a word's taps, to read. Answered in order, on the keyboard's thread.
        (data[Schema.KEY_KB_REQ] as? PebbleDictionaryItem.Bytes)?.value?.let { req ->
            scope.launch(KbDecoder.dispatcher) { answerKb(req) }
            return ReceiveResult.Ack
        }

        // The compose bar and the keyboard: a chat's draft (add, clear, send; the
        // keyboard's ask for it whole, and its edited copy). The chat is the one the
        // watch names (its thread token), resolved here at once, so one opened right
        // after can't take it.
        val draftOp = (data[Schema.KEY_DRAFT_OP] as? PebbleDictionaryItem.Int32)?.value
        if (draftOp != null) {
            val tok = (data[Schema.KEY_DRAFT_TOK] as? PebbleDictionaryItem.Int32)?.value
            val convoId = draftChat(tok)
            if (convoId == null) {
                LogStore.w(TAG, "Draft op $draftOp for no known chat; ignored")
                return ReceiveResult.Ack
            }
            when (draftOp) {
                Schema.DRAFT_ADD -> {
                    val piece = (data[Schema.KEY_DRAFT_TEXT] as? PebbleDictionaryItem.Text)?.value.orEmpty()
                    val now = DraftStore.append(this, convoId, piece)
                    LogStore.i(TAG, "Draft: added ${piece.length} chars (now ${now.length})")
                }
                Schema.DRAFT_CLEAR -> {
                    DraftStore.set(this, convoId, "")
                    LogStore.i(TAG, "Draft cleared")
                }
                Schema.DRAFT_SEND -> {
                    val text = DraftStore.take(this, convoId)
                    val target = targetByHash((data[Schema.KEY_REPLY_TO_INDEX] as? PebbleDictionaryItem.Int32)?.value ?: -1, reqHash)
                    if (text.isNotBlank()) {
                        LogStore.i(TAG, "Draft send: ${text.length} chars (target=$target)")
                        val route = routeFor(convoId)
                        scope.launch { sendDraft(convoId, route, text, target) }
                    } else {
                        LogStore.w(TAG, "Draft send with nothing in the draft")
                    }
                }
                Schema.DRAFT_GET -> {
                    scope.launch(KbDecoder.dispatcher) { KbDecoder.warm(this@PebbleListenerService) }
                    scope.launch { sendFullDraft(convoId) }
                    return ReceiveResult.Ack
                }
                Schema.DRAFT_SET -> if (!draftPiece(convoId, data)) return ReceiveResult.Ack
                else -> LogStore.w(TAG, "Unknown draft op $draftOp")
            }
            scope.launch { pushDraft(convoId) }
            return ReceiveResult.Ack
        }

        // Diagnostic string from the watch (image stride/recv etc.) -> logcat.
        val diag = (data[Schema.KEY_DIAG] as? PebbleDictionaryItem.Text)?.value
        if (diag != null) {
            LogStore.i(TAG, "WATCH-DIAG: $diag")
            return ReceiveResult.Ack
        }

        // Account switcher: watch asked to switch to service index N (the index
        // into the same logged-in-service list we reported via KEY_SERVICES).
        // Flip the active service and re-push that service's conversations.
        val reqService = (data[Schema.KEY_REQ_SERVICE] as? PebbleDictionaryItem.Int32)?.value
        if (reqService != null) {
            // Map the selected index against the exact rail we last sent, so a
            // Telegram folder sub-account resolves to (tgnative, folderId) and a
            // real account resolves to (id, 0).
            val entry = Mirror.lastSwitcherEntries.getOrNull(reqService)
            if (entry != null &&
                (entry.serviceId != Mirror.activeServiceId ||
                    entry.folderId != Mirror.activeTgFolder ||
                    entry.guildId != Mirror.activeDiscordGuild)) {
                LogStore.i(TAG, "Watch switched account -> [${entry.label.trim()}] (idx $reqService svc=${entry.serviceId} folder=${entry.folderId} guild=${entry.guildId})")
                Mirror.activeServiceId = entry.serviceId
                Mirror.activeTgFolder = entry.folderId
                Mirror.activeDiscordGuild = entry.guildId
                scope.launch { repushConversations() }
            } else {
                LogStore.i(TAG, "Watch service switch ignored (idx $reqService)")
            }
            return ReceiveResult.Ack
        }

        // Span protocol: watch requests rows [v, v+slots) of the CURRENT list as its
        // window slides. Same context resolution as the legacy page request (root
        // cache vs drilled context fetched fresh), but span-addressed. Frame-0 abs==v
        // is guaranteed by streamConvoSpan so the watch matches it to this request.
        val reqSpan = (data[Schema.KEY_REQ_SPAN] as? PebbleDictionaryItem.Int32)?.value
        if (reqSpan != null) {
            // The watch sends its window size alongside the span request, so the
            // phone streams exactly the window the watch holds — no platform guessing.
            // Absent (shouldn't happen) => default to the larger window (20); it's
            // Basalt-safe via the watch's slot guard and fills Emery completely.
            val spanCount = (data[Schema.KEY_REQ_SPAN_COUNT] as? PebbleDictionaryItem.Int32)?.value
                ?: DEFAULT_SPAN_SLOTS
            if (spanCount > 0) Mirror.convoSpanCount = spanCount   // watch size (10 Basalt / 20 Emery); drives drill-arrow pad
            LogStore.i(TAG, "Watch requested convo span @$reqSpan count=$spanCount")
            scope.launch {
                val json = (if (Mirror.navStack.isEmpty()) Mirror.getCachedConvoJson() else null)
                    ?: Mirror.activeService()?.listContext(CONVO_CAP, Mirror.navStack.toList())
                if (json != null) {
                    val total = rebuildConvoIndex(json)
                    streamConvoSpan(json, reqSpan, total, spanCount)
                } else LogStore.w(TAG, "No convo list available for span")
            }
            return ReceiveResult.Ack
        }

        // (Legacy KEY_REQ_CONVO_PAGE handler removed — the span protocol via
        // KEY_REQ_SPAN fully replaces page requests. There is one watchapp and it
        // always speaks spans, so no page path remains.)

        // Hierarchical drill/climb: n >= 0 enters the current list's absolute entry n
        // (a server/bucket); -1 pops one level. Re-streams the new context's page 0.
        val reqEnter = (data[Schema.KEY_REQ_ENTER] as? PebbleDictionaryItem.Int32)?.value
        if (reqEnter != null) {
            LogStore.i(TAG, "Watch nav request: entry $reqEnter")
            scope.launch { navigateContext(reqEnter) }
            return ReceiveResult.Ack
        }

        // Reply rooms (step 99): open the room of a feed post with replies (the watch's
        // depth with it: a request from a depth already left is stale), or Back out of
        // one, to the depth the watch names.
        val reqRoom = (data[Schema.KEY_REQ_ROOM] as? PebbleDictionaryItem.Int32)?.value
        if (reqRoom != null) {
            val convoId = Mirror.currentOpenConvoId
            val at = if (convoId == null) (reqRoom and 0xFFFF) else rowByHash(convoId, reqRoom and 0xFFFF, reqHash, "Room")
            if (at >= 0) scope.launch { openRoom(reqRoom ushr 16, at) }
            return ReceiveResult.Ack
        }
        val roomBack = (data[Schema.KEY_ROOM_BACK] as? PebbleDictionaryItem.Int32)?.value
        if (roomBack != null) {
            scope.launch { leaveRoom(roomBack) }
            return ReceiveResult.Ack
        }
        // Call (Calls.kt): picked in the watch's reply menu, for the chat on its screen. Its
        // thread token must be the open chat's, so a stale request calls no one.
        val reqCall = (data[Schema.KEY_REQ_CALL] as? PebbleDictionaryItem.Int32)?.value
        if (reqCall != null) {
            val convoId = Mirror.currentOpenConvoId
            if (convoId == null || threadToken(convoId) != reqCall) {
                LogStore.w(TAG, "Call for a chat that isn't the one open; ignored")
            } else {
                val svcId = Mirror.activeService()?.id
                scope.launch { Calls.start(applicationContext, convoId, svcId) }
            }
            return ReceiveResult.Ack
        }
        // A reaction (the watch's React row, or the emoji list it opens): on a message of
        // the chat on screen, named by its token, so one for a chat no longer open goes
        // nowhere. Never sent as a message.
        val reqReact = (data[Schema.KEY_REQ_REACT] as? PebbleDictionaryItem.Text)?.value
        if (reqReact != null) {
            val convoId = Mirror.currentOpenConvoId
            val tok = (data[Schema.KEY_DRAFT_TOK] as? PebbleDictionaryItem.Int32)?.value
            if (convoId == null || (tok != null && threadToken(convoId) != tok)) {
                LogStore.w(TAG, "Reaction for a chat that isn't the one open; ignored")
            } else {
                val target = targetByHash((data[Schema.KEY_REPLY_TO_INDEX] as? PebbleDictionaryItem.Int32)?.value ?: -1, reqHash)
                scope.launch { sendReaction(convoId, target, reqReact) }
            }
            return ReceiveResult.Ack
        }
        // Emoji actions (step 100): a feed's action (a like, a vote) on a message of the
        // chat on screen, picked at the top of the watch's reply menu.
        val reqAction = (data[Schema.KEY_REQ_ACTION] as? PebbleDictionaryItem.Int32)?.value
        if (reqAction != null) {
            val convoId = Mirror.currentOpenConvoId
            val at = if (convoId == null) (reqAction and 0xFFFF) else rowByHash(convoId, reqAction and 0xFFFF, reqHash, "Action")
            if (at >= 0) scope.launch { sendAction(reqAction ushr 16, at) }
            return ReceiveResult.Ack
        }

        // Message span: the watch requests messages [v, v+n) of the OPEN thread as its
        // window slides (KEY_REQ_MSG_SPAN=v + KEY_REQ_SPAN_COUNT=n). Distinct key from
        // the convo-list span (KEY_REQ_SPAN=80) so routing never depends on open-thread
        // state (which can go stale). Serve the counted span from the flat thread.
        val reqMsgSpan = (data[Schema.KEY_REQ_MSG_SPAN] as? PebbleDictionaryItem.Int32)?.value
        if (reqMsgSpan != null && reqMsgSpan >= 0) {
            val spanCount = (data[Schema.KEY_REQ_SPAN_COUNT] as? PebbleDictionaryItem.Int32)?.value ?: 0
            if (spanCount > 0) Mirror.msgSpanCount = spanCount   // cache the msg window size
            val convoId = Mirror.currentOpenConvoId
            if (convoId != null) {
                LogStore.i(TAG, "Watch requested msg span @$reqMsgSpan count=$spanCount (convo $convoId)")
                scope.launch {
                    // 3b: if this span reaches near the loaded floor, backfill older
                    // history from Google FIRST (ack + fetch + merge), so streamMessages
                    // then serves [v, v+n) with the grown KEY_LIST_TOTAL. Grows total on
                    // this SPAN response only — never a fresh push (Watch Claude's
                    // backfill-vs-new-message contract).
                    maybeBackfill(convoId, reqMsgSpan, if (spanCount > 0) spanCount else Mirror.msgSpanCount)
                    streamMessages(convoId,
                        MessageStore.loadAsThreadJson(this@PebbleListenerService, convoId),
                        spanStart = reqMsgSpan, spanCount = spanCount)
                }
            } else LogStore.w(TAG, "Msg span requested but no open thread")
            return ReceiveResult.Ack
        }

        // On-demand FULL TEXT: watch opened a truncated message in enhanced view
        // and wants the complete text (which didn't fit the thread blob).
        val reqFullText = (data[Schema.KEY_REQ_FULL_TEXT] as? PebbleDictionaryItem.Int32)?.value
        if (reqFullText != null) {
            val convoId = Mirror.currentOpenConvoId
            if (convoId != null) {
                val at = rowByHash(convoId, reqFullText, reqHash, "Full text")
                if (at >= 0) scope.launch { sendFullText(convoId, reqFullText, at) }
            }
            return ReceiveResult.Ack
        }

        // Sharp patch for a zoomed picture (Time 2): the part on the watch's screen,
        // at the zoomed resolution. A newer request replaces one still running.
        val izReq = (data[Schema.KEY_IZ_REQ] as? PebbleDictionaryItem.Bytes)?.value
        if (izReq != null) {
            val convoId = Mirror.currentOpenConvoId
            patchJob?.cancel()
            patchJob = scope.launch { streamPatch(convoId, izReq) }
            return ReceiveResult.Ack
        }

        // The watch's sound row (step 91): off, or back on with the sound chosen last; from
        // step 111 on at a level (0x100 | 1..3: Low, Medium, High). The answer tells the
        // watch the setting without playing it (step 111b, Dan: a chime on every press was
        // too much).
        val soundReq = (data[Schema.KEY_SOUND] as? PebbleDictionaryItem.Int32)?.value
        if (soundReq != null) {
            val lvl = if (soundReq >= 0x100) (soundReq and 0xFF) else 0
            if (lvl in 1..Mirror.TONE_VOL_NAMES.size) Mirror.setToneVol(this, lvl - 1)
            Mirror.setSoundOn(this, soundReq != 0)
            LogStore.i(TAG, "Notification sound turned " + (if (soundReq != 0)
                "on (${Mirror.TONE_VOL_NAMES[Mirror.toneVol]})" else "off") + " on the watch")
            sendSound(play = false)
            return ReceiveResult.Ack
        }
        // The watch's answer to a sound it was asked to play: 0 = it's muted or in Quiet
        // Time, so it didn't (said here, or picking one would seem to do nothing).
        val toneAns = (data[Schema.KEY_TONE_TEST] as? PebbleDictionaryItem.Int32)?.value
        if (toneAns != null) {
            if (toneAns == 0) {
                LogStore.i(TAG, "Notification sound: the watch is muted or in Quiet Time, so it didn't play")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(applicationContext,
                        "Your watch is muted or in Quiet Time, so the sound didn't play",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
            return ReceiveResult.Ack
        }

        // Contact photos (step 90): the photos missing from the list rows on the
        // watch's screen.
        val avReq = (data[Schema.KEY_AV_REQ] as? PebbleDictionaryItem.Bytes)?.value
        if (avReq != null) {
            scope.launch { answerAvatars(avReq) }
            return ReceiveResult.Ack
        }

        // On-demand image request: watch parked on / entered image at msg index.
        val reqImg = (data[Schema.KEY_REQ_IMAGE] as? PebbleDictionaryItem.Int32)?.value
        if (reqImg != null) {
            val convoId = Mirror.currentOpenConvoId
            val full = ((data[Schema.KEY_REQ_IMG_FULL] as? PebbleDictionaryItem.Int32)?.value ?: 0) == 1
            val budget = (data[Schema.KEY_REQ_IMG_BUDGET] as? PebbleDictionaryItem.Int32)?.value ?: 0
            val budgetMax = (data[Schema.KEY_REQ_IMG_BUDGET_MAX] as? PebbleDictionaryItem.Int32)?.value ?: budget
            // Watch-reported display width (144 Basalt / 200 Emery). Enhanced images
            // fit to this so they fill the actual screen. Absent/0 => IMG_MAX_W (144),
            // so pre-Emery watchapps behave exactly as before.
            // Width is cached at the top of onMessageReceived; use the cache (this
            // request carried it too, but the cache is the single source of truth).
            val dispW = Mirror.watchDisplayW
            // All images are 16-color (4bpp) now; the bridge maps quality=1 to a
            // 4bpp palettized encode. (Full-color/8bpp support was removed.)
            val quality = 1
            val priority = ((data[Schema.KEY_REQ_IMG_PRIORITY] as? PebbleDictionaryItem.Int32)?.value ?: 0) == 1
            // An album's item (step 88): the one the detail view is on. Absent: the
            // row's own picture (an album's collage).
            val item = (data[Schema.KEY_IMG_ITEM] as? PebbleDictionaryItem.Int32)?.value ?: -1
            if (convoId != null) {
                // PRIORITY (user opened enhanced view, or the watch dropped a
                // transfer): cancel every other image job so this one isn't stuck
                // behind it. A full picture also stops the preview warm-ups
                // queued behind it, so their downloads don't compete with it.
                if (priority) { cancelAllImageJobs(); if (full) { cancelThumbWarm(); cancelFullPrep(); patchJob?.cancel() } }
                // ALWAYS honor the request with a stream. The watch serializes
                // transfers and tracks in-flight state expecting an IMG_END for
                // every request it sends; dropping one here would leave the watch
                // stuck waiting forever. The imgMutex still prevents two streams
                // from overlapping on the wire.
                LogStore.i(TAG, "Watch requested image msg $reqImg${if (item >= 0) " item $item" else ""} (convo $convoId, full=$full, budget=$budget, max=$budgetMax, dispW=$dispW, q=$quality, prio=$priority)")
                // Step 110: the message the watch means, by its id; this row may hold
                // another one here when the watch's rows are behind.
                val at = rowByHash(convoId, reqImg, reqHash, "Picture")
                if (at < 0) {
                    scope.launch {
                        imgMutex.withLock { sendImgNone(convoId, reqImg, full, item) }
                        resyncAfterMiss(convoId, full, "message not in this chat here")
                    }
                    return ReceiveResult.Ack
                }
                if (rowServed.size > 400) rowServed.clear()
                rowServed["$convoId#$reqImg"] = at
                val job = scope.launch { streamImageByIndex(convoId, reqImg, full, budget, budgetMax, quality, dispW, item = item, row = at) }
                imgJobs.add(job)
                job.invokeOnCompletion { imgJobs.remove(job) }
                // The watch walks the rows around this one next: encode those ahead.
                // And a row on screen may be opened next: encode its full picture too.
                if (!full) {
                    scope.launch { warmThumbs(convoId, at) }
                    prepFull(convoId, at)
                } else if (item >= 0) {
                    // Paging through an album: its next item is likely next.
                    prepFull(convoId, at, item + 1)
                }
            } else {
                // No open chat to resolve it against: answer with an empty picture
                // so the watch isn't left waiting on its watchdog.
                LogStore.w(TAG, "Image msg $reqImg requested with no open chat")
                scope.launch { imgMutex.withLock { sendImgNone(null, reqImg, full, item) } }
            }
            return ReceiveResult.Ack
        }

        // Thread-closed: the watch's message-list window left its window stack
        // (back to the convo list, account switch, or app exit). Clear the phone's
        // idea of the open convo: (a) the debounced live repaint stops streaming
        // thread spans nobody is viewing, and (b) the same-chat IAN exception
        // (a Pebble notification must never pop over the chat being viewed) can't
        // misfire for a chat the user already backed out of. Fire-and-forget and
        // idempotent; a dropped signal self-heals on the next open or app close.
        if (data[Schema.KEY_THREAD_CLOSED] != null) {
            LogStore.i(TAG, "Watch left thread view — clearing open convo (was ${Mirror.currentOpenConvoId})")
            // Forget where the ladder ended up for this thread's animations. A rung
            // is bumped because the watch could not hold something AT THAT MOMENT —
            // often fragmentation rather than size — so carrying it forever would
            // permanently degrade a clip that would fit fine on a fresh heap. The
            // position lasts as long as the viewing session and no longer.
            Mirror.clearGifRungs()
            Mirror.currentOpenConvoId = null
            return ReceiveResult.Ack
        }

        // Mark-thread-read: the watch fires this whenever a chat thread becomes the
        // visible top of screen — manual nav, a force-open, OR backing out of a PN's
        // detail view into the thread beneath (a case KEY_REQ_OPEN does NOT cover).
        // Contract: receive it => mark that convo read. Fire-and-forget + duplicated,
        // so the op is idempotent and guarded against redundant network calls.
        val markRow = (data[Schema.KEY_MARK_THREAD_READ] as? PebbleDictionaryItem.Int32)?.value
        if (markRow != null) {
            // ENTER rows (buckets/servers) aren't threads — never mark those.
            if (Mirror.navKinds.getOrNull(markRow) == 1) return ReceiveResult.Ack
            val convoId = Mirror.convoOrder.getOrNull(markRow)
            if (convoId != null) {
                // The watch shows the ACTIVE account's list, so the convo at this
                // index belongs to the active service (same basis openThread uses).
                val svc = Mirror.activeService()
                if (svc != null && svc.supportsMarkRead) {
                    scope.launch {
                        try {
                            val newestId = MessageStore.newestMessageId(
                                this@PebbleListenerService, convoId)
                            if (newestId.isEmpty()) return@launch
                            val token = "$convoId:$newestId"
                            if (token == lastMarkedRead) return@launch  // idempotent skip
                            val res = svc.markRead(convoId, newestId)
                            lastMarkedRead = token
                            LogStore.i(TAG, "Thread-read: markRead($convoId) -> $res")
                        } catch (e: Exception) {
                            LogStore.e(TAG, "KEY_MARK_THREAD_READ handling failed", e)
                        }
                    }
                }
            }
            return ReceiveResult.Ack
        }

        val row = (data[Schema.KEY_REQ_OPEN] as? PebbleDictionaryItem.Int32)?.value
            ?: return ReceiveResult.Nack
        // Thread-open carries the watch's message-window size (KEY_REQ_SPAN_COUNT);
        // cache it so openThread's newest-span push lands at the bottom with the right
        // base (total-n). Without this a Basalt open could land mid-thread.
        (data[Schema.KEY_REQ_SPAN_COUNT] as? PebbleDictionaryItem.Int32)?.value?.let {
            if (it > 0) Mirror.msgSpanCount = it
        }
        LogStore.i(TAG, "Watch requested thread for row $row")
        scope.launch { openThread(row) }
        return ReceiveResult.Ack
    }

    // ---- Send reply --------------------------------------------------------

    /** Best-effort refresh after a reply was sent OUTSIDE the service (e.g. the
     *  notification quick-reply, which sends via Mirror.bridge directly). Re-pulls
     *  the thread so the sent message lands in the store and reaches the watch.
     *  Safe to call from any context; no-op failures are logged, not thrown. */
    fun refreshAfterReply(convoId: String) {
        scope.launch {
            try {
                val svc = Mirror.activeService() ?: return@launch
                delay(800)
                val json = svc.fetchMessages(convoId, FETCH_COUNT, "")
                val msgs = JSONObject(json).optJSONArray("messages") ?: JSONArray()
                MessageStore.merge(this@PebbleListenerService, convoId, msgs)
                Mirror.invalidateConvoList()
                refreshConversationList()
                if (Mirror.currentOpenConvoId == convoId) {
                    streamMessages(convoId, MessageStore.loadAsThreadJson(this@PebbleListenerService, convoId))
                }
            } catch (e: Exception) {
                LogStore.e(TAG, "refreshAfterReply failed", e)
            }
        }
    }

    // Re-stream the currently-open thread IN PLACE (same call shape as the post-reply
    // refresh above, so it never changes the watch's current view), so state that
    // lands out-of-band — a reaction ingested into ReactionStore, with no new message
    // to ride on — surfaces without the user re-opening the thread. No-op unless
    // convoId is the thread the watch is showing.
    // Out-of-band reaction state changed for the open thread (Discord/Telegram/
    // WhatsApp ingested a reaction with no new message). Patch the reacted badges in
    // place — never re-stream a span, which would blank the reader's view when
    // scrolled up. All-reacted patch set; the watch applies its resident rows.
    /**
     * The notification sound changed (the setting, or the watch's sound row): tell the
     * watch, whose sound row shows it, and with [play] have it play the sound now (it
     * answers whether it could). Only reaches the watch while MirrorMsg is open there;
     * otherwise it hears the setting with the account list next time.
     */
    fun sendSound(play: Boolean) {
        val tone = Mirror.pushTone
        scope.launch {
            try {
                val frame = HashMap<UInt, PebbleDictionaryItem>()
                frame[Schema.KEY_SOUND] = PebbleDictionaryItem.Int32(tone)
                frame[Schema.KEY_TONE_VOL] = PebbleDictionaryItem.Int32(Mirror.toneVol + 1)
                if (play && tone > 0) frame[Schema.KEY_TONE_TEST] = PebbleDictionaryItem.Int32(tone)
                Mirror.sender(this@PebbleListenerService).sendDataToPebble(Schema.WATCH_UUID,
                    frame, watches = null)
                LogStore.i(TAG, "Notification sound: ${Mirror.TONE_NAMES.getOrElse(tone) { "?" }}, " +
                    Mirror.TONE_VOL_NAMES[Mirror.toneVol] +
                    (if (play && tone > 0) ", sent to the watch to try" else ", sent to the watch"))
            } catch (e: Exception) {
                LogStore.w(TAG, "Notification sound not sent: ${e.message}")
            }
        }
    }

    fun refreshOpenThread(convoId: String) {
        scope.launch {
            try {
                if (Mirror.currentOpenConvoId != convoId) return@launch
                pushReactionPatches(convoId, emptyList())
            } catch (e: Exception) {
                LogStore.e(TAG, "refreshOpenThread failed", e)
            }
        }
    }

    private suspend fun sendReply(row: Int, text: String, targetIndex: Int = -1) {
        try {
            val convoId = Mirror.currentOpenConvoId
                ?: Mirror.convoOrder.getOrNull(row)
                ?: run { LogStore.w(TAG, "No open convo for reply"); return }
            val route = Mirror.currentOpenRoute
                ?: Mirror.simRoutes.getOrNull(row)
                ?: Mirror.SimRoute("2", 2L, 2L)
            // Detail-view reply: targeted at a specific message (reply-to / react),
            // gated on the owning service's opt-in capability. If sendTargeted
            // handles it, we're done; otherwise (chat-view reply, unsupported
            // service, or a failed targeted send) fall through to a normal message —
            // so non-opted services keep working exactly as before. Not in a feed: a
            // post takes a comment (or a like) and a feed takes no plain messages, so a
            // failed one stays failed, its own error in the log rather than the refusal.
            // In a reply room (step 99) one from the chat answers the post at its top. In
            // a feed an emoji is a reply too (step 100: its like is an action of its own).
            // A watch that asks for reactions outright (IMGCAP_REACT) sends emoji picked
            // to write as text: only an older one's emoji reply is a reaction.
            val target = if (targetIndex < 0) roomPostRow(convoId) else targetIndex
            val reactOk = (Mirror.watchCaps and Schema.IMGCAP_REACT) == 0
            if (target >= 0) {
                if (sendTargeted(convoId, target, text,
                        allowReact = reactOk && targetIndex >= 0 && !Mirror.isFeed(convoId))) return
                if (Mirror.isFeed(convoId)) {
                    LogStore.w(TAG, "Feed reply not sent (a feed takes no plain messages)")
                    WatchNotice.post(this, "Reply not sent", "This post didn't take the reply.")
                    return
                }
            }
            if (!sendReplyInternal(convoId, route, text))
                WatchNotice.post(this, "Message not sent", "The network didn't take it. Try again from the watch.")
        } catch (e: Exception) {
            LogStore.e(TAG, "sendReply failed", e)
        }
    }

    /**
     * A reaction asked for outright (KEY_REQ_REACT): [emoji] on message [target] of the
     * open chat, as a reaction only (the same one again takes it back, where the network
     * can). One the network can't take says so on the watch, as a notification.
     */
    private suspend fun sendReaction(convoId: String, target: Int, emoji: String) {
        try {
            if (target < 0) {
                LogStore.w(TAG, "Reaction with no message to go to; not sent")
                return
            }
            val svc = Mirror.activeService()
            if (svc == null || !svc.supportsReactionSend || !isEmojiOnly(emoji)) {
                LogStore.w(TAG, "Reaction not sent (this chat's network takes none)")
                WatchNotice.post(this, "Reaction not sent", "This chat doesn't take reactions.")
                return
            }
            if (!sendTargeted(convoId, target, emoji, allowReact = true)) {
                WatchNotice.post(this, "Reaction not sent", "The network didn't take it.")
            }
        } catch (e: Exception) {
            LogStore.e(TAG, "sendReaction failed", e)
        }
    }

    // Detail-view targeted send. Resolves the target message by its index in the
    // open thread (same index machinery as image/full-text requests), then routes:
    //   emoji-only + service supports reaction -> send a reaction
    //   text       + service supports reply-to -> send a quoted reply
    //   otherwise                              -> false (caller sends a plain message)
    // Returns true only when a targeted send actually succeeded; false means "fall
    // back to a normal message," which covers non-opted services, threads the bridge
    // can't target (e.g. FB cookie), and any send error. In a feed, false means not
    // sent: the callers send no plain message there (a feed refuses them).
    private suspend fun sendTargeted(convoId: String, targetIndex: Int, text: String,
                                     allowReact: Boolean = true): Boolean {
        val svc = Mirror.activeService() ?: return false
        val net = Rooms.netConvo(convoId)   // (a reply room's network chat, step 99)
        val instead = if (Mirror.isFeed(convoId)) "not sent" else "plain send"
        val trimmed = buildTrimmedCached(convoId)
        if (targetIndex >= trimmed.size) {
            LogStore.w(TAG, "Reply target index $targetIndex out of range (${trimmed.size}); $instead")
            return false
        }
        val mm = trimmed[targetIndex]
        // A feed post's words split off its picture carry the post's id (step 99).
        val mid = mm.messageId.ifEmpty { mm.postId }
        if (mid.isEmpty()) {
            LogStore.w(TAG, "Reply target $targetIndex has no message id; $instead")
            return false
        }
        val targetFromMe = mm.incoming == 0   // incoming: 1 = from them, 0 = from me
        // A draft (allowReact = false) is never a reaction, even when it's only emoji:
        // it goes as a reply, or as a plain message where the network takes no replies.
        val emojiOnly = isEmojiOnly(text) && allowReact
        // The user's reaction sent from the watch, the same sent again: taken back where the
        // network can (step 101, Dan: "a second one unsends").
        val keys = ownKeys()
        val prev = if (emojiOnly && svc.supportsReactionSend) ReactionStore.sentMine(mid) else null
        val remover = svc as? ReactionRemover
        val undo = prev != null && remover != null && emojiKey(prev) == emojiKey(text)
        val result: String = when {
            undo -> remover!!.removeReaction(net, mid, targetFromMe, prev!!)
            emojiOnly && svc.supportsReactionSend ->
                svc.sendReaction(net, mid, targetFromMe, text)
            !emojiOnly && svc.supportsReplyTo ->
                svc.sendReplyTo(net, mid, targetFromMe, text)
            else -> return false // service hasn't opted in -> normal message
        }
        if (!sendSucceeded(result)) {
            // (A removal that failed is never sent on as a message: the reaction stays.)
            if (undo) { LogStore.w(TAG, "Reaction removal failed: $result"); return true }
            LogStore.w(TAG, "Targeted send failed ($instead): $result")
            return false
        }
        LogStore.i(TAG, "Sent ${if (undo) "reaction removal" else if (emojiOnly) "reaction" else "reply-to"} to msg ${mid.takeLast(8)} in $convoId")
        if (undo) {
            ReactionStore.setSentMine(mid, null)
            keys.forEach { ReactionStore.remove(mid, it) }
            if (Mirror.currentOpenConvoId == convoId) pushReactionPatches(convoId, listOf(mid))
        } else if (emojiOnly) {
            // A watch-sent reaction never echoes back through the bridge (FB only
            // loops back reactions left in its own app), so apply it optimistically
            // right now. ReactionStore persists it, so it also survives a restart.
            // Keyed under the stored message's own id → matches at pack time with no
            // alias needed. "self" collapses repeat self-reactions to one slot.
            // Key the optimistic reaction under this network's own actor id when it
            // knows it (e.g. the FB fbid), so a later change/removal from the native
            // app — which carries that same actor — reconciles it. Falls back to a
            // synthetic key for networks that don't expose a self id.
            ReactionStore.ingest(mid, keys[0], text.trim())
            ReactionStore.setSentMine(mid, text.trim())   // (the badge shows it as the user's)
            if (Mirror.currentOpenConvoId == convoId)
                pushReactionPatches(convoId, listOf(mid))   // patch this one badge, no span
        } else if (Rooms.isRoom(convoId)) {
            // In a reply room (step 99): a reply to its post shows first under it, landed
            // on; one to a reply there belongs in that reply's own room.
            val room = Rooms.get(convoId)
            if (room != null && mid == room.postId && Mirror.currentOpenConvoId == convoId) {
                val sent = sentJson(net, result, text)
                room.sent(sent)
                val at = buildTrimmedCached(convoId).indexOfFirst { it.messageId == sent.optString("messageID") }
                streamMessages(convoId, "{}", landAbs = at.coerceAtLeast(0))
            }
        } else {
            // A reply optimistically inserts itself in the service; re-push the open
            // thread after a beat so the watch reflects it.
            if (Mirror.currentOpenConvoId == convoId) {
                delay(700)
                streamMessages(convoId, MessageStore.loadAsThreadJson(this, convoId))
            }
        }
        return true
    }

    // ---- Emoji actions (step 100) ------------------------------------------------

    /**
     * A feed's emoji actions (FeedActions codes): its network's own, or Like where it
     * takes reactions; none outside a feed (or a room of one).
     */
    private fun feedActions(convoId: String): List<Int> {
        if (!Mirror.isFeed(convoId)) return emptyList()
        val svc = Mirror.activeService() ?: return emptyList()
        val acts = try {
            if (svc is FeedActions) svc.feedActions(Rooms.netConvo(convoId))
            else if (svc.supportsReactionSend) listOf(FeedActions.LIKE) else emptyList()
        } catch (e: Exception) {
            LogStore.e(TAG, "feedActions failed", e)
            emptyList()
        }
        // (A like is a reaction: only where the network sends them.)
        return acts.filter { it in FeedActions.LIKE..FeedActions.DOWNVOTE && (it != FeedActions.LIKE || svc.supportsReactionSend) }
            .distinct().take(3)
    }

    /**
     * The keys a reaction sent from the watch is kept under in ReactionStore: the network's
     * id for the user where it knows it (the first, what it's ingested under), and "self".
     */
    private fun ownKeys(): List<String> =
        listOfNotNull(Mirror.activeService()?.selfActorKey(), "self").distinct()

    /** An emoji as compared for "the same one again" (step 101): no variation selector, no spaces. */
    private fun emojiKey(e: String): String = e.replace("\uFE0F", "").trim()

    /** As KEY_THREAD_ACTIONS has them: 4 bits each from the low end, how many in bits 12-15. */
    private fun actionsField(acts: List<Int>): Int {
        var v = acts.size shl 12
        acts.forEachIndexed { k, a -> v = v or (a shl (4 * k)) }
        return v
    }

    private fun actionName(a: Int): String = when (a) {
        FeedActions.LIKE -> "like"
        FeedActions.UPVOTE -> "upvote"
        FeedActions.DOWNVOTE -> "downvote"
        else -> "action $a"
    }

    /**
     * Action [action] (FeedActions) on message [abs] of the chat on screen, picked at the
     * top of the watch's reply menu. A like is the reaction ❤ (step 101), or, already
     * the user's (the network's "liked" says, else a like sent from the watch), taken back
     * where the network can (ReactionRemover): shown at once on the message's badge, its
     * count with it. A vote is the network's own (FeedActions), its undo too: it knows the
     * user's vote.
     */
    private suspend fun sendAction(action: Int, abs: Int) {
        try {
            val convoId = Mirror.currentOpenConvoId
                ?: run { LogStore.w(TAG, "Action ${actionName(action)} with no chat open"); return }
            val svc = Mirror.activeService() ?: run { LogStore.w(TAG, "Action: no service"); return }
            if (action !in feedActions(convoId)) {
                LogStore.w(TAG, "Action ${actionName(action)} isn't one this chat has; ignored")
                return
            }
            val trimmed = buildTrimmedCached(convoId)
            val mm = trimmed.getOrNull(abs)
                ?: run { LogStore.w(TAG, "Action target $abs out of range (${trimmed.size})"); return }
            // A feed post's words split off its picture carry the post's id (step 99), or
            // their picture does.
            var mid = mm.messageId.ifEmpty { mm.postId }
            if (mid.isEmpty() && (mm.link == -2 || mm.link == -3))
                mid = trimmed.getOrNull(withSplitLinks(trimmed, abs, abs + 1)[0].link)?.messageId.orEmpty()
            if (mid.isEmpty()) {
                LogStore.w(TAG, "Action target $abs has no message id")
                return
            }
            val net = Rooms.netConvo(convoId)
            val fromMe = mm.incoming == 0
            if (action != FeedActions.LIKE) {
                val result = (svc as? FeedActions)?.sendFeedAction(net, mid, fromMe, action)
                    ?: run { LogStore.w(TAG, "Action ${actionName(action)}: the network takes none"); return }
                if (!sendSucceeded(result)) {
                    LogStore.w(TAG, "Action ${actionName(action)} on ..${mid.takeLast(8)} failed: $result")
                    return
                }
                LogStore.i(TAG, "Action ${actionName(action)} sent to row $abs (..${mid.takeLast(8)})")
                return
            }
            val prev = ReactionStore.sentMine(mid)
            val liked = if (mm.liked >= 0) mm.liked == 1 else prev != null
            val what = if (liked) "unlike" else "like"
            val remover = svc as? ReactionRemover
            if (liked && remover == null) {
                LogStore.w(TAG, "Action unlike on ..${mid.takeLast(8)}: the network takes no like back from the watch")
                return
            }
            val result = if (liked) remover!!.removeReaction(net, mid, fromMe, prev ?: LIKE_GLYPH)
                         else svc.sendReaction(net, mid, fromMe, LIKE_GLYPH)
            if (!sendSucceeded(result)) {
                LogStore.w(TAG, "Action $what on ..${mid.takeLast(8)} failed: $result")
                return
            }
            LogStore.i(TAG, "Action $what sent to row $abs (..${mid.takeLast(8)})")
            // Kept (or dropped) at once, no echo comes; the count as held moves with it
            // until the network's next.
            ReactionStore.setSentMine(mid, if (liked) null else LIKE_GLYPH)
            setLiked(net, mid, !liked)
            if (Mirror.currentOpenConvoId == convoId) pushReactionPatches(convoId, listOf(mid))
        } catch (e: Exception) {
            LogStore.e(TAG, "sendAction failed", e)
        }
    }

    /**
     * Message [mid] of feed [feed] just liked by the user, or unliked (step 101): its count
     * one more or one less ("liked" with it where the network sends one), in its rooms'
     * copies and the feed's, until the network's next.
     */
    private fun setLiked(feed: String, mid: String, liked: Boolean) {
        Rooms.setLiked(mid, liked)
        val arr = MessageStore.load(this, feed)
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            if (m.optString("messageID") != mid) continue
            val copy = JSONObject(m.toString())
            if (Rooms.applyLiked(copy, liked)) MessageStore.mergeOne(this, feed, copy)
            return
        }
    }

    // A bridge send response is JSON; success carries a non-empty messageId, an
    // error carries an "error" field (errJson/errStr). Used to decide fall-back.
    private fun sendSucceeded(result: String): Boolean = try {
        JSONObject(result).optString("messageId").isNotEmpty()
    } catch (_: Exception) {
        false
    }

    // True when [text] is purely emoji (one or more emoji and nothing else) — the
    // detail-view signal to react instead of reply. Joiners, variation selectors,
    // and skin-tone modifiers are allowed; any ordinary character disqualifies it.
    private fun isEmojiOnly(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        var i = 0
        var sawEmoji = false
        while (i < t.length) {
            val cp = t.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == 0x200D || cp == 0xFE0F || cp == 0xFE0E -> {}           // ZWJ / variation selectors
                cp in 0x1F3FB..0x1F3FF -> {}                                  // skin-tone modifiers
                isEmojiCodePoint(cp) -> sawEmoji = true
                else -> return false
            }
        }
        return sawEmoji
    }

    private fun isEmojiCodePoint(cp: Int): Boolean =
        cp in 0x1F300..0x1FAFF ||  // pictographs, supplemental, symbols & pictographs extended-A
        cp in 0x1F000..0x1F0FF ||  // mahjong/dominoes/cards
        cp in 0x2600..0x27BF ||    // misc symbols + dingbats (incl. ❤ U+2764, ☺, ✌, ✨)
        cp in 0x2B00..0x2BFF ||    // stars and misc symbols/arrows
        cp in 0x2300..0x23FF ||    // misc technical (⌚ ⏰ ⏳ …)
        cp in 0x1F1E6..0x1F1FF ||  // regional indicators (flags)
        cp == 0x2640 || cp == 0x2642 || cp == 0x2695 // gender/medical signs used in ZWJ sequences

    // False when the send failed outright (the network said so, or it threw).
    private suspend fun sendReplyInternal(convoId: String, route: Mirror.SimRoute, text: String): Boolean {
        try {
            val svc = Mirror.activeService() ?: run { LogStore.w(TAG, "No service for reply"); return false }
            LogStore.i(TAG, "Sending reply to convo $convoId: ${text.length} chars")
            // Encode the SIM route as the opaque reply token the service expects.
            // (Google decodes it back into SIM routing; other services ignore it.)
            val replyToken = JSONObject().apply {
                put("p", route.participantID); put("t", route.simTwo); put("n", route.simNumber)
            }.toString()
            val result = svc.sendMessage(convoId, text, replyToken)
            LogStore.i(TAG, "Reply sent. Response: $result")
            val refused = try { JSONObject(result).has("error") } catch (_: Exception) { false }
            if (refused) return false

            // Re-fetch after a short delay so the sent message lands in the store
            // and gets pushed to the watch.
            delay(800)
            try {
                val json = svc.fetchMessages(convoId, FETCH_COUNT, "")
                val msgs = JSONObject(json).optJSONArray("messages") ?: JSONArray()
                MessageStore.merge(this, convoId, msgs)
                if (Mirror.currentOpenConvoId == convoId) {
                    streamMessages(convoId, MessageStore.loadAsThreadJson(this, convoId))
                }
            } catch (e: Exception) {
                LogStore.e(TAG, "post-send refresh failed", e)
            }
            return true
        } catch (e: Exception) {
            LogStore.e(TAG, "sendReply failed", e)
            return false
        }
    }

    // ---- The compose bar's drafts ------------------------------------------------

    /**
     * Send a chat's draft: a reply to the open message when it came from there
     * ([targetIndex] >= 0) and the network takes replies, else a plain message.
     * Never a reaction, even when the draft is only emoji: a held emoji in the reply
     * menu is how to react. When the network refuses it, the draft comes back. In a
     * feed, a post's draft is a comment and nothing else: a failed one isn't sent
     * again as a plain message (a feed refuses those), and its draft comes back.
     */
    private suspend fun sendDraft(convoId: String, route: Mirror.SimRoute, text: String, targetIndex: Int) {
        try {
            // In a reply room (step 99) one from the chat answers the post at its top.
            val target = if (targetIndex < 0) roomPostRow(convoId) else targetIndex
            val sent = if (target >= 0 && Mirror.isFeed(convoId)) {
                sendTargeted(convoId, target, text, allowReact = false)
            } else {
                (target >= 0 && sendTargeted(convoId, target, text, allowReact = false)) ||
                    sendReplyInternal(convoId, route, text)
            }
            if (sent) {
                scope.launch(KbDecoder.dispatcher) { KbDecoder.learn(text) }   // its words come up first
                return
            }
        } catch (e: Exception) {
            LogStore.e(TAG, "sendDraft failed", e)
        }
        LogStore.w(TAG, "Draft not sent; kept (${text.length} chars)")
        WatchNotice.post(this, "Message not sent", "Your draft is kept.")
        DraftStore.restore(this, convoId, text)
        pushDraft(convoId)
    }

    // The chats the watch's draft ops may name (by thread token), and their SIM routes.
    private val draftChats = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val draftRoutes = java.util.concurrent.ConcurrentHashMap<String, Mirror.SimRoute>()
    private val draftAsm = HashMap<String, java.io.ByteArrayOutputStream>()

    private fun draftChat(tok: Int?): String? {
        val open = Mirror.currentOpenConvoId
        if (tok == null || (open != null && threadToken(open) == tok)) return open
        return draftChats[tok]
    }
    private fun routeFor(convoId: String): Mirror.SimRoute =
        (if (convoId == Mirror.currentOpenConvoId) Mirror.currentOpenRoute else null)
            ?: draftRoutes[convoId] ?: Mirror.SimRoute("2", 2L, 2L)

    /**
     * The keyboard's edited draft, a piece at a time (the watch's outbox is small). On
     * the last piece it replaces the draft from where the watch's copy started (or,
     * when the watch never got the draft, is added to it), and is sent when asked.
     * True when the draft changed (the last piece).
     */
    private fun draftPiece(convoId: String, data: PebbleDictionary): Boolean {
        val ofs = (data[Schema.KEY_DRAFT_OFS] as? PebbleDictionaryItem.Int32)?.value ?: 0
        val piece = (data[Schema.KEY_DRAFT_CHUNK] as? PebbleDictionaryItem.Bytes)?.value ?: ByteArray(0)
        val buf = if (ofs == 0) java.io.ByteArrayOutputStream().also { draftAsm[convoId] = it }
                  else draftAsm[convoId] ?: return false
        if (ofs > buf.size()) {
            LogStore.w(TAG, "Draft piece at $ofs after ${buf.size()} bytes; dropped")
            draftAsm.remove(convoId)
            return false
        }
        if (ofs < buf.size()) {                       // a piece sent again: from there on
            val keep = buf.toByteArray().copyOf(ofs)
            buf.reset()
            buf.write(keep)
        }
        buf.write(piece)
        val mode = (data[Schema.KEY_DRAFT_END] as? PebbleDictionaryItem.Int32)?.value ?: return false
        draftAsm.remove(convoId)
        val text = String(buf.toByteArray(), Charsets.UTF_8)
        val base = (data[Schema.KEY_DRAFT_BASE] as? PebbleDictionaryItem.Int32)?.value ?: 0
        val target = targetByHash((data[Schema.KEY_REPLY_TO_INDEX] as? PebbleDictionaryItem.Int32)?.value ?: -1,
            (data[KEY_REQ_HASH] as? PebbleDictionaryItem.Int32)?.value ?: 0)
        if (mode == Schema.DSET_ADD || mode == Schema.DSET_ADD_SEND) {
            DraftStore.append(this, convoId, text)
        } else {
            val old = DraftStore.get(this, convoId)
            DraftStore.set(this, convoId, old.substring(0, base.coerceIn(0, old.length)) + text)
        }
        LogStore.i(TAG, "Draft from the keyboard: ${text.length} chars (mode $mode)")
        if (mode == Schema.DSET_SEND || mode == Schema.DSET_ADD_SEND) {
            val full = DraftStore.take(this, convoId)
            if (full.isNotBlank()) {
                LogStore.i(TAG, "Draft send: ${full.length} chars (target=$target)")
                val route = routeFor(convoId)
                scope.launch { sendDraft(convoId, route, full, target) }
            }
        }
        return true
    }

    /**
     * The whole draft, for the keyboard to edit (raw: no emoji swapped). A long one
     * goes as its end (KB_FULL_MAX bytes), with where that starts (KEY_DRAFT_BASE) so
     * the edited copy replaces just that part.
     */
    private suspend fun sendFullDraft(convoId: String) {
        if (!watchSendAllowed()) return
        try {
            val d = DraftStore.get(this, convoId)
            var base = d.length
            var bytes = 0
            while (base > 0) {
                val cp = d.codePointBefore(base)
                val n = if (cp < 0x80) 1 else if (cp < 0x800) 2 else if (cp < 0x10000) 3 else 4
                if (bytes + n > KB_FULL_MAX) break
                bytes += n
                base -= Character.charCount(cp)
            }
            val b = d.substring(base).toByteArray(Charsets.UTF_8)
            sendImgFrame(Mirror.sender(this), mapOf(
                Schema.KEY_DRAFT_FULL to PebbleDictionaryItem.Bytes(b + byteArrayOf(0)),
                Schema.KEY_DRAFT_OP   to PebbleDictionaryItem.Int32(threadToken(convoId)),
                Schema.KEY_DRAFT_BASE to PebbleDictionaryItem.Int32(base)
            ))
            LogStore.i(TAG, "Draft to the keyboard: ${b.size} bytes" + (if (base > 0) " (its end)" else ""))
        } catch (e: Exception) {
            LogStore.e(TAG, "sendFullDraft failed", e)
        }
    }

    /** A word's taps from the watch keyboard, read; its three words go back. */
    private suspend fun answerKb(b: ByteArray) {
        try {
            if (b.size < 3) return
            val t0 = System.currentTimeMillis()
            val seq = b[0].toInt() and 0xFF
            val n = b[2].toInt() and 0xFF
            var p = 3
            val taps = ArrayList<KbDecoder.Tap>(n)
            for (i in 0 until n) {
                if (p + 1 >= b.size) return
                val x = b[p].toInt() and 0xFF
                val y = b[p + 1].toInt() and 0xFF
                p += 2
                taps.add(if (x == 255) KbDecoder.Tap(0f, 0f, true, y.toChar())     // Latin-1 = Unicode
                         else KbDecoder.Tap(x / 16f, (y and 0x7F) / 16f, (y and 0x80) != 0, null))
            }
            val ll = if (p < b.size) b[p].toInt() and 0xFF else 0
            p++
            val lit = if (ll > 0 && p + ll <= b.size) String(b, p, ll, Charsets.UTF_8) else ""
            p += ll
            val prev = if (p < b.size) String(b, p, b.size - p, Charsets.UTF_8) else ""
            val r = KbDecoder.decode(this, taps, lit, prev)
            val out = java.io.ByteArrayOutputStream()
            out.write(seq)
            out.write((if (r.auto) Schema.KS_AUTO else 0) or (if (r.predict) Schema.KS_PREDICT else 0))
            out.write(r.words.joinToString("\u001F").toByteArray(Charsets.UTF_8))
            sendImgFrame(Mirror.sender(this), mapOf(Schema.KEY_KB_SUGG to PebbleDictionaryItem.Bytes(out.toByteArray())))
            val ms = System.currentTimeMillis() - t0
            if (ms > 300) LogStore.i(TAG, "Keyboard: $n taps read in $ms ms")
        } catch (e: Exception) {
            LogStore.e(TAG, "Keyboard answer failed", e)
        }
    }

    /**
     * Show the open chat's draft on the watch, in its compose bar ("" takes the bar
     * away). For that chat only: the frame carries its thread token, and nothing goes
     * once another chat is open.
     */
    private suspend fun pushDraft(convoId: String) {
        val tok = threadToken(convoId)
        draftChats[tok] = convoId
        if (convoId == Mirror.currentOpenConvoId) Mirror.currentOpenRoute?.let { draftRoutes[convoId] = it }
        if (!watchSendAllowed() || Mirror.currentOpenConvoId != convoId) return
        try {
            val shown = draftForWatch(DraftStore.get(this, convoId))
            sendImgFrame(Mirror.sender(this), mapOf(
                Schema.KEY_DRAFT_OP   to PebbleDictionaryItem.Int32(tok),
                Schema.KEY_DRAFT_TEXT to PebbleDictionaryItem.Text(shown)
            ))
        } catch (e: Exception) {
            LogStore.e(TAG, "pushDraft failed", e)
        }
    }

    /** The draft as the watch shows it: on one line, emoji as the watch draws them,
     *  and when it's long its end (where it's being added to) after an ellipsis. */
    private fun draftForWatch(draft: String): String {
        val t = EmojiSub.processText(draft.replace('\n', ' ').replace('\r', ' ').trim())
        val b = t.toByteArray(Charsets.UTF_8)
        if (b.size <= DRAFT_SHOW_MAX) return t
        var i = b.size - (DRAFT_SHOW_MAX - 3)                  // room for the ellipsis
        while (i < b.size && (b[i].toInt() and 0xC0) == 0x80) i++   // a character's start
        for (k in i until minOf(i + 24, b.size - 1))           // and a word's, if one is near
            if (b[k] == ' '.code.toByte()) { i = k + 1; break }
        return "\u2026" + String(b, i, b.size - i, Charsets.UTF_8)
    }

    // ---- Hierarchical navigation -------------------------------------------

    /** Drill into the current context's entry [entry] (>= 0), or pop one level (-1),
     *  then re-stream the new context as a list (page 0). [entry] is absolute (same
     *  indexing as KEY_REQ_OPEN), resolved against the current context's entries. */
    private suspend fun navigateContext(entry: Int) {
        try {
            if (entry < 0) {
                if (Mirror.navStack.isNotEmpty())
                    Mirror.navStack.removeAt(Mirror.navStack.size - 1)
                else {
                    LogStore.w(TAG, "REQ_ENTER pop at root — ignoring")
                    return
                }
            } else {
                val token = Mirror.navContexts.getOrNull(entry).orEmpty()
                if (Mirror.navKinds.getOrNull(entry) == 1 && token.isNotEmpty()) {
                    Mirror.navStack.add(token)
                } else {
                    LogStore.w(TAG, "REQ_ENTER on non-ENTER row $entry — ignoring")
                    return
                }
            }
            val svc = Mirror.activeService() ?: return
            val json = svc.listContext(CONVO_CAP, Mirror.navStack.toList())
            // Drilled context is never cached as root (see refreshConversationList).
            // A drill result is a fresh-context span from the top (frame-0 abs 0).
            streamConvos(json)
            LogStore.i(TAG, "Nav context -> [${Mirror.navStack.joinToString("/")}]")
        } catch (e: Exception) {
            LogStore.e(TAG, "navigateContext failed", e)
        }
    }

    // ---- Open thread: store-first, then catch-up ---------------------------

    private suspend fun openThread(row: Int) {
        // Defensive: if the watch opens an ENTER row (bucket/server), drill instead of
        // trying to open a non-existent thread. Normally the watch sends KEY_REQ_ENTER
        // for these, so this only catches a mismatch.
        if (Mirror.navKinds.getOrNull(row) == 1) {
            LogStore.w(TAG, "openThread on ENTER row $row — drilling instead")
            navigateContext(row)
            return
        }
        val convoId = Mirror.convoOrder.getOrNull(row)
            ?: run { LogStore.w(TAG, "No convo at row $row"); return }
        Mirror.currentOpenConvoId = convoId
        Mirror.currentOpenRoute = Mirror.simRoutes.getOrNull(row)
        // Fresh open: clear stale backfill state so exhaustion is re-derived from this
        // thread's fetches (a thread that grew since last open shouldn't stay "at top").
        Mirror.backfillExhausted.remove(convoId)
        Mirror.currentMsgPage = 0   // a fresh open always starts on the newest page

        // 1. Serve stored messages instantly if we have them. The watch no longer
        //    caches thread text (no version handshake), so we always send the full
        //    blob — the re-stream is near-instant and the open animation covers it.
        //    A network can ask to show its catch-up instead (catchUpBeforeShowing).
        val stored = MessageStore.loadAsThreadJson(this, convoId)
        val hasStored = (JSONObject(stored).optJSONArray("messages")?.length() ?: 0) > 0
        val catchUpFirst = try {
            Mirror.activeService()?.catchUpBeforeShowing(convoId) == true
        } catch (_: Throwable) { false }
        if (hasStored && !catchUpFirst) {
            LogStore.i(TAG, "Serving stored thread for convo $convoId")
            streamMessages(convoId, stored)
            pushDraft(convoId)   // after the thread: the watch takes it for this chat's token
        }

        // 2. Catch-up fetch and re-push only if changed.
        try {
            val svc = Mirror.activeService() ?: run { LogStore.w(TAG, "No service"); return }
            LogStore.i(TAG, "openThread: activeService=${svc.id} activeServiceId=${Mirror.activeServiceId} convo=$convoId")
            val json = svc.fetchMessages(convoId, FETCH_COUNT, "")
            val msgs = JSONObject(json).optJSONArray("messages") ?: JSONArray()
            val added = MessageStore.merge(this, convoId, msgs)
            LogStore.i(TAG, "Catch-up added $added new messages for $convoId")
            if (added > 0 || !hasStored || catchUpFirst) {
                if (Mirror.currentOpenConvoId == convoId) {
                    streamMessages(convoId, MessageStore.loadAsThreadJson(this, convoId))
                    if (!hasStored || catchUpFirst) pushDraft(convoId)
                }
            }
        } catch (e: Exception) {
            LogStore.e(TAG, "Catch-up fetch failed", e)
            if (catchUpFirst && hasStored && Mirror.currentOpenConvoId == convoId) {
                streamMessages(convoId, MessageStore.loadAsThreadJson(this, convoId))
                pushDraft(convoId)
            }
        }
        // 3. A feed shorter than the watch's window: its next pages, in the background.
        if (Mirror.isFeed(convoId) && Mirror.currentOpenConvoId == convoId)
            scope.launch { fillFeedBelow(convoId) }
    }

    // A feed that opens with fewer rows than the watch holds (step 95; Instagram's first
    // page is ~20 posts): the watch asks for more only once a thread outgrows its window,
    // so it would never see the rest. Its next pages are fetched here, one at a time
    // (the network's backfill contract: Mirror.backfillCursor and fetchMessages with it),
    // until the window is full, the network has no more, or the feed is closed; each
    // page's rows go to the watch marked KEY_THREAD_GREW, which adds them below without
    // moving the view. Live pushes are held off meanwhile (backfillQuietUntil), as for
    // a backfill, so the merge doesn't come back as a fresh push.
    // A reply room's replies come the same way (step 99), its first page too ([first]:
    // waited for, where a fill already running for a feed would skip it).
    private suspend fun fillFeedBelow(convoId: String, first: Boolean = false) {
        val want = when {
            Mirror.msgSpanCount > 0 -> Mirror.msgSpanCount
            Mirror.watchDisplayW >= 200 -> 24
            else -> 16
        }
        if (first) backfillMutex.lock() else if (!backfillMutex.tryLock()) return
        try {
            for (page in 1..FEED_FILL_PAGES) {
                if (Mirror.currentOpenConvoId != convoId) return
                val total = buildTrimmedCached(convoId).size
                if (total >= want || Mirror.backfillExhausted[convoId] == true) return
                val cur = Mirror.backfillCursor[convoId] ?: return
                val svc = Mirror.activeService() ?: return
                Mirror.backfillQuietUntil[convoId] = System.currentTimeMillis() + BACKFILL_QUIET_MS
                val t0 = System.currentTimeMillis()
                val arr = fetchBelow(svc, convoId, cur)
                if (Rooms.isRoom(convoId) && Rooms.get(convoId) == null) return   // (left meanwhile)
                if (arr.length() == 0) {
                    Mirror.backfillExhausted[convoId] = true
                    LogStore.i(TAG, "Feed fill: no more for $convoId at $total")
                    return
                }
                mergeBelow(convoId, arr)
                val newTotal = buildTrimmedCached(convoId).size
                Mirror.backfillQuietUntil[convoId] = System.currentTimeMillis() + BACKFILL_QUIET_MS
                if (newTotal <= total) {
                    Mirror.backfillExhausted[convoId] = true
                    LogStore.i(TAG, "Feed fill: page $page added nothing new for $convoId")
                    return
                }
                LogStore.i(TAG, "Feed fill: page $page, $total -> $newTotal rows in " +
                        "${System.currentTimeMillis() - t0} ms, sent below")
                if (Mirror.currentOpenConvoId != convoId) return
                streamMessages(convoId, MessageStore.loadAsThreadJson(this, convoId),
                    spanStart = total, spanCount = minOf(newTotal, want) - total, grew = true)
            }
        } catch (e: Exception) {
            LogStore.e(TAG, "Feed fill failed for $convoId", e)
        } finally {
            backfillMutex.unlock()
        }
    }

    /**
     * One page below what's held: a chat's or feed's from its network (fetchMessages
     * with the cursor it left in Mirror.backfillCursor), or a reply room's next replies
     * (step 99: their cursor kept there here, none when there are no more).
     */
    private fun fetchBelow(svc: MessagingService, convoId: String, cursor: String): JSONArray {
        if (!Rooms.isRoom(convoId))
            return JSONObject(svc.fetchMessages(convoId, BACKFILL_BATCH, cursor)).optJSONArray("messages") ?: JSONArray()
        val room = Rooms.get(convoId) ?: return JSONArray()     // (left meanwhile)
        val src = svc as? ReplySource ?: return JSONArray()
        val res = JSONObject(src.fetchReplies(room.feed, room.postId, cursor))
        val next = res.optString("cursor")
        if (next.isNotEmpty() && next != cursor && Rooms.get(convoId) != null) Mirror.backfillCursor[convoId] = next
        else Mirror.backfillCursor.remove(convoId)
        return res.optJSONArray("messages") ?: JSONArray()
    }

    /** What fetchBelow brought, kept: in the store, or a room's (step 99). How many were new. */
    private fun mergeBelow(convoId: String, arr: JSONArray): Int =
        if (Rooms.isRoom(convoId)) Rooms.get(convoId)?.append(arr) ?: 0
        else MessageStore.merge(this, convoId, arr)

    // ---- Reply rooms (step 99) ----------------------------------------------------------
    // A feed post's replies, in a room of their own (Rooms): the post at the top, alone at
    // first, its replies coming in below it as the network gives them (the feed's fill),
    // more as the watch nears their end (a backfill). Every room is a chat to the rest of
    // this service, by its own convoId.

    private val roomMutex = Mutex()

    /** Open the room of row [abs] of the chat on the watch, which asked from [depth] rooms deep. */
    private suspend fun openRoom(depth: Int, abs: Int) {
        val roomId = roomMutex.withLock {
            val cur = Mirror.currentOpenConvoId ?: return
            if (depth != Rooms.depth()) {
                LogStore.w(TAG, "Room asked for from depth $depth, now ${Rooms.depth()}: ignored")
                return
            }
            if (Mirror.activeService() !is ReplySource) return
            val row = buildTrimmedCached(cur).getOrNull(abs)
            if (row == null || row.drill < 0 || row.postId.isEmpty()) {
                LogStore.w(TAG, "Room asked for at row $abs, which opens none")
                return
            }
            val parent = postJson(cur, row.postId) ?: run {
                LogStore.w(TAG, "Room asked for: post ..${row.postId.takeLast(8)} not found")
                return
            }
            val room = Rooms.open(Rooms.netConvo(cur), row.postId, cur, abs, parent)
            Mirror.currentOpenConvoId = room.id
            pruneRoomParses()
            Mirror.backfillCursor[room.id] = ""          // its replies' first page
            LogStore.i(TAG, "Room ${Rooms.depth()} opened at row $abs: post ..${row.postId.takeLast(8)}, ${row.drill} replies")
            streamMessages(room.id, "{}")
            room.id
        }
        fillFeedBelow(roomId, first = true)
    }

    /** Back out of rooms to [to] rooms deep (0: the feed), landing on the post the last one was. */
    private suspend fun leaveRoom(to: Int) {
        roomMutex.withLock {
            if (!Rooms.isRoom(Mirror.currentOpenConvoId)) return
            var left: Rooms.Room? = null
            while (Rooms.depth() > maxOf(to, 0)) left = Rooms.pop() ?: break
            val r = left ?: return
            Mirror.currentOpenConvoId = r.below
            val rows = buildTrimmedCached(r.below)
            val at = rows.indexOfFirst { it.postId == r.postId && it.drill >= 0 }.takeIf { it >= 0 }
                ?: r.anchor.coerceIn(0, maxOf(0, rows.size - 1))
            LogStore.i(TAG, "Back out of a room to depth ${Rooms.depth()}: lands on row $at")
            streamMessages(r.below, if (Rooms.isRoom(r.below)) "{}" else MessageStore.loadAsThreadJson(this, r.below),
                landAbs = at, roomNav = true)
            pruneRoomParses()
        }
        // Its fill below, if the room left cut it short (the watch asks for more only
        // once a chat outgrows its window).
        val below = Mirror.currentOpenConvoId
        if (below != null && Mirror.isFeed(below)) scope.launch { fillFeedBelow(below) }
    }

    /** Parses of rooms no longer open, dropped. */
    private fun pruneRoomParses() {
        synchronized(parseCache) { parseCache.keys.removeAll { Rooms.isRoom(it) && Rooms.get(it) == null } }
    }

    /** Message [postId] as its chat holds it: a room's, or the store's. */
    private fun postJson(convoId: String, postId: String): JSONObject? {
        Rooms.get(convoId)?.let { return it.message(postId) }
        val arr = MessageStore.load(this, convoId)
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            if (m.optString("messageID") == postId) return m
        }
        return null
    }

    /** The row of a room's post (its id's), what a reply from its chat answers; -1 elsewhere. */
    private fun roomPostRow(convoId: String): Int {
        val room = Rooms.get(convoId) ?: return -1
        return buildTrimmedCached(convoId).indexOfFirst { it.messageId == room.postId }
    }

    /** A reply just sent, as a message the room can show (its id the network's, when it said). */
    private fun sentJson(convoId: String, result: String, text: String): JSONObject {
        val mid = try { JSONObject(result).optString("messageId") } catch (_: Exception) { "" }
        return JSONObject()
            .put("messageID", mid.ifEmpty { "sent:" + System.nanoTime() })
            .put("conversationID", convoId)
            .put("timestamp", (System.currentTimeMillis() * 1000L).toString())
            .put("senderParticipant", JSONObject().put("isMe", true))
            .put("messageInfo", JSONArray().put(JSONObject().put("messageContent", JSONObject().put("content", text))))
    }

    /**
     * Watch-send guard. Entitlement moved off the phone entirely — it now lives in
     * the watchapp's PKJS layer, which decides whether to render — so the phone
     * mirrors unconditionally and this always returns true. Kept as the single seam
     * every outbound emitter already calls, so if gating is ever needed again it's a
     * one-line change here rather than across all five call sites.
     */
    private fun watchSendAllowed(): Boolean = true

    // ---- Shared message-row model + parser ---------------------------------

    data class MsgRow(
        val ts: Long, val text: String, val incoming: Int,
        val sender: String, val mediaID: String, val decryptionKeyB64: String,
        val mediaDataB64: String,
        // For a combined image+caption that we SPLIT into two chat rows, `link`
        // is the partner row's index (image<->caption). -1 for normal messages.
        var link: Int = -1,
        // The FULL untruncated text. `text` is the (possibly truncated) preview
        // sent in the thread blob; the watch fetches `fullText` on demand only
        // when a message was actually truncated. Empty == not truncated.
        val fullText: String = "",
        // The source message id (FB mid.$.../whatsmeow id/Matrix id/etc.), used to
        // look up reactions in ReactionStore at pack time. Empty for synthetic rows
        // (e.g. the caption half of a split image+caption) so they carry no badge.
        val messageId: String = "",
        // Quoted-reply context when this message replies to another, kept as TWO
        // discrete tokens so the watch gets the original sender's NAME cleanly
        // (not parsed back out of the snippet) and can style it. Both "" when the
        // message isn't a reply. Live on the primary/image row; the caption half
        // carries none, mirroring messageId/reactions.
        val replyTitle: String = "",
        val replySnippet: String = "",
        // True when the media is an ANIMATED image (GIF), judged from its mime /
        // format at parse time. The watch shows the motion placeholder for these
        // until the animation plays (KEY_IMG_INDICES entry gets an 'a' suffix).
        val animated: Boolean = false,
        // A video's own media (kept separately: the still-image fields above stay
        // empty for a video-only message). Streamed on request.
        val videoMediaID: String = "",
        val videoKeyB64: String = "",
        // A video that came with a poster frame: the still fields above hold the
        // poster (prefixed), the row keeps its "[Video]" label, and the watch
        // draws the poster with the video icon over it (KEY_IMG_INDICES 'v').
        val poster: Boolean = false,
        // A voice note (step 86): its still and video fields both hold its media id behind
        // VOICE_PREFIX. The watch shows its card (drawn here) and plays it through the
        // video player (KEY_IMG_INDICES 'u').
        val voice: Boolean = false,
        // Media from a LINK (LinkMedia): its caption rides on the media row itself
        // (the watch shows it under the picture) instead of a split-off caption row,
        // so the row count is the same before and after the link is looked up.
        val caption: String = "",
        // A link LinkMedia hasn't looked up yet: streaming the row asks it to.
        val pendingLink: String = "",
        // A reply to a picture: the quoted message's id hash (hex, the watch's own
        // hash of its field 10), so the quote can show that message's preview.
        val replyPic: String = "",
        // An album (step 88): two or more pictures, GIFs or videos shown as one row,
        // from one message or from several sent together (mergeAlbums). Each item is a
        // row of its own, its media in the fields above. The chat shows the album as
        // one collage drawn here (albumStill); the detail view one item at a time.
        val album: List<MsgRow> = emptyList(),
        // The network's id for an album it sends as separate messages (Telegram's
        // grouped id, WhatsApp's album): parts with the same one become one album.
        val albumId: String = "",
        // What the message's own media is, as the network sent it ('p' a picture, 'v'
        // a video, 'g' a GIF; ' ' none, or media found in a link): what decides whether
        // it can join an album of separate messages. Never what it turned into later (a
        // video found to loop, a link looked up), so an album doesn't change shape
        // under the watch, whose rows are numbered by it.
        val albumKind: Char = ' ',
        // A feed's post (step 99): its likes (shown as a reaction badge with their count),
        // the reply room this row opens (>= 0: its reply count), whether it's
        // a room's post (at the room's top), the count under its last row there (>= 0),
        // and the post's id (a caption's or a "N replies" row's too).
        val likes: Long = 0L,
        // Whether the user has liked it (step 101): 1 yes, 0 no, -1 the network doesn't say.
        val liked: Int = -1,
        val drill: Int = -1,
        val roomTop: Boolean = false,
        val foot: Int = -1,
        val postId: String = ""
    )

    // One picture, GIF or video of a message, before it's known whether the message
    // is an album (two or more of them) or a single picture (step 88).
    private class MediaPart(
        val mediaID: String, val keyB64: String, val inlineB64: String, val animated: Boolean,
        val videoID: String = "", val videoKeyB64: String = "",
        val posterB64: String = "", val posterID: String = "", val posterKey: String = ""
    )

    // Pull the (sender name, text, isMe) of a stored message — used to resolve the
    // quoted message a reply points at, when the source only handed us its id.
    // Reduce a display name to just the first name (first whitespace-delimited
    // token) for in-thread use — the name above a bubble and the reply target.
    // The conversation list still shows full names; this only affects the thread
    // view. Blank in -> blank out; a single-token name is returned unchanged.
    // Stable uint32 token identifying a chat, so the watch can key its thumbnail
    // cache by (token, row) rather than row alone — killing the cross-chat bleed
    // where two chats' same-index images shared a cache slot. FNV-1a over the
    // convoId's UTF-8 bytes: deterministic per chat, good spread. Sent as Int32;
    // the watch reads the same 32 bits as uint32.
    // Opaque per-thread token the watch uses to key its thumbnail cache. In the span
    // world it MUST be constant for the life of an open thread (across all its spans),
    // so the watch's (token, absolute-row) thumbnail cache survives scrolling within
    // the thread — a purged-and-refetched row keeps its thumbnail. Derived purely from
    // convoId (no page/span fold): same thread => same token, every span.
    internal fun threadToken(convoId: String): Int {
        var h = 0x811C9DC5.toInt()               // FNV offset basis
        for (b in convoId.toByteArray(Charsets.UTF_8)) {
            h = (h xor (b.toInt() and 0xFF)) * 0x01000193   // xor byte, FNV prime (wraps at 32-bit)
        }
        return h
    }

    private fun firstNameOnly(name: String): String {
        val t = name.trim()
        val sp = t.indexOf(' ')
        return if (sp <= 0) t else t.substring(0, sp)
    }

    // Pre-fit a string to at most maxBytes UTF-8 bytes for a fixed-width watch
    // buffer, appending an ellipsis if it had to be cut — so the truncation reads
    // cleanly ("Christopher…") instead of the watch's silent hard clamp. Never
    // splits a multibyte character, so accented names / emoji can't overrun.
    private fun fitField(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val ell = "…"
        val budget = maxBytes - ell.toByteArray(Charsets.UTF_8).size
        if (budget <= 0) return ""
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            val piece = s.substring(i, i + n)
            val pieceBytes = piece.toByteArray(Charsets.UTF_8).size
            if (used + pieceBytes > budget) break
            sb.append(piece)
            used += pieceBytes
            i += n
        }
        return sb.toString() + ell
    }

    private fun quotedSummary(mm: org.json.JSONObject): Triple<String, String, Boolean> {
        val sp = mm.optJSONObject("senderParticipant")
        val isMe = sp?.optBoolean("isMe", false) ?: false
        val name = (sp?.optString("firstName")?.takeIf { it.isNotEmpty() }
            ?: sp?.optString("fullName")?.takeIf { it.isNotEmpty() } ?: "")
        var text = ""
        mm.optJSONArray("messageInfo")?.let { infos ->
            for (j in 0 until infos.length()) {
                val c = infos.getJSONObject(j).optJSONObject("messageContent")?.optString("content")
                if (!c.isNullOrEmpty()) { text = c; break }
            }
        }
        return Triple(name, text, isMe)
    }

    // True when the quoted message shows as a picture on the watch (an attachment, or
    // a link that becomes one), so its reply can show the picture's preview.
    private fun quotedHasPicture(q: org.json.JSONObject): Boolean {
        val infos = q.optJSONArray("messageInfo") ?: return false
        for (j in 0 until infos.length()) {
            val info = infos.optJSONObject(j) ?: continue
            if (info.optJSONObject("mediaContent") != null) return true
            val c = info.optJSONObject("messageContent")?.optString("content") ?: ""
            if (c.isNotEmpty() && (LinkMedia.find(c) != null || MediaUrls.gifLink(c) != null ||
                    MediaUrls.videoUrl(c) != null)) return true
        }
        return false
    }

    // Build a reply snippet preview of a quoted message, tagged by media type: a
    // leading "[Link]"/"[Image]"/"[Video]"/"[Voice]" token, then as much of the caption/
    // text as the snippet budget allows (buildReplyTitle does the final flatten + cap).
    // The bridge marks bare links as "[Link]" and FB videos as "[video]"; images
    // carry an image mediaContent, voice notes an audio one (step 86). Plain text
    // returns unchanged (no token).
    private fun quotedSnippetText(q: org.json.JSONObject): String {
        var text = ""
        var hasImage = false
        var hasVoice = false
        q.optJSONArray("messageInfo")?.let { infos ->
            for (j in 0 until infos.length()) {
                val info = infos.getJSONObject(j)
                if (text.isEmpty()) {
                    val c = info.optJSONObject("messageContent")?.optString("content")
                    if (!c.isNullOrEmpty()) text = c
                }
                val mc = info.optJSONObject("mediaContent")
                if (mc != null) {
                    val fmt = mc.optString("format").uppercase()
                    val mime = mc.optString("mimeType").lowercase()
                    if (fmt.contains("IMAGE") || mime.startsWith("image/")) hasImage = true
                    if (fmt.startsWith("AUDIO") || mime.startsWith("audio/")) hasVoice = true
                }
            }
        }
        val trimmed = text.trimStart()
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("[link]") -> {
                val rest = trimmed.drop(6).trim { it == '\n' || it == '\r' || it == ' ' }
                if (rest.isEmpty()) "[Link]" else "[Link] $rest"
            }
            lower == "[video]" -> "[Video]"
            hasImage -> if (text.isEmpty()) "[Image]" else "[Image] $text"
            hasVoice -> if (text.isEmpty() || isMediaPlaceholder(text)) "[Voice]" else "[Voice] $text"
            else -> text
        }
    }

    // Compose the quoted-reply tokens for the watch from the resolved quoted
    // message (name/text/isMe) plus who sent THIS message. Returns (target, snippet):
    //   target — ONLY the person/pronoun being replied to, with is-me resolved to a
    //            pronoun phone-side. The watch glues "<replier> replied to <target>",
    //            where <replier> is "You" for its own messages or the sender (field 1)
    //            for incoming. So we emit just the tail token:
    //              me -> Kendra       => "Kendra"   (watch: "You replied to Kendra")
    //              me -> my own msg   => "yourself" (watch: "You replied to yourself")
    //              Kendra -> me       => "you"      (watch: "Kendra replied to you")
    //              Kendra -> Nicole   => "Nicole"   (watch: "Kendra replied to Nicole")
    //   snippet — a trimmed preview of the quoted text, enough to recognize it.
    // Both "" when there's nothing to show. (The watch still gets field 0 to know
    // if THIS message is mine, for bubble side/color — that's separate from this.)
    private fun buildReplyTitle(
        quotedName: String, quotedText: String, quotedIsMe: Boolean,
        replierName: String, replierIsMe: Boolean
    ): Pair<String, String> {
        if (quotedName.isEmpty() && quotedText.isEmpty() && !quotedIsMe) return "" to ""
        // Field 7 now carries ONLY the reply target — the person/pronoun being
        // replied to. The watch composes the visible "<replier> replied to <target>"
        // itself, using "You" for its own messages or the message's sender (field 1)
        // for incoming. We keep all identity logic here: is-me -> pronoun, otherwise
        // the resolved name. (replierName is retained in the signature but no longer
        // glued in — the watch derives the replier from the sender field directly,
        // so the two can never diverge.)
        val target = when {
            replierIsMe && quotedIsMe -> "yourself"  // I replied to my own message
            quotedIsMe -> "you"                       // someone replied to me
            else -> fitField(EmojiSub.processText(firstNameOnly(quotedName)), WATCH_NAME_MAX_BYTES).ifBlank { "someone" }
        }
        var snippet = EmojiSub.processText(quotedText.replace("\n", " ").replace("\r", " ").trim())
        snippet = fitField(snippet, WATCH_SNIPPET_MAX_BYTES)
        return target to snippet
    }

    /** Parse + sort + trim a stored thread JSON into the exact row list the
     *  watch indexes against (so msg indices match between thread + image). */
    private fun buildSorted(json: String): List<MsgRow> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("messages") ?: JSONArray()
        // Index messages by id so a reply can resolve its quoted message's
        // sender/text/isMe from the stored thread when the source only gave us the
        // quoted id (E2EE arrivals, and our own optimistically-inserted replies).
        val byId = HashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val mm = arr.getJSONObject(i)
            val id = mm.optString("messageID")
            if (id.isNotEmpty()) byId[id] = mm
        }
        val list = ArrayList<MsgRow>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val first = list.size   // (step 99: this message's rows start here, for roomTag)
            val msgId = m.optString("messageID")
            // The detail-view text a network sets itself (X: every post, so opening
            // one can show its replies). Empty: the text, when it was cut.
            val msgFull = m.optString("fullText")
            val sp = m.optJSONObject("senderParticipant")
            val isMe = sp?.optBoolean("isMe", false) ?: false
            // Never leave the sender blank, or the watch shows no name at all for
            // that message. Prefer firstName, fall back to fullName, then the
            // formatted/raw number — so a contact's name always appears at least
            // once in the thread. In-thread we show just the first name (the convo
            // list keeps the full name); number fallbacks are left whole.
            val sender = fitField(
                (sp?.optString("firstName")?.takeIf { it.isNotEmpty() }?.let { firstNameOnly(it) }
                    ?: sp?.optString("fullName")?.takeIf { it.isNotEmpty() }?.let { firstNameOnly(it) }
                    ?: sp?.optString("formattedNumber")?.takeIf { it.isNotEmpty() }
                    ?: sp?.optJSONObject("ID")?.optString("number")
                    ?: ""),
                WATCH_NAME_MAX_BYTES
            )
            // Quoted-reply source: FB-style "replyTo" {id,sender,text,isMe}, or
            // Google RCS's native protojson "replyMessage" {messageID}. Either way
            // the quoted sender/text/isMe is resolved from the stored thread by id.
            val replyObj = m.optJSONObject("replyTo")
            val gReplyId = m.optJSONObject("replyMessage")?.optString("messageID") ?: ""
            var replyPic = ""
            val (replyTitle, replySnippet) =
                if (replyObj == null && gReplyId.isEmpty()) "" to ""
                else {
                    var qName = replyObj?.optString("sender") ?: ""
                    var qText = replyObj?.optString("text") ?: ""
                    var qIsMe = replyObj?.optBoolean("isMe", false) ?: false
                    val qId = replyObj?.optString("id")?.takeIf { it.isNotEmpty() } ?: gReplyId
                    // Fill anything the source didn't give us from the quoted message
                    // in this thread (the store is authoritative for direction). For
                    // the quoted TEXT, prefer the store's type-aware display ("[Link]
                    // …", "[Image] …", "[Video]") over the source's raw reply snippet,
                    // so a reply to a link/image/etc. shows the media token first.
                    if (qId.isNotEmpty()) byId[qId]?.let { q ->
                        val (sn, _, me) = quotedSummary(q)
                        val disp = quotedSnippetText(q)
                        if (qName.isEmpty()) qName = sn
                        if (disp.isNotEmpty()) qText = disp
                        qIsMe = me
                        if (quotedHasPicture(q)) {
                            val qid = q.optString("messageID").replace(BLOB_RS, "").replace(BLOB_US, "")
                            if (qid.isNotEmpty()) replyPic = Integer.toHexString(threadToken(qid))
                        }
                    }
                    buildReplyTitle(qName, qText, qIsMe, sender, isMe)
                }
            val ts = m.optString("timestamp").toLongOrNull() ?: 0L
            var text = ""; var mediaID = ""; var decryptionKeyB64 = ""
            var posterB64 = ""; var posterID = ""; var posterKey = ""
            var mediaDataB64 = ""
            val infos = m.optJSONArray("messageInfo")
            var sawMediaPart = false
            var sawVideoPart = false
            var animated = false
            var videoMediaID = ""
            var videoKeyB64 = ""
            var mediaFmt = ""
            var mediaMime = ""
            var voiceID = ""
            var voiceKey = ""
            var sawVoicePart = false
            // Every picture, GIF and video the message has, in order (step 88): two or
            // more make it an album. (The fields above keep the first of each kind.)
            val parts = ArrayList<MediaPart>()
            if (infos != null) {
                for (j in 0 until infos.length()) {
                    val info = infos.getJSONObject(j)
                    val mc = info.optJSONObject("messageContent")
                    val media = info.optJSONObject("mediaContent")
                    if (mc != null) {
                        val c = mc.optString("content")
                        if (c.isNotEmpty()) { text = c }
                    }
                    if (media != null) {
                        // Video is NOT a still image: the Pebble image pipeline
                        // (and the Go decoder it streams to) only handles JPEG/PNG,
                        // so feeding it MP4 bytes throws "image: unknown format" and
                        // hangs the transfer. Detect video by format/mimeType and
                        // surface it as a "[Video]" placeholder instead of trying
                        // to download + decode it as a picture.
                        val fmt = media.optString("format")
                        val mime = media.optString("mimeType")
                        if (fmt.startsWith("VIDEO") || mime.startsWith("video/")) {
                            parts.add(if (media.optBoolean("gifLike"))
                                MediaPart(CLIP_PREFIX + media.optString("mediaID"), media.optString("decryptionKey"), "", true)
                                else videoPart(media))
                            // A clip the network marks as a GIF (WhatsApp's GIFs,
                            // Telegram animations, Messenger GIFs): a short silent
                            // loop. It rides the GIF path — animated row preview,
                            // looping detail view — not the video player. The id is
                            // tagged so the fetch paths get the file through the
                            // network's own downloadMediaToFile.
                            if (media.optBoolean("gifLike") && mediaID.isEmpty() && mediaDataB64.isEmpty()) {
                                mediaID = CLIP_PREFIX + media.optString("mediaID")
                                decryptionKeyB64 = media.optString("decryptionKey")
                                animated = true
                                sawMediaPart = true
                                if (mediaFmt.isEmpty()) mediaFmt = "IMAGE"
                                if (mediaMime.isEmpty()) mediaMime = "image/gif"
                                continue
                            }
                            sawVideoPart = true
                            if (videoMediaID.isEmpty()) {
                                videoMediaID = media.optString("mediaID")
                                videoKeyB64 = media.optString("decryptionKey")
                                // The poster frame the network ships with the video:
                                // inline bytes, or an id its image path can fetch.
                                posterB64 = media.optString("posterData")
                                posterID = media.optString("posterMediaID")
                                posterKey = media.optString("posterKey")
                                if (posterB64.isEmpty() && posterID.isEmpty()) {
                                    // Google Messages: a small preview JPEG rides inline in
                                    // mediaData, exactly as it does for images; failing that,
                                    // its own thumbnail id, which its image download fetches.
                                    posterB64 = media.optString("mediaData")
                                    if (posterB64.isEmpty()) {
                                        posterID = media.optString("thumbnailMediaID")
                                        posterKey = media.optString("thumbnailDecryptionKey")
                                    }
                                }
                                val keys = media.keys().asSequence().joinToString(",")
                                LogStore.i(TAG, "VIDPARSE fmt=$fmt mime=$mime poster=" +
                                    (if (posterB64.isNotEmpty()) "inline(${posterB64.length})"
                                     else if (posterID.isNotEmpty()) "id(${posterID.length})" else "none") +
                                    " keys=$keys")
                            }
                            continue
                        }
                        // A voice note (step 86): any sound a network tags (format AUDIO_*,
                        // or an audio/ type; Google Messages' voice messages already are).
                        // Not a picture either: its card is drawn for it, and it
                        // plays through the video player.
                        if (fmt.startsWith("AUDIO") || mime.startsWith("audio/")) {
                            sawVoicePart = true
                            if (voiceID.isEmpty()) {
                                voiceID = media.optString("mediaID")
                                voiceKey = media.optString("decryptionKey")
                            }
                            continue
                        }
                        sawMediaPart = true
                        run {
                            val pid = media.optString("mediaID")
                            parts.add(MediaPart(pid.ifEmpty { media.optString("thumbnailMediaID") },
                                if (pid.isNotEmpty()) media.optString("decryptionKey") else media.optString("thumbnailDecryptionKey"),
                                media.optString("mediaData"),
                                mime.equals("image/gif", ignoreCase = true) || fmt.contains("GIF", ignoreCase = true)))
                        }
                        if (mediaFmt.isEmpty() && mediaMime.isEmpty()) { mediaFmt = fmt; mediaMime = mime }
                        if (mime.equals("image/gif", ignoreCase = true) ||
                            fmt.contains("GIF", ignoreCase = true)) animated = true
                        if (mediaID.isEmpty()) {
                            mediaID = media.optString("mediaID")
                            decryptionKeyB64 = media.optString("decryptionKey")
                        }
                        // Some image messages expose the downloadable reference
                        // only under thumbnailMediaID/thumbnailDecryptionKey (no
                        // plain mediaID), even though it points to the full-res
                        // media. Fall back to it so these images download at full
                        // resolution instead of stranding on the inline preview.
                        if (mediaID.isEmpty()) {
                            mediaID = media.optString("thumbnailMediaID")
                            decryptionKeyB64 = media.optString("thumbnailDecryptionKey")
                        }
                        if (mediaDataB64.isEmpty())
                            mediaDataB64 = media.optString("mediaData")
                    }
                }
            }
            // An album (step 88): two or more pictures, GIFs or videos in one message.
            // One row for them all (its caption split off as a picture's is), which the
            // chat shows as one collage and the detail view one item at a time.
            val shown = parts.filter { it.mediaID.isNotEmpty() || it.inlineB64.isNotEmpty() || it.videoID.isNotEmpty() }
            if (shown.size >= 2) {
                val inc = if (isMe) 0 else 1
                val snd = sender.take(SENDER_MAX)
                val items = shown.take(ALBUM_MAX).map { albumItem(it, ts, inc, snd, msgId) }
                val caption = if (isMediaPlaceholder(text)) "" else text
                list.add(MsgRow(ts, "[Image]", inc, snd, ALBUM_PREFIX + msgId, "", "",
                    messageId = msgId, replyTitle = replyTitle, replySnippet = replySnippet,
                    replyPic = replyPic, album = items, albumId = m.optString("albumID")))
                if (caption.isNotEmpty()) {
                    val cFull = msgFull.ifEmpty { if (caption.length > MSG_TEXT_MAX) caption else "" }
                    list.add(MsgRow(ts, caption.take(MSG_TEXT_MAX), inc, snd, "", "", "", -2, cFull))
                }
                captionBelow(list, first, m)
                roomTag(list, first, m)
                continue
            }
            val netKind = parts.firstOrNull()?.let { if (it.videoID.isNotEmpty()) 'v' else if (it.animated) 'g' else 'p' } ?: ' '

            // A GIF chosen from a picker (Discord's, and any pasted Tenor/Giphy
            // link elsewhere) arrives as text whose only content is a .gif URL —
            // there is no attachment to download. Treat the link as the media:
            // the URL becomes the mediaID and the shared URL path fetches it, so
            // it animates exactly like an uploaded GIF. Same for a video link.
            if (mediaID.isEmpty() && mediaDataB64.isEmpty() && !sawVideoPart && voiceID.isEmpty()) {
                val gifUrl = MediaUrls.gifLink(text)   // a .gif file, or a picker's share page
                if (gifUrl != null) {
                    mediaID = gifUrl
                    decryptionKeyB64 = ""
                    animated = true
                    sawMediaPart = true
                    if (mediaFmt.isEmpty()) mediaFmt = "IMAGE_GIF"
                    if (mediaMime.isEmpty()) mediaMime = "image/gif"
                    text = MediaUrls.stripUrl(text, gifUrl)   // the link itself isn't worth reading
                }
            }
            if (videoMediaID.isEmpty() && mediaID.isEmpty() && mediaDataB64.isEmpty() && voiceID.isEmpty()) {
                val vidUrl = MediaUrls.videoUrl(text)
                if (vidUrl != null) {
                    videoMediaID = vidUrl
                    videoKeyB64 = ""
                    sawVideoPart = true
                    text = MediaUrls.stripUrl(text, vidUrl)
                }
            }
            // A link to a post (X, Reddit, Imgur) or to a picture: the media it shows,
            // once LinkMedia has looked it up (in the background, kept on disk). Until
            // then, and when the post has none, the message stays text. Either way it
            // is ONE row, so nothing the watch holds shifts when the answer comes: the
            // media, captioned with the sender's words and the link's short label
            // ("x.com/someone"), so it reads as a shared post, not their own photo.
            // The post's own words (its text, its title) aren't in the chat: they're
            // the row's full text, which the watch fetches when the message is opened.
            var linkCaption = ""
            var linkFull = ""
            var pendingLink = ""
            if (videoMediaID.isEmpty() && mediaID.isEmpty() && mediaDataB64.isEmpty() &&
                !sawMediaPart && !sawVideoPart && voiceID.isEmpty()) {
                val link = LinkMedia.find(text)
                if (link != null) {
                    val lm = link.direct ?: LinkMedia.cached(applicationContext, link.url)
                    if (lm == null) {
                        pendingLink = link.url
                    } else if (lm.kind == LinkMedia.Kind.VIDEO) {
                        videoMediaID = lm.url; videoKeyB64 = ""
                        posterID = lm.poster; posterKey = ""; posterB64 = ""
                        sawVideoPart = true
                        // A video with no poster stays a plain "[Video]" row (the watch
                        // plays it by that label), so its caption can't ride along.
                        if (lm.poster.isNotEmpty()) {
                            linkCaption = LinkMedia.caption(text, link)
                            linkFull = LinkMedia.fullCaption(text, link)
                        }
                        text = ""
                    } else if (lm.kind != LinkMedia.Kind.NONE) {
                        mediaID = lm.url; decryptionKeyB64 = ""
                        animated = lm.kind == LinkMedia.Kind.GIF
                        sawMediaPart = true
                        if (mediaFmt.isEmpty()) mediaFmt = if (animated) "IMAGE_GIF" else "IMAGE"
                        if (mediaMime.isEmpty()) mediaMime = if (animated) "image/gif" else "image/jpeg"
                        linkCaption = LinkMedia.caption(text, link)
                        linkFull = LinkMedia.fullCaption(text, link)
                        text = ""
                    }
                }
            }
            // A video found, when it was first opened, to be a short loop:
            // it's a GIF from then on, the same as a network's own GIF clip. Only where
            // that keeps the message the same number of rows (a captioned video with no
            // poster is one text row; as a picture its caption would split off).
            if (videoMediaID.isNotEmpty() && mediaID.isEmpty() && mediaDataB64.isEmpty() && !sawMediaPart &&
                (posterB64.isNotEmpty() || posterID.isNotEmpty() || text.isEmpty() || isMediaPlaceholder(text)) &&
                clipLoops(videoMediaID)) {
                mediaID = CLIP_PREFIX + videoMediaID
                decryptionKeyB64 = videoKeyB64
                animated = true
                sawMediaPart = true
                sawVideoPart = false
                if (mediaFmt.isEmpty()) mediaFmt = "IMAGE"
                if (mediaMime.isEmpty()) mediaMime = "image/gif"
                if (isMediaPlaceholder(text)) text = ""
                videoMediaID = ""; videoKeyB64 = ""
                posterB64 = ""; posterID = ""; posterKey = ""
            }
            val isImageMsg = mediaID.isNotEmpty() || mediaDataB64.isNotEmpty() || sawMediaPart
            if (isImageMsg) {
                LogStore.i(TAG, "IMGPARSE v3: mediaID=${if (mediaID.isEmpty()) "<none>" else "present(${mediaID.length})"} inline=${mediaDataB64.length} isMe=$isMe fmt=${mediaFmt.ifEmpty { "-" }} mime=${mediaMime.ifEmpty { "-" }} anim=$animated")
            }
            // A video-only message (no still image, no text) shows a placeholder
            // so it doesn't fall through to "[Image]" and trigger a doomed decode. So does
            // a voice note whose file isn't there yet (no media id: still downloading).
            if (text.isEmpty() && !isImageMsg) text = if (sawVideoPart) "[Video]" else if (sawVoicePart) "[Voice]" else "[Image]"
            // The watch knows a playable video by the exact label "[Video]"; a network's
            // own wording for the same thing would leave it unplayable.
            if (sawVideoPart && !isImageMsg && isMediaPlaceholder(text)) text = "[Video]"
            val inc = if (isMe) 0 else 1
            val snd = sender.take(SENDER_MAX)
            // A network's bare media label ("[Video]" on a WhatsApp GIF, "[Image]",
            // "[GIF]") is not a caption: split it off and the watch gets a second,
            // text-only "[Video]" row that asks for a video it doesn't have.
            if (isImageMsg && text.isNotEmpty() && !isMediaPlaceholder(text)) {
                // Combined image+caption: SPLIT into two chat rows (image, then
                // caption text). They share the SAME ts so they stay adjacent;
                // links are wired after the final sort/trim below. Both keep the
                // media+caption pairing info so the enhanced view can recombine.
                val cFull = msgFull.ifEmpty { if (text.length > MSG_TEXT_MAX) text else "" }
                list.add(MsgRow(ts, "[Image]", inc, snd, mediaID, decryptionKeyB64, mediaDataB64, messageId = msgId, replyTitle = replyTitle, replySnippet = replySnippet, animated = animated, replyPic = replyPic,
                    albumId = m.optString("albumID"), albumKind = netKind))
                list.add(MsgRow(ts, text.take(MSG_TEXT_MAX), inc, snd, "", "", "", -2, cFull))
                // link = -2 marks "this is the caption half"; resolved below.
            } else if (!isImageMsg && videoMediaID.isNotEmpty() && (posterB64.isNotEmpty() || posterID.isNotEmpty())) {
                // A video with a poster: an image-shaped row that still says "[Video]"
                // so it plays as video, with any caption split off like an image's.
                val pId = if (posterID.isNotEmpty()) POSTER_PREFIX + posterID else ""
                val pData = if (posterID.isEmpty()) POSTER_PREFIX + posterB64 else ""
                val caption = if (isMediaPlaceholder(text)) "" else text
                list.add(MsgRow(ts, "[Video]", inc, snd, pId, posterKey, pData,
                    messageId = msgId, replyTitle = replyTitle, replySnippet = replySnippet,
                    videoMediaID = videoMediaID, videoKeyB64 = videoKeyB64, poster = true,
                    caption = linkCaption, fullText = linkFull.ifEmpty { if (caption.isEmpty()) msgFull else "" },
                    replyPic = replyPic, albumId = m.optString("albumID"), albumKind = netKind))
                if (caption.isNotEmpty()) {
                    val cFull = msgFull.ifEmpty { if (caption.length > MSG_TEXT_MAX) caption else "" }
                    list.add(MsgRow(ts, caption.take(MSG_TEXT_MAX), inc, snd, "", "", "", -2, cFull))
                }
            } else if (!isImageMsg && videoMediaID.isEmpty() && voiceID.isNotEmpty() && Media.engine.playsVoice) {
                // A voice note (step 86): a picture row (its card) that plays
                // through the video player, labelled "[Video]" as a poster is (the watch
                // shows no label under it); a caption splits off as an image's does.
                val tagged = VOICE_PREFIX + voiceID
                val caption = if (isMediaPlaceholder(text)) "" else text
                list.add(MsgRow(ts, "[Video]", inc, snd, tagged, voiceKey, "",
                    messageId = msgId, replyTitle = replyTitle, replySnippet = replySnippet,
                    videoMediaID = tagged, videoKeyB64 = voiceKey, voice = true,
                    fullText = if (caption.isEmpty()) msgFull else "", replyPic = replyPic))
                if (caption.isNotEmpty()) {
                    val cFull = msgFull.ifEmpty { if (caption.length > MSG_TEXT_MAX) caption else "" }
                    list.add(MsgRow(ts, caption.take(MSG_TEXT_MAX), inc, snd, "", "", "", -2, cFull))
                }
            } else {
                val tFull = if (isImageMsg && linkFull.isNotEmpty()) linkFull
                            else msgFull.ifEmpty { if (text.length > MSG_TEXT_MAX) text else "" }
                list.add(MsgRow(ts, text.take(MSG_TEXT_MAX), inc, snd,
                    mediaID, decryptionKeyB64, mediaDataB64, -1, tFull, msgId, replyTitle, replySnippet,
                    animated = animated && isImageMsg,
                    videoMediaID = videoMediaID, videoKeyB64 = videoKeyB64,
                    caption = if (isImageMsg) linkCaption else "", pendingLink = pendingLink, replyPic = replyPic,
                    albumId = m.optString("albumID"), albumKind = netKind))
            }
            captionBelow(list, first, m)
            roomTag(list, first, m)
        }
        // Stable sort: a split pair (same ts, image added before caption) keeps
        // its image-then-caption order. NO cap here — the full history (up to the
        // store's MAX_PER_CONVO) is returned so pageWindow() can slice any page.
        // A reply room's rows (step 99) keep the network's order.
        if (!root.optBoolean("ordered")) list.sortBy { it.ts }
        return mergeAlbums(list)
    }

    // ---- Reply rooms (step 99) ------------------------------------------------------

    /**
     * What a feed's network says of message m, on the rows it became (list[first..], as
     * added: a picture before its caption): its likes, on the row that carries its id;
     * at a room's top ("roomTop", set by Rooms), the post's rows, and its reply count
     * under the last (the first added); a post with replies ("replyCount"), the room its
     * text opens, or with no text a "N replies" row of its own above its picture (under
     * it, "captionBelow", step 100).
     */
    private fun roomTag(list: ArrayList<MsgRow>, first: Int, m: JSONObject) {
        if (first >= list.size) return
        val likes = m.optLong("likeCount", 0L)
        val liked = if (m.has("liked")) (if (m.optBoolean("liked")) 1 else 0) else -1
        val top = m.optBoolean("roomTop", false)
        val n = if (m.has("replyCount")) m.optInt("replyCount", 0).coerceAtLeast(0) else -1
        if (likes <= 0L && liked < 0 && !top && n < 0) return
        val id = m.optString("messageID")
        for (k in first until list.size) {
            val r = list[k]
            list[k] = r.copy(likes = if (r.messageId.isNotEmpty()) likes else 0L, liked = liked, roomTop = top,
                foot = if (top && k == first) maxOf(n, 0) else -1, postId = id)
        }
        if (top || n < 0 || id.isEmpty()) return
        // A picture's caption opens the room (above the picture or, step 100, below it).
        val cap = (first until list.size).firstOrNull { list[it].link == -2 || list[it].link == -3 }
        if (cap != null) { list[cap] = list[cap].copy(drill = n); return }
        // Its text opens the room only when it stays text: a message whose only content
        // is words with a link LinkMedia may make a picture (looked up later, or not)
        // gets the row of its own either way, so its row count never changes.
        val last = list[list.size - 1]
        val linky = LinkMedia.find(messageText(m)) != null
        val isText = last.mediaID.isEmpty() && last.mediaDataB64.isEmpty() &&
            last.videoMediaID.isEmpty() && last.album.isEmpty() && !isMediaPlaceholder(last.text) && !linky
        if (isText) list[list.size - 1] = last.copy(drill = n)
        else if (n > 0) list.add(if (m.optBoolean("captionBelow", false)) first else list.size,   // (under it, step 100)
            MsgRow(last.ts, if (n == 1) "1 reply" else "${shortCount(n.toLong())} replies",
            last.incoming, last.sender, "", "", "", drill = 0, postId = id))
    }

    /**
     * A post its network shows with its picture above its words ("captionBelow", step 100:
     * Instagram's): its caption row goes first, so a feed, newest on top, shows it under the
     * picture (link -3 marks it; -2 is a caption shown above its picture).
     */
    private fun captionBelow(list: ArrayList<MsgRow>, first: Int, m: JSONObject) {
        if (!m.optBoolean("captionBelow", false)) return
        val k = (first until list.size).firstOrNull { list[it].link == -2 } ?: return
        list.add(first, list.removeAt(k).copy(link = -3, postId = m.optString("messageID")))
    }

    /** A message's words as the network sent them (its first content). */
    private fun messageText(m: JSONObject): String {
        val infos = m.optJSONArray("messageInfo") ?: return ""
        for (j in 0 until infos.length()) {
            val c = infos.optJSONObject(j)?.optJSONObject("messageContent")?.optString("content")
            if (!c.isNullOrEmpty()) return c
        }
        return ""
    }

    /** A count as networks show it: 12, 1.2K, 45K, 3.1M. */
    private fun shortCount(n: Long): String = when {
        n < 1000L -> n.toString()
        n < 1_000_000L -> tenths(n / 100L, "K")
        else -> tenths(n / 100_000L, "M")
    }
    private fun tenths(t: Long, unit: String): String =
        if (t < 100L && t % 10L != 0L) "${t / 10L}.${t % 10L}$unit" else "${t / 10L}$unit"

    /**
     * The badge for a post's likes (step 99): a heart (step 101; X's and Instagram's) and
     * how many, the user's among them.
     */
    private fun likesBadge(n: Long): String =
        EmojiSub.reactionGlyph(LIKE_GLYPH) + (if (n > 1L) " " + shortCount(n) else "")

    /** A row's room for the watch (blob field 12): 'p' at a room's top, and a count. */
    private fun roomField(mm: MsgRow): String = when {
        mm.roomTop -> "p" + (if (mm.foot >= 0) mm.foot.toString() else "")
        mm.drill >= 0 -> mm.drill.toString()
        else -> ""
    }

    // ---- Albums (step 88) ---------------------------------------------------------

    // An album row's still id: what marks it as one in the paths that see only ids.
    private val ALBUM_PREFIX = "album:"

    /** One item of an album, as a row of its own: a picture, a GIF or a video. */
    private fun albumItem(p: MediaPart, ts: Long, inc: Int, snd: String, msgId: String): MsgRow =
        if (p.videoID.isEmpty())
            MsgRow(ts, "[Image]", inc, snd, p.mediaID, p.keyB64, p.inlineB64, messageId = msgId, animated = p.animated)
        else if (clipLoops(p.videoID))
            // A video found, when it was first played, to be a short loop: a GIF.
            MsgRow(ts, "[Image]", inc, snd, CLIP_PREFIX + p.videoID, p.videoKeyB64, "", messageId = msgId, animated = true)
        else
            MsgRow(ts, "[Video]", inc, snd,
                if (p.posterID.isNotEmpty()) POSTER_PREFIX + p.posterID else "", p.posterKey,
                if (p.posterID.isEmpty() && p.posterB64.isNotEmpty()) POSTER_PREFIX + p.posterB64 else "",
                messageId = msgId, videoMediaID = p.videoID, videoKeyB64 = p.videoKeyB64,
                poster = p.posterID.isNotEmpty() || p.posterB64.isNotEmpty())

    /** A video part of a message: its file and the poster frame the network ships with it. */
    private fun videoPart(media: JSONObject): MediaPart {
        var pB64 = media.optString("posterData")
        var pID = media.optString("posterMediaID")
        var pKey = media.optString("posterKey")
        if (pB64.isEmpty() && pID.isEmpty()) {
            // Google Messages: the preview JPEG inline in mediaData, else its thumbnail id.
            pB64 = media.optString("mediaData")
            if (pB64.isEmpty()) {
                pID = media.optString("thumbnailMediaID")
                pKey = media.optString("thumbnailDecryptionKey")
            }
        }
        return MediaPart("", "", "", false, media.optString("mediaID"), media.optString("decryptionKey"), pB64, pID, pKey)
    }

    /** A row that can be one picture of an album sent as separate messages. */
    private fun albumable(r: MsgRow): Boolean =
        r.album.isEmpty() && r.albumKind != ' ' && !r.voice && r.link != -2 && r.caption.isEmpty() && r.pendingLink.isEmpty() &&
            r.postId.isEmpty() &&   // (a feed's post, step 99: never part of another's album)
            (r.mediaID.isNotEmpty() || r.mediaDataB64.isNotEmpty() || r.videoMediaID.isNotEmpty()) &&
            (r.text.isEmpty() || isMediaPlaceholder(r.text)) && !r.text.equals("[Sticker]", ignoreCase = true)

    /**
     * Whether picture row c belongs with the album that picture row p ends. Without
     * an albumID, GIFs sent one after another stay messages of their own.
     */
    private fun sameAlbum(p: MsgRow, c: MsgRow): Boolean {
        if (p.incoming != c.incoming || p.sender != c.sender) return false
        if (p.albumId.isNotEmpty() || c.albumId.isNotEmpty()) return p.albumId == c.albumId
        if (p.albumKind == 'g' || c.albumKind == 'g') return false
        return c.ts - p.ts in 0..ALBUM_GAP_US
    }

    /**
     * Pictures sent together as separate messages (how WhatsApp, Telegram and Google
     * Messages deliver an album) become one album row: consecutive picture rows from
     * the same side and sender, with the same albumID when the network gives one,
     * else each sent within ALBUM_GAP_US of the one before. A picture's caption row
     * stays a row of its own, after the album (the first one shows with it). Rows
     * oldest first, as buildSorted sorts them; it's the album's first row that
     * replies and reactions go to.
     */
    private fun mergeAlbums(rows: List<MsgRow>): List<MsgRow> {
        val out = ArrayList<MsgRow>(rows.size)
        var i = 0
        while (i < rows.size) {
            val a = rows[i]
            if (!albumable(a)) { out.add(a); i++; continue }
            val members = arrayListOf(a)
            val captions = ArrayList<MsgRow>()
            var j = i + 1
            while (j < rows.size) {
                val r = rows[j]
                if (r.link == -2 && r.ts == members.last().ts) { captions.add(r); j++; continue }
                if (members.size < ALBUM_MAX && albumable(r) && sameAlbum(members.last(), r)) { members.add(r); j++; continue }
                break
            }
            if (members.size < 2) {
                out.add(a)
                out.addAll(captions)
                i = j
                continue
            }
            val f = members[0]
            val items = members.map { it.copy(link = -1, replyTitle = "", replySnippet = "", replyPic = "", albumId = "") }
            out.add(MsgRow(f.ts, "[Image]", f.incoming, f.sender, ALBUM_PREFIX + f.messageId, "", "",
                messageId = f.messageId, replyTitle = f.replyTitle, replySnippet = f.replySnippet,
                replyPic = f.replyPic, album = items, albumId = f.albumId))
            // The first caption pairs with the album (shown under it when it's opened);
            // any others are rows of text.
            captions.forEachIndexed { k, c -> out.add(if (k == 0) c else c.copy(link = -1)) }
            i = j
        }
        return out
    }

    /** The id a row goes by on the watch: an album's changes as items arrive, so its collage is asked for again. */
    private fun rowWatchId(mm: MsgRow): String =
        if (mm.album.size >= 2 && mm.messageId.isNotEmpty()) "${mm.messageId}#a${mm.album.size}" else mm.messageId

    /** Item k of album row mm, as a row of its own (null: not an album, or no such item). */
    internal fun albumItemRow(mm: MsgRow?, k: Int): MsgRow? =
        if (mm == null || mm.album.size < 2 || k !in mm.album.indices) null else mm.album[k]

    /**
     * The reactions a row shows: its own, and for an album, failing those, the
     * first of its pictures' that has any (an album sent as separate messages gets
     * its reactions picture by picture).
     */
    private fun rowReactions(mm: MsgRow, convoId: String): String {
        // A feed post's likes (step 99), the user's among them as the network says, else as
        // liked from the watch; marked so when they are (step 101).
        if (mm.messageId.isNotEmpty() && (mm.likes > 0L || mm.liked == 1)) {
            val mine = if (mm.liked >= 0) mm.liked == 1 else ReactionStore.sentMine(mm.messageId) != null
            return (if (mine) BADGE_MINE else "") + likesBadge(mm.likes)
        }
        // A badge with the reaction the user sent it from the watch among it (step 101).
        fun mark(badge: String, id: String): String {
            val sent = ReactionStore.sentMine(id) ?: return badge
            return if (badge.replace("\uFE0F", "").contains(emojiKey(EmojiSub.reactionGlyph(sent)))) BADGE_MINE + badge else badge
        }
        val own = ReactionStore.summaryForStamped(mm.messageId, convoId, mm.ts)
        if (own.isNotEmpty() || mm.album.size < 2) return mark(own, mm.messageId)
        for (it in mm.album) {
            if (it.messageId.isEmpty() || it.messageId == mm.messageId) continue
            val s = ReactionStore.summaryForStamped(it.messageId, convoId, it.ts)
            if (s.isNotEmpty()) return mark(s, it.messageId)
        }
        return ""
    }

    /**
     * How wide an album's collage is in the chat of a watch sw px wide: 160 on a Time 2's
     * 200, and never wider than the chat's room for a picture's bubble, selected (the
     * screen less its rail, margins and the bubble's frame: 108 on a 144).
     */
    private fun albumThumbW(sw: Int): Int = maxOf(64, minOf(ALBUM_THUMB_W, sw - 36)) and 1.inv()

    /**
     * The box a chat preview (a picture, a GIF, a video's poster) fits in, as [w, h], on a
     * watch [dispW] px wide (0: as last reported): 106 x 98 on the Time 2's 200 (step 103,
     * 1.33x), Schema's 80 x 74 on narrower ones. An animated preview's bytes follow it.
     */
    private fun previewBox(dispW: Int): IntArray {
        val sw = if (dispW > 0) dispW else Mirror.watchDisplayW
        return if (sw >= 200) intArrayOf(PREVIEW_W_BIG, PREVIEW_H_BIG)
               else intArrayOf(Schema.IMG_SMALL_W, Schema.IMG_SMALL_H)
    }

    /** What shows between a collage's tiles: its bubble's colour in the chat, the screen's in the detail view. */
    private fun albumGapRgb(mm: MsgRow, full: Boolean): Int {
        val night = Mirror.nightMode
        return if (full) (if (night) 0x000000 else 0xFFFFFF)
               else if (mm.incoming != 0) (if (night) 0x555555 else 0xAAAAAA)   // GColorDarkGray / LightGray
               else (if (night) 0x000055 else 0x0055AA)                          // GColorOxfordBlue / CobaltBlue
    }

    /** What a collage is made of, for its cache keys: the album's count and its first items. */
    private fun albumSig(mm: MsgRow): String =
        mm.album.size.toString() + mm.album.take(4).joinToString("") {
            "|" + (if (it.videoMediaID.isNotEmpty()) "v" else if (it.animated) "a" else "i") +
                Integer.toHexString(fullSrc(it.mediaID, it.mediaDataB64).hashCode())
        }

    // The pictures collages were made of, as fetched for a tile: an album sent as
    // separate messages arrives a picture at a time, and each time it's a new collage.
    private val albumTiles = object : LinkedHashMap<String, AlbumCollage.Pic>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AlbumCollage.Pic>?): Boolean =
            size > ALBUM_TILES_KEPT
    }

    /**
     * An album's collage (step 88), streamed as its picture. In the chat it's
     * albumThumbW of the watch's width, 4:3, the bubble's colour between its tiles;
     * in the detail view of a watch that doesn't page through albums, the screen's
     * width. Made once and kept, like any picture. [item]: the item a request named
     * that the album doesn't have (any more), so the answer still names it.
     */
    private suspend fun streamAlbumStill(convoId: String, msgIdx: Int, mm: MsgRow, full: Boolean,
                                         budget: Int, budgetMax: Int, dispW: Int, item: Int = -1) {
        val svc = Mirror.activeService()
        if (svc == null) {
            imgMutex.withLock { sendImgNone(convoId, msgIdx, full, item) }
            return
        }
        val sw = if (dispW > 0) dispW else Schema.IMG_MAX_W
        val w = if (full) sw else albumThumbW(sw)
        val h = w * 3 / 4
        val gap = albumGapRgb(mm, full)
        val cur = if (budget > 200) budget else 200
        val maxBytes = if (full) maxOf(cur, budgetMax) else 0
        val sig = albumSig(mm)
        val twoH = if (full) 0 else previewBox(sw)[1]
        val json = if (full)
            fullCached("A|$convoId|$sig|$w|$maxBytes|$gap|${fullSettings()}") {
                albumStill(svc, convoId, msgIdx, mm, w, h, maxBytes, gap)
            }
        else
            thumbCached(thumbKey(convoId, "$ALBUM_PREFIX$sig|$w|$twoH|$gap", "", "s")) {
                albumStill(svc, convoId, msgIdx, mm, w, h, 0, gap, twoH)
            }
        if (json.isEmpty() || !json.contains("\"w\"")) {
            LogStore.w(TAG, "Album msg $msgIdx: no collage")
            imgMutex.withLock { sendImgNone(convoId, msgIdx, full, item) }
            return
        }
        val madeH = try { JSONObject(json).optInt("h", h) } catch (_: Exception) { h }
        LogStore.i(TAG, "Album msg $msgIdx: collage of ${mm.album.size} (${w}x$madeH${if (full) ", whole" else ""})")
        streamImage(convoId, msgIdx, ALBUM_PREFIX + mm.messageId, "", "", mm.incoming, mm.sender, full,
            budget, budgetMax, 1, dispW, false, rowWatchId(mm), item = item, stillJson = json)
    }

    /**
     * An album's collage as a still for the watch ({"w","h","pixels",...}; "" when
     * none of its pictures could be had): the pictures of its first items (up to
     * four, fetched side by side, each kept a while for the album's next collage),
     * laid out by AlbumCollage and encoded together as one picture. Blocking.
     * [twoMaxH] > 0: a collage of two takes its height from its pictures' shapes (two
     * single previews side by side, step 103), at most that.
     */
    private fun albumStill(svc: MessagingService, convoId: String, msgIdx: Int, mm: MsgRow,
                           w: Int, h0: Int, maxBytes: Int, gapRgb: Int, twoMaxH: Int = 0): String {
        val n = mm.album.size
        val h = h0
        val cells = AlbumCollage.cells(n, w, h, ALBUM_GAP_PX)
        // Every tile's picture at the same size (twice the collage's height, square),
        // so one fetched for a collage of two still serves the collage of three.
        val edge = 2 * h
        val tiles = arrayOfNulls<AlbumCollage.Pic>(cells.size)
        val workers = ArrayList<Thread>()
        var wanted = 0
        for (k in cells.indices) {
            val it = mm.album.getOrNull(k) ?: continue
            if (it.mediaID.isEmpty() && it.mediaDataB64.isEmpty()) continue   // a video with no poster: a plain tile
            wanted++
            val key = "$convoId|${fullSrc(it.mediaID, it.mediaDataB64)}|$edge"
            val kept = synchronized(albumTiles) { albumTiles[key] }
            if (kept != null) {
                tiles[k] = kept
                continue
            }
            workers.add(Thread {
                try {
                    val pic = AlbumCollage.decode(encodeStill(svc, convoId, msgIdx, it.mediaID, it.decryptionKeyB64,
                        it.mediaDataB64, false, edge, edge, 0, 0, 0, 0))
                    if (pic != null) synchronized(albumTiles) { albumTiles[key] = pic; tiles[k] = pic }
                    else LogStore.w(TAG, "Album msg $msgIdx: no picture for item ${k + 1}")
                } catch (e: Throwable) {
                    LogStore.w(TAG, "Album msg $msgIdx: item ${k + 1}'s picture failed: ${e.message}")
                }
            }.apply { isDaemon = true; start() })
        }
        val until = System.currentTimeMillis() + ALBUM_TILE_MS
        for (t in workers) t.join(maxOf(1L, until - System.currentTimeMillis()))
        val got = synchronized(albumTiles) { tiles.toList() }
        if (wanted > 0 && got.all { it == null }) {
            LogStore.w(TAG, "Album msg $msgIdx: none of its pictures came")
            return ""
        }
        val video = cells.indices.map { mm.album.getOrNull(it)?.videoMediaID?.isNotEmpty() == true }
        var outH = h
        if (n == 2 && twoMaxH > 0) {
            val shapes = got.filterNotNull().filter { it.w > 0 && it.h > 0 }.map { it.h.toDouble() / it.w }
            val shape = if (shapes.isEmpty()) 1.0 else shapes.average()
            val tileW = (w - ALBUM_GAP_PX) / 2
            outH = (tileW * shape).toInt().coerceIn(ALBUM_TWO_MIN_H, maxOf(ALBUM_TWO_MIN_H, twoMaxH))
        }
        val rgb = AlbumCollage.compose(got, video, n, w, outH, ALBUM_GAP_PX, gapRgb)
        val env = MediaUrls.stillFromBytes(applicationContext, Png.encode(w, outH, rgb), w, outH, maxBytes, 1)
        val d = if (env.isNotEmpty()) JSONObject(env).optJSONObject("data") else null
        return d?.toString() ?: ""
    }

    // Image<->caption split pairs: a caption row (marked link=-2) and its picture, the row
    // after it (a message's picture is added before its caption, so it comes after it
    // once the rows run newest first; a caption shown below its picture, link=-3, step
    // 100, is added first, so its picture is the row before it), linked by their numbers
    // in the thread, as the watch numbers its rows (step 99: they were numbered within a
    // frame and stored on the shared parsed rows, so the next frame's were wrong; and the
    // row before a caption was taken for its picture first). On copies: the parsed rows
    // stay as parsed. A caption with no picture beside it is a plain row of text (link -1).
    private fun withSplitLinks(flat: List<MsgRow>, from: Int, to: Int): ArrayList<MsgRow> {
        fun media(r: MsgRow) = r.mediaID.isNotEmpty() || r.mediaDataB64.isNotEmpty()
        val out = ArrayList<MsgRow>(maxOf(0, to - from))
        for (a in from until to) {
            val r = flat[a]
            out.add(when {
                r.link == -2 -> r.copy(link = if (a + 1 < flat.size && media(flat[a + 1])) a + 1 else -1)
                r.link == -3 -> r.copy(link = if (a > 0 && media(flat[a - 1])) a - 1 else -1)
                a > 0 && flat[a - 1].link == -2 && media(r) -> r.copy(link = a - 1)
                a + 1 < flat.size && flat[a + 1].link == -3 && media(r) -> r.copy(link = a + 1)
                else -> r
            })
        }
        return out
    }

    // The full flat thread, NEWEST-FIRST — absolute index 0 = newest, increasing
    // into the past. This is the Stage 3 flip: 0=newest makes lazy backfill glitch-
    // free (older history appends at the high-abs end, off-screen, never renumbering
    // anything resident or in-flight), and the new-message +1 shift rides the jump-
    // to-newest we already do. Every row-indexed request the watch sends (reply/react
    // target 60, image 40/41, full-text 52) is thread-absolute in THIS space, so it
    // indexes directly: buildTrimmed(json)[abs]. Split-pair links are not resolved
    // here (handlers use mediaID/fullText, not .link); streamMessages links them on
    // copies of its span's rows (withSplitLinks).
    private fun buildTrimmed(json: String): List<MsgRow> = buildSorted(json).reversed()

    // Per-thread parse cache. buildSorted re-parses the whole thread (incl. every
    // inline image) on each call, and the span hot path calls buildTrimmed several
    // times per span — ~87 image parses × many spans dominated the span-serve time
    // and slowed the fill enough to feed the scroll freeze. This caches the parsed,
    // newest-first thread per convo, invalidated by MessageStore.threadVersion (file
    // mtime+length) — so any store write (new message, backfill merge, reaction) is
    // reflected immediately, with no JSON hashing or collision risk. Callers on the
    // hot path use buildTrimmedCached(convoId); callers holding non-store json (rare)
    // still use buildTrimmed(json) directly.
    private val parseCache = java.util.Collections.synchronizedMap(
        HashMap<String, Pair<Long, List<MsgRow>>>())
    // Which videos are clips changed (one turned out to loop, or the sound setting or
    // the watch's speaker did): every chat's rows are built again.
    @Volatile private var parseGen = 0
    internal fun buildTrimmedCached(convoId: String): List<MsgRow> {
        val gen = Mirror.clipGen
        if (gen != parseGen) { parseCache.clear(); parseGen = gen }
        // A reply room (step 99) is held in memory (Rooms), its version its own.
        val room = Rooms.get(convoId)
        val ver = room?.version ?: MessageStore.threadVersion(this, convoId)
        parseCache[convoId]?.let { if (it.first == ver) return it.second }
        // Cache miss: do the real parse (load store json + buildSorted).reversed().
        val list = buildTrimmed(room?.json() ?: MessageStore.loadAsThreadJson(this, convoId))
        parseCache[convoId] = ver to list
        return list
    }

    // Links in the open thread's newest span that are being looked up: the thread is
    // re-pushed (as a live update) when one turns out to have media. Elsewhere the
    // answer shows the next time its span is streamed.
    private val linkWaiters = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun onLinkResolved(url: String, m: LinkMedia.Media) {
        val convo = linkWaiters.remove(url)
        if (m.kind == LinkMedia.Kind.NONE) return
        parseCache.clear()                  // rows built before the answer came
        if (convo != null && Mirror.currentOpenConvoId == convo) onLiveMessage(convo)
    }

    /** On-demand: stream ONE image (by its watch msg index) for a convo. */
    // Send the FULL untruncated text of one message (by its watch index) back to
    // the watch's enhanced view. Only called for messages the watch knows were
    // truncated, so this fires rarely and never touches the chat-list path.
    private suspend fun sendFullText(convoId: String, msgIndex: Int, rowIdx: Int = msgIndex) {
        if (!watchSendAllowed()) return
        try {
            val trimmed = buildTrimmedCached(convoId)
            if (rowIdx < 0 || rowIdx >= trimmed.size) return
            val row = trimmed[rowIdx]
            val full = row.fullText
            if (full.isEmpty()) return   // not actually truncated; nothing to send
            // The network may add to it now (X: the post's top replies).
            val detail = try {
                Mirror.activeService()?.detailText(Rooms.netConvo(convoId), row.messageId.ifEmpty { row.postId }, full) ?: full
            } catch (t: Throwable) {
                LogStore.w(TAG, "detailText failed for msg $msgIndex: ${t.message}")
                full
            }
            // The watch caps its full-text buffer; keep it bounded to a sane size.
            val payload = EmojiSub.processText(detail).take(FULL_TEXT_MAX)
            val sender = Mirror.sender(this)
            sender.sendDataToPebble(Schema.WATCH_UUID, mapOf(
                Schema.KEY_FULL_TEXT_IDX to PebbleDictionaryItem.Int32(msgIndex),
                Schema.KEY_FULL_TEXT     to PebbleDictionaryItem.Text(payload)
            ), watches = null)
            LogStore.i(TAG, "Sent full text for msg $msgIndex (${payload.length} chars)")
        } catch (e: Exception) {
            LogStore.e(TAG, "sendFullText failed for msg $msgIndex", e)
        }
    }

    suspend fun streamImageByIndex(convoId: String, msgIndex: Int, full: Boolean = false, budget: Int = 0, budgetMax: Int = 0, quality: Int = 0, dispW: Int = 0,
                                   allowAnim: Boolean = true, item: Int = -1, row: Int = msgIndex) {
        try {
            val trimmed = buildTrimmedCached(convoId)
            // Every request gets an answer: with nothing to send, an empty picture.
            // [row] is the message here; the watch hears back under its own number.
            if (row < 0 || row >= trimmed.size) {
                LogStore.w(TAG, "Image request index $msgIndex out of range (${trimmed.size})")
                imgMutex.withLock { sendImgNone(convoId, msgIndex, full, item) }
                resyncAfterMiss(convoId, full, "out of range")
                return
            }
            val mm = trimmed[row]
            if (mm.album.size >= 2) {
                // An album (step 88). The chat's picture is its collage, and so is the
                // detail view's for a watch that doesn't page through albums; one that
                // does asks for one item at a time.
                val it = if (full) albumItemRow(mm, item) else null
                if (it == null) {
                    streamAlbumStill(convoId, msgIndex, mm, full, budget, budgetMax, dispW, if (full) item else -1)
                } else if (it.videoMediaID.isNotEmpty()) {
                    // A video: no picture to send; the watch plays it (KEY_VID_REQ, this item).
                    LogStore.i(TAG, "Album msg $msgIndex: item ${item + 1} of ${mm.album.size} is a video: the watch plays it")
                    imgMutex.withLock { sendImgNone(convoId, msgIndex, true, item, video = true) }
                } else if (it.mediaID.isEmpty() && it.mediaDataB64.isEmpty()) {
                    LogStore.w(TAG, "Album msg $msgIndex: item ${item + 1} of ${mm.album.size} has no media")
                    imgMutex.withLock { sendImgNone(convoId, msgIndex, true, item) }
                } else {
                    LogStore.i(TAG, "Album msg $msgIndex: item ${item + 1} of ${mm.album.size}")
                    streamImage(convoId, msgIndex, it.mediaID, it.decryptionKeyB64,
                        it.mediaDataB64, mm.incoming, mm.sender, true, budget, budgetMax, quality, dispW,
                        false, rowWatchId(mm), item = item)
                }
                return
            }
            if (item >= 0) {
                // An item of an album the watch has at this row, which isn't one here (the
                // rows moved): no picture, named, so the watch closes it at once.
                LogStore.w(TAG, "Image request msg $msgIndex item $item: not an album here")
                imgMutex.withLock { sendImgNone(convoId, msgIndex, full, item) }
                return
            }
            if (mm.mediaID.isEmpty() && mm.mediaDataB64.isEmpty()) {
                LogStore.w(TAG, "Image request msg $msgIndex has no media")
                imgMutex.withLock { sendImgNone(convoId, msgIndex, full) }
                resyncAfterMiss(convoId, full, "no media at that row")
                return
            }
            // A GIF row's preview is streamed as a small animation when the watch
            // can play one; everything else (and older watchapps) gets a still.
            val animThumb = allowAnim && !full && Mirror.watchAnimThumbs && mm.animated
            streamImage(convoId, msgIndex, mm.mediaID, mm.decryptionKeyB64,
                mm.mediaDataB64, mm.incoming, mm.sender, full, budget, budgetMax, quality, dispW,
                animThumb, mm.messageId)
        } catch (e: CancellationException) {
            throw e   // a newer request took over: it answers the watch
        } catch (e: Exception) {
            LogStore.e(TAG, "streamImageByIndex failed", e)
            try { imgMutex.withLock { sendImgNone(convoId, msgIndex, full, item) } }
            catch (e2: CancellationException) { throw e2 }
            catch (e2: Exception) { LogStore.w(TAG, "No-picture reply failed: ${e2.message}") }
        }
    }

    // Step 108: a picture opened on the watch that finds no picture at its row means
    // the watch's rows are behind the phone's (messages it never got, e.g. sent from the
    // phone while the chat was open, shift every row). Answer "none" as always, then
    // re-push the open chat landed at the newest: KEY_LAND_ABS gets past the watch's
    // live hold, so its rows match again and the next tap asks for the right one.
    // At most once per 5 s per chat; not in reply rooms (they land on their post).
    private val lastResyncAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastGentleAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // The row here each of the watch's picture requests was served from (chat#index),
    // so a GIF the watch refused is tried again for the same message.
    private val rowServed = java.util.concurrent.ConcurrentHashMap<String, Int>()
    internal fun servedRow(convoId: String, idx: Int): Int = rowServed["$convoId#$idx"] ?: idx

    // Step 110: rows by the message's id. The watch sends the id's hash with a
    // row-indexed request (KEY_REQ_HASH); a watch whose rows are behind the phone's
    // (messages it never got) names the right message under the wrong number, so
    // the request is served for the message, answered under the watch's number.

    private fun idHashOf(m: MsgRow): Int =
        threadToken(rowWatchId(m).replace(BLOB_RS, "").replace(BLOB_US, ""))

    /** Does [m] go by [hash] on the watch? An album's id carries its item count, which may have changed since. */
    private fun hashMatches(m: MsgRow, hash: Int): Boolean {
        if (m.messageId.isEmpty()) return false
        if (idHashOf(m) == hash) return true
        val base = m.messageId.replace(BLOB_RS, "").replace(BLOB_US, "")
        if (threadToken(base) == hash) return true
        if (m.album.size >= 2) for (k in 2..ALBUM_MAX) if (threadToken("$base#a$k") == hash) return true
        return false
    }

    /**
     * Where the watch's row [idx] (its message hashing to [hash]) is here: [idx] when
     * it matches or there's no hash (an older watch), its row here when the watch's
     * rows are behind (the chat is then pushed again, gently), -1 when it isn't here.
     */
    internal fun rowByHash(convoId: String, idx: Int, hash: Int, what: String): Int {
        if (hash == 0) return idx
        val t = buildTrimmedCached(convoId)
        if (idx in t.indices && hashMatches(t[idx], hash)) return idx
        val at = t.indexOfFirst { hashMatches(it, hash) }
        if (at >= 0) {
            LogStore.i(TAG, "$what: the watch's row $idx is row $at here (its rows are behind)")
            resyncGently(convoId)
        } else {
            LogStore.w(TAG, "$what: the watch's row $idx isn't in this chat here")
        }
        return at
    }

    /** A reply's or reaction's target by its id: the row here, or -1 (sent as a plain message) when it isn't here. */
    private fun targetByHash(target: Int, hash: Int): Int {
        if (target < 0 || hash == 0) return target
        val convoId = Mirror.currentOpenConvoId ?: return target
        val at = rowByHash(convoId, target, hash, "Reply target")
        if (at < 0) LogStore.w(TAG, "Reply target not found: sent without it")
        return at
    }

    /**
     * The open chat pushed again as a new message would push it: the watch's live
     * hold keeps an open message and the reader's place, and its rows catch up. At
     * most once per 5 s per chat.
     */
    private fun resyncGently(convoId: String) {
        if (Mirror.currentOpenConvoId != convoId) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - (lastGentleAt[convoId] ?: 0L) < 5_000L) return
        lastGentleAt[convoId] = now
        onLiveMessage(convoId)
    }

    private fun resyncAfterMiss(convoId: String, full: Boolean, why: String) {
        if (!full || Mirror.currentOpenConvoId != convoId || Rooms.isRoom(convoId)) return
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastResyncAt[convoId] ?: 0L
        if (now - last < 5_000L) return
        lastResyncAt[convoId] = now
        LogStore.i(TAG, "Picture request missed ($why): resyncing the open chat")
        scope.launch {
            try {
                streamMessages(convoId,
                    MessageStore.loadAsThreadJson(this@PebbleListenerService, convoId), landAbs = 0)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { LogStore.w(TAG, "Resync failed: ${e.message}") }
        }
    }

    // ---- Stream parsed messages to watch -----------------------------------

    // Stream a span of the open thread's messages as one KEY_THREAD_BLOB frame.
    // spanStart = -1 means "newest span" (thread-open / live update / force-open):
    // the phone computes [max(0, total - n), total). spanStart >= 0 is an explicit
    // counted request (KEY_REQ_MSG_SPAN): [spanStart, spanStart + spanCount). n comes
    // from the watch (KEY_REQ_SPAN_COUNT); fresh pushes without a request default to
    // DEFAULT_MSG_SPAN_SLOTS. Rows are tagged with their thread-absolute start
    // (KEY_LIST_ABS) and the full thread total (KEY_LIST_TOTAL); frame-0-abs invariant
    // holds (blob's first record is abs spanStart). Image indices stay blob-relative.
    // A reply room's push, or the feed's after a Back out of one (step 99): landAbs, the
    // message the watch lands on (the frame's rows around it), and roomNav, the room depth
    // sent with it (a room's frames always carry theirs).
    private suspend fun streamMessages(convoId: String, json: String,
                                       openView: Int = Schema.VIEW_CHAT, pnRow: Int = -1,
                                       reactionOnly: Boolean = false,
                                       spanStart: Int = -1, spanCount: Int = 0,
                                       grew: Boolean = false,
                                       landAbs: Int = -1, roomNav: Boolean = false) {
        if (!watchSendAllowed()) return
        try {
            // Google embeds the reaction set on each message but only the live path
            // parsed it, so a thread's PRE-EXISTING reactions never populated the
            // store. Hydrate from the thread here so they render on load/reopen and
            // after a restart. Gated to Google; inert for other networks anyway
            // (they don't embed a "reactions" field) but this avoids the per-msg scan.
            if (Mirror.activeServiceId == "google") LiveRouter.hydrateGoogleReactions(json)
            else if (Mirror.activeServiceId == "tgnative") LiveRouter.hydrateTelegramReactions(json)
            else if (Mirror.activeServiceId == "discord") LiveRouter.hydrateDiscordReactions(json)
            // Flat thread, oldest..newest. The watch requests spans over this whole
            // list; only a window is resident. Row-indexed requests (40/41/52/60)
            // resolve straight against this flat set via buildTrimmed.
            // Flat thread, NEWEST-FIRST (abs 0 = newest). Parsed via the per-thread
            // cache (invalidated on store writes) so the hot span path doesn't re-parse
            // every inline image each time — the main span-serve latency cost. json is
            // always store content here, so cache-by-convoId is consistent.
            val flat = buildTrimmedCached(convoId)
            val threadTotal = flat.size
            // Resolve the span to stream: explicit counted request, or newest window.
            // For newest-span pushes (spanStart<0) n MUST match the watch's real
            // window (else the total-n base lands it mid-thread): prefer the explicit
            // count, then the cached message-window count, then a platform default.
            val n = when {
                spanCount > 0            -> spanCount
                Mirror.msgSpanCount > 0  -> Mirror.msgSpanCount
                Mirror.watchDisplayW >= 200 -> 24   // Emery default until the watch tells us (step 96: was 40)
                else                     -> 16      // Basalt default (was 20)
            }
            // NEWEST-FIRST span (Stage 3 flip): buildTrimmed is newest-first, so
            // abs index maps directly. Thread-open / live push (spanStart<0) streams
            // the newest window [0, n) — abs 0 = newest = the bottom row. A counted
            // request streams [v, v+n). No more oldest-first (total-n) clamp.
            val absStart = if (spanStart >= 0) spanStart.coerceIn(0, maxOf(0, threadTotal - 1))
                           else if (landAbs > 0) (landAbs - n / 3).coerceIn(0, maxOf(0, threadTotal - n))   // around where it lands
                           else 0                                 // newest span starts at abs 0
            val absEnd = minOf(absStart + n, threadTotal)
            // Split pairs (picture + caption) linked by their numbers in the thread (step
            // 99: they were numbered within the span). Image indices stay blob-relative.
            val trimmed = withSplitLinks(flat, absStart, absEnd)
            val count = trimmed.size
            for (mm in trimmed) {
                val u = mm.pendingLink
                if (u.isEmpty()) continue
                if (absStart == 0) linkWaiters[u] = convoId
                LinkMedia.request(applicationContext, u) { m -> onLinkResolved(u, m) }
            }
            val sender = Mirror.sender(this)
            LogStore.i(TAG, "Streaming msg span [$absStart,$absEnd) of $threadTotal (count=$count) to watch for convo $convoId")

            // Pack the whole thread into a single string:
            //   each message: incoming \x1f sender \x1f text \x1f tsSeconds
            //   messages separated by \x1e
            // Images become "[Image]" text and their indices are listed separately.
            val US = "\u001f"  // unit separator (field)
            val RS = "\u001e"  // record separator (message)

            // Pack one message row. The reply title (field 8) and quoted snippet
            // (field 9) can each be emptied under byte pressure without disturbing
            // any message index, since they're trailing fields and every message is
            // still present.
            // A watch that pages through albums (step 88) gets each one's item count.
            val albumsOk = (Mirror.watchCaps and Schema.IMGCAP_ALBUM) != 0
            // And one that opens reply rooms (step 99) each row's room, where the network
            // has them.
            val roomsOk = (Mirror.watchCaps and Schema.IMGCAP_ROOMS) != 0 && Mirror.activeService() is ReplySource
            fun packRow(mm: MsgRow, withTitle: Boolean, withSnippet: Boolean): String {
                val isImg = mm.mediaID.isNotEmpty() || mm.mediaDataB64.isNotEmpty()
                // A video's poster row is picture-shaped but stays "[Video]": the
                // watch hides that label under the player, where "[Image]" showed.
                val textForBlob = if (mm.caption.isNotEmpty() && (mm.poster || isImg)) EmojiSub.processText(mm.caption)
                                  else if (mm.poster || mm.voice) "[Video]"
                                  else if (isImg) "[Image]" else EmojiSub.processText(mm.text)
                val tsSec = if (mm.ts > 0) mm.ts / 1_000_000L else 0L
                val title = if (withTitle) mm.replyTitle.replace(RS, " ").replace(US, " ") else ""
                val snippet = if (withSnippet) mm.replySnippet.replace(RS, " ").replace(US, " ") else ""
                return StringBuilder()
                    .append(mm.incoming).append(US)
                    .append(EmojiSub.processText(mm.sender)).append(US)
                    .append(textForBlob.replace(RS, " ").replace(US, " ")).append(US)
                    .append(tsSec).append(US)
                    .append(mm.link).append(US)                                // 5th: split-pair partner index (in the thread, step 99)
                    .append(if (mm.fullText.isNotEmpty()) 1 else 0).append(US)  // 6th: truncated flag
                    .append(rowReactions(mm, convoId)).append(US)                // 7th: reaction summary ("" if none)
                    .append(title).append(US)                                  // 8th: reply title ("" if not a reply / dropped)
                    .append(snippet).append(US)                                 // 9th: quoted snippet ("" if not a reply / dropped)
                    .append(rowWatchId(mm).replace(RS, "").replace(US, ""))     // 10th: stable msgId (OP_REACT_PATCH match target)
                    .apply {
                        // (An album that's a reply quotes in words: its collage under a
                        // quoted picture would make a row taller than the screen.)
                        val pic = if (withTitle && mm.album.size < 2) mm.replyPic else ""
                        val items = if (albumsOk && mm.album.size >= 2) mm.album.size else 0
                        val room = if (roomsOk) roomField(mm) else ""
                        if (pic.isNotEmpty() || items > 0 || room.isNotEmpty()) append(US).append(pic)   // 11th: quoted picture's id hash
                        if (items > 0 || room.isNotEmpty()) append(US).append(if (items > 0) items.toString() else "")   // 12th: an album's items (step 88)
                        if (room.isNotEmpty()) append(US).append(room)              // 13th: its reply room (step 99)
                    }
                    .toString()
            }
            // DIAG (reactions): one line per FB/IG thread stream — the store's key
            // count + a few key tails, how many thread rows matched a reaction, and
            // the first row's id tail. Reveals on-device whether reactions that
            // arrived are keyed to the same message ids the thread carries.
            if (Mirror.activeServiceId == "facebook" || Mirror.activeServiceId == "instagram") {
                val d = StringBuilder("REACT-DIAG store=").append(ReactionStore.debugKeys())
                var matched = 0
                for (i in 0 until count) {
                    val row = trimmed[i]
                    val id = row.messageId
                    if (ReactionStore.hasStamped(id, convoId, row.ts)) {
                        matched++
                        d.append(" [$i ..").append(id.takeLast(12)).append('=')
                            .append(ReactionStore.summaryForStamped(id, convoId, row.ts)).append(']')
                    }
                }
                d.append(" matched=").append(matched).append('/').append(count)
                    .append(" row0=..").append(if (count > 0) trimmed[0].messageId.takeLast(12) else "-")
                    .append(" alias=").append(ReactionStore.aliasDebug(convoId, if (count > 0) trimmed[0].ts else 0L))
                LogStore.i(TAG, d.toString())
            }
            fun assemble(withTitle: Boolean, withSnippet: Boolean, n: Int): Pair<String, List<Int>> {
                val b = StringBuilder()
                val imgs = ArrayList<Int>()
                for (i in 0 until n) {
                    val mm = trimmed[i]
                    if (i > 0) b.append(RS)
                    if (mm.mediaID.isNotEmpty() || mm.mediaDataB64.isNotEmpty()) imgs.add(i)
                    b.append(packRow(mm, withTitle, withSnippet))
                }
                return b.toString() to imgs
            }
            fun bytesOf(s: String) = s.toByteArray(Charsets.UTF_8).size

            // Fit the blob to the watch's 4096 dict. First drop quoted snippets (field
            // 9), then reply titles (field 8). If STILL over — an image/long-text-dense
            // span whose base content exceeds the inbox — shrink the message COUNT until
            // it fits and send KEY_COUNT = what fit. Safe now that indices are thread-
            // absolute (KEY_LIST_ABS), not blob-relative: the watch re-requests the
            // dropped older tail via its margin logic, exactly like the convo blob.
            // Sending an oversized blob instead makes the watch reject the whole span,
            // which blanks the thread — the disappearance we're fixing.
            // Fit the blob to the watch's 4096 inbox. Reply title/snippet (fields 8/9)
            // are LOAD-BEARING — the watch needs them to render a reply header — and
            // they're rare (only actual replies carry them) and small, so dropping
            // them saves almost nothing on a normal thread while breaking every reply.
            // Since the watch now refills a short count (SHORTFALL refetch), count-trim
            // is NON-lossy (dropped messages re-requested with their reply fields
            // intact) whereas dropping reply fields IS lossy. So: keep reply fields
            // always; if over budget, shrink the message COUNT and let the watch refill.
            // Only as a last resort (a single message with reply fields still exceeding
            // the inbox) do we drop reply fields, to avoid an oversized blob the watch
            // rejects outright.
            var withTitle = true; var withSnippet = true
            var effCount = count
            var (blob, imgIndices) = assemble(withTitle, withSnippet, effCount)
            while (bytesOf(blob) > BLOB_BYTE_BUDGET && effCount > 1) {
                effCount--
                assemble(withTitle, withSnippet, effCount).let { blob = it.first; imgIndices = it.second }
            }
            // Last resort: even one message doesn't fit with its reply fields. Drop
            // snippet, then title, on that single oversized message so we send SOMETHING
            // fittable rather than an oversized blob the watch rejects (which blanks it).
            if (bytesOf(blob) > BLOB_BYTE_BUDGET) {
                withSnippet = false
                assemble(withTitle, withSnippet, effCount).let { blob = it.first; imgIndices = it.second }
            }
            if (bytesOf(blob) > BLOB_BYTE_BUDGET) {
                withTitle = false
                assemble(withTitle, withSnippet, effCount).let { blob = it.first; imgIndices = it.second }
            }
            if (!withTitle || !withSnippet)
                LogStore.w(TAG, "Thread blob: dropped reply fields on an oversized single message (${bytesOf(blob)}B)")
            if (effCount < count)
                LogStore.i(TAG, "Thread blob trimmed $count->$effCount msgs to fit inbox (${bytesOf(blob)}B); watch refills remainder (reply fields preserved)")
            if (bytesOf(blob) > BLOB_BYTE_BUDGET)
                LogStore.w(TAG, "Thread blob STILL over budget at ${bytesOf(blob)}B with 1 msg — single message exceeds inbox")

            val frame = HashMap<UInt, PebbleDictionaryItem>()
            frame[Schema.KEY_OP]          = PebbleDictionaryItem.Int32(Schema.OP_MSG)
            frame[Schema.KEY_COUNT]       = PebbleDictionaryItem.Int32(effCount)
            frame[Schema.KEY_THREAD_BLOB] = PebbleDictionaryItem.Text(blob)
            // Span addressing: the blob's first record is thread-absolute index
            // absStart; KEY_LIST_TOTAL drives the watch's whole-thread scrollbar.
            // Frame-0-abs invariant: for a counted request absStart == requested v;
            // for a newest/fresh push it's the clamped bottom. Page machinery
            // (KEY_MSG_PAGE / KEY_MSG_MORE_OLDER) is retired — the watch ignores it
            // when KEY_LIST_ABS is present, same as the convo transition.
            frame[Schema.KEY_LIST_ABS]    = PebbleDictionaryItem.Int32(absStart)
            frame[Schema.KEY_LIST_TOTAL]  = PebbleDictionaryItem.Int32(threadTotal)
            // Which chat this push is for — FNV-1a(convoId), for the watch's same-chat
            // notification branch. Rides every message push (open + force-open), since
            // streamMessages serves both. Reuses threadToken() (identical FNV-1a).
            frame[Schema.KEY_CONVO_HASH]  = PebbleDictionaryItem.Int32(threadToken(convoId))
            if (imgIndices.isNotEmpty())
                frame[Schema.KEY_IMG_INDICES] = PebbleDictionaryItem.Text(
                    imgIndices.joinToString(",") {
                        if (trimmed[it].animated) "${it}a" else if (trimmed[it].poster) "${it}v"
                        else if (trimmed[it].voice) "${it}u" else "$it"
                    })
            // Tell the watch which view to land on (chat thread vs enhanced).
            frame[Schema.KEY_OPEN_VIEW] = PebbleDictionaryItem.Int32(openView)
            // A force-open names the notification sound (step 89): the Time 2 plays it with
            // its buzz when it opens the message, and a video in it follows the watch's sound
            // switch (0 = none: no sound, the video starts silent). Sent as 0 too: the watch's
            // own default is on.
            if (openView == Schema.VIEW_ENHANCED)
                frame[Schema.KEY_PUSH_TONE] = PebbleDictionaryItem.Int32(Mirror.pushTone)
            // On a force-open (PN), tell the watch the convo's row index in the
            // just-pushed list so it sets s_open_row to match — needed for reply
            // routing fallback AND thread-cache key correctness (cache_save_current
            // keys by row; a stale row poisons the cache on PN-exit).
            if (pnRow >= 0)
                frame[Schema.KEY_PN_ROW] = PebbleDictionaryItem.Int32(pnRow)
            // Chat+page identity so the watch keys its thumbnail cache by (token, row)
            // and each page keeps its own coexisting thumbnail set.
            frame[Schema.KEY_THREAD_TOKEN] = PebbleDictionaryItem.Int32(threadToken(convoId))
            // Detail-view banner: network name, or "network\nserver#channel" for
            // drillable accounts (Discord channels). Resolved from the owning service —
            // a force-open sets activeService to the owner before streaming, and a
            // browse-open is already on the owner's list, so activeService is correct.
            Mirror.activeService()?.let {
                frame[Schema.KEY_THREAD_LABEL] = PebbleDictionaryItem.Text(it.threadLabel(Rooms.netConvo(convoId)))
            }
            // A feed (step 85: a network's timeline, Mirror.setFeed): the watch shows it
            // newest on top. On every frame of it, like the label.
            if (Mirror.isFeed(convoId)) {
                frame[Schema.KEY_THREAD_FEED] = PebbleDictionaryItem.Int32(1)
                // Its emoji actions (step 100: a like, a vote), on every frame of it too.
                val acts = feedActions(convoId)
                if (acts.isNotEmpty())
                    frame[Schema.KEY_THREAD_ACTIONS] = PebbleDictionaryItem.Int32(actionsField(acts))
            }
            // A chat Calls can call: a Call row in the watch's reply menu, on every frame.
            if ((Mirror.watchCaps and Schema.IMGCAP_CALL) != 0 &&
                Calls.target(convoId, Mirror.activeService()?.id) != null)
                frame[Schema.KEY_THREAD_CALL] = PebbleDictionaryItem.Int32(1)
            // A reply room (step 99): how deep, on its every frame (and the feed's after a
            // Back); where the watch lands.
            if (roomNav || Rooms.isRoom(convoId))
                frame[Schema.KEY_ROOM_DEPTH] = PebbleDictionaryItem.Int32(Rooms.depth())
            if (landAbs >= 0)
                frame[Schema.KEY_LAND_ABS] = PebbleDictionaryItem.Int32(landAbs)
            // Reaction/badge-only update: tell the watch to keep its scroll position
            // (repaint the pin in place) instead of jumping to the newest row.
            if (reactionOnly)
                frame[Schema.KEY_REACTION_ONLY] = PebbleDictionaryItem.Int32(1)
            // Rows a feed's background fill fetched (step 95): the watch adds them below,
            // where they belong, instead of taking this as a fresh push.
            if (grew)
                frame[Schema.KEY_THREAD_GREW] = PebbleDictionaryItem.Int32(1)
            // The answer to the watch's span request (step 96): a watch that has stopped
            // waiting for it drops it instead of taking it as a fresh push (a jump to the
            // newest).
            else if (spanStart >= 0)
                frame[Schema.KEY_SPAN_REPLY] = PebbleDictionaryItem.Int32(absStart)
            sender.sendDataToPebble(Schema.WATCH_UUID, frame, watches = null)
            LogStore.i(TAG, "Sent packed thread (${blob.length} chars, ${imgIndices.size} images, ${imgIndices.count { trimmed[it].animated }} animated, watchAnim=${Mirror.watchAnimThumbs})")

            // Previews: the watch asks for each one it shows (the rows on its screen
            // first, wherever its selector is), so none is pushed unasked here — an
            // unasked picture could land while the watch is waiting for another
            // (the detail view's full picture above all). Instead the newest rows'
            // previews are encoded ahead, so its first requests skip the download.
            if (openView != Schema.VIEW_ENHANCED && spanStart < 0)
                scope.launch { warmThumbs(convoId, 0) }
        } catch (e: Exception) {
            LogStore.e(TAG, "streamMessages failed", e)
        }
    }

    // ---- Stream image to watch ---------------------------------------------

    // A looping clip's media id carries this prefix, so the fetch paths know to get
    // the file through the network's downloadMediaToFile rather than its image path.
    internal val CLIP_PREFIX = "clip:"
    // Networks word their bare media labels differently ("[Video message]" on a
    // Telegram round video, "[video]" on Messenger, "[Voice message]" on WhatsApp) —
    // all of them mean "no caption".
    private val MEDIA_PLACEHOLDER = Regex(
        """^\[(Video( message| note| clip)?|Round video|Voice( message| note)?|Audio( message)?|Image|GIF|Photo|Picture|Media|Sticker)\]$""",
        RegexOption.IGNORE_CASE)
    private fun isMediaPlaceholder(t: String): Boolean = MEDIA_PLACEHOLDER.matches(t.trim())
    // A video's poster frame in the still fields (id or inline bytes) carries this.
    private val POSTER_PREFIX = "poster:"
    // A voice note's still and video fields (its media id behind this).
    internal val VOICE_PREFIX = "voice:"

    // A video's file (or a clip's, or a voice note's): a link's through MediaUrls, a
    // network's media through its own download.
    internal fun mediaToFile(svc: MessagingService, tagged: String, key: String, f: File): Boolean {
        val id = tagged.removePrefix(VOICE_PREFIX)
        return if (MediaUrls.isHttp(id)) MediaUrls.downloadToFile(id, f.path) > 0
               else svc.downloadMediaToFile(id, key, f.path) > 0
    }

    // A video that was found to be a short loop, by what's known of it now.
    private fun clipLoops(id: String): Boolean = Media.engine.clipLoops(applicationContext, id)

    private fun clipForWatch(
        svc: MessagingService, taggedId: String, key: String,
        maxW: Int, maxH: Int, maxBytes: Int, q: Int, fpsFloor: Int, gifBytes: Int, startRung: Int,
        clipEdge: Int = 0
    ): String {
        val id = taggedId.removePrefix(CLIP_PREFIX)
        val bytes = Clips.clipBytes(applicationContext, id) { f -> mediaToFile(svc, id, key, f) }
            ?: return ""
        return Clips.encodeForWatch(applicationContext, taggedId, bytes,
            maxW, maxH, maxBytes, q, fpsFloor, gifBytes, startRung, clipEdge)
    }

    /**
     * The unified media call: download once, and encode an animation or a still,
     * whichever the bytes turn out to need ({"kind","data"}; "" when the network
     * hasn't wired it or it failed — the caller falls back to the still path).
     * Blocking (downloads). clipEdge (0 = the detail view's) bounds the size a
     * GIF-style mp4 clip is decoded at.
     */
    internal fun fetchMediaEnv(svc: MessagingService, convoId: String, mediaID: String,
                              decryptionKeyB64: String, mediaDataB64: String,
                              maxW: Int, maxH: Int, gifMax: Int, q: Int,
                              gifFps: Int, gifBytes: Int, startRung: Int, clipEdge: Int = 0): String =
        if (mediaID.startsWith(CLIP_PREFIX))
            clipForWatch(svc, mediaID, decryptionKeyB64,
                maxW, maxH, gifMax, q, gifFps, gifBytes, startRung, clipEdge)
        else if (MediaUrls.isHttp(mediaID))
            MediaUrls.encodeForWatch(applicationContext, mediaID,
                maxW, maxH, gifMax, q,
                gifFps, gifBytes, startRung, clipEdge)
        else if (mediaID.isNotEmpty()) {
            val env = svc.downloadMediaForWatch(mediaID, decryptionKeyB64,
                maxW, maxH, gifMax, q,
                gifFps, 0, gifBytes, startRung)
            // Discord holds its picture links in memory only (see DiscordService.
            // recoverImage): on a failure, have it re-learn them and try once more.
            if (env.isEmpty() && svc is DiscordService && svc.recoverImage(convoId, mediaID))
                svc.downloadMediaForWatch(mediaID, decryptionKeyB64,
                    maxW, maxH, gifMax, q,
                    gifFps, 0, gifBytes, startRung)
            else env
        }
        else
            svc.processInlineMediaForWatch(mediaDataB64,
                maxW, maxH, gifMax, q,
                gifFps, 0, gifBytes, startRung)

    /**
     * A picture encoded as a still for the watch ({"w","h","pixels",...}, or an
     * {"error"} / "" that the caller reports): a video's poster (inline or
     * fetched), a linked picture, a clip's first frame, the network's stored
     * media, or the message's inline bytes. Blocking (downloads). msgIdx, full
     * and the budgets are only for the log.
     */
    private fun encodeStill(svc: MessagingService, convoId: String, msgIdx: Int, mediaID: String,
                            decryptionKeyB64: String, mediaDataB64: String, full: Boolean,
                            maxW: Int, maxH: Int, maxBytes: Int, q: Int,
                            curBudget: Int, maxBudget: Int): String =
        if (mediaDataB64.startsWith(POSTER_PREFIX)) {
            // A video's poster that arrived inline in the message.
            val bytes = Base64.decode(mediaDataB64.removePrefix(POSTER_PREFIX), Base64.DEFAULT)
            val env = MediaUrls.stillFromBytes(applicationContext, bytes, maxW, maxH, maxBytes, q)
            val d = if (env.isNotEmpty()) JSONObject(env).optJSONObject("data") else null
            d?.toString() ?: ""
        } else if (mediaID.startsWith(POSTER_PREFIX)) {
            // A video's poster to fetch: a url, or an id the network's image path knows.
            val pid = mediaID.removePrefix(POSTER_PREFIX)
            if (MediaUrls.isHttp(pid)) {
                val env = MediaUrls.encodeStillForWatch(applicationContext, pid, maxW, maxH, maxBytes, q)
                val d = if (env.isNotEmpty()) JSONObject(env).optJSONObject("data") else null
                d?.toString() ?: ""
            } else {
                svc.downloadImage(pid, decryptionKeyB64, maxW, maxH, maxBytes, q)
            }
        } else if (mediaID.startsWith(VOICE_PREFIX)) {
            // A voice note's card (step 86): its file, fetched once and read through for
            // its length and waveform, drawn as the chat's card (or, asked for whole, the
            // player's at rest).
            val card = Media.engine.voiceCard(applicationContext, mediaID, maxW, full) { f -> mediaToFile(svc, mediaID, decryptionKeyB64, f) }
            if (card == null) {
                LogStore.w(TAG, "No voice note for msg $msgIdx")
                ""
            } else {
                val env = MediaUrls.stillFromBytes(applicationContext, card,
                    maxW, maxH, maxBytes, q)
                val d = if (env.isNotEmpty()) JSONObject(env).optJSONObject("data") else null
                d?.toString() ?: ""
            }
        } else if (MediaUrls.isHttp(mediaID)) {
            // A linked picture (a picker's GIF, a pasted image): the bridge has
            // nothing stored under a url, so it's fetched here and encoded as a
            // still by the same shared path the animated branch above uses.
            LogStore.i(TAG, "Downloading linked image for msg $msgIdx (full=$full w=$maxW)")
            val env = MediaUrls.encodeStillForWatch(applicationContext, mediaID,
                maxW, maxH, maxBytes, q)
            val d = if (env.isNotEmpty()) JSONObject(env).optJSONObject("data") else null
            d?.toString() ?: ""
        } else if (mediaID.startsWith(CLIP_PREFIX)) {
            LogStore.i(TAG, "Still of a clip for msg $msgIdx (full=$full w=$maxW)")
            val id = mediaID.removePrefix(CLIP_PREFIX)
            val bytes = Clips.clipBytes(applicationContext, id) { f -> mediaToFile(svc, id, decryptionKeyB64, f) }
            val env = if (bytes != null)
                MediaUrls.stillFromBytes(applicationContext, bytes, maxW, maxH, maxBytes, q) else ""
            val d = if (env.isNotEmpty()) JSONObject(env).optJSONObject("data") else null
            d?.toString() ?: ""
        } else if (mediaID.isNotEmpty()) {
            LogStore.i(TAG, "Downloading image for msg $msgIdx, mediaID=$mediaID (full=$full w=$maxW cur=$curBudget max=$maxBudget)")
            val r = svc.downloadImage(
                mediaID, decryptionKeyB64, maxW, maxH, maxBytes, q)
            // Discord holds its picture links in memory only (see DiscordService.
            // recoverImage): on a failure, have it re-learn them and try once more.
            if (!r.contains("\"w\"") && svc is DiscordService && svc.recoverImage(convoId, mediaID))
                svc.downloadImage(mediaID, decryptionKeyB64, maxW, maxH, maxBytes, q)
            else r
        } else {
            LogStore.i(TAG, "No mediaID; using INLINE image for msg $msgIdx (${mediaDataB64.length} b64 chars)")
            svc.processInlineImage(
                mediaDataB64, maxW, maxH, maxBytes, q)
        }

    // ---- Sharp patch for a zoomed picture (Time 2) ----------------------------
    // Holding a finger on a zoomed picture asks for the part on screen at the zoomed
    // resolution. The picture is encoded again at that width by the same still
    // encoder (the full view's colour settings), and the screen's worth is cropped
    // out of it, re-packed at the fewest bits its colours need, and row-compressed
    // when that's smaller. When the watch's room is short it goes at half, then a
    // quarter, of that resolution. Other media are left to
    // Media.engine (scale 0: the zoom ended); a message that can't be found gets "none", and the watch
    // keeps the blocky picture.
    //
    // KEY_IZ_REQ (watch->phone, little-endian): [u16 seq][i16 msg][u32 msg-id hash]
    //   [i32 x 16.16][i32 y 16.16][u16 scale x256][u16 pic w][u16 pic h]
    //   [u16 view w][u16 view h][i32 room][u8 album item, 0xFF none (step 88; a watch
    //   from before sends 30 bytes)]. x/y is the picture point at the screen's
    //   top-left, in the watch picture's pixels; scale is the zoom.
    // KEY_IZ_PATCH (phone->watch): [u8 kind][u8 0][u16 seq] then, by kind:
    //   1 start [u8 depth][u8 comp][u16 restarts][u16 w][u16 h][u32 stream bytes]
    //           [i32 x 16.16][i32 y 16.16][u32 x scale 16.16][u32 y scale 16.16]
    //           [palette, 1 << depth bytes]
    //   x/y is the patch's top-left on the watch picture; a scale is patch px per
    //   watch-picture px on that axis.
    //   2 data  [u32 offset][bytes]     3 end     0 none
    // The stream is the restart table then the compressed rows, or the raw rows.
    @Volatile private var patchJob: kotlinx.coroutines.Job? = null
    internal val PATCH_MAX_W = 1600            // never encode wider than this
    private val PATCH_ENCODE_BYTES = 8_000_000 // the big encode's budget: never shrinks it
    private val PATCH_ROW_MAX = 108           // the watch's decode row (COMP_SCRATCH_MAX)
    private val PATCH_LEN_MAX = 60000         // the watch's limit on one patch

    /** The last big encode, so another hold at the same zoom only crops. */
    private class BigEncode(val key: String, val w: Int, val h: Int, val depth: Int,
                            val stride: Int, val pixels: ByteArray, val palette: ByteArray)
    @Volatile private var bigEncode: BigEncode? = null

    internal suspend fun sendPatchNone(seq: Int) {
        sendImgFrame(Mirror.sender(this), mapOf(Schema.KEY_IZ_PATCH to PebbleDictionaryItem.Bytes(
            byteArrayOf(0, 0, (seq and 0xFF).toByte(), ((seq shr 8) and 0xFF).toByte()))))
    }

    private suspend fun streamPatch(convoId: String?, q: ByteArray) {
        if (q.size < 30) return
        val bb = java.nio.ByteBuffer.wrap(q).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val seq = bb.getShort(0).toInt() and 0xFFFF
        val idx = bb.getShort(2).toInt()
        val hash = bb.getInt(4)
        val x0 = bb.getInt(8)
        val y0 = bb.getInt(12)
        val s = bb.getShort(16).toInt() and 0xFFFF
        val pw = bb.getShort(18).toInt() and 0xFFFF
        val ph = bb.getShort(20).toInt() and 0xFFFF
        val vw = bb.getShort(22).toInt() and 0xFFFF
        val vh = bb.getShort(24).toInt() and 0xFFFF
        val room = bb.getInt(26)
        if (s == 0) {
            Media.engine.zoomEnded(this)
            return
        }
        try {
            val svc = Mirror.activeService()
            if (convoId == null || svc == null || pw <= 0 || ph <= 0 || vw <= 0 || vh <= 0 || s <= 256) {
                LogStore.w(TAG, "PATCH $seq: nothing to crop (convo=${convoId != null} s=$s pic=${pw}x$ph)")
                sendPatchNone(seq)
                return
            }
            // The message by its id (rows shift as messages arrive), else by row. An
            // album's (step 88) is the item on screen (q[30]; 0xFF: none), its collage
            // not being zoomed.
            val trimmed = buildTrimmedCached(convoId)
            fun idHash(m: MsgRow) = threadToken(rowWatchId(m).replace(BLOB_RS, "").replace(BLOB_US, ""))
            val row = trimmed.getOrNull(idx)?.takeIf { hash == 0 || idHash(it) == hash }
                ?: trimmed.firstOrNull { hash != 0 && it.messageId.isNotEmpty() && idHash(it) == hash }
            val isAlbum = row != null && row.album.size >= 2
            val item = if (isAlbum && q.size > 30) (q[30].toInt() and 0xFF).let { if (it == 0xFF) -1 else it } else -1
            val mm = if (isAlbum) albumItemRow(row, item) else row
            if (mm != null && Media.engine.zoom(this, svc, convoId, idx, mm, seq, x0, y0, s, pw, ph, vw, vh, room, item)) return
            if (mm == null || (mm.mediaID.isEmpty() && mm.mediaDataB64.isEmpty())
                || mm.animated || mm.poster || mm.videoMediaID.isNotEmpty()) {
                LogStore.i(TAG, "PATCH $seq: msg $idx is not a still (found=${mm != null})")
                sendPatchNone(seq)
                return
            }
            // The picture again, at the zoomed width.
            val bigW = minOf(PATCH_MAX_W, (pw * s + 128) / 256)
            val key = "$convoId|${mm.messageId}|${mm.mediaID.hashCode()}:${mm.mediaDataB64.length}|$bigW"
            val big = bigEncode?.takeIf { it.key == key } ?: run {
                val t0 = System.currentTimeMillis()
                val json = encodeStill(svc, convoId, idx, mm.mediaID, mm.decryptionKeyB64, mm.mediaDataB64,
                    true, bigW, 0, PATCH_ENCODE_BYTES, 1, 0, 0)
                val r = if (json.trimStart().startsWith("{")) JSONObject(json) else null
                if (r == null || !r.has("w") || r.optInt("fmt", 0) != 1) {
                    LogStore.w(TAG, "PATCH $seq: encode failed (${r?.optString("error", "") ?: json.take(80)})")
                    null
                } else {
                    val w = r.getInt("w"); val h = r.getInt("h")
                    val px = Base64.decode(r.getString("pixels"), Base64.DEFAULT)
                    val pal = Base64.decode(r.optString("palette", ""), Base64.DEFAULT)
                    val d = r.optInt("depth", 4)
                    if (w <= 0 || h <= 0 || px.size % h != 0 || (d != 1 && d != 2 && d != 4)) {
                        LogStore.w(TAG, "PATCH $seq: odd encode ${w}x$h d$d ${px.size}B")
                        null
                    } else {
                        LogStore.i(TAG, "PATCH $seq: encoded ${w}x$h d$d in ${System.currentTimeMillis() - t0}ms")
                        BigEncode(key, w, h, d, px.size / h, px, pal).also { bigEncode = it }
                    }
                }
            }
            if (big == null) { sendPatchNone(seq); return }

            // The screen's worth of it. Watch-picture px -> big px is kx across, ky
            // down (both encodes cover the whole source, each rounded to whole px).
            val kx = big.w.toDouble() / pw
            val ky = big.h.toDouble() / ph
            val fx = x0 / 65536.0
            val fy = y0 / 65536.0
            val viewW = vw * 256.0 / s
            val viewH = vh * 256.0 / s
            val cx = Math.floor(maxOf(0.0, fx) * kx).toInt().coerceIn(0, big.w - 1)
            val cy = Math.floor(maxOf(0.0, fy) * ky).toInt().coerceIn(0, big.h - 1)
            val cx1 = Math.ceil(minOf(pw.toDouble(), fx + viewW) * kx).toInt().coerceIn(cx + 1, big.w)
            val cy1 = Math.ceil(minOf(ph.toDouble(), fy + viewH) * ky).toInt().coerceIn(cy + 1, big.h)
            val cw = minOf(cx1 - cx, vw + 2)
            val ch = minOf(cy1 - cy, vh + 2)
            val ppb = 8 / big.depth
            val bmask = (1 shl big.depth) - 1
            val crop = ByteArray(cw * ch)
            for (y in 0 until ch) {
                val rowOff = (cy + y) * big.stride
                for (x in 0 until cw) {
                    val sx = cx + x
                    val b = big.pixels[rowOff + sx / ppb].toInt() and 0xFF
                    crop[y * cw + x] = ((b shr (8 - big.depth * ((sx % ppb) + 1))) and bmask).toByte()
                }
            }
            val prx = Math.round(cx / kx * 65536.0).toInt()
            val pry = Math.round(cy / ky * 65536.0).toInt()

            // Full resolution if it fits the watch's room, else half, else a quarter.
            for (lv in 0..2) {
                val f = 1 shl lv
                val w = (cw + f - 1) / f
                val h = (ch + f - 1) / f
                val skx = Math.round(kx * 65536.0 / f).toInt()
                val sky = Math.round(ky * 65536.0 / f).toInt()
                // Not worth sending once it is no sharper than the picture it covers.
                if (skx <= 69632 || sky <= 69632) break
                // Re-pack at the fewest bits the colours used need.
                val used = IntArray(16) { -1 }
                var nUsed = 0
                for (y in 0 until h) for (x in 0 until w) {
                    val c = crop[(y * f) * cw + x * f].toInt()
                    if (used[c] < 0) used[c] = nUsed++
                }
                val nd = if (nUsed <= 2) 1 else if (nUsed <= 4) 2 else 4
                val pal = ByteArray(1 shl nd)
                for (c in 0 until 16) if (used[c] >= 0 && c < big.palette.size) pal[used[c]] = big.palette[c]
                val nppb = 8 / nd
                val rowBytes = (w + nppb - 1) / nppb
                val tight = ByteArray(rowBytes * h)
                for (y in 0 until h) for (x in 0 until w) {
                    val v = used[crop[(y * f) * cw + x * f].toInt()]
                    val i = y * rowBytes + x / nppb
                    tight[i] = (tight[i].toInt() or (v shl (8 - nd * ((x % nppb) + 1)))).toByte()
                }
                val rc = if (rowBytes <= PATCH_ROW_MAX) rowCompress(tight, rowBytes, h) else null
                val comp = rc != null && rc.index.size + rc.compressed.size < tight.size
                val stream = if (comp) rc!!.index + rc.compressed else tight
                val rn = if (comp) rc!!.index.size / 2 else 0
                val need = 16 + stream.size + 4
                LogStore.i(TAG, "PATCH $seq: crop ${cw}x$ch of ${big.w}x${big.h} lv$lv -> ${w}x$h d$nd " +
                    "${if (comp) "comp" else "raw"} ${stream.size}B, room $room")
                if (need > room || stream.size >= PATCH_LEN_MAX) continue
                val hdr = java.nio.ByteBuffer.allocate(32 + pal.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                hdr.put(1.toByte()).put(0.toByte()).putShort(seq.toShort())
                hdr.put(nd.toByte()).put((if (comp) 1 else 0).toByte()).putShort(rn.toShort())
                hdr.putShort(w.toShort()).putShort(h.toShort()).putInt(stream.size)
                hdr.putInt(prx).putInt(pry).putInt(skx).putInt(sky)
                hdr.put(pal)
                imgMutex.withLock {
                    val sender = Mirror.sender(this)
                    sendImgFrame(sender, mapOf(Schema.KEY_IZ_PATCH to PebbleDictionaryItem.Bytes(hdr.array())))
                    val chunk = Schema.IMG_CHUNK_SIZE - 8
                    var off = 0
                    while (off < stream.size) {
                        val end = minOf(off + chunk, stream.size)
                        val fr = java.nio.ByteBuffer.allocate(8 + end - off).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        fr.put(2.toByte()).put(0.toByte()).putShort(seq.toShort()).putInt(off).put(stream, off, end - off)
                        sendImgFrame(sender, mapOf(Schema.KEY_IZ_PATCH to PebbleDictionaryItem.Bytes(fr.array())))
                        off = end
                    }
                    sendImgFrame(sender, mapOf(Schema.KEY_IZ_PATCH to PebbleDictionaryItem.Bytes(
                        byteArrayOf(3, 0, (seq and 0xFF).toByte(), ((seq shr 8) and 0xFF).toByte()))))
                }
                LogStore.i(TAG, "PATCH $seq: sent")
                return
            }
            LogStore.w(TAG, "PATCH $seq: no size fits room $room")
            sendPatchNone(seq)
        } catch (e: CancellationException) {
            throw e   // a newer hold took over: it answers the watch
        } catch (e: Exception) {
            LogStore.e(TAG, "PATCH $seq failed", e)
            try { sendPatchNone(seq) } catch (e2: CancellationException) { throw e2 } catch (e3: Exception) {}
        }
    }

    /**
     * Tell the watch there is no picture for this request: an empty START (0x0)
     * and its END. The watch closes the transfer at once rather than waiting out
     * its watchdog, and rests that preview a while before asking again. (A watch
     * build from before this reads it as a transfer that delivered nothing: the
     * same outcome.) Call with imgMutex held, so it can't interleave with a stream.
     * An album's item (step 88) is named; [video]: that item is a video, for the
     * watch to play.
     */
    private suspend fun sendImgNone(convoId: String?, msgIdx: Int, full: Boolean, item: Int = -1, video: Boolean = false) {
        val sender = Mirror.sender(this)
        sendFrames(sender, listOf(mapOf(
            Schema.KEY_IMG_START    to PebbleDictionaryItem.Int32(msgIdx),
            Schema.KEY_IDX          to PebbleDictionaryItem.Int32(0),
            Schema.KEY_COUNT        to PebbleDictionaryItem.Int32(0),
            Schema.KEY_TYPE         to PebbleDictionaryItem.Int32(0),
            Schema.KEY_REQ_IMG_FULL to PebbleDictionaryItem.Int32(if (full) 1 else 0),
            Schema.KEY_THREAD_TOKEN to PebbleDictionaryItem.Int32(if (convoId != null) threadToken(convoId) else 0)
        ) + albumItemKeys(item, video), mapOf(Schema.KEY_IMG_END to PebbleDictionaryItem.Int32(msgIdx))))
        if (video) LogStore.i(TAG, "Album msg $msgIdx: told the watch to play item ${item + 1}")
        else LogStore.w(TAG, "No picture for msg $msgIdx (full=$full${if (item >= 0) ", item $item" else ""}): told the watch")
    }

    /** An album item's keys on a START (step 88): which item, and whether it's a video. */
    internal fun albumItemKeys(item: Int, video: Boolean = false): Map<UInt, PebbleDictionaryItem> =
        if (item < 0) emptyMap()
        else if (video) mapOf(Schema.KEY_IMG_ITEM to PebbleDictionaryItem.Int32(item),
                              Schema.KEY_ALBUM_VIDEO to PebbleDictionaryItem.Int32(1))
        else mapOf(Schema.KEY_IMG_ITEM to PebbleDictionaryItem.Int32(item))

    /**
     * The picture's own message on its START, as its stable id (sanitized exactly
     * as the thread blob's field 10, so the watch's FNV-1a matches the row's): the
     * watch stores a preview under it, so it lands on its message even if the
     * phone's rows and the watch's disagree for a moment (a new message arriving
     * renumbers them). The key is the one reaction patches name a message by.
     */
    internal fun startMsgId(msgId: String): Map<UInt, PebbleDictionaryItem> {
        val id = msgId.replace(BLOB_RS, "").replace(BLOB_US, "")
        return if (id.isEmpty()) emptyMap()
               else mapOf(Schema.KEY_REACT_MSG_ID to PebbleDictionaryItem.Text(id))
    }

    /** Close a transfer whose START went out but which failed part-way. */
    private suspend fun sendImgEnd(msgIdx: Int) {
        sendImgFrame(Mirror.sender(this), mapOf(Schema.KEY_IMG_END to PebbleDictionaryItem.Int32(msgIdx)))
    }

    // ---- Chat-row preview cache (see thumbCache) ------------------------------

    /** The cache key of a preview: chat, media (or the inline bytes), kind, and the colour settings it was encoded under. */
    private fun thumbKey(convoId: String, mediaID: String, inline: String, kind: String): String {
        val src = if (mediaID.isNotEmpty()) mediaID else "i${inline.length}:${inline.hashCode()}"
        return "$convoId|$src|$kind|${Mirror.imgThumbCap}|${Mirror.imgThumbCoverage}"
    }

    private fun thumbHave(key: String): Boolean =
        synchronized(thumbLock) { thumbCache.containsKey(key) || thumbBuilding.containsKey(key) }

    /** A usable encode: a still ({"w"...}) or a unified result ({"kind"...}). Errors aren't kept. */
    private fun thumbGood(r: String): Boolean =
        r.trimStart().startsWith("{") &&
            try { JSONObject(r).let { it.has("w") || it.has("kind") } } catch (e: Exception) { false }

    /**
     * A preview's encode, made once: the kept one, the one already being made (by
     * a warm-up or an earlier request), or a new one. `make` is blocking and runs
     * on the IO scope. The encode keeps its own result (only a usable one) and
     * drops itself from the in-progress table when it ends, whether or not anyone
     * is still waiting for it. A wait gives up after THUMB_ENCODE_MS: an encode
     * that never returns can't hold that preview hostage, the next ask starts anew.
     */
    private suspend fun thumbCached(key: String, make: () -> String): String {
        val d = synchronized(thumbLock) {
            thumbCache[key]?.let { return it }
            thumbBuilding[key] ?: run {
                val self = arrayOfNulls<Deferred<String>>(1)
                val nd = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        val r = make()
                        if (thumbGood(r)) synchronized(thumbLock) { thumbCache[key] = r }
                        r
                    } finally {
                        synchronized(thumbLock) { if (thumbBuilding[key] === self[0]) thumbBuilding.remove(key) }
                    }
                }
                self[0] = nd
                thumbBuilding[key] = nd
                nd.start()
                nd
            }
        }
        return withTimeoutOrNull(THUMB_ENCODE_MS) { d.await() } ?: run {
            synchronized(thumbLock) { if (thumbBuilding[key] === d) thumbBuilding.remove(key) }
            LogStore.w(TAG, "Preview encode gave up after ${THUMB_ENCODE_MS / 1000}s")
            ""
        }
    }

    /**
     * Encode the previews of the image rows around `around` (thread-absolute,
     * newest-first) before the watch asks for them: nearest first, a couple at a
     * time, into the preview cache. Stills only: an animated preview or a clip's
     * still needs the whole clip downloaded, which waits until the watch asks.
     */
    private fun warmThumbs(convoId: String, around: Int) {
        val svc = Mirror.activeService() ?: return
        val flat = try { buildTrimmedCached(convoId) } catch (e: Exception) { return }
        val order = ArrayList<Int>(THUMB_WARM_ROWS * 2 + 1)
        order.add(around)
        for (d in 1..THUMB_WARM_ROWS) { order.add(around + d); order.add(around - d) }
        for (i in order) {
            if (i < 0 || i >= flat.size) continue
            val mm = flat[i]
            if (mm.mediaID.isEmpty() && mm.mediaDataB64.isEmpty()) continue
            if (mm.mediaID.startsWith(CLIP_PREFIX)) continue
            if (Mirror.watchAnimThumbs && mm.animated) continue
            // An album's preview is its collage (step 88), made just as its request makes it.
            val album = mm.album.size >= 2
            val wsw = if (Mirror.watchDisplayW > 0) Mirror.watchDisplayW else Schema.IMG_MAX_W
            val aw = albumThumbW(wsw)
            val box = previewBox(wsw)
            val gap = albumGapRgb(mm, false)
            val key = if (album) thumbKey(convoId, "$ALBUM_PREFIX${albumSig(mm)}|$aw|${box[1]}|$gap", "", "s")
                      else thumbKey(convoId, mm.mediaID, mm.mediaDataB64, "s${box[0]}")
            if (thumbHave(key)) continue
            val job = scope.launch {
                thumbWarmGate.withPermit {
                    try {
                        thumbCached(key) {
                            if (album) albumStill(svc, convoId, i, mm, aw, aw * 3 / 4, 0, gap, box[1])
                            else encodeStill(svc, convoId, i, mm.mediaID, mm.decryptionKeyB64, mm.mediaDataB64,
                                false, box[0], box[1], 0, 1, 200, 200)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LogStore.w(TAG, "Preview warm-up failed for msg $i: ${e.message}")
                    }
                }
            }
            thumbWarmJobs.add(job)
            job.invokeOnCompletion { thumbWarmJobs.remove(job) }
        }
    }

    /** Stop the preview warm-ups not yet running (a full picture wants the network). */
    private fun cancelThumbWarm() {
        val jobs = synchronized(thumbWarmJobs) { ArrayList(thumbWarmJobs).also { thumbWarmJobs.clear() } }
        jobs.forEach { it.cancel() }
    }

    // item: an album's item (step 88), named on the START. stillJson: a still already
    // encoded (an album's collage): it's sent as it is, nothing fetched.
    private suspend fun streamImage(
        convoId: String,
        msgIdx: Int, mediaID: String, decryptionKeyB64: String,
        mediaDataB64: String, incoming: Int, senderName: String,
        full: Boolean = false, budget: Int = 0, budgetMax: Int = 0, quality: Int = 0,
        dispW: Int = 0, animThumb: Boolean = false, msgId: String = "",
        item: Int = -1, stillJson: String = ""
    ) = imgMutex.withLock {
        if (!watchSendAllowed()) return@withLock
        // Serialize ALL image transfers: only one image streams to the watch at
        // a time. Two concurrent streams interleave their chunks on the wire,
        // producing overlapping IMG_STARTs that corrupt the watch's single
        // receive bitmap (recv inflated, garbled/black image).
        // Whether this transfer's START has gone out: a failure after it closes the
        // transfer with its END; one before it answers with an empty picture. Either
        // way the watch hears back at once instead of waiting on its watchdog.
        var started = false
        try {
            val svc = Mirror.activeService() ?: run {
                sendImgNone(convoId, msgIdx, full, item)
                return@withLock
            }
            // caller passes quality=1; the bridge encodes 4bpp palettized.
            val q = 1
            // Enhanced (full): width cap + heap byte-budget (height unlimited).
            // Chat (small): classic small box, no budget.
            // Enhanced width is the watch's REPORTED display width (144 Basalt /
            // 200 Emery). Absent/0 (older watchapp) falls back to IMG_MAX_W (144),
            // so nothing changes for watches that don't report it. Height still
            // scales with the source aspect ratio; only the width target moves.
            val fullW = if (dispW > 0) dispW else Schema.IMG_MAX_W
            val box = previewBox(dispW)
            val maxW = if (full) fullW else box[0]
            val maxH = if (full) 0 else box[1]
            val thumbAnimBytes = box[0] * box[1] / 2
            // Budgets come straight from the watch (already heap-floored there).
            // Do NOT inflate them — the watch may have intentionally kept them
            // small to avoid a crash. Just guard against a zero/garbage value.
            val curBudget = if (budget > 200) budget else 200
            val maxBudget = if (budgetMax > curBudget) budgetMax else curBudget
            // Scale at the LARGER (cache-freed) budget. If the result fits within
            // the current budget, no wipe is needed; if it's bigger, the watch
            // must free its cache (wipe=1) to make room.
            val maxBytes = if (full) maxBudget else 0
            // Holds a still that the unified media call already encoded, so the
            // normal path below can use it without re-downloading.
            var preEncoded = ""
            // ANIMATION ROUTING. The media has to be downloaded before anyone can
            // tell a GIF from a still, so the bridge's unified entry point
            // downloads ONCE and returns whichever encoding the bytes turned out
            // to need:
            //     {"kind":"gif","data":{...}}  or  {"kind":"image","data":{...}}
            // A single-frame GIF and a mislabelled JPEG both come back as "image",
            // so nothing downstream has to second-guess the container. FULL
            // requests and animated chat-row previews take this path.
            // Any service implementing the unified media path takes it. The default
            // implementation returns "", so a bridge that hasn't been wired yet
            // falls straight through to the still-image path below — networks light
            // up as their bridges gain the method, rather than this having to know
            // which ones are ready.
            val isPoster = mediaID.startsWith(POSTER_PREFIX) || mediaDataB64.startsWith(POSTER_PREFIX) ||
                mediaID.startsWith(VOICE_PREFIX)
            if (stillJson.isEmpty() && (full || animThumb) && !isPoster) {
                // The animation gets the watch's heap budget. That budget is only an
                // ESTIMATE — it is sampled when the watch asks, while the allocation
                // happens later, so it can be wrong by a few KB either way. Rather
                // than padding it with a guessed reserve, the watch is authoritative:
                // if it cannot allocate, it aborts with its REAL free heap and this
                // encode is redone against that number (see the KEY_GIF_ABORT
                // handler). One round trip, no magic constants.
                // An animated PREVIEW instead gets one thumbnail slot and a fixed
                // 2fps (the Go encoder switches to its thumbnail ladder at fps <= 2).
                val gifMax = if (animThumb) thumbAnimBytes else maxBytes
                val gifFps = if (animThumb) THUMB_ANIM_FPS else GIF_FPS_FLOOR
                val gifBytes = if (animThumb) thumbAnimBytes
                               else if (GIF_WIRE_BUDGET in 1 until maxBytes) GIF_WIRE_BUDGET else maxBytes
                // Where the ladder starts. Zero normally; higher after the watch has
                // refused this animation, so each attempt is strictly smaller than
                // the one it rejected.
                val startRung = Mirror.gifStartRung(convoId, gifRungIdx(msgIdx, animThumb, item))
                // Remember enough to redo this encode if the watch refuses.
                val attempt = MediaRequest(convoId, msgIdx, mediaID, decryptionKeyB64,
                    mediaDataB64, maxW, maxH, maxBytes, q, dispW, animThumb, item)
                Media.engine.noteRequest(attempt)
                val envJson = try {
                    // A preview's encode is kept (per ladder rung): asking again
                    // costs only the stream.
                    if (animThumb) thumbCached(thumbKey(convoId, mediaID, mediaDataB64, "a${box[0]}.$startRung")) {
                        fetchMediaEnv(svc, convoId, mediaID, decryptionKeyB64, mediaDataB64,
                            maxW, maxH, gifMax, q, gifFps, gifBytes, startRung)
                    } else fullCached(fullEnvKey(convoId, mediaID, mediaDataB64, maxW, gifMax, gifFps, gifBytes, startRung)) {
                        fetchMediaEnv(svc, convoId, mediaID, decryptionKeyB64, mediaDataB64,
                            maxW, maxH, gifMax, q, gifFps, gifBytes, startRung)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    LogStore.w(TAG, "Unified media fetch failed for msg $msgIdx (${e.message}) — falling back to still")
                    ""
                }
                if (envJson.isNotEmpty() && envJson.trimStart().startsWith("{")) {
                    val env = JSONObject(envJson)
                    val kind = env.optString("kind", "image")
                    val data = env.optJSONObject("data")
                    if (kind != "image" && data != null) {
                        if (Media.engine.stream(this, attempt, data, gifMax, msgId)) return@withLock
                    } else if (data != null && !animThumb) {
                        // Not animated. The SAME call already encoded it as a still,
                        // so hand that result to the normal path instead of paying
                        // for a second download. (Not for a mislabelled PREVIEW: that
                        // encode used the preview's byte budget, which the still
                        // encoder reads as the FULL view's settings — the normal
                        // thumbnail path below re-encodes it with the thumbnail's.)
                        preEncoded = data.toString()
                    }
                }
            }
            // A preview's still is kept too (and may already be made, by a warm-up).
            val resultJson = if (stillJson.isNotEmpty()) {
                stillJson
            } else if (preEncoded.isNotEmpty()) {
                preEncoded
            } else if (!full) {
                thumbCached(thumbKey(convoId, mediaID, mediaDataB64, "s${box[0]}")) {
                    encodeStill(svc, convoId, msgIdx, mediaID, decryptionKeyB64, mediaDataB64,
                        false, maxW, maxH, maxBytes, q, curBudget, maxBudget)
                }
            } else {
                fullCached(fullStillKey(convoId, mediaID, mediaDataB64, maxW, maxBytes)) {
                    encodeStill(svc, convoId, msgIdx, mediaID, decryptionKeyB64, mediaDataB64,
                        true, maxW, maxH, maxBytes, q, curBudget, maxBudget)
                }
            }
            // DIAGNOSTIC: capture the raw scaler return BEFORE parsing, so an
            // empty/null/non-JSON return is visible too (JSONObject() would
            // otherwise throw before we could log what came back).
            if (resultJson.isEmpty() || !resultJson.trimStart().startsWith("{")) {
                LogStore.e(TAG, "IMG SCALER returned non-JSON for msg $msgIdx — len=${resultJson.length} raw='${resultJson.take(200)}'")
            }
            val result = JSONObject(resultJson)
            // DIAGNOSTIC: the scaler is expected to return {w,h,pixels,fmt,palette}.
            // When it instead returns an error object (or empty), the getInt("w")
            // below throws a cryptic "No value for w". Surface what the bridge
            // actually returned, and the error field if present, so the real cause
            // (download/decode/decrypt failure vs. account issue) is visible in the
            // exported log. Truncated so base64 pixel data can't flood the log.
            if (!result.has("w")) {
                val errField = result.optString("error", "")
                val preview = if (resultJson.length > 300) resultJson.substring(0, 300) + "…(${resultJson.length} chars)" else resultJson
                LogStore.e(TAG, "IMG SCALER returned no 'w' for msg $msgIdx — error='${errField}' raw=$preview")
            }
            val w = result.getInt("w"); val h = result.getInt("h")
            val pixels = Base64.decode(result.getString("pixels"), Base64.DEFAULT)
            val imgFormat = result.optInt("fmt", 0)   // 0=8bpp, 1=palettized
            // Packed bit depth for palettized images: 1/2/4. Absent => 4 (legacy).
            // Only meaningful (and only sent) when fmt==1; the 8bpp path reports 8
            // here but goes down the watch's non-palettized route, so we never tag
            // a palettized frame with depth 8.
            val imgDepth = result.optInt("depth", 4)
            val paletteB64 = result.optString("palette", "")
            val palette = if (paletteB64.isNotEmpty())
                Base64.decode(paletteB64, Base64.DEFAULT) else ByteArray(0)
            // NOTE: the wipe decision moves BELOW, after compression — for a
            // compressed image the resident cost on the watch is the COMPRESSED
            // size, not pixels.size, so comparing raw size here would trigger
            // needless cache wipes (and mislabel the log). We log the raw size now
            // and decide wipe against the real resident size once it's known.
            LogStore.i(TAG, "Image for msg $msgIdx: ${w}x${h}, ${pixels.size} raw bytes (fmt=$imgFormat)")
            val sender = Mirror.sender(this)

            // ---- Band the FULL image -------------------------------------------
            // Horizontal strips, each its own transfer segment AND its own watch-
            // side allocation (~1/N of the image): a fragmented watch heap stops
            // killing whole images, and each completed band paints immediately so
            // the user watches the picture build instead of staring at the icon.
            // Band count adapts to height (~64 rows/band, 1..8): a full-screen
            // photo lands on 3-4 bands; tall height-free screenshots get more, so
            // no single band ever balloons back into a giant allocation.
            val srcRow = if (h > 0 && pixels.size % h == 0) pixels.size / h else 0
            val banded = full && imgFormat == 1 && h >= 64 && srcRow > 0
            val fold = (Mirror.watchCaps and Schema.IMGCAP_FOLD) != 0
            // A watch that takes folded frames gets bands of whole messages: each band
            // is one chunk (header, data and END in one message) or two, the fewest
            // that keep to its 8 bands. Otherwise ~64 rows a band, as before.
            val nbands = if (!banded) 1
                else if (fold) {
                    var m = 1
                    var rows = maxOf(1, Schema.IMG_CHUNK_SIZE / srcRow)
                    while ((h + rows - 1) / rows > 8) { m++; rows = maxOf(1, m * Schema.IMG_CHUNK_SIZE / srcRow) }
                    (h + rows - 1) / rows
                } else minOf(8, maxOf(1, (h + 63) / 64))
            val bandRows = IntArray(nbands)
            if (banded) {
                val baseRows = h / nbands
                val rem = h - baseRows * nbands
                for (k in 0 until nbands) bandRows[k] = baseRows + if (k < rem) 1 else 0
            }

            // Per-band compression (whole-image decision, per-band blobs). Each
            // band compresses its own row range with its own restart index — the
            // encoder's row-0 has no previous row, so band boundaries are natural
            // decode starts. Ship compressed only if the SUM of band blobs+indices
            // beats the raw stream (raw wins on dithered photos; comp wins on flat
            // graphics) — never worse than off.
            var encoding = 0
            var bandComp: Array<RowComp>? = null
            var sendStream = pixels          // legacy single-segment stream (small / short-full)
            var restartIndex: ByteArray? = null
            if (Mirror.imgCompress && full && imgFormat == 1) {
                val tight = tightRows(pixels, w, imgDepth)   // strip word-align padding
                if (tight != null) {
                    if (banded) {
                        val tRow = tight.second
                        var y0 = 0
                        val comps = Array(nbands) { k ->
                            val rows = bandRows[k]
                            val slice = tight.first.copyOfRange(y0 * tRow, (y0 + rows) * tRow)
                            y0 += rows
                            rowCompress(slice, tRow, rows)
                        }
                        val compTotal = comps.sumOf { it.compressed.size + it.index.size }
                        if (compTotal < pixels.size) {
                            encoding = 1
                            bandComp = comps
                            LogStore.i(TAG, "COMPRESS msg $msgIdx (banded x$nbands): raw ${pixels.size} -> $compTotal bytes")
                        } else {
                            LogStore.i(TAG, "COMPRESS msg $msgIdx: raw wins (${pixels.size} <= $compTotal) — sending raw bands")
                        }
                    } else {
                        val rc = rowCompress(tight.first, tight.second, h)
                        if (rc.compressed.size + rc.index.size < pixels.size) {
                            encoding = 1
                            sendStream = rc.compressed
                            restartIndex = rc.index
                            LogStore.i(TAG, "COMPRESS msg $msgIdx: raw ${pixels.size} -> ${rc.compressed.size} bytes +${rc.index.size} idx")
                        }
                    }
                }
            }

            // Wipe the watch's caches only when the image's ACTUAL RESIDENT size
            // won't fit the current (no-wipe) budget. Resident = sum of band blobs
            // (+indices) when compressed, else the raw packed stream.
            val residentSize = when {
                encoding == 1 && bandComp != null -> bandComp.sumOf { it.compressed.size + it.index.size }
                encoding == 1 -> sendStream.size + (restartIndex?.size ?: 0)
                else -> pixels.size
            }
            val wipeCache = full && residentSize > curBudget
            LogStore.i(TAG, "msg $msgIdx resident=$residentSize (enc=$encoding bands=${if (banded) nbands else 0}) vs curBudget=$curBudget -> wipe=$wipeCache")

            val startFrame = mapOf(
                Schema.KEY_IMG_START      to PebbleDictionaryItem.Int32(msgIdx),
                Schema.KEY_IDX            to PebbleDictionaryItem.Int32(w),
                Schema.KEY_COUNT          to PebbleDictionaryItem.Int32(h),
                Schema.KEY_MSG_IN         to PebbleDictionaryItem.Int32(incoming),
                Schema.KEY_MSG_SENDER     to PebbleDictionaryItem.Text(senderName),
                // KEY_TYPE = decoded (raw packed) size: RAW reserve / srcRow math.
                Schema.KEY_TYPE           to PebbleDictionaryItem.Int32(pixels.size),
                Schema.KEY_IMG_WIPE_CACHE to PebbleDictionaryItem.Int32(if (wipeCache) 1 else 0),
                // LOAD-BEARING — do NOT drop as "redundant with the thread frame".
                // The watch stamps the thumbnail with this per-transfer token so a
                // mid-transfer chat switch can't re-key the image (see main.c).
                Schema.KEY_THREAD_TOKEN   to PebbleDictionaryItem.Int32(threadToken(convoId)),
                Schema.KEY_IMG_FORMAT     to PebbleDictionaryItem.Int32(imgFormat),
                Schema.KEY_IMG_ENCODING   to PebbleDictionaryItem.Int32(encoding),
                Schema.KEY_REQ_IMG_FULL   to PebbleDictionaryItem.Int32(if (full) 1 else 0)
            ) + (if (banded)
                    mapOf(Schema.KEY_IMG_BAND to PebbleDictionaryItem.Int32(nbands))
                 else emptyMap()
            ) + (if (imgFormat == 1 && palette.isNotEmpty())
                    mapOf(
                        Schema.KEY_IMG_PALETTE to PebbleDictionaryItem.Bytes(palette),
                        Schema.KEY_IMG_DEPTH   to PebbleDictionaryItem.Int32(imgDepth)
                    )
                 else emptyMap()
            ) + (if (encoding == 1 && !banded)
                    mapOf(
                        Schema.KEY_IMG_ENC_BYTES   to PebbleDictionaryItem.Int32(sendStream.size),
                        Schema.KEY_IMG_RESTART_IDX to PebbleDictionaryItem.Bytes(restartIndex!!)
                    )
                 else emptyMap()
            ) + startMsgId(msgId) + albumItemKeys(item)
            started = true
            // Transport: 3800-byte chunks, KEY_IMG_SEQ = BYTE OFFSET (band-local
            // for banded transfers). Sends are RESULT-PACED: sendDataToPebble is a
            // suspend that returns the per-watch TransmissionResult, so pacing
            // comes from the transport itself (no more open-loop 45ms sleeps), and
            // a failed frame retries — safely, because offset-addressed chunks are
            // idempotent (a duplicate rewrites the same bytes). No pause after the
            // START: the watch allocates inside its handler, before it acknowledges.
            // The frames are listed in order and sent by sendFrames, which folds
            // neighbours into one message when the watch takes that.
            val out = ArrayList<Map<UInt, PebbleDictionaryItem>>()
            out.add(startFrame)
            if (banded) {
                for (k in 0 until nbands) {
                    val rows = bandRows[k]
                    val bandStream: ByteArray
                    var bandHeader: Map<UInt, PebbleDictionaryItem> = mapOf(
                        Schema.KEY_IMG_BAND to PebbleDictionaryItem.Int32(k),
                        Schema.KEY_COUNT    to PebbleDictionaryItem.Int32(rows)
                    )
                    if (encoding == 1) {
                        val rc = bandComp!![k]
                        bandStream = rc.compressed
                        bandHeader = bandHeader + mapOf(
                            Schema.KEY_TYPE            to PebbleDictionaryItem.Int32(srcRow * rows),
                            Schema.KEY_IMG_ENC_BYTES   to PebbleDictionaryItem.Int32(rc.compressed.size),
                            Schema.KEY_IMG_RESTART_IDX to PebbleDictionaryItem.Bytes(rc.index)
                        )
                    } else {
                        val y0 = bandRows.take(k).sum()
                        bandStream = pixels.copyOfRange(y0 * srcRow, (y0 + rows) * srcRow)
                        bandHeader = bandHeader + mapOf(
                            Schema.KEY_TYPE to PebbleDictionaryItem.Int32(bandStream.size)
                        )
                    }
                    out.add(bandHeader)
                    var offset = 0
                    while (offset < bandStream.size) {
                        val end = minOf(offset + Schema.IMG_CHUNK_SIZE, bandStream.size)
                        out.add(mapOf(
                            Schema.KEY_IMG_BAND to PebbleDictionaryItem.Int32(k),
                            Schema.KEY_IMG_SEQ  to PebbleDictionaryItem.Int32(offset),
                            Schema.KEY_IMG_DATA to PebbleDictionaryItem.Bytes(bandStream.copyOfRange(offset, end))
                        ))
                        offset = end
                    }
                    // Band END: the watch paints this band NOW (progressive build).
                    out.add(mapOf(
                        Schema.KEY_IMG_END  to PebbleDictionaryItem.Int32(msgIdx),
                        Schema.KEY_IMG_BAND to PebbleDictionaryItem.Int32(k)
                    ))
                }
            } else {
                var offset = 0
                while (offset < sendStream.size) {
                    val end = minOf(offset + Schema.IMG_CHUNK_SIZE, sendStream.size)
                    out.add(mapOf(
                        Schema.KEY_IMG_SEQ  to PebbleDictionaryItem.Int32(offset),
                        Schema.KEY_IMG_DATA to PebbleDictionaryItem.Bytes(sendStream.copyOfRange(offset, end))
                    ))
                    offset = end
                }
            }
            out.add(mapOf(Schema.KEY_IMG_END to PebbleDictionaryItem.Int32(msgIdx)))
            val (msgs, lost) = sendFrames(sender, out)
            LogStore.i(TAG, "Image stream complete for msg $msgIdx (${if (banded) "$nbands bands" else "single"}, " +
                "enc=$encoding, ${out.size} frames in $msgs messages, lost=$lost)")
        } catch (e: CancellationException) {
            throw e   // a newer request took over: it answers the watch
        } catch (e: Exception) {
            LogStore.e(TAG, "streamImage failed for msg $msgIdx", e)
            // Every request gets an answer: close a transfer already started with
            // its END, else say there's no picture.
            try {
                if (started) sendImgEnd(msgIdx) else sendImgNone(convoId, msgIdx, full, item)
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                LogStore.w(TAG, "No-picture reply failed: ${e2.message}")
            }
        }
    }

    /**
     * Send one image frame, RESULT-PACED with retry. sendDataToPebble is a
     * suspend returning the per-watch TransmissionResult, so the suspend itself
     * paces the stream at the transport's real rate (the old fixed 45ms/chunk
     * sleep was open-loop guesswork stacked on top of this, and every call site
     * discarded the result — NACKed chunks were silently lost). Failures retry
     * up to 3x with backoff.
     *
     * The success check is deliberately a string heuristic: TransmissionResult's
     * concrete shape is library-side (PebbleKit2), and both failure modes of the
     * heuristic are harmless BY PROTOCOL DESIGN — a false "ok" loses one frame
     * (offset-addressed placement -> one localized gap, same as any drop today);
     * a false "fail" re-sends a frame (offset-addressed -> idempotent rewrite of
     * the same bytes; control frames are duplicate-tolerant on the watch).
     */
    // Ladder-position key for an animation: detail animations and chat-row previews
    // of the same message climb separate ladders, and so do an album's items.
    internal fun gifRungIdx(msgIdx: Int, thumb: Boolean, item: Int = -1) =
        if (thumb) -1 - msgIdx else if (item >= 0) msgIdx + 4096 * (item + 1) else msgIdx

    // ---- Folded frames (IMGCAP_FOLD) -----------------------------------------
    // The watch acknowledges a message only after handling it, so every message is
    // a round trip, and a picture's protocol frames (START, a band's header, its END)
    // cost as much as its data. A watch that says it can (IMGCAP_FOLD) takes
    // neighbouring frames in one message: a START with its data and END, a band's
    // header with its first chunk, a band's last chunk with its END.

    /** The bytes a frame takes in the watch's inbox (Pebble dictionary encoding). */
    internal fun dictBytes(m: Map<UInt, PebbleDictionaryItem>): Int {
        var n = 1
        for (v in m.values) n += 7 + when (v) {
            is PebbleDictionaryItem.Int32 -> 4
            is PebbleDictionaryItem.Text -> v.value.toByteArray(Charsets.UTF_8).size + 1
            is PebbleDictionaryItem.Bytes -> v.value.size
            else -> 8
        }
        return n
    }

    private fun sameItem(a: PebbleDictionaryItem, b: PebbleDictionaryItem): Boolean = when {
        a is PebbleDictionaryItem.Int32 && b is PebbleDictionaryItem.Int32 -> a.value == b.value
        a is PebbleDictionaryItem.Text && b is PebbleDictionaryItem.Text -> a.value == b.value
        a is PebbleDictionaryItem.Bytes && b is PebbleDictionaryItem.Bytes -> a.value.contentEquals(b.value)
        else -> false
    }

    /**
     * Whether frame `b` can join the message being built (`a`). A START always opens
     * its own message; band frames join only frames of the same band (a band's END
     * must not swallow the picture's final END, nor a band header the START's
     * keys); no key may say two different things; and the whole must fit the inbox.
     */
    private fun canMerge(a: Map<UInt, PebbleDictionaryItem>, b: Map<UInt, PebbleDictionaryItem>): Boolean {
        // (An animation's final END also names KEY_GIF_START; only its START header
        // has KEY_GIF_FRAMES.)
        if (b.containsKey(Schema.KEY_IMG_START)
            || (b.containsKey(Schema.KEY_GIF_START) && b.containsKey(Schema.KEY_GIF_FRAMES))) return false
        if (a.containsKey(Schema.KEY_IMG_BAND) != b.containsKey(Schema.KEY_IMG_BAND)) return false
        var extra = 0
        for ((k, v) in b) {
            val o = a[k]
            if (o == null) extra += dictBytes(mapOf(k to v)) - 1
            else if (!sameItem(o, v)) return false
        }
        return dictBytes(a) + extra <= FOLD_MAX
    }

    /**
     * Send frames in order, each neighbour folded into the message before it when
     * the watch takes that (IMGCAP_FOLD) and canMerge allows. Returns (messages sent,
     * messages lost after retries).
     */
    internal suspend fun sendFrames(
        sender: PebbleSender, frames: List<Map<UInt, PebbleDictionaryItem>>
    ): Pair<Int, Int> {
        val fold = (Mirror.watchCaps and Schema.IMGCAP_FOLD) != 0
        var sent = 0
        var lost = 0
        var cur: LinkedHashMap<UInt, PebbleDictionaryItem>? = null
        for (f in frames) {
            if (fold && cur != null && canMerge(cur, f)) { cur.putAll(f); continue }
            if (cur != null) { sent++; if (!sendImgFrame(sender, cur)) lost++ }
            cur = LinkedHashMap(f)
        }
        if (cur != null) { sent++; if (!sendImgFrame(sender, cur)) lost++ }
        return sent to lost
    }

    // ---- Full pictures, kept and made ahead ------------------------------------
    // The watch's room for a detail-view picture is the same every time now (the
    // media pool), so a full picture's encode is kept like a preview's, and the
    // pictures on screen are encoded before they're opened: opening one costs only
    // the stream. Keyed by the media and every setting the encode depends on.
    private val fullCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > FULL_CACHE_MAX
    }
    private val fullBuilding = HashMap<String, Deferred<String>>()   // under fullLock
    private val fullLock = Any()
    private val fullPrepGate = Semaphore(FULL_PREP_PARALLEL)
    private val fullPrepJobs = java.util.Collections.synchronizedList(mutableListOf<kotlinx.coroutines.Job>())

    private fun fullSettings(): String =
        "${Mirror.imgFullCap}|${Mirror.imgFullCoverage}|${Mirror.imgCompress}|${Mirror.imgDither}|" +
        "${Mirror.imgDitherCompress}|${Mirror.imgDitherIntensity}"

    private fun fullSrc(mediaID: String, inline: String): String =
        if (mediaID.isNotEmpty()) mediaID else "i${inline.length}:${inline.hashCode()}"

    /** The key of a full picture's unified encode (still or animation). */
    private fun fullEnvKey(convoId: String, mediaID: String, inline: String, maxW: Int, gifMax: Int,
                           gifFps: Int, gifBytes: Int, startRung: Int): String =
        "E|$convoId|${fullSrc(mediaID, inline)}|$maxW|$gifMax|$gifFps|$gifBytes|$startRung|${fullSettings()}"

    /** The key of a full picture's still-path encode. */
    private fun fullStillKey(convoId: String, mediaID: String, inline: String, maxW: Int, maxBytes: Int): String =
        "S|$convoId|${fullSrc(mediaID, inline)}|$maxW|$maxBytes|${fullSettings()}"

    /** A usable full encode: a unified result ({"kind"...}) or a still ({"w"...}). Errors aren't kept. */
    private fun fullGood(r: String): Boolean =
        r.trimStart().startsWith("{") &&
            try { JSONObject(r).let { it.has("kind") || it.has("w") } } catch (e: Exception) { false }

    /**
     * A full picture's encode, made once: the kept one, the one already being made
     * (ahead of time, or by an earlier request), or a new one. Like thumbCached:
     * the encode keeps its own (usable) result and leaves the in-progress table
     * when it ends; a wait gives up after FULL_ENCODE_MS.
     */
    private suspend fun fullCached(key: String, make: () -> String): String {
        val d = synchronized(fullLock) {
            fullCache[key]?.let {
                LogStore.i(TAG, "Full picture ready (made ahead or kept)")
                return it
            }
            fullBuilding[key] ?: run {
                val self = arrayOfNulls<Deferred<String>>(1)
                val nd = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        val r = make()
                        if (fullGood(r)) synchronized(fullLock) { fullCache[key] = r }
                        r
                    } finally {
                        synchronized(fullLock) { if (fullBuilding[key] === self[0]) fullBuilding.remove(key) }
                    }
                }
                self[0] = nd
                fullBuilding[key] = nd
                nd.start()
                nd
            }
        }
        return withTimeoutOrNull(FULL_ENCODE_MS) { d.await() } ?: run {
            synchronized(fullLock) { if (fullBuilding[key] === d) fullBuilding.remove(key) }
            LogStore.w(TAG, "Full picture encode gave up after ${FULL_ENCODE_MS / 1000}s")
            ""
        }
    }

    /**
     * Encode row msgIdx's full picture (or animation) now, exactly as the detail
     * view's request will ask for it, so opening it costs only the stream. One at a
     * time, newest asks first: the oldest still waiting are dropped past four.
     * Only when the watch has said what room a full picture gets (Time 2). An
     * album's (step 88) is its first item, or the one named; one that pages through
     * albums only.
     */
    private fun prepFull(convoId: String, msgIdx: Int, item: Int = -1) {
        val room = Mirror.watchFullRoom
        val dispW = Mirror.watchDisplayW
        if (room <= 0 || dispW <= 0) return
        val job = scope.launch {
            fullPrepGate.withPermit {
                try {
                    val svc = Mirror.activeService() ?: return@withPermit
                    val row = buildTrimmedCached(convoId).getOrNull(msgIdx) ?: return@withPermit
                    val k = if (row.album.size >= 2) maxOf(0, item) else -1
                    if (k >= 0 && (Mirror.watchCaps and Schema.IMGCAP_ALBUM) == 0) return@withPermit
                    val mm = if (k >= 0) albumItemRow(row, k) ?: return@withPermit else row
                    if (k >= 0 && mm.videoMediaID.isNotEmpty()) return@withPermit   // a video: played, not shown
                    if (mm.mediaID.isEmpty() && mm.mediaDataB64.isEmpty()) return@withPermit
                    if (mm.voice) return@withPermit      // played, never shown whole (step 86)
                    val q = 1
                    val isPoster = mm.mediaID.startsWith(POSTER_PREFIX) || mm.mediaDataB64.startsWith(POSTER_PREFIX)
                    if (!isPoster) {
                        val gifBytes = if (GIF_WIRE_BUDGET in 1 until room) GIF_WIRE_BUDGET else room
                        val startRung = Mirror.gifStartRung(convoId, gifRungIdx(msgIdx, false, k))
                        val env = fullCached(fullEnvKey(convoId, mm.mediaID, mm.mediaDataB64, dispW, room,
                                GIF_FPS_FLOOR, gifBytes, startRung)) {
                            fetchMediaEnv(svc, convoId, mm.mediaID, mm.decryptionKeyB64, mm.mediaDataB64,
                                dispW, 0, room, q, GIF_FPS_FLOOR, gifBytes, startRung)
                        }
                        if (env.isNotEmpty() && env.trimStart().startsWith("{")) return@withPermit
                    }
                    fullCached(fullStillKey(convoId, mm.mediaID, mm.mediaDataB64, dispW, room)) {
                        encodeStill(svc, convoId, msgIdx, mm.mediaID, mm.decryptionKeyB64, mm.mediaDataB64,
                            true, dispW, 0, room, q, room, room)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogStore.w(TAG, "Full picture made-ahead failed for msg $msgIdx: ${e.message}")
                }
            }
        }
        synchronized(fullPrepJobs) {
            fullPrepJobs.add(job)
            while (fullPrepJobs.size > 4) fullPrepJobs.removeAt(0).cancel()
        }
        job.invokeOnCompletion { fullPrepJobs.remove(job) }
    }

    /** Drop the made-ahead encodes still waiting (one under way finishes and is kept). */
    private fun cancelFullPrep() {
        val jobs = synchronized(fullPrepJobs) { ArrayList(fullPrepJobs).also { fullPrepJobs.clear() } }
        jobs.forEach { it.cancel() }
    }

    internal suspend fun sendImgFrame(
        sender: PebbleSender, data: Map<UInt, PebbleDictionaryItem>
    ): Boolean {
        for (attempt in 0 until 3) {
            val res = try {
                sender.sendDataToPebble(Schema.WATCH_UUID, data, watches = null)
            } catch (e: Exception) {
                LogStore.w(TAG, "sendImgFrame attempt ${attempt + 1} threw: ${e.message}")
                null
            }
            val ok = res != null &&
                res.values.none { it.toString().contains("fail", ignoreCase = true) }
            if (ok) return true
            delay(120L * (attempt + 1))
        }
        LogStore.w(TAG, "sendImgFrame: frame lost after 3 attempts (keys=${data.keys})")
        return false
    }

    // ---- Phase 2: heap-engine row compression encoder ---------------------
    // Result of compressing one image: the concatenated per-row payloads and the
    // uint16-LE restart index (compressed offset of every Nth row).
    internal class RowComp(val compressed: ByteArray, val index: ByteArray)

    /**
     * Strip word-align padding from the encoder's aligned stream down to TIGHT rows
     * (the layout the watch decodes for compressed frames: ceil(w*depth/8) bytes/
     * row, no 4-byte padding). Returns (tightBytes, tightRowStride) or null if the
     * geometry doesn't line up (in which case the caller falls back to raw).
     */
    private fun tightRows(aligned: ByteArray, w: Int, depth: Int): Pair<ByteArray, Int>? {
        if (depth != 1 && depth != 2 && depth != 4) return null
        val ppb = 8 / depth
        val tightRow = (w + ppb - 1) / ppb
        val alignedRow = (tightRow + 3) and 3.inv()
        if (alignedRow == 0 || aligned.size % alignedRow != 0) return null
        val h = aligned.size / alignedRow
        if (tightRow == alignedRow) return Pair(aligned, tightRow) // already tight
        val out = ByteArray(tightRow * h)
        for (y in 0 until h) {
            System.arraycopy(aligned, y * alignedRow, out, y * tightRow, tightRow)
        }
        return Pair(out, tightRow)
    }

    private val RESTART_N = 8
    private val REPEAT_ROW = 0x00.toByte()

    /**
     * Compress a tight-packed stream row-by-row (see the Phase-2 comment in Mirror's
     * Schema). Each row is independent PackBits; a row byte-identical to the one
     * above becomes a single 0x00 "repeat previous row" token — EXCEPT restart rows
     * (every RESTART_N-th), which are always real so a seek lands on a decodable row.
     * PackBits count byte: 1..127 => repeat next byte N times; 128..255 => copy the
     * next (256-c) literals. 0x00 is never a PackBits count, so it's free as the
     * whole-row repeat marker.
     */
    internal fun rowCompress(tight: ByteArray, rowBytes: Int, h: Int): RowComp {
        val comp = java.io.ByteArrayOutputStream()
        val index = java.io.ByteArrayOutputStream()
        var prevStart = -1  // offset of previous row in `tight`, or -1
        for (y in 0 until h) {
            val start = y * rowBytes
            val isRestart = (y % RESTART_N == 0)
            if (isRestart) {
                val off = comp.size()
                index.write(off and 0xFF); index.write((off ushr 8) and 0xFF)
            }
            val repeat = !isRestart && prevStart >= 0 &&
                rowEquals(tight, start, prevStart, rowBytes)
            if (repeat) {
                comp.write(0x00) // REPEAT_ROW
            } else {
                packBitsRow(tight, start, rowBytes, comp)
            }
            prevStart = start
        }
        return RowComp(comp.toByteArray(), index.toByteArray())
    }

    private fun rowEquals(a: ByteArray, o1: Int, o2: Int, n: Int): Boolean {
        for (i in 0 until n) if (a[o1 + i] != a[o2 + i]) return false
        return true
    }

    /** PackBits-encode one row [start, start+n) into `out`. Never emits a 0x00 count. */
    private fun packBitsRow(row: ByteArray, start: Int, n: Int, out: java.io.ByteArrayOutputStream) {
        var i = 0
        while (i < n) {
            var runLen = 1
            while (i + runLen < n && row[start + i + runLen] == row[start + i] && runLen < 127) runLen++
            if (runLen >= 2) {
                out.write(runLen)                 // 1..127 = repeat
                out.write(row[start + i].toInt() and 0xFF)
                i += runLen
            } else {
                var litLen = 1
                while (i + litLen < n && litLen < 128) {
                    if (i + litLen + 1 < n && row[start + i + litLen] == row[start + i + litLen + 1]) break
                    litLen++
                }
                out.write((256 - litLen) and 0xFF)  // 128..255 = copy litLen literals
                for (k in 0 until litLen) out.write(row[start + i + k].toInt() and 0xFF)
                i += litLen
            }
        }
    }

    // ---- Auto-push conversations on watchapp open --------------------------

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != Schema.WATCH_UUID) return
        Mirror.watchAppOpen = true
        LogStore.init(applicationContext)
        LogStore.i(TAG, "Watchapp opened — pushing conversations")
        scope.launch { pushConversations() }
    }

    private suspend fun pushConversations() {
        try {
            // AUTHORITATIVE RESYNC on watchapp open. The watch may hold stale state
            // from before the phone's data was wiped (a roster of accounts that no
            // longer exist, a selector pointing at a gone row). Before pushing, make
            // the active account a REAL logged-in one (the persisted id — or the
            // "google" default — may name a wiped account), so we never boot the
            // watch into a dead account with an empty list and broken nav.
            // Use what ensureActiveLoggedIn resolved, not Mirror.activeService():
            // with nothing logged in, activeService() still names the logged-out
            // account, and its cached list used to stream to the watch.
            val svc = Mirror.ensureActiveLoggedIn(this) ?: run {
                // Nothing logged in (a logout, or right after a full wipe). Send
                // only the authoritative (empty) roster: the watch drops its saved
                // list and asks the user to log in on the phone. No list follows.
                LogStore.w(TAG, "Nothing logged in — reporting empty roster, no list")
                reportServiceList()
                return
            }
            // A fresh watchapp open lands on the account's top level — clear any nav
            // context left from a prior session so the root list (and the cache) apply.
            Mirror.navStack.clear()
            // Pin the context this push is FOR. Both background coroutines below
            // capture `svc` by reference and run after a delay, during which the
            // user can switch accounts on the watch. If that happens, the account
            // switch handler (repushConversations) has already pushed the correct
            // list — so anything these stale coroutines compute is the WRONG
            // service's data (e.g. WhatsApp threads written into / streamed under
            // the now-active Facebook list). Bail out if the active context has
            // changed. We re-check AFTER each fetch too, since listConversations
            // can span the switch. The pin is the full switcher tuple (service +
            // folder + guild), so folder/guild switches are caught too.
            val pinnedKey = Mirror.convoCacheKey()
            reportServiceList()
            val cachedJson = Mirror.getCachedConvoJson()
            if (cachedJson != null) {
                LogStore.i(TAG, "Serving cached conversation list")
                streamConvos(cachedJson)
                scope.launch {
                    try {
                        val fresh = svc.listContext(CONVO_CAP, Mirror.navStack.toList())
                        if (Mirror.convoCacheKey() == pinnedKey) Mirror.putCachedConvoJson(fresh, pinnedKey)
                    } catch (e: Exception) { LogStore.e(TAG, "Convo background refresh failed", e) }
                }
                // Late async data can land AFTER the cache serve above: an account
                // whose threads load asynchronously (notably FB's E2EE thread
                // restore, which only completes once FB finishes connecting ~1s
                // after the watchapp opens) isn't in the cache yet, so the first
                // list the watch sees is missing it. Re-check shortly and re-stream
                // only if the list actually changed, so a freshly-restored thread
                // (e.g. a 1:1 E2EE chat) appears without a manual reopen.
                scope.launch {
                    try {
                        delay(1500)
                        if (Mirror.convoCacheKey() != pinnedKey) return@launch
                        val refreshed = svc.listContext(CONVO_CAP, Mirror.navStack.toList())
                        if (Mirror.convoCacheKey() != pinnedKey) return@launch
                        if (refreshed != Mirror.getCachedConvoJson()) {
                            Mirror.putCachedConvoJson(refreshed, pinnedKey)
                            streamConvos(refreshed)
                            LogStore.i(TAG, "Late convo restore — re-streamed list to watch")
                        }
                    } catch (e: Exception) { LogStore.e(TAG, "Late convo refresh failed", e) }
                }
            } else {
                val json = svc.listContext(CONVO_CAP, Mirror.navStack.toList())
                if (Mirror.convoCacheKey() == pinnedKey) {
                    Mirror.putCachedConvoJson(json, pinnedKey)
                    streamConvos(json)
                }
            }
        } catch (e: Exception) {
            LogStore.e(TAG, "pushConversations failed", e)
        }
    }

    /**
     * Pushes the CURRENTLY ACTIVE service's conversations after an account switch
     * (KEY_REQ_SERVICE), cache-first: the account's own last-known root list (the
     * per-account cache) streams IMMEDIATELY, then a background fetch re-streams
     * only if the list actually changed. This is what makes switching instant for
     * every network — Google's listConversations is a ~4s server round-trip
     * (measured), and the old blocking fetch-then-stream made the watch sit on
     * the old account that whole time. (The old doc here said "bypassing the
     * cache... instead of the stale cached list" — that was the single-slot
     * world, where the cache could only hold the PREVIOUS account's list. With
     * per-account keys the cache IS this account's own list.) Also re-reports
     * the service list + active index so the switcher rail reflects the new
     * selection. First-ever switch to an account (no cache yet) falls back to
     * the blocking fetch.
     */
    private suspend fun repushConversations() {
        try {
            val svc = Mirror.activeService() ?: run { LogStore.w(TAG, "No active service for repush"); return }
            // Switching accounts lands on the new account's top level.
            Mirror.navStack.clear()
            reportServiceList()
            // Pin the full switcher tuple BEFORE any fetch: the user can switch
            // again mid-fetch, and both the cache put and the re-stream must then
            // be abandoned (they'd belong to the abandoned account).
            val pinnedKey = Mirror.convoCacheKey()
            val cached = Mirror.getCachedConvoJson()
            if (cached != null) {
                LogStore.i(TAG, "Account switch: served cached list for ${svc.id} (freshening behind)")
                streamConvos(cached)
                scope.launch {
                    try {
                        val fresh = svc.listContext(CONVO_CAP, emptyList())
                        if (Mirror.convoCacheKey() != pinnedKey) return@launch
                        if (fresh != cached) {
                            Mirror.putCachedConvoJson(fresh, pinnedKey)
                            streamConvos(fresh)
                            LogStore.i(TAG, "Switch refresh: list changed — re-streamed to watch")
                        }
                    } catch (e: Exception) { LogStore.e(TAG, "Switch background refresh failed", e) }
                }
            } else {
                val json = svc.listContext(CONVO_CAP, Mirror.navStack.toList())
                if (Mirror.convoCacheKey() == pinnedKey) {
                    Mirror.putCachedConvoJson(json, pinnedKey)
                    streamConvos(json)
                }
            }
        } catch (e: Exception) {
            LogStore.e(TAG, "repushConversations failed", e)
        }
    }

    // Resolve Beeper's shared base class WITHOUT a compile-time reference to it.
    // This file is core, and the core never imports a concrete network class —
    // that's what lets ServiceRegistry drop a network in/out by reflection and
    // still build (see ServiceRegistry's design note). A hard `is BeeperServiceBase`
    // here would break that: deleting Beeper's files would fail to compile this
    // file. So we look the base class up reflectively, the same way the registry
    // loads adapters. If it's absent, every service reports as native, the mask
    // is 0, and the watch draws no "Beeper" divider — exactly the additive
    // fallback the watch expects.
    private val beeperBaseClass: Class<*>? by lazy {
        try { Class.forName("com.dano.mirrormsg.BeeperServiceBase") }
        catch (_: Throwable) { null }
    }
    private fun isBeeperService(svc: MessagingService): Boolean =
        beeperBaseClass?.isInstance(svc) ?: false

    /**
     * Reports logged-in networks + active index to the watch (KEY_SERVICES +
     * KEY_ACTIVE_SERVICE) so the account-switcher rail shows the right options.
     * Sent only when 2+ accounts are connected (no switcher for a single one).
     */
    private suspend fun reportServiceList() {
        if (!watchSendAllowed()) return
        try {
            // Augmented rail = logged-in services + each enabled Telegram folder as
            // an indented sub-account. Built via Mirror so this emitter, the
            // MainActivity emitter, and the KEY_REQ_SERVICE selector all agree on
            // the exact label/index order. lastSwitcherEntries records what the
            // watch is showing so a selected index maps back to a service+folder.
            val entries = Mirror.switcherEntries(this)
            // ALWAYS report the authoritative roster, even at 0 or 1 account. The
            // old `if (entries.size < 2) return` suppressed the push for a single
            // account "because no switcher UI is needed" — but that left the watch
            // showing a STALE roster after the phone's data is wiped (e.g. 3
            // accounts drop to 1, but the watch never hears the count shrank, so
            // its switcher indices no longer match the phone and nav/switch break).
            // The watch adopts whatever roster we send as authoritative: 1 label =
            // single account (no switcher chrome), 0 labels = nothing logged in.
            Mirror.lastSwitcherEntries = entries
            val labels = entries.joinToString("\u001e") { it.label }
            val activeIdx = entries.indexOfFirst {
                it.serviceId == Mirror.activeServiceId &&
                    it.folderId == Mirror.activeTgFolder &&
                    it.guildId == Mirror.activeDiscordGuild
            }.coerceAtLeast(0)
            val serviceFlags = Mirror.switcherFlags(this, entries)
            val sender = Mirror.sender(this)
            val frame = HashMap<UInt, PebbleDictionaryItem>()
            frame[Schema.KEY_OP]             = PebbleDictionaryItem.Int32(Schema.OP_CONVO)
            frame[Schema.KEY_SERVICES]       = PebbleDictionaryItem.Text(labels)
            frame[Schema.KEY_ACTIVE_SERVICE] = PebbleDictionaryItem.Int32(activeIdx)
            frame[Schema.KEY_SERVICE_FLAGS]  = PebbleDictionaryItem.Int32(serviceFlags)
            // 1 if the active service supports reaction-send (watch uses this to
            // keep the chat put on a reaction vs jump to bottom on a message).
            val activeSvcId = entries.getOrNull(activeIdx)?.serviceId ?: Mirror.activeServiceId
            frame[Schema.KEY_ACTIVE_CAN_REACT] = PebbleDictionaryItem.Int32(
                if (ServiceRegistry.byId(this, activeSvcId)?.supportsReactionSend == true) 1 else 0)
            // The notification sound (step 91), for the switcher's sound row on a Time 2.
            frame[Schema.KEY_SOUND] = PebbleDictionaryItem.Int32(Mirror.pushTone)
            frame[Schema.KEY_TONE_VOL] = PebbleDictionaryItem.Int32(Mirror.toneVol + 1)
            sender.sendDataToPebble(Schema.WATCH_UUID, frame, watches = null)
            LogStore.i(TAG, "Reported services to watch: [$labels] active=$activeIdx flags=$serviceFlags")
        } catch (e: Exception) {
            LogStore.e(TAG, "reportServiceList failed", e)
        }
    }

    // Signature of the last switcher we pushed (logged-in ids + active id + Beeper
    // mask). refreshSwitcher() compares against this so it only re-sends when the
    // switcher actually changed.
    @Volatile private var lastSwitcherSig: String = ""

    /**
     * Background-safe re-push of the account switcher — but only if it actually
     * changed since the last push. The Beeper poll calls this when an account's
     * logged-in status may have flipped in the background (the user logged in/out
     * of a network inside Beeper). Nothing else re-reports the switcher in that
     * case, so without this the watch shows a stale rail: a newly-present account
     * missing, or a ghost account that dead-ends when selected.
     *
     * Safe to call liberally (e.g. every poll cycle) — the signature diff drops
     * no-op calls so it won't spam the watch. Runs on the service scope, needs no
     * Activity: call via PebbleListenerService.instance?.refreshSwitcher(). Also
     * called on a logout and when a Beeper account (or Beeper itself) is switched
     * off or on.
     *
     * With the watchapp open, a change that leaves nothing logged in, brings the
     * first account back, or takes away the active account re-syncs the watch in
     * full (pushConversations: the roster and the active account's list, or only
     * the empty roster, which makes the watch drop its list and ask to log in).
     * Any other change re-sends only the rail, so the watch keeps its place.
     */
    fun refreshSwitcher() {
        scope.launch {
            try {
                val entries = Mirror.switcherEntries(this@PebbleListenerService)
                val sig = entries.joinToString("\u001e") { it.serviceId + ":" + it.folderId + ":" + it.guildId } +
                          "|" + Mirror.activeServiceId + "|" + Mirror.activeTgFolder + "|" + Mirror.activeDiscordGuild
                if (sig == lastSwitcherSig) return@launch
                lastSwitcherSig = sig
                val full = Mirror.watchAppOpen && (entries.isEmpty() ||
                    Mirror.lastSwitcherEntries.isEmpty() ||
                    Mirror.activeService()?.isLoggedIn() != true)
                if (full) {
                    LogStore.i(TAG, "Accounts changed (${entries.size} logged in) — full watch resync")
                    pushConversations()
                } else {
                    reportServiceList()   // rebuilds + sends KEY_SERVICES / ACTIVE / FLAGS
                }
            } catch (e: Exception) {
                LogStore.e(TAG, "refreshSwitcher failed", e)
            }
        }
    }

    // Build the FULL conversation index (convoOrder + simRoutes + convoTargets) from
    // the list JSON, capped at CONVO_CAP, so absolute indices (KEY_REQ_OPEN and the
    // force-open row) resolve across every page — the watch holds only one page, but
    // the phone indexes the whole list. Returns the total. Streaming is separate.
    private fun rebuildConvoIndex(json: String): Int {
        val arr = JSONObject(json).getJSONArray("conversations")
        // total = the real conversation count. The bridge returns up to CONVO_CAP
        // ACTUAL conversations (confirmed via diag: all entries carry real IDs and
        // names), so the raw length IS the real total — the watch scrolls [0, total)
        // and requests successive spans as it goes.
        val total = minOf(arr.length(), CONVO_CAP)
        Mirror.convoOrder.clear()
        Mirror.simRoutes.clear()
        Mirror.navKinds.clear()
        Mirror.navContexts.clear()
        for (i in 0 until total) {
            val c = arr.getJSONObject(i)
            val convoId = c.optString("conversationID")
            Mirror.convoOrder.add(convoId)
            // Navigation kind: ENTER (bucket/server/folder) vs OPEN (a real chat).
            // Absent navKind => OPEN, so flat services need no change.
            val isEnter = c.optString("navKind") == "ENTER"
            Mirror.navKinds.add(if (isEnter) 1 else 0)
            Mirror.navContexts.add(if (isEnter) c.optString("navContext") else "")
            // Deep-link target: protocol (top-level "type") + the other participant's
            // number, so "Open on phone"/banner can jump straight to the thread.
            Mirror.convoTargets[convoId] = parseConvoTarget(c)
            val simPayload = c.optJSONObject("simCard")
                ?.optJSONObject("SIMData")?.optJSONObject("SIMPayload")
            val simTwo    = (simPayload?.optInt("two", 2) ?: 2).toLong()
            val simNumber = (simPayload?.optInt("SIMNumber", 2) ?: 2).toLong()
            Mirror.simRoutes.add(Mirror.SimRoute(
                c.optString("defaultOutgoingID", "2"), simTwo, simNumber))
        }
        return total
    }

    // First-frame-only config (settings/state the watch reads once per list stream).
    private fun attachFirstFrameConfig(cf: HashMap<UInt, PebbleDictionaryItem>) {
        cf[Schema.KEY_NIGHT_MODE] = PebbleDictionaryItem.Int32(if (Mirror.nightMode) 1 else 0)
        cf[Schema.KEY_QUICK_REPLIES] =
            PebbleDictionaryItem.Text(Mirror.quickReplies.joinToString("\u001e"))
        // KEY_THUMB_LIMIT / KEY_HEAP_GUARD are no longer sent: the watch's media
        // pool decides what stays, and Basalt uses the watch's own defaults.
        cf[Schema.KEY_ACTIVE_CAN_REACT] =
            PebbleDictionaryItem.Int32(if (Mirror.activeService()?.supportsReactionSend == true) 1 else 0)
    }

    // Rebuild the full index and stream a fresh-context span from the top (abs 0).
    // Used by every non-force-open push: reconnect, live refresh, account switch,
    // drill result. The watch resets its window to origin on frame-0 abs 0.
    private suspend fun streamConvos(json: String) {
        val total = rebuildConvoIndex(json)
        streamConvoSpan(json, 0, total)
    }

    // Stream a SPAN of the current list: rows [start, start+slots) tagged with their
    // ABSOLUTE index (KEY_LIST_ABS) and the full-list total (KEY_LIST_TOTAL). Frame 0
    // (KEY_IDX=0) carries abs == start — the load-bearing invariant that lets the
    // watch tell "response to my KEY_REQ_SPAN=start" from a fresh push. `slots` is the
    // watch's window size, sent per-request via KEY_REQ_SPAN_COUNT (10 Basalt / 20
    // Emery). Fresh/unsolicited pushes (open, drill, account switch, force-open) don't
    // carry a watch count — they pass DEFAULT_SPAN_SLOTS; the watch re-requests the
    // correct window size on its first scroll, and the origin push just needs enough
    // rows to fill the initial view (the watch tolerates a short first push and asks
    // again). Callers pass start=0 for fresh pushes; the span handler passes reqSpan.
    private suspend fun streamConvoSpan(json: String, start: Int, total: Int,
                                        slots: Int = DEFAULT_SPAN_SLOTS) {
        if (!watchSendAllowed()) return
        val arr = JSONObject(json).getJSONArray("conversations")
        val sender = Mirror.sender(this@PebbleListenerService)
        val ownerSvc = Mirror.activeService()
        val s = start.coerceIn(0, maxOf(0, total - 1))
        val end = minOf(s + slots, total)
        val len = (end - s).coerceAtLeast(0)
        val hasParent = Mirror.navStack.isNotEmpty()
        if (len == 0) {
            // Empty list (0 convos): still send a frame with count 0 so the watch
            // clears its window and learns the total. abs == start (clamped 0) holds.
            val cf = HashMap<UInt, PebbleDictionaryItem>()
            cf[Schema.KEY_OP]          = PebbleDictionaryItem.Int32(Schema.OP_CONVO)
            cf[Schema.KEY_COUNT]       = PebbleDictionaryItem.Int32(0)
            cf[Schema.KEY_CONVO_BLOB]  = PebbleDictionaryItem.Text("")
            cf[Schema.KEY_LIST_ABS]    = PebbleDictionaryItem.Int32(0)
            cf[Schema.KEY_LIST_TOTAL]  = PebbleDictionaryItem.Int32(0)
            cf[Schema.KEY_LIST_HAS_PARENT] = PebbleDictionaryItem.Int32(if (hasParent) 1 else 0)
            attachFirstFrameConfig(cf)
            sender.sendDataToPebble(Schema.WATCH_UUID, cf, watches = null)
            LogStore.i(TAG, "Streamed convo span (empty list, total=0) to watch")
            return
        }
        // Pack the span as ONE blob: records RS-separated, fields US-separated, in
        // absolute order from s. One frame -> one ACK, regardless of row count (vs the
        // old N-frames-per-span). Clamp to the ~2KB cap: if adding a record would
        // exceed it, stop and set count to what fit — the watch re-requests the rest
        // via its margin logic. In practice 20 rows (~1KB) never approaches the cap.
        val sb = StringBuilder()
        var packed = 0
        var blobBytes = 0
        val rsBytes = BLOB_RS.toByteArray(Charsets.UTF_8).size
        for (rel in 0 until len) {
            val abs = s + rel
            val c = arr.getJSONObject(abs)
            val record = packConvoRecord(c, abs, ownerSvc)
            // Measure the cap in UTF-8 BYTES (names/snippets may hold multi-byte
            // chars), matching the watch's byte-sized inbox — not char count.
            val recBytes = record.toByteArray(Charsets.UTF_8).size
            val addBytes = recBytes + (if (packed > 0) rsBytes else 0)
            if (packed > 0 && blobBytes + addBytes > CONVO_BLOB_BYTE_CAP) break
            if (packed > 0) sb.append(BLOB_RS)
            sb.append(record)
            blobBytes += addBytes
            packed++
        }
        val frame = HashMap<UInt, PebbleDictionaryItem>()
        frame[Schema.KEY_OP]          = PebbleDictionaryItem.Int32(Schema.OP_CONVO)
        frame[Schema.KEY_COUNT]       = PebbleDictionaryItem.Int32(packed)
        frame[Schema.KEY_CONVO_BLOB]  = PebbleDictionaryItem.Text(sb.toString())
        // Frame-0-abs invariant: KEY_LIST_ABS = span start s (blob's first convo is
        // abs s; the rest follow in order). For a counted response s == requested v;
        // for a fresh push s == 0. KEY_IDX is not used in blob mode.
        frame[Schema.KEY_LIST_ABS]    = PebbleDictionaryItem.Int32(s)
        frame[Schema.KEY_LIST_TOTAL]  = PebbleDictionaryItem.Int32(total)
        frame[Schema.KEY_LIST_HAS_PARENT] = PebbleDictionaryItem.Int32(if (hasParent) 1 else 0)
        attachFirstFrameConfig(frame)
        sender.sendDataToPebble(Schema.WATCH_UUID, frame, watches = null)
        LogStore.i(TAG, "Streamed convo blob [$s,${s + packed}) of $total to watch " +
            "(frame0 abs=$s, $packed rows, ${blobBytes}B)")
        // Photos the watch asked for before the phone knew their rows (step 90).
        for ((dd, keys) in Avatars.takeWaiting()) scope.launch { answerAvatars(dd, keys) }
    }

    // Pack ONE convo into a US-delimited record for the span blob:
    //   flags US type US color US name US snippet
    // flags: bit0 = enterable (drill row), bit1 = unread. type: decimal (1/4/5).
    // color: "#RRGGBB" (empty allowed -> watch defaults to purple; never malformed).
    // name/snippet: already fit to WATCH_NAME_MAX_BYTES / WATCH_SNIPPET_MAX_BYTES and
    // cannot contain US/RS. Snippet is the drill-arrow padded string for ENTER rows.
    private fun packConvoRecord(c: JSONObject, abs: Int, ownerSvc: MessagingService?): String {
        val enterable = Mirror.navKinds.getOrNull(abs) == 1
        val unread = c.optBoolean("unread", false)
        val flags = (if (enterable) 1 else 0) or (if (unread) 2 else 0)
        val type = when (c.optString("type")) { "RCS" -> 4; "FB" -> 5; else -> 1 }
        // Color: keep only a well-formed #RRGGBB; anything else -> empty (safe default).
        val rawColor = convoColor(c, ownerSvc)
        val color = if (Regex("^#[0-9a-fA-F]{6}$").matches(rawColor)) rawColor else ""
        val name = EmojiSub.processText(c.optString("name").ifEmpty { "(no name)" })
        val snippet = if (enterable) {
            // Emery (wider screen) needs extra padding to push the arrow to the edge.
            // Key on the watch's convo window size (>=20 => Emery, 10 => Basalt), which
            // arrives WITH the convo-span request — i.e. present at list-build time —
            // rather than watchDisplayW, which stays 0 until an image request stamps it
            // (that lag was moving/dropping the arrow after the first image).
            val isEmery = Mirror.convoSpanCount >= 20 || Mirror.watchDisplayW >= 200
            val arrowPad = if (isEmery) ENTER_ARROW_PAD + EMERY_ARROW_EXTRA else ENTER_ARROW_PAD
            "\u2192".padStart(arrowPad + 1)
        } else EmojiSub.processText(convoSnippet(c))
        // Sixth field (step 90): the key of the chat's photo, shown in the avatar's
        // circle; "" keeps the letter. An older watch ignores a sixth field.
        val avKey = Avatars.rowKey(ownerSvc, c, color)
        return "$flags$BLOB_US$type$BLOB_US$color$BLOB_US$name$BLOB_US$snippet$BLOB_US$avKey"
    }

    // ---- Contact photos (step 90) --------------------------------------------------
    // KEY_AV_REQ (watch->phone): [u8 dd][u32 key]..., little-endian: the rows on its
    // screen drawn without their photo, dd px across. Every key the phone knows gets a
    // KEY_AV_DATA record (Avatars.watchRecord): what's on the phone at once, in as few
    // messages as fit; then what has to be fetched (or was stale), each as it lands. A
    // key the phone doesn't know yet (it has just started, and no list has packed its
    // row) is held, and answered once a list does (streamConvoSpan).
    private val avSend = Mutex()

    private suspend fun answerAvatars(req: ByteArray) {
        if (req.size < 5) return
        val dd = req[0].toInt() and 0xFF
        val keys = (0 until (req.size - 1) / 4).map { k ->
            var v = 0L
            for (i in 0 until 4) v = v or ((req[1 + 4 * k + i].toLong() and 0xFF) shl (8 * i))
            v
        }.distinct()
        answerAvatars(dd, keys)
    }

    private suspend fun answerAvatars(dd: Int, keys: List<Long>) {
        val ctx = this@PebbleListenerService
        val now = HashMap<Long, ByteArray>()
        for (k in keys) Avatars.watchRecord(ctx, k, dd, false)?.let { now[k] = it }
        sendAvatarRecords(keys.mapNotNull { now[it] })
        val unknown = keys.filter { !Avatars.knows(it) }
        for (k in unknown) Avatars.waitFor(k, dd)
        val later = keys.filter { Avatars.knows(it) && (it !in now || Avatars.needsFetch(ctx, it)) }
        LogStore.i(TAG, "Contact photos: ${keys.size} asked at ${dd}px, ${now.size} sent, " +
            "${later.size} to fetch, ${unknown.size} held for the list")
        for (k in later) scope.launch {
            val r = Avatars.watchRecord(ctx, k, dd, true) ?: return@launch
            if (!r.contentEquals(now[k])) sendAvatarRecords(listOf(r))
        }
    }

    private suspend fun sendAvatarRecords(recs: List<ByteArray>) {
        if (recs.isEmpty()) return
        avSend.withLock {
            var i = 0
            while (i < recs.size) {
                val out = java.io.ByteArrayOutputStream()
                while (i < recs.size && (out.size() == 0 || out.size() + recs[i].size <= AV_MSG_BYTES))
                    out.write(recs[i++])
                try {
                    Mirror.sender(this@PebbleListenerService).sendDataToPebble(Schema.WATCH_UUID,
                        mapOf(Schema.KEY_AV_DATA to PebbleDictionaryItem.Bytes(out.toByteArray())), watches = null)
                } catch (e: Exception) {
                    LogStore.w(TAG, "Contact photos not sent: ${e.message}")
                    return
                }
            }
        }
    }

    // Build a protocol-agnostic deep-link target from a conversation JSON object.
    // Protocol = top-level "type" (e.g. "SMS"); address = the OTHER participant's
    // E.164 number (ID.number). For group chats (>1 non-me participant) we leave
    // the address blank so the deep link falls back gracefully (no single thread).
    private fun parseConvoTarget(c: JSONObject): ConvoTarget {
        val protocol = c.optString("type")   // "SMS", and later WhatsApp/etc via bridges
        var number = ""
        var others = 0
        val parts = c.optJSONArray("participants")
        if (parts != null) for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("isMe", false)) continue
            others++
            val id = p.optJSONObject("ID")
            val n = id?.optString("number") ?: ""
            if (n.isNotEmpty() && number.isEmpty()) number = n
        }
        // Only deep-link to a specific thread for 1:1 chats; blank address for
        // groups so DeepLink degrades to the inbox instead of the wrong thread.
        val address = if (others == 1) number else ""
        return ConvoTarget.from(protocol, address)
    }

    // The convo-list row preview. Prefer the newest message in our OWN store when
    // it's recent, because a bridge's list preview can lag a just-seen message —
    // notably an OUTGOING send (the phone's native app sends a text; libgm reflects
    // it in live events, which we store, but its conversation-list snippet keeps
    // showing the last INCOMING message until some later sync). The store is fed by
    // those live events + thread catch-up, so it's the authoritative "latest we've
    // seen." Recency-gate it so a stale store entry never masks a genuinely newer
    // bridge preview (e.g. a message that arrived while the service was dead lands
    // in the bridge list but not the store); that edge defers to the bridge and
    // self-heals on the next event. Outgoing gets a "You: " prefix, like a normal
    // messaging app's latest-preview line. A feed keeps the network's own preview
    // ("who: text"): its newest stored row is the newest post's first picture.
    private fun convoSnippet(c: JSONObject): String {
        val bridgePreview = c.optJSONObject("latestMessage")?.optString("displayContent")?.take(60) ?: ""
        val convoId = c.optString("conversationID")
        if (convoId.isEmpty() || Mirror.isFeed(convoId)) return bridgePreview
        val snip = MessageStore.newestSnippet(this@PebbleListenerService, convoId) ?: return bridgePreview
        // Normalize the store's microsecond timestamp to millis (mirrors LiveRouter's
        // tolerant read) and require it to be recent before overriding the bridge.
        val tsRaw = snip.timestampMicros
        val tsMillis = if (tsRaw > 1_000_000_000_000_000L) tsRaw / 1000 else tsRaw
        val ageMs = System.currentTimeMillis() - tsMillis
        val recent = tsMillis > 0 && ageMs in -120_000..SNIPPET_STORE_MAX_AGE_MS
        if (!recent || snip.text.isEmpty()) return bridgePreview
        val body = if (snip.isMe) "You: ${snip.text}" else snip.text
        return body.take(60)
    }

    // Avatar background color for a conversation row, in priority order:
    //   1) bridge-provided color (e.g. Google darkens per-contact at the source),
    //   2) the owning account's brand palette (the account defines its own colors
    //      in its own file; the shared selector picks a stable per-thread shade),
    //   3) the global default purple.
    private fun convoColor(c: JSONObject, owner: MessagingService?): String {
        c.optString("avatarHexColor").let { if (it.isNotEmpty()) return it }
        val parts = c.optJSONArray("participants")
        if (parts != null) for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("isMe", false)) {
                val col = p.optString("avatarHexColor")
                if (col.isNotEmpty()) return col
            }
        }
        owner?.avatarColorFor(c.optString("conversationID"))
            ?.let { if (it.isNotEmpty()) return it }
        return "#7E57C2"
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == Schema.WATCH_UUID) {
            Mirror.watchAppOpen = false
            // No convo can be open on a closed watchapp. Clearing stops the
            // debounced live repaint from streaming fresh spans at a dead app
            // (the flag was previously never cleared, so the last-open convo
            // kept getting pushed forever).
            Mirror.currentOpenConvoId = null
            LogStore.i(TAG, "Watchapp closed")
        }
    }
}
