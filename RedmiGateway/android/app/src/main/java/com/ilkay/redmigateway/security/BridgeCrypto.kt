package com.ilkay.redmigateway.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitives shared with the iPhone (see docs/PROTOCOL.md, "Security"):
 * HMAC-SHA256 for proofs and key derivation, AES-256-GCM for sealed payloads, and PBKDF2 only
 * to protect the one-time key transfer with the short pairing code.
 */
object BridgeCrypto {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val PAIRING_PBKDF2_ROUNDS = 100_000

    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (part in parts) mac.update(part)
        return mac.doFinal()
    }

    /** Domain-separated sub key: HMAC(key, label). */
    fun derive(key: ByteArray, label: String, vararg context: ByteArray): ByteArray =
        hmac(key, label.toByteArray(Charsets.UTF_8), *context)

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun pbkdf2(secret: String, salt: ByteArray, rounds: Int = PAIRING_PBKDF2_ROUNDS): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, rounds, KEY_BYTES * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    /** Returns nonce || ciphertext || tag. */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0), nonce: ByteArray = randomBytes(NONCE_BYTES)): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    /** Opens nonce || ciphertext || tag; returns null when authentication fails. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        if (sealed.size < NONCE_BYTES + TAG_BYTES) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BYTES * 8, sealed, 0, NONCE_BYTES)
            )
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        } catch (_: Exception) {
            null
        }
    }

    fun b64(bytes: ByteArray): String = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    fun unb64(text: String): ByteArray? = try {
        android.util.Base64.decode(text, android.util.Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) {
        null
    }
}
