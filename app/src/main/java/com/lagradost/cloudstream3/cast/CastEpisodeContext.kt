package com.lagradost.cloudstream3.cast

import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Tracks the current casting context — which episode is playing,
 * the full episode list, and cached links/subs for auto-play.
 *
 * Immutable snapshot — a new instance is created on each episode change.
 * Lived in [CastSessionManager.castEpisodeContext] (StateFlow).
 *
 * Used by:
 * - [CastAutoPlayManager] — to determine next episode
 * - [CastControllerFragment] — to display episode info and prev/next buttons
 */
data class CastEpisodeContext(
    /** The currently playing episode. */
    val currentEpisode: ResultEpisode,
    /** All episodes in the current season/dub group, ordered by index. */
    val allEpisodes: List<ResultEpisode>,
    /** Index of [currentEpisode] in [allEpisodes]. */
    val currentIndex: Int,
    /** Cached links for the current episode (for link selection fallback). */
    val links: List<ExtractorLink>,
    /** Cached subtitles for the current episode. */
    val subs: List<SubtitleData>,
    /** The poster URL for the show (for expanded controller). */
    val showPosterUrl: String? = null
) {
    /** Whether there is a next episode in the list. */
    val hasNext: Boolean get() = currentIndex < allEpisodes.lastIndex

    /** Whether there is a previous episode in the list. */
    val hasPrev: Boolean get() = currentIndex > 0

    /** Get the next episode, or null if at the end. */
    fun nextEpisode(): ResultEpisode? = allEpisodes.getOrNull(currentIndex + 1)

    /** Get the previous episode, or null if at the beginning. */
    fun prevEpisode(): ResultEpisode? = allEpisodes.getOrNull(currentIndex - 1)

    /** Display-friendly episode label (e.g., "S1:E3 - Episode Title"). */
    val displayLabel: String
        get() {
            val ep = currentEpisode
            val seasonPart = ep.season?.let { "S$it:" } ?: ""
            val epPart = if (ep.episode > 0) "E${ep.episode}" else ""
            val prefix = "$seasonPart$epPart".ifEmpty { "" }
            val name = ep.name ?: ep.headerName
            return if (prefix.isNotEmpty()) "$prefix - $name" else name
        }
}
