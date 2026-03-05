package com.lagradost.cloudstream3.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.lagradost.api.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Shared network utilities for the cast framework.
 *
 * Provides reliable WiFi IP detection used by:
 * - [StreamRelayServer] — relay URL generation
 * - [DlnaDeviceDiscovery] / [CloudStreamDeviceDiscovery] — self-device filtering
 * - [CastSessionManager] — relay address verification
 *
 * WiFi IP is strongly preferred over LTE/VPN because cast devices
 * are always on the local WiFi network.
 */
object NetworkUtils {

    private const val TAG = "CastNetworkUtils"

    @Volatile
    private var cachedWifiIp: String? = null

    /**
     * Get the device's WiFi IP address.
     *
     * Tries [WifiManager] first (most reliable), then falls back to
     * iterating [NetworkInterface]s and preferring `wlan*` interfaces.
     *
     * @param context Application context (for WifiManager). Pass null to skip WifiManager.
     * @return IPv4 address string, or `"127.0.0.1"` if no WiFi is available.
     */
    fun getWifiIpAddress(context: Context? = null): String {
        cachedWifiIp?.let { return it }
        return resolveWifiIpAddress(context).also { cachedWifiIp = it }
    }

    /**
     * Invalidate the cached IP. Call when the network changes or the relay stops.
     */
    fun invalidateCache() {
        cachedWifiIp = null
    }

    /**
     * Check whether the given [host] is an IP address belonging to this device.
     * Used to filter out self-device from discovery results.
     */
    fun isOwnIpAddress(host: String): Boolean {
        try {
            val ownAddresses = mutableSetOf<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    addr.hostAddress?.let { ownAddresses.add(it) }
                }
            }
            return host in ownAddresses
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check own IP: ${e.message}")
            return false
        }
    }

    // ── Internal ─────────────────────────────────────────────────────

    private fun resolveWifiIpAddress(context: Context?): String {
        // Method 1: WifiManager (most reliable for WiFi IP)
        if (context != null) {
            try {
                val wifiManager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val wifiInfo = wifiManager?.connectionInfo
                val ipInt = wifiInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    val ip = formatIpAddress(ipInt)
                    if (ip != "0.0.0.0") {
                        Log.d(TAG, "WiFi IP from WifiManager: $ip")
                        return ip
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "WifiManager failed: ${e.message}")
            }
        }

        // Method 2: NetworkInterface scan (fallback, prefers wlan*)
        return resolveFromNetworkInterfaces()
    }

    private fun resolveFromNetworkInterfaces(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            var fallback: String? = null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        // Prefer WiFi interfaces (wlan0, wlan1) over VPN/mobile
                        if (iface.name.startsWith("wlan")) {
                            Log.d(TAG, "WiFi IP from interface ${iface.name}: $ip")
                            return ip
                        }
                        if (fallback == null) fallback = ip
                    }
                }
            }
            fallback?.let {
                Log.d(TAG, "Using fallback IP: $it")
                return it
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP: ${e.message}")
        }
        return "127.0.0.1"
    }

    @Suppress("DEPRECATION")
    private fun formatIpAddress(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }
}
