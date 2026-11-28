package com.dano.mirrormsg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Process-lifetime home for incoming-message routing (store + notify + force-open).
 *
 * This logic used to live on MainActivity, which tied it to the UI: it only
 * survived backgrounding by accident (the Google bridge's EventSink retained the
 * activity). That accidental coupling is fine for Google but unsafe for Beeper,
 * whose ContentObserver pumps run on background threads with no activity at all.
 *
 * LiveRouter is a singleton (lives for the whole process) driven by
 * Mirror.appContext, so every network — Google's long-poll and Beeper's per-
 * network observers — calls the SAME pipeline the SAME way, foreground or not.
 *
 * Logic here is a verbatim relocation of the former MainActivity pipeline; only
 * the context source changed (applicationContext -> Mirror.appContext) and the
 * coroutine scope owner. The notify gates (recency, self-sent, held-image
 * staging, video) are unchanged.
 */
object LiveRouter {
    private const val TAG = "MirrorMsgSpike"

    // A live incoming message must be no older than this to wake the watch.
    // Re-synced archived threads re-deliver ancient messages that are "new"
    // to our store but not actually new — this rejects them. 2h: a text older
    // than that isn't a "buzz me now" event for a glanceable watch (you'll see
    // it when you open the thread), and it still rejects months-old re-syncs.
    private const val ROUTE_MAX_AGE_MS = 2 * 60 * 60 * 1000L

    // Message IDs we've already force-opened/notified on, so a later status stage
    // (delivered/read/image-attached) doesn't re-buzz. Bounded so it can't grow
    // without limit.
    private val imageForceOpened = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            val r = super.add(element)
            while (size > 200) remove(iterator().next())
            return r
        }
    }

    // Message IDs whose first stage we HELD (image still downloading). Only these
    // get later-stage handling, so a normal text's delivered/read status updates
    // never re-notify. Bounded like imageForceOpened.
    private val heldForImage = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            val r = super.add(element)
            while (size > 200) remove(iterator().next())
            return r
        }
    }

    /**
     * Public live-message ingest seam. The owning network's serviceId is the
     * explicit first param so the force-open path can switch the watch to the
     * RIGHT account (and so image serving via Mirror.activeService() resolves to
     * the owning adapter). Google's internal long-poll calls this with "google";
     * Beeper's per-network pumps (BeeperDiscordService / BeeperGoogleVoiceService)
     * call it with their own registry id ("discord" / "gvoice"), one message at a
     * time in timestamp order. serviceId MUST be the ServiceRegistry id, not a
     * Beeper protocol string.
     *
     * Safe to call from any thread, foreground or backgrounded: it depends only on
     * Mirror.appContext (set in MainActivity.onCreate, process-lifetime), never on
     * a live Activity.
     */
    fun ingestLiveMessage(serviceId: String, jsonPayload: String) =
        handleLiveMessage(jsonPayload, serviceId)

    private fun handleLiveMessage(jsonPayload: String, ownerServiceId: String = Mirror.activeServiceId) {
        val ctx = Mirror.appContext ?: run {
            LogStore.w(TAG, "ingestLiveMessage: no appContext yet — dropping message")
            return
        }
        try {
            val m = JSONObject(jsonPayload)
            val convoId = m.optString("conversationID")
            if (convoId.isEmpty()) return
            // Only store messages that are complete (have content or media).
            val infos = m.optJSONArray("messageInfo")
            if (infos == null || infos.length() == 0) return
            MessageStore.mergeOne(ctx, convoId, m)
            val changed = MessageStore.lastMergeChanged
            val added = MessageStore.lastMergeAdded

            // Google Messages embeds the full current reaction set (RCS tapbacks) on
            // every message update. Apply it to the source-agnostic ReactionStore as
            // a wholesale replace, so adds, changes, and removals all resolve from the
            // single embedded array. If it changed, re-push the thread so the badge
            // updates live — but do NOT fall into the notify path below; a reaction is
            // not a new message and must never buzz the wrist.
            if (ownerServiceId == "google") {
                val rMsgId = m.optString("messageID")
                if (rMsgId.isNotEmpty() && applyGoogleReactions(m, rMsgId)) {
                    LogStore.i(TAG, "Google reactions updated for msg ${rMsgId.takeLast(8)} in convo $convoId")
                    Mirror.invalidateConvoList()
                    PebbleListenerService.onLiveReaction(convoId, rMsgId)
                }
            }

            // Telegram embeds its reaction set (emoji + count) on each message; a
            // reaction change arrives as a message edit carrying the updated set. Apply
            // it as authoritative counts (adds/changes/removals all resolve). Like the
            // Google path: re-push the badge, never fall through to notify.
            if (ownerServiceId == "tgnative") {
                val rMsgId = m.optString("messageID")
                if (rMsgId.isNotEmpty() && m.has("reactions") && applyTelegramReactions(m, rMsgId)) {
                    LogStore.i(TAG, "Telegram reactions updated for msg ${rMsgId.takeLast(8)} in convo $convoId")
                    Mirror.invalidateConvoList()
                    PebbleListenerService.onLiveReaction(convoId, rMsgId)
                }
            }

            if (changed > 0) {
                LogStore.i(TAG, "Live message stored/updated for convo $convoId (added=$added changed=$changed)")
                Mirror.invalidateConvoList()
                // Suppress the fresh thread push while this convo is in its backfill
                // quiet window: the fetched older messages are re-emitted as MESSAGE
                // events for ~1-2s after a backfill fetch, and pushing the newest span
                // for them would jump the reader to the bottom mid-scroll. They're
                // already merged into the store, so they'll appear on the next span.
                val quietUntil = Mirror.backfillQuietUntil[convoId] ?: 0L
                if (System.currentTimeMillis() < quietUntil) {
                    LogStore.i(TAG, "Live push suppressed (backfill quiet window) for $convoId")
                } else {
                    // Always re-push the thread so a status progression that added
                    // media (image arriving in stages) reaches the watch — even when
                    // no brand-new messageID was added.
                    PebbleListenerService.onLiveMessage(convoId)
                }

                val sp = m.optJSONObject("senderParticipant")
                // Direction must NOT rely solely on senderParticipant.isMe: some
                // events (e.g. a re-synced archived thread, or certain outgoing
                // rows) arrive with NO senderParticipant block at all, so that
                // read defaults to false and an OUTGOING message gets mistaken for
                // incoming — which force-opened the enhanced view on our OWN sent
                // message. Treat it as ours if ANY of these say so.
                val statusForDir = m.optJSONObject("messageStatus")?.optString("status") ?: ""
                val isMe = (sp?.optBoolean("isMe", false) ?: false) ||
                           m.optBoolean("isMe", false) ||
                           m.optBoolean("fromMe", false) ||
                           statusForDir.startsWith("OUTGOING")
                val msgId = m.optString("messageID").ifEmpty {
                    m.optJSONObject("anotherMessageID")?.optString("messageID") ?: ""
                }
                val hasImage = messageHasImage(m)
                // Every stage gets a chance to contribute the media reference,
                // including stages that trigger no notification of their own.
                if (hasImage) rememberMediaRef(msgId, m)
                // RCS delivers a media message in stages: the FIRST stage has no
                // mediaContent yet and carries status INCOMING_AUTO_DOWNLOADING
                // (the phone is fetching the picture); a later stage flips to
                // INCOMING_COMPLETE with the image attached. A plain text arrives
                // already COMPLETE. So AUTO_DOWNLOADING is our "this is probably
                // an image" signal — used to hold the notification in hybrid mode
                // so a picture force-opens instead of first flashing a text alert.
                val status = m.optJSONObject("messageStatus")?.optString("status") ?: ""
                val downloadingMedia = status == "INCOMING_AUTO_DOWNLOADING"

                if (!isMe) {
                    // Ensure a deep-link target exists for this convo before we
                    // post the notification (streamConvos may not have run yet).
                    if (Mirror.convoTargets[convoId] == null) {
                        val num = sp?.optJSONObject("ID")?.optString("number") ?: ""
                        Mirror.convoTargets[convoId] =
                            ConvoTarget.from(ConvoTarget.SMS, num)
                    }
                    val sender = sp?.optString("firstName")
                        ?.ifEmpty { sp.optString("fullName") } ?: ""
                    val body = extractMessageText(m)
                    val groupName = m.optString("groupName")   // Discord server; "" for DMs/others
                    val silent = m.optBoolean("silent")         // Discord mute / notification level

                    // Recency gate: "added>0" only means new to OUR store, not
                    // newly sent in the real world. When the phone re-syncs an
                    // archived/old thread it re-delivers ancient messages that
                    // were never stored this session, so they pass added>0 and
                    // would force-open the watch for a "phantom" message that
                    // isn't actually new (and shows nothing new in Messages).
                    // A real incoming message's timestamp is within seconds of
                    // now; require it to be recent before we alert. The field is
                    // microseconds (16 digits) — fall back gracefully if absent.
                    val tsRaw = m.optString("timestamp").toLongOrNull() ?: 0L
                    val tsMillis = if (tsRaw > 1_000_000_000_000_000L) tsRaw / 1000 else tsRaw
                    val ageMs = System.currentTimeMillis() - tsMillis
                    val isRecent = tsMillis > 0 && ageMs in -120_000..ROUTE_MAX_AGE_MS
                    if (added > 0 && !isRecent) {
                        LogStore.i(TAG, "routeIncoming: SKIP stale convo $convoId (ageMs=$ageMs, ts=$tsMillis) — re-sync, not a new message")
                    }

                    // Route ONLY for a genuinely NEW incoming message — not for
                    // status updates of one we already alerted on (else an image's
                    // 2nd/3rd stage would re-buzz/re-open).
                    if (added > 0 && isRecent) {
                        if ((Mirror.notifyMode == Mirror.MODE_HYBRID
                             || Mirror.notifyMode == Mirror.MODE_NOTIFY)
                            && !hasImage && downloadingMedia
                            && msgId.isNotEmpty()) {
                            // Image still downloading: HOLD this first-stage alert.
                            // The completing stage posts/force-opens ONCE with the
                            // full body (so we don't get a bare "[Image]" first and
                            // a second notification when the caption/image lands).
                            heldForImage.add(msgId)
                            LogStore.i(TAG, "Holding notification for downloading media msg $msgId (mode=${Mirror.notifyMode})")
                        } else {
                            if (hasImage && msgId.isNotEmpty()) imageForceOpened.add(msgId)
                            routeIncoming(convoId, sender, body, groupName, silent, hasImage, ownerServiceId, msgId, NotificationImages.refFor(m))
                        }
                    } else if (msgId.isNotEmpty() && heldForImage.contains(msgId)
                               && !imageForceOpened.contains(msgId)) {
                        // Later stage of a message whose first stage we HELD (only
                        // these — a normal text's delivered/read updates never enter
                        // here, so they don't re-notify).
                        if (hasImage) {
                            imageForceOpened.add(msgId)   // handled once
                            if (Mirror.notifyMode == Mirror.MODE_HYBRID) {
                                LogStore.i(TAG, "Late image attach -> hybrid force-open for msg $msgId")
                                routeIncoming(convoId, sender, body, groupName, silent, true, ownerServiceId, msgId, NotificationImages.refFor(m))
                            } else {
                                // Notify mode: still a notification (routeIncoming's
                                // mode check prevents a force-open), but pass TRUE so
                                // the notification carries hasImage — "Open on watch"
                                // then lands on the enhanced view (straight to the
                                // picture) instead of the chat thread.
                                LogStore.i(TAG, "Late image attach -> notify [Image] for msg $msgId")
                                routeIncoming(convoId, sender, body, groupName, silent, true, ownerServiceId, msgId, NotificationImages.refFor(m))
                            }
                        } else if (status == "INCOMING_COMPLETE") {
                            // Completed with no media after all -> notify text once.
                            imageForceOpened.add(msgId)
                            LogStore.i(TAG, "Held media never arrived -> notify text for msg $msgId")
                            routeIncoming(convoId, sender, body, groupName, silent, false, ownerServiceId, msgId)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogStore.e(TAG, "handleLiveMessage failed", e)
        }
    }

    // Parse Google's embedded "reactions" array into actorKey(participantID) -> emoji
    // and hand the full set to ReactionStore.replaceAll. Each ReactionEntry is one
    // emoji (data.unicode) shared by a list of participantIDs. An absent/empty array
    // means the message currently has no reactions (replaceAll then clears any prior
    // ones — that's how a removal-to-empty resolves). Returns whether the store
    // changed.
    /**
     * Hydrate [ReactionStore] from the reactions embedded on every message of a
     * Google thread JSON. Google carries the full reaction set on each message,
     * but only the LIVE-message path ([applyGoogleReactions] at ingest) parsed it
     * — so a thread's PRE-EXISTING reactions never reached the store and didn't
     * show on the watch (the in-memory store is also empty after an app restart).
     * Call this on thread load for Google convos, before the thread is built.
     * Gated per-message on the presence of a "reactions" field, so it's inert for
     * networks that don't embed one; callers should still restrict it to Google.
     * Idempotent — each message is a wholesale replace.
     */
    fun hydrateGoogleReactions(threadJson: String) {
        try {
            val msgs = JSONObject(threadJson).optJSONArray("messages") ?: return
            for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                if (!m.has("reactions")) continue
                val msgId = m.optString("messageID")
                if (msgId.isNotEmpty()) applyGoogleReactions(m, msgId)
            }
        } catch (e: Exception) {
            LogStore.e(TAG, "hydrateGoogleReactions failed", e)
        }
    }

    private fun applyGoogleReactions(m: JSONObject, msgId: String): Boolean {
        val arr = m.optJSONArray("reactions")
        val map = LinkedHashMap<String, String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val emoji = entry.optJSONObject("data")?.optString("unicode") ?: ""
                if (emoji.isEmpty()) continue
                val parts = entry.optJSONArray("participantIDs") ?: continue
                for (j in 0 until parts.length()) {
                    val pid = parts.optString(j)
                    if (pid.isNotEmpty()) map[pid] = emoji
                }
            }
        }
        return ReactionStore.replaceAll(msgId, map)
    }

    /**
     * Hydrate [ReactionStore] from the reactions embedded on each message of a
     * Telegram thread JSON — the mirror of [hydrateGoogleReactions]. Telegram carries
     * the aggregate reaction set (emoji + count, no per-actor attribution) on every
     * message, so [ReactionStore.setCounts] synthesizes count keys. Call on thread
     * load for tgnative convos, before the thread is built. Idempotent.
     */
    fun hydrateTelegramReactions(threadJson: String) {
        try {
            val msgs = JSONObject(threadJson).optJSONArray("messages") ?: return
            for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                if (!m.has("reactions")) continue
                val msgId = m.optString("messageID")
                if (msgId.isNotEmpty()) applyTelegramReactions(m, msgId)
            }
        } catch (e: Exception) {
            LogStore.e(TAG, "hydrateTelegramReactions failed", e)
        }
    }

    // Apply one Telegram message's embedded "reactions":[{emoji,count}] as the
    // authoritative count set. An empty (but present) array clears the badge. Returns
    // whether the stored state changed.
    private fun applyTelegramReactions(m: JSONObject, msgId: String): Boolean {
        val arr = m.optJSONArray("reactions") ?: return false
        val counts = LinkedHashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val emoji = e.optString("emoji")
            val c = e.optInt("count", 0)
            if (emoji.isNotEmpty() && c > 0) counts[emoji] = c
        }
        return ReactionStore.setCounts(msgId, counts)
    }

    /**
     * Hydrate [ReactionStore] from the reactions embedded on each message of a Discord
     * thread JSON — identical wire shape to Telegram (aggregate emoji + count, no
     * per-actor attribution), so it defers to [ReactionStore.setCounts]. Call on thread
     * load for discord convos, before the thread is built. This is the historic +
     * server-sync path: every fetch carries the authoritative reaction snapshot, so a
     * reopen reconciles any live per-actor deltas back to the true counts. Idempotent.
     */
    fun hydrateDiscordReactions(threadJson: String) {
        try {
            val msgs = JSONObject(threadJson).optJSONArray("messages") ?: return
            for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                if (!m.has("reactions")) continue
                val msgId = m.optString("messageID")
                if (msgId.isNotEmpty()) applyDiscordReactions(m, msgId)
            }
        } catch (e: Exception) {
            LogStore.e(TAG, "hydrateDiscordReactions failed", e)
        }
    }

    // Apply one Discord message's embedded "reactions":[{emoji,count}] as the
    // authoritative count set (unicode only; the bridge skips custom emoji). An empty
    // (but present) array clears the badge. Returns whether the stored state changed.
    private fun applyDiscordReactions(m: JSONObject, msgId: String): Boolean {
        val arr = m.optJSONArray("reactions") ?: return false
        val counts = LinkedHashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val emoji = e.optString("emoji")
            val c = e.optInt("count", 0)
            if (emoji.isNotEmpty() && c > 0) counts[emoji] = c
        }
        return ReactionStore.setCounts(msgId, counts)
    }

    /** True if the convo's most-recent message carries a (still) image — the "Open on
     *  watch" view decision: land on the enhanced view for a picture so it shows big
     *  immediately, otherwise the chat view (message in context). */
    fun convoLatestHasImage(convoId: String): Boolean {
        val ctx = Mirror.appContext ?: return false
        return try {
            val msgs = JSONObject(MessageStore.loadAsThreadJson(ctx, convoId))
                .optJSONArray("messages") ?: return false
            if (msgs.length() == 0) return false
            // Thread JSON is oldest-first, so the last entry is the triggering message.
            val last = msgs.optJSONObject(msgs.length() - 1) ?: return false
            messageHasImage(last)
        } catch (_: Exception) { false }
    }

    private fun messageHasImage(m: JSONObject): Boolean {
        val infos = m.optJSONArray("messageInfo") ?: return false
        for (j in 0 until infos.length()) {
            val media = infos.getJSONObject(j).optJSONObject("mediaContent") ?: continue
            // Video isn't a still image — it can't render on the watch and must
            // not trigger Hybrid-mode force-open-to-enhanced-view. Only count
            // actual images here.
            val fmt = media.optString("format")
            val mime = media.optString("mimeType")
            if (fmt.startsWith("VIDEO") || mime.startsWith("video/")) continue
            return true
        }
        return false
    }

    /** Route an incoming message per the selected notify mode. */
    private fun routeIncoming(convoId: String, sender: String, body: String, groupName: String, silent: Boolean, hasImage: Boolean, ownerServiceId: String, msgId: String, mediaRef: NotificationImages.Ref? = null) {
        val ctx = Mirror.appContext ?: return
        // Per-message silence from the source's own settings (e.g. a muted Discord
        // server/channel). Stored already, browsable on the watch — just no alert.
        if (silent) {
            LogStore.i(TAG, "routeIncoming: message silenced by source settings — no notify/PN (still stored)")
            return
        }
        // Mute: the message is already in the MessageStore (browsable on the watch),
        // but a muted account never alerts — no notification, no force-open/PN.
        if (MuteState.isMuted(ctx, ownerServiceId)) {
            LogStore.i(TAG, "routeIncoming: $ownerServiceId is muted — no notify/PN (message still stored)")
            return
        }
        val mode = Mirror.notifyMode
        // ONE decision -> ONE delivery. Every notify-worthy message gets exactly
        // one of the two base behaviors (never both):
        //   IAN  -> forceOpenWatch (the watch branches on its own view state:
        //           same-chat list = quiet buzz / hold; anywhere else = detail view)
        //   Pebble notification -> alerting Android notification, Pebble mirrors
        //           it and does ALL the alerting; the watchapp stays passive.
        // MODE_FORCE ("In-app notification"): always IAN — including launching the
        //   watchapp when it's closed.
        // MODE_HYBRID: an image is ALWAYS an IAN (can't render in a Pebble
        //   notification — launches the app if closed, sub-option ignored); text
        //   follows the notification path, honoring hybridIanWhenOpen.
        // MODE_NOTIFY: Pebble notification always — unless notifyIanWhenOpen is on
        //   AND the watchapp is currently open, then IAN.
        // SAME-CHAT EXCEPTION (both notification-capable modes): a Pebble
        //   notification must never pop over the conversation the user is actively
        //   viewing. When the watch is open ON this convo's thread, deliver as an
        //   IAN instead — the watch's own same-chat branch turns that into the
        //   quiet double-buzz (follow at bottom / hold when scrolled up), i.e.
        //   exactly the IAN in-chat behavior. currentOpenConvoId is kept honest by
        //   KEY_REQ_OPEN (set on open), KEY_THREAD_CLOSED (cleared on back-out),
        //   and onAppClosed (cleared), so a user browsing the convo list still
        //   gets real Pebble notifications.
        val watchOpen = Mirror.watchAppOpen
        val sameChatOpen = watchOpen && convoId == Mirror.currentOpenConvoId
        val ian = when (mode) {
            Mirror.MODE_FORCE  -> true
            Mirror.MODE_HYBRID -> hasImage || sameChatOpen || (Mirror.hybridIanWhenOpen && watchOpen)
            else               -> sameChatOpen || (Mirror.notifyIanWhenOpen && watchOpen)   // MODE_NOTIFY
        }
        if (ian) {
            LogStore.i(TAG, "routeIncoming: IAN convo $convoId (mode=$mode, img=$hasImage, watchOpen=$watchOpen, sameChat=$sameChatOpen, owner=$ownerServiceId)")
            // MirrorMap or MirrorMov may be on the watch's screen. While the MirrorMsg
            // watchapp is closed, a force-open would close that map app, so MirrorMap
            // Dash is asked first to show the message as a card inside it (MapCard).
            // Without a Dash that can, exactly as before.
            if (!watchOpen && MapCard.canShow(ctx)) {
                offerCard(ctx, convoId, sender, body, groupName, hasImage, ownerServiceId, msgId, mediaRef)
            } else {
                forceOpenWatch(convoId, Schema.VIEW_ENHANCED, ownerServiceId)
            }
        } else {
            LogStore.i(TAG, "routeIncoming: pebble notification convo $convoId (mode=$mode, img=$hasImage, watchOpen=$watchOpen)")
            postNotification(ctx, convoId, sender, body, groupName, hasImage, ownerServiceId, msgId, mediaRef)
        }
    }

    /**
     * The Pebble notification for a message: an alerting Android notification that the
     * Pebble app mirrors (it does all the alerting; the watchapp stays passive). A
     * picture message, or a link with a picture behind it, waits a bounded time for its
     * picture first.
     */
    private fun postNotification(ctx: android.content.Context, convoId: String, sender: String, body: String,
                                 groupName: String, hasImage: Boolean, ownerServiceId: String, msgId: String,
                                 mediaRef: NotificationImages.Ref?) {
        // PebbleOS renders images attached to Android notifications, so a
        // picture message must carry its picture — otherwise our notification
        // shows "[Image]" where a Pebble-native one shows the photo, and the
        // user's rational move is to turn MirrorMsg off.
        //
        // The fetch can touch the network, so it runs off-thread with a bounded
        // wait: if the image isn't ready in time we post text-only rather than
        // sitting on the notification. Posting late-but-with-picture is fine;
        // never posting is not. (One post either way — a second post to update
        // the same id would not re-alert, so Pebble would keep showing the
        // text-only version anyway.)
        val ref = if (hasImage) mediaRef else null
        if (ref != null && ref.usable) {
            CoroutineScope(Dispatchers.IO).launch {
                val t0 = System.currentTimeMillis()
                // ONE budget covers both halves — waiting for the reference and
                // fetching the image. Timing them separately would let a slow
                // message stack 4s of waiting on top of 5s of downloading, and a
                // notification that lands 9 seconds late is its own bug.
                val pic = withTimeoutOrNull(NOTIF_IMAGE_TIMEOUT_MS) {
                    // If this stage had no downloadable reference, give the later
                    // stage a moment to bring one; otherwise we'd notify with the
                    // preview thumbnail and it renders as a blur.
                    val best = if (ref.mediaId.isEmpty()) (awaitMediaRef(msgId, convoId, ref) ?: ref) else ref
                    NotificationImages.bitmapFor(best, ownerServiceId, ctx)
                }
                val took = System.currentTimeMillis() - t0
                if (pic == null) {
                    // Distinguish "timed out" from "returned nothing" — they have
                    // completely different fixes and both used to look identical
                    // in the log (a cancelled coroutine logs nothing at all).
                    if (took >= NOTIF_IMAGE_TIMEOUT_MS)
                        LogStore.w(TAG, "Notification image TIMED OUT after ${took}ms for $msgId — posting text-only")
                    else
                        LogStore.i(TAG, "Notification image unavailable (${took}ms) for $msgId — posting text-only")
                } else {
                    LogStore.i(TAG, "Notification image ready in ${took}ms (${pic.width}x${pic.height}) for $msgId")
                }
                Notifier.postNewMessage(ctx, convoId, sender, body, ownerServiceId, msgId, groupName, pic)
                mediaRefs.remove(msgId)
            }
        } else if (LinkPictures.has(body)) {
            // Step 109: a link with a picture behind it (an X / Reddit / Imgur /
            // Instagram / Facebook post, a YouTube video, a GIF picker's link, a
            // picture file) carries that picture too, looked up as the watch's
            // chat would. Dan: a late notification with its picture beats a quick
            // one without, so this waits up to LINK_IMAGE_TIMEOUT_MS.
            CoroutineScope(Dispatchers.IO).launch {
                val t0 = System.currentTimeMillis()
                val pic = try {
                    withTimeoutOrNull(LINK_IMAGE_TIMEOUT_MS) {
                        LinkPictures.forText(ctx, body, LINK_IMAGE_TIMEOUT_MS - 1000L)
                    }
                } catch (e: Throwable) {
                    LogStore.w(TAG, "Link picture failed: ${e.javaClass.simpleName}")
                    null
                }
                val took = System.currentTimeMillis() - t0
                if (pic == null) LogStore.i(TAG, "Link picture unavailable (${took}ms) for $msgId: posting text-only")
                else LogStore.i(TAG, "Link picture ready in ${took}ms (${pic.width}x${pic.height}) for $msgId")
                Notifier.postNewMessage(ctx, convoId, sender, body, ownerServiceId, msgId, groupName, pic)
            }
        } else {
            Notifier.postNewMessage(ctx, convoId, sender, body, ownerServiceId, msgId, groupName)
        }
    }

    /**
     * The force-open [routeIncoming] chose, offered to MirrorMap Dash as a message card
     * first. Off the calling thread: Dash's answer takes up to
     * [MapCardRules.SHOW_TIMEOUT_MS].
     *  - shown / queued: the card is up on the map app (or will be). Done.
     *  - not_shown: a map app is on screen but can't show cards (an older one, or a watch
     *    without them): the Pebble notification, which shows over it without closing it.
     *  - anything else (no map app on screen, refused, failed, no answer): force-open
     *    exactly as before.
     * Muted and silenced messages never get here (routeIncoming returned for them).
     */
    private fun offerCard(ctx: android.content.Context, convoId: String, sender: String, body: String,
                          groupName: String, hasImage: Boolean, ownerServiceId: String, msgId: String,
                          mediaRef: NotificationImages.Ref?) {
        // This may still become a force-open, so the convo's debounced repaint is held
        // off from now, as forceOpenWatch does (armed before the coroutine; see there),
        // over the whole exchange. A force-open re-arms it for itself; otherwise only
        // this window is lifted when Dash has answered.
        val quiet = System.currentTimeMillis() + MapCardRules.SHOW_TIMEOUT_MS + FORCE_OPEN_QUIET_MS
        Mirror.forceOpenQuietUntil[convoId] = quiet
        val token = MapCard.tokens.add(convoId, ownerServiceId)
        // The card shows who it's from; with no name, the network's.
        val name = sender.ifBlank { ServiceRegistry.byId(ctx, ownerServiceId)?.displayName.orEmpty() }
        // The watch buzzes for a card as for the force-open it replaces: that always
        // buzzes once routeIncoming gets this far (the watch itself skips it in Quiet
        // Time), so nothing here turns it off.
        val buzz = true
        LogStore.i(TAG, "routeIncoming: card $token offered to Dash (owner=$ownerServiceId, img=$hasImage)")
        CoroutineScope(Dispatchers.IO).launch {
            val result = try {
                MapCard.show(ctx, token, name, body, MapCardRules.colorFor(ownerServiceId), buzz)
            } catch (e: Exception) {
                LogStore.w(TAG, "Card $token failed: ${e.javaClass.simpleName}")
                MapCardRules.RESULT_FAILED
            }
            when (MapCardRules.actionFor(result)) {
                MapCardRules.Action.DONE -> liftQuiet(convoId, quiet)
                MapCardRules.Action.NOTIFY -> {
                    liftQuiet(convoId, quiet)
                    LogStore.i(TAG, "Card $token not shown: pebble notification instead")
                    postNotification(ctx, convoId, sender, body, groupName, hasImage, ownerServiceId, msgId, mediaRef)
                }
                MapCardRules.Action.FORCE_OPEN -> {
                    LogStore.i(TAG, "Card $token: ${MapCardRules.logWord(result)}, force-open instead")
                    forceOpenWatch(convoId, Schema.VIEW_ENHANCED, ownerServiceId)
                }
            }
        }
    }

    /** Lifts the repaint hold [offerCard] armed, unless a force-open has re-armed it since. */
    private fun liftQuiet(convoId: String, armed: Long) {
        val m = Mirror.forceOpenQuietUntil
        synchronized(m) { if (m[convoId] == armed) m[convoId] = 0L }
    }

    /**
     * A message card on a map app was tapped (MapCard, Dash's "opened"): open that chat
     * in the MirrorMsg watchapp as a force-open does, on the chat view. With the map app
     * known ([app]), Back from that chat brings it back (MapCard.onThreadClosed).
     */
    fun openFromCard(convoId: String, ownerServiceId: String, app: String?) {
        LogStore.i(TAG, "Card opened: opening the chat on the watch (owner=$ownerServiceId, from=${app ?: "?"})")
        forceOpenWatch(convoId, Schema.VIEW_CHAT, ownerServiceId)
        if (app != null) MapCard.markReturn(convoId, app)
    }

    // Serializes IAN sequences. A burst of notify-worthy messages fires one
    // forceOpenWatch each (repeatable IANs are intended); without serialization
    // their list-stream + thread-push sequences would interleave on the wire.
    private val forceOpenMutex = Mutex()
    // How long the debounced live repaint stays suppressed while an IAN sequence
    // is in flight (safety ceiling — it's cleared early the moment the ENHANCED
    // push has gone out). Covers the watchapp-launch delay + account switch +
    // list stream + thread push with margin.
    private const val FORCE_OPEN_QUIET_MS = 6000L
    // How long a notification will wait on its picture before going out without
    // one. Inline images return almost immediately; a downloaded one is a network
    // round-trip, and past a few seconds a late notification is worse than a
    // picture-less one.
    // Total budget for producing a notification picture: waiting for the media
    // reference to arrive PLUS downloading and decoding it. Past this the
    // notification goes out text-only — late is worse than plain.
    // Step 109 (Dan): a late notification with its picture beats a quick one
    // without, so the budget is 15 s (was 7 s).
    private const val NOTIF_IMAGE_TIMEOUT_MS = 15000L
    // The same for a picture found behind a link (a post looked up first, then its
    // picture downloaded).
    private const val LINK_IMAGE_TIMEOUT_MS = 15000L
    // How long a picture notification waits for the media reference to show up.
    // RCS delivers an image message in stages: the stage that triggers the
    // notification carries only a preview (measured: a 275x246 photo arrives as a
    // 69x62 inline thumbnail with NO mediaID field at all), and the downloadable
    // reference appears on a later stage. Posting immediately means posting a
    // blur, so we wait — briefly — for the real reference.
    private const val MEDIA_REF_WAIT_MS = 3000L
    private const val MEDIA_REF_POLL_MS = 400L   // each poll reads the store file

    /** Best media reference seen for a message id, recorded as stages arrive.
     *  Bounded: image messages are rare relative to text, and entries are dropped
     *  once used or when the map grows past a sane ceiling. */
    private val mediaRefs = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, NotificationImages.Ref>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NotificationImages.Ref>?) = size > 64
        })

    /** Record a downloadable reference for [msgId] if this stage has one. Called
     *  for EVERY live message stage, so whichever stage carries the id wins. */
    private fun rememberMediaRef(msgId: String, m: JSONObject) {
        if (msgId.isEmpty()) return
        val ref = NotificationImages.refFor(m) ?: return
        if (ref.mediaId.isEmpty()) return          // preview-only stage: nothing to record
        val prev = mediaRefs[msgId]
        if (prev == null || prev.mediaId.isEmpty()) {
            mediaRefs[msgId] = ref
            LogStore.i(TAG, "Media reference arrived for $msgId")
        }
    }

    /**
     * Wait briefly for a downloadable media reference for [msgId], checking BOTH
     * places one can appear:
     *
     *  - the live-stage map, for sources that deliver the reference on a later
     *    MESSAGE event, and
     *  - MessageStore, which is where Google Messages actually puts it. Verified
     *    from logs: the notification-triggering stage carries no id field at all
     *    (only a preview thumbnail), no later live event brings one either, and
     *    yet the thread parser reads a 62-character mediaID from the store moments
     *    afterwards. MessageStore.merge treats gaining a mediaID as a "richer"
     *    version of the same message, so the id lands there when the fuller copy
     *    arrives — via a path that never passes through this router.
     *
     * Returns the original preview-only ref if nothing shows up in time; a small
     * picture beats none.
     */
    private suspend fun awaitMediaRef(msgId: String, convoId: String,
                                      fallback: NotificationImages.Ref?): NotificationImages.Ref? {
        if (msgId.isEmpty()) return fallback
        val ctx = Mirror.appContext
        val started = System.currentTimeMillis()
        val deadline = started + MEDIA_REF_WAIT_MS
        while (true) {
            mediaRefs[msgId]?.let {
                LogStore.i(TAG, "Media reference for $msgId from live stage after ${System.currentTimeMillis() - started}ms")
                return it
            }
            if (ctx != null) {
                storeRefFor(ctx, convoId, msgId)?.let {
                    LogStore.i(TAG, "Media reference for $msgId from MessageStore after ${System.currentTimeMillis() - started}ms")
                    return it
                }
            }
            if (System.currentTimeMillis() >= deadline) break
            delay(MEDIA_REF_POLL_MS)
        }
        LogStore.i(TAG, "No media reference for $msgId within ${MEDIA_REF_WAIT_MS}ms — using preview")
        return fallback
    }

    /** The stored copy of [msgId], if it has gained a downloadable reference. */
    private fun storeRefFor(ctx: android.content.Context, convoId: String, msgId: String): NotificationImages.Ref? {
        return try {
            val arr = MessageStore.load(ctx, convoId)
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val id = m.optString("messageID").ifEmpty {
                    m.optJSONObject("anotherMessageID")?.optString("messageID") ?: ""
                }
                if (id != msgId) continue
                val ref = NotificationImages.refFor(m)
                return if (ref != null && ref.mediaId.isNotEmpty()) ref else null
            }
            null
        } catch (e: Throwable) {
            LogStore.w(TAG, "MessageStore lookup failed for $msgId: ${e.message}")
            null
        }
    }

    /**
     * Deliver an IAN: open the watchapp (if closed), landing on the given view.
     * ownerServiceId is the account that OWNS this convo — the force-open switches
     * the watch's active account to it (so the rail + list + image serving all
     * agree), then pushes the thread. This is the multi-account IAN contract: an
     * in-app notification makes its owning account active, always.
     *
     * The convo's debounced live repaint is suppressed for the duration
     * (Mirror.forceOpenQuietUntil): it used to fire ~400ms in, BEFORE this
     * sequence's ENHANCED push — a VIEW_CHAT fresh push that consumed the watch's
     * new-message delta (killing the scrolled-up hold) and overwrote its thread
     * token (turning a should-be IAN into a same-chat quiet buzz). The ENHANCED
     * push streams the full thread itself, so nothing is lost by skipping it.
     */
    private fun forceOpenWatch(convoId: String, openView: Int = Schema.VIEW_CHAT,
                               ownerServiceId: String = Mirror.activeServiceId) {
        val ctx = Mirror.appContext ?: return
        // Another chat taking the screen ends a "back to the map" return (MapCard).
        MapCard.onForceOpen(convoId)
        // Arm the repaint suppression BEFORE launching the coroutine, so a repaint
        // debounce started by this same message can't slip through the gap.
        Mirror.forceOpenQuietUntil[convoId] = System.currentTimeMillis() + FORCE_OPEN_QUIET_MS
        CoroutineScope(Dispatchers.IO).launch {
            try {
                forceOpenMutex.withLock {
                    val sender = Mirror.sender(ctx)
                    Mirror.currentOpenConvoId = convoId
                    val wasOpen = Mirror.watchAppOpen
                    val launched = sender.startAppOnTheWatch(Schema.WATCH_UUID, watches = null)
                    LogStore.i(TAG, "forceOpenWatch startAppOnTheWatch -> $launched (view=$openView, owner=$ownerServiceId, wasOpen=$wasOpen)")
                    // The launch handshake only needs real time when the watchapp was
                    // closed; when it's already open a short settle keeps the IAN snappy.
                    delay(if (wasOpen) 250 else 1200)
                    // Hand the whole IAN sequence to the service: switch active account
                    // to the owner, refresh that account's list + rail, then push the
                    // enhanced frame carrying the convo's row index (KEY_PN_ROW).
                    PebbleListenerService.instance?.pushForceOpen(convoId, openView, ownerServiceId)
                }
            } catch (e: Exception) {
                LogStore.e(TAG, "forceOpenWatch failed", e)
            } finally {
                // ENHANCED push is out (or failed) — lift the suppression so later
                // stages of this message (image attach) repaint normally.
                Mirror.forceOpenQuietUntil[convoId] = 0L
            }
        }
    }

    private fun extractMessageText(m: JSONObject): String {
        val infos = m.optJSONArray("messageInfo") ?: return "New message"
        var text = ""
        var hasImage = false
        // Scan ALL parts (don't early-return): a message can carry both an image
        // and a text caption, and we want to show both.
        for (j in 0 until infos.length()) {
            val info = infos.getJSONObject(j)
            val c = info.optJSONObject("messageContent")?.optString("content") ?: ""
            if (c.isNotEmpty() && text.isEmpty()) text = c.take(120)
            if (info.optJSONObject("mediaContent") != null) hasImage = true
        }
        // First stage of an image hasn't attached the media yet but carries the
        // AUTO_DOWNLOADING status — still label it an image so notify mode shows
        // "[Image]" instead of "New message".
        if (!hasImage) {
            val status = m.optJSONObject("messageStatus")?.optString("status") ?: ""
            if (status == "INCOMING_AUTO_DOWNLOADING") hasImage = true
        }
        return when {
            // Image + caption: "[Image]" on its own line, caption beneath.
            hasImage && text.isNotEmpty() -> "[Image]\n$text"
            hasImage                      -> "[Image]"
            text.isNotEmpty()             -> text
            else                          -> "New message"
        }
    }
}
