package com.lagradost.cloudstream3.cast.dlna

import com.lagradost.api.Log
import com.lagradost.cloudstream3.cast.CastDevice
import com.lagradost.cloudstream3.cast.CastError
import com.lagradost.cloudstream3.cast.CastErrorCode
import com.lagradost.cloudstream3.cast.CastHeaderManager
import com.lagradost.cloudstream3.cast.CastMediaPayload
import com.lagradost.cloudstream3.cast.CastSession
import com.lagradost.cloudstream3.cast.CastSessionListener
import com.lagradost.cloudstream3.cast.CastSessionState
import com.lagradost.cloudstream3.cast.DisconnectReason
import com.lagradost.cloudstream3.cast.relay.StreamRelayServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * DLNA cast session — controls a MediaRenderer via SOAP/XML (AVTransport).
 *
 * All media is routed through the phone's [StreamRelayServer], so the
 * renderer receives a simple HTTP URL with no auth headers.
 *
 * Transport control uses the UPnP AVTransport:1 service:
 * - SetAVTransportURI — load the relay URL
 * - Play / Pause / Stop — transport control
 * - Seek — position seeking
 * - GetTransportInfo — poll playback state
 * - GetPositionInfo — poll current position
 *
 * The session automatically polls the device for state updates
 * and reports them via [CastSessionListener].
 */
class DlnaCastSession(
    override val device: CastDevice,
    private val relay: StreamRelayServer
) : CastSession {

    companion object {
        private const val TAG = "DlnaCastSession"
        private const val SOAP_NS = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<CastSessionListener>()
    private var pollJob: Job? = null
    private var currentStreamId: String? = null

    private val _state = MutableStateFlow(CastSessionState.IDLE)
    override val state: StateFlow<CastSessionState> = _state

    /** The AVTransport control URL from device metadata. */
    private val controlUrl: String?
        get() = device.metadata["controlUrl"]

    // ── Lifecycle ────────────────────────────────────────────────────

    override suspend fun connect() {
        updateState(CastSessionState.CONNECTING)

        // Validate that we have the AVTransport control URL
        if (controlUrl == null) {
            Log.e(TAG, "No AVTransport control URL for ${device.name}")
            updateState(CastSessionState.ERROR)
            notifyError(CastErrorCode.DEVICE_NOT_FOUND, "No AVTransport control URL for ${device.name}", null)
            throw IllegalStateException("No AVTransport control URL for ${device.name}")
        }

        try {
            // Verify the device is reachable by fetching transport info
            val info = withRetry { getTransportInfo() }
            Log.d(TAG, "Connected to ${device.name}, transport state: $info")
            updateState(CastSessionState.CONNECTED)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to ${device.name}: ${e.message}")
            updateState(CastSessionState.ERROR)
            notifyError(CastErrorCode.CONNECTION_FAILED, "Cannot reach ${device.name}", e)
            throw e
        }
    }

    override suspend fun disconnect() {
        pollJob?.cancel()
        pollJob = null

        try {
            if (controlUrl != null) {
                soapAction("Stop", "<InstanceID>0</InstanceID>")
            }
        } catch (_: Exception) { /* Best effort */ }

        // Clean up relay stream
        currentStreamId?.let { relay.unregisterStream(it) }
        currentStreamId = null

        // Cancel all coroutines owned by this session
        scope.cancel()

        updateState(CastSessionState.DISCONNECTED)
        notifyDisconnected(DisconnectReason.USER_REQUEST)
        Log.d(TAG, "Disconnected from ${device.name}")
    }

    // ── Media Loading ────────────────────────────────────────────────

    override suspend fun loadMedia(payload: CastMediaPayload) {
        updateState(CastSessionState.LOADING)

        // Track the relay stream ID for cleanup on disconnect
        currentStreamId = payload.metadata["relayStreamId"]

        val mediaUrl = payload.url
        val mimeType = payload.mimeType

        Log.d(TAG, "Loading media on ${device.name}: ${mediaUrl.take(80)}...")

        try {
            // Build DIDL-Lite metadata for the renderer
            val didlMetadata = buildDidlMetadata(
                title = payload.title ?: "CloudStream",
                mimeType = mimeType,
                url = mediaUrl
            )

            // Set the transport URI
            withRetry {
                soapAction(
                    "SetAVTransportURI",
                    """
                    <InstanceID>0</InstanceID>
                    <CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>
                    <CurrentURIMetaData>${escapeXml(didlMetadata)}</CurrentURIMetaData>
                    """.trimIndent()
                )
            }

            // Auto-play
            withRetry { soapAction("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>") }

            // Seek to start position if specified
            if (payload.startPositionMs != null && payload.startPositionMs > 0) {
                val seekTarget = formatTime(payload.startPositionMs)
                delay(500) // Give the renderer a moment to start
                withRetry {
                    soapAction(
                        "Seek",
                        "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$seekTarget</Target>"
                    )
                }
            }

            updateState(CastSessionState.PLAYING)
            startPolling()

            Log.d(TAG, "Media loaded and playing on ${device.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load media on ${device.name}: ${e.message}")
            updateState(CastSessionState.ERROR)
            notifyError(CastErrorCode.MEDIA_LOAD_FAILED, "Failed to load media", e)
        }
    }

    // ── Transport Controls ───────────────────────────────────────────

    override suspend fun play() {
        withRetry { soapAction("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>") }
        updateState(CastSessionState.PLAYING)
    }

    override suspend fun pause() {
        withRetry { soapAction("Pause", "<InstanceID>0</InstanceID>") }
        updateState(CastSessionState.PAUSED)
    }

    override suspend fun stop() {
        pollJob?.cancel()
        withRetry { soapAction("Stop", "<InstanceID>0</InstanceID>") }
        updateState(CastSessionState.CONNECTED)
    }

    override suspend fun seek(positionMs: Long) {
        val target = formatTime(positionMs)
        withRetry {
            soapAction(
                "Seek",
                "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target>"
            )
        }
    }

    override suspend fun setVolume(volume: Float) {
        // Volume is on the RenderingControl service, not AVTransport
        // We'd need the RenderingControl URL — skip for now if not available
        val rcUrl = device.metadata["renderingControlUrl"]
        if (rcUrl != null) {
            val desiredVolume = (volume * 100).toInt().coerceIn(0, 100)
            soapActionOnUrl(
                rcUrl,
                "urn:schemas-upnp-org:service:RenderingControl:1",
                "SetVolume",
                """
                <InstanceID>0</InstanceID>
                <Channel>Master</Channel>
                <DesiredVolume>$desiredVolume</DesiredVolume>
                """.trimIndent()
            )
        }
    }

    // ── Listeners ────────────────────────────────────────────────────

    override fun addListener(listener: CastSessionListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    override fun removeListener(listener: CastSessionListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    // ── State Polling ────────────────────────────────────────────────

    /**
     * Poll the device for transport state and position.
     * DLNA doesn't push events reliably, so we poll.
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    pollTransportState()
                    pollPosition()
                } catch (e: Exception) {
                    Log.w(TAG, "Poll error: ${e.message}")
                    // Don't stop polling on transient errors
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollTransportState() {
        val info = getTransportInfo()
        val newState = when {
            info.contains("PLAYING") -> CastSessionState.PLAYING
            info.contains("PAUSED") -> CastSessionState.PAUSED
            info.contains("STOPPED") -> CastSessionState.CONNECTED
            info.contains("TRANSITIONING") -> CastSessionState.BUFFERING
            info.contains("NO_MEDIA_PRESENT") -> CastSessionState.CONNECTED
            else -> _state.value
        }
        if (newState != _state.value) {
            updateState(newState)
        }
    }

    private suspend fun pollPosition() {
        val positionInfo = getPositionInfo()
        // Parse position from the response
        val position = parseTimeFromResponse(positionInfo, "RelTime")
        val duration = parseTimeFromResponse(positionInfo, "TrackDuration")
        if (position >= 0) {
            synchronized(listeners) {
                listeners.forEach { it.onPositionChanged(position) }
            }
        }
        if (duration > 0) {
            synchronized(listeners) {
                listeners.forEach { it.onDurationReceived(duration) }
            }
        }
    }

    // ── SOAP Actions ─────────────────────────────────────────────────

    /**
     * Send a SOAP action to the AVTransport service.
     */
    private suspend fun soapAction(action: String, body: String): String {
        return soapActionOnUrl(controlUrl!!, SOAP_NS, action, body)
    }

    /**
     * Send a SOAP action to any UPnP service URL.
     */
    private suspend fun soapActionOnUrl(
        url: String,
        serviceNs: String,
        action: String,
        body: String
    ): String = withContext(Dispatchers.IO) {
        val soapEnvelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                        s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:$action xmlns:u="$serviceNs">
                        $body
                    </u:$action>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
        conn.setRequestProperty("SOAPAction", "\"$serviceNs#$action\"")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.doOutput = true

        OutputStreamWriter(conn.outputStream).use { it.write(soapEnvelope) }

        val responseCode = conn.responseCode
        val response = try {
            conn.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }

        if (responseCode !in 200..299) {
            Log.w(TAG, "SOAP $action returned $responseCode: ${response.take(200)}")
        }

        response
    }

    private suspend fun getTransportInfo(): String {
        return soapAction("GetTransportInfo", "<InstanceID>0</InstanceID>")
    }

    private suspend fun getPositionInfo(): String {
        return soapAction("GetPositionInfo", "<InstanceID>0</InstanceID>")
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun updateState(newState: CastSessionState) {
        _state.value = newState
        synchronized(listeners) {
            listeners.forEach { it.onStateChanged(newState) }
        }
    }

    private fun notifyError(code: CastErrorCode, message: String, cause: Throwable? = null) {
        val error = CastError(code, message, cause)
        synchronized(listeners) {
            listeners.forEach { it.onError(error) }
        }
    }

    private fun notifyDisconnected(reason: DisconnectReason) {
        synchronized(listeners) {
            listeners.forEach { it.onDisconnected(reason) }
        }
    }

    /**
     * Retry an operation with exponential backoff.
     */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var lastException: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            }
        }
        throw lastException!!
    }

    /**
     * Build DIDL-Lite metadata XML for the media.
     */
    private fun buildDidlMetadata(title: String, mimeType: String, url: String): String {
        val protocolInfo = "http-get:*:$mimeType:*"
        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                       xmlns:dc="http://purl.org/dc/elements/1.1/"
                       xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
                <item id="0" parentID="-1" restricted="1">
                    <dc:title>${escapeXml(title)}</dc:title>
                    <upnp:class>object.item.videoItem</upnp:class>
                    <res protocolInfo="$protocolInfo">${escapeXml(url)}</res>
                </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    /**
     * Format milliseconds as HH:MM:SS for DLNA Seek.
     */
    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    /**
     * Parse a time value from a SOAP response.
     * Looks for <tagName>HH:MM:SS</tagName> and returns milliseconds.
     */
    private fun parseTimeFromResponse(response: String, tagName: String): Long {
        val regex = Regex("<$tagName>([^<]+)</$tagName>")
        val match = regex.find(response) ?: return -1
        val time = match.groupValues[1]
        return parseTimeString(time)
    }

    /**
     * Parse HH:MM:SS or HH:MM:SS.mmm to milliseconds.
     */
    private fun parseTimeString(time: String): Long {
        return try {
            val parts = time.split(":")
            if (parts.size < 3) return -1
            val hours = parts[0].toLong()
            val minutes = parts[1].toLong()
            val secondsParts = parts[2].split(".")
            val seconds = secondsParts[0].toLong()
            val millis = if (secondsParts.size > 1) {
                secondsParts[1].padEnd(3, '0').take(3).toLong()
            } else 0

            (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
        } catch (_: Exception) {
            -1
        }
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
