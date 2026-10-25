package com.ilkay.redmigateway.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/** Restores the saved Wi-Fi tether after Android turns it off at boot. */
internal object HotspotRestarter {
    private const val TAG = "HotspotRestarter"

    fun startIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!Settings.System.canWrite(context)) {
            Log.w(TAG, "Modify system settings permission is not granted; trying tethering permission")
        }

        try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val isEnabled = wifi.javaClass.getMethod("isWifiApEnabled").invoke(wifi) as Boolean
            if (isEnabled) {
                Log.i(TAG, "Hotspot is already enabled")
                return
            }
        } catch (error: Exception) {
            // MIUI may hide this query; the tethering start request is safe to repeat.
            Log.w(TAG, "Could not query hotspot state", error)
        }

        try {
            val manager = context.getSystemService("tethering")
                ?: throw IllegalStateException("Tethering service unavailable")
            val callbackType = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val callback = Proxy.newProxyInstance(
                callbackType.classLoader,
                arrayOf(callbackType)
            ) { _, method, args ->
                when (method.name) {
                    "onTetheringStarted" -> {
                        Log.i(TAG, "Wi-Fi tethering started")
                        record("Wi-Fi tethering started")
                    }
                    "onTetheringFailed" -> {
                        Log.e(TAG, "Wi-Fi tethering failed: ${args?.firstOrNull()}")
                        record("Wi-Fi tethering failed: ${args?.firstOrNull()}")
                    }
                }
                null
            }
            val requestType = Class.forName("android.net.TetheringManager\$TetheringRequest")
            val builderType = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val request = builderType.getConstructor(Int::class.javaPrimitiveType)
                .newInstance(0)
                .let { builderType.getMethod("build").invoke(it) }
            val method = manager.javaClass.getMethod(
                "startTethering", requestType, Executor::class.java, callbackType
            )
            method.invoke(manager, request, Executor { it.run() }, callback)
            Log.i(TAG, "Requested Wi-Fi tethering")
            record("Requested Wi-Fi tethering")
        } catch (error: Exception) {
            Log.e(TAG, "Could not start Wi-Fi tethering", error)
            record("Could not start Wi-Fi tethering: ${error.javaClass.simpleName}")
            val manager = context.getSystemService("tethering")
            Log.e(TAG, "Available start methods: " + manager?.javaClass?.methods
                ?.filter { it.name.contains("Tether", ignoreCase = true) }
                ?.joinToString { it.toString() })
        }
    }

    /**
     * One line in the on-device diagnostics file.
     *
     * Without it "the hotspot never came on" cannot be told apart from "it came on and the iPhone
     * did not join": logcat has the lines but the file is what survives a day on the phone.
     */
    private fun record(message: String) {
        // A hotspot that keeps failing is retried on every twenty-second pass; the same pair of
        // lines each time would push the day's calls out of the 4000-line file. A repeat is kept
        // once every two minutes so a long failure still shows up, and SmartHotspot's own line
        // still marks every real start.
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(lastRecordedAt) {
            val previous = lastRecordedAt[message]
            if (previous != null && now - previous < REPEAT_RECORD_MS) return
            lastRecordedAt[message] = now
        }
        com.ilkay.redmigateway.journal.DiagnosticsLog.record(TAG, message)
    }

    private const val REPEAT_RECORD_MS = 2 * 60_000L
    /** Keyed by the whole line: a retry writes "requested" and "failed" in turn. */
    private val lastRecordedAt = HashMap<String, Long>()
}
