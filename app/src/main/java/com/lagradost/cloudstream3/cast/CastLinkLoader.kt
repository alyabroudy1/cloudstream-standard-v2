package com.lagradost.cloudstream3.cast

import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Abstraction for loading fresh links for an episode.
 *
 * Registered by [ResultViewModel2] when casting starts.
 * Consumed by [CastAutoPlayManager] and [CastControllerFragment]
 * to get fresh links for prev/next episode navigation.
 *
 * This avoids coupling the cast module to the ViewModel's internal
 * link-loading machinery.
 */
fun interface CastLinkLoader {
    /**
     * Load links and subtitles for the given episode.
     * Returns null if loading fails or is cancelled.
     */
    suspend fun loadLinksForEpisode(episode: ResultEpisode): LinkLoadResult?
}

/**
 * Result of loading links for an episode.
 */
data class LinkLoadResult(
    val links: List<ExtractorLink>,
    val subs: List<SubtitleData>
)
