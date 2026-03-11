package com.lagradost.cloudstream3.cast

import android.content.Context
import com.lagradost.api.Log
import com.lagradost.cloudstream3.cast.relay.CastRelayService
import com.lagradost.cloudstream3.cast.relay.StreamRelayServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Singleton that manages device discovery, active cast sessions,
 * and the stream relay server.
 *
 * Being an `object` (singleton), all state survives Activity config changes
 * (rotation, theme change, etc.) without any ViewModel glue.
 *
 * Usage:
 * ```
 * // App startup
 * CastSessionManager.registerDiscovery(DlnaDeviceDiscovery())
 * CastSessionManager.registerDiscovery(CloudStreamDeviceDiscovery())
 * CastSessionManager.startAllDiscovery(context)
 *
 * // Cast to a device
 * val session = CastSessionManager.connect(device)
 * session.loadMedia(payload)
 *
 * // Observe from UI
 * CastSessionManager.activeSession.collect { session -> ... }
 * CastSessionManager.allDevices.collect { devices -> ... }
 * ```
 */
object CastSessionManager {

    private const val TAG = "CastSessionManager"

    /** Coroutine scope tied to the app lifetime (never cancelled). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Discovery ────────────────────────────────────────────────────

    private val discoveryServices = mutableListOf<DeviceDiscovery>()

    /** Application context for WiFi IP detection. */
    private var appContext: Context? = null

    /** Merged device list from all registered discovery services. */
    private val _allDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    val allDevices: StateFlow<List<CastDevice>> = _allDevices

    // ── Session ──────────────────────────────────────────────────────

    private val _activeSession = MutableStateFlow<CastSession?>(null)
    val activeSession: StateFlow<CastSession?> = _activeSession

    // ── Episode Context ──────────────────────────────────────────────

    private val _castEpisodeContext = MutableStateFlow<CastEpisodeContext?>(null)
    /** Current episode context — observed by UI and auto-play. */
    val castEpisodeContext: StateFlow<CastEpisodeContext?> = _castEpisodeContext

    /** Update the episode context (called after loadMedia). */
    fun updateEpisodeContext(context: CastEpisodeContext?) {
        _castEpisodeContext.value = context
    }

    // ── Link Loader ─────────────────────────────────────────────────

    /**
     * Registered by the ViewModel to provide fresh link loading for
     * auto-play and episode navigation. Null when no show is loaded.
     */
    @Volatile
    var linkLoader: CastLinkLoader? = null

    // ── Stream Relay ─────────────────────────────────────────────────

    /** The shared relay server. Started on-demand when a session needs it. */
    val relay = StreamRelayServer()

    // ── Discovery Management ─────────────────────────────────────────

    /**
     * Register a discovery service.
     * Call before [startAllDiscovery].
     */
    fun registerDiscovery(discovery: DeviceDiscovery) {
        synchronized(discoveryServices) {
            discoveryServices.add(discovery)
        }
        // Observe this discovery's device list and merge into allDevices
        scope.launch {
            discovery.discoveredDevices.collect {
                mergeDeviceLists()
            }
        }
    }

    /**
     * Start all registered discovery services.
     * Safe to call multiple times — each service is idempotent.
     */
    fun startAllDiscovery(context: Context) {
        appContext = context.applicationContext
        synchronized(discoveryServices) {
            discoveryServices.forEach { discovery ->
                try {
                    discovery.startDiscovery(context)
                    Log.d(TAG, "Started discovery: ${discovery::class.simpleName}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start discovery: ${discovery::class.simpleName} - ${e.message}")
                }
            }
        }
    }

    /**
     * Stop all discovery services and release OS resources.
     */
    fun stopAllDiscovery() {
        synchronized(discoveryServices) {
            discoveryServices.forEach { discovery ->
                try {
                    discovery.stopDiscovery()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop discovery: ${discovery::class.simpleName} - ${e.message}")
                }
            }
        }
    }

    // ── Session Management ───────────────────────────────────────────

    /**
     * Connect to a device, creating the appropriate session type.
     *
     * If the device needs relay, the relay server is started automatically.
     * Only one active session at a time — connecting to a new device
     * disconnects the previous one.
     *
     * @return The connected [CastSession].
     */
    suspend fun connect(device: CastDevice): CastSession {
        // If already connected to the SAME device, reuse the existing session
        val current = _activeSession.value
        if (current != null && current.device.id == device.id &&
            current.state.value != CastSessionState.DISCONNECTED &&
            current.state.value != CastSessionState.ERROR) {
            Log.d(TAG, "Reusing existing session to ${device.name}")
            return current
        }

        // Disconnect any existing session (different device)
        disconnect()

        Log.d(TAG, "Connecting to ${device.name} (${device.type})")

        // Start relay if needed
        if (device.needsRelay) {
            relay.start(appContext)
            // Keep relay alive when app is backgrounded
            appContext?.let { CastRelayService.start(it) }
            Log.d(TAG, "Relay server started for ${device.name}")
        }

        // Create protocol-specific session
        val session = createSession(device)

        // Wire up a listener to clean up on disconnect
        session.addListener(object : CastSessionListener {
            override fun onDisconnected(reason: DisconnectReason) {
                scope.launch {
                    Log.d(TAG, "Session disconnected: ${device.name}, reason: $reason")
                    _activeSession.value = null
                    if (!hasRelayNeededSession()) {
                        relay.stop()
                        appContext?.let { CastRelayService.stop(it) }
                    }
                }
            }
        })

        try {
            session.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to ${device.name}: ${e.message}")
            // Clean up relay if connection failed
            if (device.needsRelay && !hasRelayNeededSession()) {
                relay.stop()
            }
            throw e
        }

        _activeSession.value = session

        Log.d(TAG, "Connected to ${device.name}")
        return session
    }

    /**
     * Disconnect the active session and clean up resources.
     */
    suspend fun disconnect() {
        val current = _activeSession.value ?: return
        try {
            current.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting: ${e.message}")
        }
        _activeSession.value = null
        _castEpisodeContext.value = null
        linkLoader = null

        if (!hasRelayNeededSession()) {
            relay.stop()
            appContext?.let { CastRelayService.stop(it) }
        }
    }

    /** Whether there is an active, connected session. */
    fun isConnected(): Boolean {
        val session = _activeSession.value ?: return false
        return session.state.value != CastSessionState.DISCONNECTED &&
               session.state.value != CastSessionState.ERROR &&
               session.state.value != CastSessionState.IDLE
    }

    /** Get the current session, if any. */
    fun getActiveSession(): CastSession? = _activeSession.value

    // ── Internal ─────────────────────────────────────────────────────

    /**
     * Factory method — creates the right session type for the device.
     * New cast protocols just add a branch here.
     */
    private fun createSession(device: CastDevice): CastSession {
        return when (device.type) {
            CastDeviceType.DLNA -> {
                com.lagradost.cloudstream3.cast.dlna.DlnaCastSession(device, relay)
            }
            CastDeviceType.CLOUDSTREAM -> {
                com.lagradost.cloudstream3.cast.c2c.CloudStreamCastSession(device)
            }
            CastDeviceType.GOOGLE_CAST -> {
                com.lagradost.cloudstream3.cast.googlecast.GoogleCastSession(device, relay, appContext)
            }
        }
    }

    /**
     * Merge device lists from all discovery services into [_allDevices].
     */
    private fun mergeDeviceLists() {
        val merged = synchronized(discoveryServices) {
            discoveryServices.flatMap { it.discoveredDevices.value }
        }
        _allDevices.value = merged.distinctBy { it.id }
    }

    /**
     * Check if any session needs the relay (to decide whether to stop it).
     */
    private fun hasRelayNeededSession(): Boolean {
        return _activeSession.value?.device?.needsRelay == true
    }
}
