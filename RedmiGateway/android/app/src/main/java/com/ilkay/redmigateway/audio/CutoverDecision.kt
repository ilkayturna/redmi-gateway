package com.ilkay.redmigateway.audio

/**
 * The one rule that decides whether the Redmi's own microphone and earpiece may be muted and the
 * call handed to the iPhone.
 *
 * The rate checks alone are not enough: the counters include concealed frames, so a link that
 * delivered a burst and then died keeps "writing" at full rate while nothing real arrives. The
 * Redmi's devices are already isolated at that point, and the fallback that notices costs seconds
 * of one-way silence. Both directions therefore have to be flowing *right now*, which a live 20 ms
 * stream always satisfies and a dead one never does.
 *
 * Kept free of Android types so every branch is unit tested.
 */
internal object CutoverDecision {
    /**
     * How recently each direction must have delivered audio. This is six nominal frame periods: a
     * healthy stream is 0-20 ms old, and even the measured Wi-Fi gaps (209 ms) only defer the
     * handover to the next decision instead of forbidding it.
     */
    const val FRESHNESS_MS = 120L

    fun canHandOver(
        live: Boolean,
        writesRate: Int,
        readsRate: Int,
        minFramesPerSecond: Int,
        uplinkAgeMs: Long,
        downlinkAgeMs: Long,
        iphoneFresh: Boolean,
        iphoneHearing: Boolean
    ): Boolean = live &&
        writesRate >= minFramesPerSecond &&
        readsRate >= minFramesPerSecond &&
        uplinkAgeMs in 0..FRESHNESS_MS &&
        downlinkAgeMs in 0..FRESHNESS_MS &&
        iphoneFresh &&
        iphoneHearing
}
