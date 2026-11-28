package com.dano.mirrormsg.feedback

import android.app.Activity
import android.content.Intent

/**
 * Release builds: no Feedback screen. The debug source set's copy of this
 * object sets up the kit and opens it; this one keeps release builds free of
 * the kit, Compose and the microphone permission.
 */
internal object FeedbackFlavor {
    val available: Boolean = false

    fun open(activity: Activity, tabs: List<String>) {}

    fun tabChosen(requestCode: Int, resultCode: Int, data: Intent?): Int = -1
}
