package com.ilkay.redmigateway.ble

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("A67D0001-2056-427E-971D-3C9A6F4E8D10")
    val CHAR_DEVICE_INFO_UUID: UUID = UUID.fromString("A67D0002-2056-427E-971D-3C9A6F4E8D10")
    val CHAR_STATE_UUID: UUID = UUID.fromString("A67D0003-2056-427E-971D-3C9A6F4E8D10")
    val CHAR_CALL_CONTROL_UUID: UUID = UUID.fromString("A67D0004-2056-427E-971D-3C9A6F4E8D10")
    val CHAR_HEARTBEAT_UUID: UUID = UUID.fromString("A67D0005-2056-427E-971D-3C9A6F4E8D10")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Invariant: BLE is strictly presence/bootstrap/control side-channel. NEVER audio media.
     */
    const val BLE_INVARIANT_NOTE = "BLE is strictly presence/bootstrap/control side-channel. NEVER audio media."
}
