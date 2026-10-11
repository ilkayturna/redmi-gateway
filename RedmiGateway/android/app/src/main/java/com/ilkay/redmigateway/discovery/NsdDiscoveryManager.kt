package com.ilkay.redmigateway.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ilkay.redmigateway.RedmiGatewayApp
import com.ilkay.redmigateway.ble.NetworkUtils

class NsdDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "NsdDiscoveryManager"
        const val SERVICE_TYPE = "_redmigateway._tcp"
        private const val DEBOUNCE_MS = 800L
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var activePort: Int = 8787
    private var isRegistered = false
    private var isRegistering = false
    private var isRunning = false
    private var currentRegisteredName: String? = null
    private var lastKnownIp: String? = null
    var onIpChangedListener: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val reRegisterRunnable = Runnable {
        reRegisterService()
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            isRegistered = true
            isRegistering = false
            currentRegisteredName = serviceInfo.serviceName
            Log.i(TAG, "NSD service registered successfully as '${serviceInfo.serviceName}' on port ${serviceInfo.port}")
            releaseMulticastLock()
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            isRegistered = false
            isRegistering = false
            Log.w(TAG, "NSD registration failed with error code $errorCode")
            releaseMulticastLock()
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            isRegistered = false
            isRegistering = false
            Log.i(TAG, "NSD service unregistered successfully")
            releaseMulticastLock()
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            isRegistered = false
            isRegistering = false
            Log.w(TAG, "NSD unregistration failed with error code $errorCode")
            releaseMulticastLock()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            checkNetworkChange("network available")
        }

        override fun onLost(network: Network) {
            checkNetworkChange("network lost")
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            checkNetworkChange("link properties changed")
        }
    }

    fun start(port: Int) {
        this.activePort = port
        if (isRunning) return
        isRunning = true

        acquireMulticastLock()
        registerNetworkCallback()
        registerService()
    }

    fun updatePort(port: Int) {
        if (this.activePort != port) {
            this.activePort = port
            triggerDebouncedReRegistration()
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        handler.removeCallbacks(reRegisterRunnable)
        unregisterNetworkCallback()
        unregisterService()
        releaseMulticastLock()
        Log.i(TAG, "NsdDiscoveryManager stopped")
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                // Critical for Xiaomi MIUI / HyperOS power management: non-ref-counted multicast lock
                multicastLock = wifiManager?.createMulticastLock("RedmiGatewayNsdLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
                Log.d(TAG, "Acquired Wi-Fi MulticastLock")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "Released Wi-Fi MulticastLock")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release MulticastLock: ${e.message}")
        }
    }

    private fun registerNetworkCallback() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register NetworkCallback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not unregister NetworkCallback: ${e.message}")
        }
    }

    private fun checkNetworkChange(trigger: String) {
        val currentIp = NetworkUtils.getLocalIPv4Address()
        if (currentIp != lastKnownIp) {
            Log.i(TAG, "Network change detected ($trigger): old IP=$lastKnownIp, new IP=$currentIp")
            lastKnownIp = currentIp
            triggerDebouncedReRegistration()
            if (currentIp != null) {
                onIpChangedListener?.invoke(currentIp)
            }
        }
    }

    private fun triggerDebouncedReRegistration() {
        handler.removeCallbacks(reRegisterRunnable)
        handler.postDelayed(reRegisterRunnable, DEBOUNCE_MS)
    }

    private fun reRegisterService() {
        if (!isRunning) return
        Log.i(TAG, "Re-registering NSD service on port $activePort...")
        acquireMulticastLock()
        unregisterService()
        // Allow brief interval for unregistration before registering anew
        handler.postDelayed({
            if (isRunning) {
                registerService()
            } else {
                releaseMulticastLock()
            }
        }, 300L)
    }

    private fun registerService() {
        if (isRegistered || isRegistering || nsdManager == null) {
            releaseMulticastLock()
            return
        }
        isRegistering = true

        val deviceId = RedmiGatewayApp.getOpaqueDeviceId()
        val serviceInfo = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            serviceName = "RedmiGateway-$deviceId"
            port = activePort

            // Non-sensitive operational metadata TXT records
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAttribute("v", "2")
                setAttribute("id", deviceId)
                setAttribute("audio", "relay_udp_pcm")
                setAttribute("ble", "1")
                setAttribute("tls", "0")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            lastKnownIp = NetworkUtils.getLocalIPv4Address()
        } catch (e: Exception) {
            isRegistering = false
            Log.e(TAG, "Exception during NSD service registration: ${e.message}", e)
            releaseMulticastLock()
        }
    }

    private fun unregisterService() {
        if ((isRegistered || isRegistering) && nsdManager != null) {
            try {
                nsdManager.unregisterService(registrationListener)
            } catch (e: Exception) {
                Log.w(TAG, "Exception during NSD unregisterService: ${e.message}")
            }
        }
        isRegistered = false
        isRegistering = false
    }
}
