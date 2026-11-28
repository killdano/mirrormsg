package com.dano.mirrormsg.feedback

import android.Manifest
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dano.mirrormsg.BeeperState
import com.dano.mirrormsg.LogStore
import com.dano.mirrormsg.MuteState
import com.dano.mirrormsg.ServiceRegistry
import com.mirrorapps.kit.BundleContributor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * MirrorMsg's sections of a bug report's diagnostic bundle. The kit adds the
 * app version, the device and the log; these are what only the app knows.
 *
 * Counts, states and times only. Never message text, contact or chat names,
 * numbers or account names: networks are named by their service id
 * ("whatsapp", "telegram"), never by the account logged in to them. These
 * sections go in every "Logs in bug reports" mode, None included.
 */
internal object MsgBundle : BundleContributor {

    override fun contribute(context: Context, app: String, bundle: JSONObject) {
        bundle.put("networks", networks(context))
        bundle.put("beeper", JSONObject().apply {
            put("installed", BeeperState.isInstalled(context))
            put("access", BeeperState.hasPermissions(context))
            put("enabled", BeeperState.masterEnabled(context))
        })
        bundle.put("permissions", permissions(context))
        bundle.put("power", power(context))
        bundle.put("watch", watch())
    }

    private fun networks(context: Context) = JSONArray().apply {
        for (s in ServiceRegistry.available(context)) {
            put(JSONObject().apply {
                put("id", s.id)
                put("loggedIn", runCatching { s.isLoggedIn() }.getOrDefault(false))
                put("active", runCatching { s.isActive(context) }.getOrDefault(false))
                put("muted", MuteState.isMuted(context, s.id))
            })
        }
    }

    private fun permissions(context: Context) = JSONObject().apply {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            put("postNotifications", granted(Manifest.permission.POST_NOTIFICATIONS))
        }
        put("notificationsEnabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
        put("notificationAccess",
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
        put("displayOverApps", Settings.canDrawOverlays(context))
        put("microphone", granted(Manifest.permission.RECORD_AUDIO))
    }

    private fun power(context: Context) = JSONObject().apply {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        put("batteryUnrestricted", pm.isIgnoringBatteryOptimizations(context.packageName))
        put("powerSave", pm.isPowerSaveMode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            put("backgroundRestricted", am.isBackgroundRestricted)
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            put("standbyBucket", usm.appStandbyBucket)
        }
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        put("batteryPercent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        put("charging", bm.isCharging)
    }

    /**
     * What the log says about the watch: how many diagnostic lines it sent
     * (they're in the log itself, which follows the logs setting) and how
     * long ago the last one came.
     */
    private fun watch() = JSONObject().apply {
        val lines = LogStore.dump().lineSequence().filter { it.contains(WATCH_DIAG) }.toList()
        put("diagLines", lines.size)
        val last = lines.lastOrNull()?.let { minutesAgo(it) }
        put("minutesSinceDiag", last ?: JSONObject.NULL)
    }

    /** Minutes since a log line's "MM-dd HH:mm:ss.SSS" stamp, or null. */
    private fun minutesAgo(line: String): Long? = runCatching {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).parse(line.take(18)) ?: return null
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply {
            time = stamp
            set(Calendar.YEAR, now.get(Calendar.YEAR))
            if (after(now)) add(Calendar.YEAR, -1)
        }
        (now.timeInMillis - then.timeInMillis) / 60_000
    }.getOrNull()

    /** PebbleListenerService logs each watch diagnostic line with this. */
    private const val WATCH_DIAG = "WATCH-DIAG:"
}
