package com.lagradost.cloudstream3.cast.dlna

import android.content.Context
import com.lagradost.api.Log
import com.lagradost.cloudstream3.cast.CastDevice
import com.lagradost.cloudstream3.cast.CastDeviceType
import com.lagradost.cloudstream3.cast.DeviceCapability
import com.lagradost.cloudstream3.cast.DeviceDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Discovers DLNA MediaRenderers on the local network via UPnP/SSDP.
 *
 * Uses M-SEARCH multicast to find devices, then fetches their description
 * XML to extract the friendly name and AVTransport control URL.
 *
 * This is a lightweight, zero-dependency implementation — no jupnp needed.
 * The UPnP specification used is minimal: just enough for MediaRenderer discovery.
 */
class DlnaDeviceDiscovery : DeviceDiscovery {

    companion object {
        private const val TAG = "DlnaDiscovery"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val DISCOVERY_INTERVAL_MS = 10_000L // Re-scan every 10s
        private const val DEVICE_TIMEOUT_MS = 60_000L     // Remove after 60s without re-discovery
        private const val SOCKET_TIMEOUT_MS = 5_000       // Wait 5s for responses
        private const val INITIAL_BURST_COUNT = 3          // Rapid M-SEARCH bursts on startup
        private const val INITIAL_BURST_DELAY_MS = 1_500L  // Delay between burst packets
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discoveryJob: Job? = null
    private var isActive = false

    private val _discoveredDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<CastDevice>> = _discoveredDevices

    /** Last seen timestamps for device cleanup. */
    private val deviceTimestamps = mutableMapOf<String, Long>()

    // ── DeviceDiscovery Interface ─────────────────────────────────────

    override fun startDiscovery(context: Context) {
        if (isActive) return
        isActive = true

        discoveryJob = scope.launch {
            Log.d(TAG, "Starting DLNA discovery")

            // Rapid initial burst: 3 M-SEARCH packets in quick succession
            // so TVs are found within seconds, not after the full interval
            for (burst in 1..INITIAL_BURST_COUNT) {
                try {
                    Log.d(TAG, "Initial burst M-SEARCH $burst/$INITIAL_BURST_COUNT")
                    performSsdpSearch()
                } catch (e: Exception) {
                    Log.e(TAG, "SSDP burst $burst error: ${e.message}")
                }
                if (burst < INITIAL_BURST_COUNT) delay(INITIAL_BURST_DELAY_MS)
            }

            // Then settle into the regular interval
            while (isActive) {
                delay(DISCOVERY_INTERVAL_MS)
                try {
                    performSsdpSearch()
                } catch (e: Exception) {
                    Log.e(TAG, "SSDP search error: ${e.message}")
                }
                cleanupStaleDevices()
            }
        }
    }

    override fun stopDiscovery() {
        isActive = false
        discoveryJob?.cancel()
        discoveryJob = null
        _discoveredDevices.value = emptyList()
        deviceTimestamps.clear()
        Log.d(TAG, "Stopped DLNA discovery")
    }

    override fun isDiscovering(): Boolean = isActive

    // ── SSDP M-SEARCH ────────────────────────────────────────────────

    /**
     * Send an SSDP M-SEARCH request and process responses.
     *
     * The SSDP search message follows the UPnP Device Architecture 2.0 spec:
     * ```
     * M-SEARCH * HTTP/1.1
     * HOST: 239.255.255.250:1900
     * MAN: "ssdp:discover"
     * MX: 3
     * ST: urn:schemas-upnp-org:device:MediaRenderer:1
     * ```
     */
    private suspend fun performSsdpSearch() = withContext(Dispatchers.IO) {
        val searchMessage = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 3\r\n")
            append("ST: $SEARCH_TARGET\r\n")
            append("USER-AGENT: CloudStream/1.0 UPnP/2.0\r\n")
            append("\r\n")
        }

        val socket = DatagramSocket().apply {
            soTimeout = SOCKET_TIMEOUT_MS
            broadcast = true
        }

        try {
            // Send M-SEARCH
            val data = searchMessage.toByteArray()
            val packet = DatagramPacket(
                data, data.size,
                InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT
            )
            socket.send(packet)

            Log.d(TAG, "Sent M-SEARCH for MediaRenderers")

            // Collect responses
            val buffer = ByteArray(4096)
            val responsePacket = DatagramPacket(buffer, buffer.size)

            while (isActive) {
                try {
                    socket.receive(responsePacket)
                    val response = String(responsePacket.data, 0, responsePacket.length)
                    handleSsdpResponse(response)
                } catch (_: java.net.SocketTimeoutException) {
                    break // Done collecting
                }
            }
        } finally {
            socket.close()
        }
    }

    /**
     * Parse an SSDP response and fetch the device description.
     */
    private suspend fun handleSsdpResponse(response: String) {
        // Extract LOCATION header — points to the device description XML
        val locationRegex = Regex("LOCATION:\\s*(.+)", RegexOption.IGNORE_CASE)
        val location = locationRegex.find(response)?.groupValues?.get(1)?.trim() ?: return

        // Skip if we already know this device recently
        val existing = deviceTimestamps[location]
        val now = System.currentTimeMillis()
        if (existing != null && now - existing < DISCOVERY_INTERVAL_MS) {
            deviceTimestamps[location] = now // Refresh timestamp
            return
        }

        try {
            val device = fetchDeviceDescription(location)
            if (device != null) {
                deviceTimestamps[location] = now
                addOrUpdateDevice(device)
                Log.d(TAG, "Found DLNA device: ${device.name} at ${device.host}:${device.port}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch device description from $location: ${e.message}")
        }
    }

    // ── Device Description Parsing ───────────────────────────────────

    /**
     * Fetch and parse a UPnP device description XML.
     *
     * Extracts:
     * - friendlyName
     * - UDN (unique device name)
     * - AVTransport controlURL (for SOAP commands)
     * - manufacturer / model (for metadata)
     */
    private suspend fun fetchDeviceDescription(locationUrl: String): CastDevice? =
        withContext(Dispatchers.IO) {
            val conn = URL(locationUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.connect()

            val xml = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            parseDeviceXml(xml, locationUrl)
        }

    /**
     * Parse the UPnP device description XML.
     */
    private fun parseDeviceXml(xml: String, locationUrl: String): CastDevice? {
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(xml.byteInputStream())

            val deviceElement = document.getElementsByTagName("device").item(0) ?: return null

            val friendlyName = getElementText(deviceElement, "friendlyName") ?: "Unknown DLNA Device"
            val udn = getElementText(deviceElement, "UDN") ?: locationUrl
            val manufacturer = getElementText(deviceElement, "manufacturer") ?: ""
            val modelName = getElementText(deviceElement, "modelName") ?: ""

            // Extract AVTransport control URL
            val controlUrl = findServiceControlUrl(document, locationUrl, "AVTransport")
            // Extract RenderingControl URL (for volume control)
            val renderingControlUrl = findServiceControlUrl(document, locationUrl, "RenderingControl")

            // Parse host and port from location URL
            val url = URL(locationUrl)

            // Determine capabilities from the device's service list
            val capabilities = mutableSetOf(
                DeviceCapability.VIDEO,
                DeviceCapability.AUDIO,
                DeviceCapability.STOP
            )
            if (controlUrl != null) {
                capabilities.add(DeviceCapability.SEEK)
                capabilities.add(DeviceCapability.PAUSE)
            }
            if (renderingControlUrl != null) {
                capabilities.add(DeviceCapability.VOLUME)
            }

            return CastDevice(
                id = udn,
                name = friendlyName,
                host = url.host,
                port = url.port.let { if (it == -1) 80 else it },
                type = CastDeviceType.DLNA,
                capabilities = capabilities,
                needsRelay = true, // DLNA devices always need relay
                metadata = mapOf(
                    "manufacturer" to manufacturer,
                    "model" to modelName,
                    "locationUrl" to locationUrl,
                    "controlUrl" to (controlUrl ?: ""),
                    "renderingControlUrl" to (renderingControlUrl ?: "")
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse device XML: ${e.message}")
            return null
        }
    }

    /**
     * Find a UPnP service control URL by service type keyword.
     * Works for AVTransport, RenderingControl, ConnectionManager, etc.
     */
    private fun findServiceControlUrl(
        document: org.w3c.dom.Document,
        locationUrl: String,
        serviceKeyword: String
    ): String? {
        val serviceNodes = document.getElementsByTagName("service")
        for (i in 0 until serviceNodes.length) {
            val service = serviceNodes.item(i)
            val serviceType = getElementText(service, "serviceType")
            if (serviceType?.contains(serviceKeyword) == true) {
                val controlUrl = getElementText(service, "controlURL") ?: return null
                // Resolve relative URL against the device's base URL
                return if (controlUrl.startsWith("http")) {
                    controlUrl
                } else {
                    val base = URL(locationUrl)
                    "${base.protocol}://${base.host}:${base.port.let { if (it == -1) 80 else it }}$controlUrl"
                }
            }
        }
        return null
    }

    // ── Device List Management ───────────────────────────────────────

    private fun addOrUpdateDevice(device: CastDevice) {
        val current = _discoveredDevices.value.toMutableList()
        val index = current.indexOfFirst { it.id == device.id }
        if (index >= 0) {
            current[index] = device
        } else {
            current.add(device)
        }
        _discoveredDevices.value = current
    }

    private fun cleanupStaleDevices() {
        val now = System.currentTimeMillis()
        val staleLocations = deviceTimestamps.entries
            .filter { now - it.value > DEVICE_TIMEOUT_MS }
            .map { it.key }

        if (staleLocations.isNotEmpty()) {
            staleLocations.forEach { deviceTimestamps.remove(it) }
            // Remove stale devices by matching their locationUrl in metadata
            _discoveredDevices.value = _discoveredDevices.value.filter { device ->
                val location = device.metadata["locationUrl"]
                location == null || location !in staleLocations
            }
        }
    }

    // ── XML Helpers ──────────────────────────────────────────────────

    private fun getElementText(parent: org.w3c.dom.Node, tagName: String): String? {
        if (parent is org.w3c.dom.Element) {
            val elements = parent.getElementsByTagName(tagName)
            if (elements.length > 0) {
                return elements.item(0).textContent?.trim()
            }
        }
        // Fallback: search child nodes
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeName == tagName) {
                return child.textContent?.trim()
            }
        }
        return null
    }
}
