package com.ilkay.redmigateway.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the three files that have to agree before the audio permissions exist on the phone.
 *
 * The manifest asks for the privileged permissions, the allowlist grants them by package name,
 * and the package name has to be the one the app is actually installed under. Any one of the
 * three being off is silent: the app installs, runs, reports "permissions missing" and the
 * iPhone has no sound, with nothing pointing at the file that is wrong. This was exactly the
 * state after the 2026-10-28 ROM update, so the contract is checked rather than remembered.
 *
 * These are repository files, not device files: the test runs from the app module directory.
 */
class InstallContractTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val allowlist = File("../system/privapp-permissions-redmi-gateway.xml").readText()
    private val gradle = File("build.gradle.kts").readText()

    private val allowlistPermissions = Regex("""<permission name="([^"]+)"""")
        .findAll(allowlist).map { it.groupValues[1] }.toList()

    private val applicationId = Regex("""applicationId\s*=\s*"([^"]+)"""")
        .find(gradle)?.groupValues?.get(1)

    @Test
    fun theAllowlistIsWrittenForTheInstalledPackage() {
        // A package name typo here is invisible everywhere else: the APK installs as priv-app and
        // still gets nothing, because no entry matches it.
        assertEquals("com.ilkay.redmigateway", applicationId)
        assertTrue(
            "allowlist targets a different package",
            allowlist.contains("""<privapp-permissions package="$applicationId">""")
        )
    }

    @Test
    fun everyAllowlistedPermissionIsOneTheAppActuallyRequests() {
        // An entry for a permission the manifest does not request usually means the manifest lost
        // the request (or the entry is for another app), and the audio path lost it with them.
        assertTrue("allowlist is empty", allowlistPermissions.isNotEmpty())
        for (permission in allowlistPermissions) {
            assertTrue("$permission is allowlisted but not requested", manifest.contains(permission))
        }
    }

    @Test
    fun theAudioPermissionsAreBothRequestedAndAllowed() {
        for (permission in HealthCheck.requiredPrivilegedPermissions) {
            assertTrue("$permission is not requested in the manifest", manifest.contains(permission))
            assertTrue("$permission is not allowlisted", allowlistPermissions.contains(permission))
        }
    }

    @Test
    fun theOtherPrivilegedPermissionTheAppUsesStaysAllowed() {
        // The hotspot path calls the privileged tether API. Dropping the entry would not break the
        // audio check, but it would take the hotspot away on a build that enforces privapp rules,
        // which this ROM does (ro.control_privapp_permissions=enforce).
        assertTrue(allowlistPermissions.contains("android.permission.TETHER_PRIVILEGED"))
        assertTrue(manifest.contains("android.permission.TETHER_PRIVILEGED"))
    }

    @Test
    fun theAllowlistHasNoDuplicateEntries() {
        assertEquals(allowlistPermissions.size, allowlistPermissions.toSet().size)
    }

    @Test
    fun thePrivAppInstallNamesAreTheOnesTheAppLooksFor() {
        // InstallState checks these paths; the repair package writes the same name. If one side
        // renames the directory, the app reports "not priv-app" forever while audio works, or
        // worse, reports nothing while it does not.
        for (path in InstallState.privAppPaths) {
            assertTrue("$path is not a priv-app path", path.contains("/priv-app/"))
            assertTrue(
                "$path names neither the RedmiGateway directory nor $applicationId",
                path.contains("RedmiGateway") || path.contains(applicationId.orEmpty())
            )
        }
    }
}
