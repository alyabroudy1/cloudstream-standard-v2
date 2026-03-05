package com.lagradost.cloudstream3.cast.c2c

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.lagradost.api.Log
import com.lagradost.cloudstream3.cast.CastMediaPayload
import com.lagradost.cloudstream3.cast.CastSubtitle
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Receives cast requests from other CloudStream instances.
 *
 * Runs a TCP server that listens for incoming [CastMediaPayload] messages.
 * On receive, auto-plays the media in the internal player — no confirmation dialog.
 *
 * Also registers this device via mDNS so other CloudStream instances can discover it.
 *
 * Lifecycle: start in [MainActivity], stop on app exit.
 */
class CloudStreamCastReceiver {

    companion object {
        private const val TAG = "C2CReceiver"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var isRunning = false

    /** Callback to launch the player with received media. */
    var onMediaReceived: ((CastMediaPayload) -> Unit)? = null

    // ── Lifecycle ────────────────────────────────────────────────────

    /**
     * Start the receiver: register mDNS service and listen for connections.
     */
    fun start(context: Context) {
        if (isRunning) return
        isRunning = true

        // Start TCP server
        scope.launch {
            startServer()
        }

        // Register mDNS service
        registerNsdService(context)

        Log.d(TAG, "Cast receiver started")
    }

    /**
     * Stop the receiver and clean up resources.
     */
    fun stop() {
        isRunning = false

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
        registrationListener = null

        Log.d(TAG, "Cast receiver stopped")
    }

    // ── TCP Server ───────────────────────────────────────────────────

    private suspend fun startServer() = withContext(Dispatchers.IO) {
        try {
            val server = ServerSocket(CloudStreamDeviceDiscovery.TCP_PORT)
            serverSocket = server
            Log.d(TAG, "Listening on port ${CloudStreamDeviceDiscovery.TCP_PORT}")

            while (isRunning) {
                try {
                    val client = server.accept()
                    scope.launch { handleClient(client) }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server start failed: ${e.message}")
        }
    }

    /**
     * Handle an incoming client connection.
     * Reads messages and dispatches them.
     */
    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())

            while (isRunning && !client.isClosed) {
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

                handleMessage(opcode, payload, output)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client disconnected: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Process an incoming message from the sender.
     */
    private fun handleMessage(opcode: Byte, payload: String, output: DataOutputStream) {
        when (opcode) {
            C2COpcode.LOAD_MEDIA.value -> {
                Log.d(TAG, "Received media payload")
                val mediaPayload = parseMediaPayload(payload)
                if (mediaPayload != null) {
                    // Auto-play — no confirmation dialog
                    onMediaReceived?.invoke(mediaPayload)
                    sendStatus(output, "PLAYING", 0)
                } else {
                    sendError(output, "Failed to parse media payload")
                }
            }
            C2COpcode.PLAY.value -> {
                Log.d(TAG, "Received PLAY command")
                // TODO: control internal player
            }
            C2COpcode.PAUSE.value -> {
                Log.d(TAG, "Received PAUSE command")
                // TODO: control internal player
            }
            C2COpcode.STOP.value -> {
                Log.d(TAG, "Received STOP command")
                // TODO: control internal player
            }
            C2COpcode.SEEK.value -> {
                Log.d(TAG, "Received SEEK: $payload")
                // TODO: control internal player
            }
            C2COpcode.PING.value -> {
                sendMessage(output, C2COpcode.PONG, "")
            }
        }
    }

    // ── mDNS Registration ────────────────────────────────────────────

    private fun registerNsdService(context: Context) {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val deviceName = "${CloudStreamDeviceDiscovery.SERVICE_PREFIX}-${Build.MANUFACTURER}-${Build.MODEL}"
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = CloudStreamDeviceDiscovery.SERVICE_TYPE
            port = CloudStreamDeviceDiscovery.TCP_PORT
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "mDNS service registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS registration failed: errorCode=$errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "mDNS service unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS unregistration failed: errorCode=$errorCode")
            }
        }

        nsdManager?.registerService(
            serviceInfo,
            NsdManager.PROTOCOL_DNS_SD,
            registrationListener
        )
    }

    // ── Protocol Helpers ─────────────────────────────────────────────

    private fun sendStatus(output: DataOutputStream, state: String, positionMs: Long) {
        val json = """{"state":"$state","positionMs":$positionMs}"""
        sendMessage(output, C2COpcode.STATUS_UPDATE, json)
    }

    private fun sendError(output: DataOutputStream, message: String) {
        sendMessage(output, C2COpcode.ERROR, """{"error":"$message"}""")
    }

    private fun sendMessage(output: DataOutputStream, opcode: C2COpcode, json: String) {
        ioSafe {
            val content = json.toByteArray()
            val size = content.size + 1
            val sizeBytes = ByteArray(4) { i -> (size shr (8 * i) and 0xFF).toByte() }

            synchronized(output) {
                output.write(sizeBytes)
                output.write(byteArrayOf(opcode.value))
                output.write(content)
                output.flush()
            }
        }
    }

    // ── JSON Parsing ─────────────────────────────────────────────────

    private fun parseMediaPayload(json: String): CastMediaPayload? {
        return try {
            val obj = JSONObject(json)
            val subtitles = obj.optJSONArray("subtitles")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val sub = arr.getJSONObject(i)
                    CastSubtitle(
                        url = sub.getString("url"),
                        language = sub.optString("language", ""),
                        label = sub.optString("label", ""),
                        mimeType = sub.optString("mimeType", "text/vtt")
                    )
                }
            } ?: emptyList()

            val headers = obj.optJSONObject("headers")?.let { h ->
                h.keys().asSequence().associate { k -> k to h.getString(k) }
            } ?: emptyMap()

            CastMediaPayload(
                url = obj.getString("url"),
                mimeType = obj.optString("mimeType", "video/mp4"),
                title = obj.optString("title").takeIf { !it.isNullOrEmpty() && it != "null" },
                posterUrl = obj.optString("posterUrl").takeIf { !it.isNullOrEmpty() && it != "null" },
                subtitles = subtitles,
                startPositionMs = obj.optLong("startPositionMs", 0).takeIf { it > 0 },
                headers = headers,
                isRelayed = obj.optBoolean("isRelayed", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse media payload: ${e.message}")
            null
        }
    }
}
