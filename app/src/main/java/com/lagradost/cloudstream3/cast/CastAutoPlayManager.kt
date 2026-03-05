package com.lagradost.cloudstream3.cast

import com.lagradost.api.Log
import com.lagradost.cloudstream3.ui.result.getRealPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Observes the active cast session's position and triggers auto-play
 * of the next episode when the current one finishes.
 *
 * Design:
 * - Observes [CastSessionManager.activeSession] for session changes
 * - Attaches a [CastSessionListener] to track position/duration
 * - When position ≥ 95% of duration, loads the next episode
 * - Uses [CastSessionManager.castEpisodeContext] for episode list
 * - Self-manages lifecycle: starts on session connect, stops on disconnect
 *
 * This is a singleton — only one auto-play observer is needed app-wide.
 */
object CastAutoPlayManager {

    private const val TAG = "CastAutoPlayManager"
    private const val AUTO_PLAY_THRESHOLD = 0.95 // 95% of duration

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observerJob: Job? = null
    private var isLoadingNext = false

    /**
     * Start observing the active session for auto-play.
     * Safe to call multiple times — previous observer is cancelled.
     */
    fun startObserving() {
        observerJob?.cancel()
        observerJob = scope.launch {
            CastSessionManager.activeSession.collectLatest { session ->
                if (session == null) {
                    Log.d(TAG, "No active session, auto-play idle")
                    return@collectLatest
                }

                Log.d(TAG, "Observing session for auto-play: ${session.device.name}")
                isLoadingNext = false
                observeSessionPosition(session)
            }
        }
    }

    /**
     * Stop observing. Call on app shutdown.
     */
    fun stopObserving() {
        observerJob?.cancel()
        observerJob = null
        isLoadingNext = false
    }

    // ── Internal ─────────────────────────────────────────────────────

    private suspend fun observeSessionPosition(session: CastSession) {
        var lastPosition = 0L
        var lastDuration = 0L

        val listener = object : CastSessionListener {
            override fun onPositionChanged(positionMs: Long) {
                lastPosition = positionMs
            }

            override fun onDurationReceived(durationMs: Long) {
                lastDuration = durationMs
            }

            override fun onStateChanged(state: CastSessionState) {
                // Reset flag if playback restarted (e.g., user manually changed episode)
                if (state == CastSessionState.LOADING || state == CastSessionState.PLAYING) {
                    isLoadingNext = false
                }
            }

            override fun onDisconnected(reason: DisconnectReason) {
                isLoadingNext = false
            }
        }

        session.addListener(listener)

        try {
            // Poll position to check auto-play threshold
            while (scope.isActive) {
                delay(2000) // Check every 2 seconds

                if (isLoadingNext) continue
                if (lastDuration <= 0) continue
                if (session.state.value != CastSessionState.PLAYING) continue

                val progress = lastPosition.toDouble() / lastDuration.toDouble()
                if (progress >= AUTO_PLAY_THRESHOLD) {
                    loadNextEpisode(session)
                }
            }
        } finally {
            session.removeListener(listener)
        }
    }

    private fun loadNextEpisode(session: CastSession) {
        val episodeContext = CastSessionManager.castEpisodeContext.value ?: return
        if (!episodeContext.hasNext) {
            Log.d(TAG, "No next episode available")
            return
        }

        val nextEp = episodeContext.nextEpisode() ?: return
        isLoadingNext = true

        Log.d(TAG, "Auto-playing next episode: ${nextEp.name ?: "Episode ${nextEp.episode}"}")

        scope.launch {
            try {
                val device = session.device

                // Try fresh link loading first, fall back to cached links
                val linkLoader = CastSessionManager.linkLoader
                val freshResult = linkLoader?.loadLinksForEpisode(nextEp)

                val linksToUse = freshResult?.links ?: episodeContext.links
                val subsToUse = freshResult?.subs ?: episodeContext.subs

                val link = linksToUse.firstOrNull()
                if (link == null) {
                    Log.w(TAG, "No links available for auto-play, skipping")
                    isLoadingNext = false
                    return@launch
                }

                val readyLink = CastHeaderManager.prepareForCast(
                    link, device, CastSessionManager.relay
                )

                val subtitles = subsToUse.map { sub ->
                    CastSubtitle(
                        url = sub.url,
                        language = sub.languageCode ?: "",
                        label = sub.name,
                        mimeType = sub.mimeType
                    )
                }

                val payload = CastMediaPayload(
                    url = readyLink.url,
                    mimeType = readyLink.mimeType,
                    title = nextEp.name ?: nextEp.headerName,
                    subtitles = subtitles,
                    startPositionMs = nextEp.getRealPosition(),
                    headers = CastHeaderManager.buildFullHeaders(link),
                    isRelayed = readyLink.isRelayed,
                    metadata = if (readyLink.streamId != null) {
                        mapOf("relayStreamId" to readyLink.streamId)
                    } else emptyMap()
                )

                session.loadMedia(payload)

                // Update episode context to reflect new current episode
                val newIndex = episodeContext.currentIndex + 1
                CastSessionManager.updateEpisodeContext(
                    episodeContext.copy(
                        currentEpisode = nextEp,
                        currentIndex = newIndex,
                        links = linksToUse,
                        subs = subsToUse
                    )
                )

                Log.d(TAG, "Auto-play loaded: ${nextEp.name ?: "Episode ${nextEp.episode}"}")
            } catch (e: Exception) {
                Log.e(TAG, "Auto-play failed: ${e.message}")
                isLoadingNext = false
            }
        }
    }
}

