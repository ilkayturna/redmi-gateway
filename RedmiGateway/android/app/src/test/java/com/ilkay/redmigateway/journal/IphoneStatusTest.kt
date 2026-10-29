package com.ilkay.redmigateway.journal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how the iPhone's `client_status` is written.
 *
 * Until wave 1 only a fixed list of twelve keys was kept and anything else the iPhone sent was
 * dropped without a word, so a new iOS field stayed invisible until a Redmi build caught up. Now
 * every key is kept. The line from iPhone build 155 must still read exactly as it always has,
 * because scripts and people grep it.
 */
class IphoneStatusTest {

    /** What build 155 sends (GatewayConnection.sendClientStatus), including the frame type. */
    private fun build155() = JSONObject()
        .put("type", "client_status")
        .put("appVersion", "2.0")
        .put("appBuild", "155")
        .put("appState", "active")
        .put("mic", "granted")
        .put("battery", "80% charging")
        .put("thermal", "nominal")
        .put("lowPower", false)

    /** The line the Redmi wrote before wave 1, for the comparison. */
    private fun oldLine(json: JSONObject): String {
        val parts = ArrayList<String>()
        for (key in LEGACY_STATUS_KEYS) {
            val value = json.opt(key)
            if (value != null && value.toString().isNotBlank()) parts.add("$key=$value")
        }
        return parts.joinToString(" ")
    }

    @Test
    fun theLineFromBuild155IsUnchanged() {
        val line = iphoneStatusLine(sanitizeIphoneStatus(build155()))

        assertEquals(oldLine(build155()), line)
        assertEquals(
            "appVersion=2.0 appBuild=155 appState=active mic=granted battery=80% charging " +
                "thermal=nominal lowPower=false",
            line
        )
    }

    @Test
    fun theFrameTypeIsNotAStatusField() {
        assertFalse(sanitizeIphoneStatus(build155()).has("type"))
    }

    @Test
    fun newKeysFollowTheOldOnesSorted() {
        val json = build155()
            .put("os", "27.0")
            .put("model", "iPhone16,2")
            .put("tfDaysLeft", 88)
            .put("callkit", "calls=1 audio=activated")
            .put("buildDate", "2026-10-28T22:17:33Z")
            .put("location", "always")

        val line = iphoneStatusLine(sanitizeIphoneStatus(json))

        assertEquals(
            "appVersion=2.0 appBuild=155 appState=active callkit=calls=1 audio=activated mic=granted " +
                "battery=80% charging thermal=nominal lowPower=false " +
                "buildDate=2026-10-28T22:17:33Z location=always model=iPhone16,2 os=27.0 tfDaysLeft=88",
            line
        )
    }

    @Test
    fun theOrderIsLegacyKeysThenTheRestSorted() {
        assertEquals(
            listOf("appBuild", "battery", "network", "a", "zeta"),
            iphoneStatusOrder(listOf("zeta", "network", "a", "battery", "appBuild"))
        )
    }

    @Test
    fun aLongValueIsCutToTwoHundredChars() {
        val values = sanitizeIphoneStatus(JSONObject().put("audio", "x".repeat(500)))

        assertEquals(MAX_STATUS_VALUE_CHARS, values.getString("audio").length)
    }

    @Test
    fun aNewlineCannotStartAnUnstampedLogLine() {
        val values = sanitizeIphoneStatus(JSONObject().put("network", "link=local\nwifi=1\r\tviable=1"))

        val text = values.getString("network")
        assertFalse(text.contains('\n') || text.contains('\r') || text.contains('\t'))
        assertEquals("link=local wifi=1  viable=1", text)
    }

    @Test
    fun nullAndBlankValuesAreLeftOutAsBefore() {
        val values = sanitizeIphoneStatus(
            JSONObject().put("mic", "granted").put("ble", "   ").put("notify", JSONObject.NULL)
        )

        assertEquals("mic=granted", iphoneStatusLine(values))
    }

    @Test
    fun numbersAndBooleansKeepTheirJsonType() {
        val values = sanitizeIphoneStatus(JSONObject().put("tfDaysLeft", 88).put("lowPower", true))

        assertEquals(88, values.get("tfDaysLeft"))
        assertEquals(true, values.get("lowPower"))
    }

    @Test
    fun aNestedValueBecomesAShortString() {
        val values = sanitizeIphoneStatus(JSONObject().put("extra", JSONObject().put("a", 1)))

        assertEquals("{\"a\":1}", values.getString("extra"))
    }

    @Test
    fun keysAreMadeSafeAndCapped() {
        val json = JSONObject().put("bad key\n", "1").put("k".repeat(100), "2")
        val values = sanitizeIphoneStatus(json)

        assertTrue(values.has("bad_key_"))
        assertTrue(values.has("k".repeat(MAX_STATUS_KEY_CHARS)))
    }

    @Test
    fun aFrameWithThousandsOfKeysIsCapped() {
        val json = JSONObject()
        for (index in 0 until 1_000) json.put("k$index", "v")

        assertEquals(MAX_STATUS_KEYS, sanitizeIphoneStatus(json).length())
    }

    // ---- Saying what was changed ----

    @Test
    fun aCleanFrameReportsNoAdjustment() {
        val adjusted = ArrayList<String>()

        sanitizeIphoneStatus(build155().put("os", "27.0").put("tfDaysLeft", 88), adjusted)

        assertEquals(emptyList<String>(), adjusted)
    }

    @Test
    fun aRenamedKeyIsReported() {
        val adjusted = ArrayList<String>()

        sanitizeIphoneStatus(JSONObject().put("bad key", "1"), adjusted)

        assertEquals(listOf("renamed \"bad key\" to bad_key"), adjusted)
    }

    @Test
    fun keysOverTheCapAreCountedNotLostSilently() {
        val json = JSONObject().put("type", "client_status")
        for (index in 0 until 100) json.put("k%03d".format(index), "v")
        val adjusted = ArrayList<String>()

        sanitizeIphoneStatus(json, adjusted)

        assertEquals(listOf("dropped 36 key(s) over the cap of $MAX_STATUS_KEYS"), adjusted)
    }

    @Test
    fun twoKeysThatBecomeTheSameNameKeepTheFirstAndSaySo() {
        val adjusted = ArrayList<String>()

        val values = sanitizeIphoneStatus(JSONObject().put("a b", "1").put("a_b", "2"), adjusted)

        assertEquals(1, values.length())
        assertTrue(adjusted.any { it.startsWith("dropped \"") })
    }

    @Test
    fun aControlCharacterInAReportedKeyIsShownAsAQuestionMark() {
        val adjusted = ArrayList<String>()

        sanitizeIphoneStatus(JSONObject().put("x\ny", "1"), adjusted)

        assertFalse(adjusted.single().contains('\n'))
        assertEquals("renamed \"x?y\" to x_y", adjusted.single())
    }

    // ---- iphone-status.json ----

    @Test
    fun theStatusFileHoldsTheValuesAndTheirReceiveTime() {
        val values = sanitizeIphoneStatus(build155())

        val document = iphoneStatusDocument(values, 1_793_104_931_960L, "2026-10-29 08:02:11.960 +0300")

        assertEquals(1_793_104_931_960L, document.getLong("receivedAt"))
        assertEquals("2026-10-29 08:02:11.960 +0300", document.getString("receivedAtText"))
        assertEquals("155", document.getJSONObject("status").getString("appBuild"))
        assertEquals(setOf("receivedAt", "receivedAtText", "status"), document.keys().asSequence().toSet())
    }
}
