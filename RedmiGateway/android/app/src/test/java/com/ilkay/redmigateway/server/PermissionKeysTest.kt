package com.ilkay.redmigateway.server

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the keys the permission section of the report is written under.
 *
 * The report is a flat JSON object keyed by the last part of each permission name, so two
 * permissions that shorten to the same key would silently overwrite each other and one would be
 * reported with the other's state. That is worse than a missing row: it would send whoever is
 * reading the report looking at the wrong permission while believing they had checked it.
 *
 * The report also claims to carry no numbers, message text, keys or audio, which is what makes it
 * safe to paste into a support message, so what goes into it is worth knowing exactly.
 */
class PermissionKeysTest {

    private val keys = permissionKeys()

    @Test
    fun noTwoPermissionsShareAKey() {
        // The failure this file exists for.
        assertEquals("duplicate keys in $keys", keys.size, keys.toSet().size)
    }

    @Test
    fun everyKeyIsTheLastPartOfItsPermission() {
        for (key in keys) {
            assertFalse("key=$key still contains a dot", key.contains('.'))
            assertTrue("key=$key is blank", key.isNotBlank())
        }
    }

    @Test
    fun everyKeyMapsBackToItsPermission() {
        // The lookup used when reading the state has to find the permission the key came from.
        for (permission in DiagnosticsReport.permissions) {
            val key = permission.substringAfterLast('.')
            assertEquals("permission=$permission", permission, permissionFor(key))
        }
    }

    @Test
    fun everyKeyRoundTripsToTheSamePermission() {
        for (key in keys) {
            val permission = permissionFor(key)
            assertEquals("key=$key", key, permission.substringAfterLast('.'))
        }
    }

    @Test
    fun theKeyCountMatchesThePermissionCount() {
        assertEquals(DiagnosticsReport.permissions.size, keys.size)
        assertTrue("only ${keys.size} permissions reported", keys.size >= 10)
    }

    // ---- The permissions the report has to cover ----

    @Test
    fun thePermissionsTheCallPathNeedsAreReported() {
        // Without these rows a report cannot say why calling does not work.
        val expected = listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
        )
        for (permission in expected) {
            assertTrue(
                "missing ${permission.substringAfterLast('.')}",
                keys.contains(permission.substringAfterLast('.'))
            )
        }
    }

    @Test
    fun theBluetoothPermissionsAreReported() {
        // The wake beacon needs both; a report missing them cannot explain a silent iPhone.
        assertTrue(keys.contains(Manifest.permission.BLUETOOTH_CONNECT.substringAfterLast('.')))
        assertTrue(keys.contains(Manifest.permission.BLUETOOTH_ADVERTISE.substringAfterLast('.')))
    }

    @Test
    fun thePrivilegedPermissionsAreReportedByTheirBareName() {
        // These two are written as strings because they are not in Manifest.permission on every
        // SDK, and shortening must not leave a fragment behind.
        assertTrue(keys.contains("CALL_AUDIO_INTERCEPTION"))
        assertTrue(keys.contains("CAPTURE_AUDIO_OUTPUT"))
    }

    @Test
    fun noKeyCarriesAValueRatherThanAState() {
        // Every key is a permission name, so none of them may look like data. A key containing a
        // digit that is not part of a name would suggest something else was being reported.
        for (key in keys) {
            assertTrue("key=$key", key.all { it.isLetter() || it == '_' })
        }
    }

    @Test
    fun theKeysAreStableAcrossCalls() {
        // Two reads of the same report must be comparable.
        assertEquals(keys, permissionKeys())
    }
}
