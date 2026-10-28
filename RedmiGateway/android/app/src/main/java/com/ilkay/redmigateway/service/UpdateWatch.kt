package com.ilkay.redmigateway.service

import android.content.Context

/**
 * Remembers which ROM build the phone was last seen running.
 *
 * The vendor audio fix and the priv-app install are both outside the app, in partitions an OTA
 * replaces. When the build string changes, every one of them is suspect, so the service says so
 * on the first start after the update instead of waiting for a silent call.
 */
object UpdateWatch {
    private const val PREFS = "gateway_update_watch"
    private const val KEY_BUILD = "last_build"

    /**
     * Whether [current] is a different build from [previous].
     *
     * No previous build (first run, fresh install) is not a change: announcing an update that
     * never happened on every new install would teach the user to ignore the one that matters.
     */
    fun changed(previous: String?, current: String): Boolean =
        !previous.isNullOrBlank() && previous != current

    fun lastBuild(context: Context): String? = prefs(context).getString(KEY_BUILD, null)

    /** Records [current] and answers whether it differs from the build seen before. */
    fun check(context: Context, current: String): Boolean {
        val changed = changed(lastBuild(context), current)
        prefs(context).edit().putString(KEY_BUILD, current).apply()
        return changed
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
