package com.lagradost.cloudstream3.cast.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.lagradost.api.Log
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.cast.CastSessionListener
import com.lagradost.cloudstream3.cast.CastSessionManager
import com.lagradost.cloudstream3.cast.CastSessionState
import com.lagradost.cloudstream3.cast.DisconnectReason
import com.lagradost.cloudstream3.ui.result.getRealPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Full-screen cast controller with seek bar, volume,
 * transport controls, and episode info.
 *
 * Protocol-agnostic — observes [CastSessionManager.activeSession]
 * and [CastSessionManager.castEpisodeContext] only.
 *
 * Uses [viewLifecycleOwner.lifecycleScope] for lifecycle-safe coroutines.
 *
 * Opened from [CastMiniController] tap; closed via close button or back press.
 */
class CastControllerFragment : Fragment() {

    companion object {
        private const val TAG = "CastController"
    }

    // Views
    private lateinit var closeBtn: ImageButton
    private lateinit var posterView: ImageView
    private lateinit var deviceNameView: TextView
    private lateinit var titleView: TextView
    private lateinit var positionView: TextView
    private lateinit var durationView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var prevBtn: ImageButton
    private lateinit var playPauseBtn: ImageButton
    private lateinit var nextBtn: ImageButton
    private lateinit var stopBtn: ImageButton
    private lateinit var volumeBar: SeekBar

    // State
    private var currentDurationMs = 0L
    private var currentPositionMs = 0L
    private var isSeeking = false
    private var sessionListener: CastSessionListener? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_cast_controller, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupControls()
        observeSession()
        observeEpisodeContext()
    }

    override fun onDestroyView() {
        detachSessionListener()
        super.onDestroyView()
    }

    // ── View Binding ─────────────────────────────────────────────────

    private fun bindViews(view: View) {
        closeBtn = view.findViewById(R.id.cast_controller_close)
        posterView = view.findViewById(R.id.cast_controller_poster)
        deviceNameView = view.findViewById(R.id.cast_controller_device_name)
        titleView = view.findViewById(R.id.cast_controller_title)
        positionView = view.findViewById(R.id.cast_controller_position)
        durationView = view.findViewById(R.id.cast_controller_duration)
        seekBar = view.findViewById(R.id.cast_controller_seek)
        prevBtn = view.findViewById(R.id.cast_controller_prev)
        playPauseBtn = view.findViewById(R.id.cast_controller_play_pause)
        nextBtn = view.findViewById(R.id.cast_controller_next)
        stopBtn = view.findViewById(R.id.cast_controller_stop)
        volumeBar = view.findViewById(R.id.cast_controller_volume)
    }

    // ── Controls Setup ───────────────────────────────────────────────

    private fun setupControls() {
        closeBtn.setOnClickListener {
            try {
                findNavController().popBackStack()
            } catch (_: Exception) {
                activity?.onBackPressedDispatcher?.onBackPressed()
            }
        }

        playPauseBtn.setOnClickListener {
            val session = CastSessionManager.activeSession.value ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                when (session.state.value) {
                    CastSessionState.PLAYING -> session.pause()
                    CastSessionState.PAUSED -> session.play()
                    else -> { /* ignore */ }
                }
            }
        }

        stopBtn.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                CastSessionManager.activeSession.value?.stop()
                CastSessionManager.disconnect()
            }
            try {
                findNavController().popBackStack()
            } catch (_: Exception) {
                activity?.onBackPressedDispatcher?.onBackPressed()
            }
        }

        prevBtn.setOnClickListener { navigateEpisode(-1) }
        nextBtn.setOnClickListener { navigateEpisode(1) }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentDurationMs > 0) {
                    val ms = (progress.toLong() * currentDurationMs) / 1000
                    positionView.text = formatTime(ms)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isSeeking = false
                val progress = sb?.progress ?: return
                if (currentDurationMs <= 0) return
                val seekMs = (progress.toLong() * currentDurationMs) / 1000
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    CastSessionManager.activeSession.value?.seek(seekMs)
                }
            }
        })

        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val vol = (sb?.progress ?: 100) / 100f
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    CastSessionManager.activeSession.value?.setVolume(vol)
                }
            }
        })
    }

    // ── Session Observing ────────────────────────────────────────────

    private fun observeSession() {
        viewLifecycleOwner.lifecycleScope.launch {
            CastSessionManager.activeSession.collectLatest { session ->
                if (session == null) {
                    try {
                        findNavController().popBackStack()
                    } catch (_: Exception) {
                        activity?.onBackPressedDispatcher?.onBackPressed()
                    }
                    return@collectLatest
                }

                deviceNameView.text = getString(R.string.casting_prefix_format, session.device.name)
                attachSessionListener(session)

                launch {
                    session.state.collectLatest { state -> updateStateUI(state) }
                }
            }
        }
    }

    private fun attachSessionListener(session: com.lagradost.cloudstream3.cast.CastSession) {
        detachSessionListener()
        sessionListener = object : CastSessionListener {
            override fun onPositionChanged(positionMs: Long) {
                currentPositionMs = positionMs
                activity?.runOnUiThread {
                    if (!isSeeking && currentDurationMs > 0) {
                        positionView.text = formatTime(positionMs)
                        seekBar.progress = ((positionMs * 1000) / currentDurationMs).toInt()
                    }
                }
            }

            override fun onDurationReceived(durationMs: Long) {
                currentDurationMs = durationMs
                activity?.runOnUiThread {
                    durationView.text = formatTime(durationMs)
                }
            }

            override fun onVolumeChanged(volume: Float) {
                activity?.runOnUiThread {
                    volumeBar.progress = (volume * 100).toInt()
                }
            }

            override fun onDisconnected(reason: DisconnectReason) {
                activity?.runOnUiThread {
                    try {
                        findNavController().popBackStack()
                    } catch (_: Exception) {
                        activity?.onBackPressedDispatcher?.onBackPressed()
                    }
                }
            }
        }
        session.addListener(sessionListener!!)
    }

    private fun detachSessionListener() {
        sessionListener?.let { listener ->
            CastSessionManager.activeSession.value?.removeListener(listener)
        }
        sessionListener = null
    }

    // ── Episode Context Observing ────────────────────────────────────

    private fun observeEpisodeContext() {
        viewLifecycleOwner.lifecycleScope.launch {
            CastSessionManager.castEpisodeContext.collectLatest { ctx ->
                if (ctx == null) return@collectLatest

                titleView.text = ctx.displayLabel

                // Poster
                ctx.showPosterUrl?.let { url ->
                    with(com.lagradost.cloudstream3.utils.ImageLoader) {
                        posterView.loadImage(url)
                    }
                    posterView.visibility = View.VISIBLE
                } ?: run {
                    posterView.visibility = View.GONE
                }

                // Next/prev availability
                prevBtn.isEnabled = ctx.hasPrev
                prevBtn.alpha = if (ctx.hasPrev) 1f else 0.3f
                nextBtn.isEnabled = ctx.hasNext
                nextBtn.alpha = if (ctx.hasNext) 1f else 0.3f
            }
        }
    }

    // ── Episode Navigation ───────────────────────────────────────────

    private fun navigateEpisode(direction: Int) {
        val ctx = CastSessionManager.castEpisodeContext.value ?: return
        val targetEp = if (direction > 0) ctx.nextEpisode() else ctx.prevEpisode()
        targetEp ?: return

        val session = CastSessionManager.activeSession.value ?: return
        val device = session.device

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Try fresh link loading first, fall back to cached links
                val linkLoader = CastSessionManager.linkLoader
                val freshResult = linkLoader?.loadLinksForEpisode(targetEp)

                val linksToUse = freshResult?.links ?: ctx.links
                val subsToUse = freshResult?.subs ?: ctx.subs

                val link = linksToUse.firstOrNull() ?: return@launch
                val readyLink = com.lagradost.cloudstream3.cast.CastHeaderManager.prepareForCast(
                    link, device, CastSessionManager.relay
                )

                val subtitles = subsToUse.map { sub ->
                    com.lagradost.cloudstream3.cast.CastSubtitle(
                        url = sub.url,
                        language = sub.languageCode ?: "",
                        label = sub.name,
                        mimeType = sub.mimeType
                    )
                }

                val payload = com.lagradost.cloudstream3.cast.CastMediaPayload(
                    url = readyLink.url,
                    mimeType = readyLink.mimeType,
                    title = targetEp.name ?: targetEp.headerName,
                    subtitles = subtitles,
                    startPositionMs = targetEp.getRealPosition(),
                    headers = com.lagradost.cloudstream3.cast.CastHeaderManager.buildFullHeaders(link),
                    isRelayed = readyLink.isRelayed,
                    metadata = if (readyLink.streamId != null) {
                        mapOf("relayStreamId" to readyLink.streamId)
                    } else emptyMap()
                )

                session.loadMedia(payload)

                val newIndex = ctx.currentIndex + direction
                CastSessionManager.updateEpisodeContext(
                    ctx.copy(
                        currentEpisode = targetEp,
                        currentIndex = newIndex,
                        links = linksToUse,
                        subs = subsToUse
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Episode navigation failed: ${e.message}")
            }
        }
    }

    // ── UI Updates ───────────────────────────────────────────────────

    private fun updateStateUI(state: CastSessionState) {
        when (state) {
            CastSessionState.PLAYING -> {
                playPauseBtn.setImageResource(R.drawable.video_pause)
                playPauseBtn.isEnabled = true
            }
            CastSessionState.PAUSED -> {
                playPauseBtn.setImageResource(R.drawable.ic_baseline_play_arrow_24)
                playPauseBtn.isEnabled = true
            }
            CastSessionState.BUFFERING, CastSessionState.LOADING -> {
                playPauseBtn.isEnabled = false
            }
            else -> { /* keep current state */ }
        }
    }

    // ── Utilities ────────────────────────────────────────────────────

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
}
