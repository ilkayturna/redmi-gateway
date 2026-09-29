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
    private const val CHECK_EVERY_MS = 5_000L
    private const val STABLE_MS = 15_000L
    private const val LOST_MS = 15_000L
    private const val TRY_MS = 30_000L
    private const val SCAN_EVERY_MS = 60_000L
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

        // A switch would cut the iPhone's link mid-call; wait until the call is over.
        if (CallManager.currentCallState !in setOf("idle", "disconnected")) return

        when {
            wifi && hotspot && now - wifiSince >= STABLE_MS -> {
                Log.i(TAG, "Known Wi-Fi in use; hotspot off to save mobile data")
                stopHotspot()
            }
            !wifi && !hotspot && now - lostSince >= LOST_MS -> {
                Log.i(TAG, "No Wi-Fi; hotspot on for the iPhone")
                HotspotRestarter.startIfNeeded(context)
                lostSince = now
            }
            !wifi && hotspot -> tryKnownNetwork(now)
        }
    }

    private fun tryKnownNetwork(now: Long) {
        val known = knownNetworks
        if (known.isEmpty()) return
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (now - lastScanAt >= SCAN_EVERY_MS) {
            lastScanAt = now
            @Suppress("DEPRECATION")
            runCatching { wifi.startScan() }
        }
        val fresh = now * 1000 - SCAN_MAX_AGE_MS * 1000
        val candidate = runCatching { wifi.scanResults }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.timestamp >= fresh && it.level >= MIN_LEVEL_DBM }
            .mapNotNull { result ->
                @Suppress("DEPRECATION")
                val ssid = result.SSID?.removeSurrounding("\"").orEmpty()
                if (ssid in known && (backoff[ssid] ?: 0L) < now) ssid to result.level else null
            }
            .maxByOrNull { it.second }?.first ?: return
        Log.i(TAG, "Known Wi-Fi $candidate in range; hotspot off to join it")
        trying = candidate
        tryDeadline = now + TRY_MS
        stopHotspot()
    }

    private fun connectivity() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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

/** adb only (DUMP): seeds the known networks from the Redmi's saved Wi-Fi list. */
class KnownWifiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.ilkay.redmigateway.KNOWN_WIFI") return
        SmartHotspot.start(context)
        intent.getStringArrayExtra("ssids")?.let { SmartHotspot.remember(it.toList()) }
    }
}
