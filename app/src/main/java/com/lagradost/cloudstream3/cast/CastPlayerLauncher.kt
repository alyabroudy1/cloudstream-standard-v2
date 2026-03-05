package com.lagradost.cloudstream3.cast

import android.app.Activity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.NoVideoGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * An [IGenerator] that wraps a [CastMediaPayload] for the internal player.
 *
 * Used by the C2C receiver to launch the player with received media.
 * Converts the payload's URL, headers, and subtitles into the
 * [ExtractorLink] + [SubtitleData] format that [GeneratorPlayer] expects.
 */
class CastMediaLinkGenerator(
    private val payload: CastMediaPayload
) : NoVideoGenerator() {

    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean
    ): Boolean {
        // Build ExtractorLink from the payload
        val linkType = when {
            payload.mimeType.contains("dash") -> ExtractorLinkType.DASH
            payload.mimeType.contains("mpegurl") || payload.mimeType.contains("m3u8") -> ExtractorLinkType.M3U8
            else -> INFER_TYPE
        }

        val link = newExtractorLink(
            source = "CloudStream Cast",
            name = payload.title ?: "Cast Media",
            url = payload.url,
            type = linkType
        ) {
            payload.headers["Referer"]?.let { this.referer = it }
            this.quality = Qualities.Unknown.value
            this.headers = payload.headers
        }

        callback(link to null)

        // Convert subtitles
        for (sub in payload.subtitles) {
            subtitleCallback(
                SubtitleData(
                    originalName = sub.label,
                    nameSuffix = "",
                    url = sub.url,
                    origin = SubtitleOrigin.URL,
                    mimeType = sub.mimeType,
                    headers = emptyMap(),
                    languageCode = sub.language
                )
            )
        }

        return true
    }
}

/**
 * Launches the internal player with a [CastMediaPayload].
 *
 * Call from the main thread (or use `runOnUiThread`).
 */
object CastPlayerLauncher {
    private const val TAG = "CastPlayerLauncher"

    /**
     * Launch the internal player to play a received cast payload.
     *
     * @param activity Current activity (must be running).
     * @param payload  The media to play.
     */
    fun launch(activity: Activity, payload: CastMediaPayload) {
        val generator = CastMediaLinkGenerator(payload)
        val bundle = GeneratorPlayer.newInstance(generator)

        Log.d(TAG, "Launching player for: ${payload.title}")
        activity.navigate(R.id.global_to_navigation_player, bundle)
    }
}
