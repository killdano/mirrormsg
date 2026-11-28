package com.dano.mirrormsg

import android.app.Activity
import android.content.Context
import android.content.Intent
import fi.mirrormsg.signalbridge.Bridge as SigBridge
import fi.mirrormsg.signalbridge.EventSink as SigEventSink
import fi.mirrormsg.signalbridge.Signalbridge
import java.io.File

/**
 * SignalService — native Signal as a linked device, via the signalbridge Go
 * package (signalmeow on the pure-Go libsignal backend) bound into
 * mirrorbridge.aar. Same adapter shape as WhatsAppService: this adapter OWNS the
 * single bridge (one signalmeow client per store, at filesDir/signal.db); the
 * pairing screen borrows it through the instance.
 *
 * Pairing puts the link QR on the WATCH: the bridge posts it as a picture message
 * in a "MirrorMsg \u00b7 Signal" setup thread, so the phone's own Signal can scan
 * it. Live receive, send, quoted replies, reactions, images, stickers, GIF clips,
 * video and chat photos (contacts' profile pictures, groups' icons) are wired;
 * link-and-sync history is the next milestone.
 *
 * The id is "signalnative", NOT "signal": Beeper's Signal twin owns "signal".
 * conflictGroup "signal" keeps the two mutually exclusive (same twin pattern as
 * "tgnative"/"telegram" and "whatsapp"/"wabeeper"). Users only see "Signal".
 *
 * Unlike WhatsApp/Telegram, the bridge emits our OWN sends as MESSAGE events
 * itself (Signal never loops them back), so there is no optimistic echo here.
 */
class SignalService : MessagingService, ReactionRemover {

    override val id = "signalnative"
    override val displayName = "Signal"
    override val conflictGroup = "signal"

    // Quoted replies + emoji reactions from the watch detail view (Signal
    // DataMessage.Quote / DataMessage.Reaction, addressed by the message id's
    // "<authorACI>|<timestamp>" pair).
    override val supportsReplyTo = true
    override val supportsReactionSend = true

    // Signal brand blues, kept under ~140 luma so the watch draws white initials.
    override val avatarPalette = listOf("#3A76F0", "#2C6BED", "#1B5FD9", "#2E5BBA")

    @Volatile private var sigBridge: SigBridge? = null
    @Volatile private var connected = false
    @Volatile private var pairingListener: ((String, String) -> Unit)? = null
    private val lock = Any()

    private val sink = object : SigEventSink {
        override fun onEvent(eventType: String, jsonPayload: String) {
            when (eventType) {
                "MESSAGE" -> LiveRouter.ingestLiveMessage(id, jsonPayload)
                "REACTION" -> {
                    ReactionStore.handleBridgeEvent(jsonPayload)
                    // A reaction rides on no message, so nothing re-pushes the
                    // thread; refresh an open Signal thread in place.
                    val open = Mirror.currentOpenConvoId
                    if (open != null && Mirror.activeServiceId == id) {
                        PebbleListenerService.instance?.refreshOpenThread(open)
                    }
                }
                "CONNECTED" -> {
                    connected = true
                    pairingListener?.invoke(eventType, jsonPayload)
                }
                "DISCONNECTED" -> {
                    connected = false
                    LogStore.w(TAG, "Signal disconnected: $jsonPayload")
                    pairingListener?.invoke(eventType, jsonPayload)
                }
                "LOGGED_OUT" -> {
                    connected = false
                    LogStore.w(TAG, "Signal session logged out server-side: $jsonPayload")
                    pairingListener?.invoke(eventType, jsonPayload)
                }
                "QR", "PAIRED", "PAIR_ERROR" -> {
                    if (eventType == "PAIR_ERROR") LogStore.w(TAG, "Signal pairing $eventType $jsonPayload")
                    else if (eventType != "QR") LogStore.i(TAG, "Signal pairing $eventType")
                    pairingListener?.invoke(eventType, jsonPayload)
                }
                "CHATS_UPDATED" -> Mirror.invalidateConvoList()
                "QUEUE_EMPTY" -> LogStore.i(TAG, "Signal offline queue drained")
                "DIAG" -> LogStore.i(TAG, jsonPayload)
                else -> { /* EVENT goType=… traffic — ignored in normal operation */ }
            }
        }
    }

    /** The single process-wide signalmeow bridge, created lazily at filesDir/signal.db. */
    fun ensureBridge(): SigBridge? {
        sigBridge?.let { return it }
        val c = Mirror.appContext ?: return null
        return synchronized(lock) {
            sigBridge ?: run {
                try {
                    val storePath = File(c.filesDir, STORE_FILE).absolutePath
                    Signalbridge.newBridge(storePath, sink).also { sigBridge = it }
                } catch (e: Throwable) {
                    LogStore.e(TAG, "Signal newBridge failed", e); null
                }
            }
        }
    }

    // ---- Identity & state --------------------------------------------------

    override fun isLoggedIn(): Boolean =
        try { ensureBridge()?.isPaired() ?: false } catch (e: Throwable) { false }

    // Our ACI: the actor key our own reactions carry when they come back from the
    // network (sync messages), so the watch-side optimistic badge reconciles.
    override fun selfActorKey(): String? =
        try { ensureBridge()?.selfID()?.takeIf { it.isNotEmpty() } } catch (_: Throwable) { null }

    override fun accountLabel(): String =
        try {
            val b = ensureBridge() ?: return ""
            val n = b.selfNumber()
            if (n.isNotEmpty()) n else if (b.isLinking()) "Linking\u2026" else ""
        } catch (_: Throwable) { "" }

    // ---- Lifecycle ---------------------------------------------------------

    override fun connectIfPossible(): Boolean {
        val b = ensureBridge() ?: return false
        return try {
            if (!b.isPaired()) return false
            if (connected) return true
            b.connect()
            true
        } catch (e: Throwable) {
            LogStore.w(TAG, "Signal connect: ${e.message}")
            connected
        }
    }

    override fun disconnect() {
        try { sigBridge?.disconnect() } catch (_: Throwable) {}
        connected = false
    }

    override fun logout(context: Context) {
        // Unlinks server-side (best effort), then drops the store so the next
        // launch is unpaired.
        try { sigBridge?.logout() } catch (e: Throwable) { LogStore.w(TAG, "Signal unlink: ${e.message}") }
        try { sigBridge?.close() } catch (_: Throwable) {}
        sigBridge = null
        connected = false
        for (n in listOf(STORE_FILE, "$STORE_FILE-wal", "$STORE_FILE-shm", "$STORE_FILE-journal",
                         "$STORE_FILE.chats.json", "$STORE_FILE.names.json", "$STORE_FILE.media.json")) {
            try { File(context.filesDir, n).delete() } catch (_: Throwable) {}
        }
        try { File(context.filesDir, "$STORE_FILE.msgs").deleteRecursively() } catch (_: Throwable) {}
        LogStore.i(TAG, "Signal logged out (local session cleared)")
    }

    // ---- Login -------------------------------------------------------------

    override fun startLogin(activity: Activity) {
        activity.startActivity(Intent(activity, SignalPairActivity::class.java))
    }

    // ---- Pairing-screen hooks (used by SignalPairActivity) -----------------

    fun setPairingListener(l: ((String, String) -> Unit)?) { pairingListener = l }
    fun pairedNow(): Boolean =
        try { ensureBridge()?.let { it.isPaired() && !it.isLinking() } ?: false } catch (_: Throwable) { false }
    fun linkingNow(): Boolean = try { ensureBridge()?.isLinking() ?: false } catch (_: Throwable) { false }
    fun connectedNow(): Boolean = connected

    /**
     * Starts a link attempt (the bridge posts the QR into the setup thread, which
     * LiveRouter pushes to the watch like any picture message), or just connects
     * an existing session. Call off the main thread. Safe to call repeatedly: the
     * bridge ignores a second call while an attempt is live.
     */
    fun beginPairing(): Boolean {
        val b = ensureBridge() ?: return false
        if (connected) { pairingListener?.invoke("CONNECTED", "{}"); return true }
        return try { b.connect(); true } catch (e: Throwable) {
            LogStore.w(TAG, "Signal beginPairing: ${e.message}"); connected
        }
    }

    /** Abandon a live link attempt (keeps any stored session). */
    fun cancelPairing() {
        if (!linkingNow()) return
        try { sigBridge?.disconnect() } catch (_: Throwable) {}
    }

    // ---- Universal data surface --------------------------------------------

    override fun listConversations(count: Int): String =
        try {
            ensureBridge()?.listConversations(count.toLong()) ?: "{\"conversations\":[]}"
        } catch (e: Throwable) {
            LogStore.e(TAG, "Signal listConversations failed", e)
            "{\"conversations\":[]}"
        }

    override fun fetchMessages(conversationId: String, count: Long, cursor: String): String {
        val resp = try {
            ensureBridge()?.fetchMessages(conversationId, count, cursor) ?: "{\"messages\":[]}"
        } catch (e: Throwable) {
            LogStore.e(TAG, "Signal fetchMessages failed", e)
            Mirror.backfillExhausted[conversationId] = true
            return "{\"messages\":[]}"
        }
        // Same cursor contract as WhatsApp: a (beforeTs, beforeId) anchor means
        // older messages remain in the local index; its absence is the clean stop.
        try {
            val cur = org.json.JSONObject(resp).optJSONObject("cursor")
            if (cur != null && cur.optString("beforeTs").isNotEmpty()) {
                Mirror.backfillCursor[conversationId] = cur.toString()
                Mirror.backfillExhausted[conversationId] = false
            } else {
                Mirror.backfillExhausted[conversationId] = true
            }
        } catch (_: Throwable) {
            Mirror.backfillExhausted[conversationId] = true
        }
        return resp
    }

    // ---- New chat (People.kt) -------------------------------------------------
    // The contacts synced from the primary phone. A person's id is their 1:1 chat's
    // (their ACI); the first send makes the chat.
    override val supportsNewChat = true

    override fun listPeople(): String =
        ensureBridge()?.listPeople() ?: "{\"people\":[]}"

    override fun openPerson(personId: String): String =
        org.json.JSONObject().put("conversationID", personId).toString()

    override fun sendMessage(conversationId: String, text: String, replyToken: String): String =
        try {
            // The bridge indexes + emits our own send as a MESSAGE event, so the
            // thread refreshes through the normal live path — no optimistic echo.
            ensureBridge()?.sendMessage(conversationId, text)
                ?: "{\"error\":\"signal bridge unavailable\"}"
        } catch (e: Throwable) {
            LogStore.e(TAG, "Signal send failed", e)
            "{\"error\":\"${e.message}\"}"
        }

    // Quoted reply. The bridge indexes + emits our own send (tagged replyTo) as a
    // MESSAGE event, so no optimistic echo here either.
    override fun sendReplyTo(
        conversationId: String, targetMessageId: String,
        targetFromMe: Boolean, text: String
    ): String =
        try {
            ensureBridge()?.sendReply(conversationId, targetMessageId, text)
                ?: "{\"error\":\"signal bridge unavailable\"}"
        } catch (e: Throwable) {
            LogStore.e(TAG, "Signal sendReplyTo failed", e)
            "{\"error\":\"${e.message}\"}"
        }

    // Emoji reaction (empty emoji removes ours). The shared send path applies the
    // optimistic badge once for every network, so nothing is ingested here.
    override fun sendReaction(
        conversationId: String, targetMessageId: String,
        targetFromMe: Boolean, emoji: String
    ): String =
        try {
            ensureBridge()?.sendReaction(conversationId, targetMessageId, emoji)
                ?: "{\"error\":\"signal bridge unavailable\"}"
        } catch (e: Throwable) {
            LogStore.e(TAG, "Signal sendReaction failed", e)
            "{\"error\":\"${e.message}\"}"
        }

    // The same emoji sent again takes it back. Signal names the reaction being
    // removed, so the bridge sends a removal of `emoji` (or the one it last sent
    // there, if that differs by a variation selector).
    override fun removeReaction(
        conversationId: String, targetMessageId: String,
        targetFromMe: Boolean, emoji: String
    ): String =
        try {
            ensureBridge()?.removeReaction(conversationId, targetMessageId, emoji)
                ?: "{\"error\":\"signal bridge unavailable\"}"
        } catch (e: Throwable) {
            LogStore.e(TAG, "Signal removeReaction failed", e)
            "{\"error\":\"${e.message}\"}"
        }

    override fun replyTokenFor(conversationId: String): String = ""

    /**
     * "Open in app" target for [DeepLink]: a signal.me link that opens this DM in
     * Signal, or "" for groups / contacts whose number we don't know — then
     * DeepLink should just launch [SIGNAL_PACKAGE].
     */
    fun deepLinkUri(convoId: String): String =
        try { ensureBridge()?.chatDeepLink(convoId) ?: "" } catch (_: Throwable) { "" }

    // ---- Chat photos (NOTE-to-network-Claudes-photos) ----------------------

    // The bridge names a chat's photo "p:<ACI>|<avatar path>" (the contact's
    // profile picture) or "g:<group id>|<avatar path>" (the group's own icon).
    // Signal uploads every new picture to a new path, so the name changes with
    // the photo. With a row (packing the chat list) it's read straight from the
    // row listConversations built: no network. Without one (a notification, off
    // the main thread) the bridge may look the chat up on Signal first.
    override fun avatarId(conversationId: String, row: org.json.JSONObject?): String? {
        if (row != null && row.has("avatarKey")) {
            return row.optString("avatarKey").takeIf { it.isNotEmpty() }
        }
        return try {
            ensureBridge()?.avatarKey(conversationId, row == null)?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    // The picture, decrypted (JPEG/PNG as its owner uploaded it). null = no
    // picture under that name any more (the bridge looks the chat up again, so a
    // new name follows). Throws when it can't be had right now (not connected,
    // network), so the phone tries again in 2 minutes.
    override fun avatarBytes(avatarId: String): ByteArray? {
        val b = ensureBridge() ?: throw IllegalStateException("Signal bridge unavailable")
        return b.avatarBytes(avatarId)?.takeIf { it.isNotEmpty() }
    }

    // Still OR animation, decided from the bytes by the shared mediaroute encoder.
    // "" on failure = fall back to the still path.
    override fun downloadMediaForWatch(
        mediaId: String, decryptionKeyB64: String,
        maxW: Int, maxH: Int, maxBytes: Int, quality: Int,
        fpsFloor: Int, maxFrames: Int, gifBytes: Int, startRung: Int
    ): String =
        try {
            ensureBridge()?.downloadMediaForWatch(
                mediaId, maxW.toLong(), maxH.toLong(), maxBytes.toLong(), quality.toLong(),
                fpsFloor.toLong(), maxFrames.toLong(), gifBytes.toLong(), startRung.toLong()
            ) ?: ""
        } catch (e: Throwable) {
            LogStore.w(TAG, "Signal downloadMediaForWatch failed: ${e.message}")
            ""
        }

    // Video (incl. Signal's GIF-flagged mp4 clips): decrypted to a file for the
    // phone's streamer. The handle lives in the bridge's media store by message id.
    override fun downloadMediaToFile(mediaId: String, decryptionKeyB64: String, path: String): Long =
        try {
            ensureBridge()?.downloadMediaToFile(mediaId, path) ?: -1L
        } catch (e: Throwable) {
            LogStore.e(TAG, "Signal downloadMediaToFile failed", e)
            -1L
        }

    override fun downloadImage(
        mediaId: String, decryptionKeyB64: String,
        maxW: Int, maxH: Int, maxBytes: Int, quality: Int
    ): String =
        try {
            // Image attachments, stickers (WebP flattened to PNG in the bridge)
            // and the setup thread's link QR, all by message id.
            ensureBridge()?.downloadImageForWatch(
                mediaId, maxW.toLong(), maxH.toLong(), maxBytes.toLong(), quality.toLong()
            ) ?: "{\"error\":\"signal bridge unavailable\"}"
        } catch (e: Throwable) {
            LogStore.e(TAG, "Signal downloadImage failed", e)
            "{\"error\":\"${e.message}\"}"
        }

    override fun processInlineImage(
        mediaDataB64: String,
        maxW: Int, maxH: Int, maxBytes: Int, quality: Int
    ): String = "{\"error\":\"signal has no inline images\"}"

    companion object {
        private const val TAG = "MirrorMsgSpike"
        private const val STORE_FILE = "signal.db"
        const val SIGNAL_PACKAGE = "org.thoughtcrime.securesms"
    }
}
