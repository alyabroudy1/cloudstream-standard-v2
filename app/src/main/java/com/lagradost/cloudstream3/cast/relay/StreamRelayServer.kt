package com.lagradost.cloudstream3.cast.relay

import android.content.Context
import com.lagradost.api.Log
import com.lagradost.cloudstream3.cast.NetworkUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Local HTTP server that routes video streams through the phone.
 *
 * The phone fetches the source stream with full headers (Referer, cookies, etc.)
 * and re-serves it as a plain HTTP URL that any device can play.
 *
 * Flow:
 * ```
 * Old TV / Projector  <── plain HTTP ──  Phone (this server)  ──> Source CDN (with auth headers)
 * ```
 *
 * Supports:
 * - Direct video proxy (MP4/WebM) with Range request forwarding for seeking
 * - DASH manifest rewriting (segment URLs → relay URLs)
 * - HLS playlist rewriting (.ts segment URLs → relay URLs)
 *
 * Uses raw [ServerSocket] since `com.sun.net.httpserver` is not available on Android.
 *
 * Lifecycle is managed by [CastSessionManager] — starts when a relay-needing
 * session connects, stops when it disconnects.
 */
class StreamRelayServer {

    companion object {
        private const val TAG = "StreamRelayServer"
        private const val DEFAULT_PORT = 48721
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB chunks
    }

    private var serverSocket: ServerSocket? = null
    private val activeStreams = ConcurrentHashMap<String, RelayStream>()
    private var executor = Executors.newCachedThreadPool()
    @Volatile private var running = false

    /** The port the server is running on. */
    var port: Int = DEFAULT_PORT
        private set

    /** Whether the server is currently accepting connections. */
    val isRunning: Boolean get() = running

    // ── Lifecycle ────────────────────────────────────────────────────

    /**
     * Start the relay server. Idempotent — calling twice is safe.
     */
    fun start(context: Context? = null) {
        if (running) return

        // Recreate executor if it was previously shut down
        if (executor.isShutdown) {
            executor = Executors.newCachedThreadPool()
        }

        // Resolve and cache WiFi IP before starting
        NetworkUtils.invalidateCache()
        NetworkUtils.getWifiIpAddress(context)

        try {
            val ss = ServerSocket(port)
            serverSocket = ss
            running = true
            port = ss.localPort
            executor.submit { acceptLoop(ss) }
            Log.d(TAG, "Relay server started on port $port")
        } catch (e: Exception) {
            // Port conflict — try an ephemeral port
            Log.w(TAG, "Port $port in use, trying ephemeral port: ${e.message}")
            try {
                val ss = ServerSocket(0)
                serverSocket = ss
                running = true
                port = ss.localPort
                executor.submit { acceptLoop(ss) }
                Log.d(TAG, "Relay server started on fallback port $port")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start relay server: ${e2.message}")
            }
        }
    }

    /**
     * Stop the server and clean up all active streams.
     */
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        activeStreams.clear()
        NetworkUtils.invalidateCache()
        executor.shutdownNow()
        Log.d(TAG, "Relay server stopped")
    }

    // ── Accept Loop ──────────────────────────────────────────────────

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val client = ss.accept()
                executor.submit { handleClient(client) }
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "Accept error: ${e.message}")
                }
            }
        }
    }

    // ── Stream Registration ──────────────────────────────────────────

    /**
     * Register a stream for relaying.
     *
     * @param link    The source [ExtractorLink].
     * @param headers Full header map (built by [CastHeaderManager]).
     * @return A [RelayUrl] with a clean URL the cast device can fetch.
     */
    fun registerStream(
        link: ExtractorLink,
        headers: Map<String, String>
    ): RelayUrl {
        val streamId = UUID.randomUUID().toString().take(12)
        val relay = RelayStream(
            originalUrl = link.url,
            headers = headers,
            mimeType = link.type.getMimeType(),
            type = link.type
        )
        activeStreams[streamId] = relay

        val localIp = getLocalIpAddress()
        val extension = when (link.type) {
            ExtractorLinkType.DASH -> ".mpd"
            ExtractorLinkType.M3U8 -> ".m3u8"
            else -> ".mp4"
        }

        val url = "http://$localIp:$port/relay/$streamId$extension"
        Log.d(TAG, "Registered stream $streamId → ${link.url.take(80)}...")

        return RelayUrl(
            url = url,
            mimeType = relay.effectiveMimeType,
            streamId = streamId
        )
    }

    /**
     * Remove a stream registration.
     */
    fun unregisterStream(streamId: String) {
        activeStreams.remove(streamId)
        Log.d(TAG, "Unregistered stream $streamId")
    }

    // ── HTTP Request Handling ────────────────────────────────────────

    /**
     * Handle an incoming HTTP client connection.
     * Uses raw InputStream to avoid BufferedReader consuming bytes past headers.
     */
    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // Read the request line
            val requestLine = readHttpLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1]
            Log.d(TAG, "INCOMING REQUEST: $method $path")

            // Read headers
            val requestHeaders = mutableMapOf<String, String>()
            while (true) {
                val headerLine = readHttpLine(input) ?: break
                if (headerLine.isBlank()) break
                val colonIdx = headerLine.indexOf(':')
                if (colonIdx > 0) {
                    val key = headerLine.substring(0, colonIdx).trim()
                    val value = headerLine.substring(colonIdx + 1).trim()
                    requestHeaders[key] = value
                    Log.d(TAG, "HEADER: $key: $value")
                }
            }

            // Handle CORS preflight
            if (method.equals("OPTIONS", ignoreCase = true)) {
                val corsHeaders = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                    "Access-Control-Allow-Headers" to "Origin, Accept, X-Requested-With, Content-Type, Access-Control-Request-Method, Access-Control-Request-Headers, Range",
                    "Access-Control-Max-Age" to "86400"
                )
                sendHttpResponse(output, 204, "", corsHeaders, null)
                return
            }

            // Route
            when {
                path.startsWith("/relay/") -> handleRelayRequest(path, requestHeaders, output)
                path.startsWith("/segment/") -> handleSegmentRequest(path, requestHeaders, output)
                path.startsWith("/variant/") -> handleVariantRequest(path, requestHeaders, output)
                else -> sendHttpResponse(output, 404, "Not Found", emptyMap(), null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Read a single HTTP line (terminated by \r\n) from raw InputStream.
     * Avoids BufferedReader which can read ahead and consume body bytes.
     */
    private fun readHttpLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) {
                input.read() // consume \n
                return sb.toString()
            }
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
        }
    }

    private fun handleRelayRequest(
        path: String,
        requestHeaders: Map<String, String>,
        output: OutputStream
    ) {
        // Path: /relay/{streamId}.ext
        val streamId = path.substringAfter("/relay/").substringBefore(".")

        val relay = activeStreams[streamId]
        if (relay == null) {
            sendHttpResponse(output, 404, "Not Found", emptyMap(), null)
            return
        }

        try {
            when {
                relay.type == ExtractorLinkType.VIDEO ->
                    proxyDirect(output, relay, requestHeaders)
                relay.type == ExtractorLinkType.DASH ->
                    proxyDashManifest(output, relay)
                relay.type == ExtractorLinkType.M3U8 ->
                    proxyHlsPlaylist(output, relay)
                else ->
                    proxyDirect(output, relay, requestHeaders)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling relay request: ${e.message}")
            try {
                sendHttpResponse(output, 500, "Internal Server Error", emptyMap(), null)
            } catch (_: Exception) {}
        }
    }

    /**
     * Handle requests for individual DASH/HLS segments.
     * Path: /segment/{streamId}?url={encoded_segment_url}
     */
    private fun handleSegmentRequest(
        path: String,
        requestHeaders: Map<String, String>,
        output: OutputStream
    ) {
        try {
            val streamId = path.substringAfter("/segment/").substringBefore("?")
            val query = if (path.contains("?")) path.substringAfter("?") else ""
            val segmentUrl = query.substringAfter("url=").let {
                URLDecoder.decode(it, "UTF-8")
            }

            val relay = activeStreams[streamId]
            if (relay == null || segmentUrl.isBlank()) {
                sendHttpResponse(output, 404, "Not Found", emptyMap(), null)
                return
            }

            // Proxy the segment with the same headers as the main stream
            proxyUrl(output, segmentUrl, relay.headers, requestHeaders)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling segment request: ${e.message}")
            try {
                sendHttpResponse(output, 500, "Internal Server Error", emptyMap(), null)
            } catch (_: Exception) {}
        }
    }

    /**
     * Handle requests for HLS variant (sub) playlists from a master playlist.
     * Path: /variant/{streamId}?url={encoded_variant_playlist_url}
     *
     * Fetches the variant playlist, rewrites its segment URLs through the relay,
     * and serves the rewritten playlist to the device.
     */
    private fun handleVariantRequest(
        path: String,
        @Suppress("UNUSED_PARAMETER") requestHeaders: Map<String, String>,
        output: OutputStream
    ) {
        try {
            val streamId = path.substringAfter("/variant/").substringBefore("?")
            val query = if (path.contains("?")) path.substringAfter("?") else ""
            val variantUrl = query.substringAfter("url=").let {
                URLDecoder.decode(it, "UTF-8")
            }

            val relay = activeStreams[streamId]
            if (relay == null || variantUrl.isBlank()) {
                sendHttpResponse(output, 404, "Not Found", emptyMap(), null)
                return
            }

            // Fetch the variant playlist with auth headers
            val conn = openConnection(variantUrl, relay.headers)
            conn.connect()
            val playlist = conn.inputStream.bufferedReader().readText()

            // Create a temporary relay with the variant URL as base for resolving relative segments
            val variantRelay = relay.copy(originalUrl = variantUrl)

            // Rewrite as a media playlist (segments only, not nested variants)
            val rewritten = rewriteHlsMediaPlaylist(playlist, variantRelay, streamId)

            val bytes = rewritten.toByteArray()
            val responseHeaders = mutableMapOf(
                "Content-Type" to "application/vnd.apple.mpegurl",
                "Access-Control-Allow-Origin" to "*",
                "Content-Length" to bytes.size.toString()
            )
            sendHttpResponseHeaders(output, 200, responseHeaders)
            output.write(bytes)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling variant request: ${e.message}")
            try {
                sendHttpResponse(output, 500, "Internal Server Error", emptyMap(), null)
            } catch (_: Exception) {}
        }
    }

    // ── Proxy Strategies ─────────────────────────────────────────────

    /**
     * Direct proxy: fetch from source with headers, pipe bytes to client.
     * Supports HTTP Range requests for seeking.
     */
    private fun proxyDirect(
        output: OutputStream,
        relay: RelayStream,
        requestHeaders: Map<String, String>
    ) {
        val conn = openConnection(relay.originalUrl, relay.headers)

        // Forward Range header from the client (for seeking)
        val rangeHeader = requestHeaders["Range"] ?: requestHeaders["range"]
        if (rangeHeader != null) {
            conn.setRequestProperty("Range", rangeHeader)
        }

        conn.connect()

        val responseCode = conn.responseCode
        val contentType = conn.contentType ?: relay.mimeType
        val contentLength = conn.contentLengthLong

        val responseHeaders = mutableMapOf<String, String>()
        responseHeaders["Content-Type"] = contentType
        conn.getHeaderField("Content-Range")?.let { responseHeaders["Content-Range"] = it }
        conn.getHeaderField("Accept-Ranges")?.let { responseHeaders["Accept-Ranges"] = it }
        responseHeaders["Access-Control-Allow-Origin"] = "*"
        if (contentLength >= 0) {
            responseHeaders["Content-Length"] = contentLength.toString()
        }

        sendHttpResponseHeaders(output, responseCode, responseHeaders)

        // Stream the data
        conn.inputStream.use { input ->
            input.copyTo(output, BUFFER_SIZE)
        }
        output.flush()
    }

    /**
     * DASH manifest proxy: fetch MPD, rewrite segment BaseURLs to relay URLs.
     */
    private fun proxyDashManifest(output: OutputStream, relay: RelayStream) {
        val conn = openConnection(relay.originalUrl, relay.headers)
        conn.connect()

        val mpd = conn.inputStream.bufferedReader().readText()
        val rewritten = rewriteDashManifest(mpd, relay)

        val bytes = rewritten.toByteArray()
        val responseHeaders = mutableMapOf(
            "Content-Type" to "application/dash+xml",
            "Access-Control-Allow-Origin" to "*",
            "Content-Length" to bytes.size.toString()
        )
        sendHttpResponseHeaders(output, 200, responseHeaders)
        output.write(bytes)
        output.flush()
    }

    /**
     * HLS playlist proxy: fetch m3u8, rewrite .ts segment URLs to relay URLs.
     */
    private fun proxyHlsPlaylist(output: OutputStream, relay: RelayStream) {
        val conn = openConnection(relay.originalUrl, relay.headers)
        conn.connect()

        val playlist = conn.inputStream.bufferedReader().readText()
        val rewritten = rewriteHlsPlaylist(playlist, relay)

        val bytes = rewritten.toByteArray()
        val responseHeaders = mutableMapOf(
            "Content-Type" to "application/vnd.apple.mpegurl",
            "Access-Control-Allow-Origin" to "*",
            "Content-Length" to bytes.size.toString()
        )
        sendHttpResponseHeaders(output, 200, responseHeaders)
        output.write(bytes)
        output.flush()
    }

    /**
     * HLS-to-Direct-Stream proxy for DLNA devices.
     *
     * Fetches the M3U8 playlist, resolves segment URLs, and pipes each
     * segment's bytes sequentially as a continuous MPEG-TS stream.
     * The TV sees a single long-running HTTP response with Content-Type: video/mp2t.
     *
     * Supports:
     * - Master playlists (picks highest bandwidth variant)
     * - Media playlists (iterates all segments)
     * - Live streams (re-polls playlist for new segments)
     */
    private fun proxyHlsAsDirectStream(output: OutputStream, relay: RelayStream) {
        Log.d(TAG, "HLS direct stream: fetching playlist ${relay.originalUrl.take(80)}")

        val conn = openConnection(relay.originalUrl, relay.headers)
        conn.connect()
        val playlist = conn.inputStream.bufferedReader().readText()

        // Resolve the media playlist URL
        val mediaPlaylistUrl: String
        // Only #EXT-X-STREAM-INF definitively indicates a master playlist.
        // #EXT-X-MEDIA can appear in both master and media playlists (alternate renditions).
        val isMaster = playlist.contains("#EXT-X-STREAM-INF")

        if (isMaster) {
            // Pick the highest bandwidth variant
            mediaPlaylistUrl = pickBestVariant(playlist, relay.originalUrl)
                ?: relay.originalUrl // fallback
            Log.d(TAG, "HLS direct stream: master playlist, picked variant: ${mediaPlaylistUrl.take(80)}")
        } else {
            mediaPlaylistUrl = relay.originalUrl
        }

        // Send response headers — chunked transfer since we don't know total length
        val responseHeaders = mutableMapOf(
            "Content-Type" to "video/mp2t",
            "Access-Control-Allow-Origin" to "*",
            "Transfer-Encoding" to "chunked",
            "Connection" to "keep-alive"
        )
        sendHttpResponseHeaders(output, 200, responseHeaders)

        // Track already-served segments for live streams
        val servedSegments = mutableSetOf<String>()
        var currentPlaylistUrl = mediaPlaylistUrl
        var consecutiveEmptyPolls = 0
        val maxEmptyPolls = 10 // Give up after 10 polls with no new segments

        while (running) {
            try {
                val mediaConn = openConnection(currentPlaylistUrl, relay.headers)
                mediaConn.connect()
                val mediaPlaylist = mediaConn.inputStream.bufferedReader().readText()

                val isLive = !mediaPlaylist.contains("#EXT-X-ENDLIST")
                val segments = parseHlsSegmentUrls(mediaPlaylist, currentPlaylistUrl)

                var newSegmentsFound = false
                for (segmentUrl in segments) {
                    if (segmentUrl in servedSegments) continue
                    servedSegments.add(segmentUrl)
                    newSegmentsFound = true

                    try {
                        val segConn = openConnection(segmentUrl, relay.headers)
                        segConn.connect()
                        segConn.inputStream.use { segInput ->
                            // Write chunked transfer encoding
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            while (segInput.read(buffer).also { bytesRead = it } != -1) {
                                // Chunked encoding: size in hex + CRLF + data + CRLF
                                val sizeHex = Integer.toHexString(bytesRead)
                                output.write("$sizeHex\r\n".toByteArray())
                                output.write(buffer, 0, bytesRead)
                                output.write("\r\n".toByteArray())
                                output.flush()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "HLS direct stream: segment fetch error: ${e.message}")
                        // If the TV disconnected (broken pipe), stop
                        if (e.message?.contains("Broken pipe") == true ||
                            e.message?.contains("Connection reset") == true) {
                            return
                        }
                    }
                }

                if (!isLive) {
                    // VOD: all segments served, send terminating chunk and done
                    output.write("0\r\n\r\n".toByteArray())
                    output.flush()
                    Log.d(TAG, "HLS direct stream: VOD complete, ${servedSegments.size} segments")
                    return
                }

                // Live: wait and re-poll for new segments
                if (newSegmentsFound) {
                    consecutiveEmptyPolls = 0
                } else {
                    consecutiveEmptyPolls++
                    if (consecutiveEmptyPolls >= maxEmptyPolls) {
                        Log.d(TAG, "HLS direct stream: no new segments after $maxEmptyPolls polls, ending")
                        output.write("0\r\n\r\n".toByteArray())
                        output.flush()
                        return
                    }
                }

                // Parse target duration for poll interval (default 5s)
                val targetDuration = Regex("#EXT-X-TARGETDURATION:(\\d+)")
                    .find(mediaPlaylist)?.groupValues?.get(1)?.toLongOrNull() ?: 5
                Thread.sleep(targetDuration * 1000 / 2) // Poll at half the target duration

            } catch (e: Exception) {
                Log.e(TAG, "HLS direct stream: playlist poll error: ${e.message}")
                if (e.message?.contains("Broken pipe") == true ||
                    e.message?.contains("Connection reset") == true) {
                    return
                }
                Thread.sleep(2000) // Back off on error
            }
        }
    }

    /**
     * Pick the highest bandwidth variant from an HLS master playlist.
     */
    private fun pickBestVariant(masterPlaylist: String, baseUrl: String): String? {
        var bestBandwidth = -1L
        var bestUrl: String? = null
        val lines = masterPlaylist.lines()

        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                val urlLine = lines.getOrNull(i + 1)?.trim()
                if (urlLine != null && !urlLine.startsWith("#") && urlLine.isNotBlank()) {
                    if (bw > bestBandwidth) {
                        bestBandwidth = bw
                        bestUrl = resolveUrl(baseUrl, urlLine)
                    }
                }
            }
        }
        return bestUrl
    }

    /**
     * Parse segment URLs from an HLS media playlist.
     */
    private fun parseHlsSegmentUrls(playlist: String, baseUrl: String): List<String> {
        return playlist.lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { resolveUrl(baseUrl, it.trim()) }
    }

    /**
     * Generic URL proxy — used for individual segments.
     */
    private fun proxyUrl(
        output: OutputStream,
        url: String,
        headers: Map<String, String>,
        requestHeaders: Map<String, String>
    ) {
        val conn = openConnection(url, headers)

        val rangeHeader = requestHeaders["Range"] ?: requestHeaders["range"]
        if (rangeHeader != null) {
            conn.setRequestProperty("Range", rangeHeader)
        }

        conn.connect()

        val responseCode = conn.responseCode
        val contentLength = conn.contentLengthLong

        val responseHeaders = mutableMapOf<String, String>()
        conn.contentType?.let { responseHeaders["Content-Type"] = it }
        responseHeaders["Access-Control-Allow-Origin"] = "*"
        conn.getHeaderField("Content-Range")?.let { responseHeaders["Content-Range"] = it }
        if (contentLength >= 0) {
            responseHeaders["Content-Length"] = contentLength.toString()
        }

        sendHttpResponseHeaders(output, responseCode, responseHeaders)
        conn.inputStream.use { input ->
            input.copyTo(output, BUFFER_SIZE)
        }
        output.flush()
    }

    // ── Raw HTTP Response ────────────────────────────────────────────

    private fun sendHttpResponseHeaders(
        output: OutputStream,
        statusCode: Int,
        headers: Map<String, String>
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            206 -> "Partial Content"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $statusCode $statusText\r\n")
        headers.forEach { (key, value) -> sb.append("$key: $value\r\n") }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray())
    }

    private fun sendHttpResponse(
        output: OutputStream,
        statusCode: Int,
        body: String,
        headers: Map<String, String>,
        @Suppress("UNUSED_PARAMETER") unused: Nothing?
    ) {
        val bodyBytes = body.toByteArray()
        val allHeaders = headers.toMutableMap()
        if (body.isNotEmpty()) {
            allHeaders["Content-Length"] = bodyBytes.size.toString()
            allHeaders["Content-Type"] = "text/plain"
        }
        sendHttpResponseHeaders(output, statusCode, allHeaders)
        output.write(bodyBytes)
        output.flush()
    }

    // ── Manifest Rewriting ───────────────────────────────────────────

    /**
     * Rewrite DASH MPD — handles BaseURL, SegmentTemplate, and SegmentList.
     *
     * Strategy: rewrite existing <BaseURL> tags, and for manifests without a
     * top-level BaseURL, inject one pointing to the relay segment handler.
     * This makes all relative template/segment URLs route through the relay.
     */
    private fun rewriteDashManifest(mpd: String, relay: RelayStream): String {
        val streamId = activeStreams.entries.find { it.value === relay }?.key ?: return mpd
        val localIp = getLocalIpAddress()
        val segmentBase = "http://$localIp:$port/segment/$streamId"
        val manifestBaseDir = relay.originalUrl.substringBeforeLast("/") + "/"

        var result = mpd

        // 1. Rewrite existing <BaseURL> tags
        val hasBaseUrl = result.contains("<BaseURL>")
        if (hasBaseUrl) {
            result = result.replace(Regex("<BaseURL>([^<]+)</BaseURL>")) { match ->
                val originalUrl = resolveUrl(relay.originalUrl, match.groupValues[1])
                val encoded = URLEncoder.encode(originalUrl, "UTF-8")
                "<BaseURL>$segmentBase?url=$encoded</BaseURL>"
            }
        } else {
            // 2. No BaseURL — inject one after <MPD ...> so relative URLs route through relay
            val encodedBase = URLEncoder.encode(manifestBaseDir, "UTF-8")
            val mpdRegex = Regex("(<MPD[^>]*>)")
            val mpdMatch = mpdRegex.find(result)
            if (mpdMatch != null) {
                val injection = "${mpdMatch.groupValues[1]}\n<BaseURL>$segmentBase?url=$encodedBase</BaseURL>"
                result = result.replaceRange(mpdMatch.range, injection)
            }
        }

        // 3. Rewrite SegmentURL media attributes (used by <SegmentList>)
        val segUrlRegex = Regex("<SegmentURL\\s+media=\"([^\"]+)\"")
        result = result.replace(segUrlRegex) { match ->
            val url = resolveUrl(relay.originalUrl, match.groupValues[1])
            val encoded = URLEncoder.encode(url, "UTF-8")
            "<SegmentURL media=\"$segmentBase?url=$encoded\""
        }

        return result
    }

    /**
     * Rewrite HLS playlist — detects master vs media and handles both.
     */
    private fun rewriteHlsPlaylist(playlist: String, relay: RelayStream): String {
        val streamId = activeStreams.entries.find { it.value === relay }?.key ?: return playlist
        // Only #EXT-X-STREAM-INF definitively indicates a master playlist.
        // #EXT-X-MEDIA can appear in both master and media playlists (alternate renditions).
        val isMaster = playlist.contains("#EXT-X-STREAM-INF")

        return if (isMaster) {
            rewriteHlsMasterPlaylist(playlist, relay, streamId)
        } else {
            rewriteHlsMediaPlaylist(playlist, relay, streamId)
        }
    }

    /**
     * Rewrite HLS master playlist — route variant playlist URLs through /variant/ handler.
     */
    private fun rewriteHlsMasterPlaylist(playlist: String, relay: RelayStream, streamId: String): String {
        val localIp = getLocalIpAddress()

        return playlist.lines().joinToString("\n") { line ->
            when {
                line.startsWith("#") -> {
                    // Rewrite URI= attributes in #EXT-X-MEDIA tags
                    if (line.contains("URI=\"")) {
                        line.replace(Regex("URI=\"([^\"]+)\"")) { match ->
                            val originalUrl = resolveUrl(relay.originalUrl, match.groupValues[1])
                            val encoded = URLEncoder.encode(originalUrl, "UTF-8")
                            "URI=\"http://$localIp:$port/variant/$streamId?url=$encoded\""
                        }
                    } else line
                }
                line.isBlank() -> line
                else -> {
                    // Variant playlist URL
                    val originalUrl = resolveUrl(relay.originalUrl, line.trim())
                    val encoded = URLEncoder.encode(originalUrl, "UTF-8")
                    "http://$localIp:$port/variant/$streamId?url=$encoded"
                }
            }
        }
    }

    /**
     * Rewrite HLS media playlist — route .ts/.m4s segment URLs through /segment/ handler.
     */
    private fun rewriteHlsMediaPlaylist(playlist: String, relay: RelayStream, streamId: String): String {
        val localIp = getLocalIpAddress()
        val segmentBase = "http://$localIp:$port/segment/$streamId"

        return playlist.lines().joinToString("\n") { line ->
            when {
                line.startsWith("#") -> {
                    // Rewrite URI= in #EXT-X-MAP or #EXT-X-KEY tags
                    if (line.contains("URI=\"")) {
                        line.replace(Regex("URI=\"([^\"]+)\"")) { match ->
                            val originalUrl = resolveUrl(relay.originalUrl, match.groupValues[1])
                            val encoded = URLEncoder.encode(originalUrl, "UTF-8")
                            "URI=\"$segmentBase?url=$encoded\""
                        }
                    } else line
                }
                line.isBlank() -> line
                else -> {
                    // Segment URL
                    val originalUrl = resolveUrl(relay.originalUrl, line.trim())
                    val encoded = URLEncoder.encode(originalUrl, "UTF-8")
                    "$segmentBase?url=$encoded"
                }
            }
        }
    }

    // ── Utilities ────────────────────────────────────────────────────

    private fun openConnection(url: String, headers: Map<String, String>): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
        return conn
    }

    /**
     * Resolve a potentially relative URL against a base URL.
     */
    private fun resolveUrl(base: String, relative: String): String {
        return if (relative.startsWith("http://") || relative.startsWith("https://")) {
            relative
        } else {
            try {
                URL(URL(base), relative).toString()
            } catch (_: Exception) {
                relative
            }
        }
    }

    /**
     * Get the phone's WiFi IP address via shared utility.
     */
    private fun getLocalIpAddress(): String {
        return NetworkUtils.getWifiIpAddress()
    }
}

// ── Data Classes ─────────────────────────────────────────────────────

/**
 * Internal representation of a stream being relayed.
 */
data class RelayStream(
    val originalUrl: String,
    val headers: Map<String, String>,
    val mimeType: String,
    val type: ExtractorLinkType
) {
    /**
     * The MIME type the device will see.
     * For DASH/HLS we serve the manifest type; segments get their own type.
     */
    val effectiveMimeType: String get() = mimeType
}

/**
 * A relay URL ready for the cast device.
 */
data class RelayUrl(
    /** The URL the device should fetch. */
    val url: String,
    /** MIME type for the stream / manifest. */
    val mimeType: String,
    /** Internal stream ID for management. */
    val streamId: String
)
