package com.ilkay.redmigateway.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins what the repair package contains.
 *
 * The package is applied on a phone that is already broken, usually from a recovery session with
 * no way to iterate: a wrong path, a stale allowlist or a script that stops halfway is discovered
 * only after the reboot. Everything that can be checked without a device is checked here.
 */
class RepairKitTest {

    @Test
    fun theAllowlistInThePackageIsTheOneTheRepositoryInstalls() {
        val repository = File("../system/privapp-permissions-redmi-gateway.xml").readText()
        val generated = RepairKit.allowlistXml("com.ilkay.redmigateway")
        val repositoryPermissions = Regex("""<permission name="([^"]+)"""")
            .findAll(repository).map { it.groupValues[1] }.toList()
        val generatedPermissions = Regex("""<permission name="([^"]+)"""")
            .findAll(generated).map { it.groupValues[1] }.toList()
        assertEquals(repositoryPermissions, generatedPermissions)
        assertTrue(generated.contains("""package="com.ilkay.redmigateway""""))
    }

    @Test
    fun theBundledVendorScriptIsTheProvenOne() {
        // The asset is a copy; a copy that drifted is a fix that behaves differently from the one
        // every measurement in docs/ was taken with.
        val asset = File("src/main/assets/repair/vendor_audio_fix.py").normalized()
        val repository = File("../../scripts/device/vendor_audio_fix.py").normalized()
        assertEquals(repository, asset)
    }

    @Test
    fun theApplyScriptNamesEveryFileItTouches() {
        val script = RepairKit.applyScript()
        for (entry in listOf(
            "mixer_paths_idp.xml", "audio_platform_info_intcodec.xml",
            "priv-app/RedmiGateway/RedmiGateway.apk",
            "privapp-permissions-redmi-gateway.xml"
        )) {
            assertTrue("apply.sh does not mention $entry", script.contains(entry))
        }
    }

    @Test
    fun theApplyScriptMountsTheRealPartitionsAwayFromTheRecoveryOnes() {
        // Measured 2026-10-28: mounting the vendor partition over the recovery's /vendor turns
        // /system/bin/sh into a symlink loop and every later adb shell fails. The real partitions
        // have to be mounted at /mnt/rg-* and the recovery's own mountpoints left alone.
        val script = RepairKit.applyScript()
        assertTrue(script.contains("/mnt/rg-vendor"))
        assertTrue(script.contains("/mnt/rg-system"))
        assertFalse("apply.sh must not mount over /vendor", script.contains("mount /vendor"))
        assertFalse("apply.sh must not mount over /system", script.contains("mount /system"))
        assertTrue(script.contains("MSYS_NO_PATHCONV=1"))
    }

    @Test
    fun theApplyScriptOnlyRunsInRecovery() {
        val script = RepairKit.applyScript()
        assertTrue(script.contains("sys.boot_completed"))
        assertTrue(script.contains("Bu paket kurtarma modunda calisir"))
        assertTrue(script.contains("ro.boot.veritymode"))
        // The property alone is not the decision: this ROM reports enforcing while vbmeta has
        // hashtree disabled. The guard must read the vbmeta flag's 0x2 bit instead.
        assertTrue(script.contains("by-name/vbmeta"))
        assertTrue(script.contains("LAST & 2"))
        assertTrue(script.contains("dm-verity etkin"))
    }

    @Test
    fun theApplyScriptCanPatchWithoutPython() {
        // The phone has no Python and the PC may not either; the sed fallback is the path that
        // runs when nothing else is installed, so both edits have to be there, verified.
        val script = RepairKit.applyScript()
        assertTrue(script.contains("USECASE_INCALL_MUSIC_UPLINK2"))
        assertTrue(script.contains("Incall_Music_2 Audio Mixer MultiMedia9"))
        assertTrue(script.contains("sed -i"))
        assertTrue(script.contains("command -v python"))
        assertTrue("the fallback must verify its own result", script.contains("yamasi uygulanmadi"))
    }

    @Test
    fun theApplyScriptStopsInsteadOfHalfApplying() {
        val script = RepairKit.applyScript()
        assertTrue(script.contains("set -euo pipefail"))
        assertTrue(script.contains("vendor yazilamiyor"))
        assertTrue(script.contains("system yazilamiyor"))
        assertTrue(script.contains("adb_ reboot"))
        // The reboot is only allowed after the vendor patch and the allowlist have both been
        // read back from the device.
        val rebootAt = script.indexOf("adb_ reboot")
        val vendorCheckAt = script.indexOf("vendor duzeltmesi dogrulanamadi")
        val allowCheckAt = script.indexOf("izin listesinde")
        assertTrue("vendor verification must precede the reboot", vendorCheckAt in 0 until rebootAt)
        assertTrue("allowlist verification must precede the reboot", allowCheckAt in 0 until rebootAt)
    }

    @Test
    fun theApplyScriptRefusesToRunWithoutRoot() {
        val script = RepairKit.applyScript()
        assertTrue(script.contains("adb_ root"))
        assertTrue(script.contains("kurtarma modunda"))
    }

    @Test
    fun theReadmeNamesTheSymptomsAndThePaths() {
        val problems = HealthCheck.problems(
            vendorFix = false, privApp = false,
            missingPrivileged = HealthCheck.requiredPrivilegedPermissions, defaultDialer = false
        )
        val readme = RepairKit.readme(problems)
        for (problem in problems) assertTrue("README is missing ${problem.code}", readme.contains(problem.title))
        assertTrue(readme.contains("bash apply.sh"))
        assertTrue(readme.contains(RepairKit.ALLOWLIST))
        assertTrue(readme.contains(RepairKit.APK))
    }

    @Test
    fun theReadmeForAHealthyPhoneDoesNotInventProblems() {
        val readme = RepairKit.readme(emptyList())
        assertFalse(readme.contains("düzeltmesi yok"))
        assertTrue(readme.contains("eksik görünmüyor"))
    }

    @Test
    fun theRomSnippetPutsBothHalvesInTheDeviceTree() {
        // Baking is the only permanent anchor on this ROM, so the snippet has to be complete
        // enough to forward to whoever builds it.
        val snippet = RepairKit.romSnippet()
        assertTrue(snippet.contains("PRODUCT_PACKAGES"))
        assertTrue(snippet.contains("PRODUCT_COPY_FILES"))
        assertTrue(snippet.contains(RepairKit.ALLOWLIST_PATH))
        assertTrue(snippet.contains(RepairKit.PLATFORM_LINE))
        assertTrue(snippet.contains(RepairKit.MIXER_NEW))
    }

    @Test
    fun theApplyScriptIsAScriptAndNotATemplate() {
        val script = RepairKit.applyScript()
        assertTrue(script.startsWith("#!/usr/bin/env bash\n"))
        assertTrue(script.endsWith("\n"))
        for (line in script.lines()) {
            assertFalse("margin leaked into the script: $line", line.startsWith("|"))
            assertFalse("unexpanded Kotlin template: $line", line.contains("\${'"))
        }
        // Kept so a failed run on the phone can be diffed with the script that was generated.
        File("build/tmp/apply.sh").apply { parentFile?.mkdirs() }.writeText(script)
    }

    @Test
    fun thePackageEntriesDoNotCollide() {
        val names = listOf(
            RepairKit.README, RepairKit.APPLY, RepairKit.VENDOR_SCRIPT,
            RepairKit.ALLOWLIST, RepairKit.APK, RepairKit.ROM_SNIPPET
        )
        assertEquals(names.size, names.toSet().size)
        for (name in names) assertFalse("$name is not a relative path", name.startsWith("/"))
    }

    private fun File.normalized(): String =
        readText().removePrefix("\uFEFF").replace("\r\n", "\n").trimEnd()
}
