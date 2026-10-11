package com.ilkay.redmigateway.audio

import java.util.ArrayDeque

/** Single bounded queue shared by the UDP receiver and the blocking audio writer. */
class UplinkJitterBuffer {
    data class Snapshot(
        val queued: Int,
        val dropped: Int,
        val underruns: Int,
        val expired: Int,
        val maxAuthenticatedArrivalGapMs: Long,
        val maxAuthenticatedArrivalBurstFrames: Int
    )

    private data class QueuedFrame(val pcm: ByteArray, val arrivedAtMs: Long)
    private val queue = ArrayDeque<QueuedFrame>()
    private var primed = false
    private var consecutiveUnderruns = 0
    private var highSince: Long? = null
    private var dropped = 0
    private var underruns = 0
    private var expiredFrames = 0
    private var remoteLink = false
    private var lastAuthenticatedArrivalAt: Long? = null
    private var authenticatedBurstFrames = 0
    private var maxAuthenticatedArrivalGapMs = 0L
    private var maxAuthenticatedArrivalBurstFrames = 0

    @Synchronized fun reset(remote: Boolean = false) {
        clear()
        dropped = 0
        underruns = 0
        expiredFrames = 0
        remoteLink = remote
        lastAuthenticatedArrivalAt = null
        authenticatedBurstFrames = 0
        maxAuthenticatedArrivalGapMs = 0
        maxAuthenticatedArrivalBurstFrames = 0
    }

    /** Stop playback without erasing the failure evidence needed after a fallback. */
    @Synchronized fun clear() {
        queue.clear()
        primed = false
        consecutiveUnderruns = 0
        highSince = null
    }

    @Synchronized fun offer(frame: ByteArray, nowMs: Long, authenticated: Boolean = false) {
        if (authenticated) {
            val gapMs = lastAuthenticatedArrivalAt?.let { nowMs - it }
            if (gapMs != null) maxAuthenticatedArrivalGapMs = maxOf(maxAuthenticatedArrivalGapMs, gapMs)
            // Consecutive arrivals within 5 ms represent a burst rather than paced 20 ms PCM.
            authenticatedBurstFrames = if (gapMs != null && gapMs <= 5) authenticatedBurstFrames + 1 else 1
            maxAuthenticatedArrivalBurstFrames = maxOf(maxAuthenticatedArrivalBurstFrames, authenticatedBurstFrames)
            lastAuthenticatedArrivalAt = nowMs
        }
        val maxQueue = if (remoteLink) 12 else 10
        while (queue.size >= maxQueue) {
            queue.removeFirst()
            dropped++
        }
        queue.addLast(QueuedFrame(frame, nowMs))
    }

    @Synchronized fun poll(nowMs: Long): ByteArray? {
        val wasPrimed = primed
        var expired = false
        // Depth bounds queued duration, but a blocked audio writer can still leave old PCM.
        while (queue.peekFirst()?.let { nowMs - it.arrivedAtMs > 400 } == true) {
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
        val trimThreshold = if (remoteLink) 8 else 6
        if (queue.size > trimThreshold) {
            val since = highSince
            if (since == null) highSince = nowMs
            else if (nowMs - since > 500) {
                queue.removeFirst()
                dropped++
                highSince = nowMs
            }
        } else highSince = null
        if (!primed && queue.size >= 2) {
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
                if (consecutiveUnderruns >= 6) {
                    primed = false
                }
            }
        }
        return frame?.pcm
    }

    @Synchronized fun snapshot() = Snapshot(queue.size, dropped, underruns, expiredFrames,
        maxAuthenticatedArrivalGapMs, maxAuthenticatedArrivalBurstFrames)
}
