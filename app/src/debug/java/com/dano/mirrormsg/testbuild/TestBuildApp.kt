package com.dano.mirrormsg.testbuild

import android.app.Application

/**
 * Debug (test) builds' Application, named only in src/debug/AndroidManifest.xml.
 * It starts the test-build updater ([TestBuilds]) whenever the app's process
 * starts: the app opened, the anchor service after a boot or an update, or a
 * scheduled check. Release builds have no Application class of their own.
 */
class TestBuildApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TestBuilds.start(this)
    }
}
