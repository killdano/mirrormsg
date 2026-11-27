package com.dano.mirrormsg.feedback

import android.app.Activity

/**
 * Release builds: no Feedback screen. The debug source set's copy of this
 * object sets up the kit and opens it; this one keeps release builds free of
 * the kit, Compose and the microphone permission.
 */
internal object FeedbackFlavor {
    val available: Boolean = false

    fun open(activity: Activity) {}
}
