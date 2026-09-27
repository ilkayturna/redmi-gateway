package com.ilkay.redmigateway.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Gate for the measurement hooks in [CallAudioLab]. They change audio routing and mutes, so they
 * are off in normal use and can only be switched on from a computer over adb:
 *
 *   adb shell am broadcast -a com.ilkay.redmigateway.LAB_MODE --ez enable true \
 *       -n com.ilkay.redmigateway/.audio.LabModeReceiver
 *
 * The receiver requires android.permission.DUMP, which the adb shell holds and apps do not.
 * Lab mode lives in memory only and expires after two hours or when the process restarts.
 */
object LabMode {
    private const val DURATION_MS = 2 * 60 * 60 * 1000L
    @Volatile private var enabledUntil = 0L

    val enabled: Boolean get() = SystemClock.elapsedRealtime() < enabledUntil

    fun set(enable: Boolean) {
        enabledUntil = if (enable) SystemClock.elapsedRealtime() + DURATION_MS else 0L
        Log.i("LabMode", "Lab mode ${if (enable) "enabled for 2 h" else "disabled"}")
    }
}

class LabModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.ilkay.redmigateway.LAB_MODE") LabMode.set(intent.getBooleanExtra("enable", false))
    }
}
