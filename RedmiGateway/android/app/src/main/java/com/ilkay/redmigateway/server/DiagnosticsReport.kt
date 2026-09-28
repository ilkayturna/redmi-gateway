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
 * Technical state for Advanced > Diagnostics on both phones. Contains no phone numbers, message
 * text, keys or audio; safe to copy into a support report.
 */
object DiagnosticsReport {
    private val permissions = listOf(
        Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE,
        "android.permission.CALL_AUDIO_INTERCEPTION", "android.permission.CAPTURE_AUDIO_OUTPUT"
    )

    /** True when the SIM slot 2 in-call music mapping (scripts/device/vendor_audio_fix.py) is present. */
    fun vendorAudioFixPresent(): Boolean = runCatching {
        File("/vendor/etc/audio_platform_info_intcodec.xml").readText().contains("USECASE_INCALL_MUSIC_UPLINK2")
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
            .put("push", JSONObject()
                .put("key", com.ilkay.redmigateway.push.ApnsSender.hasKey)
                .put("iphoneRegistered", com.ilkay.redmigateway.push.ApnsSender.hasDeviceToken))
            .put("permissions", JSONObject().apply {
                for (p in permissions) put(p.substringAfterLast('.'), context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED)
            })
    }
}
