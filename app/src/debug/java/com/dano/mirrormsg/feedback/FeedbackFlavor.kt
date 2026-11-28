package com.dano.mirrormsg.feedback

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dano.mirrormsg.LogStore
import com.dano.mirrormsg.Vault
import com.dano.mirrormsg.testbuild.TestBuilds
import com.mirrorapps.kit.KitHost
import com.mirrorapps.kit.KitLog

/**
 * Debug (test) builds: the Feedback screen from the feedback module, set up
 * with MirrorMsg's log, its bug-report sections ([MsgBundle]) and the owner
 * code, which is sealed in the app's [Vault]. The owner code is also the
 * test-build updater's credential ([ownerCode], testbuild/TestBuilds.kt). The
 * release source set has a no-op copy of this object.
 */
internal object FeedbackFlavor {
    val available: Boolean = true

    private const val PREFS = "mirrormsg_feedback_code"
    private const val KEY_CODE = "owner_code"

    /** The Feedback screen's request code; its result is a home tab tapped there. */
    private const val REQ_FEEDBACK = 9310

    /**
     * Opens the Feedback screen as the home screen's last tab: it draws [tabs]
     * (the other tabs' labels, in order) with Feedback selected after them.
     */
    fun open(activity: Activity, tabs: List<String>) {
        try {
            init(activity)
            MsgFeedback.open(activity, tabs, REQ_FEEDBACK)
        } catch (t: Throwable) {
            LogStore.e(TAG, "Feedback: couldn't open", t)
        }
    }

    /**
     * For the home screen's onActivityResult: the position in open's tabs of the
     * tab tapped on the Feedback screen, or -1 (Back, or another screen's result).
     */
    fun tabChosen(requestCode: Int, resultCode: Int, data: Intent?): Int =
        if (requestCode == REQ_FEEDBACK) MsgFeedback.tabChosen(resultCode, data) else -1

    /**
     * The owner code saved on the Feedback screen, or null. Read from the
     * vault each time, so it works before the Feedback screen was ever opened.
     */
    fun ownerCode(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Vault.getString(prefs, KEY_CODE).trim().ifEmpty { null }
    }

    private fun init(context: Context) {
        if (MsgFeedback.isInitialized) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        MsgFeedback.init(
            app,
            MsgFeedback.Host(
                host = KitHost("MirrorMsg", info.versionName ?: "?", code, debug = true),
                log = MsgKitLog,
                bundle = MsgBundle,
                loadCode = { Vault.getString(prefs, KEY_CODE).ifEmpty { null } },
                saveCode = {
                    Vault.putString(prefs, KEY_CODE, it ?: "")
                    if (!it.isNullOrBlank()) TestBuilds.ownerCodeSet()
                },
            ),
        )
        LogStore.i(TAG, "Feedback: kit set up")
    }

    private const val TAG = "Feedback"
}

/**
 * The kit logs into the app's log, and a bug report attaches that log. Every
 * line in it was scrubbed when it was written (LogStore.scrub).
 */
private object MsgKitLog : KitLog {
    override fun d(tag: String, message: String) = LogStore.d(tag, message)
    override fun i(tag: String, message: String) = LogStore.i(tag, message)
    override fun w(tag: String, message: String, t: Throwable?) =
        LogStore.w(tag, message + (t?.let { " :: ${it.javaClass.simpleName}" } ?: ""))

    override fun snapshotForReport(): String = LogStore.dump()
}
