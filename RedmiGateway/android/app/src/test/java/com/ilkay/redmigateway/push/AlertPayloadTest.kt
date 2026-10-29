package com.ilkay.redmigateway.push

import com.ilkay.redmigateway.security.BridgeCrypto
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the alert push the iPhone app routes on (docs/WAVE1-CONTRACT.md sections 6 and 7).
 *
 * The iPhone reads "open" and the sealed "p" next to "aps", and registers the categories by name.
 * A key put inside "aps" by mistake, or a phone number left in plaintext, would not fail any build:
 * the tap would simply open the wrong screen, or Apple would see the number.
 */
class AlertPayloadTest {

    private val key = ByteArray(32) { it.toByte() }

    private fun smsPayload(): JSONObject = alertPayload(
        "Ayşe", "Merhaba", "sms-5321234567", SMS_CATEGORY,
        JSONObject().put("open", "sms").put("p", sealPushRoute(key, JSONObject().put("address", "+905321234567").put("gid", 42L)))
    )

    @Test
    fun apsCarriesWhatIosShowsByItself() {
        val aps = smsPayload().getJSONObject("aps")
        assertEquals("Ayşe", aps.getJSONObject("alert").getString("title"))
        assertEquals("Merhaba", aps.getJSONObject("alert").getString("body"))
        assertEquals("default", aps.getString("sound"))
        assertEquals("sms-5321234567", aps.getString("thread-id"))
        assertEquals("SMS", aps.getString("category"))
    }

    @Test
    fun theRouteSitsNextToApsNotInsideIt() {
        val payload = smsPayload()
        assertEquals(setOf("aps", "open", "p"), payload.keys().asSequence().toSet())
        assertFalse(payload.getJSONObject("aps").has("open"))
        assertFalse(payload.getJSONObject("aps").has("p"))
    }

    @Test
    fun theRedmiNeverSetsTheBadge() {
        // The iPhone keeps its own badge; a number from the Redmi would overwrite it.
        assertFalse(smsPayload().getJSONObject("aps").has("badge"))
    }

    @Test
    fun noCategoryMeansNoCategoryKey() {
        val aps = alertPayload("Sesli mesaj", "Yeni sesli mesaj", VOICEMAIL_THREAD_ID, null, null).getJSONObject("aps")
        assertFalse(aps.has("category"))
    }

    @Test
    fun anExtraNamedApsCannotReplaceIt() {
        val payload = alertPayload("t", "b", "health", HEALTH_CATEGORY, JSONObject().put("aps", "broken").put("open", "health"))
        assertEquals("health", payload.getJSONObject("aps").getString("thread-id"))
        assertEquals("health", payload.getString("open"))
    }

    @Test
    fun theSealedRouteOpensWithThePushKeyAndAad() {
        val sealed = smsPayload().getString("p")
        val opened = BridgeCrypto.open(BridgeCrypto.derive(key, "push"), BridgeCrypto.unb64(sealed)!!, PUSH_AAD)
        assertNotNull("the iPhone opens it like the VoIP payload", opened)
        val route = JSONObject(String(opened!!, Charsets.UTF_8))
        assertEquals("+905321234567", route.getString("address"))
        assertEquals(42L, route.getLong("gid"))
    }

    @Test
    fun theSealedRouteDoesNotOpenWithoutTheAad() {
        val sealed = BridgeCrypto.unb64(smsPayload().getString("p"))!!
        assertNull(BridgeCrypto.open(BridgeCrypto.derive(key, "push"), sealed))
    }

    @Test
    fun theRouteFieldsNeverTravelInPlaintext() {
        val wire = smsPayload().toString()
        for (field in listOf("address", "gid", "number", "logId")) {
            assertFalse("'$field' must only exist inside p", wire.contains("\"$field\""))
        }
        assertFalse(wire.contains("+905321234567"))
    }

    @Test
    fun theAadIsTheVoipOne() {
        assertEquals("phonebridge-push", String(PUSH_AAD, Charsets.UTF_8))
    }

    @Test
    fun alertsWaitADayAndCallsNever() {
        val now = 1_793_104_931_960L
        assertEquals((now / 1000 + 86_400).toString(), apnsExpiration(now, 24L * 3600))
        assertEquals("0", apnsExpiration(now, 0L))
        assertEquals("0", apnsExpiration(now, -5L))
    }

    @Test
    fun theExpirationIsInSecondsNotMilliseconds() {
        val now = 1_793_104_931_960L
        val value = apnsExpiration(now, 3600).toLong()
        assertTrue("seconds since 1970, not ms", value < now)
    }

    @Test
    fun theContractLifetimeIsTwentyFourHours() {
        assertEquals(86_400L, ApnsSender.ALERT_LIFETIME_SECONDS)
    }

    @Test
    fun theVoicemailLineCountsOnlyWhenThereIsMoreThanOne() {
        assertEquals("Yeni sesli mesaj", voicemailPushBody(-1))
        assertEquals("Yeni sesli mesaj", voicemailPushBody(1))
        assertEquals("3 yeni sesli mesaj", voicemailPushBody(3))
    }

    @Test
    fun theContractNamesAreSpelledAsTheIphoneRegistersThem() {
        assertEquals(listOf("SMS", "MISSED", "HEALTH"), listOf(SMS_CATEGORY, MISSED_CATEGORY, HEALTH_CATEGORY))
        assertEquals(listOf("missed", "health"), listOf(MISSED_THREAD_ID, HEALTH_THREAD_ID))
    }
}
