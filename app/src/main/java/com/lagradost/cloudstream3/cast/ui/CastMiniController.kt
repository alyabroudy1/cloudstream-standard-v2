package com.lagradost.cloudstream3.cast.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.cast.CastSessionManager
import com.lagradost.cloudstream3.cast.CastSessionState
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * A compact mini controller that shows the current cast session state.
 * Built programmatically - no XML layout required.
 */
class CastMiniController @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val deviceNameView: TextView
    private val mediaTitleView: TextView
    private val stateIconView: ImageView
    private val playPauseButton: ImageButton
    private val stopButton: ImageButton
    private val progressBar: ProgressBar

    init {
        visibility = GONE
        
        val linearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(12.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }
        addView(linearLayout, LayoutParams(LayoutParams.MATCH_PARENT, 56.dpToPx()))

        stateIconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx()).apply {
                marginEnd = 12.dpToPx()
            }
            setImageResource(R.drawable.ic_baseline_cast_connected_24)
            contentDescription = "Cast"
        }
        linearLayout.addView(stateIconView)

        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        linearLayout.addView(textContainer)

        deviceNameView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textContainer.addView(deviceNameView)

        mediaTitleView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textContainer.addView(mediaTitleView)

        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(0, 3.dpToPx(), 0.5f).apply {
                marginStart = 8.dpToPx()
                marginEnd = 8.dpToPx()
            }
            max = 100
        }
        linearLayout.addView(progressBar)

        playPauseButton = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx())
            setImageResource(R.drawable.video_pause)
            background = null
            isEnabled = false
        }
        linearLayout.addView(playPauseButton)

        stopButton = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx())
            setImageResource(R.drawable.baseline_stop_24)
            background = null
        }
        linearLayout.addView(stopButton)

        setupControls()
        observeSession()

        // Tap to open expanded cast controller
        setOnClickListener {
            try {
                val activity = (context as? android.app.Activity) ?: return@setOnClickListener
                activity.navigate(R.id.global_to_navigation_cast_controller)
            } catch (e: Exception) {
                com.lagradost.api.Log.w("CastMiniController", "Nav failed: ${e.message}")
            }
        }
    }

    private fun Int.dpToPx(): Int = (this * context.resources.displayMetrics.density).toInt()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }

    private fun setupControls() {
        playPauseButton.setOnClickListener {
            val session = CastSessionManager.activeSession.value ?: return@setOnClickListener
            scope.launch(Dispatchers.IO) {
                when (session.state.value) {
                    CastSessionState.PLAYING -> session.pause()
                    CastSessionState.PAUSED -> session.play()
                    else -> { /* ignore */ }
                }
            }
        }

        stopButton.setOnClickListener {
            val session = CastSessionManager.activeSession.value ?: return@setOnClickListener
            scope.launch(Dispatchers.IO) {
                session.stop()
                CastSessionManager.disconnect()
            }
        }
    }

    private fun observeSession() {
        scope.launch {
            CastSessionManager.activeSession.collectLatest { session ->
                if (session == null) {
                    visibility = GONE
                    return@collectLatest
                }

                visibility = VISIBLE
                deviceNameView.text = session.device.name

                launch {
                    session.state.collectLatest { state ->
                        updateStateUI(state)
                    }
                }
            }
        }
    }

    private fun updateStateUI(state: CastSessionState) {
        when (state) {
            CastSessionState.IDLE -> {
                visibility = GONE
            }
            CastSessionState.CONNECTING -> {
                stateIconView.setImageResource(R.drawable.ic_baseline_cast_connected_24)
                mediaTitleView.text = context.getString(R.string.loading)
                progressBar.isIndeterminate = true
                playPauseButton.isEnabled = false
            }
            CastSessionState.CONNECTED -> {
                stateIconView.setImageResource(R.drawable.ic_baseline_cast_connected_24)
                mediaTitleView.text = "Connected"
                progressBar.isIndeterminate = false
                playPauseButton.isEnabled = false
            }
            CastSessionState.LOADING -> {
                mediaTitleView.text = context.getString(R.string.loading)
                progressBar.isIndeterminate = true
                playPauseButton.isEnabled = false
            }
            CastSessionState.PLAYING -> {
                playPauseButton.setImageResource(R.drawable.video_pause)
                playPauseButton.isEnabled = true
                progressBar.isIndeterminate = false
            }
            CastSessionState.PAUSED -> {
                playPauseButton.setImageResource(R.drawable.ic_baseline_play_arrow_24)
                playPauseButton.isEnabled = true
                progressBar.isIndeterminate = false
            }
            CastSessionState.BUFFERING -> {
                progressBar.isIndeterminate = true
                playPauseButton.isEnabled = false
            }
            CastSessionState.RELAYING -> {
                mediaTitleView.text = "Streaming..."
                progressBar.isIndeterminate = true
                playPauseButton.isEnabled = false
            }
            CastSessionState.DISCONNECTED -> {
                visibility = GONE
            }
            CastSessionState.ERROR -> {
                mediaTitleView.text = "Error"
                playPauseButton.isEnabled = false
            }
        }
    }
}
