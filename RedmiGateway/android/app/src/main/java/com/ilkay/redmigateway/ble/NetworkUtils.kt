package com.ilkay.redmigateway.ble

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun getLocalIPv4Address(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = mutableListOf<Pair<String, String>>()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        candidates.add(Pair(name, host))
                    }
                }
            }
            // Prefer Wi-Fi (wlan), hotspot (ap), or USB tethering (rndis)
            val preferred = candidates.firstOrNull { it.first.startsWith("wlan") }
                ?: candidates.firstOrNull { it.first.startsWith("ap") }
                ?: candidates.firstOrNull { it.first.startsWith("rndis") }
                ?: candidates.firstOrNull()
            return preferred?.second
        } catch (_: Exception) {
            return null
        }
    }
}
