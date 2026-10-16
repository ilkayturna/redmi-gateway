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

    /**
     * Whether the iPhone's stream is good enough to open the audio path for it.
     *
     * [recentRate] is measured over the latest sampling interval, never as the average since the
     * first frame. The iPhone ramps up - a measured call started at 14 frames per second and
     * reached 50 - and an average keeps that slow start in the denominator long after the stream
     * itself is healthy: the handover waited 1431 ms while the last second was already running at
     * 53 frames per second. The evidence standard is unchanged: this much continuous audio, and
     * the iPhone reports that it hears the Redmi.
     */
    fun canStartMedia(
        live: Boolean,
        iphoneFresh: Boolean,
        streamMs: Long,
        streamFrames: Int,
        recentRate: Int,
        minStreamMs: Long,
        minFrames: Int,
        minFramesPerSecond: Int
    ): Boolean = startMediaBlocker(
        live, iphoneFresh, streamMs, streamFrames, recentRate,
        minStreamMs, minFrames, minFramesPerSecond) == READY

    const val READY = "ready"

    /**
     * The first condition that keeps the audio path closed, in the order the decision applies them.
     * The log then names the condition that waited instead of leaving it to be guessed: an iPhone
     * that ramps up slowly and a gate that is merely strict look identical in a single total, and
     * they are fixed in different places.
     */
    fun startMediaBlocker(
        live: Boolean,
        iphoneFresh: Boolean,
        streamMs: Long,
        streamFrames: Int,
        recentRate: Int,
        minStreamMs: Long,
        minFrames: Int,
        minFramesPerSecond: Int
    ): String = when {
        !live -> "notLive"
        !iphoneFresh -> "stale"
        streamMs < minStreamMs -> "tooShort"
        streamFrames < minFrames -> "tooFewFrames"
        recentRate < minFramesPerSecond -> "rateLow"
        else -> READY
    }
}
