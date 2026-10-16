package com.ilkay.redmigateway.server

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import com.ilkay.redmigateway.BuildConfig
import com.ilkay.redmigateway.audio.CallAudioRelay
import com.ilkay.redmigateway.ble.BleServerManager
import com.ilkay.redmigateway.security.PairingStore
import com.ilkay.redmigateway.service.GatewayService
import com.ilkay.redmigateway.telecom.CallManager
import org.json.JSONObject
import java.io.File

/**
 * Technical state for the Settings status on the Redmi and diagnostics on the iPhone. Contains no phone numbers, message
 * text, keys or audio; safe to copy into a support report.
 */
object DiagnosticsReport {
    internal val permissions = listOf(
        Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE,
        "android.permission.CALL_AUDIO_INTERCEPTION", "android.permission.CAPTURE_AUDIO_OUTPUT"
    )

    /**
     * True only when both halves of the SIM slot 2 in-call music mapping are present.
     *
     * The script in scripts/device/vendor_audio_fix.py writes two files and requires both: the
     * mixer mapping to MultiMedia9, and the USECASE_INCALL_MUSIC_UPLINK2 usecase. This used to
     * check only the platform file, so a ROM update that restored the mixer alone would still be
     * reported as fixed. That matters more than it looks, because this flag is the first thing
     * anyone checks when the iPhone has no sound, and a false "present" sends the search into the
     * code when the answer is in /vendor.
     */
    fun vendorAudioFixPresent(): Boolean = runCatching {
        vendorFixPresent(
            platformInfo = File("/vendor/etc/audio_platform_info_intcodec.xml").readText(),
            mixerPaths = File("/vendor/etc/mixer_paths_idp.xml").readText(),
        )
    }.getOrDefault(false)

    fun build(context: Context): JSONObject {
        val telecom = context.getSystemService(TelecomManager::class.java)
        val paired = PairingStore.pairedClient()
        return JSONObject()
            .put("app", JSONObject()
                .put("version", BuildConfig.VERSION_NAME)
                .put("build", BuildConfig.VERSION_CODE)
                .put("protocol", GatewayWebSocketServer.PROTOCOL))
            .put("service", JSONObject()
                .put("running", GatewayService.isRunning)
                .put("clients", GatewayService.authenticatedClientCount())
                .put("iphone", com.ilkay.redmigateway.service.IPhoneReachability.current().level.name)
                .put("defaultDialer", telecom?.defaultDialerPackage == context.packageName))
            .put("pairing", JSONObject()
                .put("paired", paired != null)
                .put("pairedAt", paired?.pairedAt ?: 0)
                .put("legacyAllowed", PairingStore.legacyAuthAllowed))
            .put("call", JSONObject()
                .put("state", CallManager.currentCallState)
                .put("direction", CallManager.currentDirection))
            .put("audio", JSONObject(CallAudioRelay.diagnostics())
                .put("vendorFix", vendorAudioFixPresent()))
            .put("ble", BleServerManager.diagnostics())
            .put("hotspot", JSONObject()
                .put("smart", com.ilkay.redmigateway.service.SmartHotspot.enabled)
                .put("on", com.ilkay.redmigateway.service.SmartHotspot.hotspotOn)
                .put("knownNetworks", com.ilkay.redmigateway.service.SmartHotspot.knownNetworks.size))
            .put("push", JSONObject()
                .put("key", com.ilkay.redmigateway.push.ApnsSender.hasKey)
                .put("iphoneRegistered", com.ilkay.redmigateway.push.ApnsSender.hasDeviceToken)
                .put("alertsRegistered", com.ilkay.redmigateway.push.ApnsSender.hasAlertToken))
            .put("permissions", JSONObject().apply {
                for (k in permissionKeys()) put(k, context.checkSelfPermission(permissionFor(k)) == PackageManager.PERMISSION_GRANTED)
            })
    }
}

/**
 * Whether both halves of the vendor audio fix are in the given file contents.
 *
 * The fix is two edits in two files and neither works without the other: the platform file gains
 * the USECASE_INCALL_MUSIC_UPLINK2 usecase, and the mixer file points Incall_Music_2 at
 * MultiMedia9 instead of MultiMedia2. scripts/device/vendor_audio_fix.py requires both before it
 * will apply anything, so the report has to require both before it will claim the fix is there.
 *
 * Kept as a function over two strings so it can be asserted on: reading /vendor is not something a
 * unit test can do, and this flag is the first thing checked when the iPhone has no sound.
 *
 * @param platformInfo the contents of /vendor/etc/audio_platform_info_intcodec.xml
 * @param mixerPaths the contents of /vendor/etc/mixer_paths_idp.xml
 */
internal fun vendorFixPresent(platformInfo: String, mixerPaths: String): Boolean =
    platformInfo.contains("USECASE_INCALL_MUSIC_UPLINK2") &&
        mixerPaths.contains("Incall_Music_2 Audio Mixer MultiMedia9")

/**
 * The short key each permission is reported under.
 *
 * The report is a flat JSON object keyed by the last part of the permission name, so two
 * permissions that shorten to the same key would silently overwrite each other and one of them
 * would be reported with the other's state. The list is asserted for that.
 */
internal fun permissionKeys(): List<String> = DiagnosticsReport.permissions.map { it.substringAfterLast('.') }

/** The full permission name for a short key, for looking its state up. */
internal fun permissionFor(key: String): String =
    DiagnosticsReport.permissions.firstOrNull { it.substringAfterLast('.') == key } ?: key
