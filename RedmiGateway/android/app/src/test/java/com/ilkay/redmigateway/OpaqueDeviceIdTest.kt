package com.ilkay.redmigateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shape of the identifier this Redmi advertises.
 *
 * The iPhone compares the identifier in the Bluetooth advertisement against the one it was told
 * during pairing and ignores anything that does not match, so the value has to be exactly the same
 * shape every time. The failure worth guarding against is quiet: formatting each byte without
 * padding drops the leading zero for a byte below sixteen, which happens for roughly one value in
 * sixteen, produces a seven character identifier, and makes the iPhone skip an advertisement it
 * should have accepted. That looks exactly like the phone being out of range.
 */
class OpaqueDeviceIdTest {

    private val eightBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
    private val fourBytes = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)

    @Test
    fun itIsEightCharactersForFourBytes() {
        assertEquals(8, opaqueDeviceId(fourBytes).length)
        assertEquals(8, opaqueDeviceId(byteArrayOf(1, 2, 3, 4)).length)
    }

    @Test
    fun aByteBelowSixteenKeepsItsLeadingZero() {
        // The whole reason this function exists. Without the padding this would be "A" and the
        // identifier would be seven characters long.
        assertEquals("0A0B0C0D", opaqueDeviceId(fourBytes))
        assertEquals("00010203", opaqueDeviceId(byteArrayOf(0, 1, 2, 3)))
    }

    @Test
    fun azeroByteBecomesTwoZeros() {
        assertEquals("00000000", opaqueDeviceId(byteArrayOf(0, 0, 0, 0)))
    }

    @Test
    fun theWholeRangeIsAlwaysTwoCharactersPerByte() {
        // Walk every byte value and check the length never slips.
        for (value in 0..255) {
            val text = opaqueDeviceId(byteArrayOf(value.toByte(), value.toByte(), value.toByte(), value.toByte()))
            assertEquals("value=$value text=$text", 8, text.length)
        }
    }

    @Test
    fun itIsUppercaseHexadecimal() {
        val text = opaqueDeviceId(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x01))
        assertEquals("ABCDEF01", text)
        assertTrue("only hex digits", text.all { it in "0123456789ABCDEF" })
    }

    @Test
    fun itIsNeverLowercase() {
        // The iPhone compares strings after trimming, and case is not folded anywhere.
        val text = opaqueDeviceId(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()))
        assertFalse(text.any { it in "abcdef" })
    }

    @Test
    fun differentBytesGiveDifferentIdentifiers() {
        assertFalse(opaqueDeviceId(byteArrayOf(1, 2, 3, 4)) == opaqueDeviceId(byteArrayOf(1, 2, 3, 5)))
        assertFalse(opaqueDeviceId(fourBytes) == opaqueDeviceId(eightBytes))
    }

    @Test
    fun itIsDeterministicForTheSameBytes() {
        // The value is written once and read many times; the same bytes must always read the same.
        assertEquals(opaqueDeviceId(eightBytes), opaqueDeviceId(eightBytes))
    }

    @Test
    fun aLargerInputGivesAProportionallyLongerIdentifier() {
        // Records that the length follows the input, so nothing relies on eight being fixed.
        assertEquals(8, opaqueDeviceId(ByteArray(4)).length)
        assertEquals(16, opaqueDeviceId(ByteArray(8)).length)
    }

    @Test
    fun anEmptyInputGivesAnEmptyIdentifier() {
        // Not reachable in practice, but it must not throw, because this runs during startup.
        assertEquals("", opaqueDeviceId(ByteArray(0)))
    }

    @Test
    fun theIdentifierDoesNotLookLikeAPhoneNumber() {
        // It is advertised over Bluetooth, so it must not resemble anything personal.
        val text = opaqueDeviceId(eightBytes)
        assertFalse(text.startsWith("0"))
        assertFalse(text.contains("-"))
        assertFalse(text.contains("+"))
    }
}
