package com.dano.mirrormsg.testbuild

import android.app.Application
import android.content.Context
import android.widget.TextView
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dano.mirrormsg.BuildConfig
import com.dano.mirrormsg.LogStore
import com.dano.mirrormsg.Mirror
import com.dano.mirrormsg.feedback.FeedbackFlavor
import com.mirrorapps.kit.updater.TestBuildUpdater
import com.mirrorapps.kit.updater.TestBuildUpdater.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/**
 * Debug (test) builds: install newer test builds by themselves, the way
 * MirrorMap Dash's test builds do (mirrorapps-kit's updater/). The release
 * source set's copy of this object does nothing, and release builds don't
 * have the updater (`debugImplementation(project(":kit-updater"))`) or its
 * permissions (src/debug/AndroidManifest.xml).
 *
 * Checks for a newer test build when the app's process starts, whenever one
 * of its screens comes to the front (at most once a minute), when the owner
 * code is saved on the Feedback screen, and every 3 hours ([TestBuildWorker]).
 * The backend answers only the owner's code. An install waits while
 * MirrorMsg is open on the watch, because installing restarts the app and
 * cuts the watch off for a moment; it's tried again 15 minutes later. Every
 * step is logged under `TestBuildUpdater`.
 */
internal object TestBuilds {

    val available: Boolean = true

    private const val TAG = "TestBuildUpdater"
    private const val WORK = "test-build-check"
    private const val RETRY = "test-build-retry"
    private const val BASE_URL = "https://api.mirrormap.app"

    /** Test builds are versionCode 100000 + N (android.yml), tag test-N. */
    private const val TEST_CODE_BASE = 100_000L

    /**
     * The longest an open watch app holds an install: a missed "watch app
     * closed" must not hold updates for good.
     */
    private const val WATCH_HOLD_MS = 2L * 60 * 60 * 1000

    private const val RETRY_MINUTES = 15L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var app: Application? = null
    @Volatile private var heldSince = 0L
    @Volatile private var detailView: WeakReference<TextView>? = null

    fun start(application: Application) {
        if (app != null) return
        app = application
        LogStore.init(application)
        try {
            TestBuildUpdater.init(
                application,
                TestBuildUpdater.Config(
                    appId = "mirrormsg",
                    appLabel = "MirrorMsg",
                    baseUrl = BASE_URL,
                    installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    http = OkHttpClient.Builder()
                        .connectTimeout(20, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build(),
                    credential = { FeedbackFlavor.ownerCode(application) },
                    log = object : TestBuildUpdater.Log {
                        override fun i(message: String) = LogStore.i(TAG, message)
                        override fun w(message: String, error: Throwable?) =
                            LogStore.w(TAG, message + (error?.let { " :: ${it.javaClass.simpleName}" } ?: ""))
                    },
                    smallIcon = android.R.drawable.stat_sys_download_done,
                    holdInstall = { holdReason() },
                ),
            )
            schedule(application, TestBuildUpdater.state.value.autoInstall)
            // The Settings row's detail line follows the updater.
            scope.launch {
                TestBuildUpdater.state.collect { s -> detailView?.get()?.text = detail(s) }
            }
        } catch (t: Throwable) {
            LogStore.e(TAG, "couldn't start the test-build updater", t)
        }
    }

    /** The Feedback screen saved an owner code: a chance to check now. */
    fun ownerCodeSet() {
        if (app != null) TestBuildUpdater.checkSoon("owner code set")
    }

    val autoInstall: Boolean get() = TestBuildUpdater.state.value.autoInstall

    fun setAutoInstall(context: Context, on: Boolean) {
        TestBuildUpdater.setAutoInstall(on)
        schedule(context.applicationContext, on)
    }

    /** The Settings row's detail line, kept current while [view] is on screen. */
    fun bindDetail(view: TextView) {
        detailView = WeakReference(view)
        view.text = detail()
    }

    fun detail(): String = detail(TestBuildUpdater.state.value)

    private fun schedule(context: Context, on: Boolean) {
        try {
            val wm = WorkManager.getInstance(context)
            if (!on) {
                wm.cancelUniqueWork(WORK)
                wm.cancelUniqueWork(RETRY)
                return
            }
            val request = PeriodicWorkRequestBuilder<TestBuildWorker>(3, TimeUnit.HOURS)
                .setConstraints(online())
                .setInputData(workDataOf(TestBuildWorker.REASON to "every 3 hours"))
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (t: Throwable) {
            LogStore.e(TAG, "couldn't schedule the test-build check", t)
        }
    }

    private fun online(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Why an install should wait for now: installing restarts MirrorMsg. */
    private fun holdReason(): String? {
        if (!Mirror.watchAppOpen) {
            heldSince = 0L
            return null
        }
        val now = System.currentTimeMillis()
        if (heldSince == 0L) heldSince = now
        if (now - heldSince >= WATCH_HOLD_MS) return null
        retryLater()
        return "MirrorMsg is open on the watch"
    }

    /** One more check in [RETRY_MINUTES], for a held install. */
    private fun retryLater() {
        val ctx = app ?: return
        try {
            val request = OneTimeWorkRequestBuilder<TestBuildWorker>()
                .setInitialDelay(RETRY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(online())
                .setInputData(workDataOf(TestBuildWorker.REASON to "held install"))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(RETRY, ExistingWorkPolicy.KEEP, request)
        } catch (t: Throwable) {
            LogStore.e(TAG, "couldn't schedule the retry", t)
        }
    }

    private fun testName(code: Long): String =
        if (code > TEST_CODE_BASE) "test ${code - TEST_CODE_BASE}" else "$code"

    /** Installed and newest, then what's happening. */
    private fun detail(s: TestBuildUpdater.State): String {
        val installed = if (s.installedCode > TEST_CODE_BASE) testName(s.installedCode)
            else "${BuildConfig.VERSION_NAME} (${s.installedCode})"
        val newest = s.newest?.let { testName(it.versionCode) } ?: "not checked yet"
        val doing = when (s.phase) {
            Phase.CHECKING -> "checking"
            Phase.DOWNLOADING -> "downloading ${s.newest?.let { testName(it.versionCode) } ?: ""}".trim()
            Phase.INSTALLING -> "installing"
            Phase.WAITING_FOR_TAP -> "tap the notification to install"
            Phase.HELD -> "waiting: ${s.note}"
            Phase.FAILED -> s.note
            Phase.IDLE -> s.note?.let { if (it == "no access code yet") "set the owner code below" else it }
            Phase.UP_TO_DATE, Phase.NEWER_BUT_OFF -> null
        }
        return "Installed $installed · newest $newest" + (doing?.let { " · $it" } ?: "")
    }
}

/** The 3-hourly check, and the retry of a held install. */
class TestBuildWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        TestBuildUpdater.check(inputData.getString(REASON) ?: "scheduled")
        return Result.success()
    }

    companion object {
        const val REASON = "reason"
    }
}
