package com.lagradost.cloudstream3.cast.googlecast

import android.content.Context
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession as GmsCastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.lagradost.api.Log
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.cast.CastDevice
import com.lagradost.cloudstream3.cast.CastErrorCode
import com.lagradost.cloudstream3.cast.CastMediaPayload
import com.lagradost.cloudstream3.cast.CastSession
import com.lagradost.cloudstream3.cast.CastSessionListener
import com.lagradost.cloudstream3.cast.CastSessionState
import com.lagradost.cloudstream3.cast.DisconnectReason
import com.lagradost.cloudstream3.cast.relay.StreamRelayServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cast session implementation for Google Cast (Chromecast) devices.
 *
 * Uses the Google Cast SDK's [RemoteMediaClient] for media loading and transport controls.
 *
 * Streams that need relay (Referer, cookies, auth headers) are served through the phone's
 * [StreamRelayServer] — Chromecast fetches a plain HTTP URL from the relay.
 * Non-protected streams load directly on Chromecast for better performance.
 *
 * Transport controls map directly to [RemoteMediaClient]:
 * - play/pause/stop/seek/setVolume
 * - Position updates via [RemoteMediaClient.Callback]
 */
class GoogleCastSession(
    override val device: CastDevice,
    private val relay: StreamRelayServer,
    private val context: Context?
) : CastSession {

    companion object {
        private const val TAG = "GoogleCastSession"
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<CastSessionListener>()
    private var gmsCastSession: GmsCastSession? = null
    private var remoteMediaClient: RemoteMediaClient? = null
    private var currentStreamId: String? = null

    private val _state = MutableStateFlow(CastSessionState.IDLE)
    override val state: StateFlow<CastSessionState> = _state

    private var mediaCallback: RemoteMediaClient.Callback? = null
    private var sessionManagerListener: SessionManagerListener<GmsCastSession>? = null

    // ── Lifecycle ────────────────────────────────────────────────────

    override suspend fun connect() {
        updateState(CastSessionState.CONNECTING)

        val ctx = context ?: AcraApplication.context
        if (ctx == null) {
            updateState(CastSessionState.ERROR)
            throw IllegalStateException("No context available for Google Cast")
        }

        try {
            val castContext = CastContext.getSharedInstance(ctx)
            val sessionManager = castContext.sessionManager

            // Check if there's already an active Cast session
            val existingSession = sessionManager.currentCastSession
            if (existingSession != null && existingSession.isConnected) {
                gmsCastSession = existingSession
                remoteMediaClient = existingSession.remoteMediaClient
                setupMediaCallback()
                updateState(CastSessionState.CONNECTED)
                Log.d(TAG, "Reusing existing Cast session for ${device.name}")
                return
            }

            // Select the route and start a new session
            val routeId = device.metadata["routeId"]
            if (routeId != null) {
                val mediaRouter = androidx.mediarouter.media.MediaRouter.getInstance(ctx)
                val route = mediaRouter.routes.firstOrNull { it.id == routeId }
                if (route != null) {
                    // Start session via route selection
                    connectViaRoute(sessionManager, route, ctx)
                    return
                }
            }

            // Fallback: try to connect via existing session manager
            val session = sessionManager.currentCastSession
            if (session != null) {
                gmsCastSession = session
                remoteMediaClient = session.remoteMediaClient
                setupMediaCallback()
                updateState(CastSessionState.CONNECTED)
            } else {
                updateState(CastSessionState.ERROR)
                throw IllegalStateException("No Google Cast session available for ${device.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to ${device.name}: ${e.message}")
            updateState(CastSessionState.ERROR)
            throw e
        }
    }

    private suspend fun connectViaRoute(
        sessionManager: SessionManager,
        route: androidx.mediarouter.media.MediaRouter.RouteInfo,
        ctx: Context
    ) = suspendCancellableCoroutine { continuation ->
        sessionManagerListener = object : SessionManagerListener<GmsCastSession> {
            override fun onSessionStarted(session: GmsCastSession, sessionId: String) {
                gmsCastSession = session
                remoteMediaClient = session.remoteMediaClient
                setupMediaCallback()
                updateState(CastSessionState.CONNECTED)
                Log.d(TAG, "Connected to ${device.name}")
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onSessionStartFailed(session: GmsCastSession, error: Int) {
                updateState(CastSessionState.ERROR)
                Log.e(TAG, "Cast session start failed: error=$error")
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("Cast session start failed: error=$error")
                    )
                }
            }

            override fun onSessionEnded(session: GmsCastSession, error: Int) {
                handleSessionEnd(DisconnectReason.DEVICE_GONE)
            }

            override fun onSessionResumed(session: GmsCastSession, wasSuspended: Boolean) {
                gmsCastSession = session
                remoteMediaClient = session.remoteMediaClient
                setupMediaCallback()
                updateState(CastSessionState.CONNECTED)
            }

            override fun onSessionSuspended(session: GmsCastSession, reason: Int) {
                updateState(CastSessionState.BUFFERING)
            }

            override fun onSessionStarting(session: GmsCastSession) {}
            override fun onSessionEnding(session: GmsCastSession) {}
            override fun onSessionResuming(session: GmsCastSession, sessionId: String) {}
            override fun onSessionResumeFailed(session: GmsCastSession, error: Int) {}
        }

        sessionManager.addSessionManagerListener(sessionManagerListener!!, GmsCastSession::class.java)

        // Select the route to trigger connection
        val mediaRouter = androidx.mediarouter.media.MediaRouter.getInstance(ctx)
        mediaRouter.selectRoute(route)

        continuation.invokeOnCancellation {
            sessionManager.removeSessionManagerListener(sessionManagerListener!!, GmsCastSession::class.java)
        }
    }

    override suspend fun disconnect() {
        cleanup()

        try {
            val ctx = context ?: AcraApplication.context
            if (ctx != null) {
                val castContext = CastContext.getSharedInstance(ctx)
                castContext.sessionManager.endCurrentSession(true)
            }
        } catch (_: Exception) { /* Best effort */ }

        // Clean up relay stream
        currentStreamId?.let { relay.unregisterStream(it) }
        currentStreamId = null

        updateState(CastSessionState.DISCONNECTED)
        notifyDisconnected(DisconnectReason.USER_REQUEST)
        Log.d(TAG, "Disconnected from ${device.name}")
    }

    // ── Media Loading ────────────────────────────────────────────────

    override suspend fun loadMedia(payload: CastMediaPayload) {
        updateState(CastSessionState.LOADING)

        // Track relay stream ID for cleanup
        currentStreamId = payload.metadata["relayStreamId"]

        val client = remoteMediaClient
        if (client == null) {
            updateState(CastSessionState.ERROR)
            notifyError(CastErrorCode.MEDIA_LOAD_FAILED, "No RemoteMediaClient available")
            return
        }

        try {
            val mimeType = when {
                payload.mimeType.contains("m3u8", ignoreCase = true) || payload.url.contains(".m3u8", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
                payload.mimeType.contains("mpd", ignoreCase = true) || payload.url.contains(".mpd", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_MPD
                else -> androidx.media3.common.MimeTypes.VIDEO_MP4
            }

            val mediaMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, payload.title ?: "CloudStream")
            }

            val mediaInfo = MediaInfo.Builder(payload.url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(mimeType)
                .setMetadata(mediaMetadata)
                .build()

            val requestData = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .apply {
                    if (payload.startPositionMs != null && payload.startPositionMs > 0) {
                        setCurrentTime(payload.startPositionMs) // setCurrentTime takes milliseconds in MediaLoadRequestData.Builder!
                    }
                }
                .build()

            Log.d(TAG, "Submitting to Chromecast:")
            Log.d(TAG, " - URL: ${mediaInfo.contentId}")
            Log.d(TAG, " - MimeType: ${mediaInfo.contentType}")
            Log.d(TAG, " - StreamType: ${mediaInfo.streamType}")

            client.load(requestData)
            updateState(CastSessionState.PLAYING)
            Log.d(TAG, "Media loaded on ${device.name}: ${payload.url.take(80)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load media: ${e.message}")
            updateState(CastSessionState.ERROR)
            notifyError(CastErrorCode.MEDIA_LOAD_FAILED, "Failed to load media on Chromecast", e)
        }
    }

    // ── Transport Controls ───────────────────────────────────────────
    // RemoteMediaClient calls MUST be on the main thread.

    override suspend fun play() {
        withContext(Dispatchers.Main) {
            remoteMediaClient?.play()
        }
        updateState(CastSessionState.PLAYING)
    }

    override suspend fun pause() {
        withContext(Dispatchers.Main) {
            remoteMediaClient?.pause()
        }
        updateState(CastSessionState.PAUSED)
    }

    override suspend fun stop() {
        withContext(Dispatchers.Main) {
            remoteMediaClient?.stop()
        }
        updateState(CastSessionState.IDLE)
    }

    override suspend fun seek(positionMs: Long) {
        withContext(Dispatchers.Main) {
            remoteMediaClient?.seek(positionMs)
        }
    }

    override suspend fun setVolume(volume: Float) {
        try {
            withContext(Dispatchers.Main) {
                gmsCastSession?.volume = volume.toDouble()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set volume: ${e.message}")
        }
    }

    // ── Listeners ────────────────────────────────────────────────────

    override fun addListener(listener: CastSessionListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: CastSessionListener) {
        listeners.remove(listener)
    }

    // ── Internal ─────────────────────────────────────────────────────

    private fun setupMediaCallback() {
        mediaCallback = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                val client = remoteMediaClient ?: return
                val status = client.mediaStatus ?: return

                // Update position
                val position = client.approximateStreamPosition
                val duration = status.mediaInfo?.streamDuration ?: 0L
                if (position >= 0) {
                    listeners.forEach { it.onPositionChanged(position) }
                }
                if (duration > 0) {
                    listeners.forEach { it.onDurationReceived(duration) }
                }

                // Map player state to our state
                val newState = when (status.playerState) {
                    com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING ->
                        CastSessionState.PLAYING
                    com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PAUSED ->
                        CastSessionState.PAUSED
                    com.google.android.gms.cast.MediaStatus.PLAYER_STATE_BUFFERING ->
                        CastSessionState.BUFFERING
                    com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE ->
                        CastSessionState.CONNECTED
                    else -> null
                }
                newState?.let { updateState(it) }
            }
        }
        remoteMediaClient?.registerCallback(mediaCallback!!)
    }

    private fun cleanup() {
        mediaCallback?.let { remoteMediaClient?.unregisterCallback(it) }
        mediaCallback = null

        val ctx = context ?: AcraApplication.context
        if (ctx != null) {
            try {
                sessionManagerListener?.let {
                    CastContext.getSharedInstance(ctx).sessionManager
                        .removeSessionManagerListener(it, GmsCastSession::class.java)
                }
            } catch (_: Exception) {}
        }
        sessionManagerListener = null
    }

    private fun handleSessionEnd(reason: DisconnectReason) {
        cleanup()
        currentStreamId?.let { relay.unregisterStream(it) }
        currentStreamId = null
        updateState(CastSessionState.DISCONNECTED)
        notifyDisconnected(reason)
    }

    private fun updateState(newState: CastSessionState) {
        _state.value = newState
        listeners.forEach { it.onStateChanged(newState) }
    }

    private fun notifyDisconnected(reason: DisconnectReason) {
        listeners.forEach { it.onDisconnected(reason) }
    }

    private fun notifyError(code: CastErrorCode, message: String, cause: Throwable? = null) {
        listeners.forEach {
            it.onError(com.lagradost.cloudstream3.cast.CastError(code, message, cause))
        }
    }
}
