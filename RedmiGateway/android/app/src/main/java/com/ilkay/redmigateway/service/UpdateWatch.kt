package com.ilkay.redmigateway.service

import android.content.Context
import android.os.Build

/**
 * Remembers which ROM build the phone was last seen running.
 *
 * The vendor audio fix and the priv-app install are both outside the app, in partitions an OTA
 * replaces. When the build string changes, every one of them is suspect, so the service says so
 * on the first start after the update instead of waiting for a silent call.
 *
 * The build before the last change and the time of that change are kept too: the iPhone learns
 * about the update whenever it next connects, which may be long after the one service start that
 * noticed it (docs/WAVE1-CONTRACT.md section 4, "rom").
 */
object UpdateWatch {
    private const val PREFS = "gateway_update_watch"
    private const val KEY_BUILD = "last_build"
    private const val KEY_PREVIOUS = "previous_build"
    private const val KEY_CHANGED_AT = "changed_at"

    /**
     * What is known about the ROM.
     *
     * @param build the build seen at the last check
     * @param previous the build before the last real change, or null if none was ever seen
     * @param changedAt when that change was noticed (epoch ms), or null
     */
    data class Rom(val build: String, val previous: String?, val changedAt: Long?)

    /** The build string the service compares: incremental and display id together. */
    fun currentBuild(): String = "${Build.VERSION.INCREMENTAL}|${Build.DISPLAY}"

    /**
     * Whether [current] is a different build from [previous].
     *
     * No previous build (first run, fresh install) is not a change: announcing an update that
     * never happened on every new install would teach the user to ignore the one that matters.
     */
    fun changed(previous: String?, current: String): Boolean =
        !previous.isNullOrBlank() && previous != current

    /**
     * The record after seeing [current] at [now]. A real change moves the stored build into
     * "previous" and stamps the time; anything else keeps the last change as it was, so a later
     * restart does not erase what the iPhone has not been told yet.
     */
    fun next(stored: Rom?, current: String, now: Long): Rom = when {
        stored == null -> Rom(current, null, null)
        changed(stored.build, current) -> Rom(current, stored.build, now)
        else -> stored.copy(build = current)
    }

    fun lastBuild(context: Context): String? = prefs(context).getString(KEY_BUILD, null)

    /** The stored record, or null before the first check. */
    fun rom(context: Context): Rom? {
        val prefs = prefs(context)
        val build = prefs.getString(KEY_BUILD, null) ?: return null
        val changedAt = prefs.getLong(KEY_CHANGED_AT, 0L).takeIf { it > 0L }
        return Rom(build, prefs.getString(KEY_PREVIOUS, null), changedAt)
    }

    /** Records [current] and answers whether it differs from the build seen before. */
    fun check(context: Context, current: String): Boolean {
        val stored = rom(context)
        val updated = next(stored, current, System.currentTimeMillis())
        val editor = prefs(context).edit().putString(KEY_BUILD, updated.build)
        if (updated.previous != null) editor.putString(KEY_PREVIOUS, updated.previous) else editor.remove(KEY_PREVIOUS)
        if (updated.changedAt != null) editor.putLong(KEY_CHANGED_AT, updated.changedAt) else editor.remove(KEY_CHANGED_AT)
        editor.apply()
        return changed(stored?.build, current)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
