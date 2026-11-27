package com.dano.mirrormsg

import com.dano.mirrormsg.feedback.FeedbackFlavor
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Switch
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import android.widget.ScrollView
import android.widget.FrameLayout
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.provider.Settings
import fi.mirrormsg.mirrorbridge.Bridge
import fi.mirrormsg.mirrorbridge.EventSink
import fi.mirrormsg.mirrorbridge.Mirrorbridge
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.client.PebbleSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * MirrorMsg main activity.
 *
 * Login: built-in WebView (honest UA passes Google's gate) + CookieManager
 * harvest -> libgm. Pair once, persist session, restore on relaunch.
 * Pushes the conversation list to the watch on button tap.
 * PebbleListenerService handles tap-in requests from the watch.
 * Shared state (bridge, convoOrder, sender) lives in Mirror.
 */
class MainActivity : Activity() {

    private var webView: WebView? = null
    @Volatile private var paired = false
    private var statusView: TextView? = null
    private var homeShowing = false

    // Each component owns its own coroutine scope.
    private val pebbleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val prefs by lazy { getSharedPreferences("mirrormsg", MODE_PRIVATE) }

    companion object {
        private const val TAG = "MirrorMsgSpike"
        private const val PREF_SESSION = "session"
        // False only when the process is freshly (re)created — i.e. a cold start /
        // launch-after-complete-close, NOT a foreground resume or config-change
        // Activity recreation (companion statics survive those within one process).
        // Used to reset sub-account "SHOW ALL" lamps to their dim/hidden default on
        // cold start, so the lamp honestly reflects that late-arriving channels/folders
        // aren't loaded yet (tap SHOW ALL reveals them once they arrive).
        private var coldStartHandled = false
        private const val MAX_CONVOS = 15
        private const val SNIPPET_MAX = 60

        private const val LOGIN_URL =
            "https://accounts.google.com/AccountChooser?continue=https://messages.google.com/web/config"

        private val COOKIE_URLS = listOf(
            "https://accounts.google.com",
            "https://messages.google.com",
            "https://www.google.com"
        )
    }

    private val sink = object : EventSink {
        override fun onEvent(eventType: String, jsonPayload: String) {
            when (eventType) {
                "PAIR_EMOJI" -> {
                    LogStore.w(TAG, ">>> Confirm THIS emoji on your phone: $jsonPayload")
                    val emoji = try { JSONObject(jsonPayload).optString("emoji") } catch (e: Exception) { "" }
                    runOnUiThread { showPairingEmoji(emoji) }
                }
                "MESSAGE"      -> {
                    LogStore.i(TAG, "MESSAGE $jsonPayload")
                    // Google's native long-poll: owner is always "google".
                    ingestLiveMessage("google", jsonPayload)
                }
                "CONVERSATION" -> {
                    LogStore.i(TAG, "CONVERSATION $jsonPayload")
                    // Conversation list changed — invalidate cached list.
                    Mirror.invalidateConvoList()
                    // Capture the account's own number from the live event (it's
                    // in simCard.SIMData and arrives regardless of watch state,
                    // unlike the push path which needs a reachable watch).
                    if (Mirror.accountNumber.isEmpty()) {
                        try {
                            val num = JSONObject(jsonPayload)
                                .optJSONObject("simCard")
                                ?.optJSONObject("SIMData")
                                ?.optString("formattedPhoneNumber") ?: ""
                            if (num.isNotEmpty()) Mirror.setAccountNumber(this@MainActivity, num)
                        } catch (e: Exception) { /* ignore parse miss */ }
                    }
                }
                "LOGGED_OUT"   -> {
                    LogStore.e(TAG, "Session died — clearing saved session.")
                    prefs.edit().remove(PREF_SESSION).apply()
                }
                else -> {
                    LogStore.d(TAG, "$eventType $jsonPayload")
                    // The Settings event (gmproto.Settings) fires on every connect
                    // and carries the account's own number. It arrives as protobuf
                    // text (not JSON), so pull formattedPhoneNumber with a regex.
                    // This is the most reliable capture point: the push path may
                    // serve cached convos (skipping listConversations) and a
                    // CONVERSATION event isn't always emitted.
                    if (Mirror.accountNumber.isEmpty() && jsonPayload.contains("formattedPhoneNumber")) {
                        val m = Regex("formattedPhoneNumber:\\\\?\"([^\"\\\\]+)").find(jsonPayload)
                        val num = m?.groupValues?.getOrNull(1) ?: ""
                        if (num.isNotEmpty()) {
                            Mirror.setAccountNumber(this@MainActivity, num)
                            LogStore.i(TAG, "Captured account number: $num")
                        }
                    }
                }
            }
        }
    }

    /**
     * Live-message ingest. Forwards to LiveRouter (the process-lifetime home for
     * store + notify + force-open routing), which is safe to call whether or not
     * this Activity is alive. Google's long-poll EventSink calls this with
     * "google"; Beeper's per-network pumps call LiveRouter.ingestLiveMessage
     * directly with their own registry id. Kept here as a thin forwarder so the
     * existing sink call site is unchanged.
     */
    fun ingestLiveMessage(serviceId: String, jsonPayload: String) =
        LiveRouter.ingestLiveMessage(serviceId, jsonPayload)

    private val heapHandler = Handler(Looper.getMainLooper())
    private val heapRunnable = object : Runnable {
        override fun run() {
            LogStore.logHeap("periodic")
            heapHandler.postDelayed(this, 120_000L) // every 2 minutes
        }
    }
    private fun startHeapTimer() {
        heapHandler.removeCallbacks(heapRunnable)
        heapHandler.postDelayed(heapRunnable, 120_000L)
        LogStore.logHeap("startup")
    }

        override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mirror.appContext = applicationContext
        Mirror.loadSettings(this)
        // Anchor the process. Before this, every network connection was
        // established here and nothing kept the process alive afterwards, so
        // Android reclaimed it after a few idle hours and notifications stopped
        // until the app was reopened. Idempotent — a no-op if already running
        // (e.g. started at boot, which is the normal case).
        MirrorForegroundService.start(this)
        LogStore.init(this)
        ServiceRegistry.ensureLoaded(this)   // discover installed network modules
        // On a COLD start (process (re)created), reset every sub-account "SHOW ALL"
        // lamp to its dim/hidden default (0). Servers/folders (Discord, Telegram)
        // arrive late after a force-close or update recovery, so a persisted green/
        // yellow lamp would claim they're shown while the list is still empty. Dim =
        // "they exist but are hidden"; tapping SHOW ALL reveals them once loaded. Runs
        // once per process (coldStartHandled survives Activity recreation, resets on
        // process death), so a foreground resume / rotation doesn't clobber the user's
        // choice mid-session.
        if (!coldStartHandled) {
            coldStartHandled = true
            for (svc in ServiceRegistry.available(this)) {
                if (svc.id == "tgnative" || svc.id == "discord") setSubsShown(svc.id, 0)
            }
            LogStore.i(TAG, "Cold start: reset sub-account SHOW ALL lamps to hidden")
        }
        activeTab = 0                        // land on Networks
        LogStore.i(TAG, "App started")
        startHeapTimer()
        // Android 13+ needs runtime permission to post notifications.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9001)
            }
        }
        val saved = Vault.getString(prefs, PREF_SESSION)   // sealed in the Keystore
        if (saved.isNotEmpty()) {
            LogStore.i(TAG, "Found saved session — restoring, skipping login.")
            showHome("Restoring saved session\u2026")
            thread(name = "bridge-restore") { restoreAndConnect(saved) }
        } else {
            // Logged out of Google, but the app is serverless/unlocked and the other
            // services (Facebook, Discord, Telegram, and all Beeper networks) have
            // their OWN saved sessions. Connect them regardless of the Google session —
            // otherwise every Beeper network stays read-only (no live ContentObserver/
            // poll, no notifications), since those register inside connectIfPossible.
            LogStore.i(TAG, "No saved Google session — connecting other registered services.")
            showHome("All accounts logged out")
            thread(name = "svc-restore") {
                connectRegisteredServices()
                runOnUiThread { reportConnectionStatus() }
            }
        }
        handleNotificationLaunch(intent)
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        handleNotificationLaunch(newIntent)
    }

    override fun onResume() {
        super.onResume()
        // Re-render so the notification-permission checkmark reflects any change
        // made in system settings, and day/night theme stays current.
        if (homeShowing) renderHome()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (homeShowing) renderHome()   // system day/night switch
    }

    /**
     * If we were opened from a message notification ("Open on phone"), force-open
     * the watchapp to the triggering conversation, on the account that owns it (the
     * notification's EXTRA_SERVICE_ID, as OpenWatchReceiver does: never the active one,
     * or the thread would open under whichever account the watch happened to show).
     */
    private fun handleNotificationLaunch(launchIntent: Intent?) {
        // A call from the watch couldn't start for want of a permission: its notification
        // opens this to ask for them.
        if (launchIntent?.getBooleanExtra(Calls.EXTRA_SETUP, false) == true) {
            launchIntent.removeExtra(Calls.EXTRA_SETUP)
            if (Mirror.watchCalls) requestCallPermissions()
            return
        }
        if (launchIntent?.getBooleanExtra(Notifier.EXTRA_FROM_NOTIF, false) != true) return
        val convoId = launchIntent.getStringExtra(Notifier.EXTRA_CONVO_ID) ?: return
        val owner = launchIntent.getStringExtra(Notifier.EXTRA_SERVICE_ID)
        LogStore.i(TAG, "Opened from notification for convo $convoId (owner=${owner ?: "?"}) — opening watchapp")
        // startAppOnTheWatch is a suspend fun, so run it in a coroutine.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val sender = Mirror.sender(this@MainActivity)
                // Set the open-convo target so the watch lands on this thread.
                Mirror.currentOpenConvoId = convoId
                val launched = sender.startAppOnTheWatch(Schema.WATCH_UUID, watches = null)
                LogStore.i(TAG, "startAppOnTheWatch -> $launched")
                // Give the watchapp a moment to boot, then push this thread: on its own
                // account (switching to it) when the notification says which.
                kotlinx.coroutines.delay(1200)
                val pls = PebbleListenerService.instance
                if (owner != null) pls?.pushForceOpen(convoId, Schema.VIEW_CHAT, owner)
                else pls?.pushThreadUpdate(convoId)
            } catch (e: Exception) {
                LogStore.e(TAG, "notification watch-open failed", e)
            }
        }
    }

    // ---- Theming (violet accent from app icon; day/night by system) -------

    private data class Palette(
        val accent: Int, val bg: Int, val card: Int, val cardBorder: Int,
        val text: Int, val subtext: Int, val headerText: Int, val onAccent: Int)

    private fun palette(): Palette {
        val accent = Color.parseColor("#7C4DFF")       // violet accent (icon)
        val night = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) Palette(
            accent = accent,
            bg = Color.parseColor("#0E0E10"),
            card = Color.parseColor("#1C1C1F"),
            cardBorder = Color.parseColor("#2A2A2E"),
            text = Color.parseColor("#F2F2F5"),
            subtext = Color.parseColor("#9A9AA2"),
            headerText = Color.WHITE,
            onAccent = Color.WHITE)
        else Palette(
            accent = accent,
            bg = Color.parseColor("#F5F4F8"),
            card = Color.WHITE,
            cardBorder = Color.parseColor("#E2E0E8"),
            text = Color.parseColor("#1A1A1E"),
            subtext = Color.parseColor("#6B6B73"),
            headerText = Color.WHITE,
            onAccent = Color.WHITE)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(28)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(fill); cornerRadius = dp(radius).toFloat()
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }

    private var activeTab = 0   // 0=Networks, 1=Settings, 2=Status; launch default chosen in onCreate by license state

    // ---- Pairing emoji confirmation screen --------------------------------
    // Shown while startGaiaPairing() blocks. The emoji to tap is in the Google
    // Messages app on the phone (Device pairing prompt), NOT in this WebView.
    private fun showPairingEmoji(emoji: String) {
        homeShowing = false
        webView = null
        val p = palette()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(p.bg)
            setPadding(dp(32), dp(48), dp(32), dp(48))
        }
        root.addView(TextView(this).apply {
            text = "Confirm pairing"; textSize = 22f; setTextColor(p.text)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = if (emoji.isNotEmpty()) emoji else "\uD83D\uDD17"
            textSize = 72f; gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        })
        root.addView(TextView(this).apply {
            text = "Open Google Messages on your phone, find the device-pairing prompt, and tap this emoji."
            textSize = 16f; setTextColor(p.subtext); gravity = Gravity.CENTER
        })
        setContentView(root)
    }

    // ---- Home screen (two-tab: Settings | Accounts) -----------------------

    private fun showHome(initial: String) {
        statusMsg = initial
        renderHome()
    }

    private var statusMsg: String = ""
    private var homeScroller: ScrollView? = null
    private var lastRenderedTab: Int = -1

    private fun renderHome() {
        homeShowing = true
        // Preserve scroll across a same-tab rebuild — toggling an account re-renders
        // the whole tab, and without this the user is yanked back to the top. A tab
        // *switch* deliberately starts fresh at the top.
        val prevScrollY = if (activeTab == lastRenderedTab) (homeScroller?.scrollY ?: 0) else 0
        val p = palette()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }

        // Header band (accent).
        val statusBar = statusBarHeight()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.accent)
            // Accent band extends behind the status bar (takes over the top bar);
            // text starts just below it, matching MirrorMap's tighter spacing.
            setPadding(dp(20), statusBar + dp(6), dp(20), dp(18))
        }
        header.addView(TextView(this).apply {
            text = "MirrorMsg"; textSize = 26f; setTextColor(p.headerText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "Messages on your Pebble"; textSize = 14f
            setTextColor(p.headerText); alpha = 0.85f
        })
        root.addView(header)

        // Tab row: Networks (left) | Settings (right).
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        // Test builds add a 4th tab, Feedback, which opens its own screen
        // (FeedbackFlavor; release builds don't have it). Four tabs get a
        // smaller font and padding so they fit a narrow screen on one line.
        val withFeedback = FeedbackFlavor.available
        fun tab(label: String, index: Int): TextView = TextView(this).apply {
            text = label; textSize = if (withFeedback) 14f else 15f; gravity = Gravity.CENTER
            val side = if (withFeedback) 4 else 12
            setPadding(dp(side), dp(14), dp(side), dp(14))
            if (withFeedback) isSingleLine = true
            val selected = activeTab == index
            setTextColor(if (selected) p.onAccent else p.text)
            background = rounded(if (selected) p.accent else p.card, 10,
                if (selected) 0 else 1, p.cardBorder)
            setOnClickListener { activeTab = index; renderHome() }
        }
        val settingsTab = tab("Settings", 1)
        val accountsTab = tab("Networks", 0)
        val statusTab = tab("Status", 2)
        val gap = if (withFeedback) 6 else 10
        tabRow.addView(accountsTab, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabRow.addView(android.view.View(this), LinearLayout.LayoutParams(dp(gap), 1))
        tabRow.addView(statusTab, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabRow.addView(android.view.View(this), LinearLayout.LayoutParams(dp(gap), 1))
        tabRow.addView(settingsTab, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (withFeedback) {
            val feedbackTab = tab("Feedback", -1).apply {
                setOnClickListener { FeedbackFlavor.open(this@MainActivity) }
            }
            tabRow.addView(android.view.View(this), LinearLayout.LayoutParams(dp(gap), 1))
            tabRow.addView(feedbackTab, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabRow)

        // Tab content in a scroll view.
        val scroller = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        when (activeTab) {
            1 -> buildSettingsTab(content, p)
            2 -> buildStatusTab(content, p)
            else -> buildAccountsTab(content, p)
        }
        scroller.addView(content)
        root.addView(scroller)

        setContentView(root)
        homeScroller = scroller
        lastRenderedTab = activeTab
        // Restore after layout so scrollTo has a measured child to move within.
        if (prevScrollY > 0) scroller.post { scroller.scrollTo(0, prevScrollY) }
    }

    private fun sectionLabel(parent: LinearLayout, p: Palette, text: String, first: Boolean = false) {
        parent.addView(TextView(this).apply {
            this.text = text.uppercase(); textSize = 12f
            setTextColor(p.subtext); setPadding(dp(4), dp(if (first) 4 else 20), 0, dp(8))
            letterSpacing = 0.08f
        })
    }

    private fun card(p: Palette): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(p.card, 12, 1, p.cardBorder)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    // ---- Settings tab -----------------------------------------------------

    private fun buildSettingsTab(c: LinearLayout, p: Palette) {
        // QUICK REPLIES — opens the editor. (Above Watch settings.) "On new
        // message" used to lead this tab; it now lives on the Status tab, between
        // Status and Permissions.
        sectionLabel(c, p, "Quick replies", first = true)
        val qrCard = card(p)
        val qrRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                startActivity(Intent(this@MainActivity, QuickRepliesActivity::class.java))
            }
        }
        val qrText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        qrText.addView(TextView(this).apply {
            text = "Edit quick replies"; textSize = 16f; setTextColor(p.text)
        })
        qrText.addView(TextView(this).apply {
            text = Mirror.quickReplies.joinToString(", ").take(40) +
                   (if (Mirror.quickReplies.joinToString(", ").length > 40) "\u2026" else "")
            textSize = 13f; setTextColor(p.subtext)
        })
        qrRow.addView(qrText, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        qrRow.addView(TextView(this).apply {
            text = "\u203A"; textSize = 22f; setTextColor(p.subtext)
        })
        qrCard.addView(qrRow)
        c.addView(qrCard)

        // WATCH SETTINGS — toggles. (Live fetching is always on now.)
        sectionLabel(c, p, "Watch settings")
        val setCard = card(p)
        addToggleRow(setCard, p, "Night mode",
            "Dark theme on the Pebble", Mirror.nightMode) { on ->
            Mirror.setNightMode(this, on)
            pushConversationsToWatch()
        }
        addDivider(setCard, p)
        addToggleRow(setCard, p, "Emoji substitution",
            "Show unsupported emoji as a close supported one", Mirror.emojiSubstitution) { on ->
            Mirror.setEmojiSubstitution(this, on)
            pushConversationsToWatch()
        }
        addDivider(setCard, p)
        // Calls from the watch (Calls.kt): a Call row in the watch's reply menu. Turning
        // it on asks for the phone-call and contacts permissions.
        addToggleRow(setCard, p, "Calls from the watch",
            "Call a chat from the watch's reply menu. The call starts on this phone", Mirror.watchCalls) { on ->
            Mirror.setWatchCalls(this, on)
            if (on) requestCallPermissions()
        }
        if (Media.engine.playsVideo) {
            addDivider(setCard, p)
            // How much of the link a video's sound takes (whether it plays is the watch's
            // sound switch: beside a video's poster, and on the video). See Mirror.videoSoundLow.
            addChoiceRow(setCard, p, "Video sound quality",
                "Low sends half the sound data (duller), leaving more of the link to the picture",
                listOf("Standard", "Low"), if (Mirror.videoSoundLow) 1 else 0) { pick ->
                Mirror.setVideoSoundLow(this, pick == 1)
            }
        }
        addDivider(setCard, p)
        // The Time 2's sound when a message pops up on the watch (steps 89, 91), and with
        // it the message's video plays with sound when the watch's video sound is on (Off:
        // it starts silent). Picking one plays it there; the watch's mute and Quiet Time
        // still silence it. The watch's account switcher turns it off and on too (its bell
        // row), and this picker follows.
        // Which sound (Off is in the row below). Picking one turns the sound on and plays it.
        val showTone = addChoiceRow(setCard, p, "Notification sound",
            "The Pebble Time 2 plays this when a message pops up, and its video plays with sound unless videos are muted on the watch",
            Mirror.TONE_NAMES.drop(1), Mirror.lastTone - 1) { pick ->
            Mirror.setPushTone(this, pick + 1)
            PebbleListenerService.instance?.sendSound(play = true)
        }
        addDivider(setCard, p)
        // How loud, or off (step 111): one setting with the watch's sound row at the top of
        // its account switcher, which steps through the same four; this row follows it.
        val showLevel = addChoiceRow(setCard, p, "Notification sound volume",
            "How loud, within the watch's own speaker volume. The top of the watch's account switcher changes it too",
            Mirror.TONE_LEVEL_NAMES, Mirror.toneLevel()) { pick ->
            Mirror.setToneLevel(this, pick)
            PebbleListenerService.instance?.sendSound(play = Mirror.pushTone > 0)
        }
        Mirror.onSoundChanged = { runOnUiThread { showTone(Mirror.lastTone - 1); showLevel(Mirror.toneLevel()) } }
        // "Cached thumbnails" and "Image memory reserve" are gone: the watch's
        // media pool decides what stays in memory now.
        c.addView(setCard)

        // ---- IMAGE QUALITY ------------------------------------------------
        sectionLabel(c, p, "Image quality")
        val iqCard = card(p)
        // Animate adds/removes inside this card so the compressibility dial slides
        // in and the rows below it push down smoothly, instead of snapping.
        iqCard.layoutTransition = android.animation.LayoutTransition().apply {
            enableTransitionType(android.animation.LayoutTransition.CHANGING)
        }
        iqCard.addView(TextView(this).apply {
            text = "Images use as many colors as they need, up to a limit. " +
                "The sliders decide how readily a busy image settles for fewer " +
                "colors — lower = fewer colors more often (smaller, faster), " +
                "higher = keep more colors."
            textSize = 13f; setTextColor(p.subtext)
            setPadding(0, dp(2), 0, dp(10))
        })
        addColorCapRow(iqCard, p, "Thumbnail color limit",
            "Max colors for chat-view previews.", Mirror.imgThumbCap) { colors ->
            Mirror.setImgThumbCap(this, colors)
        }
        addDivider(iqCard, p)
        addCoverageSliderRow(iqCard, p, "Thumbnail colors",
            Mirror.imgThumbCoverage) { pct -> Mirror.setImgThumbCoverage(this, pct) }
        addDivider(iqCard, p)
        addColorCapRow(iqCard, p, "Full-size color limit",
            "Max colors for the opened image.", Mirror.imgFullCap) { colors ->
            Mirror.setImgFullCap(this, colors)
        }
        addDivider(iqCard, p)
        addCoverageSliderRow(iqCard, p, "Full-size colors",
            Mirror.imgFullCoverage) { pct -> Mirror.setImgFullCoverage(this, pct) }
        addDivider(iqCard, p)
        addToggleRow(iqCard, p, "Compress large images",
            "Keep full-size images compressed in watch memory so bigger images fit.",
            Mirror.imgCompress) { on ->
            Mirror.setImgCompress(this, on)
            // Show/hide the compressibility dial IN PLACE — same approach as the
            // Beeper master switch. Re-rendering the tab instead made the whole
            // settings list flicker and jump the scroll position.
            compressDitherRow?.visibility =
                if (on) android.view.View.VISIBLE else android.view.View.GONE
        }
        addDivider(iqCard, p)
        addDitherSection(iqCard, p)
        c.addView(iqCard)
    }

    // Dithering: a switch that reveals three sliders (edge protection, strength,
    // intensity). Simulates more colours on smooth gradients by blending palette
    // colours, at a compression cost — so it's opt-in. The sliders are disabled
    // (greyed) until the switch is on.
    // The compressibility dial, held so the compression switch can show/hide it in
    // place. Rebuilt on each render of the settings tab; cleared implicitly when
    // that tab is rebuilt, and only ever touched from the compression switch's
    // listener while the same tab is on screen.
    private var compressDitherRow: android.view.View? = null

    private fun addDitherSection(parent: LinearLayout, p: Palette) {
        val sliders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun setEnabled(on: Boolean) {
            sliders.alpha = if (on) 1f else 0.4f
            fun walk(v: android.view.View) {
                v.isEnabled = on
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(sliders)
        }
        addToggleRow(parent, p, "Dither gradients",
            "Blend palette colors to smooth gradients and simulate more colors. " +
            "Flat areas and hard edges stay crisp.",
            Mirror.imgDither) { on -> Mirror.setImgDither(this, on); setEnabled(on) }
        // Only meaningful alongside compression: this dial trades dithering away to
        // keep long runs of identical pixels intact for the compressor. With
        // compression off there is nothing to preserve, so the encoder dithers the
        // whole image and the dial is hidden rather than shown doing nothing.
        // It's built ALWAYS and kept in the layout with its visibility toggled, so
        // the compression switch reveals it in place (no re-render, no flicker,
        // no scroll jump) — the same technique the Beeper master switch uses.
        val compressRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addSliderRow(compressRow, p, "Keep images compressible",
            "Leaves flat areas and hard edges undithered so images compress smaller. " +
            "Lower means smoother gradients but larger transfers.",
            Mirror.imgDitherCompress,
            "smoother  \u2190          \u2192  smaller") { v -> Mirror.setImgDitherCompress(this, v) }
        compressRow.visibility =
            if (Mirror.imgCompress) android.view.View.VISIBLE else android.view.View.GONE
        compressDitherRow = compressRow
        sliders.addView(compressRow)
        addSliderRow(sliders, p, "Dither strength",
            "Higher = stronger texture; lower = softer and more compressible.",
            Mirror.imgDitherIntensity,
            "lite  \u2190          \u2192  strong") { v -> Mirror.setImgDitherIntensity(this, v) }
        parent.addView(sliders)
        setEnabled(Mirror.imgDither)
    }

    // Generic 0-100 slider row with a caption. Value label updates live; onChange
    // fires on release.
    private fun addSliderRow(parent: LinearLayout, p: Palette, title: String,
                             sub: String, current: Int, caption: String,
                             onChange: (Int) -> Unit) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(p.text)
        })
        titles.addView(TextView(this).apply {
            text = sub; textSize = 13f; setTextColor(p.subtext)
        })
        header.addView(titles, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueLabel = TextView(this).apply {
            text = "$current"; textSize = 14f; setTextColor(p.subtext)
        }
        header.addView(valueLabel)
        col.addView(header)
        val seek = android.widget.SeekBar(this).apply {
            max = 100
            progress = current.coerceIn(0, 100)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, prog: Int, fromUser: Boolean) {
                    valueLabel.text = "$prog"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    onChange(sb?.progress ?: 0)
                }
            })
        }
        col.addView(seek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(TextView(this).apply {
            text = caption; textSize = 11f; setTextColor(p.subtext)
        })
        parent.addView(col)
    }

    // ---- Accounts tab -----------------------------------------------------

    private fun buildAccountsTab(c: LinearLayout, p: Palette) {
        // ---- ACCOUNTS — native networks only ------------------------------
        // Google Messages + Facebook. Beeper-backed networks have their own
        // section below, so "native" = a present service that is NOT a
        // BeeperServiceBase. Each row shows "Log out" when signed in, "Log in"
        // when not (the name greys out when signed out). No Locked placeholders.
        sectionLabel(c, p, "Accounts")
        val natives = ServiceRegistry.available(this).filter { it !is BeeperServiceBase }
        val accCard = card(p)
        if (natives.isEmpty()) {
            accCard.addView(TextView(this).apply {
                text = "No messaging networks in this build."
                textSize = 14f; setTextColor(p.subtext)
            })
        } else {
            natives.forEachIndexed { i, svc ->
                if (i > 0) addDivider(accCard, p)
                // Native accounts that expose channel/folder sub-accounts (Telegram
                // folders, Discord servers) get a "SHOW ALL" amber lamp to the LEFT
                // of mute — same button the Beeper section uses. Default collapsed;
                // pressing it slides the sub-accounts out from under the account
                // panel (and back under, hidden) with an animation, each showing its
                // name + the two buttons like the Car Launcher row. This is purely a
                // tab-visibility control: it does NOT change whether a sub-account is
                // on the watch (that's the per-row enable toggle, independent of this).
                val hasSubs = (svc.id == "tgnative" || svc.id == "discord") && svc.isLoggedIn()
                var subWrap: android.widget.FrameLayout? = null
                var subCard: LinearLayout? = null
                var lamp: android.view.View? = null
                if (hasSubs) {
                    val shown = subsShown(svc.id)
                    val wrap = android.widget.FrameLayout(this).apply {
                        clipChildren = true
                        clipToPadding = true
                        // Always WRAP_CONTENT: collapsed still shows the on-watch
                        // sub-accounts (like the Beeper list), so it's content-sized
                        // in both states, not zero-height when collapsed.
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    val cardv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    wrap.addView(cardv, android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    subWrap = wrap; subCard = cardv
                    lamp = triLamp("SHOW ALL", 64, shown) { state ->
                        setSubsShown(svc.id, state)
                        animateSubAccounts(wrap, cardv, p, svc.id, state)
                    }
                }
                if (svc.isLoggedIn()) {
                    val sub = svc.accountLabel().ifEmpty { "Signed in" }
                    addAccountRow(accCard, p, svc.displayName, sub, "Log out",
                        active = true, isReal = true, muteId = svc.id,
                        leadingButton = lamp) { showLogoutConfirm(p, svc) }
                } else {
                    addAccountRow(accCard, p, svc.displayName, "Not connected", "Log in",
                        active = false, isReal = true) { attemptNativeLogin(p, svc) }
                }
                if (hasSubs) {
                    accCard.addView(subWrap!!)
                    populateSubAccounts(subCard!!, p, svc.id, subsShown(svc.id))
                }
            }
        }
        c.addView(accCard)

        // ---- BEEPER — optional, opt-in, additive --------------------------
        buildBeeperSection(c, p)
    }

    // The Beeper section. Beeper is ADDITIVE — MirrorMsg has full value without
    // it — so the whole section stays dormant until the user installs Beeper and
    // flips the master switch, which defaults OFF (opt-in, never insta-connected).
    //
    // States come from BeeperState (single source of truth) and, for per-network
    // presence, from each adapter's isPresentInBeeper(). The active gate
    // (isInstalled && hasPermissions && masterEnabled && isAccountEnabled) lives
    // in BeeperState/isLoggedIn(), so a disabled or master-off account reports
    // isLoggedIn()=false and vanishes from the watch switcher automatically.
    private fun buildBeeperSection(c: LinearLayout, p: Palette) {
        sectionLabel(c, p, "Beeper")

        // Not installed: a single live control deep-links to the Play listing;
        // nothing else in the section is interactive.
        if (!BeeperState.isInstalled(this)) {
            val card = card(p)
            addAccountRow(card, p, "Beeper", "Not installed", "Get Beeper",
                active = false, isReal = true) { openBeeperPlayListing() }
            c.addView(card)
            return
        }

        val masterOn = BeeperState.masterEnabled(this)

        // ---- Master "unlock" control (the breaker) ------------------------
        // Deliberately NOT an account row: it's the one switch that turns the
        // whole Beeper subsystem on, sliding the accounts out from behind it.
        val masterCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = beeperMasterBg(p, masterOn)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        val masterTxt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Title line: bold label with the info badge immediately to its right.
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "Enable Beeper link"; textSize = 19f; setTextColor(p.text)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        })
        titleRow.addView(infoIcon(p) { showBeeperInfo() })
        masterTxt.addView(titleRow)
        masterTxt.addView(TextView(this).apply {
            text = if (!BeeperState.hasPermissions(this@MainActivity))
                "Grant Beeper access to MirrorMsg to use"
            else "Add or remove accounts in the Beeper app"
            textSize = 13f; setTextColor(p.subtext); setPadding(0, dp(3), 0, 0)
        })

        // The accounts the master unlocks live in a CLIP wrapper so they can
        // slide out from behind the master panel when it's switched on, and back
        // under it (hidden) when off. Built once; the master toggle ANIMATES this
        // wrapper rather than re-rendering, so the reveal/hide can animate. The
        // 10dp top gap is the wrapper's own padding, so it collapses with it.
        val svcs = ServiceRegistry.available(this).filterIsInstance<BeeperServiceBase>()
        val accWrap = android.widget.FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
            setPadding(0, dp(10), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (masterOn) ViewGroup.LayoutParams.WRAP_CONTENT else 0)
        }
        val accCard = card(p)
        populateBeeperAccounts(accCard, p, svcs, beeperShowState())
        accWrap.addView(accCard, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // SHOW ALL lamp — same housing as the account mute buttons, wider for the
        // label, just left of the master switch (mirroring where mute sits on
        // account rows). 3-way cycle (see beeperShowState): amber-dim = connected
        // only (default), green-lit = every network Beeper could bridge, amber-lit =
        // the accounts you have (connected + disconnected). It lives in the row
        // permanently with its visibility toggled — so the master switch can
        // show/hide it in place (no re-render), and cycling it animates the list
        // rather than flickering.
        val showAllBtn = triLamp("SHOW ALL", 64, beeperShowState()) { state ->
            setBeeperShowState(state)
            animateBeeperAccounts(accWrap, accCard, p, svcs, state)
        }
        showAllBtn.visibility =
            if (masterOn) android.view.View.VISIBLE else android.view.View.GONE

        val masterSw = breakerSwitch(p, masterOn) { on ->
            BeeperState.setMasterEnabled(this@MainActivity, on)
            masterCard.background = beeperMasterBg(p, on)
            showAllBtn.visibility =
                if (on) android.view.View.VISIBLE else android.view.View.GONE
            val all = ServiceRegistry.available(this@MainActivity)
                .filterIsInstance<BeeperServiceBase>()
            // connectIfPossible() is self-gating (no-op unless active); disconnect
            // stops the observer/poll. Off-thread (priming queries the CP).
            thread(name = "beeper-master") {
                for (svc in all) if (on) svc.connectIfPossible() else svc.disconnect()
            }
            revealAccounts(accWrap, accCard, on)   // slide out / back under
            pushConversationsToWatch()             // refresh the watch switcher
        }
        masterCard.addView(masterTxt, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        masterCard.addView(showAllBtn)
        masterCard.addView(masterSw)
        c.addView(masterCard)

        if (svcs.isNotEmpty()) c.addView(accWrap)
    }

    private fun beeperMasterBg(p: Palette, on: Boolean) =
        rounded(tint(p.accent, p.card, if (on) 0.18f else 0.09f), 14, 2,
            if (on) p.accent else p.cardBorder)

    // Populate the Beeper accounts card. Rows are only visible while the master
    // is on (the wrapper hides them otherwise), so they're built in their live
    // representation; per-account presence + enable state still apply.
    private fun populateBeeperAccounts(accCard: LinearLayout, p: Palette,
                                       svcs: List<BeeperServiceBase>, state: Int) {
        // 3-way SHOW ALL (see beeperShowState):
        //  0 amber-dim: connected only (present && enabled) — the default
        //  1 green-lit: everything Beeper could bridge (present + not-present)
        //  2 amber-lit: the accounts you actually have (present = connected + disconnected)
        val visible = when (state) {
            1    -> svcs
            2    -> svcs.filter { it.isPresentInBeeper(this) }
            else -> svcs.filter {
                it.isPresentInBeeper(this) && BeeperState.isAccountEnabled(this, it.id)
            }
        }
        if (visible.isEmpty()) {
            accCard.addView(TextView(this).apply {
                text = when (state) {
                    0    -> "No connected accounts — tap SHOW ALL to reveal more"
                    2    -> "No accounts in your Beeper yet"
                    else -> "No networks available"
                }
                textSize = 14f; setTextColor(p.subtext)
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        visible.forEachIndexed { i, svc ->
            if (i > 0) addDivider(accCard, p)
            val present = svc.isPresentInBeeper(this)
            val enabled = BeeperState.isAccountEnabled(this, svc.id)
            val sub = when {
                !present -> "Not in your Beeper"
                enabled  -> "Connected"
                else     -> "Disconnected"
            }
            addBeeperAccountRow(accCard, p, svc.displayName, sub,
                present = present, enabled = enabled, interactive = true, muteId = svc.id) { on ->
                // Mirror attemptNativeLogin: enabling a Beeper account is blocked if
                // its native twin (same conflictGroup) is already active, so the two
                // don't fight over one account. (The switch flips visually first;
                // renderHome() below reverts it since state was never changed.)
                if (on) {
                    val conflict = ServiceRegistry.conflictingActive(this, svc.id)
                    if (conflict != null) {
                        showConflictBlock(p, svc.displayName, conflict.displayName)
                        return@addBeeperAccountRow false  // reject: row reverts the switch in place
                    }
                }
                BeeperState.setAccountEnabled(this, svc.id, on)
                thread(name = "beeper-acct-${svc.id}") {
                    if (on) svc.connectIfPossible() else svc.disconnect()
                }
                renderHome()
                pushConversationsToWatch()
                true
            }
        }
    }

    // Slide the accounts wrapper open (out from behind the master panel) or
    // closed (back under it, hidden), animating its clipped height. The card is
    // pinned to its measured height during the run so the shrinking wrapper CLIPS
    // it instead of squishing it; a small upward offset + fade sells "from behind".
    private fun revealAccounts(wrap: android.widget.FrameLayout,
                               card: android.view.View, show: Boolean) {
        val w = wrap.width
        if (w <= 0) {
            // Not laid out yet — just set the final state, no animation.
            wrap.layoutParams = wrap.layoutParams.apply {
                height = if (show) ViewGroup.LayoutParams.WRAP_CONTENT else 0
            }
            card.alpha = 1f
            wrap.requestLayout()
            return
        }
        val innerW = w - wrap.paddingLeft - wrap.paddingRight
        card.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(innerW, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        val cardH = card.measuredHeight
        val full = cardH + wrap.paddingTop + wrap.paddingBottom
        // Pin the card height so the shrinking wrapper clips rather than squishes.
        card.layoutParams = card.layoutParams.apply { height = cardH }
        val from = if (show) 0 else full
        val to   = if (show) full else 0
        wrap.visibility = android.view.View.VISIBLE
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { va ->
                val f = va.animatedValue as Float
                wrap.layoutParams = wrap.layoutParams.apply {
                    height = (from + (to - from) * f).toInt()
                }
                wrap.requestLayout()
                val prog = if (show) f else 1f - f
                card.translationY = -(cardH * 0.18f) * (1f - prog)
                card.alpha = 0.4f + 0.6f * prog
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    card.layoutParams = card.layoutParams.apply {
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    card.translationY = 0f
                    card.alpha = 1f
                    wrap.layoutParams = wrap.layoutParams.apply {
                        height = if (show) ViewGroup.LayoutParams.WRAP_CONTENT else 0
                    }
                    wrap.requestLayout()
                }
            })
        }.start()
    }

    // A Beeper per-account row: name + a Switch. Visual states per spec:
    //   not present        -> greyed name, switch disabled (supported, but the
    //                         user doesn't have that network in their Beeper)
    //   present + enabled  -> lit name, switch ON
    //   present + disabled -> greyed name, switch OFF (but live, so re-enableable)
    // `interactive` is the master gate: with the master switch OFF, every row is
    // greyed and non-interactive (no account is active).
    private fun addBeeperAccountRow(parent: LinearLayout, p: Palette, name: String,
                                    sub: String, present: Boolean, enabled: Boolean,
                                    interactive: Boolean, muteId: String? = null,
                                    onToggle: (Boolean) -> Boolean) {
        val lit = interactive && present && enabled
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(TextView(this).apply {
            text = name; textSize = 16f
            setTextColor(if (lit) p.text else p.subtext)
        })
        txt.addView(TextView(this).apply {
            text = sub; textSize = 13f; setTextColor(p.subtext)
        })
        val sw = Switch(this)
        sw.isChecked = interactive && present && enabled
        sw.isEnabled = interactive && present
        // If onToggle returns false (rejected — e.g. a conflict block), snap the
        // switch back in place: listener detached during the revert so it doesn't
        // re-fire, and no renderHome() so there's no full-tree flicker.
        var onChange: android.widget.CompoundButton.OnCheckedChangeListener? = null
        onChange = android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
            if (!onToggle(checked)) {
                sw.setOnCheckedChangeListener(null)
                sw.isChecked = !checked
                sw.setOnCheckedChangeListener(onChange)
            }
        }
        sw.setOnCheckedChangeListener(onChange)
        row.addView(txt, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Mute button only when the account is available AND on (active).
        if (lit && muteId != null) row.addView(muteButton(muteId))
        row.addView(sw)
        parent.addView(row)
    }

    // Deep-link to Beeper's Play Store listing (market:// first, web fallback).
    private fun openBeeperPlayListing() {
        val pkg = BeeperState.PKG
        try {
            startActivity(Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("market://details?id=$pkg")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
            } catch (_: Exception) {}
        }
    }

    // "Show all" persistence for the Beeper accounts list.
    // Beeper "SHOW ALL" is a 3-way cycle (new Int key; the old Boolean is abandoned):
    // 0 amber-dim  -> connected accounts only (hide disconnected + not-present)
    // 1 green-lit  -> every network Beeper could bridge (present + not-present)
    // 2 amber-lit  -> the accounts you actually have (connected + disconnected)
    private fun beeperShowState(): Int = prefs.getInt("beeper_show_state", 0)
    private fun setBeeperShowState(state: Int) =
        prefs.edit().putInt("beeper_show_state", state).apply()

    // The universal info badge: a filled circle with an "i", drawn so it needs no
    // asset. Sits to the right of a title; tap opens [onClick].
    private fun infoIcon(p: Palette, onClick: () -> Unit): android.view.View {
        return object : android.view.View(this) {
            private val circle = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val glyph = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD_ITALIC)
            }
            init {
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(6) }
                isClickable = true
                setOnClickListener { onClick() }
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                val cx = w / 2f; val cy = h / 2f
                val rad = minOf(w, h) / 2f - dp(1)
                circle.style = android.graphics.Paint.Style.FILL
                circle.color = p.subtext
                canvas.drawCircle(cx, cy, rad, circle)
                glyph.color = p.card
                glyph.textSize = h * 0.66f
                canvas.drawText("i", cx, cy - (glyph.descent() + glyph.ascent()) / 2f, glyph)
            }
        }
    }

    // Animate the Beeper accounts card growing/shrinking when SHOW ALL changes the
    // visible set. Re-populates in place and tweens the clip-wrapper height from
    // its current height to the new content height — the same "reveal from behind"
    // feel as the master switch, and (unlike a full re-render) no flicker. A light
    // alpha fade masks the row set swapping.
    private fun animateBeeperAccounts(wrap: android.widget.FrameLayout,
                                      card: LinearLayout, p: Palette,
                                      svcs: List<BeeperServiceBase>, state: Int) {
        val w = wrap.width
        val oldH = wrap.height
        card.removeAllViews()
        populateBeeperAccounts(card, p, svcs, state)
        if (w <= 0 || oldH <= 0) {   // not laid out yet — just settle final state
            wrap.layoutParams = wrap.layoutParams.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            card.alpha = 1f
            wrap.requestLayout()
            return
        }
        val innerW = w - wrap.paddingLeft - wrap.paddingRight
        card.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(innerW, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        val newH = card.measuredHeight + wrap.paddingTop + wrap.paddingBottom
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { va ->
                val f = va.animatedValue as Float
                wrap.layoutParams = wrap.layoutParams.apply {
                    height = (oldH + (newH - oldH) * f).toInt()
                }
                wrap.requestLayout()
                card.alpha = 0.45f + 0.55f * f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    wrap.layoutParams = wrap.layoutParams.apply {
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    card.alpha = 1f
                    wrap.requestLayout()
                }
            })
        }.start()
    }

    // A hardware-style lamp button — the same dark bezel + recessed lit/unlit face
    // as [muteButton], but generalized: any label, any width, any lit tint. Lit =
    // glow ring + top-lit gradient face + white label; unlit = dim recessed face.
    private fun lampButton(label: String, widthDp: Int, initialOn: Boolean,
                           litTop: Int, litBottom: Int, litGlow: Int, litLabel: Int,
                           offFace: Int, offLabel: Int,
                           onToggle: (Boolean) -> Unit): android.view.View {
        return object : android.view.View(this) {
            private var on = initialOn
            private val face = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
            }
            private val lbl = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textSize = resources.displayMetrics.density * 8.5f
            }
            init {
                layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(34)).apply {
                    leftMargin = dp(10); rightMargin = dp(8)
                }
                isClickable = true
                setOnClickListener { on = !on; onToggle(on); invalidate() }
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                face.shader = null
                face.color = 0xFF1C1E22.toInt()
                canvas.drawRoundRect(0f, 0f, w, h, dp(9).toFloat(), dp(9).toFloat(), face)
                val inset = dp(4).toFloat(); val r = dp(5).toFloat()
                val rect = android.graphics.RectF(inset, inset, w - inset, h - inset)
                if (on) {
                    ring.color = litGlow; ring.strokeWidth = dp(3).toFloat()
                    canvas.drawRoundRect(rect, r, r, ring)
                    face.shader = android.graphics.LinearGradient(
                        0f, rect.top, 0f, rect.bottom, litTop, litBottom,
                        android.graphics.Shader.TileMode.CLAMP)
                    canvas.drawRoundRect(rect, r, r, face)
                    face.shader = null
                    lbl.color = litLabel
                } else {
                    face.color = offFace
                    canvas.drawRoundRect(rect, r, r, face)
                    lbl.color = offLabel
                }
                canvas.drawText(label, w / 2f,
                    h / 2f - (lbl.descent() + lbl.ascent()) / 2f, lbl)
            }
        }
    }

    // Reusable three-state "SHOW ALL" lamp. Cycles on tap:
    // 0 amber-dim -> 1 green-lit -> 2 amber-lit -> 0. Colours follow Discord's own
    // notification logic: green = full blast (all), amber = just the relevant subset.
    // The MEANING of each state is set by the caller's filter (Discord/TG vs Beeper);
    // only the look is fixed here. Same housing/label as lampButton.
    private fun triLamp(label: String, widthDp: Int, initialState: Int,
                        onCycle: (Int) -> Unit): android.view.View {
        return object : android.view.View(this) {
            private var state = initialState.coerceIn(0, 2)
            private val face = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
            }
            private val lbl = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textSize = resources.displayMetrics.density * 8.5f
            }
            init {
                layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(34)).apply {
                    leftMargin = dp(10); rightMargin = dp(8)
                }
                isClickable = true
                setOnClickListener { state = (state + 1) % 3; onCycle(state); invalidate() }
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                face.shader = null
                face.color = 0xFF1C1E22.toInt()
                canvas.drawRoundRect(0f, 0f, w, h, dp(9).toFloat(), dp(9).toFloat(), face)
                val inset = dp(4).toFloat(); val r = dp(5).toFloat()
                val rect = android.graphics.RectF(inset, inset, w - inset, h - inset)
                when (state) {
                    0 -> {   // amber dim: default (Discord/TG: nothing; Beeper: connected only)
                        face.color = 0xFF332B0E.toInt()
                        canvas.drawRoundRect(rect, r, r, face)
                        lbl.color = 0xFF8C7638.toInt()
                    }
                    1 -> {   // green lit: full blast (all)
                        ring.color = 0x6634C759.toInt(); ring.strokeWidth = dp(3).toFloat()
                        canvas.drawRoundRect(rect, r, r, ring)
                        face.color = 0xFF34C759.toInt()
                        canvas.drawRoundRect(rect, r, r, face)
                        lbl.color = 0xFFFFFFFF.toInt()
                    }
                    else -> {   // amber lit: just the relevant subset
                        ring.color = 0x66FFD23F.toInt(); ring.strokeWidth = dp(3).toFloat()
                        canvas.drawRoundRect(rect, r, r, ring)
                        face.shader = android.graphics.LinearGradient(
                            0f, rect.top, 0f, rect.bottom,
                            0xFFFFD23F.toInt(), 0xFFE0A012.toInt(),
                            android.graphics.Shader.TileMode.CLAMP)
                        canvas.drawRoundRect(rect, r, r, face)
                        face.shader = null
                        lbl.color = 0xFF201A00.toInt()
                    }
                }
                canvas.drawText(label, w / 2f,
                    h / 2f - (lbl.descent() + lbl.ascent()) / 2f, lbl)
            }
        }
    }

    // Explains why duplicate (Beeper) accounts exist and how they differ from the
    // native ones. Triggered by the info badge on the Beeper master row.
    private fun showBeeperInfo() {
        android.app.AlertDialog.Builder(this)
            .setTitle("About Beeper accounts")
            .setMessage(
                "For the networks we support directly (above), the native account " +
                "is the better choice for most people \u2014 it handles images and " +
                "rich chats correctly. The Beeper versions are here only for the " +
                "convenience of existing Beeper users who'd rather not log in " +
                "again.\n\n" +
                "Beeper's connection has limits: it can't directly reply to a " +
                "specific message or send emoji reactions (you can still see them, " +
                "and send an emoji as a normal message). Images aren't available " +
                "through Beeper's API \u2014 they're pulled from its notifications, " +
                "which is less consistent, so you won't see images you sent " +
                "yourself, or images that never showed up in a notification.")
            .setPositiveButton("Got it", null)
            .show()
    }

    // A vertical breaker-style master switch: green lever up = ON, red lever down
    // = OFF, like a wall light switch / circuit breaker. Custom-drawn so it reads
    // as a physical "unlock" control rather than a plain toggle. Tapping flips it;
    // the caller persists the new state and re-renders.
    private fun breakerSwitch(p: Palette, initialOn: Boolean,
                              onToggle: (Boolean) -> Unit): android.view.View {
        return object : android.view.View(this) {
            private var on = initialOn
            private val housing = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val slot    = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val lever   = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val label   = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textSize = dp(10).toFloat()
            }
            init {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(78))
                isClickable = true
                setOnClickListener {
                    on = !on
                    invalidate()       // instant flip
                    onToggle(on)       // persist + re-render (rebuilds in new state)
                }
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                val green = 0xFF34C759.toInt(); val red = 0xFFFF3B30.toInt()
                // Housing.
                housing.color = 0xFF1C1E22.toInt()
                canvas.drawRoundRect(0f, 0f, w, h, dp(12).toFloat(), dp(12).toFloat(), housing)
                // Inner slot, faintly tinted by state.
                val inset = dp(4).toFloat()
                slot.color = if (on) 0xFF12331C.toInt() else 0xFF331414.toInt()
                canvas.drawRoundRect(inset, inset, w - inset, h - inset,
                    dp(9).toFloat(), dp(9).toFloat(), slot)
                // Lever: top half when ON (green), bottom half when OFF (red).
                val zoneT = inset + dp(2)
                val zoneB = h - inset - dp(2)
                val halfH = (zoneB - zoneT) / 2f
                val lt = if (on) zoneT else zoneT + halfH
                val lInset = inset + dp(3)
                lever.color = if (on) green else red
                canvas.drawRoundRect(lInset, lt, w - lInset, lt + halfH,
                    dp(7).toFloat(), dp(7).toFloat(), lever)
                // Active label on the lever (white); faint opposite in the empty zone.
                val onY  = zoneT + halfH / 2f + label.textSize / 3f
                val offY = zoneT + halfH + halfH / 2f + label.textSize / 3f
                label.color = 0xFFFFFFFF.toInt()
                canvas.drawText(if (on) "ON" else "OFF", w / 2f, if (on) onY else offY, label)
                label.color = 0x44FFFFFF
                canvas.drawText(if (on) "OFF" else "ON", w / 2f, if (on) offY else onY, label)
            }
        }
    }


    // ---- Reusable row builders --------------------------------------------

    private fun addCheckRow(parent: LinearLayout, p: Palette, title: String,
                            granted: Boolean, sub: String, onTap: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { if (!granted) onTap() }
        }
        val badge = TextView(this).apply {
            text = if (granted) "\u2713" else "!"
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            val sz = dp(28)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { rightMargin = dp(14) }
            background = rounded(if (granted) p.accent else Color.parseColor("#C0392B"), 14)
        }
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(p.text)
        })
        txt.addView(TextView(this).apply {
            text = if (granted) sub else "Tap to grant — $sub"
            textSize = 13f; setTextColor(p.subtext)
        })
        row.addView(badge); row.addView(txt)
        parent.addView(row)
    }

    private fun addModeCard(parent: LinearLayout, p: Palette, mode: Int,
                            title: String, sub: String) {
        val selected = Mirror.notifyMode == mode
        // Selected: accent border + faint accent tint. Unselected: normal card.
        val fill = if (selected) tint(p.accent, p.card, 0.14f) else p.card
        val borderColor = if (selected) p.accent else p.cardBorder
        val borderW = if (selected) 2 else 1

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(fill, 12, borderW, borderColor)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            setOnClickListener { Mirror.setNotifyMode(this@MainActivity, mode); renderHome() }
        }
        // Radio circle (bigger, clearer).
        val ring = TextView(this).apply {
            text = if (selected) "\u25C9" else "\u25CB"
            setTextColor(if (selected) p.accent else p.subtext); textSize = 22f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(14) }
        }
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(TextView(this).apply {
            text = title; textSize = 17f; setTextColor(p.text)
            setTypeface(typeface,
                if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        })
        txt.addView(TextView(this).apply {
            text = sub; textSize = 13f; setTextColor(p.subtext); setPadding(0, dp(2), 0, 0)
        })
        row.addView(ring)
        row.addView(txt)
        parent.addView(row)
    }

    // An indented on/off row shown directly under its mode card — the mode's
    // sub-option. Title only, no subtitle: the mode card above it already explains
    // the mode, and the switch reads as an on/off of the label itself.
    private fun addModeSubToggle(parent: LinearLayout, p: Palette, title: String,
                                 initial: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(p.card, 12, 1, p.cardBorder)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6); leftMargin = dp(28)
            }
        }
        val label = TextView(this).apply {
            text = title; textSize = 14f; setTextColor(p.text)
        }
        val sw = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        row.addView(label, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        parent.addView(row)
    }

    // Blend `over` onto `under` by alpha fraction (for the selected tint).
    private fun tint(over: Int, under: Int, frac: Float): Int {
        fun mix(a: Int, b: Int) = (a * frac + b * (1 - frac)).toInt()
        return Color.rgb(
            mix(Color.red(over), Color.red(under)),
            mix(Color.green(over), Color.green(under)),
            mix(Color.blue(over), Color.blue(under)))
    }

    private fun addToggleRow(parent: LinearLayout, p: Palette, title: String,
                             sub: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(p.text)
        })
        txt.addView(TextView(this).apply {
            text = sub; textSize = 13f; setTextColor(p.subtext)
        })
        val sw = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        row.addView(txt, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        parent.addView(row)
    }

    // ---- Image quality helper rows ----------------------------------------
    // Colour-cap picker: a small spinner of 2 / 4 / 16 (the three watch-supported
    // palette sizes). onPick gets the chosen colour COUNT.
    private fun addColorCapRow(parent: LinearLayout, p: Palette, title: String,
                               subtitle: String, current: Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(p.text)
        })
        txt.addView(TextView(this).apply {
            text = subtitle; textSize = 13f; setTextColor(p.subtext)
        })
        val options = listOf(2, 4, 16)
        val spinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                options.map { "$it colors" }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(options.indexOf(current).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?,
                                            view: android.view.View?, pos: Int, id: Long) {
                    val picked = options[pos]
                    if (picked != current) onPick(picked)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        row.addView(txt, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(spinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(row)
    }

    // A setting with a few named choices: title and subtitle, and a dropdown on the right.
    // Returns a setter that shows another choice without calling onPick (the setting
    // changed elsewhere).
    private fun addChoiceRow(parent: LinearLayout, p: Palette, title: String, subtitle: String,
                             labels: List<String>, current: Int, onPick: (Int) -> Unit): (Int) -> Unit {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(p.text)
        })
        txt.addView(TextView(this).apply {
            text = subtitle; textSize = 13f; setTextColor(p.subtext)
        })
        var shown = current
        val spinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(current.coerceIn(0, labels.size - 1))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?,
                                            view: android.view.View?, pos: Int, id: Long) {
                    if (pos != shown) { shown = pos; onPick(pos) }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        row.addView(txt, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(spinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(row)
        return { v ->
            val c = v.coerceIn(0, labels.size - 1)
            if (c != shown) { shown = c; spinner.setSelection(c) }
        }
    }

    // Coverage slider: a SeekBar mapped to 10-100% with a live "fewer <-> more
    // colours" caption and the current percent. onChange fires on release.
    private fun addCoverageSliderRow(parent: LinearLayout, p: Palette, title: String,
                                     currentPct: Int, onChange: (Int) -> Unit) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(p.text)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueLabel = TextView(this).apply {
            text = "$currentPct%"; textSize = 14f; setTextColor(p.subtext)
        }
        header.addView(valueLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(header)
        val seek = android.widget.SeekBar(this).apply {
            max = 90                       // 0..90 maps to 10..100%
            progress = (currentPct - 10).coerceIn(0, 90)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, prog: Int, fromUser: Boolean) {
                    valueLabel.text = "${prog + 10}%"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    onChange((sb?.progress ?: 0) + 10)
                }
            })
        }
        col.addView(seek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val caption = TextView(this).apply {
            text = "fewer colors  \u2190          \u2192  more colors"
            textSize = 11f; setTextColor(p.subtext)
        }
        col.addView(caption)
        parent.addView(col)
    }

    /**
     * A small illuminated "MUTE" push-button for an account row, styled after a
     * panel-mount indicator button. Dim and recessed when off; bright red with a
     * glow when engaged (the account is muted). Tapping toggles [MuteState] in
     * place (no full re-render). Shown only for accounts that are available AND on.
     *
     * Muting keeps the account connected and browsable on the watch — it only
     * silences alerts (no notifications, no force-open PNs); see [MuteState].
     */
    // A minimal folder glyph (tab + body), drawn so it needs no asset. Marks a
    // Telegram chat-folder sub-account row, sitting in its indent.
    private fun folderIcon(p: Palette): android.view.View {
        return object : android.view.View(this) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.FILL
            }
            init {
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                    leftMargin = dp(8); rightMargin = dp(10)
                }
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                paint.color = p.subtext
                val r = dp(2).toFloat()
                // tab (top-left)
                canvas.drawRoundRect(w * 0.10f, h * 0.20f, w * 0.48f, h * 0.40f, r, r, paint)
                // body
                canvas.drawRoundRect(w * 0.10f, h * 0.33f, w * 0.90f, h * 0.82f, r, r, paint)
            }
        }
    }

    /**
     * Three-segment Discord notification-level selector, beside the mute button.
     * Segments left→right: All (green, bell), Only @mentions (yellow, @), Nothing
     * (red, bell-off). The active level lights in its colour; the others stay dark.
     * Tapping a segment writes that level to Discord off the main thread (reverting on
     * failure), and registers for live refresh so a change made in the Discord app
     * flips it here in real time. Mute overrides level in the gate, so a muted server
     * stays silent regardless of the segment shown here (faithful to Discord).
     */
    private fun discordNotifyButton(guildId: String): android.view.View {
        return object : android.view.View(this) {
            private var level = DiscordService.serverNotifyLevel(guildId)   // 0=all,1=@,2=none
            private val face = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val glyph = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = resources.displayMetrics.density * 1.7f
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            private val glyphFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val at = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textSize = dp(16).toFloat()
            }
            private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
            }
            // Segment colours copied from the app's existing buttons: green ~ the breaker
            // lever, yellow ~ the Beeper "SHOW ALL" lamp, red ~ the mute button. Off uses
            // each colour's existing dim recessed shade (the lit light "leaking").
            private val litTop    = intArrayOf(0xFF34C759.toInt(), 0xFFFFD23F.toInt(), 0xFFFF5247.toInt())
            private val litBottom = intArrayOf(0xFF34C759.toInt(), 0xFFE0A012.toInt(), 0xFFC4140A.toInt())
            private val litGlow   = intArrayOf(0x6634C759.toInt(), 0x66FFD23F.toInt(), 0x66FF5A4D.toInt())
            private val litLabel  = intArrayOf(0xFFFFFFFF.toInt(), 0xFF201A00.toInt(), 0xFFFFFFFF.toInt())
            private val offFace   = intArrayOf(0xFF12331C.toInt(), 0xFF332B0E.toInt(), 0xFF351A1A.toInt())
            private val offLabel  = intArrayOf(0xFF4E8C66.toInt(), 0xFF8C7638.toInt(), 0xFF7C4A4A.toInt())
            // Unknown state (level < 0): no data at all for this guild, so we don't
            // claim a setting. All segments render in a neutral dim grey — reads as
            // "unavailable" rather than a wrong "all notifications".
            private val unkFace   = 0xFF26282C.toInt()
            private val unkLabel  = 0xFF55585E.toInt()
            init {
                layoutParams = LinearLayout.LayoutParams(dp(84), dp(34)).apply {
                    leftMargin = dp(4); rightMargin = dp(4)
                }
                isClickable = true
                setOnTouchListener { v, ev ->
                    if (ev.action == android.view.MotionEvent.ACTION_UP) {
                        val seg = (ev.x / (width / 3f)).toInt().coerceIn(0, 2)
                        if (seg != level) {
                            val prev = level
                            level = seg
                            invalidate()   // optimistic
                            Thread {
                                val ok = DiscordService.setServerNotifyLevel(guildId, seg)
                                if (!ok) runOnUiThread {
                                    level = prev; invalidate()
                                    android.widget.Toast.makeText(
                                        this@MainActivity, "Couldn't reach Discord",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }.start()
                        }
                        v.performClick()
                    }
                    true
                }
            }
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                DiscordService.registerSettingRefresher("$guildId#notify") {
                    level = DiscordService.serverNotifyLevel(guildId)
                    invalidate()
                }
            }
            override fun onDetachedFromWindow() {
                DiscordService.unregisterSettingRefresher("$guildId#notify")
                super.onDetachedFromWindow()
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                // Housing bezel — matches the mute button's look.
                face.shader = null
                face.color = 0xFF1C1E22.toInt()
                canvas.drawRoundRect(0f, 0f, w, h, dp(9).toFloat(), dp(9).toFloat(), face)
                val inset = dp(3).toFloat()
                val gap = dp(2).toFloat()
                val segW = (w - inset * 2 - gap * 2) / 3f
                val rad = dp(5).toFloat()
                for (i in 0..2) {
                    val left = inset + i * (segW + gap)
                    val right = left + segW
                    val rect = android.graphics.RectF(left, inset, right, h - inset)
                    val gc: Int
                    if (level < 0) {
                        // Unknown: neutral dim grey for every segment, nothing lit.
                        face.color = unkFace
                        canvas.drawRoundRect(rect, rad, rad, face)
                        gc = unkLabel
                    } else if (i == level) {
                        // Lit: glow ring + top-lit gradient of the ACTIVE colour.
                        ring.color = litGlow[level]; ring.strokeWidth = dp(2).toFloat()
                        canvas.drawRoundRect(rect, rad, rad, ring)
                        face.shader = android.graphics.LinearGradient(
                            0f, rect.top, 0f, rect.bottom, litTop[level], litBottom[level],
                            android.graphics.Shader.TileMode.CLAMP)
                        canvas.drawRoundRect(rect, rad, rad, face)
                        face.shader = null
                        gc = litLabel[level]
                    } else {
                        // The other segments take the DIM shade of the SAME (active)
                        // colour, so the whole control reads as one colour — the
                        // lamp/Beeper button's lit+off scheme.
                        face.color = offFace[level]
                        canvas.drawRoundRect(rect, rad, rad, face)
                        gc = offLabel[level]
                    }
                    glyph.color = gc; glyphFill.color = gc; at.color = gc
                    val cx = (left + right) / 2f
                    val cy = h / 2f
                    when (i) {
                        0 -> drawBell(canvas, cx, cy, false)
                        1 -> {
                            // Centre the @ on its actual ink bounds — font metrics
                            // include line padding the glyph doesn't use, leaving it low.
                            val r = android.graphics.Rect()
                            at.getTextBounds("@", 0, 1, r)
                            canvas.drawText("@", cx, cy - (r.top + r.bottom) / 2f, at)
                        }
                        2 -> drawBell(canvas, cx, cy, true)
                    }
                }
            }
            private fun drawBell(canvas: android.graphics.Canvas, cx: Float, cy: Float, slashed: Boolean) {
                val s = dp(13).toFloat()
                val topY = cy - s * 0.46f
                val rimY = cy + s * 0.26f
                val halfW = s * 0.38f
                val body = android.graphics.Path().apply {
                    moveTo(cx - halfW, rimY)
                    quadTo(cx - halfW, topY, cx, topY - s * 0.05f)
                    quadTo(cx + halfW, topY, cx + halfW, rimY)
                }
                canvas.drawPath(body, glyph)                                               // dome
                canvas.drawLine(cx - halfW * 1.25f, rimY, cx + halfW * 1.25f, rimY, glyph) // rim
                canvas.drawLine(cx, topY - s * 0.05f, cx, topY - s * 0.16f, glyph)         // handle
                canvas.drawCircle(cx, rimY + s * 0.16f, s * 0.09f, glyphFill)              // clapper
                if (slashed) {
                    canvas.drawLine(cx - halfW * 1.5f, cy + s * 0.52f, cx + halfW * 1.5f, cy - s * 0.52f, glyph)
                }
            }
        }
    }

    private fun muteButton(
        serviceId: String,
        isMutedProvider: () -> Boolean = { MuteState.isMuted(this, serviceId) },
        onToggle: (Boolean, () -> Unit) -> Unit = { desired, _ ->
            MuteState.setMuted(this, serviceId, desired)
        },
        onAttach: (refresh: () -> Unit) -> Unit = {},
        onDetach: () -> Unit = {},
        // When true, we have no data about this account's mute state, so the button
        // renders neutral dim grey rather than implying "not muted" (dim red).
        isUnknownProvider: () -> Boolean = { false }
    ): android.view.View {
        return object : android.view.View(this) {
            private var muted = isMutedProvider()
            private val face = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
            }
            private val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textSize = dp(9).toFloat()
            }
            init {
                layoutParams = LinearLayout.LayoutParams(dp(50), dp(34)).apply {
                    leftMargin = dp(12); rightMargin = dp(12)
                }
                isClickable = true
                setOnClickListener {
                    muted = !muted
                    invalidate()   // instant light on/off (optimistic)
                    // onToggle applies the change (locally, or via a background write);
                    // if it fails it calls the revert lambda to flip the light back.
                    onToggle(muted) {
                        muted = !muted
                        invalidate()
                    }
                    LogStore.i(TAG, "Account $serviceId muted=$muted")
                }
            }
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                // While on screen, accept live refreshes: re-read the source of truth
                // and repaint (used for Discord's real mute changing outside the app).
                onAttach {
                    muted = isMutedProvider()
                    invalidate()
                }
            }
            override fun onDetachedFromWindow() {
                onDetach()
                super.onDetachedFromWindow()
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                // Dark housing bezel — the same thick-frame look as the breaker switch.
                face.shader = null
                face.color = 0xFF1C1E22.toInt()
                canvas.drawRoundRect(0f, 0f, w, h, dp(9).toFloat(), dp(9).toFloat(), face)
                // Recessed face inside the bezel.
                val inset = dp(4).toFloat()
                val r = dp(5).toFloat()
                val rect = android.graphics.RectF(inset, inset, w - inset, h - inset)
                if (isUnknownProvider()) {
                    // Unknown: neutral dim grey, no red — we don't claim a mute state.
                    face.color = 0xFF26282C.toInt()
                    canvas.drawRoundRect(rect, r, r, face)
                    label.color = 0xFF55585E.toInt()
                } else if (muted) {
                    // Lit: glow ring, then a top-lit red gradient face.
                    ring.color = 0x66FF5A4D.toInt(); ring.strokeWidth = dp(3).toFloat()
                    canvas.drawRoundRect(rect, r, r, ring)
                    face.shader = android.graphics.LinearGradient(
                        0f, rect.top, 0f, rect.bottom,
                        0xFFFF5247.toInt(), 0xFFC4140A.toInt(),
                        android.graphics.Shader.TileMode.CLAMP)
                    canvas.drawRoundRect(rect, r, r, face)
                    face.shader = null
                    label.color = android.graphics.Color.WHITE
                } else {
                    // Unlit: dark recessed maroon face, dim label.
                    face.color = 0xFF351A1A.toInt()
                    canvas.drawRoundRect(rect, r, r, face)
                    label.color = 0xFF7C4A4A.toInt()
                }
                canvas.drawText("MUTE", w / 2f,
                    h / 2f - (label.descent() + label.ascent()) / 2f, label)
            }
        }
    }

    private fun addAccountRow(parent: LinearLayout, p: Palette, name: String,
                              detail: String, badge: String, active: Boolean, isReal: Boolean,
                              muteId: String? = null,
                              leadingButton: android.view.View? = null,
                              onBadgeTap: (() -> Unit)? = null) {
        // A row is "actionable" if it has a tap handler (Connected -> Log out, or
        // a real-but-signed-out account -> Sign in). Actionable rows render at
        // full opacity with an accent badge so they clearly stand out from the
        // dimmed, grey "Locked" rows that don't do anything yet.
        val actionable = onBadgeTap != null
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            alpha = if (isReal || actionable) 1f else 0.55f
        }
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(TextView(this).apply {
            text = name; textSize = 16f; setTextColor(if (active) p.text else p.subtext)
        })
        txt.addView(TextView(this).apply {
            text = detail; textSize = 13f; setTextColor(p.subtext)
            setPadding(0, dp(2), 0, 0)   // small gap below the account name
        })
        // Accent badge for any actionable row (Sign in / Log out); plain grey
        // pill for the inert "Locked" rows.
        val accentBadge = active || actionable
        val badgeView = TextView(this).apply {
            text = badge; textSize = 12f
            setTextColor(if (accentBadge) p.onAccent else p.subtext)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(
                if (accentBadge) p.accent else p.cardBorder, 8)
            if (onBadgeTap != null) setOnClickListener { onBadgeTap() }
        }
        row.addView(txt, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (leadingButton != null) row.addView(leadingButton)
        if (muteId != null) row.addView(muteButton(muteId))
        row.addView(badgeView)
        parent.addView(row)
    }

    // ---- Telegram channel sub-accounts -----------------------------------
    // A Telegram chat folder (e.g. a joined community like "Car Launcher") shows
    // as an indented sub-account under the Telegram row, and — once wired to the
    // watch — as its own selectable account showing that folder's chats. Enable
    // (Switch) hides/shows it on the watch; mute (browsable, no notifications)
    // mirrors the Beeper account rows. Folders are read off-thread (a cached
    // bridge call) then populated on the UI thread so position stays under Telegram.
    // Populate a native account's channel/folder sub-accounts SYNCHRONOUSLY (like
    // the Beeper list) so a renderHome() rebuild never shows a blank-then-fill
    // flicker. state 0 (amber-dim) lists nothing; 1 (green-lit) lists every
    // folder/server; 2 (amber-lit) lists only the on-watch (enabled) ones. The
    // underlying foldersJson/guildsJson are cached in-memory bridge reads, so this
    // is cheap on the UI thread.
    private fun populateSubAccounts(card: LinearLayout, p: Palette, serviceId: String, state: Int) {
        if (state == 0) return              // amber-dim: nothing visible (default)
        val activeOnly = (state == 2)       // green-lit (1): all; amber-lit (2): active only
        when (serviceId) {
            "tgnative" -> {
                val folders = try {
                    org.json.JSONObject(TelegramService.foldersJson()).optJSONArray("folders")
                        ?: org.json.JSONArray()
                } catch (e: Throwable) { org.json.JSONArray() }
                for (k in 0 until folders.length()) {
                    val f = folders.optJSONObject(k) ?: continue
                    val id = f.optInt("id", -1)
                    if (id < 0) continue
                    if (activeOnly && !tgSubEnabled(id)) continue   // amber-lit: enabled only
                    val title = f.optString("title").ifEmpty { "Channel" }
                    addTgChannelRow(card, p, title, id)
                }
            }
            "discord" -> {
                val servers = try {
                    org.json.JSONObject(DiscordService.guildsJson()).optJSONArray("guilds")
                        ?: org.json.JSONArray()
                } catch (e: Throwable) { org.json.JSONArray() }
                for (k in 0 until servers.length()) {
                    val g = servers.optJSONObject(k) ?: continue
                    val gid = g.optString("id")
                    if (gid.isEmpty()) continue
                    if (activeOnly && !DiscordService.serverEnabled(this, gid)) continue
                    val name = g.optString("name").ifEmpty { "Server" }
                    addDiscordServerRow(card, p, name, gid)
                }
            }
        }
    }

    // Toggle the sub-account list between collapsed (on-watch only) and SHOW ALL,
    // animating the height change with the same slide+fade the Beeper "show all"
    // uses. Re-populates the card with the new visible set, then animates the
    // wrapper's clipped height old->new. (Mirrors animateBeeperAccounts.)
    private fun animateSubAccounts(wrap: android.widget.FrameLayout, card: LinearLayout,
                                   p: Palette, serviceId: String, state: Int) {
        val w = wrap.width
        val oldH = wrap.height
        card.removeAllViews()
        populateSubAccounts(card, p, serviceId, state)
        if (w <= 0) {   // not laid out yet — settle final state
            wrap.layoutParams = wrap.layoutParams.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            card.alpha = 1f
            wrap.requestLayout()
            return
        }
        val innerW = w - wrap.paddingLeft - wrap.paddingRight
        card.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(innerW, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        val newH = card.measuredHeight + wrap.paddingTop + wrap.paddingBottom
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { va ->
                val f = va.animatedValue as Float
                wrap.layoutParams = wrap.layoutParams.apply {
                    height = (oldH + (newH - oldH) * f).toInt()
                }
                wrap.requestLayout()
                card.alpha = 0.45f + 0.55f * f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    wrap.layoutParams = wrap.layoutParams.apply {
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    card.alpha = 1f
                    wrap.requestLayout()
                }
            })
        }.start()
    }

    private fun addTgChannelRow(parent: LinearLayout, p: Palette, title: String, folderId: Int) {
        val subId = "tgsub:$folderId"
        val enabled = tgSubEnabled(folderId)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), 0, dp(6))   // folder icon supplies the rest of the indent
        }
        row.addView(folderIcon(p))
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(TextView(this).apply {
            text = title; textSize = 15f
            setTextColor(if (enabled) p.text else p.subtext)
        })
        txt.addView(TextView(this).apply {
            text = if (enabled) "On watch" else "Hidden"
            textSize = 12f; setTextColor(p.subtext)
        })
        val sw = Switch(this).apply {
            isChecked = enabled
            setOnCheckedChangeListener { _, checked ->
                setTgSubEnabled(folderId, checked)
                // Refresh just this sub-account card in place (preserving the SHOW ALL
                // expand state) instead of renderHome(), which flickers the whole tab.
                parent.removeAllViews()
                populateSubAccounts(parent, p, "tgnative", subsShown("tgnative"))
                pushConversationsToWatch()
            }
        }
        row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (enabled) row.addView(muteButton(subId))
        row.addView(sw)
        parent.addView(row)
    }

    // Per-folder enable state. Default ON — a detected channel is shown on the
    // watch until the user hides it. Keyed by folder id.
    private fun tgSubEnabled(folderId: Int): Boolean =
        prefs.getBoolean("tgsub_enabled_$folderId", true)
    private fun setTgSubEnabled(folderId: Int, on: Boolean) =
        prefs.edit().putBoolean("tgsub_enabled_$folderId", on).apply()

    // Per-account "SHOW ALL" reveal state, a 3-way cycle in the Networks tab:
    // 0 = amber-dim  -> nothing visible (default; where recovery/reinstall lands)
    // 1 = amber-lit  -> only active (enabled) servers/folders
    // 2 = green-lit  -> all servers/folders ("full blast", mirroring Discord's colours)
    // Persisted under a new Int key (the old Boolean key is abandoned). Separate from
    // each sub-account's on-watch enable toggle.
    private fun subsShown(serviceId: String): Int =
        prefs.getInt("subs_state_$serviceId", 0)
    private fun setSubsShown(serviceId: String, state: Int) =
        prefs.edit().putInt("subs_state_$serviceId", state).apply()

    // ---- Discord server sub-accounts -------------------------------------
    // A Discord server (guild) shows as an indented sub-account under the Discord
    // row and, on the watch, as its own selectable account listing that server's
    // channels. Unlike Telegram folders, servers default OFF (opt-in): the enable
    // Switch adds the server to the watch rail and, via DiscordService, gates its
    // channel messages through the bridge. Rows are built synchronously by
    // populateSubAccounts (no async load) so renderHome() doesn't flicker.
    private fun addDiscordServerRow(parent: LinearLayout, p: Palette, name: String, guildId: String) {
        val enabled = DiscordService.serverEnabled(this, guildId)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), 0, dp(6))
        }
        row.addView(folderIcon(p))
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(TextView(this).apply {
            text = name; textSize = 15f
            setTextColor(if (enabled) p.text else p.subtext)
        })
        txt.addView(TextView(this).apply {
            text = if (enabled) "On watch" else "Hidden"
            textSize = 12f; setTextColor(p.subtext)
        })
        val sw = Switch(this).apply {
            isChecked = enabled
            setOnCheckedChangeListener { _, checked ->
                // setServerEnabled persists the flag AND re-pushes the enabled set to
                // the bridge so live channel messages are gated to opted-in servers.
                DiscordService.setServerEnabled(this@MainActivity, guildId, checked)
                // Refresh just this sub-account card in place (preserving the SHOW ALL
                // expand state) instead of renderHome(), which flickers the whole tab.
                parent.removeAllViews()
                populateSubAccounts(parent, p, "discord", subsShown("discord"))
                pushConversationsToWatch()
            }
        }
        row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (enabled) row.addView(discordNotifyButton(guildId))
        if (enabled) row.addView(muteButton(
            "dcserver:$guildId",
            isMutedProvider = { DiscordService.serverMutedInDiscord(guildId) },
            onToggle = { desired, revert ->
                // Write Discord's real mute off the main thread; revert the light if
                // the REST call fails so the button never lies about Discord's state.
                Thread {
                    val ok = DiscordService.setServerMutedInDiscord(guildId, desired)
                    if (!ok) runOnUiThread {
                        revert()
                        android.widget.Toast.makeText(
                            this@MainActivity, "Couldn't reach Discord",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }.start()
            },
            onAttach = { refresh -> DiscordService.registerSettingRefresher("$guildId#mute", refresh) },
            onDetach = { DiscordService.unregisterSettingRefresher("$guildId#mute") },
            // Grey the mute button out when we have no settings data for this guild
            // at all (notify level resolves to -1 = unknown), matching the notify
            // control's dim state rather than implying "not muted".
            isUnknownProvider = { DiscordService.serverNotifyLevel(guildId) < 0 }
        ))
        row.addView(sw)
        parent.addView(row)
    }

    // ---- Slide-to-confirm logout -----------------------------------------

    /**
     * Native login entry point, gated by native<->Beeper mutual exclusion. If the
     * twin in the same conflictGroup is already active (e.g. Beeper Facebook when
     * the user taps Log in on native Facebook), block and tell them to turn it off
     * first — two clients on one account fight over the connection. Otherwise hand
     * off to the network's own login flow. The Beeper side enforces the mirror of
     * this in BeeperState.tryEnableAccount.
     */
    private fun attemptNativeLogin(p: Palette, svc: MessagingService) {
        val conflict = ServiceRegistry.conflictingActive(this, svc.id)
        if (conflict != null) {
            showConflictBlock(p, svc.displayName, conflict.displayName)
            return
        }
        svc.startLogin(this)
    }

    /** Both permissions Calls from the watch asks for are granted. */
    private fun hasCallPermissions(): Boolean = Calls.PERMISSIONS.all {
        checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Ask for whichever of Calls from the watch's permissions are missing. */
    private fun requestCallPermissions() {
        val missing = Calls.PERMISSIONS.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 9010)
    }

    /** True when the Beeper app is installed (visible via the manifest <queries>). */
    private fun isBeeperInstalled(): Boolean =
        try { packageManager.getPackageInfo("com.beeper.android", 0); true }
        catch (e: Exception) { false }

    /** Both Beeper content-provider permissions (read + send) granted. */
    private fun hasBeeperAccess(): Boolean =
        checkSelfPermission("com.beeper.android.permission.READ_PERMISSION") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission("com.beeper.android.permission.SEND_PERMISSION") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * "On new message" — the delivery-mode picker. Notification and Hybrid each
     * carry a sub-option (in-app notifications while the watchapp is open),
     * rendered as an indented toggle directly under its card and only while that
     * mode is selected (the mode cards call renderHome, so it appears/disappears
     * with the selection). Lives on the STATUS tab, between Status and
     * Permissions — it's the behavior a user reaches for most, and it sits next to
     * the notification permission it depends on.
     */
    private fun buildOnNewMessageSection(c: LinearLayout, p: Palette) {
        sectionLabel(c, p, "On new message")
        addModeCard(c, p, Mirror.MODE_NOTIFY, "Notification",
            "Pebble shows a notification; tap to open the message")
        if (Mirror.notifyMode == Mirror.MODE_NOTIFY)
            addModeSubToggle(c, p, "In-app notifications while watchapp is open",
                Mirror.notifyIanWhenOpen) { Mirror.setNotifyIanWhenOpen(this, it) }
        addModeCard(c, p, Mirror.MODE_FORCE, "In-app notification",
            "Jump straight to the message on your Pebble")
        addModeCard(c, p, Mirror.MODE_HYBRID, "Hybrid",
            "Notify for texts, jump to the message for pictures")
        if (Mirror.notifyMode == Mirror.MODE_HYBRID)
            addModeSubToggle(c, p, "In-app notifications while watchapp is open",
                Mirror.hybridIanWhenOpen) { Mirror.setHybridIanWhenOpen(this, it) }
    }

    /**
     * Permissions section (notification posting/access, battery, and — only when
     * the Beeper app is installed — Beeper content-provider access).
     */
    private fun buildPermissionsSection(c: LinearLayout, p: Palette) {
        sectionLabel(c, p, "Permissions")
        val permCard = card(p)
        // General permissions first (always relevant, Beeper or not).
        addCheckRow(permCard, p, "Notification posting",
            hasNotificationPermission(),
            "Lets MirrorMsg alert your Pebble on new messages") {
            openNotificationSettings()
        }
        addDivider(permCard, p)
        addCheckRow(permCard, p, "Unrestricted battery",
            isBatteryUnrestricted(),
            "Keeps MirrorMsg running so messages arrive reliably") {
            requestBatteryUnrestricted()
        }
        // Calls from the watch, while it's on: the permissions it asks for, "Display over
        // other apps" (a WhatsApp, Signal or Telegram call starts in one press; without it
        // the watch shows a Call button to press), and notification access (Hang up from
        // the watch; shown below with Beeper's when Beeper is installed).
        if (Mirror.watchCalls) {
            addDivider(permCard, p)
            addCheckRow(permCard, p, "Phone calls and contacts", hasCallPermissions(),
                "Lets the watch start calls on this phone") {
                requestCallPermissions()
            }
            addDivider(permCard, p)
            addCheckRow(permCard, p, "Display over other apps", Settings.canDrawOverlays(this),
                "Starts WhatsApp, Signal and Telegram calls from the watch in one press") {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")))
                } catch (e: Exception) { LogStore.e(TAG, "Overlay settings didn't open", e) }
            }
            if (!isBeeperInstalled()) {
                addDivider(permCard, p)
                addCheckRow(permCard, p, "Notification access",
                    hasNotificationListenerAccess(),
                    "Lets you hang up from the watch") {
                    openNotificationListenerSettings()
                }
            }
        }
        // Beeper-specific permissions grouped together at the BOTTOM, and shown ONLY
        // when the Beeper app is installed. Both are only meaningful with Beeper:
        // "Notification access" reads Beeper notifications for message images, and
        // "Beeper access" is the content-provider grant. Without Beeper they're noise,
        // so hide both (previously only "Beeper access" was gated).
        if (isBeeperInstalled()) {
            addDivider(permCard, p)
            addCheckRow(permCard, p, "Notification access",
                hasNotificationListenerAccess(),
                "Reads Beeper notifications so MirrorMsg can capture message images") {
                openNotificationListenerSettings()
            }
            addDivider(permCard, p)
            addCheckRow(permCard, p, "Beeper access",
                hasBeeperAccess(),
                "Lets MirrorMsg read and send messages through the Beeper app") {
                requestPermissions(arrayOf(
                    "com.beeper.android.permission.READ_PERMISSION",
                    "com.beeper.android.permission.SEND_PERMISSION"), 9002)
            }
        }
        c.addView(permCard)
    }

    // ---- Status tab -------------------------------------------------------

    /**
     * Status tab: an at-a-glance health light + message, and the permissions.
     * Light: yellow = no network connected; green = >=1 network connected
     * (mirroring live).
     */
    private fun buildStatusTab(c: LinearLayout, p: Palette) {
        sectionLabel(c, p, "Status", first = true)
        val connList = try { ServiceRegistry.loggedIn(this) } catch (_: Throwable) { emptyList() }
        val connected = connList.size
        val muted = connList.count { MuteState.isMuted(this, it.id) }
        val lightColor: Int
        val headline: String
        when {
            connected == 0 -> {
                lightColor = Color.parseColor("#F1C40F")
                headline = "Connect a network to start mirroring."
            }
            else -> {
                lightColor = Color.parseColor("#2ECC71")
                headline = "Mirroring active \u2014 $connected " +
                    (if (connected == 1) "account" else "accounts") + " connected."
            }
        }
        val statusCard = card(p)
        val lightRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lightRow.addView(View(this).apply { background = rounded(lightColor, 7) },
            LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(10) })
        lightRow.addView(TextView(this).apply {
            text = headline; textSize = 15f; setTextColor(p.text)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Muted count sits inline on the title row (pushed right), not on its own
        // line. The connected total in the headline already includes muted accounts.
        if (connected > 0 && muted > 0) {
            lightRow.addView(TextView(this).apply {
                text = "$muted muted"
                textSize = 13f; setTextColor(p.subtext)
                setPadding(dp(8), 0, 0, 0)
            })
        }
        statusCard.addView(lightRow)
        // Transient operation status — shown ONLY when it adds something the light
        // above doesn't already say (e.g. "Loading…", "No watch connected", "Push
        // failed…"). Steady-state connection messages are suppressed so the same
        // information never appears twice.
        statusCard.addView(TextView(this).apply {
            text = statusMsg; textSize = 13f; setTextColor(p.subtext)
            setPadding(0, dp(8), 0, 0)
            visibility = if (isHeadlineRedundant(statusMsg)) View.GONE else View.VISIBLE
        }.also { statusView = it })
        c.addView(statusCard)

        buildOnNewMessageSection(c, p)

        buildPermissionsSection(c, p)

        // View logs lives at the very bottom of the Status tab (moved here from
        // Settings) — diagnostics belong with the status/health surface.
        c.addView(TextView(this).apply {
            text = "View logs"; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(p.subtext); setPadding(0, dp(24), 0, dp(8))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, LogViewerActivity::class.java))
            }
        })
    }

    /** Styled "can't turn this on, its twin is active" notice (matches our dialogs). */
    private fun showConflictBlock(p: Palette, attempted: String, active: String) {
        val dlg = Dialog(this)
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = rounded(p.card, 18)
        }
        root.addView(TextView(this).apply {
            text = "Can't turn on $attempted"
            textSize = 18f; setTextColor(p.text)
        })
        root.addView(TextView(this).apply {
            text = "$active is already active. Turn it off first — the same account " +
                "can't run through two connections at once."
            textSize = 13f; setTextColor(p.subtext)
            setPadding(0, dp(8), 0, dp(20))
        })
        root.addView(TextView(this).apply {
            text = "Got it"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(p.accent); setPadding(0, dp(6), 0, 0)
            setOnClickListener { dlg.dismiss() }
        })
        dlg.setContentView(root, ViewGroup.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT))
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dlg.show()
    }

    private fun showLogoutConfirm(p: Palette, service: MessagingService) {
        val dlg = Dialog(this)
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = rounded(p.card, 18)
        }
        root.addView(TextView(this).apply {
            text = "Log out of ${service.displayName}?"
            textSize = 18f; setTextColor(p.text)
        })
        root.addView(TextView(this).apply {
            text = "This clears your saved login. You'll need to sign in again to keep mirroring messages."
            textSize = 13f; setTextColor(p.subtext)
            setPadding(0, dp(8), 0, dp(20))
        })

        // Slide track.
        val trackH = dp(52)
        val thumbW = dp(64)
        val track = FrameLayout(this).apply {
            background = rounded(p.cardBorder, trackH / 2)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, trackH)
        }
        val hint = TextView(this).apply {
            text = "Slide to log out  \u203A\u203A"
            textSize = 14f; setTextColor(p.subtext)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val thumb = TextView(this).apply {
            text = "\u203A"
            textSize = 22f; setTextColor(p.onAccent)
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#C0392B"), trackH / 2)  // red = destructive
            layoutParams = FrameLayout.LayoutParams(thumbW, trackH)
        }
        track.addView(hint)
        track.addView(thumb)
        root.addView(track)

        // Cancel.
        root.addView(TextView(this).apply {
            text = "Cancel"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(p.subtext); setPadding(0, dp(18), 0, 0)
            setOnClickListener { dlg.dismiss() }
        })

        // Drag handling: thumb follows finger; release past ~75% confirms.
        var downX = 0f
        var startTx = 0f
        track.post {
            val maxX = (track.width - thumbW).toFloat()
            thumb.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = ev.rawX; startTx = v.translationX; true }
                    MotionEvent.ACTION_MOVE -> {
                        var nx = startTx + (ev.rawX - downX)
                        if (nx < 0f) nx = 0f
                        if (nx > maxX) nx = maxX
                        v.translationX = nx
                        hint.alpha = 1f - (nx / maxX).coerceIn(0f, 1f)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (v.translationX >= maxX * 0.75f) {
                            v.translationX = maxX
                            dlg.dismiss()
                            performLogout(service)
                        } else {
                            v.animate().translationX(0f).setDuration(150).start()
                            hint.animate().alpha(1f).setDuration(150).start()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        dlg.setContentView(root, ViewGroup.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT))
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dlg.show()
    }

    private fun performLogout(service: MessagingService) {
        LogStore.i(TAG, "Logging out of ${service.displayName}.")
        // 1. Let the service clear its own session/state (Google clears its
        //    session + cached number; Facebook clears its fb_session, etc.).
        service.logout(this)
        // 2. Wipe WebView cookies so the next login starts fresh. (Both Google
        //    and Facebook log in via harvested WebView cookies, so this is
        //    shared cleanup — harmless for services that don't use it.)
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        } catch (e: Exception) { LogStore.e(TAG, "Cookie clear failed", e) }
        // 3. Back to the normal home UI, now showing the logged-out state.
        //    (Login WebView only opens when the user taps "Sign in".)
        runOnUiThread { showHome("All accounts logged out") }
    }

    private fun addDivider(parent: LinearLayout, p: Palette) {
        parent.addView(android.view.View(this).apply {
            setBackgroundColor(p.cardBorder)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(4); bottomMargin = dp(4)
            }
        })
    }

    private fun accentButton(p: Palette, label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label; setTextColor(p.onAccent); textSize = 16f
            isAllCaps = false
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(p.accent, 12)
            stateListAnimator = null            // kill default elevation shadow offset
            setOnClickListener { onClick() }
        }

    // ---- Notification permission helpers ----------------------------------

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else true
    }

    private fun openNotificationSettings() {
        // Take the user to MirrorMsg's notification settings page.
        try {
            val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(i)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        }
    }

    // ---- Notification-access (listener) helpers ---------------------------

    private fun hasNotificationListenerAccess(): Boolean {
        return try {
            val flat = Settings.Secure.getString(
                contentResolver, "enabled_notification_listeners") ?: return false
            val mine = android.content.ComponentName(
                this, BeeperNotificationListenerService::class.java)
            flat.split(":").any {
                android.content.ComponentName.unflattenFromString(it) == mine
            }
        } catch (e: Exception) { false }
    }

    private fun openNotificationListenerSettings() {
        // Deep-link straight to our listener's toggle on API 30+; fall back to
        // the listener list, then app details, so the user always lands
        // somewhere they can grant it.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cn = android.content.ComponentName(
                    this, BeeperNotificationListenerService::class.java)
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                    putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        cn.flattenToString())
                })
                return
            }
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e2: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            }
        }
    }

    // ---- Battery-optimization helpers -------------------------------------

    private fun isBatteryUnrestricted(): Boolean {
        return try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE)
                as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) { false }
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryUnrestricted() {
        // Prefer the direct "ignore optimizations for this app" dialog; fall
        // back to the general battery-optimization list if unavailable.
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            }
        }
    }

    private fun setStatus(s: String) {
        statusMsg = s   // keep stored value in sync so renderHome() won't revert to stale text
        runOnUiThread {
            statusView?.let { v ->
                v.text = s
                v.visibility = if (isHeadlineRedundant(s)) View.GONE else View.VISIBLE
            }
        }
    }

    /**
     * True if a status message merely restates what the Status light + headline
     * already convey (overall key/connection/mirroring state), so the subtitle
     * stays hidden rather than repeat it. Operational, watch, and error messages
     * ("Loading…", "No watch connected", "Push failed…") are NOT redundant.
     */
    private fun isHeadlineRedundant(msg: String): Boolean {
        val m = msg.trim()
        if (m.isBlank()) return true
        if (m == "All accounts logged out") return true
        if (m == "Connected. Mirroring live.") return true
        if (m.endsWith("Mirroring live")) return true  // "$count accounts connected · Mirroring live"
        return false
    }

    // ---- Push conversation list to the watch --------------------------

    private fun pushConversationsToWatch() {
        setStatus("Loading conversations\u2026")
        pebbleScope.launch {
            try {
                // Pull from the ACTIVE service (Google or Facebook), not a hardwired
                // bridge. Each service returns the same conversation JSON shape; FB
                // simply omits the SIM/RCS fields, which the optJSONObject/optString
                // reads below tolerate via defaults.
                val svc = Mirror.activeService()
                    ?: run { setStatus("No active service."); return@launch }
                if (!svc.isLoggedIn()) { setStatus("${svc.displayName} not connected yet."); return@launch }
                val json = svc.listConversations(MAX_CONVOS)
                val arr = JSONObject(json).getJSONArray("conversations")
                val count = minOf(arr.length(), MAX_CONVOS)

                val sender = Mirror.sender(this@MainActivity)
                val launched = sender.startAppOnTheWatch(Schema.WATCH_UUID, watches = null)
                if (launched == null) { setStatus("Pebble app unreachable"); return@launch }
                if (launched.isEmpty()) { setStatus("No watch connected"); return@launch }
                delay(1500)

                // Tell the watch which networks exist + which is active, so holding
                // up reveals the switcher rail with the right selection.
                pushServiceListToWatch(sender)

                Mirror.convoOrder.clear()
                Mirror.simRoutes.clear()
                var sent = 0
                for (i in 0 until count) {
                    val c = arr.getJSONObject(i)
                    Mirror.convoOrder.add(c.optString("conversationID"))

                    // Cache SIM routing info for SendMessage (reply path). Absent for
                    // Facebook — defaults to "2"/2/2, harmlessly unused since FB send
                    // ignores SIM routing.
                    val participantID = c.optString("defaultOutgoingID", "2")
                    val simData = c.optJSONObject("simCard")?.optJSONObject("SIMData")
                    val simPayload = simData?.optJSONObject("SIMPayload")
                    val simTwo    = (simPayload?.optInt("two", 2) ?: 2).toLong()
                    val simNumber = (simPayload?.optInt("SIMNumber", 2) ?: 2).toLong()
                    Mirror.simRoutes.add(Mirror.SimRoute(participantID, simTwo, simNumber))
                    // The account's own number is the same across conversations;
                    // capture it once for the account subtitle. (Google only.)
                    if (Mirror.accountNumber.isEmpty()) {
                        val num = simData?.optString("formattedPhoneNumber") ?: ""
                        if (num.isNotEmpty()) Mirror.setAccountNumber(this@MainActivity, num)
                    }

                    val name    = c.optString("name").ifEmpty { "(no name)" }
                    val snippet = c.optJSONObject("latestMessage")
                        ?.optString("displayContent")?.take(SNIPPET_MAX) ?: ""
                    val color   = convoColor(c)
                    val type    = when (c.optString("type")) {
                        "RCS" -> 4
                        "FB"  -> 5
                        else  -> 1
                    }
                    val unread  = if (c.optBoolean("unread", false)) 1 else 0

                    val frame = mapOf(
                        Schema.KEY_OP      to PebbleDictionaryItem.Int32(Schema.OP_CONVO),
                        Schema.KEY_IDX     to PebbleDictionaryItem.Int32(i),
                        Schema.KEY_COUNT   to PebbleDictionaryItem.Int32(count),
                        Schema.KEY_NAME    to PebbleDictionaryItem.Text(name),
                        Schema.KEY_SNIPPET to PebbleDictionaryItem.Text(snippet),
                        Schema.KEY_COLOR   to PebbleDictionaryItem.Text(color),
                        Schema.KEY_TYPE    to PebbleDictionaryItem.Int32(type),
                        Schema.KEY_UNREAD  to PebbleDictionaryItem.Int32(unread)
                    )
                    val r = sender.sendDataToPebble(Schema.WATCH_UUID, frame, watches = null)
                    LogStore.i(TAG, "convo $i -> $r")
                    sent++
                    delay(150)
                }
                setStatus("Pushed $sent ${svc.displayName} conversations. Tap one on the watch.")
            } catch (e: Exception) {
                LogStore.e(TAG, "Push failed", e)
                setStatus("Push failed: ${e.message}")
            }
        }
    }

    /**
     * Reports the list of logged-in networks + the active one to the watch, so
     * the watch's account-switcher rail (revealed by holding up in conversation
     * view) shows the right options and selection. Sent as a \x1e-separated label
     * list (KEY_SERVICES) plus the active index (KEY_ACTIVE_SERVICE).
     */
    private suspend fun pushServiceListToWatch(sender: PebbleSender) {
        try {
            // Same augmented rail as PebbleListenerService.reportServiceList (built
            // via Mirror so both emitters + the selector agree): logged-in services
            // plus each enabled Telegram folder as an indented sub-account.
            val entries = Mirror.switcherEntries(this)
            if (entries.size < 2) return  // no switcher needed for a single account
            Mirror.lastSwitcherEntries = entries
            val labels = entries.joinToString("\u001e") { it.label }
            val activeIdx = entries.indexOfFirst {
                it.serviceId == Mirror.activeServiceId &&
                    it.folderId == Mirror.activeTgFolder &&
                    it.guildId == Mirror.activeDiscordGuild
            }.coerceAtLeast(0)
            val serviceFlags = Mirror.switcherFlags(this, entries)
            val frame = mapOf(
                Schema.KEY_OP             to PebbleDictionaryItem.Int32(Schema.OP_CONVO),
                Schema.KEY_SERVICES       to PebbleDictionaryItem.Text(labels),
                Schema.KEY_ACTIVE_SERVICE to PebbleDictionaryItem.Int32(activeIdx),
                Schema.KEY_SERVICE_FLAGS  to PebbleDictionaryItem.Int32(serviceFlags)
            )
            sender.sendDataToPebble(Schema.WATCH_UUID, frame, watches = null)
            LogStore.i(TAG, "Pushed service list to watch: [$labels] active=$activeIdx flags=$serviceFlags")
        } catch (e: Exception) {
            LogStore.e(TAG, "pushServiceListToWatch failed", e)
        }
    }

    private fun convoColor(c: JSONObject): String {
        c.optString("avatarHexColor").let { if (it.isNotEmpty()) return it }
        val parts = c.optJSONArray("participants")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (!p.optBoolean("isMe", false)) {
                    val col = p.optString("avatarHexColor")
                    if (col.isNotEmpty()) return col
                }
            }
        }
        return "#7E57C2"
    }

    // ---- Restore path -------------------------------------------------

    private fun restoreAndConnect(savedSession: String) {
        // The anchor service may already have restored Google headlessly (it runs
        // at boot, with no Activity). Creating a second bridge here would open a
        // SECOND long-poll on the same account and double-deliver every message,
        // so adopt the existing one instead. (Interactive login is unaffected: it
        // builds its own bridge through startWebViewLogin, which is what installs
        // the UI-capable sink needed for the pairing emoji.)
        val existing = Mirror.bridge
        if (existing != null) {
            LogStore.i(TAG, "Google bridge already live (headless restore) — adopting it.")
            connectRegisteredServices()
            runOnUiThread { reportConnectionStatus() }
            return
        }
        try {
            val b = Mirrorbridge.newBridge(savedSession, sink)
            Mirror.bridge = b
            if (b.isLoggedIn) {
                b.connect()
                LogStore.i(TAG, "Restored + connected (google).")
            } else {
                prefs.edit().remove(PREF_SESSION).apply()
            }
        } catch (e: Exception) {
            LogStore.e(TAG, "Restore failed; clearing google session.", e)
            prefs.edit().remove(PREF_SESSION).apply()
        }
        // Connect every other registered service, then report status. Split out so it
        // can ALSO run when there's no Google session (serverless/unlocked boot) — see
        // connectRegisteredServices.
        connectRegisteredServices()
        reportConnectionStatus()
    }

    /**
     * Connect every registered NON-Google service (Facebook, Discord, Telegram, and
     * all Beeper networks) via each service's own self-gating connectIfPossible(),
     * which reads that service's own saved session/cookies and no-ops if it can't
     * connect. This MUST run on every startup regardless of the Google session state:
     * the app is serverless/unlocked, so a boot with no Google session still needs the
     * Beeper live ContentObserver + poll (registered inside connectIfPossible) and the
     * other services' connections brought up. Gating this behind the Google session
     * left every Beeper network with no live updates or notifications (read-only).
     */
    private fun connectRegisteredServices() {
        try {
            for (svc in ServiceRegistry.available(this)) {
                if (svc.id == "google") continue  // Google restored via Mirror.bridge
                try {
                    val ok = svc.connectIfPossible()
                    LogStore.i(TAG, "Connect ${svc.id}: connectIfPossible=$ok")
                } catch (e: Throwable) {
                    LogStore.e(TAG, "Connect ${svc.id} failed", e)
                }
            }
        } catch (e: Throwable) {
            LogStore.e(TAG, "Registered-service connect failed", e)
        }
    }

    /**
     * Sets the home status line based on what's actually logged in across every
     * service (Google + registry services), instead of the old Google-only check.
     */
    private fun reportConnectionStatus() {
        var count = 0
        // Google isn't a registry service in the same way; check its bridge.
        if (Mirror.bridge?.isLoggedIn == true) count++
        try {
            for (svc in ServiceRegistry.loggedIn(this)) {
                if (svc.id == "google") continue
                count++
            }
        } catch (_: Throwable) {}

        if (count == 0) {
            runOnUiThread { showHome("All accounts logged out") }
        } else {
            val noun = if (count == 1) "account" else "accounts"
            val msg = "$count $noun connected · Mirroring live"
            setStatus(msg)
            LogStore.i(TAG, msg)
        }
    }

    // ---- WebView login path -------------------------------------------

    internal fun startWebViewLogin() {
        homeShowing = false
        thread(name = "bridge-init") {
            try { Mirror.bridge = Mirrorbridge.newBridge("", sink) }
            catch (e: Exception) { LogStore.e(TAG, "Bridge init failed", e) }
        }

        val wv = WebView(this)
        webView = wv

        // The WebView used to go straight into setContentView with no top padding
        // at all, so the page's own content started drawing at y=0 and clipped
        // under the status bar. Wrap it in a container and pad for the REAL,
        // live system-bar inset (not a static resource-id guess) so it holds up
        // across notches/cutouts/gesture-nav and any status-bar height Android
        // reports at runtime.
        val container = FrameLayout(this)
        container.addView(wv, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.systemBars()).top
            } else {
                @Suppress("DEPRECATION") top = insets.systemWindowInsetTop
            }
            v.setPadding(0, top, 0, 0)
            insets
        }
        container.requestApplyInsets()
        setContentView(container)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // Do NOT override UA — WebView's honest default passes Google's gate.

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && url.contains("messages.google.com/web") && !paired) {
                    val cookies = harvestCookies()
                    if (!cookies.contains("__Secure-3PSID")) {
                        LogStore.w(TAG, "At messages page but __Secure-3PSID not set yet; waiting.")
                        return
                    }
                    paired = true
                    LogStore.i(TAG, "Login complete; harvested cookies. Pairing…")
                    pairWithCookies(cookies)
                }
            }
        }
        wv.loadUrl(LOGIN_URL)
    }

    private fun harvestCookies(): String {
        val cm = CookieManager.getInstance()
        cm.flush()
        val json = JSONObject()
        for (u in COOKIE_URLS) {
            val raw = cm.getCookie(u) ?: continue
            for (pair in raw.split("; ")) {
                val i = pair.indexOf('=')
                if (i > 0) json.put(pair.substring(0, i), pair.substring(i + 1))
            }
        }
        LogStore.i(TAG, "Harvested ${json.length()} cookies.")
        return json.toString()
    }

    private fun pairWithCookies(cookiesJson: String) {
        thread(name = "bridge-pair") {
            try {
                val b = Mirror.bridge ?: Mirrorbridge.newBridge("", sink).also { Mirror.bridge = it }
                b.setCookies(cookiesJson)
                LogStore.i(TAG, "Gaia pairing — confirm the emoji on your phone…")
                b.startGaiaPairing()
                val s = b.exportSession()
                Vault.putString(prefs, PREF_SESSION, s)   // sealed in the Keystore
                LogStore.i(TAG, "Paired and session saved (${s.length} chars).")
                b.connect()
                LogStore.i(TAG, "Connected.")
                runOnUiThread {
                    webView = null
                    showHome("Connected. Mirroring live.")
                }
            } catch (e: Exception) {
                LogStore.e(TAG, "Pairing failed", e)
                paired = false
            }
        }
    }

    override fun onDestroy() {
        Mirror.onSoundChanged = null   // the Settings picker goes with this screen
        webView?.destroy()
        // Leave Mirror.bridge alive — PebbleListenerService may still need it.
        super.onDestroy()
    }
}
