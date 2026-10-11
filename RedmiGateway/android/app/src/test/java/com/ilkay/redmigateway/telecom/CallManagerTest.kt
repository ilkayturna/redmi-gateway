package com.ilkay.redmigateway.telecom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallManagerTest {

    @Test
    fun testInitialState() {
        assertEquals("idle", CallManager.currentCallState)
        assertFalse(CallManager.isCallLive())
        assertFalse(CallManager.isConference)
    }

    @Test
    fun testMaskPhoneNumber() {
        assertEquals("Unknown", CallManager.maskPhoneNumber(null))
        assertEquals("Unknown", CallManager.maskPhoneNumber(""))
        assertEquals("Unknown", CallManager.maskPhoneNumber("   "))
        assertEquals("***", CallManager.maskPhoneNumber("123"))
        assertEquals("***", CallManager.maskPhoneNumber("1234"))
        val masked = CallManager.maskPhoneNumber("05551234567")
        assertTrue(masked.startsWith("055"))
        assertTrue(masked.endsWith("67"))
        assertTrue(masked.contains("***"))
    }

    @Test
    fun testGetCallNonExistent() {
        org.junit.Assert.assertNull(CallManager.getCall("non_existent_id"))
    }

    @Test
    fun testSwapWithoutCallsReturnsFalse() {
        assertFalse(CallManager.swap())
    }

    @Test
    fun testCanMergeWithoutCallsReturnsFalse() {
        assertFalse(CallManager.canMerge())
    }
}
