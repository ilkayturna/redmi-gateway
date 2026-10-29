package com.ilkay.redmigateway.telecom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins how the Redmi treats Telecom's missed-call count and the voicemail indicator, now that it
 * owns those notifications as the default dialer (quiet-1, quiet-5, quiet-7, CONS-1).
 *
 * The owner's complaint was Telecom's "Cevapsız çağrılar" buzzing in the pocket and coming back
 * after every reboot. While an iPhone is paired the Redmi must show nothing, and the count=0 reply
 * Telecom sends after a reset must never trigger another reset, or the two would loop.
 */
class MissedCallsTest {

    // ---- missed calls ----

    @Test
    fun pairedMeansNothingOnTheRedmi() {
        assertEquals(MissedCallAction.CLEAR_SILENTLY, missedCallAction(count = 1, paired = true))
        assertEquals(MissedCallAction.CLEAR_SILENTLY, missedCallAction(count = 4, paired = true))
    }

    @Test
    fun withoutAnIphoneTheRedmiShowsTheCount() {
        assertEquals(MissedCallAction.SHOW_ON_REDMI, missedCallAction(count = 1, paired = false))
    }

    @Test
    fun aZeroCountOnlyRemovesRows() {
        // REMOVE_ROWS is the one action that never resets Telecom's count again.
        assertEquals(MissedCallAction.REMOVE_ROWS, missedCallAction(count = 0, paired = true))
        assertEquals(MissedCallAction.REMOVE_ROWS, missedCallAction(count = 0, paired = false))
        assertEquals(MissedCallAction.REMOVE_ROWS, missedCallAction(count = -1, paired = true))
    }

    @Test
    fun telecomsOwnRowIsClearedBeforeTheTakeoverOnlyWhilePaired() {
        assertTrue(clearTelecomBeforeTakeover(receiverEnabled = false, paired = true))
        // Without an iPhone that row is the owner's only record of those calls.
        assertFalse(clearTelecomBeforeTakeover(receiverEnabled = false, paired = false))
        // Once the receiver is on, Telecom forwards the cancel and keeps its row: too late.
        assertFalse(clearTelecomBeforeTakeover(receiverEnabled = true, paired = true))
    }

    @Test
    fun theRedmiRowTitleCountsLikeAPerson() {
        assertEquals("Cevapsız arama", missedCallsTitle(1))
        assertEquals("4 cevapsız arama", missedCallsTitle(4))
    }

    // ---- voicemail ----

    @Test
    fun voicemailGoesToTheIphoneWhenPaired() {
        assertEquals(VoicemailAction.FORWARD_TO_IPHONE, voicemailAction(count = 2, refresh = false, paired = true))
        assertEquals(VoicemailAction.FORWARD_TO_IPHONE, voicemailAction(count = -1, refresh = false, paired = true))
    }

    @Test
    fun aRefreshAfterARebootIsNotNews() {
        assertEquals(VoicemailAction.NOTHING, voicemailAction(count = 2, refresh = true, paired = true))
    }

    @Test
    fun withoutAnIphoneVoicemailShowsOnTheRedmi() {
        assertEquals(VoicemailAction.SHOW_ON_REDMI, voicemailAction(count = 1, refresh = false, paired = false))
        assertEquals(VoicemailAction.SHOW_ON_REDMI, voicemailAction(count = 1, refresh = true, paired = false))
    }

    @Test
    fun aZeroVoicemailCountClears() {
        assertEquals(VoicemailAction.REMOVE_ROWS, voicemailAction(count = 0, refresh = false, paired = true))
        assertEquals(VoicemailAction.REMOVE_ROWS, voicemailAction(count = 0, refresh = true, paired = false))
    }

    // ---- diagnostics lines ----

    @Test
    fun aBootReplayBurstWritesOneLine() {
        assertTrue(shouldRecordMissedCallEvent(null, 0, "CLEAR_SILENTLY", 10_000))
        assertFalse(shouldRecordMissedCallEvent("CLEAR_SILENTLY", 10_000, "CLEAR_SILENTLY", 11_000))
        assertTrue(shouldRecordMissedCallEvent("CLEAR_SILENTLY", 10_000, "REMOVE_ROWS", 11_000))
    }

    @Test
    fun separateMissedCallsAreEachWritten() {
        assertTrue(shouldRecordMissedCallEvent("CLEAR_SILENTLY", 10_000, "CLEAR_SILENTLY", 10_000 + 5_000))
    }

    // ---- the manifest declarations, which no build or unit test would otherwise notice ----

    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun receiverBlock(name: String): String {
        val start = manifest.indexOf("android:name=\"$name\"")
        assertTrue("$name is not declared", start >= 0)
        return manifest.substring(start, manifest.indexOf("</receiver>", start))
    }

    @Test
    fun theMissedCallReceiverIsDeclaredForTelecom() {
        val block = receiverBlock(".telecom.MissedCallsReceiver")
        assertTrue(block.contains("android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION"))
        // Telecom sends it from system_server, so it must be exported.
        assertTrue(block.contains("android:exported=\"true\""))
        // Off until MissedCalls.takeOver: Telecom has to clear its own row first.
        assertTrue(block.contains("android:enabled=\"false\""))
    }

    @Test
    fun theVoicemailReceiverIsDeclaredForThePhoneProcess() {
        val block = receiverBlock(".telecom.VoicemailNotificationReceiver")
        assertTrue(block.contains("android.telephony.action.SHOW_VOICEMAIL_NOTIFICATION"))
        assertTrue(block.contains("android:exported=\"true\""))
        assertFalse(block.contains("android:enabled=\"false\""))
    }

    @Test
    fun theCallLogCanBeMarkedRead() {
        assertTrue(manifest.contains("android.permission.WRITE_CALL_LOG"))
    }
}
