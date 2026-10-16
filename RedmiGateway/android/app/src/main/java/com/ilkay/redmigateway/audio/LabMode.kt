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
    internal const val DURATION_MS = 2 * 60 * 60 * 1000L
    @Volatile private var enabledUntil = 0L

    val enabled: Boolean get() = labModeEnabled(SystemClock.elapsedRealtime(), enabledUntil)

    fun set(enable: Boolean) {
        enabledUntil = labModeDeadline(SystemClock.elapsedRealtime(), enable)
        Log.i("LabMode", "Lab mode ${if (enable) "enabled for 2 h" else "disabled"}")
    }
}

class LabModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.ilkay.redmigateway.LAB_MODE") LabMode.set(intent.getBooleanExtra("enable", false))
    }
}


/**
 * Whether the measurement hooks are allowed to run right now.
 *
 * They change audio routing and mutes, so being wrong in the permissive direction is the expensive
 * one: an app that thinks lab mode is on in normal use quietly changes how a call sounds. The
 * default is therefore off, represented as a deadline of zero, and the comparison is strict, so a
 * deadline exactly at the current instant is already expired.
 */
internal fun labModeEnabled(now: Long, enabledUntil: Long): Boolean = now < enabledUntil

/**
 * The new expiry instant for a request to turn lab mode on or off.
 *
 * Off is a zero deadline rather than a past one, so it reads as never enabled rather than expired a
 * moment ago. On adds the fixed two hours. Lab mode lives in memory only, so a process restart
 * clears it whatever this returns.
 */
internal fun labModeDeadline(now: Long, enable: Boolean): Long =
    if (enable) now + LabMode.DURATION_MS else 0L
