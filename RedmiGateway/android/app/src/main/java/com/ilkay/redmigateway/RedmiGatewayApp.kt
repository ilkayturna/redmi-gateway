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
        private const val KEY_PAIRING_TOKEN = "pairing_token"
        private val secureRandom = SecureRandom()

        fun getPairingToken(): String {
            val prefs = instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var token = prefs.getString(KEY_PAIRING_TOKEN, null)
            if (token.isNullOrBlank()) {
                token = generateRandomToken()
                prefs.edit().putString(KEY_PAIRING_TOKEN, token).apply()
            }
            return token
        }

        private fun generateRandomToken(): String {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Exclude confusing chars like 0/O, 1/I
            return (1..6)
                .map { chars[secureRandom.nextInt(chars.length)] }
                .joinToString("")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Ensure pairing token is initialized
        getPairingToken()
    }
}
