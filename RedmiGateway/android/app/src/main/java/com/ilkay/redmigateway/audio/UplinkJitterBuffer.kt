package com.ilkay.redmigateway.audio

import java.util.ArrayDeque

/**
 * Single bounded queue shared by the UDP receiver and the blocking audio writer.
 *
 * The depth is adaptive because this link delivers 20 ms frames in bursts: a measured call showed
 * `maxAuthenticatedArrivalGapMs=240` (nominal 20 ms) followed by `maxAuthenticatedArrivalBurstFrames=10`
 * — the Wi-Fi retry storm releases ten frames at once after a quarter second of silence. A fixed
 * depth therefore fails twice over: it drops the tail of a burst that arrives above the ceiling and
 * it runs dry during the silence that follows a burst it did absorb.
 *
 * The target depth is the worst recent need over a four second window, so a burst raises the depth
 * immediately (fast attack) and four clean seconds let it fall back to the floor (slow decay).
 * Ceilings stay inside the walkie-talkie budget: 240 ms on a local link, 480 ms on a remote/VPN link
 * whose stalls are longer. The hard queue limit is only a memory guard; the trim keeps latency at
 * the target.
 */
class UplinkJitterBuffer {
    data class Snapshot(
        val queued: Int,
        val dropped: Int,
        val underruns: Int,
        val expired: Int,
        val maxAuthenticatedArrivalGapMs: Long,
        val maxAuthenticatedArrivalBurstFrames: Int,
        /** Current adaptive target depth in frames (20 ms each). */
        val targetFrames: Int
    )

    private data class QueuedFrame(val pcm: ByteArray, val arrivedAtMs: Long)

    private companion object {
        const val FRAME_MS = 20
        /** Arrival observations are kept this long: fast attack, four second decay. */
        const val HISTORY_WINDOW_MS = 4_000L
        const val HISTORY_SAMPLES = 32
        const val TRIM_HOLD_MS = 500L
        /** Trimming only starts two frames above the target, so a short spike is not cut away. */
        const val TRIM_SLACK_FRAMES = 2
        /** A paced link resumes on a two frame reserve; a bursty one waits for up to four. */
        const val PRIME_FRAMES_MIN = 2
        const val PRIME_FRAMES_MAX = 4
        /** Three starved polls (60 ms) mean the gap is real: wait for the reserve again. */
        const val UNPRIME_AFTER_UNDERRUNS = 3
        const val LOCAL_TARGET_FLOOR = 2
        const val REMOTE_TARGET_FLOOR = 3
        /** 12 * 20 ms = 240 ms: a local link must not feel like a walkie-talkie. */
        const val LOCAL_TARGET_CEILING = 12
        /** 24 * 20 ms = 480 ms: mobile data and VPN paths stall for longer. */
        const val REMOTE_TARGET_CEILING = 24
        const val LOCAL_MAX_AGE_MS = 400L
        const val REMOTE_MAX_AGE_MS = 640L
        /**
         * Frames delivered back to back count as one burst. Wi-Fi clumps are forwarded a few
         * milliseconds apart rather than in the same instant, so this is a fifth of the 20 ms
         * pacing: a real burst is recognised while a paced stream never looks like one.
         */
        const val BURST_GAP_MS = 12L
    }

    private val queue = ArrayDeque<QueuedFrame>()
    private var primed = false
    private var consecutiveUnderruns = 0
    private var highSince: Long? = null
    private var dropped = 0
    private var underruns = 0
    private var expiredFrames = 0
    private var remoteLink = false
    private var lastArrivalAt: Long? = null
    private var burstFrames = 0
    private var lastAuthenticatedArrivalAt: Long? = null
    private var maxAuthenticatedArrivalGapMs = 0L
    private var maxAuthenticatedArrivalBurstFrames = 0
    // Diagnostics must not report 0 before the first poll: a fresh buffer already aims at the floor.
    private var targetFrames = LOCAL_TARGET_FLOOR

    // Rolling window of "how deep the buffer had to be" observations, newest replaces oldest.
    private val needAt = LongArray(HISTORY_SAMPLES)
    private val needFrames = IntArray(HISTORY_SAMPLES)
    private var needIndex = 0

    @Synchronized fun reset(remote: Boolean = false) {
        clear()
        dropped = 0
        underruns = 0
        expiredFrames = 0
        remoteLink = remote
        lastArrivalAt = null
        burstFrames = 0
        lastAuthenticatedArrivalAt = null
        maxAuthenticatedArrivalGapMs = 0
        maxAuthenticatedArrivalBurstFrames = 0
        needAt.fill(0)
        needFrames.fill(0)
        needIndex = 0
        targetFrames = floor()
    }

    /** Stop playback without erasing the failure evidence needed after a fallback. */
    @Synchronized fun clear() {
        queue.clear()
        primed = false
        consecutiveUnderruns = 0
        highSince = null
    }

    @Synchronized fun offer(frame: ByteArray, nowMs: Long, authenticated: Boolean = false) {
        val gapMs = lastArrivalAt?.let { nowMs - it }
        if (gapMs != null) {
            burstFrames = if (gapMs <= BURST_GAP_MS) burstFrames + 1 else 1
            observe(gapMs, burstFrames, nowMs)
        } else {
            burstFrames = 1
        }
        lastArrivalAt = nowMs
        if (authenticated) {
            val authenticatedGap = lastAuthenticatedArrivalAt?.let { nowMs - it }
            if (authenticatedGap != null) {
                maxAuthenticatedArrivalGapMs = maxOf(maxAuthenticatedArrivalGapMs, authenticatedGap)
                maxAuthenticatedArrivalBurstFrames = maxOf(maxAuthenticatedArrivalBurstFrames, burstFrames)
            }
            lastAuthenticatedArrivalAt = nowMs
        }
        // The hard limit is the documented ceiling itself (12 frames local, 24 remote), so even a
        // huge burst can never leave more than 240 ms / 480 ms queued. The adaptive trim below
        // only lowers the depth further.
        val maxQueue = ceiling()
        while (queue.size >= maxQueue) {
            queue.removeFirst()
            dropped++
        }
        queue.addLast(QueuedFrame(frame, nowMs))
    }

    /**
     * How deep the buffer has to be to survive what the link just did: a burst of N frames has to
     * fit, and a silence of G ms has to be covered until the next frame arrives.
     */
    private fun observe(gapMs: Long, burst: Int, nowMs: Long) {
        val gapFrames = if (gapMs > FRAME_MS) ((gapMs - FRAME_MS + FRAME_MS - 1) / FRAME_MS).toInt() else 0
        val need = maxOf(gapFrames, burst) + 1
        if (need <= floor()) return
        needAt[needIndex] = nowMs
        needFrames[needIndex] = minOf(need, ceiling())
        needIndex = (needIndex + 1) % HISTORY_SAMPLES
        targetFrames = target(nowMs)
    }

    /** Worst need still inside the window; unused slots never raise the target. */
    private fun target(nowMs: Long): Int {
        var need = floor()
        for (i in 0 until HISTORY_SAMPLES) {
            if (needFrames[i] > need && nowMs - needAt[i] <= HISTORY_WINDOW_MS) need = needFrames[i]
        }
        return need.coerceIn(floor(), ceiling())
    }

    private fun floor() = if (remoteLink) REMOTE_TARGET_FLOOR else LOCAL_TARGET_FLOOR

    private fun ceiling() = if (remoteLink) REMOTE_TARGET_CEILING else LOCAL_TARGET_CEILING

    /**
     * How much reserve playback waits for after a starvation. A paced link needs two frames; one
     * that just bursted waits for four, because that link is the one that will starve again.
     */
    private fun primeFrames() = targetFrames.coerceIn(PRIME_FRAMES_MIN, PRIME_FRAMES_MAX)

    @Synchronized fun poll(nowMs: Long): ByteArray? {
        val wasPrimed = primed
        var expired = false
        val maxAge = if (remoteLink) REMOTE_MAX_AGE_MS else LOCAL_MAX_AGE_MS
        // The depth target bounds queued duration, but a blocked audio writer can still leave old PCM.
        while (queue.peekFirst()?.let { nowMs - it.arrivedAtMs > maxAge } == true) {
            queue.removeFirst()
            dropped++
            expiredFrames++
            expired = true
        }
        if (expired) {
            primed = false
            consecutiveUnderruns = 0
            highSince = null
        }
        val current = target(nowMs)
        targetFrames = current
        if (queue.size > current + TRIM_SLACK_FRAMES) {
            val since = highSince
            if (since == null) highSince = nowMs
            else if (nowMs - since > TRIM_HOLD_MS) {
                queue.removeFirst()
                dropped++
                highSince = nowMs
            }
        } else highSince = null
        if (!primed && queue.size >= primeFrames()) {
            primed = true
            consecutiveUnderruns = 0
        }
        val frame = if (primed) queue.pollFirst() else null
        if (frame != null) {
            consecutiveUnderruns = 0
        } else {
            if (primed || (expired && wasPrimed)) {
                underruns++
                consecutiveUnderruns++
                if (consecutiveUnderruns >= UNPRIME_AFTER_UNDERRUNS) {
                    primed = false
                }
            }
        }
        return frame?.pcm
    }

    @Synchronized fun snapshot() = Snapshot(queue.size, dropped, underruns, expiredFrames,
        maxAuthenticatedArrivalGapMs, maxAuthenticatedArrivalBurstFrames, targetFrames)
}
