package com.ilkay.redmigateway.audio

import java.util.ArrayDeque

/**
 * Single bounded queue shared by the UDP receiver and the blocking audio writer.
 *
 * The depth is adaptive because this link delivers 20 ms frames in bursts: the latest measured call
 * showed `maxAuthenticatedArrivalGapMs=439` (nominal 20 ms) followed by
 * `maxAuthenticatedArrivalBurstFrames=26` - the Wi-Fi retry storm released 520 ms of speech at once
 * after nearly half a second of silence. A fixed depth therefore fails twice over: it drops the
 * tail of a burst that arrives above the ceiling and it runs dry during the silence that follows a
 * burst it did absorb.
 *
 * The target depth is the worst recent need over a ten second window, so a burst raises the depth
 * immediately (fast attack) and ten clean seconds let it fall back to the floor (slow decay). Build
 * 123 measured 315-439 ms gaps against the old 240 ms ceiling and dropped 34 frames; the target
 * ceiling is now 480 ms local and 720 ms remote. The hard queue limit (640/960 ms) is a memory guard
 * above the target, and the age watchdog is 800/1100 ms so a deep reserve is not expired before it
 * plays. The extra walkie-talkie delay is the price of a link that stalls for half a second; a paced
 * link still stays at the 40 ms floor.
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
        /** Arrival observations are kept this long: fast attack, ten second decay. */
        const val HISTORY_WINDOW_MS = 10_000L
        /**
         * Room for those observations. The ring counts arrivals, not seconds, and a burst records
         * one observation per frame (a ten frame clump writes about nine), so a small ring forgets
         * a spike long before the window ends: 32 slots covered roughly 1.3 s of this link's
         * clumped traffic, which is why one measured call showed a 338 ms gap and a target of 6 in
         * the same health line. 512 slots cover ten seconds of that traffic, comfortably past the
         * ten second window, for about 6 KB. The test that pins this is
         * UplinkJitterBufferTest.theMemoryRingHoldsAFullWindowOfClumpedTraffic.
         */
        const val HISTORY_SAMPLES = 512
        const val TRIM_HOLD_MS = 500L
        /** Trimming only starts two frames above the target, so a short spike is not cut away. */
        const val TRIM_SLACK_FRAMES = 2
        /**
         * A paced link resumes on an 80 ms reserve; a link that just proved it can stall for
         * hundreds of milliseconds waits for the measured reserve. The old eight-frame (160 ms)
         * maximum could not cover the 315-439 ms gaps seen in build 123, so re-priming repeated
         * the same underrun. Twenty-four frames (480 ms) cover the worst local gap plus slack.
         */
        const val PRIME_FRAMES_MIN = 4
        const val PRIME_FRAMES_MAX = 24
        /** Three starved polls (60 ms) mean the gap is real: wait for the reserve again. */
        const val UNPRIME_AFTER_UNDERRUNS = 3
        const val LOCAL_TARGET_FLOOR = 2
        const val REMOTE_TARGET_FLOOR = 3
        /** 24 * 20 ms = 480 ms: cover the measured 439 ms local gap. */
        const val LOCAL_TARGET_CEILING = 24
        /** 36 * 20 ms = 720 ms: mobile data and VPN paths stall for longer. */
        const val REMOTE_TARGET_CEILING = 36
        /** A 26-frame burst can exceed the target briefly; this is only a memory guard. */
        const val LOCAL_QUEUE_LIMIT = 32
        const val REMOTE_QUEUE_LIMIT = 48
        /** The watchdog must outlive the deepest target, or it would expire its own reserve. */
        const val LOCAL_MAX_AGE_MS = 800L
        const val REMOTE_MAX_AGE_MS = 1_100L
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
    /** Raised by [reset] while the sender duplicates frames, so a copy still lands in time. */
    private var floorOverride = 0

    // Rolling window of "how deep the buffer had to be" observations, newest replaces oldest.
    private val needAt = LongArray(HISTORY_SAMPLES)
    private val needFrames = IntArray(HISTORY_SAMPLES)
    private var needIndex = 0

    @Synchronized fun reset(remote: Boolean = false, minTargetFrames: Int = 0) {
        clear()
        dropped = 0
        underruns = 0
        expiredFrames = 0
        remoteLink = remote
        // A redundant sender repeats the previous frame in the next packet, and that copy only
        // helps if the buffer still holds the frame it belongs to: the caller asks for a slightly
        // deeper floor while redundancy is on.
        floorOverride = minTargetFrames.coerceAtLeast(0)
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
        // The hard limit is above the adaptive target so a legitimate 26-frame burst can land
        // without discarding its tail; the trim below brings latency back to the target.
        val maxQueue = queueLimit()
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
        // A silence needs two frames of slack: the writer consumes one while the next arrival is
        // due, and a 439 ms gap with only one frame of slack still underran in the field. A burst
        // needs one frame so its tail is not immediately trimmed away.
        val need = maxOf(gapFrames + 2, burst + 1)
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

    private fun floor() = maxOf(if (remoteLink) REMOTE_TARGET_FLOOR else LOCAL_TARGET_FLOOR, floorOverride)

    private fun ceiling() = if (remoteLink) REMOTE_TARGET_CEILING else LOCAL_TARGET_CEILING

    private fun queueLimit() = if (remoteLink) REMOTE_QUEUE_LIMIT else LOCAL_QUEUE_LIMIT

    /**
     * How much reserve playback waits for after a starvation. The target includes one frame of
     * slack over the measured need, so the reserve is one less. A paced link gets 80 ms; a link
     * that just proved a 439 ms gap waits for up to 480 ms, because that is the one that will
     * starve again.
     */
    private fun primeFrames() =
        (targetFrames - 1).coerceIn(PRIME_FRAMES_MIN, PRIME_FRAMES_MAX)

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

