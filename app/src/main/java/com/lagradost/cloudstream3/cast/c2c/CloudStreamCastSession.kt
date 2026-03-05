package com.lagradost.cloudstream3.cast.c2c

import com.lagradost.api.Log
import com.lagradost.cloudstream3.cast.CastDevice
import com.lagradost.cloudstream3.cast.CastError
import com.lagradost.cloudstream3.cast.CastErrorCode
import com.lagradost.cloudstream3.cast.CastMediaPayload
import com.lagradost.cloudstream3.cast.CastSession
import com.lagradost.cloudstream3.cast.CastSessionListener
import com.lagradost.cloudstream3.cast.CastSessionState
import com.lagradost.cloudstream3.cast.DisconnectReason
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * CloudStream-to-CloudStream cast session (sender side).
 *
 * Sends media payloads to another CloudStream instance over TCP.
 * The receiver auto-plays the media using its internal player.
 *
 * Protocol:
 * ```
 * [4 bytes: payload size (little-endian)] [1 byte: opcode] [N bytes: JSON payload]
 * ```
 *
 * Full headers and subtitles are included in the payload —
 * no relay needed since both sides are CloudStream.
 */
class CloudStreamCastSession(
    override val device: CastDevice
) : CastSession {

    companion object {
        private const val TAG = "C2CSession"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val POLL_INTERVAL_MS = 2_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<CastSessionListener>()
    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null
    private var receiveJob: Job? = null

    private val _state = MutableStateFlow(CastSessionState.IDLE)
    override val state: StateFlow<CastSessionState> = _state

    // ── Lifecycle ────────────────────────────────────────────────────

    override suspend fun connect() = withContext(Dispatchers.IO) {
        updateState(CastSessionState.CONNECTING)
        try {
            val sock = Socket()
            sock.connect(
                java.net.InetSocketAddress(device.host, device.port),
                CONNECT_TIMEOUT_MS
            )
            socket = sock
            outputStream = DataOutputStream(sock.getOutputStream())
            inputStream = DataInputStream(sock.getInputStream())

            // Start listening for responses from the receiver
            startReceiving()

            updateState(CastSessionState.CONNECTED)
            Log.d(TAG, "Connected to ${device.name} at ${device.host}:${device.port}")
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed to ${device.name}: ${e.message}")
            updateState(CastSessionState.ERROR)
            notifyError(CastErrorCode.CONNECTION_FAILED, "Cannot connect to ${device.name}", e)
            throw e
        }
    }

    override suspend fun disconnect() {
        receiveJob?.cancel()
        receiveJob = null

        try {
            sendMessage(C2COpcode.STOP, null)
        } catch (_: Exception) { /* Best effort */ }

        closeSocket()
        updateState(CastSessionState.DISCONNECTED)
        notifyDisconnected(DisconnectReason.USER_REQUEST)
        Log.d(TAG, "Disconnected from ${device.name}")
    }

    // ── Media Loading ────────────────────────────────────────────────

    override suspend fun loadMedia(payload: CastMediaPayload) {
        updateState(CastSessionState.LOADING)
        try {
            sendMessage(C2COpcode.LOAD_MEDIA, payload.toJson())
            updateState(CastSessionState.PLAYING)
            Log.d(TAG, "Sent media to ${device.name}: ${payload.url.take(60)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send media: ${e.message}")
            updateState(CastSessionState.ERROR)
            notifyError(CastErrorCode.MEDIA_LOAD_FAILED, "Failed to send media", e)
        }
    }

    // ── Transport Controls ───────────────────────────────────────────

    override suspend fun play() {
        sendMessage(C2COpcode.PLAY, null)
        updateState(CastSessionState.PLAYING)
    }

    override suspend fun pause() {
        sendMessage(C2COpcode.PAUSE, null)
        updateState(CastSessionState.PAUSED)
    }

    override suspend fun stop() {
        sendMessage(C2COpcode.STOP, null)
        updateState(CastSessionState.CONNECTED)
    }

    override suspend fun seek(positionMs: Long) {
        sendMessage(C2COpcode.SEEK, """{"positionMs":$positionMs}""")
    }

    override suspend fun setVolume(volume: Float) {
        sendMessage(C2COpcode.VOLUME, """{"volume":$volume}""")
    }

    // ── Listeners ────────────────────────────────────────────────────

    override fun addListener(listener: CastSessionListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    override fun removeListener(listener: CastSessionListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    // ── TCP Protocol ─────────────────────────────────────────────────

    /**
     * Send a message over the TCP connection.
     * Format: [4 bytes size LE] [1 byte opcode] [N bytes JSON]
     */
    private suspend fun sendMessage(opcode: C2COpcode, json: String?) = withContext(Dispatchers.IO) {
        val out = outputStream ?: throw IllegalStateException("Not connected")
        val content = json?.toByteArray() ?: ByteArray(0)
        val size = content.size + 1

        // Little-endian size
        val sizeBytes = ByteArray(4) { i -> (size shr (8 * i) and 0xFF).toByte() }

        synchronized(out) {
            out.write(sizeBytes)
            out.write(byteArrayOf(opcode.value))
            out.write(content)
            out.flush()
        }
    }

    /**
     * Listen for status updates from the receiver.
     */
    private fun startReceiving() {
        receiveJob = scope.launch {
            try {
                val input = inputStream ?: return@launch
                while (isActive) {
                    // Read size (4 bytes LE)
                    val sizeBytes = ByteArray(4)
                    input.readFully(sizeBytes)
                    val size = sizeBytes.foldIndexed(0) { i, acc, b ->
                        acc or ((b.toInt() and 0xFF) shl (8 * i))
                    }

                    if (size < 1) continue

                    // Read opcode
                    val opcode = input.readByte()

                    // Read payload
                    val payload = if (size > 1) {
                        val data = ByteArray(size - 1)
                        input.readFully(data)
                        String(data)
                    } else ""

                    handleReceiverMessage(opcode, payload)
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "Receiver connection lost: ${e.message}")
                    updateState(CastSessionState.DISCONNECTED)
                    notifyDisconnected(DisconnectReason.NETWORK_LOSS)
                }
            }
        }
    }

    /**
     * Handle a message from the receiver (status updates).
     */
    private fun handleReceiverMessage(opcode: Byte, payload: String) {
        when (opcode) {
            C2COpcode.STATUS_UPDATE.value -> {
                // Parse position update
                try {
                    // Simple JSON: {"positionMs":12345, "state":"PLAYING"}
                    val posRegex = Regex("\"positionMs\":\\s*(\\d+)")
                    val stateRegex = Regex("\"state\":\\s*\"(\\w+)\"")
                    val durationRegex = Regex("\"durationMs\":\\s*(\\d+)")

                    posRegex.find(payload)?.groupValues?.get(1)?.toLongOrNull()?.let { pos ->
                        synchronized(listeners) { listeners.forEach { it.onPositionChanged(pos) } }
                    }
                    durationRegex.find(payload)?.groupValues?.get(1)?.toLongOrNull()?.let { dur ->
                        synchronized(listeners) { listeners.forEach { it.onDurationReceived(dur) } }
                    }
                    stateRegex.find(payload)?.groupValues?.get(1)?.let { state ->
                        val newState = try {
                            CastSessionState.valueOf(state)
                        } catch (_: Exception) { null }
                        newState?.let { updateState(it) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse status: ${e.message}")
                }
            }
            C2COpcode.ERROR.value -> {
                Log.e(TAG, "Receiver error: $payload")
                notifyError(CastErrorCode.PLAYBACK_FAILED, payload)
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun closeSocket() {
        try { outputStream?.close() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        outputStream = null
        inputStream = null
    }

    private fun updateState(newState: CastSessionState) {
        _state.value = newState
        synchronized(listeners) { listeners.forEach { it.onStateChanged(newState) } }
    }

    private fun notifyError(code: CastErrorCode, message: String, cause: Throwable? = null) {
        synchronized(listeners) {
            listeners.forEach { it.onError(CastError(code, message, cause)) }
        }
    }

    private fun notifyDisconnected(reason: DisconnectReason) {
        synchronized(listeners) { listeners.forEach { it.onDisconnected(reason) } }
    }
}

/**
 * Opcodes for the CloudStream-to-CloudStream TCP protocol.
 */
enum class C2COpcode(val value: Byte) {
    LOAD_MEDIA(0x01),
    PLAY(0x02),
    PAUSE(0x03),
    STOP(0x04),
    SEEK(0x05),
    VOLUME(0x06),
    STATUS_UPDATE(0x10),
    ERROR(0x11),
    PING(0x20),
    PONG(0x21)
}
