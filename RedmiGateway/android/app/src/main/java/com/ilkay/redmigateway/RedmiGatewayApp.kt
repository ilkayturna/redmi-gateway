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
                deviceId = bytes.joinToString("") { "%02X".format(it) }
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
        com.ilkay.redmigateway.telecom.IncomingCallPresenter.init(this)
    }
}
