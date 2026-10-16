package com.ilkay.redmigateway.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.ilkay.redmigateway.RedmiGatewayApp
import com.ilkay.redmigateway.telecom.CallManager

/**
 * The Redmi's hotspot runs only while the Redmi has no known Wi-Fi.
 *
 * The iPhone has no mobile service of its own and goes online through the Redmi. On a known
 * Wi-Fi (home, office) both phones use that network and the hotspot is turned off, which saves
 * mobile data; away from those networks the hotspot comes back and the iPhone joins it.
 *
 * This Redmi's Wi-Fi chip cannot join a network while its own hotspot runs. So while the hotspot
 * is on and a known network shows up in a scan, the hotspot is turned off to let Android join it;
 * if that does not work within 30 s, the hotspot returns and that network is left alone for ten
 * minutes. Nothing is switched during a call.
 */
object SmartHotspot {
    private const val TAG = "SmartHotspot"
    private const val PREFS = "smart_hotspot"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_KNOWN = "known_ssids"
    private const val CHECK_EVERY_MS = 20_000L
    /** How long a Wi-Fi network must hold before the hotspot is turned off again. */
    internal const val STABLE_MS = 15_000L
    /** How long Wi-Fi must be gone before the hotspot is turned on. */
    internal const val LOST_MS = 15_000L
    private const val TRY_MS = 30_000L
    private const val SCAN_EVERY_MS = 60_000L
    /** The iPhone is on the hotspot: the same search continues, just on a slower cadence. */
    private const val CLIENT_SCAN_EVERY_MS = 90_000L
    private const val SCAN_MAX_AGE_MS = 90_000L
    private const val BACKOFF_MS = 10 * 60_000L
    private const val MIN_LEVEL_DBM = -75

    private val context: Context get() = RedmiGatewayApp.instance
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var wifiNetwork: Network? = null
    private var wifiSince = 0L
    private var lostSince = 0L
    private var trying: String? = null
    private var tryDeadline = 0L
    private var lastScanAt = 0L
    /** The last decision written to the diagnostics file, so repeats are not written again. */
    private var lastRecordedAction: HotspotAction? = null
    private val backoff = HashMap<String, Long>()
    private var started = false

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            if (value) handler.post { evaluate() }
        }

    /** Wi-Fi networks the Redmi has joined; only these switch the hotspot off. */
    val knownNetworks: Set<String>
        get() = prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty().toSet()

    /**
     * A saved network is in range while the Wi-Fi toggle is off, so nothing can join it and the
     * iPhone keeps spending mobile data on the hotspot. Only the user can flip that toggle (an app
     * cannot on Android 10+), so the ongoing notification names the network instead.
     */
    @Volatile var networkWaitingForWifi: String? = null
        private set

    private fun setWaitingNetwork(ssid: String?) {
        if (networkWaitingForWifi == ssid) return
        networkWaitingForWifi = ssid
        GatewayService.refreshStatus()
    }

    fun remember(ssids: Collection<String>) {
        val clean = ssids.map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID }
        if (clean.isEmpty()) return
        val updated = knownNetworks + clean
        if (updated != knownNetworks) {
            prefs.edit().putStringSet(KEY_KNOWN, updated).apply()
            Log.i(TAG, "Known Wi-Fi networks: ${updated.size}")
        }
    }

    fun forgetAll() {
        prefs.edit().remove(KEY_KNOWN).apply()
    }

    val hotspotOn: Boolean get() = isHotspotOn()

    fun start(appContext: Context) {
        if (started) return
        started = true
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivity().registerNetworkCallback(request, callback) }
            .onFailure { Log.w(TAG, "Wi-Fi callback not registered: ${it.javaClass.simpleName}") }
        handler.post(ticker)
        Log.i(TAG, "Started (enabled=${prefs.getBoolean(KEY_ENABLED, true)}, known=${knownNetworks.size})")
    }

    fun stop(appContext: Context? = null) {
        if (!started) return
        started = false
        handler.removeCallbacks(ticker)
        val cm = (appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager) ?: connectivity()
        runCatching { cm.unregisterNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "Wi-Fi callback not unregistered: ${it.javaClass.simpleName}") }
        trying = null
        tryDeadline = 0L
        wifiNetwork = null
        wifiSince = 0L
        lostSince = 0L
        lastScanAt = 0L
        Log.i(TAG, "Stopped")
    }

    private val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = wifiChanged(network, caps)
            override fun onLost(network: Network) = wifiLost(network)
        }
    } else {
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = wifiChanged(network, caps)
            override fun onLost(network: Network) = wifiLost(network)
        }
    }

    private fun wifiChanged(network: Network, caps: NetworkCapabilities) {
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) (caps.transportInfo as? WifiInfo)?.ssid else null
        handler.post {
            if (wifiNetwork != network) Log.i(TAG, "Wi-Fi with internet joined")
            wifiNetwork = network
            ssid?.let { remember(listOf(it)) }
            evaluate()
        }
    }

    private fun wifiLost(network: Network) {
        handler.post {
            if (wifiNetwork == network) {
                wifiNetwork = null
                Log.i(TAG, "Wi-Fi lost")
            }
            evaluate()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, CHECK_EVERY_MS)
        }
    }

    private fun evaluate() {
        if (!started) return
        val now = SystemClock.elapsedRealtime()
        val wifi = wifiNetwork != null
        // A Wi-Fi connection makes the "turn Wi-Fi on" hint stale.
        if (wifi) setWaitingNetwork(null)
        if (wifi) {
            if (wifiSince == 0L) wifiSince = now
            lostSince = 0L
        } else {
            wifiSince = 0L
            if (lostSince == 0L) lostSince = now
        }
        if (!prefs.getBoolean(KEY_ENABLED, true)) {
            trying = null
            return
        }
        val hotspot = isHotspotOn()

        trying?.let { ssid ->
            when {
                wifi -> {
                    Log.i(TAG, "Joined $ssid; hotspot stays off")
                    trying = null
                }
                now > tryDeadline -> {
                    Log.i(TAG, "Could not join $ssid; hotspot back on")
                    backoff[ssid] = now + BACKOFF_MS
                    trying = null
                    HotspotRestarter.startIfNeeded(context)
                }
            }
            return
        }

        // Turning the hotspot off would cut the iPhone's link mid-call, so that waits until the call
        // is over. Turning it on never hurts: the Wi-Fi is already gone, and the hotspot gives the
        // iPhone its way back to the call audio.
        val inCall = CallManager.currentCallState !in setOf("idle", "disconnected")
        val action = decideHotspotAction(
            enabled = true,
            wifi = wifi,
            hotspot = hotspot,
            inCall = inCall,
            wifiStableMs = if (wifi) now - wifiSince else 0L,
            lostForMs = if (wifi) 0L else now - lostSince,
        )
        when (action) {
            HotspotAction.STOP_HOTSPOT -> {
                recordDecision(action, "Known Wi-Fi in use; hotspot off to save mobile data")
                stopHotspot()
            }
            HotspotAction.START_HOTSPOT -> {
                recordDecision(action, "No Wi-Fi; hotspot on for the iPhone${if (inCall) " (during a call)" else ""}")
                HotspotRestarter.startIfNeeded(context)
                lostSince = now
            }
            HotspotAction.TRY_KNOWN_NETWORK -> {
                recordDecision(action, "Wi-Fi lost; trying a known network before falling back to the hotspot")
                tryKnownNetwork(now)
            }
            HotspotAction.NONE -> Unit
        }
    }

    /**
     * Writes a decision to logcat and to the diagnostics file, but only when it changes.
     *
     * The evaluation runs every twenty seconds, and the state it is evaluating usually has not
     * changed, so recording every pass would write the same line three times a minute and push
     * real history out of a file that keeps four thousand lines. The first version of this did
     * exactly that and it was visible on the device within the minute: "Wi-Fi lost; trying a known
     * network" appeared at 15:32:13, again at 15:32:33, and again at 15:32:53.
     *
     * What is worth keeping is the transition, which is what a person reading the file later is
     * looking for: when the hotspot went off and when it came back on.
     */
    private fun recordDecision(action: HotspotAction, message: String) {
        Log.i(TAG, message)
        if (action == lastRecordedAction) return
        lastRecordedAction = action
        com.ilkay.redmigateway.journal.DiagnosticsLog.record(TAG, message)
    }

    private fun tryKnownNetwork(now: Long) {
        if (!started || !enabled) return
        val clientConnected = GatewayService.hasAuthenticatedClient()
        val inCall = CallManager.currentCallState !in setOf("idle", "disconnected")
        // A running call is never interrupted: no scan, no hotspot change (data rule 3).
        if (inCall) return

        val known = knownNetworks
        if (known.isEmpty()) return
        val eligible = known.filter { (backoff[it] ?: 0L) < now }
        if (eligible.isEmpty()) return

        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        // The hotspot runs with the station interface off, so isWifiEnabled() is false while this
        // phone serves the AP (verified here: `Wifi is disabled`, wifi_on=0, soft AP on wlan2).
        // Scanning still works in that state - this build reports "Wifi scanning is always
        // available", and scan results come back with the saved networks in range - so the old
        // check hid every saved network exactly when the hotspot was the thing to switch off.
        if (!wifi.isWifiEnabled && !isHotspotOn()) return

        // The iPhone being on the hotspot must not hide a saved network. This chip cannot serve the
        // hotspot and scan at full rate, so the same search runs on a slower cadence while a client
        // is connected: arriving home, the hotspot then closes and the iPhone joins that network
        // instead of spending mobile data for the rest of the evening.
        val scanEvery = if (clientConnected) CLIENT_SCAN_EVERY_MS else SCAN_EVERY_MS

        val fresh = now * 1000 - SCAN_MAX_AGE_MS * 1000
        val candidate = runCatching { wifi.scanResults }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.timestamp >= fresh && it.level >= MIN_LEVEL_DBM }
            .mapNotNull { result ->
                @Suppress("DEPRECATION")
                val ssid = result.SSID?.removeSurrounding("\"").orEmpty()
                if (ssid in eligible) ssid to result.level else null
            }
            .maxByOrNull { it.second }?.first

        if (candidate != null) {
            // The hotspot runs with the station down. Whether Android brings the station back after
            // the hotspot stops follows the stored Wi-Fi toggle, and a plain app cannot flip that on
            // Android 10+ (WifiServiceImpl requires NETWORK_SETTINGS). Measured on this device: with
            // the toggle off the station never came up, the join failed and the hotspot returned
            // 40 s later - a needless outage for the iPhone that is on that hotspot. So the hotspot
            // only gives way when the station can actually take over.
            if (!wifiEnabledByUser()) {
                Log.i(TAG, "Saved network $candidate in range but Wi-Fi is switched off; hotspot stays")
                com.ilkay.redmigateway.journal.DiagnosticsLog.record(TAG, "Saved network $candidate in range but Wi-Fi is off; hotspot stays")
                setWaitingNetwork(candidate)
                return
            }
            // A call that started while we were deciding keeps its link (data rule 3).
            if (CallManager.currentCallState !in setOf("idle", "disconnected")) {
                Log.i(TAG, "Known Wi-Fi $candidate in range; keeping the hotspot for the running call")
                com.ilkay.redmigateway.journal.DiagnosticsLog.record(TAG, "Known Wi-Fi $candidate in range; keeping hotspot for the call")
                return
            }
            Log.i(TAG, "Known Wi-Fi $candidate in range" +
                if (clientConnected) " (iPhone on the hotspot); hotspot off to join it" else "; hotspot off to join it")
            com.ilkay.redmigateway.journal.DiagnosticsLog.record(TAG, "Known Wi-Fi $candidate in range; hotspot off to join it")
            trying = candidate
            tryDeadline = now + TRY_MS
            stopHotspot()
            return
        }

        if (now - lastScanAt >= scanEvery) {
            lastScanAt = now
            @Suppress("DEPRECATION")
            runCatching { wifi.startScan() }
        }
    }

    private fun connectivity() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * The stored Wi-Fi toggle ("wifi_on"), which is what decides whether Android starts the station
     * interface again once the hotspot stops. [WifiManager.isWifiEnabled] cannot answer this: it
     * reads false while this phone serves the hotspot, even with the toggle on.
     */
    private fun wifiEnabledByUser(): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, "wifi_on", 0) == 1
    }.getOrDefault(false)

    private fun isHotspotOn(): Boolean = runCatching {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.javaClass.getMethod("isWifiApEnabled").invoke(wifi) as Boolean
    }.getOrDefault(false)

    private fun stopHotspot() {
        runCatching {
            val manager = context.getSystemService("tethering") ?: error("no tethering service")
            manager.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(manager, 0)
        }.onFailure { Log.w(TAG, "Could not stop the hotspot: ${it.javaClass.simpleName}") }
    }
}

/** What the hotspot should do on this pass. */
internal enum class HotspotAction { NONE, STOP_HOTSPOT, START_HOTSPOT, TRY_KNOWN_NETWORK }

/**
 * The hotspot rules, which are the ones the user asked to keep: a known network means the hotspot
 * goes off to save mobile data, no network at all means it comes on so the iPhone has a way back,
 * and neither of those ever happens in the middle of a call because that would cut the iPhone's
 * link while the call audio is running over it.
 *
 * Extracted from evaluate() so it can be asserted on. The rules were only ever described in
 * comments, and a wrong turn here costs the user mobile data or the call audio, which is exactly
 * the kind of thing a comment does not protect.
 *
 * @param enabled the user's switch for this behaviour
 * @param wifi true when a Wi-Fi network is currently connected
 * @param hotspot true when the hotspot is currently up
 * @param inCall true while a call is anything other than idle or disconnected
 * @param wifiStableMs how long the current Wi-Fi has been connected, ignored when wifi is false
 * @param lostForMs how long Wi-Fi has been gone, ignored when wifi is true
 */
internal fun decideHotspotAction(
    enabled: Boolean,
    wifi: Boolean,
    hotspot: Boolean,
    inCall: Boolean,
    wifiStableMs: Long,
    lostForMs: Long,
): HotspotAction {
    if (!enabled) return HotspotAction.NONE
    return when {
        // Off to save mobile data, but only once the network has proven stable and never mid-call.
        wifi && hotspot && !inCall && wifiStableMs >= SmartHotspot.STABLE_MS -> HotspotAction.STOP_HOTSPOT
        // Back on so the iPhone can reach this Redmi. This one is allowed during a call.
        !wifi && !hotspot && lostForMs >= SmartHotspot.LOST_MS -> HotspotAction.START_HOTSPOT
        // While the hotspot is up, keep looking for a known network to hand back to.
        !wifi && hotspot && !inCall -> HotspotAction.TRY_KNOWN_NETWORK
        else -> HotspotAction.NONE
    }
}

/** adb only (DUMP): seeds the known networks from the Redmi's saved Wi-Fi list. */
class KnownWifiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.ilkay.redmigateway.KNOWN_WIFI") return
        SmartHotspot.start(context)
        intent.getStringArrayExtra("ssids")?.let { SmartHotspot.remember(it.toList()) }
    }
}
