package com.ilkay.redmigateway.ble

/**
 * When the Redmi lights its beacon to bring back an iPhone that has no link at all.
 *
 * The pause was meant to grow (three minutes, then ten, then thirty) so an idle iPhone is not woken
 * all night. It never grew: each pulse woke the iPhone, its socket authenticated and iOS suspended
 * the app about 50-100 ms later, and that brief link reset the count. On 30.10.2026 the Redmi woke
 * the iPhone every five minutes for hours (its Bluetooth link was down, see BleBondWatch), and every
 * wake kept the iPhone busy for up to 25 s. The same fault was fixed for the silent push
 * (CallWakePush.noteSocketHealthy): only a link that held for [LINK_HEALTHY_MS] starts over.
 *
 * Calls and new messages do not wait for any of this: a ringing or outgoing call lights the beacon
 * at once, and a new SMS or missed call pulses immediately.
 */
internal object ReconnectPulsePolicy {
    /**
     * The first wait after a link that worked ends: blips and Bluetooth restarts heal by themselves.
     *
     * Twenty seconds was the old value and it turned a normal iOS suspension into a wake loop: the
     * iPhone connects, iOS suspends it about fifty milliseconds later (measured 17.10.2026 14:21:05
     * "iPhone linked again" and 22 ms later "iPhone has no link"), and the pulse woke it again - 15
     * connections and 8 pulses in one idle hour. A phone that is genuinely away (across the room, a
     * Bluetooth restart, a reboot) still comes back within five minutes.
     */
    const val GRACE_MS = 5 * 60_000L
    /** A link that stayed up this long was a real recovery, not a woken app suspended again. */
    const val LINK_HEALTHY_MS = 60_000L

    /** The pause after the [pulses]-th pulse of a run. */
    fun pauseAfterPulse(pulses: Int): Long = when {
        pulses < 3 -> 3 * 60_000L
        pulses < 10 -> 10 * 60_000L
        else -> 30 * 60_000L
    }

    /**
     * The iPhone's link ended after [heldMs] (null when its start was not seen): the pulse count
     * that still applies, and how long until the next pulse. Never sooner than [GRACE_MS].
     */
    fun afterLinkEnds(heldMs: Long?, pulses: Int): Pair<Int, Long> {
        val count = if (heldMs != null && heldMs >= LINK_HEALTHY_MS) 0 else pulses
        val delay = if (count == 0) GRACE_MS else maxOf(GRACE_MS, pauseAfterPulse(count))
        return count to delay
    }
}
