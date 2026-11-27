package com.dano.mirrormsg.testbuild

import android.content.Context
import android.widget.TextView

/**
 * Release builds: no test-build updater. The debug source set's copy of this
 * object installs newer test builds by itself; this one keeps release builds
 * free of the updater and the install permissions.
 */
internal object TestBuilds {
    val available: Boolean = false
    val autoInstall: Boolean = false

    fun setAutoInstall(context: Context, on: Boolean) {}

    fun bindDetail(view: TextView) {}

    fun detail(): String = ""
}
