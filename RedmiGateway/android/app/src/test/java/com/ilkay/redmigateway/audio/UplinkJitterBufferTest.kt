package com.ilkay.redmigateway.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UplinkJitterBufferTest {
    // Catches a local queue discarding speech when a 200 ms network hold releases ten frames.
    @Test fun localTwoHundredMillisecondBurstPreservesEverySpeechFrame() {
        val buffer = UplinkJitterBuffer()
        val played = mutableListOf<Int>()
        repeat(3) { burst ->
            repeat(10) { offset -> buffer.offer(frame(burst * 10 + offset), burst * 200L) }
            repeat(10) { offset ->
                buffer.poll(burst * 200L + offset * 20L)?.let { played += it[0].toInt() }
            }
        }
        assertEquals((0 until 30).toList(), played)
        assertEquals(0, buffer.snapshot().dropped)
        assertEquals(0, buffer.snapshot().underruns)
    }

    // Catches premature restart after a gap, which leaves only 20 ms of jitter headroom.
    @Test fun speechStartsOnlyAfterFourFramesHaveArrived() {
        val buffer = UplinkJitterBuffer()
        repeat(3) { offset ->
            buffer.offer(frame(offset), offset * 20L)
            assertNull(buffer.poll(offset * 20L))
        }
        buffer.offer(frame(3), 60)
        assertEquals(0, buffer.poll(60)!![0].toInt())
    }

    // Catches an unbounded backlog replaying stale speech after a long network burst.
    @Test fun excessiveBurstKeepsOnlyTheNewestTwoHundredFortyMilliseconds() {
        val buffer = UplinkJitterBuffer()
        repeat(20) { buffer.offer(frame(it), 0) }
        val played = (0 until 12).mapNotNull { buffer.poll(it * 20L)?.get(0)?.toInt() }
        assertEquals((8 until 20).toList(), played)
        assertEquals(8, buffer.snapshot().dropped)
        assertEquals(0, buffer.snapshot().queued)
    }

    // Catches latency trimming a fresh backlog before it has persisted for half a second.
    @Test fun freshBacklogIsNotTrimmedBeforeHalfASecond() {
        val buffer = UplinkJitterBuffer()
        repeat(10) { buffer.offer(frame(it), 0) }
        for (index in 0..25) {
            assertEquals(index, buffer.poll(index * 20L)!![0].toInt())
            buffer.offer(frame(index + 10), index * 20L)
        }
        assertEquals(0, buffer.snapshot().dropped)
    }

    // Catches latency growing without recovery when a paced stream keeps a large backlog.
    @Test fun sustainedExcessLatencyShedsOnlyOneFrameAfterHalfASecond() {
        val buffer = UplinkJitterBuffer()
        repeat(10) { buffer.offer(frame(it), 0) }
        for (index in 0..25) {
            assertEquals(index, buffer.poll(index * 20L)!![0].toInt())
            buffer.offer(frame(index + 10), index * 20L)
        }
        assertEquals(27, buffer.poll(520)!![0].toInt())
        assertEquals(1, buffer.snapshot().dropped)
        assertEquals(28, buffer.poll(540)!![0].toInt())
        assertEquals(1, buffer.snapshot().dropped)
    }

    // Catches trimming a normal fresh 120 ms reserve, which causes avoidable speech gaps.
    @Test fun sixFrameReserveDoesNotTriggerLatencyTrimming() {
        val buffer = UplinkJitterBuffer()
        repeat(6) { buffer.offer(frame(it), 0) }
        for (index in 0..26) {
            assertEquals(index, buffer.poll(index * 20L)!![0].toInt())
            buffer.offer(frame(index + 6), index * 20L)
        }
        assertEquals(0, buffer.snapshot().dropped)
    }

    // Catches replaying stale speech after a writer stall shorter than the 900 ms watchdog.
    @Test fun expiredSpeechIsDiscardedBeforeFreshSpeechResumes() {
        val buffer = UplinkJitterBuffer()
        repeat(5) { buffer.offer(frame(it), 0) }
        assertEquals(0, buffer.poll(0)!![0].toInt())
        buffer.offer(frame(5), 380)
        assertNull(buffer.poll(401))
        assertEquals(4, buffer.snapshot().dropped)
        assertEquals(4, buffer.snapshot().expired)
        assertEquals(1, buffer.snapshot().underruns)
        buffer.offer(frame(6), 420)
        buffer.offer(frame(7), 440)
        assertNull(buffer.poll(440))
        buffer.offer(frame(8), 460)
        assertEquals(5, buffer.poll(460)!![0].toInt())
    }

    // Catches dropped/reordered speech or false underflows on a paced, lossless stream.
    @Test fun pacedAudioRemainsOrderedWithoutDroppingSpeech() {
        val buffer = UplinkJitterBuffer()
        val played = mutableListOf<Int>()
        for (index in 0 until 100) {
            buffer.offer(frame(index), index * 20L)
            buffer.poll(index * 20L)?.let { played += it[0].toInt() }
        }
        for (index in 100 until 104) buffer.poll(index * 20L)?.let { played += it[0].toInt() }
        assertEquals((0 until 100).toList(), played)
        assertEquals(0, buffer.snapshot().dropped)
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
        repeat(4) { buffer.offer(frame(it), 0) }
        repeat(4) { assertEquals(it, buffer.poll(it * 20L)!![0].toInt()) }
        // Queue is now empty, but primed. A single missing frame underruns without un-priming.
        assertNull(buffer.poll(80L))
        assertEquals(1, buffer.snapshot().underruns)

        // The delayed frame arrives: because primed was retained, it plays immediately on next poll.
        buffer.offer(frame(4), 85L)
        assertEquals(4, buffer.poll(100L)!![0].toInt())
    }

    // Catches buffer staying primed indefinitely despite continuous buffer starvation.
    @Test fun threeConsecutiveUnderrunsDropsPrimedState() {
        val buffer = UplinkJitterBuffer()
        repeat(4) { buffer.offer(frame(it), 0) }
        repeat(4) { assertEquals(it, buffer.poll(it * 20L)!![0].toInt()) }

        // 3 consecutive underruns should drop primed state
        assertNull(buffer.poll(80L))  // underrun 1
        assertNull(buffer.poll(100L)) // underrun 2
        assertNull(buffer.poll(120L)) // underrun 3 -> un-primes buffer
        assertEquals(3, buffer.snapshot().underruns)

        // Single new frame should NOT play immediately because buffer is un-primed
        buffer.offer(frame(4), 130L)
        assertNull(buffer.poll(140L))

        // Once 4 frames accumulate, buffer re-primes and plays
        buffer.offer(frame(5), 150L)
        buffer.offer(frame(6), 170L)
        buffer.offer(frame(7), 190L) // 4th queued frame triggers priming
        assertEquals(4, buffer.poll(190L)!![0].toInt())
    }

    private fun frame(index: Int) = byteArrayOf(index.toByte())
}
