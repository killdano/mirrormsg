package com.dano.mirrormsg.feedback

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mirrorapps.kit.BundleContributor
import com.mirrorapps.kit.FeedbackOptions
import com.mirrorapps.kit.KitApp
import com.mirrorapps.kit.KitColors
import com.mirrorapps.kit.KitConfig
import com.mirrorapps.kit.KitHost
import com.mirrorapps.kit.KitLog
import com.mirrorapps.kit.KitTheme
import com.mirrorapps.kit.LogsMode
import com.mirrorapps.kit.MirrorAppsKit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MirrorMsg's side of mirrorapps-kit's Feedback tab: the hub app id, the
 * app's colours, and the two settings that belong to Feedback (the owner
 * code and "Logs in bug reports"). What only the app knows (its log, its
 * bug-report sections, where the code is stored) comes in through [Host].
 *
 * A bug report never holds message text, contact or chat names, numbers or
 * account names: the app's log is scrubbed as it's written, logcat is left
 * out ([FeedbackOptions.logcat] = false, because the bridges log there
 * unscrubbed), and the bundle sections are counts and states only.
 */
object MsgFeedback {

    /** The hub's app id. A wire value: never rename it. */
    const val APP = "mirrormsg"

    /** What the app supplies. */
    class Host(
        val host: KitHost,
        val log: KitLog,
        val bundle: BundleContributor,
        /** The stored owner code, or null. */
        val loadCode: () -> String?,
        /** Stores the owner code (null clears it). */
        val saveCode: (String?) -> Unit,
    )

    private const val PREFS = "mirrormsg_feedback"
    private const val KEY_LOGS = "logs_mode"

    private val code = MutableStateFlow<String?>(null)

    @Volatile
    private var host: Host? = null

    val isInitialized: Boolean get() = host != null

    /** The owner code right now (null when none is set). */
    val ownerCode: StateFlow<String?> get() = code

    /** Sets up the kit. Safe to call more than once; the first call wins. */
    @Synchronized
    fun init(context: Context, host: Host) {
        if (this.host != null) return
        val app = context.applicationContext
        code.value = runCatching { host.loadCode() }.getOrNull()?.takeIf { it.isNotBlank() }
        MirrorAppsKit.init(
            app,
            KitConfig(
                apps = listOf(KitApp(APP, "MirrorMsg")),
                host = host.host,
                credential = code,
                logSource = host.log,
                theme = MsgTheme,
                bundleContributors = listOf(host.bundle),
                feedback = FeedbackOptions(
                    logsMode = { logsMode(app) },
                    setLogsMode = { setLogsMode(app, it) },
                    installIdPrefs = PREFS,
                    logName = "log",
                    fullLogsText = "Full logs can include the names of networks and chats. " +
                        "They're only sent with a report you start, never on their own. " +
                        "You can change this at the top of this screen.",
                    app = APP,
                    logcat = false,
                ),
            ),
        )
        this.host = host
    }

    private const val EXTRA_TABS = "com.dano.mirrormsg.feedback.TABS"
    private const val EXTRA_TAB = "com.dano.mirrormsg.feedback.TAB"

    /**
     * Opens the Feedback screen as the last tab of the app's home screen: it
     * draws the home header and [tabs] (the other tabs' labels, in order) with
     * Feedback selected after them. Tapping one of them closes it with that
     * tab's position as the result ([tabChosen]). Neither way slides, so it
     * looks like a tab switch.
     */
    fun open(activity: Activity, tabs: List<String>, requestCode: Int) {
        activity.startActivityForResult(
            Intent(activity, FeedbackActivity::class.java).putExtra(EXTRA_TABS, tabs.toTypedArray()),
            requestCode,
        )
        // From Android 14 the Feedback screen turns its own transitions off.
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
    }

    /** The position of the home tab tapped on the Feedback screen, or -1 (Back). */
    fun tabChosen(resultCode: Int, data: Intent?): Int =
        if (resultCode == Activity.RESULT_OK) data?.getIntExtra(EXTRA_TAB, -1) ?: -1 else -1

    internal fun tabs(intent: Intent): List<String> =
        intent.getStringArrayExtra(EXTRA_TABS)?.toList().orEmpty()

    internal fun tabResult(position: Int): Intent = Intent().putExtra(EXTRA_TAB, position)

    /** Saves the owner code; blank clears it. */
    fun setOwnerCode(value: String?) {
        val v = value?.trim()?.takeIf { it.isNotEmpty() }
        host?.saveCode?.invoke(v)
        code.value = v
    }

    fun logsMode(context: Context): LogsMode {
        val wire = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOGS, null)
        return LogsMode.entries.firstOrNull { it.wire == wire } ?: LogsMode.REDACTED
    }

    fun setLogsMode(context: Context, mode: LogsMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOGS, mode.wire).apply()
    }
}

/** MirrorMsg's palette, day and night (MainActivity's palette()). */
internal object MsgTheme : KitTheme {
    val accent = Color(0xFF7C4DFF)

    @Composable
    override fun colors(): KitColors = if (isSystemInDarkTheme()) {
        KitColors(
            bg = Color(0xFF0E0E10),
            fg = Color(0xFFF2F2F5),
            cardBg = Color(0xFF1C1C1F),
            cardDivider = Color(0xFF2A2A2E),
            sectionFg = Color(0xFF9A9AA2),
            helpFg = Color(0xFF9A9AA2),
            accent = accent,
            accentFg = Color.White,
            disabledFg = Color(0xFF5A5A60),
        )
    } else {
        KitColors(
            bg = Color(0xFFF5F4F8),
            fg = Color(0xFF1A1A1E),
            cardBg = Color.White,
            cardDivider = Color(0xFFE2E0E8),
            sectionFg = Color(0xFF6B6B73),
            helpFg = Color(0xFF6B6B73),
            accent = accent,
            accentFg = Color.White,
            disabledFg = Color(0xFFB0B0B8),
        )
    }
}
