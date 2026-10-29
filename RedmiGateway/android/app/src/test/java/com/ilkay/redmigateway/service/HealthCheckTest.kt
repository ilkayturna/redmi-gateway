package com.ilkay.redmigateway.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the app calls a broken iPhone audio path, and how loudly it says so.
 *
 * Each problem here is one file or one grant that a ROM update can remove in a single reboot
 * without anything in the app changing. The app cannot put them back by itself, so the only
 * defence is to name exactly what is missing, before the user finds out through a silent call.
 * A problem that silently stops being reported is the same failure as one that is not detected:
 * the screen says everything is fine and the call has no sound.
 */
class HealthCheckTest {

    private fun problems(
        vendorFix: Boolean = true,
        privApp: Boolean = true,
        missingPrivileged: List<String> = emptyList(),
        defaultDialer: Boolean = true,
        bluetoothPairingStale: Boolean = false
    ) = HealthCheck.problems(vendorFix, privApp, missingPrivileged, defaultDialer, bluetoothPairingStale)

    @Test
    fun aStaleBluetoothPairingIsReportedWithoutClaimingBrokenAudio() {
        // 25-30.10.2026: the iPhone's link died in milliseconds for four days and nothing said so.
        // Calls still rang through the beacon and push, so it must not raise the red audio banner.
        val list = problems(bluetoothPairingStale = true)
        assertEquals(listOf(HealthCheck.BLUETOOTH_PAIRING), list.map { it.code })
        assertFalse(list.single().audioCritical)
        assertTrue("the fix names the iPhone setting to use", list.single().fix.contains("Bu Aygıtı Unut"))
    }

    @Test
    fun everythingPresentMeansNoProblems() {
        assertEquals(emptyList<HealthProblem>(), problems())
    }

    @Test
    fun aMissingVendorFixIsReportedAsBrokenAudio() {
        val list = problems(vendorFix = false)
        assertEquals(listOf(HealthCheck.VENDOR_FIX), list.map { it.code })
        assertTrue("vendor fix breaks audio in both directions", list.single().audioCritical)
    }

    @Test
    fun anAppOutsidePrivAppIsReported() {
        val list = problems(privApp = false)
        assertEquals(listOf(HealthCheck.PRIVILEGED_APP), list.map { it.code })
        assertTrue(list.single().audioCritical)
    }

    @Test
    fun everyMissingPrivilegedPermissionIsNamedInTheTitle() {
        val list = problems(missingPrivileged = HealthCheck.requiredPrivilegedPermissions)
        val problem = list.single { it.code == HealthCheck.PRIVILEGED_PERMISSIONS }
        for (permission in HealthCheck.requiredPrivilegedPermissions) {
            assertTrue(
                "title does not name ${permission.substringAfterLast('.')}: ${problem.title}",
                problem.title.contains(permission.substringAfterLast('.'))
            )
        }
        assertTrue(problem.audioCritical)
    }

    @Test
    fun aMissingDialerRoleDoesNotClaimTheAudioPathIsBroken() {
        // The role blocks calling altogether, which is a different failure from silent audio and
        // has its own one-tap button. Reporting it as an audio problem would send the user to the
        // repair kit for something the settings screen can fix.
        val list = problems(defaultDialer = false)
        assertEquals(listOf(HealthCheck.DEFAULT_DIALER), list.map { it.code })
        assertFalse(list.single().audioCritical)
        assertTrue(HealthCheck.audioCritical(list).isEmpty())
    }

    @Test
    fun allProblemsTogetherKeepAStableOrder() {
        val list = problems(vendorFix = false, privApp = false,
            missingPrivileged = HealthCheck.requiredPrivilegedPermissions, defaultDialer = false)
        assertEquals(
            listOf(
                HealthCheck.VENDOR_FIX,
                HealthCheck.PRIVILEGED_APP,
                HealthCheck.PRIVILEGED_PERMISSIONS,
                HealthCheck.DEFAULT_DIALER
            ),
            list.map { it.code }
        )
        assertEquals(3, HealthCheck.audioCritical(list).size)
    }

    @Test
    fun everyReportedProblemSaysWhatIsMissingAndWhatToDo() {
        val list = problems(vendorFix = false, privApp = false,
            missingPrivileged = HealthCheck.requiredPrivilegedPermissions, defaultDialer = false)
        for (problem in list) {
            assertTrue("${problem.code} has no title", problem.title.isNotBlank())
            assertTrue("${problem.code} has no detail", problem.detail.isNotBlank())
            assertTrue("${problem.code} says nothing about the fix", problem.fix.length > 20)
        }
    }

    @Test
    fun theSummaryNamesEveryProblemOnOneLine() {
        val list = problems(vendorFix = false, privApp = false)
        val summary = HealthCheck.summary(list)
        assertFalse("summary must stay a single line", summary.contains('\n'))
        for (problem in list) assertTrue("summary is missing ${problem.code}", summary.contains(problem.title))
    }

    @Test
    fun onlyTheTwoAudioPermissionsAreRequiredPrivilegedOnes() {
        // TETHER_PRIVILEGED and MODIFY_PHONE_STATE are declared too, but the hotspot and the call
        // controls keep working without them on this ROM. Requiring them here would keep the
        // banner up forever on a setup whose audio is fine.
        assertEquals(
            listOf("android.permission.CAPTURE_AUDIO_OUTPUT", "android.permission.CALL_AUDIO_INTERCEPTION"),
            HealthCheck.requiredPrivilegedPermissions
        )
    }

    @Test
    fun missingPrivilegedReportsWhatIsNotGranted() {
        val granted = mapOf(
            "android.permission.CAPTURE_AUDIO_OUTPUT" to true,
            "android.permission.CALL_AUDIO_INTERCEPTION" to false
        )
        assertEquals(listOf("android.permission.CALL_AUDIO_INTERCEPTION"), HealthCheck.missingPrivileged(granted))
        assertEquals(
            HealthCheck.requiredPrivilegedPermissions,
            HealthCheck.missingPrivileged(emptyMap())
        )
    }
}
