package com.ilkay.redmigateway.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UplinkJitterBufferTest {
    // Catches a local queue discarding speech when a 200 ms network hold releases frames.
    @Test fun localTwoHundredMillisecondBurstPreservesEverySpeechFrame() {
        val buffer = UplinkJitterBuffer()
        val played = mutableListOf<Int>()
        repeat(3) { burst ->
            repeat(12) { offset -> buffer.offer(frame(burst * 12 + offset), burst * 200L) }
            repeat(12) { offset ->
                buffer.poll(burst * 200L + offset * 20L)?.let { played += it[0].toInt() }
            }
        }
        assertEquals((0 until 36).toList(), played)
        assertEquals(0, buffer.snapshot().dropped)
        assertEquals(0, buffer.snapshot().underruns)
    }

    // Catches premature restart after a gap, which leaves only 40-60 ms of jitter headroom. The
    // adaptive target is still at its two frame floor during setup, so the first prime uses the
    // deeper startup reserve that covers the measured 160-206 ms opening gaps.
    @Test fun speechStartsOnlyAfterTheStartupReserve() {
        val buffer = UplinkJitterBuffer()
        for (index in 0 until 11) {
            buffer.offer(frame(index), index * 20L)
            assertNull(buffer.poll(index * 20L))
        }
        assertEquals(12, buffer.snapshot().targetFrames)
        buffer.offer(frame(11), 220)
        assertEquals(0, buffer.poll(220)!![0].toInt())
    }

    // Catches an unbounded backlog replaying stale speech after a long network burst.
    @Test fun excessiveBurstKeepsOnlyTheNewestQueueLimit() {
        val buffer = UplinkJitterBuffer()
        repeat(40) { buffer.offer(frame(it), 0) }
        val played = (0 until 32).mapNotNull { buffer.poll(it * 20L)?.get(0)?.toInt() }
        assertEquals((8 until 40).toList(), played)
        assertEquals(8, buffer.snapshot().dropped)
        assertEquals(0, buffer.snapshot().queued)
    }

    // Catches latency trimming a fresh backlog before it has persisted for half a second.
    @Test fun freshBacklogIsNotTrimmedBeforeHalfASecond() {
        val buffer = UplinkJitterBuffer()
        repeat(12) { buffer.offer(frame(it), 0) }
        for (index in 0..25) {
            assertEquals(index, buffer.poll(index * 20L)!![0].toInt())
            buffer.offer(frame(index + 12), index * 20L)
        }
        assertEquals(0, buffer.snapshot().dropped)
    }

    // Catches the two failures of an adaptive target: keeping a one-off burst's latency forever,
    // and shedding the depth a bursty link just proved it needs before that link was idle long
    // enough to forget the burst. The measured link repeats a 160 ms stall well after four seconds,
    // so the window is ten seconds.
    @Test fun burstRaisesTheTargetAndTenQuietSecondsLetItDecay() {
        val buffer = UplinkJitterBuffer()
        repeat(12) { buffer.offer(frame(it), 0) }
        assertEquals(13, buffer.snapshot().targetFrames)
        var next = 12
        var now = 20L
        while (now < 10_000) {
            assertTrue(buffer.poll(now) != null)
            buffer.offer(frame(next++), now)
            now += 20
        }
        // The burst is still inside the ten second window: the depth it asked for is kept.
        assertEquals(13, buffer.snapshot().targetFrames)
        assertEquals(0, buffer.snapshot().dropped)
        while (now <= 10_560) {
            assertTrue(buffer.poll(now) != null)
            buffer.offer(frame(next++), now)
            now += 20
        }
        // Window expired: the target is back at the floor and the excess is shed, one frame per hold.
        assertEquals(2, buffer.snapshot().targetFrames)
        assertEquals(1, buffer.snapshot().dropped)
        assertEquals(11, buffer.snapshot().queued)
    }

    // Catches trimming a normal fresh 240 ms startup reserve, which causes avoidable speech gaps.
    @Test fun startupReserveDoesNotTriggerLatencyTrimming() {
        val buffer = UplinkJitterBuffer()
        repeat(12) { buffer.offer(frame(it), 0) }
        for (index in 0..26) {
            assertEquals(index, buffer.poll(index * 20L)!![0].toInt())
            buffer.offer(frame(index + 12), index * 20L)
        }
        assertEquals(0, buffer.snapshot().dropped)
    }

    // Catches replaying stale speech after a writer stall beyond the watchdog.
    @Test fun expiredSpeechIsDiscardedBeforeItCanReplay() {
        val buffer = UplinkJitterBuffer()
        repeat(12) { buffer.offer(frame(it), 0) }
        assertEquals(0, buffer.poll(0)!![0].toInt())
        buffer.offer(frame(12), 380)
        assertNull(buffer.poll(801))
        assertEquals(11, buffer.snapshot().dropped)
        assertEquals(11, buffer.snapshot().expired)
        assertEquals(1, buffer.snapshot().underruns)
        assertEquals(1, buffer.snapshot().queued)
    }

    // Catches dropped/reordered speech or false underflows on a paced, lossless stream. The 240 ms
    // startup reserve is deliberately shed one frame per 500 ms hold, so the first seconds trade a
    // few 20 ms cuts for cover against the opening gap; order and pace must still hold.
    @Test fun pacedAudioRemainsOrderedWhileTheStartupReserveSheds() {
        val buffer = UplinkJitterBuffer()
        val played = mutableListOf<Int>()
        for (index in 0 until 100) {
            buffer.offer(frame(index), index * 20L)
            buffer.poll(index * 20L)?.let { played += it[0].toInt() }
        }
        // The startup prime holds the first twelve frames; drain the tail after the input.
        var drain = 100L
        while (buffer.snapshot().queued > 0) {
            buffer.poll(drain * 20L)?.let { played += it[0].toInt() }
            drain++
        }
        assertEquals(0, played.first())
        assertEquals(99, played.last())
        // The only missing frames are the trims; nothing is lost or reordered beyond them.
        assertEquals(100 - buffer.snapshot().dropped, played.size)
        assertTrue(played.zipWithNext().all { (first, second) -> second > first && second - first <= 2 })
        assertTrue(buffer.snapshot().dropped <= 3)
        assertEquals(0, buffer.snapshot().underruns)
    }

    // Catches stale audio/priming state leaking into the next call.
    @Test fun resetRemovesQueuedAudioAndRearmsPriming() {
        val buffer = UplinkJitterBuffer()
        repeat(5) { buffer.offer(frame(it), 0) }
        buffer.poll(0)
        buffer.reset()
        assertNull(buffer.poll(20))
        assertEquals(0, buffer.snapshot().queued)
        assertEquals(0, buffer.snapshot().underruns)
        assertEquals(0, buffer.snapshot().dropped)
    }

    // Catches losing the cause of a fallback before diagnostics can be fetched from the phone.
    @Test fun stoppingPlaybackRetainsFailureEvidenceUntilTheNextStart() {
        val buffer = UplinkJitterBuffer()
        repeat(20) { buffer.offer(frame(it), 0, authenticated = true) }
        buffer.poll(0)
        buffer.poll(401)
        buffer.offer(frame(20), 420, authenticated = true)
        val beforeStop = buffer.snapshot()
        buffer.clear()
        val stopped = buffer.snapshot()
        assertNull(buffer.poll(440))
        assertEquals(0, stopped.queued)
        assertEquals(beforeStop.dropped, stopped.dropped)
        assertEquals(beforeStop.underruns, stopped.underruns)
        assertEquals(beforeStop.expired, stopped.expired)
        assertEquals(420, stopped.maxAuthenticatedArrivalGapMs)
        assertEquals(20, stopped.maxAuthenticatedArrivalBurstFrames)
        buffer.reset()
        assertEquals(0, buffer.snapshot().dropped)
        assertEquals(0, buffer.snapshot().expired)
        assertEquals(0, buffer.snapshot().underruns)
        assertEquals(0, buffer.snapshot().maxAuthenticatedArrivalGapMs)
        assertEquals(0, buffer.snapshot().maxAuthenticatedArrivalBurstFrames)
    }

    // Catches a single missing frame dropping primed state and causing an 80ms silence drought.
    @Test fun singleMissingFrameDoesNotDropPrimedState() {
        val buffer = UplinkJitterBuffer()
        repeat(12) { buffer.offer(frame(it), 0) }
        repeat(12) { assertEquals(it, buffer.poll(it * 20L)!![0].toInt()) }
        // Queue is now empty, but primed. A single missing frame underruns without un-priming.
        assertNull(buffer.poll(240L))
        assertEquals(1, buffer.snapshot().underruns)

        // The delayed frame arrives: because primed was retained, it plays immediately on next poll.
        buffer.offer(frame(12), 245L)
        assertEquals(12, buffer.poll(260L)!![0].toInt())
    }

    // Catches buffer staying primed indefinitely despite continuous buffer starvation.
    @Test fun threeConsecutiveUnderrunsDropsPrimedState() {
        val buffer = UplinkJitterBuffer()
        repeat(12) { buffer.offer(frame(it), 0) }
        repeat(12) { assertEquals(it, buffer.poll(it * 20L)!![0].toInt()) }

        // 3 consecutive underruns should drop primed state
        assertNull(buffer.poll(240L))  // underrun 1
        assertNull(buffer.poll(260L))  // underrun 2
        assertNull(buffer.poll(280L))  // underrun 3 -> un-primes buffer
        assertEquals(3, buffer.snapshot().underruns)

        // Single new frame should NOT play immediately because buffer is un-primed
        buffer.offer(frame(12), 290L)
        assertNull(buffer.poll(300L))

        // The 290 ms gap raises the target to sixteen frames, so the reserve is fifteen; once
        // fifteen fresh frames accumulate, the buffer re-primes and plays.
        for (index in 13..26) buffer.offer(frame(index), 290L + (index - 12) * 20L)
        assertEquals(12, buffer.poll(590L)!![0].toInt())
    }

    // Catches a hard limit that lets the queue sit above the documented guard: 640 ms local and
    // 960 ms remote are memory bounds, above the adaptive 480/720 ms targets.
    @Test fun hardQueueLimitIsAboveTheAdaptiveCeiling() {
        val local = UplinkJitterBuffer()
        repeat(40) { local.offer(frame(it), 0) }
        assertEquals(32, local.snapshot().queued)
        assertEquals(8, local.snapshot().dropped)

        val remote = UplinkJitterBuffer()
        remote.reset(remote = true)
        repeat(60) { remote.offer(frame(it), 0) }
        assertEquals(48, remote.snapshot().queued)
        assertEquals(12, remote.snapshot().dropped)
    }

    // Catches trimming the startup reserve before it has had half a second to prove itself, and
    // then keeping the old 240 ms latency forever on a clean link.
    @Test fun startupReserveIsNotTrimmedBeforeHalfASecond() {
        val buffer = UplinkJitterBuffer()
        // Paced arrival: the adaptive target stays at its floor, so only the hold protects the
        // startup reserve from being trimmed immediately.
        repeat(12) { buffer.offer(frame(it), it * 20L) }
        buffer.poll(220)
        var now = 240L
        for (index in 0 until 22) {
            buffer.poll(now)
            buffer.offer(frame(12 + index), now)
            now += 20
        }
        // 660 ms: the reserve is still intact and no speech has been dropped.
        assertEquals(0, buffer.snapshot().dropped)
        val reserve = buffer.snapshot().queued
        for (index in 22 until 40) {
            buffer.poll(now)
            buffer.offer(frame(12 + index), now)
            now += 20
        }
        // A clean link sheds the startup reserve slowly after the hold, not in one burst.
        assertTrue(buffer.snapshot().queued < reserve)
        assertEquals(2, buffer.snapshot().targetFrames)
    }

    // Catches a diagnostics field reporting a depth the buffer does not actually trim against.
    @Test fun snapshotReportsTheAdaptiveTargetTheBufferTrimsAgainst() {
        val local = UplinkJitterBuffer()
        // Before the first prime the reported depth is the startup reserve the writer waits for.
        assertEquals(12, local.snapshot().targetFrames)
        repeat(12) { local.offer(frame(it), 0) }
        assertEquals(13, local.snapshot().targetFrames)

        val remote = UplinkJitterBuffer()
        remote.reset(remote = true)
        assertEquals(16, remote.snapshot().targetFrames)
        repeat(40) { remote.offer(frame(it), 0) }
        assertEquals(36, remote.snapshot().targetFrames)
    }

    // Catches Wi-Fi clumps that arrive a few milliseconds apart being read as paced frames: the
    // link clearly delivered a burst. The startup reserve already exceeds this clump's need, so
    // the reported depth stays at the startup target instead of dropping below it.
    @Test fun framesDeliveredFewMillisecondsApartCountAsOneBurst() {
        val buffer = UplinkJitterBuffer()
        for (index in 0 until 5) buffer.offer(frame(index), index * 10L)
        assertEquals(12, buffer.snapshot().targetFrames)
    }

    // Catches a paced stream being mistaken for a burst, which would raise latency for nothing.
    @Test fun pacedFramesDoNotRaiseTheTarget() {
        val buffer = UplinkJitterBuffer()
        for (index in 0 until 10) buffer.offer(frame(index), index * 20L)
        assertEquals(12, buffer.snapshot().targetFrames)
    }

    // Catches a memory ring too small to hold a full ten second window of this link's traffic.
    // The clumped link records about one observation per frame, so a 32 slot ring forgot the spike
    // after ~1.3 s while the window promises ten; the target then fell back to the clump's need -
    // the measured "gap=338 ms but targetFrames=6".
    @Test fun theMemoryRingHoldsAFullWindowOfClumpedTraffic() {
        val buffer = UplinkJitterBuffer()
        // One 338 ms silence raises the target by the gap plus two frames of slack.
        buffer.offer(frame(1), 0)
        buffer.offer(frame(2), 338)
        assertEquals(18, buffer.snapshot().targetFrames)

        // Clumped traffic for the rest of the window: four frames every 100 ms, which is four
        // qualifying observations per clump and ~340 of them inside the ten second window.
        var index = 3
        var now = 438L
        while (now <= 9_800) {
            repeat(4) { buffer.offer(frame(index++), now) }
            repeat(4) { buffer.poll(now + it * 20L) }
            now += 100
        }
        // The spike is still inside the window: the answer must not depend on how many clumps ran.
        assertEquals(18, buffer.snapshot().targetFrames)
    }

    // Catches the same ring being sized by seconds rather than by the samples a burst produces.
    @Test fun theBurstMemoryIsTimeBasedNotSampleCounted() {
        val buffer = UplinkJitterBuffer()
        buffer.offer(frame(0), 0)
        buffer.offer(frame(1), 338)
        assertEquals(18, buffer.snapshot().targetFrames)

        var now = 358L
        var index = 2
        while (now < 338 + 3_000) {
            buffer.offer(frame(index++), now)
            buffer.poll(now)
            now += 20
        }
        // Three seconds of paced audio later the silence is still remembered.
        assertEquals(18, buffer.snapshot().targetFrames)

        while (now < 338 + 10_500) {
            buffer.offer(frame(index++), now)
            buffer.poll(now)
            now += 20
        }
        // Past the ten second window it is not, and the target is back at the floor.
        assertEquals(2, buffer.snapshot().targetFrames)
    }

    // Catches a redundant sender's copy arriving after the frame it repeats has already been
    // trimmed away: while redundancy is on the buffer keeps a deeper floor (40+ ms) so the copy
    // still lands before its playback deadline.
    @Test fun redundancyRaisesTheFloorSoACopyCanStillHelp() {
        val plain = UplinkJitterBuffer()
        plain.reset(remote = false)
        repeat(12) { plain.offer(frame(0), it * 20L) }
        plain.poll(220)
        assertEquals(2, plain.snapshot().targetFrames)

        val redundant = UplinkJitterBuffer()
        redundant.reset(remote = false, minTargetFrames = 3)
        repeat(12) { redundant.offer(frame(0), it * 20L) }
        redundant.poll(220)
        assertEquals(3, redundant.snapshot().targetFrames)

        // A quiet link must not trim the extra reserve back down.
        var now = 240L
        repeat(40) {
            redundant.offer(frame(0), now)
            redundant.poll(now)
            now += 20
        }
        assertEquals(3, redundant.snapshot().targetFrames)
    }

    private fun frame(index: Int) = byteArrayOf(index.toByte())
}
