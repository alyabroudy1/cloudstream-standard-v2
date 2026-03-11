package com.lagradost.cloudstream3.cast

import kotlinx.coroutines.flow.StateFlow

/**
 * A live connection to a [CastDevice].
 *
 * Implementations handle the protocol-specific details (DLNA SOAP, C2C TCP)
 * while exposing a uniform playback-control surface.
 *
 * Lifecycle:
 *   [connect] → [loadMedia] → [play]/[pause]/[seek] → [stop] → [disconnect]
 *
 * All methods are suspending — they perform network I/O.
 * State changes are emitted via [state] (StateFlow) and [CastSessionListener].
 *
 * The session is owned by [CastSessionManager] (singleton) so it
 * survives Activity config changes.
 */
interface CastSession {

    /** The device this session is connected to. */
    val device: CastDevice

    /** Observable session state — survives rotation via singleton ownership. */
    val state: StateFlow<CastSessionState>

    // ── Lifecycle ────────────────────────────────────────────────────

    /** Open the connection to the device. */
    suspend fun connect()

    /** Gracefully close the connection and release resources. */
    suspend fun disconnect()

    // ── Media Loading ────────────────────────────────────────────────

    /** Load and optionally auto-play media on the device. */
    suspend fun loadMedia(payload: CastMediaPayload)

    // ── Transport Controls ───────────────────────────────────────────

    suspend fun play()
    suspend fun pause()
    suspend fun stop()

    /**
     * Seek to an absolute position.
     * @param positionMs Milliseconds from the start of the media.
     */
    suspend fun seek(positionMs: Long)

    /**
     * Set the device volume.
     * @param volume 0.0 (mute) to 1.0 (max).
     */
    suspend fun setVolume(volume: Float)

    // ── Listeners ────────────────────────────────────────────────────

    fun addListener(listener: CastSessionListener)
    fun removeListener(listener: CastSessionListener)
}

// ── State Machine ────────────────────────────────────────────────────

/** Represents every state a [CastSession] can be in. */
enum class CastSessionState {
    /** No connection. */
    IDLE,
    /** TCP / UPnP handshake in progress. */
    CONNECTING,
    /** Connected but no media loaded. */
    CONNECTED,
    /** Media is being loaded / buffered for first play. */
    LOADING,
    /** Media is actively playing on the device. */
    PLAYING,
    /** Playback is paused. */
    PAUSED,
    /** Temporary stall (network buffering). */
    BUFFERING,
    /** Phone is actively proxying the stream via [StreamRelayServer]. */
    RELAYING,
    /** An unrecoverable error occurred. Check [CastError] for details. */
    ERROR,
    /** Session was disconnected (graceful or network loss). */
    DISCONNECTED
}

// ── Listener ─────────────────────────────────────────────────────────

/** Callback interface for session events. All callbacks are on a background thread. */
interface CastSessionListener {
    fun onStateChanged(state: CastSessionState) {}
    fun onPositionChanged(positionMs: Long) {}
    fun onDurationReceived(durationMs: Long) {}
    fun onVolumeChanged(volume: Float) {}
    fun onError(error: CastError) {}
    fun onDisconnected(reason: DisconnectReason) {}
}

// ── Error / Disconnect Types ─────────────────────────────────────────

/** Structured error for diagnosing cast failures. */
data class CastError(
    val code: CastErrorCode,
    val message: String,
    val cause: Throwable? = null
)

enum class CastErrorCode {
    CONNECTION_FAILED,
    CONNECTION_LOST,
    MEDIA_LOAD_FAILED,
    PLAYBACK_FAILED,
    RELAY_FAILED,
    DEVICE_NOT_FOUND,
    UNSUPPORTED_MEDIA,
    TIMEOUT,
    UNKNOWN
}

enum class DisconnectReason {
    /** User explicitly stopped casting. */
    USER_REQUEST,
    /** Network connection was lost. */
    NETWORK_LOSS,
    /** Device went offline. */
    DEVICE_GONE,
    /** An error forced disconnection. */
    ERROR
}
