package com.ilkay.redmigateway.security

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sealed WebSocket frames after protocol 2 authentication.
 *
 * Each JSON message travels as {"t":"s","c":<counter>,"d":"<base64>"} where d is AES-256-GCM
 * with the session's WebSocket key. The nonce is a 4-byte direction tag plus the 8-byte counter,
 * and each side only accepts counters above the last one it opened, so frames cannot be
 * replayed or reordered.
 */
class SecureChannel(private val key: ByteArray, private val isGateway: Boolean) {
    private var sendCounter = 0L
    private var lastReceived = 0L

    private fun nonce(direction: ByteArray, counter: Long): ByteArray =
        ByteBuffer.allocate(BridgeCrypto.NONCE_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(direction).putLong(counter).array()

    @Synchronized
    fun seal(json: String): String {
        sendCounter += 1
        val direction = if (isGateway) GATEWAY_TO_CLIENT else CLIENT_TO_GATEWAY
        val sealed = BridgeCrypto.seal(key, json.toByteArray(Charsets.UTF_8), AAD, nonce(direction, sendCounter))
        // The nonce is implied by the counter; only ciphertext and tag are sent.
        val body = sealed.copyOfRange(BridgeCrypto.NONCE_BYTES, sealed.size)
        return "{\"t\":\"s\",\"c\":$sendCounter,\"d\":\"${BridgeCrypto.b64(body)}\"}"
    }

    /** Returns the plaintext JSON, or null for anything forged, replayed or malformed. */
    @Synchronized
    fun open(counter: Long, data: String): String? {
        if (counter <= lastReceived) return null
        val body = BridgeCrypto.unb64(data) ?: return null
        val direction = if (isGateway) CLIENT_TO_GATEWAY else GATEWAY_TO_CLIENT
        val plain = BridgeCrypto.open(key, nonce(direction, counter) + body, AAD) ?: return null
        lastReceived = counter
        return String(plain, Charsets.UTF_8)
    }

    companion object {
        private val AAD = "phonebridge-ws-2".toByteArray(Charsets.UTF_8)
        val GATEWAY_TO_CLIENT = byteArrayOf('S'.code.toByte(), '2'.code.toByte(), 'C'.code.toByte(), 0)
        val CLIENT_TO_GATEWAY = byteArrayOf('C'.code.toByte(), '2'.code.toByte(), 'S'.code.toByte(), 0)
    }
}
