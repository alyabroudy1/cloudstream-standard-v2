package com.lagradost.cloudstream3.cast

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.cast.relay.StreamRelayServer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

/**
 * Decides how to handle HTTP headers when casting to different device types.
 *
 * DLNA devices cannot send custom headers (Referer, User-Agent, cookies).
 * CloudStream C2C devices can — both ends understand the full header set.
 *
 * This class determines whether a stream needs to be relayed through the phone
 * and prepares the link accordingly.
 */
object CastHeaderManager {

    /**
     * Build the full header map from an [ExtractorLink].
     *
     * Merges the link's explicit headers with the referer and a default User-Agent.
     * Used by the relay server when fetching from the source.
     */
    fun buildFullHeaders(link: ExtractorLink): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        // Default User-Agent — the source server expects a browser-like agent
        headers["User-Agent"] = USER_AGENT

        // Referer is often required to prevent hotlink protection
        if (link.referer.isNotBlank()) {
            headers["Referer"] = link.referer
        }

        // Merge any extra headers from the link (cookies, auth tokens, etc.)
        headers.putAll(link.headers)

        return headers
    }

    /**
     * Determine whether a link requires relaying for a given device type.
     *
     * Returns true when:
     * - The device type is DLNA (can't send custom headers) AND
     * - The link has meaningful headers beyond the default
     *
     * DLNA devices always use relay by default ([CastDevice.needsRelay]),
     * but this can also be used for fine-grained per-link decisions.
     */
    fun needsRelay(link: ExtractorLink, deviceType: CastDeviceType): Boolean {
        return when (deviceType) {
            CastDeviceType.DLNA -> true  // Always relay for DLNA — safest approach
            CastDeviceType.CLOUDSTREAM -> false  // C2C handles headers natively
            CastDeviceType.GOOGLE_CAST -> {
                // Smart relay: only relay when the link has meaningful headers
                // Chromecast can fetch plain URLs directly but can't send
                // Referer, cookies, or auth tokens
                link.referer.isNotBlank() || link.headers.isNotEmpty()
            }
        }
    }

    /**
     * Prepare a link for casting.
     *
     * If the device needs relay, registers the stream with [StreamRelayServer]
     * and returns the relay URL. Otherwise returns the original URL.
     *
     * @param link       The source link from the provider.
     * @param device     The target cast device.
     * @param relay      The relay server instance (from [CastSessionManager]).
     * @return A [CastReadyLink] with the URL the device should fetch.
     */
    fun prepareForCast(
        link: ExtractorLink,
        device: CastDevice,
        relay: StreamRelayServer
    ): CastReadyLink {
        if (!device.needsRelay && !needsRelay(link, device.type)) {
            // Device handles headers natively — pass the original URL
            return CastReadyLink(
                url = link.url,
                mimeType = link.type.getMimeType(),
                isRelayed = false,
                headers = buildFullHeaders(link)
            )
        }

        // Route through the phone — register with relay server
        // The relay rewrites HLS/DASH manifests to route segments through the relay,
        // preserving the playlist structure so the device can seek normally.
        // (Old directStreamMode converted HLS to continuous MPEG-TS, breaking seeking.)
        val relayUrl = relay.registerStream(link, buildFullHeaders(link))
        return CastReadyLink(
            url = relayUrl.url,
            mimeType = relayUrl.mimeType,
            isRelayed = true,
            headers = emptyMap(), // No headers needed — relay handles them
            streamId = relayUrl.streamId
        )
    }

    /**
     * A link ready for casting — either direct or relayed.
     */
    data class CastReadyLink(
        /** URL the device should fetch (original or relay). */
        val url: String,
        /** MIME type for the stream. */
        val mimeType: String,
        /** Whether this link is routed through the phone. */
        val isRelayed: Boolean,
        /** Headers to include (empty if relayed). */
        val headers: Map<String, String> = emptyMap(),
        /** Relay stream ID for cleanup. Null if not relayed. */
        val streamId: String? = null
    )
}
