package com.ilkay.redmigateway.push

import android.content.Context
import android.util.Base64
import android.util.Log
import com.ilkay.redmigateway.RedmiGatewayApp
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit

/**
 * Sends Apple push notifications (APNs, HTTP/2) straight from the Redmi to the paired iPhone
 * while it is away. No server of ours sits in between.
 *
 * The owner imports the APNs key (.p8) from the Apple Developer account on the Redmi's Advanced
 * screen; it stays in this app's private storage. The iPhone hands over its push token after
 * each authentication.
 */
object ApnsSender {
    private const val TAG = "ApnsSender"
    private const val PREFS = "phonebridge_push"
    private const val KEY_PEM = "apns_key_pem"
    private const val KEY_ID = "apns_key_id"
    private const val KEY_TOKEN = "device_token"
    const val TEAM_ID = "6FR2S79P48"
    const val BUNDLE_ID = "com.ilkay.redmigateway.ios"
    private const val HOST = "https://api.push.apple.com"

    private val client by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    @Volatile private var cachedToken: Pair<String, Long>? = null

    private val prefs get() = RedmiGatewayApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val hasKey: Boolean get() = prefs.getString(KEY_PEM, null) != null && prefs.getString(KEY_ID, null) != null
    val keyId: String? get() = prefs.getString(KEY_ID, null)
    val hasDeviceToken: Boolean get() = prefs.getString(KEY_TOKEN, null) != null
    val isReady: Boolean get() = hasKey && hasDeviceToken

    /** Stores the .p8 key after checking that it is a usable EC private key. */
    fun importKey(pem: String, keyId: String): Boolean {
        val id = keyId.trim()
        if (!id.matches(Regex("^[A-Z0-9]{10}$")) || parseKey(pem) == null) return false
        prefs.edit().putString(KEY_PEM, pem).putString(KEY_ID, id).apply()
        cachedToken = null
        Log.i(TAG, "APNs key imported")
        return true
    }

    fun removeKey() {
        prefs.edit().remove(KEY_PEM).remove(KEY_ID).apply()
        cachedToken = null
    }

    fun setDeviceToken(token: String) {
        if (!token.matches(Regex("^[0-9a-f]{32,200}$"))) return
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun clearDeviceToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    /** VoIP push: iOS launches the app, which must show the call with CallKit. */
    fun sendVoip(payload: JSONObject) = post("voip", "$BUNDLE_ID.voip", payload, collapseId = null)

    /** Ordinary notification (SMS, missed call). */
    fun sendAlert(title: String, body: String, threadId: String, collapseId: String) {
        val payload = JSONObject().put("aps", JSONObject()
            .put("alert", JSONObject().put("title", title).put("body", body))
            .put("sound", "default")
            .put("thread-id", threadId))
        post("alert", BUNDLE_ID, payload, collapseId)
    }

    private fun post(type: String, topic: String, payload: JSONObject, collapseId: String?) {
        val token = prefs.getString(KEY_TOKEN, null) ?: return
        val jwt = providerToken() ?: return
        val request = Request.Builder()
            .url("$HOST/3/device/$token")
            .header("authorization", "bearer $jwt")
            .header("apns-push-type", type)
            .header("apns-topic", topic)
            .header("apns-priority", "10")
            // Never deliver late: a call or code that arrives minutes later only confuses.
            .header("apns-expiration", "0")
            .apply { if (collapseId != null) header("apns-collapse-id", collapseId.take(64)) }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "APNs $type push failed: ${e.javaClass.simpleName}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        Log.i(TAG, "APNs $type push accepted")
                    } else {
                        val reason = runCatching { JSONObject(it.body?.string().orEmpty()).optString("reason") }.getOrDefault("")
                        Log.w(TAG, "APNs $type push refused: ${it.code} $reason")
                        if (reason == "BadDeviceToken" || reason == "Unregistered") clearDeviceToken()
                        if (it.code == 403) cachedToken = null
                    }
                }
            }
        })
    }

    /** Provider JWT (ES256), reused for 50 minutes as Apple recommends. */
    @Synchronized
    private fun providerToken(): String? {
        val now = System.currentTimeMillis() / 1000
        cachedToken?.let { (token, issued) -> if (now - issued < 3000) return token }
        val key = prefs.getString(KEY_PEM, null)?.let(::parseKey) ?: return null
        val kid = prefs.getString(KEY_ID, null) ?: return null
        val header = b64url(JSONObject().put("alg", "ES256").put("kid", kid).toString().toByteArray())
        val claims = b64url(JSONObject().put("iss", TEAM_ID).put("iat", now).toString().toByteArray())
        val signingInput = "$header.$claims"
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(signingInput.toByteArray())
            sign()
        }
        val token = "$signingInput.${b64url(derToJose(der))}"
        cachedToken = token to now
        return token
    }

    private fun parseKey(pem: String): PrivateKey? = runCatching {
        val body = pem.replace(Regex("-----[A-Z ]+-----"), "").replace(Regex("\\s"), "")
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(body, Base64.DEFAULT)))
    }.getOrNull()

    private fun b64url(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** ECDSA signatures come as DER SEQUENCE { r, s }; JWS wants r || s, 32 bytes each. */
    private fun derToJose(der: ByteArray): ByteArray {
        var offset = 2
        if (der[1].toInt() and 0x80 != 0) offset += der[1].toInt() and 0x7f
        fun readInt(): ByteArray {
            val length = der[offset + 1].toInt() and 0xff
            val value = der.copyOfRange(offset + 2, offset + 2 + length)
            offset += 2 + length
            val trimmed = value.dropWhile { it.toInt() == 0 }.toByteArray()
            return ByteArray(32 - trimmed.size) + trimmed
        }
        return readInt() + readInt()
    }
}
