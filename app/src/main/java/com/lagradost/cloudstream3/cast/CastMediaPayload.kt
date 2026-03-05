package com.lagradost.cloudstream3.cast

/**
 * Protocol-agnostic description of media to cast.
 *
 * Created by Actions, consumed by [CastSession.loadMedia].
 * Contains everything a receiver needs to play the stream.
 */
data class CastMediaPayload(
    /** Stream URL — may be the original or a relay URL from [StreamRelayServer]. */
    val url: String,
    /** MIME type ("video/mp4", "application/dash+xml", "application/x-mpegURL"). */
    val mimeType: String,
    /** Display title (shown on receiver UI if supported). */
    val title: String? = null,
    /** Poster/thumbnail URL. */
    val posterUrl: String? = null,
    /** Subtitles to send alongside the video. */
    val subtitles: List<CastSubtitle> = emptyList(),
    /** Resume position in milliseconds. Null = start from beginning. */
    val startPositionMs: Long? = null,
    /**
     * HTTP headers for the stream URL.
     * Only meaningful for targets that support custom headers (C2C).
     * DLNA targets ignore this — the relay handles headers instead.
     */
    val headers: Map<String, String> = emptyMap(),
    /** Whether the stream is being relayed through the phone. */
    val isRelayed: Boolean = false,
    /** Arbitrary extra data for protocol-specific needs. */
    val metadata: Map<String, String> = emptyMap()
)

/**
 * A subtitle track to send to the receiver.
 */
data class CastSubtitle(
    /** URL to the subtitle file. May be relayed. */
    val url: String,
    /** Language tag (e.g. "en", "ar"). */
    val language: String,
    /** Display label (e.g. "English", "العربية"). */
    val label: String = language,
    /** MIME type ("text/vtt", "application/x-subrip", "text/srt"). */
    val mimeType: String = "text/vtt"
)
