package com.lagradost.cloudstream3.cast.googlecast

import android.content.Context
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.lagradost.api.Log
import com.lagradost.cloudstream3.cast.CastDevice
import com.lagradost.cloudstream3.cast.CastDeviceType
import com.lagradost.cloudstream3.cast.DeviceCapability
import com.lagradost.cloudstream3.cast.DeviceDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Discovers Google Cast (Chromecast) devices on the local network via [MediaRouter].
 *
 * Uses the standard Android MediaRouter API to find Cast-compatible devices.
 * Each discovered route is converted to a [CastDevice] with [CastDeviceType.GOOGLE_CAST].
 *
 * Smart relay: only streams with Referer/cookies/auth go through the phone relay.
 * Non-protected streams are loaded directly by Chromecast.
 */
class GoogleCastDeviceDiscovery : DeviceDiscovery {

    companion object {
        private const val TAG = "GoogleCastDiscovery"
    }

    private var mediaRouter: MediaRouter? = null
    private var routeCallback: MediaRouter.Callback? = null
    private var isActive = false

    private val _discoveredDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<CastDevice>> = _discoveredDevices

    // ── DeviceDiscovery Interface ─────────────────────────────────────

    override fun startDiscovery(context: Context) {
        if (isActive) return
        isActive = true

        try {
            mediaRouter = MediaRouter.getInstance(context)

            val selector = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(
                    CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                ))
                .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
                .build()

            routeCallback = object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    if (!route.isDefault) {
                        addOrUpdateRoute(route)
                    }
                }

                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    if (!route.isDefault) {
                        addOrUpdateRoute(route)
                    }
                }

                override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    removeRoute(route)
                }
            }

            mediaRouter?.addCallback(
                selector,
                routeCallback!!,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
            )

            // Scan existing routes
            mediaRouter?.routes?.forEach { route ->
                if (!route.isDefault && route.matchesSelector(selector)) {
                    addOrUpdateRoute(route)
                }
            }

            Log.d(TAG, "Started Google Cast discovery")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Google Cast discovery: ${e.message}")
            isActive = false
        }
    }

    override fun stopDiscovery() {
        if (!isActive) return
        isActive = false

        try {
            routeCallback?.let { mediaRouter?.removeCallback(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping discovery: ${e.message}")
        }

        routeCallback = null
        _discoveredDevices.value = emptyList()
        Log.d(TAG, "Stopped Google Cast discovery")
    }

    override fun isDiscovering(): Boolean = isActive

    // ── Route → CastDevice Conversion ────────────────────────────────

    private fun addOrUpdateRoute(route: MediaRouter.RouteInfo) {
        val device = routeToCastDevice(route) ?: return

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

        Log.d(TAG, "Found Google Cast device: ${device.name}")
    }

    private fun removeRoute(route: MediaRouter.RouteInfo) {
        val routeId = "gcast_${route.id}"
        synchronized(_discoveredDevices) {
            _discoveredDevices.value = _discoveredDevices.value.filter { it.id != routeId }
        }
        Log.d(TAG, "Removed Google Cast device: ${route.name}")
    }

    /**
     * Convert a MediaRouter route to our CastDevice model.
     */
    private fun routeToCastDevice(route: MediaRouter.RouteInfo): CastDevice? {
        val name = route.name ?: return null

        return CastDevice(
            id = "gcast_${route.id}",
            name = name,
            host = "", // Google Cast SDK handles connections internally
            port = 0,
            type = CastDeviceType.GOOGLE_CAST,
            capabilities = setOf(
                DeviceCapability.VIDEO,
                DeviceCapability.AUDIO,
                DeviceCapability.SUBTITLES,
                DeviceCapability.SEEK,
                DeviceCapability.PAUSE,
                DeviceCapability.STOP,
                DeviceCapability.VOLUME
            ),
            needsRelay = false, // Determined per-link by CastHeaderManager
            metadata = mapOf(
                "routeId" to route.id,
                "description" to (route.description ?: "")
            )
        )
    }
}
