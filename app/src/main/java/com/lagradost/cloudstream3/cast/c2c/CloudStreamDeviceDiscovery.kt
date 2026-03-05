package com.lagradost.cloudstream3.cast.c2c

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.ext.SdkExtensions
import com.lagradost.api.Log
import com.lagradost.cloudstream3.cast.CastDevice
import com.lagradost.cloudstream3.cast.CastDeviceType
import com.lagradost.cloudstream3.cast.DeviceCapability
import com.lagradost.cloudstream3.cast.DeviceDiscovery
import com.lagradost.cloudstream3.cast.NetworkUtils
import com.lagradost.cloudstream3.mvvm.safe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Discovers other CloudStream instances on the local network via mDNS/NSD.
 *
 * Service type: `_cloudstream._tcp`
 *
 * Each CloudStream device advertises itself when the app is running.
 * This discovery service finds those devices for app-to-app casting.
 *
 * Pattern is similar to the existing [FcastManager] but with a
 * CloudStream-specific service type and full [CastDevice] output.
 */
class CloudStreamDeviceDiscovery : DeviceDiscovery {

    companion object {
        private const val TAG = "C2CDiscovery"
        const val SERVICE_TYPE = "_cloudstream._tcp"
        const val SERVICE_PREFIX = "CS"
        const val TCP_PORT = 48723
    }

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isActive = false

    private val _discoveredDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<CastDevice>> = _discoveredDevices

    // ── DeviceDiscovery Interface ─────────────────────────────────────

    override fun startDiscovery(context: Context) {
        if (isActive) return
        isActive = true

        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        discoveryListener = createDiscoveryListener()
        nsdManager?.discoverServices(
            SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener
        )

        Log.d(TAG, "Started CloudStream device discovery")
    }

    override fun stopDiscovery() {
        if (!isActive) return
        isActive = false

        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping discovery: ${e.message}")
        }

        discoveryListener = null
        _discoveredDevices.value = emptyList()
        Log.d(TAG, "Stopped CloudStream device discovery")
    }

    override fun isDiscovering(): Boolean = isActive

    // ── NSD Listener ─────────────────────────────────────────────────

    private fun createDiscoveryListener() = object : NsdManager.DiscoveryListener {

        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
            Log.e(TAG, "Discovery start failed: errorCode=$errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
            Log.e(TAG, "Discovery stop failed: errorCode=$errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String?) {
            Log.d(TAG, "Discovery started for $serviceType")
        }

        override fun onDiscoveryStopped(serviceType: String?) {
            Log.d(TAG, "Discovery stopped for $serviceType")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
            safe {
                if (serviceInfo == null) return@safe
                resolveService(serviceInfo)
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
            if (serviceInfo == null) return
            removeDevice(serviceInfo.serviceName)
            Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
        }
    }

    // ── Service Resolution ───────────────────────────────────────────

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7
        ) {
            // Modern API — uses ServiceInfoCallback
            nsdManager?.registerServiceInfoCallback(
                serviceInfo,
                Runnable::run,
                object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        Log.e(TAG, "Service registration failed: $errorCode")
                    }

                    override fun onServiceUpdated(info: NsdServiceInfo) {
                        val host = info.hostAddresses.firstOrNull()?.hostAddress ?: return
                        addOrUpdateDevice(info.serviceName, host, info.port)
                    }

                    override fun onServiceLost() {
                        removeDevice(serviceInfo.serviceName)
                    }

                    override fun onServiceInfoCallbackUnregistered() {}
                }
            )
        } else {
            // Legacy API
            @Suppress("DEPRECATION")
            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "Resolve failed: ${info?.serviceName}, error=$errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo?) {
                    if (info == null) return
                    @Suppress("DEPRECATION")
                    val host = info.host?.hostAddress ?: return
                    addOrUpdateDevice(info.serviceName, host, info.port)
                }
            })
        }
    }

    // ── Device Management ────────────────────────────────────────────

    private fun addOrUpdateDevice(rawName: String, host: String, port: Int) {
        // Filter out self-device — the phone's own CloudStream instance
        if (NetworkUtils.isOwnIpAddress(host)) {
            Log.d(TAG, "Skipping self-device at $host")
            return
        }

        val device = CastDevice(
            id = "$rawName@$host",
            name = rawName.replace("-", " ").removePrefix("$SERVICE_PREFIX "),
            host = host,
            port = port,
            type = CastDeviceType.CLOUDSTREAM,
            capabilities = setOf(
                DeviceCapability.VIDEO,
                DeviceCapability.AUDIO,
                DeviceCapability.SUBTITLES,
                DeviceCapability.SEEK,
                DeviceCapability.PAUSE,
                DeviceCapability.STOP
            ),
            needsRelay = false // C2C handles headers natively
        )

        synchronized(_discoveredDevices) {
            val current = _discoveredDevices.value.toMutableList()
            val index = current.indexOfFirst { it.id == device.id }
            if (index >= 0) {
                current[index] = device
            } else {
                current.add(device)
            }
            _discoveredDevices.value = current
        }

        Log.d(TAG, "Found CloudStream device: ${device.name} at $host:$port")
    }

    private fun removeDevice(rawName: String) {
        synchronized(_discoveredDevices) {
            _discoveredDevices.value = _discoveredDevices.value.filter {
                !it.id.startsWith("$rawName@")
            }
        }
    }
}
