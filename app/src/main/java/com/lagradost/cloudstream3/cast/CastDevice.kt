package com.lagradost.cloudstream3.cast

/**
 * Represents a discovered cast-capable device on the network.
 *
 * Immutable value type — discovery services create these,
 * [CastSessionManager] consumes them.
 */
data class CastDevice(
    /** Unique device identifier (UDN for DLNA, hostname for C2C). */
    val id: String,
    /** Human-readable name ("Living Room TV"). */
    val name: String,
    /** IP address on the local network. */
    val host: String,
    /** Service port. */
    val port: Int,
    /** Protocol this device speaks. */
    val type: CastDeviceType,
    /** What playback controls the device supports. */
    val capabilities: Set<DeviceCapability> = emptySet(),
    /**
     * When true the stream is routed through the phone's [StreamRelayServer]
     * so the device receives a plain HTTP URL with no auth headers.
     * Defaults to true for DLNA (old devices), false for C2C.
     */
    val needsRelay: Boolean = true,
    /** Extra info (manufacturer, model, service URLs, etc.). */
    val metadata: Map<String, String> = emptyMap()
)

/** Protocol families the framework supports. Extend this enum to add new cast targets. */
enum class CastDeviceType {
    /** UPnP / DLNA Media Renderer. */
    DLNA,
    /** Another CloudStream instance (app-to-app). */
    CLOUDSTREAM,
    /** Google Cast / Chromecast device. */
    GOOGLE_CAST
}

/** Fine-grained capabilities a device may advertise. */
enum class DeviceCapability {
    VIDEO,
    AUDIO,
    SUBTITLES,
    SEEK,
    PAUSE,
    VOLUME,
    STOP
}
