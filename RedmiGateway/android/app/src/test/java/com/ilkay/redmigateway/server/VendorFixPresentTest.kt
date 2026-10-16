package com.ilkay.redmigateway.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the flag that says whether the vendor audio fix is in place.
 *
 * This matters out of proportion to its size. It is the first thing anyone checks when the iPhone
 * has no sound, and the handover notes say so: check the fix before looking at any code, because
 * without it the iPhone audio does not work at all and no code change can bring it back. A flag
 * that says "present" when the fix is half gone sends that search into the wrong layer, and the
 * symptom will look exactly like a code regression.
 *
 * The fix is two edits in two files. The platform file gains the USECASE_INCALL_MUSIC_UPLINK2
 * usecase and the mixer file points Incall_Music_2 at MultiMedia9. The applying script requires
 * both, so the report must require both. It used to check only the platform file.
 */
class VendorFixPresentTest {

    private val platformWithFix =
        "<usecase name=\"USECASE_INCALL_MUSIC_UPLINK\" type=\"out\" id=\"27\" />\n" +
            "<usecase name=\"USECASE_INCALL_MUSIC_UPLINK2\" type=\"out\" id=\"27\" />"

    private val platformWithoutFix =
        "<usecase name=\"USECASE_INCALL_MUSIC_UPLINK\" type=\"out\" id=\"27\" />"

    private val mixerWithFix =
        "<ctl name=\"Incall_Music_2 Audio Mixer MultiMedia2\" value=\"0\" />\n" +
            "<ctl name=\"Incall_Music_2 Audio Mixer MultiMedia9\" value=\"1\" />"

    private val mixerWithoutFix =
        "<ctl name=\"Incall_Music_2 Audio Mixer MultiMedia2\" value=\"0\" />"

    @Test
    fun bothHalvesPresentMeansPresent() {
        assertTrue(vendorFixPresent(platformWithFix, mixerWithFix))
    }

    @Test
    fun thePlatformHalfAloneIsNotEnough() {
        // The old check passed here, which is the fault this test exists for.
        assertFalse(vendorFixPresent(platformWithFix, mixerWithoutFix))
    }

    @Test
    fun theMixerHalfAloneIsNotEnough() {
        assertFalse(vendorFixPresent(platformWithoutFix, mixerWithFix))
    }

    @Test
    fun neitherHalfMeansNotPresent() {
        assertFalse(vendorFixPresent(platformWithoutFix, mixerWithoutFix))
    }

    @Test
    fun emptyFilesMeanNotPresent() {
        // A ROM update that replaces /vendor leaves readable files with none of the fix in them.
        assertFalse(vendorFixPresent("", ""))
    }

    @Test
    fun unreadableFilesBecomeNotPresentRatherThanACrash() {
        // runCatching around the reader turns a missing or unreadable file into the empty string,
        // which has to mean "not present" rather than "present".
        assertFalse(vendorFixPresent("", mixerWithFix))
        assertFalse(vendorFixPresent(platformWithFix, ""))
    }

    @Test
    fun theMixerHalfIsLookedForAtTheNewValueNotTheOldOne() {
        // The old mapping is still in the file with value 0, so a check that only looked for
        // "Incall_Music_2" would pass on an unfixed file.
        val unfixedButNamed = "<ctl name=\"Incall_Music_2 Audio Mixer MultiMedia2\" value=\"1\" />"
        assertFalse(vendorFixPresent(platformWithFix, unfixedButNamed))
    }
}
