package com.ilkay.redmigateway.server

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Redmi's health as the iPhone sees it: one snapshot, replaced whenever it is re-evaluated.
 *
 * The owner never looks at the Redmi (it stays in a pocket), so a problem found here only matters
 * once it reaches the iPhone. HealthReporter publishes; the WebSocket server sends [latest] to every
 * iPhone that authenticates and forwards each change to the ones already connected.
 *
 * [publish] takes a change key next to the snapshot because not every re-evaluation is news: a
 * battery level that moved by one percent must not wake the iPhone (AGENTS.md 8.1), while a lost
 * vendor fix must. Callers build the key from the fields that deserve a message.
 *
 * The snapshot shape is the `health` frame in docs/PROTOCOL.md.
 */
object HealthBus {
    fun interface Listener {
        fun onHealth(snapshot: JSONObject)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile private var latest: JSONObject? = null
    private var latestKey: String? = null

    /** A copy of the last published snapshot, or null before the first one. */
    fun latest(): JSONObject? = latest?.let { JSONObject(it.toString()) }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Stores [snapshot] as the latest one and notifies listeners only when [changeKey] differs
     * from the previous key. Returns whether listeners were notified.
     */
    fun publish(snapshot: JSONObject, changeKey: String): Boolean {
        val copy = JSONObject(snapshot.toString())
        val changed = synchronized(this) {
            latest = copy
            if (changeKey == latestKey) {
                false
            } else {
                latestKey = changeKey
                true
            }
        }
        if (!changed) return false
        // Each listener gets its own copy and a failure in one cannot stop the others.
        listeners.forEach { listener -> runCatching { listener.onHealth(JSONObject(copy.toString())) } }
        return true
    }

    internal fun resetForTest() {
        synchronized(this) {
            listeners.clear()
            latest = null
            latestKey = null
        }
    }
}
