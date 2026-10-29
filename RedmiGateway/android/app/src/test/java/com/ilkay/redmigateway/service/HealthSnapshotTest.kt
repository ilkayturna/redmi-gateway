package com.ilkay.redmigateway.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Redmi health snapshot the iPhone shows (docs/WAVE1-CONTRACT.md section 4), the key that
 * decides when it is news, and when the iPhone gets an Apple alert about it.
 *
 * The owner never looks at the Redmi, so this frame is the only way he learns that an OTA took the
 * audio fix away or that the gateway battery is running out. A wrong key either stays silent on a
 * real fault or wakes the iPhone for every battery percent.
 */
class HealthSnapshotTest {

    private val brokenAudio = HealthCheck.problems(
        vendorFix = false, privApp = true, missingPrivileged = emptyList(), defaultDialer = false
    )
    private val rom = UpdateWatch.Rom("1792673413|ginkgo-new", "1792000000|ginkgo-old", 1_793_100_000_000)

    // ---- battery band ----

    @Test
    fun theBandsFollowTheContract() {
        assertEquals("ok", batteryBand(21, charging = false))
        assertEquals("low", batteryBand(20, charging = false))
        assertEquals("low", batteryBand(11, charging = false))
        assertEquals("critical", batteryBand(10, charging = false))
        assertEquals("critical", batteryBand(0, charging = false))
    }

    @Test
    fun chargingWinsOverAnyLevel() {
        assertEquals("charging", batteryBand(5, charging = true))
        assertEquals("charging", batteryBand(100, charging = true))
    }

    @Test
    fun anUnknownLevelIsItsOwnBand() {
        assertEquals("unknown", batteryBand(-1, charging = false))
    }

    @Test
    fun theBatteryIsReadAsPercentAndPower() {
        assertEquals(RedmiBattery(45, false), redmiBattery(level = 45, scale = 100, plugged = 0, status = 3))
        assertEquals(RedmiBattery(50, true), redmiBattery(level = 500, scale = 1000, plugged = 2, status = 3))
        // Charging status without a plug flag still counts as charging.
        assertEquals(RedmiBattery(30, true), redmiBattery(level = 30, scale = 100, plugged = 0, status = 2))
    }

    @Test
    fun aMissingLevelIsNoBattery() {
        assertNull(redmiBattery(level = -1, scale = 100, plugged = 0, status = 1))
        assertNull(redmiBattery(level = 50, scale = 0, plugged = 0, status = 1))
    }

    // ---- change key ----

    private fun key(codes: List<String> = listOf("vendorFix"), build: String = "b1",
                    updater: UpdaterState? = UpdaterState(true, false), band: String? = "ok") =
        healthChangeKey(codes, build, updater, band)

    @Test
    fun aBatteryPercentIsNotNewsButABandIs() {
        assertEquals(key(band = batteryBand(80, false)), key(band = batteryBand(79, false)))
        assertNotEquals(key(band = batteryBand(21, false)), key(band = batteryBand(20, false)))
        assertNotEquals(key(band = "low"), key(band = "charging"))
    }

    @Test
    fun problemsRomAndUpdaterAreNews() {
        assertNotEquals(key(), key(codes = emptyList()))
        assertNotEquals(key(), key(codes = listOf("vendorFix", "privApp")))
        assertNotEquals(key(), key(build = "b2"))
        assertNotEquals(key(), key(updater = UpdaterState(true, true)))
        assertNotEquals(key(), key(updater = UpdaterState(false, false)))
        assertNotEquals(key(), key(updater = null))
    }

    // ---- snapshot ----

    private fun snapshot(battery: RedmiBattery? = RedmiBattery(45, false),
                         updater: UpdaterState? = UpdaterState(true, false),
                         adbWifi: Boolean? = true,
                         theRom: UpdateWatch.Rom = rom): JSONObject =
        healthSnapshot(1_793_104_931_960, brokenAudio, theRom, battery, updater, adbWifi)

    @Test
    fun theSnapshotHasTheContractShape() {
        val json = snapshot()
        assertEquals("health", json.getString("type"))
        assertEquals(1_793_104_931_960, json.getLong("at"))
        assertEquals(setOf("type", "at", "problems", "rom", "battery", "updater", "adbWifi"), json.keys().asSequence().toSet())
        assertEquals(45, json.getJSONObject("battery").getInt("level"))
        assertFalse(json.getJSONObject("battery").getBoolean("charging"))
        assertTrue(json.getJSONObject("updater").getBoolean("present"))
        assertFalse(json.getJSONObject("updater").getBoolean("enabled"))
        assertTrue(json.getBoolean("adbWifi"))
    }

    @Test
    fun everyProblemCarriesItsTextAndFlag() {
        val problems = snapshot().getJSONArray("problems")
        assertEquals(2, problems.length())
        val vendor = problems.getJSONObject(0)
        assertEquals("vendorFix", vendor.getString("code"))
        assertTrue(vendor.getBoolean("audioCritical"))
        assertEquals(setOf("code", "audioCritical", "title", "detail", "fix"), vendor.keys().asSequence().toSet())
        val dialer = problems.getJSONObject(1)
        assertEquals("defaultDialer", dialer.getString("code"))
        assertFalse("a missing dialer role is reported, but not as broken audio", dialer.getBoolean("audioCritical"))
    }

    @Test
    fun theRomCarriesThePreviousBuildAndWhenItChanged() {
        val romJson = snapshot().getJSONObject("rom")
        assertEquals("1792673413|ginkgo-new", romJson.getString("build"))
        assertEquals("1792000000|ginkgo-old", romJson.getString("previous"))
        assertEquals(1_793_100_000_000, romJson.getLong("changedAt"))
    }

    @Test
    fun unknownPartsAreLeftOutNotGuessed() {
        val json = snapshot(battery = null, updater = null, adbWifi = null, theRom = UpdateWatch.Rom("b", null, null))
        assertFalse(json.has("battery"))
        assertFalse(json.has("updater"))
        assertFalse(json.has("adbWifi"))
        assertFalse(json.getJSONObject("rom").has("previous"))
        assertFalse(json.getJSONObject("rom").has("changedAt"))
    }

    @Test
    fun anAbsentUpdaterSaysOnlyThat() {
        val updater = snapshot(updater = UpdaterState(false, false)).getJSONObject("updater")
        assertFalse(updater.getBoolean("present"))
        assertFalse(updater.has("enabled"))
    }

    @Test
    fun noProblemsIsAnEmptyListNotAMissingOne() {
        val json = healthSnapshot(1, emptyList(), rom, null, null, null)
        assertEquals(0, json.getJSONArray("problems").length())
    }

    // ---- Apple alert ----

    @Test
    fun aNewAudioCriticalCodeIsSent() {
        assertEquals(HealthAlertStep.SEND, healthAlertStep(null, listOf("vendorFix")))
        assertEquals(HealthAlertStep.SEND, healthAlertStep("vendorFix", listOf("vendorFix", "privApp")))
    }

    @Test
    fun theSameCodesAfterARestartStayQuiet() {
        // The service restarts many times a day (START_STICKY, updates); one alert per fault.
        assertEquals(HealthAlertStep.NOTHING, healthAlertStep("vendorFix,privApp", listOf("vendorFix", "privApp")))
        assertEquals(HealthAlertStep.NOTHING, healthAlertStep("privApp,vendorFix", listOf("vendorFix", "privApp")))
    }

    @Test
    fun aPartialRepairIsRememberedWithoutASound() {
        assertEquals(HealthAlertStep.REMEMBER, healthAlertStep("vendorFix,privApp", listOf("vendorFix")))
    }

    @Test
    fun aRepairForgetsSoTheSameFaultAlertsAgain() {
        assertEquals(HealthAlertStep.FORGET, healthAlertStep("vendorFix", emptyList()))
        assertEquals(HealthAlertStep.NOTHING, healthAlertStep(null, emptyList()))
        assertEquals(HealthAlertStep.NOTHING, healthAlertStep("", emptyList()))
    }

    @Test
    fun aRecentRomChangeIsNamedAsTheLikelyCause() {
        val critical = HealthCheck.audioCritical(brokenAudio)
        val changedAt = rom.changedAt!!
        assertTrue(healthAlertBody(critical, rom, changedAt + 60_000).startsWith("ROM güncellendi. "))
        assertFalse(healthAlertBody(critical, rom, changedAt + ROM_CHANGE_RECENT_MS).startsWith("ROM güncellendi."))
        assertEquals(HealthCheck.summary(critical), healthAlertBody(critical, UpdateWatch.Rom("b", null, null), changedAt))
    }

    @Test
    fun theUpdaterIsVisibleToThePackageManager() {
        // Without <queries> Android 11+ hides the package and the report would say "absent".
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("<package android:name=\"${HealthReporter.UPDATER_PACKAGE}\" />"))
    }
}
