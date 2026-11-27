package com.dano.mirrormsg.feedback

import android.app.Activity
import android.content.Context
import android.os.Build
import com.dano.mirrormsg.LogStore
import com.dano.mirrormsg.Vault
import com.mirrorapps.kit.KitHost
import com.mirrorapps.kit.KitLog

/**
 * Debug (test) builds: the Feedback screen from the feedback module, set up
 * with MirrorMsg's log, its bug-report sections ([MsgBundle]) and the owner
 * code, which is sealed in the app's [Vault]. The release source set has a
 * no-op copy of this object.
 */
internal object FeedbackFlavor {
    val available: Boolean = true

    private const val PREFS = "mirrormsg_feedback_code"
    private const val KEY_CODE = "owner_code"

    fun open(activity: Activity) {
        try {
            init(activity)
            MsgFeedback.open(activity)
        } catch (t: Throwable) {
            LogStore.e(TAG, "Feedback: couldn't open", t)
        }
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
                saveCode = { Vault.putString(prefs, KEY_CODE, it ?: "") },
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
