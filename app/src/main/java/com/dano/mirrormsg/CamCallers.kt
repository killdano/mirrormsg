package com.dano.mirrormsg

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Who may use the Dash Cam picker and send service: MirrorMap Dash, signed with a
 * pinned certificate. Fingerprints are the SHA-256 of the signing certificate, as
 * `keytool -printcert -jarfile <apk>` or Play Console's App signing page shows them.
 * A certificate fingerprint is public (anyone holding the APK can read it).
 */
object CamCallers {

    /**
     * MirrorMap Dash as Google Play installs it: Play's app signing key.
     * TODO: add it from Play Console (MirrorMap Dash > App integrity > App signing,
     * "App signing key certificate", SHA-256). Until then release builds accept nobody.
     */
    private val RELEASE: List<String> = emptyList()

    /** MirrorMap Dash test builds (its shared debug key). Accepted by debug builds only. */
    private val DEBUG: List<String> = listOf(
        "55:2D:9D:59:A0:64:C3:BA:F1:21:E0:21:5F:13:47:4C:50:B4:74:F9:E8:6D:AD:9D:66:7F:E5:CA:37:84:66:9C",
    )

    private fun pins(): List<ByteArray> =
        (if (BuildConfig.DEBUG) RELEASE + DEBUG else RELEASE).mapNotNull { CamRules.hexBytes(it) }

    /** True when [callerPackage] is MirrorMap Dash, signed with a pinned certificate. */
    fun isDash(ctx: Context, callerPackage: String?): Boolean {
        val pkg = callerPackage ?: return false
        if (pkg != CamRules.DASH_PACKAGE) return false
        val pins = pins()
        if (pins.isEmpty()) return false
        val pm = ctx.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pins.any { pm.hasSigningCertificate(pkg, it, PackageManager.CERT_INPUT_SHA256) }
            } else {
                @Suppress("DEPRECATION")
                val sigs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
                !sigs.isNullOrEmpty() && sigs.all { s ->
                    val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                    pins.any { it.contentEquals(d) }
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /** True when [uid] (a binder caller's) is MirrorMap Dash's, signed with a pinned certificate. */
    fun isDashUid(ctx: Context, uid: Int): Boolean {
        if (uid < 0) return false
        val pkgs = try { ctx.packageManager.getPackagesForUid(uid) } catch (e: Exception) { null } ?: return false
        return CamRules.DASH_PACKAGE in pkgs && isDash(ctx, CamRules.DASH_PACKAGE)
    }
}
