package com.ilkay.redmigateway.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Pins the Bluetooth identifiers, which have to agree with the iPhone's byte for byte.
 *
 * These are the one thing in the project that is written down twice, once in Kotlin and once in
 * Swift, and a mismatch does not fail loudly: the Redmi advertises, the iPhone scans, and neither
 * ever finds the other. The wake beacon, the call state and the control channel all go through
 * here, so a typo in one digit would take the whole Bluetooth side away while the network side
 * kept working, which reads as "the wake does not work" rather than "the identifiers disagree".
 *
 * The values in RedmiGatewayBLEConstants.swift on the iOS side are recorded here as the reference
 * they must match:
 *
 *   serviceUUID          A67D0001-2056-427E-971D-3C9A6F4E8D10
 *   charDeviceInfoUUID   A67D0002-2056-427E-971D-3C9A6F4E8D10
 *   charStateUUID        A67D0003-2056-427E-971D-3C9A6F4E8D10
 *   charCallControlUUID  A67D0004-2056-427E-971D-3C9A6F4E8D10
 *   charHeartbeatUUID    A67D0005-2056-427E-971D-3C9A6F4E8D10
 *   cccdUUID             00002902-0000-1000-8000-00805F9B34FB
 *
 * The last one is the standard Bluetooth descriptor, so it is the same in every project and only
 * its presence here is worth checking.
 */
class BleConstantsTest {

    @Test
    fun theServiceIdentifierIsTheOneTheIPhoneLooksFor() {
        assertEquals(
            UUID.fromString("A67D0001-2056-427E-971D-3C9A6F4E8D10"),
            BleConstants.SERVICE_UUID
        )
    }

    @Test
    fun everyCharacteristicIdentifierIsTheOneTheIPhoneUses() {
        assertEquals(UUID.fromString("A67D0002-2056-427E-971D-3C9A6F4E8D10"), BleConstants.CHAR_DEVICE_INFO_UUID)
        assertEquals(UUID.fromString("A67D0003-2056-427E-971D-3C9A6F4E8D10"), BleConstants.CHAR_STATE_UUID)
        assertEquals(UUID.fromString("A67D0004-2056-427E-971D-3C9A6F4E8D10"), BleConstants.CHAR_CALL_CONTROL_UUID)
        assertEquals(UUID.fromString("A67D0005-2056-427E-971D-3C9A6F4E8D10"), BleConstants.CHAR_HEARTBEAT_UUID)
    }

    @Test
    fun theClientConfigurationDescriptorIsTheStandardOne() {
        // Bluetooth assigns this one; it is not ours to choose, so it is the same everywhere.
        assertEquals(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BleConstants.CCCD_UUID
        )
    }

    @Test
    fun noTwoIdentifiersAreTheSame() {
        // Two characteristics sharing a UUID would make one of them unreachable, and the GATT
        // table would still build.
        val ours = listOf(
            BleConstants.SERVICE_UUID,
            BleConstants.CHAR_DEVICE_INFO_UUID,
            BleConstants.CHAR_STATE_UUID,
            BleConstants.CHAR_CALL_CONTROL_UUID,
            BleConstants.CHAR_HEARTBEAT_UUID,
        )
        assertEquals("duplicate identifiers in $ours", ours.size, ours.toSet().size)
    }

    @Test
    fun theCharacteristicsShareTheServicePrefix() {
        // They are a numbered family under one prefix, so a mistyped digit shows up as a stranger.
        val prefix = "a67d000"
        val suffix = "-2056-427e-971d-3c9a6f4e8d10"
        val characteristics = listOf(
            BleConstants.CHAR_DEVICE_INFO_UUID,
            BleConstants.CHAR_STATE_UUID,
            BleConstants.CHAR_CALL_CONTROL_UUID,
            BleConstants.CHAR_HEARTBEAT_UUID,
        )
        for (uuid in characteristics) {
            val text = uuid.toString().lowercase()
            assertTrue("uuid=$text", text.startsWith(prefix))
            assertTrue("uuid=$text", text.endsWith(suffix))
        }
        assertTrue(BleConstants.SERVICE_UUID.toString().lowercase().startsWith(prefix))
    }

    @Test
    fun theCharacteristicsAreNumberedApart() {
        // The distinguishing part is one hex digit at the end of the first group. In
        // "a67d0001-2056-..." that digit is index 7, not index 6, which is the kind of off by one
        // a test is supposed to catch in itself rather than in the code.
        val digits = listOf(
            BleConstants.SERVICE_UUID,
            BleConstants.CHAR_DEVICE_INFO_UUID,
            BleConstants.CHAR_STATE_UUID,
            BleConstants.CHAR_CALL_CONTROL_UUID,
            BleConstants.CHAR_HEARTBEAT_UUID,
        ).map { it.toString().lowercase().substring(7, 8) }
        assertEquals(listOf("1", "2", "3", "4", "5"), digits)
    }

    @Test
    fun theBluetoothIdentifiersAreNotNetworkOnes() {
        // Cheap guard against a copy and paste from somewhere else in the project.
        assertNotEquals(BleConstants.SERVICE_UUID, BleConstants.CCCD_UUID)
        assertNotEquals(BleConstants.CHAR_STATE_UUID, BleConstants.CCCD_UUID)
    }

    @Test
    fun theIdentifiersAreStableAcrossReads() {
        // They are constructed once; a second read must give the same value or pairing would break
        // between calls.
        assertEquals(BleConstants.SERVICE_UUID, BleConstants.SERVICE_UUID)
        assertEquals(BleConstants.CHAR_STATE_UUID.toString(), BleConstants.CHAR_STATE_UUID.toString())
    }

    @Test
    fun theInvariantNoteStillSaysBluetoothIsNotForAudio() {
        // The note is a comment that travels with the constants, and it records the rule that the
        // media path must never move onto Bluetooth.
        assertTrue(BleConstants.BLE_INVARIANT_NOTE.contains("NEVER audio"))
    }
}
