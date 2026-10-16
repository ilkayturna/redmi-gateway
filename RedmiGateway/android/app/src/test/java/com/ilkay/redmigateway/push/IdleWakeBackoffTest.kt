package com.ilkay.redmigateway.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the idle wake backoff.
 *
 * This exists because the first version of the idle wake got it wrong in a way the device exposed
 * immediately: the backoff was cleared whenever a socket opened, and iOS suspends a backgrounded
 * app about fifty milliseconds after it authenticates, so the socket "came back" several times a
 * minute and the gateway sent a silent push every fifteen seconds. That is a data and battery
 * cost with nothing to show for it, and it is exactly the kind of loop that looks like progress
 * in a log.
 *
 * The idle wake itself is what answers the user's complaint that the iPhone shows as
 * disconnected, so it has to keep working. What must not drift is the waiting.
 */
class IdleWakeBackoffTest {

    @Test
    fun theFirstWaitIsNeverShorterThanAMinute() {
        assertEquals(60_000L, idleWakePauseMs(1))
        assertEquals(60_000L, idleWakePauseMs(2))
    }

    @Test
    fun theWaitGrowsRatherThanRepeating() {
        // A flat schedule is how the fifteen second loop would come back.
        val first = idleWakePauseMs(1)
        val second = idleWakePauseMs(3)
        val third = idleWakePauseMs(6)
        assertTrue("120s should be longer than 60s", second > first)
        assertTrue("300s should be longer than 120s", third > second)
    }

    @Test
    fun itKeepsGrowingUntilItSettlesAtFifteenMinutes() {
        assertEquals(900_000L, idleWakePauseMs(10))
        assertEquals(900_000L, idleWakePauseMs(50))
        assertEquals(900_000L, idleWakePauseMs(10_000))
    }

    @Test
    fun theBoundariesAreWhereTheCommentsSayTheyAre() {
        assertEquals(60_000L, idleWakePauseMs(2))
        assertEquals(120_000L, idleWakePauseMs(3))
        assertEquals(120_000L, idleWakePauseMs(5))
        assertEquals(300_000L, idleWakePauseMs(6))
        assertEquals(300_000L, idleWakePauseMs(9))
        assertEquals(900_000L, idleWakePauseMs(10))
    }

    @Test
    fun noInputCanProduceAWaitShortEnoughToSpin() {
        // Zero or negative would mean "send again immediately"; the loop this test guards against
        // was fifteen seconds, so anything under a minute is treated as the same failure.
        for (sent in -5..2_000) {
            val pause = idleWakePauseMs(sent)
            assertTrue("pause was ${pause}ms at sent=$sent", pause >= 60_000L)
        }
    }

    @Test
    fun theScheduleIsMonotonic() {
        // A step backwards would mean the gateway asks more often the longer the phone stays
        // asleep, which is the opposite of what a backoff is for.
        var previous = 0L
        for (sent in 1..2_000) {
            val pause = idleWakePauseMs(sent)
            assertTrue("pause fell from ${previous}ms to ${pause}ms at sent=$sent", pause >= previous)
            previous = pause
        }
    }

    @Test
    fun oneHourOfSleepCostsAboutAKilobyteNotAStream() {
        // The data promise: a phone that stays asleep must cost kilobytes an hour, not a push
        // every few seconds. Walking the schedule gives twelve wakes in the first hour, which at
        // the measured body size is about a kilobyte, and the rate falls to four an hour once the
        // fifteen minute step is reached.
        //
        // This assertion was ten when it was written, and the test failed: the schedule actually
        // gives twelve. The number here is the measured one, not the hoped for one.
        var sent = 0
        var elapsed = 0L
        while (elapsed < 3_600_000L) {
            sent++
            elapsed += idleWakePauseMs(sent)
        }
        assertEquals("an hour of sleep should send twelve idle wakes", 12, sent)
    }
}
