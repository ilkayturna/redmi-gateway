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
 * The owner imports the APNs key (.p8) from the Apple Developer account on the Redmi's Settings
 * screen; it stays in this app's private storage. The iPhone hands over its push token after
 * each authentication.
 */
object ApnsSender {
    private const val TAG = "ApnsSender"
    private const val PREFS = "phonebridge_push"
    private const val KEY_PEM = "apns_key_pem"
    private const val KEY_ID = "apns_key_id"
    private const val KEY_TOKEN = "device_token"
    // Calls use the PushKit (VoIP) token; SMS and missed-call notifications need the app's
    // ordinary notification token. Apple refuses an alert sent to a VoIP token.
    private const val KEY_ALERT_TOKEN = "alert_token"
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
    val hasAlertToken: Boolean get() = prefs.getString(KEY_ALERT_TOKEN, null) != null
    val alertsReady: Boolean get() = hasKey && hasAlertToken

    /** Stores the .p8 key after checking that it is a usable EC private key. */
    fun importKey(pem: String, keyId: String): Boolean {
        val id = keyId.trim()
        if (!id.matches(Regex("^[A-Z0-9]{10}$")) || parseKey(pem) == null) return false
        prefs.edit().putString(KEY_PEM, pem).putString(KEY_ID, id).apply()
        cachedToken = null
        Log.i(TAG, "APNs key imported")
        com.ilkay.redmigateway.service.IPhoneReachability.changed()
        return true
    }

    fun removeKey() {
        prefs.edit().remove(KEY_PEM).remove(KEY_ID).apply()
        cachedToken = null
        com.ilkay.redmigateway.service.IPhoneReachability.changed()
    }

    fun setDeviceToken(token: String) {
        if (!token.matches(Regex("^[0-9a-f]{32,200}$"))) return
        prefs.edit().putString(KEY_TOKEN, token).apply()
        com.ilkay.redmigateway.service.IPhoneReachability.changed()
    }

    fun setAlertToken(token: String) {
        if (!token.matches(Regex("^[0-9a-f]{32,200}$"))) return
        prefs.edit().putString(KEY_ALERT_TOKEN, token).apply()
    }

    fun clearDeviceToken() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_ALERT_TOKEN).apply()
        com.ilkay.redmigateway.service.IPhoneReachability.changed()
    }

    /** VoIP push: iOS launches the app, which must show the call with CallKit. */
    fun sendVoip(payload: JSONObject) = post("voip", "$BUNDLE_ID.voip", KEY_TOKEN, payload, collapseId = null)

    /** Ordinary notification (SMS, missed call). */
    fun sendAlert(title: String, body: String, threadId: String, collapseId: String) {
        val payload = JSONObject().put("aps", JSONObject()
            .put("alert", JSONObject().put("title", title).put("body", body))
            .put("sound", "default")
            .put("thread-id", threadId))
        post("alert", BUNDLE_ID, KEY_ALERT_TOKEN, payload, collapseId, retry = true)
    }

    /**
     * Asks Apple whether the key, key ID and team are accepted, without notifying anyone: the
     * request goes to a device token that cannot exist. "BadDeviceToken" means Apple accepted the
     * key and only the made-up token failed; a 403 means the key itself is refused.
     */
    fun verifyKey(done: (String?) -> Unit) {
        val jwt = providerToken() ?: return done("Apple anahtarı okunamadı.")
        val request = Request.Builder()
            .url("$HOST/3/device/" + "0".repeat(64))
            .header("authorization", "bearer $jwt")
            .header("apns-push-type", "alert")
            .header("apns-topic", BUNDLE_ID)
            .header("apns-expiration", "0")
            .post("{\"aps\":{}}".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                done("Apple'a ulaşılamadı (${e.javaClass.simpleName}).")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val reason = runCatching { JSONObject(it.body?.string().orEmpty()).optString("reason") }.getOrDefault("")
                    Log.i(TAG, "APNs key check: ${it.code} $reason")
                    when {
                        it.code == 400 && reason == "BadDeviceToken" -> done(null)
                        it.code == 403 -> {
                            cachedToken = null
                            done("Apple anahtarı kabul etmedi: $reason")
                        }
                        else -> done("Apple yanıtı: ${it.code} $reason")
                    }
                }
            }
        })
    }

    /** Settings › "Test bildirimi gönder": a visible notification; [done] gets null or the problem. */
    fun sendTest(done: (String?) -> Unit) {
        when {
            !hasKey -> return done("Apple anahtarı yüklü değil.")
            !hasAlertToken -> return done("iPhone bildirim kaydı yok. iPhone uygulamasını bir kez aç.")
        }
        val payload = JSONObject().put("aps", JSONObject()
            .put("alert", JSONObject().put("title", "Telefon Geçidi")
                .put("body", "Apple bildirimi çalışıyor. Uygulama kapalıyken de aramalar ve mesajlar gelir."))
            .put("sound", "default"))
        post("alert", BUNDLE_ID, KEY_ALERT_TOKEN, payload, "setup-test", done)
    }

    /**
     * Silent push: wakes the app in the background without a banner and without a CallKit screen,
     * which is how a sleeping iPhone learns that a call is waiting for it. PushKit cannot carry
     * this - a silent push needs the app's ordinary token and topic, and priority 5 is mandatory
     * for the background push type. Carries only what the app needs to react, never a number or a
     * name. Returns false when there is no alert token, which only the iPhone can register.
     */
    fun sendBackground(payload: JSONObject, done: ((String?) -> Unit)? = null): Boolean {
        if (!alertsReady) return false
        val body = JSONObject().put("aps", JSONObject().put("content-available", 1))
        payload.keys().forEach { key -> body.put(key, payload.get(key)) }
        post("background", BUNDLE_ID, KEY_ALERT_TOKEN, body, collapseId = null, done = done, priority = "5")
        return true
    }

    /**
     * @param retry one more attempt on a fresh connection after a network failure. Seen on the
     *   device on 24.10.2026 23:24 and 25.10.2026 10:12: the request hung until the 15 s timeout,
     *   most likely on a pooled HTTP/2 connection the network had silently dropped, and the SMS
     *   alert was simply lost. Only alerts use it; a late VoIP push could ring a call that ended.
     */
    private fun post(
        type: String, topic: String, tokenKey: String, payload: JSONObject, collapseId: String?,
        done: ((String?) -> Unit)? = null, priority: String = "10", retry: Boolean = false
    ) {
        val token = prefs.getString(tokenKey, null) ?: return done?.invoke("iPhone bildirim kaydı yok.") ?: Unit
        val jwt = providerToken() ?: return done?.invoke("Apple anahtarı okunamadı.") ?: Unit
        val request = Request.Builder()
            .url("$HOST/3/device/$token")
            .header("authorization", "bearer $jwt")
            .header("apns-push-type", type)
            .header("apns-topic", topic)
            .header("apns-priority", priority)
            // Never deliver late: a call or code that arrives minutes later only confuses.
            .header("apns-expiration", "0")
            .apply { if (collapseId != null) header("apns-collapse-id", collapseId.take(64)) }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "APNs $type push failed: ${e.javaClass.simpleName}" + if (retry) "; retrying on a new connection" else "")
                if (retry) {
                    client.connectionPool.evictAll()
                    post(type, topic, tokenKey, payload, collapseId, done, priority, retry = false)
                    return
                }
                done?.invoke("Apple'a ulaşılamadı (${e.javaClass.simpleName}).")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        Log.i(TAG, "APNs $type push accepted")
                        done?.invoke(null)
                    } else {
                        val reason = runCatching { JSONObject(it.body?.string().orEmpty()).optString("reason") }.getOrDefault("")
                        Log.w(TAG, "APNs $type push refused: ${it.code} $reason")
                        if (reason == "BadDeviceToken" || reason == "Unregistered") prefs.edit().remove(tokenKey).apply()
                        if (it.code == 403) cachedToken = null
                        done?.invoke("Apple reddetti: ${it.code} $reason")
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
