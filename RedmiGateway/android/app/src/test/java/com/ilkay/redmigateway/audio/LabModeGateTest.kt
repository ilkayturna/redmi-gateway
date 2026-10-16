package com.ilkay.redmigateway.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the gate on the measurement hooks.
 *
 * The hooks change audio routing and mutes, so being wrong in the permissive direction is the
 * expensive one: an app that thinks lab mode is on during a normal call quietly changes how the
 * call sounds. That is a fault the person would hear and nobody would be able to explain.
 *
 * The default is off, represented as a deadline of zero, and the comparison is strict, so a
 * deadline exactly at the current instant is already expired. Lab mode lives in memory only, which
 * is why a process restart clears it and why nothing here is persisted.
 */
class LabModeGateTest {

    private val twoHours = LabMode.DURATION_MS

    // ---- The default, which is the thing that matters ----

    @Test
    fun labModeIsOffBeforeItIsEverSwitchedOn() {
        // A deadline of zero is the never-enabled state.
        assertFalse(labModeEnabled(now = 0L, enabledUntil = 0L))
        assertFalse(labModeEnabled(now = 1L, enabledUntil = 0L))
        assertFalse(labModeEnabled(now = 999_999_999L, enabledUntil = 0L))
    }

    @Test
    fun turningItOffProducesTheNeverEnabledDeadline() {
        assertEquals(0L, labModeDeadline(now = 500_000L, enable = false))
    }

    @Test
    fun turningItOnLastsTwoHours() {
        assertEquals(500_000L + twoHours, labModeDeadline(now = 500_000L, enable = true))
    }

    @Test
    fun theDurationIsTwoHours() {
        assertEquals(2 * 60 * 60 * 1000L, LabMode.DURATION_MS)
    }

    // ---- The strict comparison ----

    @Test
    fun theInstantItExpiresItIsAlreadyOff() {
        // Strict rather than inclusive: a deadline reached is expired.
        assertFalse(labModeEnabled(now = 1_000L, enabledUntil = 1_000L))
        assertTrue(labModeEnabled(now = 999L, enabledUntil = 1_000L))
    }

    @Test
    fun itIsOnThroughoutTheWindowAndOffAfter() {
        val start = 10_000L
        val until = labModeDeadline(start, enable = true)
        assertTrue(labModeEnabled(start, until))
        assertTrue(labModeEnabled(start + twoHours - 1, until))
        assertFalse(labModeEnabled(start + twoHours, until))
        assertFalse(labModeEnabled(start + twoHours + 1, until))
    }

    @Test
    fun cyclingOnAndOffEndsOff() {
        // Whatever order the broadcasts arrive in, the last one wins and off means off.
        var deadline = 0L
        deadline = labModeDeadline(now = 100L, enable = true)
        assertTrue(labModeEnabled(100L, deadline))
        deadline = labModeDeadline(now = 200L, enable = false)
        assertFalse(labModeEnabled(200L, deadline))
        assertFalse(labModeEnabled(201L, deadline))
    }

    @Test
    fun enablingTwiceDoesNotExtendPastTwoHoursFromTheSecondRequest() {
        // Not an accumulator: each enable sets a fresh window from that moment.
        val first = labModeDeadline(now = 0L, enable = true)
        val second = labModeDeadline(now = twoHours - 1, enable = true)
        assertEquals(twoHours, first)
        assertEquals(twoHours - 1 + twoHours, second)
        assertTrue(second > first)
    }

    @Test
    fun anImpossibleDeadlineNeverReadsAsEnabled() {
        // Negative deadlines cannot happen, but the comparison must not invert if one does.
        assertFalse(labModeEnabled(now = 5L, enabledUntil = -1L))
        assertFalse(labModeEnabled(now = 5L, enabledUntil = Long.MIN_VALUE))
    }

    @Test
    fun onlyAnExplicitTurnOnProducesAWindow() {
        // The receiver passes the extra straight through, defaulting to false, so a broadcast that
        // forgets the flag turns lab mode off rather than on.
        assertEquals(0L, labModeDeadline(now = 42L, enable = false))
        assertFalse(labModeEnabled(42L, labModeDeadline(now = 42L, enable = false)))
    }
}
