package com.ilkay.redmigateway

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

class RedmiGatewayApp : Application() {

    companion object {
        lateinit var instance: RedmiGatewayApp
            private set

        private const val PREFS_NAME = "redmi_gateway_prefs"
        private val secureRandom = SecureRandom()

        /** The six-character code shown in Settings; see PairingStore. */
        fun getPairingToken(): String = com.ilkay.redmigateway.security.PairingStore.pairingCode()

        private const val KEY_DEVICE_ID = "opaque_device_id"

        fun getOpaqueDeviceId(): String {
            val prefs = instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var deviceId = prefs.getString(KEY_DEVICE_ID, null)
            if (deviceId.isNullOrBlank()) {
                val bytes = ByteArray(4)
                secureRandom.nextBytes(bytes)
                deviceId = opaqueDeviceId(bytes)
                prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
            }
            return deviceId
        }

    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Ensure pairing token and device ID are initialized
        getPairingToken()
        getOpaqueDeviceId()
        com.ilkay.redmigateway.server.ContactResolver.loadSeedContacts(this)
        com.ilkay.redmigateway.telecom.IncomingCallPresenter.init(this)
        // Call events are also written to a file the phone can hand over without a cable.
        com.ilkay.redmigateway.journal.DiagnosticsLog.init(this)
        com.ilkay.redmigateway.journal.DiagnosticsLog.record("App", "diagnostics started")
        // The iPhone cannot be cabled to a computer; its own log arrives over the sealed socket and
        // is written to a second file so adb can read it without anyone copying a report by hand.
        com.ilkay.redmigateway.journal.IphoneDiagnostics.init(this)
    }
}

/**
 * The opaque identifier this Redmi advertises and the iPhone matches against.
 *
 * It is deliberately not derived from anything about the phone: four random bytes, written once
 * and kept. The iPhone compares the identifier in the Bluetooth advertisement with the one it was
 * told during pairing, and ignores anything that does not match, so this value has to be stable
 * across restarts and exactly the same shape every time.
 *
 * The shape is the part worth pinning. Each byte is formatted to two characters, so a byte below
 * sixteen keeps its leading zero and the result is always eight characters. A plain hexadecimal
 * conversion would drop that zero and produce a seven character identifier for roughly one value
 * in sixteen, and the iPhone would then ignore an advertisement it should have accepted, which
 * looks exactly like the phone being out of range.
 */
internal fun opaqueDeviceId(bytes: ByteArray): String =
    bytes.joinToString("") { "%02X".format(it) }
