package com.lagradost.cloudstream3.ui.search

import android.widget.Toast
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup.handleDownloadClick
import com.lagradost.cloudstream3.ui.download.DownloadClickEvent
import com.lagradost.cloudstream3.ui.result.START_ACTION_LOAD_EP
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.VideoDownloadHelper

object SearchHelper {
    fun handleSearchClickCallback(callback: SearchClickCallback) {
        val card = callback.card
        when (callback.action) {
            SEARCH_ACTION_LOAD -> {
                // Direct play for live/IPTV items — bypass the details page
                if (card.type == TvType.Live) {
                    handleDirectPlay(card)
                } else {
                    loadSearchResult(card)
                }
            }

            SEARCH_ACTION_PLAY_FILE -> {
                if (card is DataStoreHelper.ResumeWatchingResult) {
                    val id = card.id
                    if (id == null) {
                        showToast(R.string.error_invalid_id, Toast.LENGTH_SHORT)
                    } else {
                        if (card.isFromDownload) {
                            handleDownloadClick(
                                DownloadClickEvent(
                                    DOWNLOAD_ACTION_PLAY_FILE,
                                    VideoDownloadHelper.DownloadEpisodeCached(
                                        name = card.name,
                                        poster = card.posterUrl,
                                        episode = card.episode ?: 0,
                                        season = card.season,
                                        id = id,
                                        parentId = card.parentId ?: return,
                                        score = null,
                                        description = null,
                                        cacheTime = System.currentTimeMillis(),
                                    )
                                )
                            )
                        } else {
                            loadSearchResult(card, START_ACTION_LOAD_EP, id)
                        }
                    }
                } else {
                    handleSearchClickCallback(
                        SearchClickCallback(SEARCH_ACTION_LOAD, callback.view, -1, callback.card)
                    )
                }
            }

            SEARCH_ACTION_SHOW_METADATA -> {
                (activity as? MainActivity?)?.apply {
                    loadPopup(callback.card)
                } ?: kotlin.run {
                    showToast(callback.card.name, Toast.LENGTH_SHORT)
                }
            }
        }
    }

    /**
     * Direct play for TvType.Live items (e.g., IPTV channels).
     *
     * 1. Stores a [DownloadHeaderCached] entry so the item appears in
     *    continue watching with proper name and poster.
     * 2. Navigates to the result page with START_ACTION_LOAD_EP to
     *    trigger immediate playback.
     *
     * Falls back to normal details page if the provider isn't found.
     */
    private fun handleDirectPlay(card: com.lagradost.cloudstream3.SearchResponse) {
        val parentId = card.id ?: card.url.hashCode()

        // Store header cache so continue watching shows name + poster
        setKey(
            DOWNLOAD_HEADER_CACHE,
            parentId.toString(),
            VideoDownloadHelper.DownloadHeaderCached(
                apiName = card.apiName,
                url = card.url,
                type = card.type ?: TvType.Live,
                name = card.name,
                poster = card.posterUrl,
                cacheTime = System.currentTimeMillis(),
                id = parentId
            )
        )

        // Navigate to result page with auto-play action
        loadResult(card.url, card.apiName, card.name, START_ACTION_LOAD_EP, 0)
    }
}