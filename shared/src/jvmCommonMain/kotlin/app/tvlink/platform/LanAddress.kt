package app.tvlink.platform

import java.net.Inet4Address
import java.net.NetworkInterface

/** Prefer the Wi-Fi LAN address when multiple local interfaces are available. */
internal fun localLanAddress(): Inet4Address? {
    val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
    var fallback: Inet4Address? = null
    for (ni in ifaces) {
        if (!ni.isUp || ni.isLoopback) continue
        for (addr in ni.inetAddresses) {
            if (addr !is Inet4Address || !addr.isSiteLocalAddress) continue
            if (ni.name.startsWith("wlan")) return addr
            if (fallback == null) fallback = addr
        }
    }
    return fallback
}
