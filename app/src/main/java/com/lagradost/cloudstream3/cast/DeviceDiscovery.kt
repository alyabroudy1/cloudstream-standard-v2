package com.lagradost.cloudstream3.cast

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Discovers [CastDevice]s on the local network.
 *
 * Each protocol family (DLNA, C2C) provides its own implementation.
 * Implementations must be thread-safe — discovery callbacks arrive
 * on arbitrary OS threads (NSD, UPnP background threads).
 *
 * Discovered devices are emitted via [discoveredDevices] (StateFlow),
 * which [CastSessionManager] merges from all registered discoveries.
 */
interface DeviceDiscovery {

    /** Live list of currently visible devices. Thread-safe, observable. */
    val discoveredDevices: StateFlow<List<CastDevice>>

    /**
     * Begin scanning the network.
     * Implementations should be idempotent — calling start twice
     * must not create duplicate listeners.
     */
    fun startDiscovery(context: Context)

    /** Stop scanning and release OS resources (NSD, multicast locks, etc.). */
    fun stopDiscovery()

    /** Whether discovery is currently active. */
    fun isDiscovering(): Boolean
}
