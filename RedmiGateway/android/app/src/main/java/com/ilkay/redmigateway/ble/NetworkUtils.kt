package com.ilkay.redmigateway.ble

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * Mobile data and internal interfaces. The iPhone can never reach the Redmi through them, so
     * they are never announced: an address on them only misleads the iPhone.
     */
    private val UNREACHABLE = listOf("rmnet", "r_rmnet", "v4-rmnet", "ccmni", "clat", "dummy", "ifb", "ip6tnl", "sit", "lo")

    /** Every local IPv4 address the iPhone may reach: Wi-Fi, this phone's hotspot, USB/BT tethering, VPN. */
    fun localAddresses(): List<String> = candidates().map { it.second }.distinct()

    /** The address to announce first: the Wi-Fi network, then this phone's hotspot. */
    fun getLocalIPv4Address(): String? {
        val candidates = candidates()
        return (candidates.firstOrNull { it.first == "wlan0" }
            ?: candidates.firstOrNull { it.first.startsWith("wlan") }
            ?: candidates.firstOrNull { it.first.startsWith("softap") || it.first.startsWith("ap") }
            ?: candidates.firstOrNull())?.second
    }

    private fun candidates(): List<Pair<String, String>> = try {
        val result = mutableListOf<Pair<String, String>>()
        for (intf in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
            if (!intf.isUp || intf.isLoopback) continue
            val name = intf.name.lowercase()
            if (UNREACHABLE.any { name.startsWith(it) }) continue
            for (addr in intf.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    addr.hostAddress?.let { result.add(name to it) }
                }
            }
        }
        result.sortedBy { it.first }
    } catch (_: Exception) {
        emptyList()
    }
}
