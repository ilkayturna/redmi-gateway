package com.ilkay.redmigateway.security

import android.content.Context
import com.ilkay.redmigateway.RedmiGatewayApp
import java.security.SecureRandom

/**
 * The single paired iPhone and its 256-bit pairing key.
 *
 * The six-character code on the Redmi's Settings screen only bootstraps pairing: the iPhone
 * proves it knows the code, receives a random key sealed under PBKDF2(code), and from then on
 * authenticates with that key. The code is rotated after each pairing. Until an iPhone has
 * paired this way, clients may still authenticate by sending the code itself (protocol 1), so
 * older iPhone builds keep working during an update.
 */
object PairingStore {
    private const val PREFS = "redmi_gateway_prefs"
    private const val KEY_CODE = "pairing_token" // also the protocol 1 token
    private const val KEY_CLIENT_ID = "paired_client_id"
    private const val KEY_CLIENT_NAME = "paired_client_name"
    private const val KEY_SECRET = "paired_client_key"
    private const val KEY_PAIRED_AT = "paired_at"
    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    data class PairedClient(val id: String, val name: String, val key: ByteArray, val pairedAt: Long)

    private val prefs get() = RedmiGatewayApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun pairingCode(): String = prefs.getString(KEY_CODE, null)?.takeIf { it.isNotBlank() }
        ?: newCode().also { prefs.edit().putString(KEY_CODE, it).apply() }

    @Synchronized
    fun rotatePairingCode(): String = newCode().also { prefs.edit().putString(KEY_CODE, it).apply() }

    @Synchronized
    fun pairedClient(): PairedClient? {
        val id = prefs.getString(KEY_CLIENT_ID, null) ?: return null
        val key = prefs.getString(KEY_SECRET, null)?.let(BridgeCrypto::unb64) ?: return null
        if (key.size != BridgeCrypto.KEY_BYTES) return null
        return PairedClient(
            id, prefs.getString(KEY_CLIENT_NAME, null) ?: "iPhone", key, prefs.getLong(KEY_PAIRED_AT, 0L)
        )
    }

    /** Protocol 1 clients send the code in clear text; allowed only before a keyed pairing exists. */
    val legacyAuthAllowed: Boolean get() = pairedClient() == null

    @Synchronized
    fun completePairing(clientId: String, clientName: String, key: ByteArray) {
        prefs.edit()
            .putString(KEY_CLIENT_ID, clientId)
            .putString(KEY_CLIENT_NAME, clientName.take(64))
            .putString(KEY_SECRET, BridgeCrypto.b64(key))
            .putLong(KEY_PAIRED_AT, System.currentTimeMillis())
            .putString(KEY_CODE, newCode())
            .apply()
        com.ilkay.redmigateway.service.IPhoneReachability.changed()
    }

    /** Forgets the paired iPhone; call history, SMS and contacts are untouched. */
    @Synchronized
    fun forget() {
        com.ilkay.redmigateway.push.ApnsSender.clearDeviceToken()
        prefs.edit()
            .remove(KEY_CLIENT_ID).remove(KEY_CLIENT_NAME).remove(KEY_SECRET).remove(KEY_PAIRED_AT)
            .putString(KEY_CODE, newCode())
            .apply()
        com.ilkay.redmigateway.service.IPhoneReachability.changed()
    }

    private fun newCode(): String = (1..6).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
}
